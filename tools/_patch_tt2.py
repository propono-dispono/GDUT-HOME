# -*- coding: utf-8 -*-
"""课表请求改造：用 CookieManager 累积会话，并按 主框架→列表页→周课表 的顺序预热。"""

import io
import os
import re

P = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "xiaojii-server",
                 "src", "main", "java", "com", "gdut", "xiaojii",
                 "service", "impl", "EduDataServiceImpl.java")
s = io.open(P, encoding="utf-8").read()

if "import java.net.CookieManager;" not in s:
    s = s.replace("import java.net.URI;",
                  "import java.net.CookieManager;\nimport java.net.HttpCookie;\nimport java.net.URI;", 1)

start = s.index("    /** 教务页面都要求带 Referer，否则返回「你没有该权限」 */")
end = s.index("    /** 同一格（星期 + 起止节）多门课", start)

NEW = r'''    /**
     * 取教务页面 HTML。
     * 注意：周课表页面不是"请求一次就能拿到数据"，必须先访问主框架和列表页建立会话上下文，
     * 且请求过程中服务器会下发新的 Cookie，所以这里用 CookieManager 累积而不是固定 header。
     */
    private String jxfwHtml(String path, String referer) {
        Long userId = UserContext.requireUserId();
        String raw = redis.opsForValue().get(SESSION_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "请先登录教务");
        }
        String cookies;
        try {
            cookies = JSON.readTree(raw).path("cookies").asText("");
        } catch (Exception e) {
            throw BizException.of(ResultCode.SERVER_ERROR, "教务会话解析失败，请重新登录");
        }

        CookieManager jar = new CookieManager();
        for (String part : cookies.split(";")) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                String n = part.substring(0, idx).trim();
                String v = part.substring(idx + 1).trim();
                if (!n.isEmpty()) {
                    try {
                        jar.getCookieStore().add(URI.create(eduBaseUrl), new HttpCookie(n, v));
                    } catch (Exception ignore) {
                        // 单个 cookie 异常不影响整体
                    }
                }
            }
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .cookieHandler(jar)
                    .sslContext(EduAuthServiceImpl.eduSslContext())
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            String home = eduBaseUrl + "/login!welcome.action";
            String main = eduBaseUrl + "/xsgrkbcx!xsgrkbMain.action";
            fetch(client, main, home);
            fetch(client, eduBaseUrl + "/xsgrkbcx!getXsgrbkList.action", main);
            String body = fetch(client, eduBaseUrl + path, referer);

            if (body.isBlank() || body.contains("非法访问") || body.contains("你没有该权限")
                    || body.contains("请输入密码")) {
                log.warn("[教务] 课表页异常 path={} len={}", path, body.length());
                throw BizException.of(ResultCode.PARAM_ERROR, "教务会话已失效，请重新登录教务");
            }
            if (!body.contains("fc-event")) {
                log.warn("[教务] 课表页没有课程块 path={} len={} head={}", path, body.length(),
                        body.length() > 160 ? body.substring(0, 160).replace("\n", " ") : body);
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[教务] 课表请求失败 path={}", path, e);
            throw BizException.of(ResultCode.SERVER_ERROR, "课表获取失败，请稍后重试");
        }
    }

    private String fetch(HttpClient client, String url, String referer) throws Exception {
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", UA)
                        .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Referer", referer)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.body() == null ? "" : r.body();
    }

'''
s = s[:start] + NEW + s[end:]
io.open(P, "w", encoding="utf-8").write(s)
print("patched, size =", len(s))
