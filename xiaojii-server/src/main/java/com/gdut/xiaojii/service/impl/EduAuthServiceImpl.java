package com.gdut.xiaojii.service.impl;

import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.EduSessionStore;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.dto.req.EduLoginReq;
import com.gdut.xiaojii.dto.vo.EduCaptchaVO;
import com.gdut.xiaojii.dto.vo.EduStatusVO;
import com.gdut.xiaojii.service.EduAuthService;
import com.gdut.xiaojii.support.edu.EduCredentialStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广工教务登录实现。
 *
 * 流程：教务 ssoLogin 入口 → 302 到统一认证中心 → 抠 salt 与 execution →
 * 提交账号密码（AES-CBC，key 与 iv 都是 salt）→ 拿 ticket 回教务建会话 →
 * 把教务 Cookie 存进 Redis。
 *
 * 【凭据策略：可选保存密码（2026-11-12 改版）】
 * 当前实现：前端登录时若勾选"记住账号"（remember=true），后端把「学号+密码」用 AES-GCM 加密后
 * 存进 edu_credential 表，有效期 30 天滑动 / 90 天硬顶；Cookie 失效时用它自动重登。
 * 没勾选则不保存，行为等同旧版（只存 Cookie，12 小时滑动 / 24 小时硬顶）。
 *
 * 【为什么从"绝不存"改成"可选存"】
 * 旧方案只存 Cookie，用户每天至少重登一次，体验太差（用户明确要求 30 天免登录）。
 * 而教务只给两种凭证：Cookie（十几小时）和密码（长期），要撑 30 天只能存密码，没有第三条路。
 * 代价必须清楚：
 *   一、拖库风险 —— 密码泄漏不可撤销。缓解：AES-GCM 加密 + 密钥放环境变量与库分离 + 90 天硬顶；
 *   二、隐私声明 —— 旧版声明写的是「密码只在本次登录使用」，现在必须同步改成
 *       「可加密保存，用于 30 天内自动刷新课表，可随时解除绑定」，否则就是虚假声明；
 *   三、必须由用户主动勾选（不能默认开启），并在绑定页明确告知用途。
 * 这三条里第二条是合规红线，改代码的同时必须改小程序隐私协议与绑定页文案。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EduAuthServiceImpl implements EduAuthService {

    // 未使用：只服务已废弃的验证码接口（/app/edu/captcha）
    private static final String CAPTCHA_PREFIX = "edu:captcha:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final String AUTH_BASE = "https://authserver.gdut.edu.cn";
    private static final String SSO_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + " (KHTML, like Gecko) Chrome/128.0 Safari/537.36";

    /** 教务证书链里有 GDCA 中间证书，JDK 默认信任库没有；这里叠加一个专用信任库 */
    private static volatile SSLContext EDU_SSL_CONTEXT;

    private final StringRedisTemplate redis;
    private final EduSessionStore sessionStore;
    // 加密凭证仓库：登录时按 remember 决定是否保存密码，Cookie 失效时由 EduDataServiceImpl 用它自动重登
    private final EduCredentialStore credentialStore;

    @Value("${edu.base-url:https://jxfw.gdut.edu.cn}")
    private String baseUrl;

    /** 未使用：登录走 SSO 后不需要图形验证码，保留此接口仅为兼容旧客户端。 */
    @Override
    public EduCaptchaVO captcha() {
        try {
            CookieManager cookies = new CookieManager();
            HttpClient client = newClient(cookies);

            HttpResponse<String> page = client.send(
                    request(base() + "/login!welcome.action").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (page.statusCode() != 200) {
                throw BizException.of(ResultCode.SERVER_ERROR, "教务系统暂时无法访问");
            }

            HttpResponse<byte[]> img = client.send(
                    request(base() + "/yzm?d=" + System.currentTimeMillis())
                            .header("Referer", base() + "/login!welcome.action")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (img.statusCode() != 200 || img.body() == null || img.body().length == 0) {
                throw BizException.of(ResultCode.SERVER_ERROR, "验证码获取失败，请重试");
            }

            String sessionId = UUID.randomUUID().toString().replace("-", "");
            String cookieHeader = cookieHeader(cookies);
            redis.opsForValue().set(CAPTCHA_PREFIX + sessionId, cookieHeader, CAPTCHA_TTL);
            return new EduCaptchaVO(sessionId,
                    Base64.getEncoder().encodeToString(img.body()), CAPTCHA_TTL.toSeconds());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[教务] 取验证码失败", e);
            throw BizException.of(ResultCode.SERVER_ERROR, "教务系统暂时无法访问");
        }
    }

    @Override
    /** 登录 */
    public EduStatusVO login(EduLoginReq req) {
        Long userId = UserContext.requireUserId();
        // 走统一认证（CAS）：教务子系统的 /new/login 是另一套密码，不可用
        String cookies = ssoLogin(req.getAccount(), req.getPassword());
        String masked = mask(req.getAccount());

        sessionStore.save(userId, cookies, masked);

        // 第 3 步：按前端是否勾选"记住账号"决定存不存密码。
        // 【写法】只有 remember=true 且服务端配了密钥才保存；否则一律清除
        // 【为什么】"记住账号"是用户主动勾的授权，没勾就不能存；
        //   同时要兼容"上次勾了、这次取消"的情况 —— 取消时必须把旧密文删掉
        // 【代价】没勾选的用户，Cookie 过期后要手动重登（回到旧体验），这是他自己选的
        boolean remember = Boolean.TRUE.equals(req.getRemember());
        if (remember) {
            boolean saved = credentialStore.save(userId, req.getAccount(), req.getPassword());
            if (!saved) {
                // 没存成功不影响登录本身：可能是密钥未配置，或写库失败
                log.info("[教务] 记住密码未生效 userId={}（密钥未配置或保存失败）", userId);
            }
        } else {
            credentialStore.clear(userId);
        }

        log.info("[教务] SSO 登录成功 userId={} account={}", userId, masked);
        return status();
    }

    @Override
    /** 退出 */
    public void logout() {
        Long userId = UserContext.requireUserId();
        sessionStore.clear(userId);
        // 退出必须连密码一起删：用户点"退出"的语义是"别再替我记着"
        credentialStore.clear(userId);
    }

    /**
     * 登录状态。
     *
     * 【写法】只判断 Redis 里有没有会话，不额外请求教务去「验票」。
     * 【为什么】验票要多一次外部请求，而 status 每次进页面都会被调用。
     *   即使票已失效，课表接口会走降级逻辑返回缓存数据并标记 stale，用户不会撞到硬错误，
     *   所以这里没必要付出那一次往返。
     * 【代价】极端情况下会显示「已登录」但票已废，由课表的 stale 标记兜住。
     */
    @Override
    public EduStatusVO status() {
        Long userId = UserContext.requireUserId();
        EduStatusVO vo = new EduStatusVO();
        boolean loggedIn = sessionStore.remainingSeconds(userId) > 0L;
        vo.setLoggedIn(loggedIn);
        // 是否"已记住密码"：给前端显示"已记住，30 天内免登录"
        // 【注意这里只查存在性，不解密】每次进页面都调 status，解密没必要
        vo.setSaved(credentialStore.exists(userId));
        vo.setAccountMasked(loggedIn ? sessionStore.accountMasked(userId) : "");
        vo.setExpiresAt(loggedIn ? sessionStore.expiresAt(userId) : "");
        return vo;
    }

    /**
     * 用已保存的加密凭证自动重登。
     *
     * 流程：解密凭证 → 走一遍 SSO → 新 Cookie 存回会话 → 凭证续期 30 天。
     * 返回 false 的三种情况：密钥未配置、没有凭证/已过期、重登失败。
     */
    // 【为什么这个方法不抛异常】它是"补救动作"，调用方（查课表）已经有原始错误在手，
    //   这里再抛一个只会把原因搅乱。让调用方拿到 false 后统一报"请重新登录"更清楚。
    @Override
    /** 刷新session */
    public boolean refreshSession() {
        Long userId = UserContext.requireUserId();
        // 密钥没配 → 功能未启用，不可能自动重登
        if (!credentialStore.available()) {
            return false;
        }
        EduCredentialStore.Credential cred = credentialStore.load(userId);
        if (cred == null) {
            return false;
        }
        try {
            String cookies = ssoLogin(cred.getAccount(), cred.getPassword());
            sessionStore.save(userId, cookies, mask(cred.getAccount()));
            // 重登成功才续期 —— 失败时续期等于给一张废凭证延长寿命
            credentialStore.touch(userId);
            log.info("[教务] 自动重登成功 userId={}", userId);
            return true;
        } catch (BizException e) {
            // 【关键区分】用错误码分辨"账号密码错"和"教务暂时不可用"
            //   前者要删凭证（用户改密码了，留着只会反复失败）
            //   后者绝不能删（网络抖动一下就把用户凭证删了，太粗暴）
            if (e.getCode() == ResultCode.PARAM_ERROR.getCode()) {
                log.warn("[教务] 自动重登失败（账号或密码已变更），清除凭证 userId={}", userId);
                credentialStore.clear(userId);
                sessionStore.clear(userId);
            } else {
                log.warn("[教务] 自动重登失败（教务暂时不可用），保留凭证 userId={} err={}",
                        userId, e.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.error("[教务] 自动重登异常 userId={}", userId, e);
            return false;
        }
    }

    /* ==================== 教务协议 ==================== */

    /**
     * 广工统一认证登录，返回教务站的 Cookie 头。
     * 流程：教务 ssoLogin → 认证中心登录取 salt/execution → 提交账号密码（AES-CBC）→ 拿着 ticket 回教务建会话。
     */
    /**
     * 统一认证（CAS）登录，返回教务域的 Cookie 头。
     *
     * 【五步流程】
     *   ① 预热教务会话：先访问 /login!welcome.action，让教务下发 JSESSIONID
     *   ② 访问 /new/ssoLogin → 302 跳转到认证中心（authserver）的登录页
     *   ③ 从登录页 HTML 里抠出 salt（密码加密盐）和 execution（表单防重令牌）
     *   ④ POST 账号 + 加密后的密码 → 302 带着 ticket 跳回教务
     *   ⑤ 教务侧拿 ticket 换会话：可能连续多次 302，循环跟到落地为止
     *
     * 【三个必踩的坑（都是实测出来的）】
     *   一、两个域各有自己的 JSESSIONID，共用一个 Cookie 容器会互相覆盖 → 必须两个 CookieManager
     *   二、登录成功后地址仍然是 login!welcome.action，**不能靠 URL 判断成败**，要看页面里有没有「注销」
     *   三、收票过程可能连续 302（含 ssoLogin;jsessionid=... 的会话更新），只跟一次会拿到空页面
     *
     * 参数 account：学号
     * 参数 password：明文密码，只在内存里用于这一次加密提交，不落库、不进日志
     * 返回：形如 "JSESSIONID=xxx; route=yyy" 的 Cookie 头，供后续所有教务请求使用
     * 异常：账号或密码错 → 400；教务站点异常 → 500
     *
     * 【模拟走一遍（用这组假数据预演整个流程）】
     *   输入：学号 3124004295 / 密码 123456 / 教务 https://jxfw.gdut.edu.cn
     *   ① 预热   GET /login!welcome.action   → 200，jxJar 收到 JSESSIONID=ABC123
     *   ② 跳转   GET /new/ssoLogin           → 302，Location=https://authserver.../login?service=...
     *   ③ 抠值   GET 登录页 HTML              → salt=a1b2c3d4e5f6g7h8、execution=e1s1
     *   ④ 提交   POST 账号+加密密码           → 302，Location=...ssoLogin?ticket=ST-123-abc
     *   ⑤ 收票   GET 带 ticket 的地址          → 跟 2 跳后落地，页面含「注销」
     *   ⑥ 返回   拼 Cookie 头                 → "JSESSIONID=ABC123; route=8f2e"
     *   失败样本：密码改成 000000 → 第 ④ 步 Location 里没有 ticket → 抛 400「学号或密码不正确」
     */
    private String ssoLogin(String account, String password) {
        try {
            // 第 1 步：预热教务侧会话。
            // 通俗说：先去教务处门口报个到，让他记住"有这么个人来过"，后面才肯跟你说话。
            // 【本段预演】请求 /login!welcome.action → 期望 200，jxJar 里出现 JSESSIONID=ABC123
            // 【为什么要先访问一次】教务用 URL 重写（;jsessionid=...）作为兜底；
            //   先建立 Cookie 会话，后面的重定向才会走 Cookie 而不是把 sessionid 拼进 URL，
            //   否则最后拼出来的 Cookie 头里没有 JSESSIONID，后续请求全部失效。
            //
            // 本段 API：
            //   CookieManager        JDK：Cookie 容器，自动收下发 Cookie、后续请求自动回放
            //   newClient()          本项目：建客户端（绑 Cookie + 教务证书 + 禁止自动跳转）
            //   request(url)         本项目：建请求构造器；base() 取根地址；.GET() 声明方法；.build() 固化成请求
            //   send(req, handler)   JDK：同步发出请求并等待响应
            //   BodyHandlers.ofString(UTF_8)  JDK：把响应体按 UTF-8 读成字符串
            CookieManager jxJar = new CookieManager();
            HttpClient jxClient = newClient(jxJar);
            jxClient.send(request(base() + "/login!welcome.action").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 第 2 步：从教务的 SSO 入口跳出去，只取 302 的 Location（真正的登录页在认证中心）。
            // 通俗说：问教务处"统一认证的登录页在哪儿"，他把门牌号给你，但不带你过去。
            // 【本段预演】GET /new/ssoLogin → 302，Location =
            //   https://authserver.gdut.edu.cn/authserver/login?service=https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin
            // 【为什么不直接请求 authserver】service 参数必须由教务生成（带它自己的回调地址），
            //   自己拼的 URL 认证中心不认。
            //
            // 本段 API：
            //   headers().firstValue("Location")  JDK：取响应头 Location —— 302 的跳转目标
            //   orElse("")                        JDK：Optional 取值，为空时给默认值（避免 NPE）
            HttpResponse<String> entry = jxClient.send(
                    request(base() + "/new/ssoLogin").GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String loginUrl = entry.headers().firstValue("Location").orElse("");
            if (loginUrl.isBlank()) {
                throw BizException.of(ResultCode.SERVER_ERROR, "教务 SSO 入口异常，请稍后重试");
            }

            // 认证中心单独一个会话：两个域都有 JSESSIONID，共用会互相覆盖
            // 【这句是整个方法最容易出错的地方】如果复用 jxJar，认证中心下发的 JSESSIONID
            //   会盖掉教务那个，最后拿回来的 Cookie 头里只剩认证中心的，教务侧仍然未登录。
            // 【本段预演】
            //   教务下发 JSESSIONID=ABC123 → 认证中心下发 JSESSIONID=XYZ789
            //   共用 jxJar 时：同名 key 被覆盖，最终 jxJar 只剩 {JSESSIONID=XYZ789}
            //                → Cookie 头里没有教务会话 → 后面查课表全部提示未登录
            //   分开存时：jxJar={JSESSIONID=ABC123, route=8f2e}、authJar={JSESSIONID=XYZ789} ✅
            CookieManager authJar = new CookieManager();
            HttpClient authClient = newClient(authJar);
            // 第 3 步：打开登录页，从 HTML 里抠两个值 —— 这一步是在"读到浏览器会提交什么"。
            // 通俗说：打开那张登录页，把页面上印着的两个「暗号」抄下来（一个是加密用的盐，一个是本次表单的编号）。
            // 【本段预演】HTML 里两行 → salt=a1b2c3d4e5f6g7h8（16 位）、execution=e1s1
            // 本段 API：
            //   send(...).body()  JDK：发请求并直接取响应体字符串
            //   firstMatch(...)   本项目：正则取第一个捕获组，取不到返回 null
            String html = authClient.send(request(loginUrl).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();

            // salt：密码加密的盐，教务前端 JS 从 #pwdEncryptSalt 读它，我们照做
            String salt = firstMatch(html, "id=\"pwdEncryptSalt\"[^>]*value=\"([^\"]+)\"");
            // execution：CAS 的表单令牌，防重复提交；每次登录页刷新都会变，不能写死
            String execution = firstMatch(html, "id=\"execution\"[^>]*name=\"execution\"[^>]*value=\"([^\"]+)\"");
            // 【为什么写两种匹配】页面在不同版本下 id 和 name 的顺序会调换，
            //   只匹配一种会在某个版本悄悄失效（表现为 execution 取不到、登录一直失败）
            if (execution == null) {
                execution = firstMatch(html, "name=\"execution\"[^>]*id=\"execution\"[^>]*value=\"([^\"]+)\"");
            }
            // salt 固定 16 位（AES-128 的密钥长度）。长度不对说明页面改版了，直接报错而不是继续提交。
            // 【本段预演】
            //   正常：salt="a1b2c3d4e5f6g7h8"（16 位）→ 通过
            //   改版：salt="ab12"（4 位）或取不到 null → 抛 500「认证中心页面结构变化，请稍后重试」
            //   为什么不能兜底：用错盐加密，服务器解出乱码，表现成"密码错"，比直接报错更难查
            if (salt == null || salt.length() != 16) {
                throw BizException.of(ResultCode.SERVER_ERROR, "认证中心页面结构变化，请稍后重试");
            }
            // execution 实在取不到时的兜底：CAS 默认值 e1s1。
            // 【为什么敢兜底】它是防重复提交的令牌，取不到时多数实现仍接受默认值；
            //   而 salt 不能兜底（密码加错解不开），所以上面那条必须抛异常。
            if (execution == null) {
                execution = "e1s1";
            }

            // 第 4 步：提交表单。
            // 通俗说：把学号、用暗号加密过的密码、表单编号一起交上去，换一张「通行证」（ticket）。
            // 下面这串参数名（lt/dllt/cllt/_eventId/rmShown）都是 CAS 协议的固定字段，
            //   不是教务自定义的 —— 少一个或拼错都会被当成非法请求。
            // password 不是原文，而是 ssoEncrypt 处理后的密文（AES-CBC + Base64）。
            // 【本段预演】输入 account=3124004295 / password=123456 / salt=a1b2c3d4e5f6g7h8 / execution=e1s1
            //   ① ssoEncrypt("123456", salt) → "Xk9mP2qR7tY="     （AES-CBC 加密后转 Base64）
            //   ② enc(密文)                  → "Xk9mP2qR7tY%3D"   （Base64 的 + / = 会被 URL 转义）
            //   ③ 拼出的 form（注意 lt 是空值）：
            //      username=3124004295&password=Xk9mP2qR7tY%3D&lt=&dllt=generalLogin
            //      &cllt=userNameLogin&execution=e1s1&_eventId=submit&rmShown=1
            //   ④ POST 之后 → 期望 302，Location=https://jxfw.gdut.edu.cn/new/ssoLogin?ticket=ST-123-abc
            //   ⑤ 密码错时 → 也是 302，但 Location 里没有 ticket= → 抛 400「学号或密码不正确」
            // 本段 API：
            //   enc()            本项目：URL 编码，防中文/符号把表单串截断
            //   ssoEncrypt()     本项目：按官方 encrypt.js 加密密码
            //   .header(k, v)    JDK：设置请求头
            //   .POST(ofString(form, UTF_8))  JDK：声明 POST，请求体按 UTF-8 编码
            String form = "username=" + enc(account)
                    + "&password=" + enc(ssoEncrypt(password, salt))
                    + "&lt=&dllt=generalLogin&cllt=userNameLogin"
                    + "&execution=" + enc(execution)
                    + "&_eventId=submit&rmShown=1";
            // 【为什么要带 Referer 和 Origin】认证中心会校验请求来源，
            //   缺这两个头有时会被判定为跨站请求而拒掉。
            HttpResponse<String> res = authClient.send(
                    request(loginUrl)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Referer", loginUrl)
                            .header("Origin", AUTH_BASE)
                            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 【怎么判断账号密码对不对】看 302 的 Location 里有没有 ticket。
            //   认证成功才会发 ticket；失败时 Location 会带回登录页并附错误码。
            // 【本段预演】
            //   密码正确 → Location = https://jxfw.gdut.edu.cn/new/ssoLogin?ticket=ST-123-abc
            //              → contains("ticket=")=true → 继续第 5 步
            //   密码错误 → Location = https://authserver.gdut.edu.cn/authserver/login?service=...#errorCode=...
            //              → 不含 "ticket=" → 抛 400「学号或密码不正确」
            //   ⚠️ 两种都是 302，只有 Location 内容不同 —— 所以必须看内容，不能只看状态码
            String ticketUrl = res.headers().firstValue("Location").orElse("");
            if (!ticketUrl.contains("ticket=")) {
                throw BizException.of(ResultCode.PARAM_ERROR, "学号或密码不正确");
            }

            // 第 5 步：拿着 ticket 回教务换会话。
            // 通俗说：拿通行证回教务处换正式门票，教务可能让他在几个窗口之间来回跑两趟才给。
            // 【本段预演】ticketUrl = https://jxfw.gdut.edu.cn/new/ssoLogin?ticket=ST-123-abc
            //   第 1 跳 → 302，Location=/new/ssoLogin;jsessionid=DEF456（相对路径，补全成绝对地址）
            //   第 2 跳 → 200，body 含「注销」→ 跳出循环，成功
            //   异常：连续 8 跳仍未落地 → 抛 500「教务会话建立失败，请稍后重试」
            // 【为什么要循环】教务收票时可能连续 302 好几次（其中一次是更新 ;jsessionid=...），
            //   只跟一次会停在中间页，拿到的 body 里没有「注销」，被误判成失败。
            // 上限 8 次：正常 2~4 次就落地，超过说明出现异常重定向环，及时止损。
            // 本段 API：
            //   statusCode()             JDK：HTTP 状态码，3xx 表示重定向
            //   firstValue("Location")   JDK：取下一个跳转地址
            //   startsWith("http")       JDK：判断是绝对地址还是相对地址（相对的要补全）
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
                    // 相对路径要补全成绝对地址才能继续请求
                    cur = next.startsWith("http") ? next : base() + (next.startsWith("/") ? "" : "/") + next;
                    continue;
                }
                body = r.body();
                break;
            }
            // 登录成功后地址仍是 login!welcome.action，必须按页面内容判断
            // 【这是实测踩过的坑】旧代码用 url.contains("login") 判断，
            //   结果每次成功都被误判成失败（因为成功的地址里也有 login）。
            //   正确判据：页面里出现「注销」或「登录时间」= 已登录。
            // 【本段预演】
            //   情况 A：body 含「注销」 → 不进 if，直接过 ✔
            //   情况 B：落在中间页，body 不含「注销」 → 再请求一次首页 /，拿到含「注销」的页面 ✔
            //   情况 C：两次都没有「注销」也没有「登录时间」 → 抛 500「教务会话建立失败」
            if (!body.contains("注销")) {
                body = jxClient.send(request(base() + "/").GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            }
            if (!body.contains("注销") && !body.contains("登录时间")) {
                throw BizException.of(ResultCode.SERVER_ERROR, "教务会话建立失败，请稍后重试");
            }

            // 收尾：把教务域的 Cookie 拼成请求头字符串，交给 EduSessionStore 存进 Redis。
            // 通俗说：把门票信息抄成一行字，交给后面保管的人（Redis），以后每次进门报这一行就行。
            // 【本段预演】返回 "JSESSIONID=ABC123; route=8f2e"，日志只打 cookieNames=[JSESSIONID, route]
            // 【只取 jxJar 不取 authJar】后续所有请求都是发给教务域的，
            //   认证中心的 Cookie 拿到教务也没用，反而可能让请求头变长。
            // 本段 API：
            //   cookieHeader()  本项目：把 CookieManager 里的 Cookie 拼成 "k1=v1; k2=v2" 请求头
            //   cookieNames()   本项目：只提取名字做日志，不打值（值属敏感信息）
            String cookies = cookieHeader(jxJar);
            // 日志只打 Cookie 的「名字」不打值：名字能看出拿到了哪些会话，值属于敏感信息
            log.info("[教务] SSO 会话建立 cookieNames={}", cookieNames(cookies));
            return cookies;
        } catch (BizException e) {
            // 上面主动抛的（账号密码错、页面改版）已经翻译好了，原样上抛
            throw e;
        } catch (Exception e) {
            // 网络、超时、解析 —— 统一对外说"登录失败"，细节只进日志
            log.error("[教务] SSO 登录异常", e);
            throw BizException.of(ResultCode.SERVER_ERROR, "教务登录失败，请稍后重试");
        }
    }

    /**
     * 从文本里用正则取第一个捕获组（即正则里第一个括号的内容）。
     *
     * 取不到返回 null 而不是抛异常 —— 由调用方决定是兜底还是报错。
     * 例：firstMatch(html, "id=\"execution\"[^>]*value=\"([^\"]+)\"") → "e1s1"
     */
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
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(key));
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

    /**
     * 创建教务专用的 HTTP 客户端。
     *
     * 做了四件事：绑定 Cookie 容器、挂教务专用信任库（JDK 缺 GDCA 中间证书）、10 秒连接超时、
     * 禁止自动跟随重定向。
     * 【为什么禁止自动跟随】SSO 全程靠读 302 的 Location 一步步走；自动跟随会直接跳到终点，
     * 把中间的 ticket、jsessionid 全丢掉，最后拿不到有效会话。
     */
    private HttpClient newClient(CookieManager cookies) {
        return HttpClient.newBuilder()
                .cookieHandler(cookies)
                .sslContext(eduSslContext())
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 默认信任库 + 教务专用信任库（edu-trust.p12）叠加。
     * 只额外信任 GDCA 中间证书，不关闭校验、不影响其他域名。
     */
    public static SSLContext eduSslContext() {
        if (EDU_SSL_CONTEXT != null) {
            return EDU_SSL_CONTEXT;
        }
        synchronized (EduAuthServiceImpl.class) {
            if (EDU_SSL_CONTEXT != null) {
                return EDU_SSL_CONTEXT;
            }
            try {
                TrustManagerFactory def = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                def.init((KeyStore) null);

                KeyStore ts = KeyStore.getInstance("PKCS12");
                try (InputStream in = EduAuthServiceImpl.class.getResourceAsStream("/edu-trust.p12")) {
                    if (in == null) {
                        log.warn("[教务] 未找到 edu-trust.p12，使用 JDK 默认信任库");
                        return SSLContext.getDefault();
                    }
                    ts.load(in, "changeit".toCharArray());
                }
                TrustManagerFactory extra = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                extra.init(ts);

                X509TrustManager defaultTm = firstX509(def);
                X509TrustManager extraTm = firstX509(extra);
                TrustManager[] managers = new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        defaultTm.checkClientTrusted(chain, authType);
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType)
                            throws CertificateException {
                        try {
                            defaultTm.checkServerTrusted(chain, authType);
                        } catch (CertificateException e) {
                            extraTm.checkServerTrusted(chain, authType);
                        }
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        X509Certificate[] a = defaultTm.getAcceptedIssuers();
                        X509Certificate[] b = extraTm.getAcceptedIssuers();
                        X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
                        System.arraycopy(b, 0, all, a.length, b.length);
                        return all;
                    }
                }};

                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, managers, null);
                EDU_SSL_CONTEXT = ctx;
                return ctx;
            } catch (Exception e) {
                log.error("[教务] 初始化信任库失败，回退默认", e);
                try {
                    return SSLContext.getDefault();
                } catch (Exception ex) {
                    throw new IllegalStateException("无法初始化 TLS", ex);
                }
            }
        }
    }

    private static X509TrustManager firstX509(TrustManagerFactory factory) {
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager x) {
                return x;
            }
        }
        throw new IllegalStateException("没有可用的 X509TrustManager");
    }

    /**
     * 构造一个请求（只设公共头，GET/POST 由调用方在链尾决定）。
     *
     * 设了三样：15 秒读超时、浏览器 UA、中文 Accept-Language。
     * 【为什么要伪装 UA】教务会拒绝非浏览器的 UA，直接返回 403。
     */
    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9");
    }

    /** 只提取 Cookie 的「名字」用于日志观察，不输出值 —— 值属于敏感信息，不能进日志 */
    private String cookieNames(String header) {
        if (header == null || header.isBlank()) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (String part : header.split(";")) {
            int idx = part.indexOf('=');
            if (idx > 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append(part.substring(0, idx).trim());
            }
        }
        return sb.toString();
    }

    private String cookieHeader(CookieManager manager) {
        StringBuilder sb = new StringBuilder();
        for (HttpCookie c : manager.getCookieStore().getCookies()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.getName()).append('=').append(c.getValue());
        }
        return sb.toString();
    }

    /** 取教务根地址，并去掉末尾斜杠（配置里写了 / 也不会拼出双斜杠） */
    private String base() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** URL 编码：把表单值里的中文和特殊字符转成 %XX，避免拼出来的 form 串被截断或错位 */
    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 脱敏 */
    private String mask(String account) {
        if (account == null || account.length() <= 6) return account;
        return account.substring(0, 4) + "****" + account.substring(account.length() - 2);
    }
}
