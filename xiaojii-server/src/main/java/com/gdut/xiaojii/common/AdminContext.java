package com.gdut.xiaojii.common;

/**
 * 管理端登录上下文（ThreadLocal）。
 *
 * <p><b>为什么与 {@link UserContext} 分开：</b>小程序用户与后台管理员是两套完全独立的主体
 * （对应 {@code user} 与 {@code admin_user} 两张表），ID 空间也可能重叠。
 * 如果共用同一个 ThreadLocal，一个 adminId=1 的请求会被当成 userId=1 的用户，
 * 造成越权。分开存放，谁也别想冒充谁。
 *
 * <p>与 UserContext 一样，必须在拦截器 {@code afterCompletion} 里 {@link #clear()}，
 * 否则 Tomcat 线程复用会串号。
 *
 * @author 校集后端
 */
public final class AdminContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void setAdminId(Long adminId) {
        HOLDER.set(adminId);
    }

    /** 未登录返回 null */
    public static Long getAdminId() {
        return HOLDER.get();
    }

    /** 未登录直接抛 401，业务代码里直接用这个 */
    public static Long requireAdminId() {
        Long id = HOLDER.get();
        if (id == null) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return id;
    }

    /** 清空 */
    public static void clear() {
        HOLDER.remove();
    }
}
