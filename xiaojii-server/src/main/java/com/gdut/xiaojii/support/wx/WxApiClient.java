package com.gdut.xiaojii.support.wx;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.config.WxProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 微信小程序服务端接口客户端。
 *
 * 目前只用到一个微信接口：jscode2session
 *   用小程序 wx.login() 拿到的临时 code，换 openid 与 session_key。
 *
 * 为什么必须放服务端调：
 *   AppSecret 一旦下发到小程序就会被反编译拿走，任何人都能冒充你的小程序调用微信接口。
 *
 * 常见错误码（code2Session 内部会把它们翻译成中文提示）：
 *   40029  code 无效（code 只能用一次、5 分钟过期、必须与 appid 匹配）
 *   45011  频率限制（同一用户短时间内狂刷登录）
 *   -1     微信系统繁忙
 *   40125  AppSecret 不正确（通常是配置写错或复制了多余空格）
 *
 * @author 校集后端
 */
// Lombok 生成 log 字段，用来打 warn/error 日志
@Slf4j
// 注册成 Spring Bean，AuthServiceImpl 通过构造器注入拿到它
@Component
// 为 final 字段生成构造器，Spring 用它注入 WxProperties
@RequiredArgsConstructor
public class WxApiClient {

    // 微信配置：appid、secret、jscode2session 的接口地址，来自 application.yml 的 xiaojii.wx.*
    private final WxProperties props;

    /**
     * 复用 RestClient 实例（线程安全），避免每次请求都新建连接工厂。
     *
     * RestClient 是什么：
     *   Spring 6.1 提供的「同步 HTTP 客户端」，用来在 Java 代码里发 GET / POST 请求。
     *   它是老牌 RestTemplate 的替代品，API 是链式的，但仍然是阻塞式
     *   （不像 WebClient 那样需要响应式编程）。
     *
     * 和几个同类比：
     *   · RestTemplate —— Spring 老一代，API 笨重，已进入维护状态；
     *   · RestClient   —— Spring 6.1 新增，链式 + 可精细控制，本项目用的就是它；
     *   · WebClient    —— 响应式（非阻塞），适合高并发网关，学习成本高；
     *   · JDK HttpClient —— Java 11 自带，无框架依赖，但用法啰嗦、不集成 Spring 的消息转换器。
     *
     * 为什么写成 final 字段、只创建一次：
     *   RestClient 实例是线程安全的，全应用复用一个即可；
     *   每次请求都 RestClient.create() 会反复创建底层连接工厂，白白浪费资源。
     */
    private final RestClient restClient = RestClient.create();

