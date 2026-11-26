package com.gdut.xiaojii.interceptor;

import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.JwtUtil;
import com.gdut.xiaojii.common.ResultCode;
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
 * 管理端登录拦截器。
 *
 * <p><b>为什么必须单独一条链：</b>原骨架让 {@code /admin/**} 复用小程序用户令牌，
 * 意味着任何一个普通学生拿到 token 就能调后台接口（封禁别人、改信用分）。
 * 这里强制校验 {@code typ=admin}，并且只认 {@code login:admin:{adminId}} 里的令牌。
 *
 * <p>管理员与小程序用户是两套主体（{@code admin_user} / {@code user}），
 * 因此 Context 也分开：{@link AdminContext}，不写 UserContext。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        Claims claims = jwtUtil.parse(token);

        if (!JwtUtil.TYPE_ADMIN.equals(jwtUtil.getType(claims))) {
            // 用小程序令牌调后台 → 1003，不给任何"是不是路径写错了"的提示
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }

        Long adminId = jwtUtil.getId(claims);
        String cached = redis.opsForValue().get(RedisKey.loginAdminToken(adminId));
        if (cached == null || !cached.equals(token)) {
            throw BizException.of(ResultCode.TOKEN_INVALID);
        }

        AdminContext.setAdminId(adminId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AdminContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String raw = request.getHeader(jwtProperties.getHeader());
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String prefix = jwtProperties.getPrefix();
        return raw.startsWith(prefix) ? raw.substring(prefix.length()).trim() : raw.trim();
    }
}
