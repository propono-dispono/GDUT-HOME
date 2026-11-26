package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.JwtUtil;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.constant.RedisKey;
import com.gdut.xiaojii.common.util.PasswordUtil;
import com.gdut.xiaojii.dto.req.AdminLoginReq;
import com.gdut.xiaojii.dto.vo.AdminLoginVO;
import com.gdut.xiaojii.entity.AdminUser;
import com.gdut.xiaojii.mapper.AdminUserMapper;
import com.gdut.xiaojii.service.AdminAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 管理端登录实现。
 *
 * <p><b>安全要点：</b>
 * <ol>
 *   <li>账号不存在与密码错误返回同一句"账号或密码错误"——否则等于送了个账号枚举接口</li>
 *   <li>密码用 PBKDF2 校验，明文不入库</li>
 *   <li>令牌带 {@code typ=admin}，小程序用户令牌拿过来会被拦截器拒绝</li>
 *   <li>管理端令牌 12 小时过期，比用户端 7 天短得多</li>
 * </ol>
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthServiceImpl implements AdminAuthService {

    private final AdminUserMapper adminUserMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;

    /** 同一 IP + 账号连续失败 5 次，锁 10 分钟 */
    private static final int MAX_LOGIN_FAILS = 5;
    private static final Duration LOGIN_LOCK = Duration.ofMinutes(10);

    @Override
    /** 登录 */
    public AdminLoginVO login(AdminLoginReq req) {
        String username = req.getUsername().trim();
        String failKey = loginFailKey(username);
        String fails = redis.opsForValue().get(failKey);
        if (fails != null && Integer.parseInt(fails) >= MAX_LOGIN_FAILS) {
            throw BizException.of(ResultCode.ADMIN_LOGIN_FAIL, "尝试次数过多，请 10 分钟后再试");
        }

        AdminUser admin = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, username));

        // 统一错误文案，不区分"账号不存在"与"密码错误"
        if (admin == null || !PasswordUtil.verify(req.getPassword(), admin.getPasswordHash())) {
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1L) {
                redis.expire(failKey, LOGIN_LOCK);
            }
            log.warn("[管理端登录失败] username={} ip={}", username, clientIp());
            throw BizException.of(ResultCode.ADMIN_LOGIN_FAIL);
        }
        if (admin.getStatus() != null && admin.getStatus() == AdminUser.STATUS_DISABLED) {
            throw BizException.of(ResultCode.ADMIN_DISABLED);
        }

        // 登录成功清空失败计数
        redis.delete(failKey);

        admin.setLastLoginAt(LocalDateTime.now());
        adminUserMapper.updateById(admin);

        String token = jwtUtil.generateAdmin(admin.getId());
        redis.opsForValue().set(RedisKey.loginAdminToken(admin.getId()), token,
                Duration.ofSeconds(jwtUtil.getAdminExpireSeconds()));

        log.info("[管理端登录成功] adminId={} username={}", admin.getId(), admin.getUsername());

        AdminLoginVO vo = new AdminLoginVO();
        vo.setToken(token);
        vo.setAdminId(admin.getId());
        vo.setNickname(admin.getNickname());
        vo.setRole(admin.getRole());
        return vo;
    }

    @Override
    /** 退出 */
    public void logout() {
        Long adminId = AdminContext.requireAdminId();
        redis.delete(RedisKey.loginAdminToken(adminId));
    }

    @Override
    /** 变更password */
    public void changePassword(Long adminId, String oldPassword, String newPassword) {
        AdminUser admin = adminUserMapper.selectById(adminId);
        if (admin == null) {
            throw BizException.of(ResultCode.ADMIN_LOGIN_FAIL, "账号不存在");
        }
        if (!PasswordUtil.verify(oldPassword, admin.getPasswordHash())) {
            throw BizException.of(ResultCode.ADMIN_LOGIN_FAIL, "原密码不正确");
        }
        if (oldPassword.equals(newPassword)) {
            throw BizException.of(ResultCode.PARAM_ERROR, "新密码不能与原密码相同");
        }
        admin.setPasswordHash(PasswordUtil.hash(newPassword));
        adminUserMapper.updateById(admin);
        // 改密后强制重新登录：删掉白名单里的旧令牌
        redis.delete(RedisKey.loginAdminToken(adminId));
        log.info("[管理端修改密码] adminId={}", adminId);
    }

    /** 登录失败计数 key：按 IP + 账号，避免一个 IP 把管理员账号全局锁死 */
    private String loginFailKey(String username) {
        return "login:admin:fail:" + clientIp() + ":" + username;
    }

    /** 取真实客户端 IP（Nginx 会透传 X-Forwarded-For） */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "unknown";
            }
            HttpServletRequest request = attrs.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
