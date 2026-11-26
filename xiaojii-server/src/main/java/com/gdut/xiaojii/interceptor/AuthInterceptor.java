package com.gdut.xiaojii.interceptor;

import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.JwtUtil;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.constant.RedisKey;
import com.gdut.xiaojii.config.JwtProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 小程序端登录拦截器：解析 JWT → 校验令牌类型 → 校验 Redis 白名单 → 写入 UserContext。
 *
 * 【它在整条链路里的位置】
 *   请求进来 → Tomcat → 本拦截器 preHandle → Controller → Service → Mapper → 返回
 *                      ↑ 越不过这里，就不可能有"未登录也能访问"的接口
 *
 * 【为什么要有 Redis 白名单（JWT 本身已经能验签）】
 * JWT 是"自证型"令牌，签发后服务端无法撤销。但业务上必须支持：
 * 用户被封禁后立即失效、用户主动退出、换设备登录踢掉旧设备。
 * 因此在 Redis 存 login:token:{userId} → 当前有效令牌，校验时比对一次。
 * 代价是一次 Redis GET（亚毫秒），换来"可主动踢人"。
 *
 * 【多端登录的取舍】
 * 当前设计是「一个用户同时只有一个有效令牌」，即新登录会踢掉旧设备。
 * 校园场景单人单机，这个取舍更安全也更好排查问题。
 * 若将来要支持多端，这里的 key 得改成 login:token:{userId}:{deviceId}，比对逻辑也要改。
 *
 * 【⚠️ 序列化约定】
 * 登录态只能用 StringRedisTemplate 读写。
 * 若用 RedisTemplate<String,Object>，value 会被 JSON 序列化成带双引号的 "eyJhbGci..."
 * 与请求头里的裸 token 永远不相等，表现为"登录成功后立刻 1003"。
 *
 * @author 校集后端
 */