    /** 微信失败时可能返回 text/plain，先读字符串再解析，避免依赖 Content-Type */
    // 为什么不用 Spring 自动注入的 ObjectMapper：
    // 这里只做一次简单的字符串转对象，用独立实例更可控，也不受全局 Jackson 配置影响
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * code 换 openid。
     *
     * 入参：code —— wx.login() 返回的临时凭证（一次性、5 分钟过期、绑定 appid）
     * 返回：WxSession —— 含 openid / unionid / sessionKey 的响应体
     * 异常：任何失败都抛 BizException(1001)，并把原因翻译成中文放在 msg 里
     *
     * 这个方法的原则：不让上游看到任何"原始"的微信错误，全部转成业务异常。
     *
     * 流程（先读这里，再对照下面的代码，代码里已用"第 N 段"标注）：
     *   第1段  配置没配齐（appid/secret 为空）？ → 直接报错，提示改用 dev-login
     *   第2段  拼出请求地址：接口地址 + appid + secret + code + 固定参数
     *   第3段  发 GET 请求，把微信返回的内容读成一段文本（先不解析）
     *   第4段  把这段文本解析成 WxSession 对象（JSON → Java 对象）
     *   第5段  微信说失败了（errcode 不为 0）？ → 按错误码翻译成人话再报错
     *   第6段  没拿到 openid？ → 同样算失败
     *   全部通过 → 返回 session，交给 AuthServiceImpl 继续处理
     */
    public WxSession code2Session(String code) {
        // 第 1 段：先检查配置是否齐全。
        // 没配 appid/secret 时给出明确提示，而不是让前端看到一个莫名其妙的"登录失败"。
        if (!props.configured()) {
            log.warn("微信登录未配置：xiaojii.wx.appid / secret 为空");
            throw BizException.of(ResultCode.WX_LOGIN_FAIL, "服务端未配置小程序 AppID/AppSecret，请先用开发登录");
        }

        // 第 2 段：拼请求 URL（微信这个接口的参数都放 query string，不用请求体）。
        // 注意：URL 里含 secret，属于敏感信息 —— 万一异常打出整个 URL，secret 就进日志了，
        // 所以下面 catch 里只打固定文案，不打 url。
        String url = props.getJscode2sessionUrl()
                + "?appid=" + props.getAppid()
                + "&secret=" + props.getSecret()
                + "&js_code=" + code
                + "&grant_type=authorization_code";

        WxSession session;
        try {
            // 第 3 段：发请求。
            //
            // 【写法】body(String.class) —— 先拿原始文本，再用 Jackson 手动解析
            // 【为什么】微信在"失败"时可能返回 text/plain 而不是 application/json。
            //           若直接写 body(WxSession.class)，Spring 会因为 Content-Type 不匹配
            //           抛出 UnknownContentTypeException，结果是"连错误码都拿不到"，
            //           只能给用户一句笼统的"登录失败"，排查时也看不到 40029/40125。
            //           先读成文本，成功和失败两种情况都能走到自己的解析逻辑。
            // 【代价】多一次"字符串 → 对象"的转换，1ms 级别，可忽略；
            // 【另一种】要求微信统一返回 JSON —— 做不到，对方接口行为不受我们控制。
            // 这条链式调用一共四步，逐环看：
            //   .get()       声明 HTTP 方法（另有 post() / put() / delete()）
            //   .uri(url)    指定目标地址（appid/secret/js_code 已拼在 url 里）
            //   .retrieve()  开始执行请求，准备处理响应
            //   .body(类型)  把响应体按指定类型读出来；这里读成 String
            //
            // 中间插入的 .onStatus(...) 是一个"空的"状态码处理器：
            //
            // 【写法】onStatus(status -> status.isError(), (req, res) -> { }) —— 大括号里什么都不做
            // 【为什么】RestClient 的默认行为是"4xx/5xx 抛 HttpStatusCodeException"。
            //           但微信失败时错误码在响应体里（errcode），HTTP 层未必是 2xx；
            //           如果让它抛异常，就永远走不到下面解析 errcode 的代码，
            //           用户只能看到"登录失败"，而不是"凭证已失效/太频繁/AppSecret 错了"。
            //           空实现 = 把这个默认行为关掉，让流程继续往下走。
            // 【代价】状态码这里不做判断，一切交给 errcode —— 如果微信哪天不返 errcode，
            //         这里会漏判（所以下面还有 session==null 和 openid 为空的兜底）。
            // 【另一种】把状态码判断写在这里并抛异常 —— 会丢掉错误码里的具体原因，体验更差。
            String body = restClient.get().uri(url)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                    })
                    .body(String.class);
            // 响应体为空的兜底：微信偶发返回空内容
            if (body == null || body.isBlank()) {
                throw BizException.of(ResultCode.WX_LOGIN_FAIL);
            }
            // 把 JSON 字符串转成 WxSession 对象
            session = JSON.readValue(body, WxSession.class);
        } catch (BizException e) {
            // 上面主动抛的业务异常，原样往上抛，不要被下面的 catch 吞掉
            throw e;
        } catch (RestClientException e) {
            // 网络层异常：连不上、超时、DNS 失败等
            log.error("调用微信 jscode2session 失败", e);
            throw BizException.of(ResultCode.WX_LOGIN_FAIL, "微信服务暂时不可用，请稍后重试");
        } catch (JsonProcessingException e) {
            // 微信返回了内容，但不是合法 JSON
            log.error("解析微信 jscode2session 响应失败: {}", e.getOriginalMessage());
            throw BizException.of(ResultCode.WX_LOGIN_FAIL, "微信返回内容无法识别，请稍后重试");
        }

        // 第 4 段：极少数情况下解析出 null（比如响应体是 "null" 字面量），兜一下
        if (session == null) {
            throw BizException.of(ResultCode.WX_LOGIN_FAIL);
        }

        // 第 5 段：微信用 errcode 表达失败（0 或 null 才是成功）。
        // switch 表达式是 Java 14+ 的语法：箭头右边直接是结果值，不需要 break。
        if (session.getErrcode() != null && session.getErrcode() != 0) {
            log.warn("微信登录失败 errcode={} errmsg={}", session.getErrcode(), session.getErrmsg());
            String hint = switch (session.getErrcode()) {
                // 40029 code 无效；40163 code 已被使用 —— 对用户来说都是"重新登录"
                case 40029, 40163 -> "登录凭证已失效，请重试";
                // 45011 调用太频繁
                case 45011 -> "操作太频繁，请稍后再试";
                // 40125 secret 配错：这是服务端配置问题，不能让用户去"重试"
                case 40125 -> "服务端 AppSecret 配置错误，请联系管理员";
                // 其他未知错误码：把原始码带上，方便排查
                default -> "微信登录失败（" + session.getErrcode() + "），请重试";
            };
            throw BizException.of(ResultCode.WX_LOGIN_FAIL, hint);
        }

        // 第 6 段：errcode 为 0 但 openid 为空，属于脏数据，同样不能放过去 ——
        // 因为后续 doLogin 全靠 openid 定位用户，空值会建出一条无效记录。
        if (session.getOpenid() == null || session.getOpenid().isBlank()) {
            throw BizException.of(ResultCode.WX_LOGIN_FAIL);
        }
        return session;
    }

    /**
     * jscode2session 响应体。
     * 字段名是微信的下划线风格，必须显式映射，否则拿不到值。
     */
    // Lombok 生成 getter/setter，Jackson 反序列化需要 setter
    @Data
    // 静态内部类：只服务于 WxApiClient，不对外暴露成独立文件
    public static class  WxSession {

        // 用户在这个小程序里的唯一标识，登录主键
        private String openid;

        /** 会话密钥，用于解密手机号等敏感数据；本项目不下发、不落库 */
        // 微信返回的 JSON 字段叫 session_key（下划线），Java 字段是 sessionKey（驼峰），
        // 必须用 @JsonProperty 显式指定，否则这个字段永远为 null
        @JsonProperty("session_key")
        private String sessionKey;

        // 同一微信开放平台下多个应用通用的用户标识，可能为空
        private String unionid;

        // 微信错误码：0 或 null 表示成功，其余见类注释里的对照表
        private Integer errcode;

        // 微信的错误描述，只用于日志排查，不直接给用户看
        private String errmsg;
    }
}
