package com.gdut.xiaojii.common;

import com.gdut.xiaojii.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：签发与校验登录令牌。
 *
 * 【为什么用 JWT 而不是服务端 Session】
 * 小程序端天然没有 Cookie；服务端还要能水平扩容，而 Session 需要共享存储。
 * JWT 自带签名，服务端不用查库就能信任里面的 userId。
 *
 * 【为什么还要 Redis 白名单】
 * JWT 一旦签发就撤不回来（除非等它过期）。用户被封禁、主动退出时需要能立刻失效，
 * 所以在 Redis 存一份有效令牌：校验时多一次 Redis 查询（微秒级），
 * 换来"能主动踢人"的能力。这是用一点性能换安全，值得。
 *
 * 【令牌分两类】
 * typ=app 给小程序用户，typ=admin 给后台管理员。
 * 两类令牌用同一密钥签名、靠 claim 里的 typ 区分；
 * 拦截器据此拒绝"拿用户令牌调管理端接口"。
 *
 * @author 校集后端
 */
// 【写法】@Slf4j 而不是手写 private static final Logger log = LoggerFactory.getLogger(...)
// 【为什么】少一行样板代码，且不会把类名写错（写错会导致日志落到别的 logger 上，查不到）
// 【代价】依赖 Lombok 注解处理器；IDE 需要装插件，否则编译期看不到 log 变量
@Slf4j
// 【写法】@Component
// 【为什么】AuthServiceImpl 与拦截器都要用它，必须成为 Spring Bean 才能被注入
// 【另一种】写成 new JwtUtil(...) —— 那就要手动传入 JwtProperties，等于自己装配依赖
@Component
// 【写法】@RequiredArgsConstructor + final 字段
// 【为什么】Lombok 生成只含 final 字段的构造器，Spring 用构造器注入 props；
//           final 保证注入后不可变，也保证"缺依赖时启动立刻失败"
// 【另一种】@Autowired 字段注入 —— 字段不能是 final，且单测时必须靠反射塞值
@RequiredArgsConstructor
public class JwtUtil {

    /** 小程序端令牌类型 */
    public static final String TYPE_APP = "app";
    /** 管理端令牌类型 */
    public static final String TYPE_ADMIN = "admin";
    /** 自定义 claim：令牌类型 */
    public static final String CLAIM_TYPE = "typ";

    /** HS256 要求密钥至少 256 位（32 字节） */
    private static final int MIN_SECRET_BYTES = 32;

    // JWT 配置：密钥 secret、两个过期时间（小程序 7 天 / 管理端 12 小时）
    // 这些值来自 application.yml 的 xiaojii.jwt.*，由 JwtProperties 绑定好
    private final JwtProperties props;

    /**
     * HMAC 签名密钥。
     * 在 @PostConstruct 里一次性初始化好，之后只读，等同于 final 的效果。
     * 【为什么不写成懒加载】懒加载要在每次用时判断 key == null，
     * 多线程可能同时进入判断并各自 new 一个，出现"不安全发布"；
     * 启动时初始化一次，既没有并发问题，也能让坏密钥在启动时就报错。
     */
    private SecretKey key;