// 【写法】@Slf4j
// 【为什么】本类目前只做校验、不记日志（异常由全局处理器记）；
//          保留它是为了后续加"令牌即将过期"这类日志时不用再改类声明
@Slf4j
// 【写法】@Component
// 【为什么】WebMvcConfig 注册拦截器时要拿它的实例，必须先是 Spring Bean
@Component
// 【写法】@RequiredArgsConstructor + final 字段
// 【为什么】三个依赖都由 Spring 注入；final 保证不可变，也保证缺依赖时启动就报错
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    // JWT 工具：负责验签、取 userId、取令牌类型（它自己不认识 Redis）
    private final JwtUtil jwtUtil;
    // JWT 配置：提供请求头名（默认 Authorization）与前缀（默认 "Bearer "）
    // 之所以不写死字符串，是为了将来换 header 名时只改 yml
    private final JwtProperties jwtProperties;
    // Redis 客户端：读"当前有效令牌"与"账号状态"两个缓存
    private final StringRedisTemplate redis;

    /** 兼容旧代码的 key 前缀（新代码请用 RedisKey.loginToken(Long)） */
    // 【写法】@Deprecated + 指向新的统一入口 RedisKey.LOGIN_TOKEN
    // 【为什么】早期代码直接拼 "login:token:" + userId；后来统一收口到 RedisKey，
    //   这里保留常量是为了不让老代码编译失败，同时用 @Deprecated 提示"不要再用"
    @Deprecated
    public static final String LOGIN_KEY_PREFIX = RedisKey.LOGIN_TOKEN;

    /**
     * 请求进入 Controller 之前执行。
     *
     * @return true = 放行进入 Controller；本方法**从不返回 false** —— 校验失败一律抛异常，
     *         交给 GlobalExceptionHandler 转成 {code,msg}。原因见下面注释。
     */
    // 【写法】返回 boolean，但失败路径全部 throw，不 return false
    // 【为什么】return false 只会让请求"停在这里"，响应体是空的；
    //   前端拿不到 401/1003 这类码，只知道"请求没反应"，无法引导用户去登录。
    //   抛 BizException 则会被 @RestControllerAdvice 接住，返回 {code:401,msg:"请先登录"}。
    // 【另一种】return false 后自己往 response 里写 JSON —— 能做，但要手动处理序列化、
    //   Content-Type、编码，重复了全局处理器已有的工作。
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 【写法】OPTIONS 预检请求直接放行，连令牌都不看
        // 【为什么】浏览器的跨域预检请求（OPTIONS）不会带自定义头，如果也要求令牌，
        //   预检就会 401，真正的请求根本发不出去。小程序不走 CORS，但本地 Web 调试需要。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 第 1 步：从请求头取令牌（会去掉 "Bearer " 前缀）
        String token = resolveToken(request);
        // 没带令牌 = 未登录（401）；带了但非法/过期 = 凭证问题（1002/1003）
        // 【为什么这两种要分开】401 是"你还没登录"（前端应跳登录页），
        //   1002/1003 是"你的令牌坏了"（前端应清本地缓存再登录），提示语不同
        if (token == null || token.isBlank()) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }

        // 第 2 步：验签 + 查过期。签名不对、被篡改、已过期都在这里抛异常
        //   · 过期 → JwtUtil.parse 内部抛 TOKEN_EXPIRED(1002)
        //   · 伪造 → 抛 TOKEN_INVALID(1003)
        Claims claims = jwtUtil.parse(token);
        Long userId = jwtUtil.getId(claims);

        // 第 3 步：校验令牌类型。
        // 【为什么必须校验】学生令牌和管理员令牌用同一个密钥签名，
        //   如果只看"能验签通过"就放行，那么一个学生可以拿自己的令牌去调 /admin/** 接口。
        //   这里要求 typ 必须是 app，管理端拦截器要求必须是 admin，两条链互不通用。
        if (!JwtUtil.TYPE_APP.equals(jwtUtil.getType(claims))) {
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }

        // 第 4 步：白名单校验 —— Redis 里存的必须和这次带来的一致。
        // 【为什么验签通过还要比对】JWT 无法撤销。用户退出、换设备、被封禁时，
        //   服务端只能删掉 Redis 里的那条记录；旧令牌本身在 7 天内仍然"验签通过"，
        //   只有比对失败才能让它立即失效。
        String cached = redis.opsForValue().get(RedisKey.loginToken(userId));
        if (cached == null || !cached.equals(token)) {
            // 令牌已被踢掉（换设备 / 退出 / 封禁）
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }

        // 第 5 步：封禁校验（读缓存，避免每个请求查库；封禁时服务端会立即删除该 key）
        // 【写法】状态判断用 "3".equals(status) 而不是 status.equals("3")
        // 【为什么】status 可能为 null（缓存过期/刚被删），写成 status.equals 会抛 NPE；
        //   把常量放前面，null 时安全返回 false。这是字符串比较的通用防御写法。
        String status = redis.opsForValue().get(RedisKey.userStatus(userId));
        if ("3".equals(status)) {
            throw BizException.of(ResultCode.USER_BANNED);
        }

        // 第 6 步：把 userId 放进 ThreadLocal。
        // 【为什么】后面的 Service 层要拿当前用户（比如 noticeService.list(userId, ...)），
        //   如果靠参数层层传递，每个方法签名都要加 userId；放 ThreadLocal 后，
        //   同一线程内任何地方都能 UserContext.requireUserId() 直接取。
        // 【代价】ThreadLocal 是"隐式参数"，读代码时看不到它的来源，必须靠注释和约定
        UserContext.setUserId(userId);
        return true;
    }

    /**
     * 请求结束清理 ThreadLocal。
     * ⚠️ 必须放在 afterCompletion：即使 Controller 抛异常也会执行，
     * 否则线程被 Tomcat 复用时会串到下一个用户（越权风险）。
     */
    // 【写法】重写 afterCompletion（而不是 postHandle）
    // 【为什么】postHandle 在 Controller 抛异常时不会执行；afterCompletion 无论成功失败都会执行，
    //   才能保证 ThreadLocal 一定被清掉。
    // 【不清理的后果（真实风险）】Tomcat 用线程池处理请求，同一个线程会服务很多用户。
    //   如果上一个用户的 userId 留在 ThreadLocal 里，下一个没带令牌的请求
    //   可能读到"上一个用户"的 id —— 这就是越权。
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 从请求头取令牌，去掉 "Bearer " 前缀。
     * 容忍不带前缀的裸 token（方便 Postman / curl 调试）。
     */
    // 【写法】前缀用配置（jwtProperties.getPrefix()）而不是写死 "Bearer "
    // 【为什么】前后端约定可能变（有的团队用 "Token "）；写在配置里改 yml 即可，不用改代码
    // 【宽松处理】startsWith 判断，有前缀就截掉，没有就原样返回 ——
    //   好处是 Postman 调试时直接粘裸 token 也能用；代价是"格式错误"不会在这里被发现，
    //   而是往后传到验签阶段才失败（错误码变成 1003 而不是 401）
    private String resolveToken(HttpServletRequest request) {
        // 请求头名来自配置（默认 Authorization）
        String raw = request.getHeader(jwtProperties.getHeader());
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String prefix = jwtProperties.getPrefix();
        // substring(prefix.length()) 截掉前缀；trim() 去掉可能存在的前后空格
        // （常见坑：前端拼成 "Bearer  xxx" 两个空格，不 trim 会导致验签失败）
        return raw.startsWith(prefix) ? raw.substring(prefix.length()).trim() : raw.trim();
    }
}
