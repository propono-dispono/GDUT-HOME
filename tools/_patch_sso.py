# -*- coding: utf-8 -*-
"""把 EduAuthServiceImpl.login 从「教务直连 + 图形验证码」改为「统一认证 SSO」。"""

import io
import os
import re

P = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                 "xiaojii-server", "src", "main", "java", "com", "gdut", "xiaojii",
                 "service", "impl", "EduAuthServiceImpl.java")

src = io.open(P, encoding="utf-8").read()

# ---------- 1. 补 import ----------
NEED = [
    ("import javax.crypto.spec.IvParameterSpec;", "import javax.crypto.spec.SecretKeySpec;"),
    ("import java.util.LinkedHashMap;", "import java.util.HexFormat;"),
    ("import java.util.Map;", "import java.util.HexFormat;"),
    ("import java.util.concurrent.ThreadLocalRandom;", "import java.util.Map;"),
    ("import java.util.regex.Matcher;", "import java.util.Map;"),
    ("import java.util.regex.Pattern;", "import java.util.Map;"),
]
for imp, after in NEED:
    if imp in src:
        continue
    if after in src:
        src = src.replace(after, after + "\n" + imp, 1)
    else:
        src = src.replace("import java.util.Base64;", "import java.util.Base64;\n" + imp, 1)

# ---------- 2. 替换 login 方法 ----------
NEW_LOGIN = '''    @Override
    public EduStatusVO login(EduLoginReq req) {
        Long userId = UserContext.requireUserId();
        // 走统一认证（CAS）：教务子系统的 /new/login 是另一套密码，不可用
        String cookies = ssoLogin(req.getAccount(), req.getPassword());
        String masked = mask(req.getAccount());

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("cookies", cookies);
        session.put("accountMasked", masked);
        session.put("savedAt", LocalDateTime.now().format(MINUTE));
        try {
            redis.opsForValue().set(SESSION_PREFIX + userId, JSON.writeValueAsString(session), SESSION_TTL);
        } catch (Exception e) {
            log.error("[教务] 会话写入失败 userId={}", userId, e);
            throw BizException.of(ResultCode.SERVER_ERROR, "教务会话保存失败");
        }

        if (Boolean.TRUE.equals(req.getRemember())) {
            saveCredential(userId, req.getAccount(), req.getPassword(), masked);
        } else {
            credentialMapper.deleteById(userId);
        }
        log.info("[教务] SSO 登录成功 userId={} account={}", userId, masked);
        return status();
    }

'''

start = src.index("    @Override\n    public EduStatusVO login(EduLoginReq req) {")
end = src.index("    @Override", start + 10)
src = src[:start] + NEW_LOGIN + src[end:]

# ---------- 3. 插入 SSO 私有方法 ----------
ANCHOR = "    private HttpClient newClient(CookieManager cookies) {"
SSO = '''    /* ==================== 统一认证（CAS） ==================== */

    private static final String AUTH_BASE = "https://authserver.gdut.edu.cn";
    private static final String SSO_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";

    /**
     * 广工统一认证登录，返回教务站的 Cookie 头。
     * 流程：教务 ssoLogin → 认证中心登录取 salt/execution → 提交账号密码（AES-CBC）→ 拿着 ticket 回教务建会话。
     */
    private String ssoLogin(String account, String password) {
        try {
            // 教务侧会话（先预热，避免 Tomcat 用 URL 重写会话）
            CookieManager jxJar = new CookieManager();
            HttpClient jxClient = newClient(jxJar);
            jxClient.send(request(base() + "/login!welcome.action").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            HttpResponse<String> entry = jxClient.send(
                    request(base() + "/new/ssoLogin").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String loginUrl = entry.headers().firstValue("Location").orElse("");
            if (loginUrl.isBlank()) {
                throw BizException.of(ResultCode.SERVER_ERROR, "教务 SSO 入口异常，请稍后重试");
            }

            // 认证中心单独一个会话：两个域都有 JSESSIONID，共用会互相覆盖
            CookieManager authJar = new CookieManager();
            HttpClient authClient = newClient(authJar);
            String html = authClient.send(request(loginUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();

            String salt = firstMatch(html, "id=\\"pwdEncryptSalt\\"[^>]*value=\\"([^\\"]+)\\"");
            String execution = firstMatch(html, "id=\\"execution\\"[^>]*name=\\"execution\\"[^>]*value=\\"([^\\"]+)\\"");
            if (execution == null) {
                execution = firstMatch(html, "name=\\"execution\\"[^>]*id=\\"execution\\"[^>]*value=\\"([^\\"]+)\\"");
            }
            if (salt == null || salt.length() != 16) {
                throw BizException.of(ResultCode.SERVER_ERROR, "认证中心页面结构变化，请稍后重试");
            }
            if (execution == null) {
                execution = "e1s1";
            }

            String form = "username=" + enc(account)
                    + "&password=" + enc(ssoEncrypt(password, salt))
                    + "&lt=&dllt=generalLogin&cllt=userNameLogin"
                    + "&execution=" + enc(execution)
                    + "&_eventId=submit&rmShown=1";
            HttpResponse<String> res = authClient.send(
                    request(loginUrl)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Referer", loginUrl)
                            .header("Origin", AUTH_BASE)
                            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String ticketUrl = res.headers().firstValue("Location").orElse("");
            if (!ticketUrl.contains("ticket=")) {
                throw BizException.of(ResultCode.PARAM_ERROR, "学号或密码不正确");
            }

            // 教务侧收票：可能出现多次 302（含 ssoLogin;jsessionid=... 的会话更新）
            String cur = ticketUrl;
            String body = "";
            for (int i = 0; i < 8; i++) {
                HttpResponse<String> r = jxClient.send(request(cur).GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() >= 300 && r.statusCode() < 400) {
                    String next = r.headers().firstValue("Location").orElse("");
                    if (next.isBlank()) {
                        break;
                    }
                    cur = next.startsWith("http") ? next : base() + (next.startsWith("/") ? "" : "/") + next;
                    continue;
                }
                body = r.body();
                break;
            }
            // 登录成功后地址仍是 login!welcome.action，必须按页面内容判断
            if (!body.contains("注销")) {
                body = jxClient.send(request(base() + "/").GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            }
            if (!body.contains("注销") && !body.contains("登录时间")) {
                throw BizException.of(ResultCode.SERVER_ERROR, "教务会话建立失败，请稍后重试");
            }

            String cookies = cookieHeader(jxJar);
            log.info("[教务] SSO 会话建立 cookieNames={}", cookieNames(cookies));
            return cookies;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[教务] SSO 登录异常", e);
            throw BizException.of(ResultCode.SERVER_ERROR, "教务登录失败，请稍后重试");
        }
    }

    private static String firstMatch(String text, String regex) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 官方 encrypt.js：64 位随机前缀 + AES-CBC(key=iv=salt)，输出 Base64。 */
    private String ssoEncrypt(String password, String salt) throws Exception {
        String data = randomStr(64) + password;
        byte[] key = salt.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String randomStr(int n) {
        StringBuilder sb = new StringBuilder(n);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            sb.append(SSO_CHARS.charAt(r.nextInt(SSO_CHARS.length())));
        }
        return sb.toString();
    }

'''
assert ANCHOR in src, "锚点没找到"
src = src.replace(ANCHOR, SSO + ANCHOR, 1)

io.open(P, "w", encoding="utf-8").write(src)
print("patched ok, size =", len(src))
print("ssoLogin:", "private String ssoLogin(" in src)
print("old login removed:", "req.getCaptcha()" not in src)