    /**
     * 启动即校验密钥，坏密钥不要等到第一次登录才炸。
     *
     * 【踩坑记录】Keys.hmacShaKeyFor 遇到短密钥抛的是 WeakKeyException（继承 JwtException）：
     * 签发时没 try-catch 会直接 500，解析时又被 catch 吞掉变成 401，
     * 于是表现为"登录一直失败、鉴权永远 401"，而日志里什么都看不到。
     * 所以这里在启动时就断言，把问题提前到最早能发现的时刻。
     */
    // 【写法】@PostConstruct 而不是把校验写进构造器
    // 【为什么】构造器执行时，字段还没被 Spring 完全初始化好（依赖注入在此之后）；
    //           @PostConstruct 是"依赖注入完成之后"的第一个回调，此时 props 一定有值
    // 【另一种】实现 InitializingBean 接口 —— 效果相同，但会与 Spring 接口耦合，注解更轻
    @PostConstruct
    void init() {
        String secret = props.getSecret();
        // 先判 null 和空白：配置项被整个删掉时字段为 null，写成 ${X:} 时是空串
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "xiaojii.jwt.secret 未配置。请在 application.yml 或环境变量 JWT_SECRET 中配置至少 32 字节的随机字符串");
        }
        // 按 UTF-8 取字节数：中文一个字符占 3 字节，所以不能用 secret.length() 判断长度
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "xiaojii.jwt.secret 长度为 %d 字节，HS256 要求至少 %d 字节，请更换为更长的随机串",
                    bytes.length, MIN_SECRET_BYTES));
        }
        // 用字节数组生成 HMAC-SHA 密钥，后续签发/验签都用它
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 签发小程序用户令牌（有效期 7 天）。
     *
     * @param userId 用户 ID，作为 subject 写入
     * @return JWT 字符串（不含 "Bearer " 前缀）
     */
    public String generate(Long userId) {
        return generate(userId, TYPE_APP, props.getExpireSeconds());
    }

    /**
     * 签发管理端令牌（有效期 12 小时，可配置）。
     *
     * @param adminId 管理员 ID
     * @return JWT 字符串
     */
    public String generateAdmin(Long adminId) {
        return generate(adminId, TYPE_ADMIN, props.getAdminExpireSeconds());
    }

    /**
     * 真正的签发逻辑（两个公开方法最终都汇到这里）。
     *
     * 【写法】抽成私有方法，而不是两处各写一遍 Jwts.builder()...
     * 【为什么】签发步骤有 6 环，任何一环漏掉（比如忘了 expiration）都会产生"永不过期的令牌"；
     *          抽成一处，只有一份实现要维护
     * 【代价】多一层调用；但两个公开方法只剩一行，反而更好读
     *
     * [id] 会被写进 subject，[type] 写进自定义 claim typ，[expireSeconds] 决定过期时刻
     */
    private String generate(Long id, String type, long expireSeconds) {
        Date now = new Date();
        // expireSeconds 是"秒"，Date 需要"毫秒"，所以 ×1000
        Date exp = new Date(now.getTime() + expireSeconds * 1000);
        return Jwts.builder()
                // subject = 用户/管理员 ID。J·WT 规范里 subject 是字符串，所以这里要 String.valueOf 转一下
                .subject(String.valueOf(id))
                // 自定义 claim：令牌类型，拦截器靠它区分"学生令牌"与"管理员令牌"
                .claim(CLAIM_TYPE, type)
                // 签发时间。解析时可以据此判断"这个令牌是什么时候发的"
                .issuedAt(now)
                // 过期时间。解析时 jjwt 会自动比对，过期直接抛 ExpiredJwtException
                .expiration(exp)
                // 用 HMAC 密钥签名，算法由密钥长度自动推断（32 字节 → HS256）
                .signWith(key)
                // 把上面所有内容拼成最终的三段式字符串 header.payload.signature
                .compact();
    }

    /**
     * 解析并验签。
     *
     * @return 解析成功返回 Claims
     * @throws BizException 令牌为空/格式错 → 1003；已过期 → 1002
     */
    public Claims parse(String token) {
        // 先挡空值：空令牌连解析都不必做，直接按"令牌无效"处理
        if (token == null || token.isBlank()) {
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }
        try {
            return Jwts.parser()
                    // verifyWith(key)：用同一个密钥验签。
                    // 这是整个鉴权的地基 —— 签名对不上说明令牌被改过（比如把 userId 改成别人），直接拒绝
                    .verifyWith(key)
                    .build()
                    // parseSignedClaims 同时做两件事：验签 + 检查是否过期
                    .parseSignedClaims(token)
                    // 取出 payload 部分（也就是 Claims：subject、exp、typ 等）
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // 单独识别过期：前端据此做"重新登录"提示，而不是笼统的凭证无效
            // 【写法】单独 catch ExpiredJwtException，再 catch 其余 JwtException
            // 【为什么】"过期"和"伪造"对用户是两种完全不同的处理：过期只需重新登录，
            //          伪造则可能是攻击行为。分成 1002 / 1003 两个码，前端才能做不同引导
            // 【另一种】统一 catch 成一个"凭证无效" —— 用户明明只是放久了，却被告知"令牌错误"，体验差
            throw BizException.of(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            // 其余情况：签名不对、格式错、三段缺失、密钥不匹配等
            log.debug("JWT 解析失败: {}", e.getMessage());
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }
    }

    /**
     * 从 Claims 取用户/管理员 ID。
     *
     * 【为什么要把 String 转成 Long】签发时 subject 必须写字符串（JWT 规范要求），
     * 解析回来仍是字符串；而业务里 userId 是 Long，所以要显式转换。
     */
    public Long getId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 从 Claims 取令牌类型（app / admin），历史令牌无此 claim 时按 app 处理。
     *
     * 【为什么 null 要兜底成 app】早期版本的令牌没有 typ 这个 claim；
     * 如果不兜底，老令牌会永远取不到类型，导致"升级后老用户全部掉线"。
     * 默认成 app 是兼容策略：老令牌本来就都是小程序用户的。
     */
    public String getType(Claims claims) {
        String type = claims.get(CLAIM_TYPE, String.class);
        return type == null ? TYPE_APP : type;
    }

    /**
     * 兼容旧调用：解析令牌取出 userId。
     *
     * @return 解析成功返回 userId；令牌为空 / 过期 / 被篡改则返回 null
     * @deprecated 新代码请用 parse(String)，它能区分"过期"与"伪造"
     */
    // 【写法】@Deprecated 标记 + 内部 catch 掉异常返回 null
    // 【为什么】@Deprecated 让 IDE 给新代码划删除线，阻止它继续扩散；
    //          而旧调用方期望"拿不到就返回 null"，所以这里把异常吞掉保持原语义
    // 【代价】吞异常会丢失"到底为什么失败"的信息，所以新代码不应该再用它
    @Deprecated
    /** 解析userid */
    public Long parseUserId(String token) {
        try {
            return getId(parse(token));
        } catch (BizException e) {
            return null;
        }
    }

    /** 小程序令牌有效期（秒），用于 Redis 白名单 TTL */
    // 白名单的过期时间必须和令牌一致：令牌过期了白名单却在，会留下无效数据；
    // 白名单过期早了，用户明明有令牌却被踢下线
    /** 取expireseconds */
    public long getExpireSeconds() {
        return props.getExpireSeconds();
    }

    /** 管理端令牌有效期（秒） */
    public long getAdminExpireSeconds() {
        return props.getAdminExpireSeconds();
    }
}
