package com.gdut.xiaojii.service;

import com.gdut.xiaojii.dto.req.DevLoginReq;
import com.gdut.xiaojii.dto.req.LoginReq;
import com.gdut.xiaojii.dto.vo.LoginVO;

/**
 * 登录服务。
 *
 * 【它在登录链路中的位置】
 * 第 4 站（契约层）。Controller 只认识这个接口，不认识 AuthServiceImpl。
 *
 * 【为什么要有接口】
 *   ① 依赖倒置：Controller 依赖抽象，实现替换不影响调用方（将来加短信登录、校园卡登录都只加实现）；
 *   ② 契约清晰：3 个方法就是"登录模块"的全部能力，读完这个文件就知道模块边界；
 *   ③ 便于测试：单测可以给 Controller 传一个 mock 的 AuthService，不需要启动 Spring、不需要数据库。
 *
 * 【这 3 个方法覆盖了登录的全部动作】
 *   loginByWx  —— 正式登录（走微信）
 *   loginByDev —— 开发登录（跳过微信，仅本地）
 *   logout     —— 退出（删 Redis 白名单，令牌立即失效）
 *
 * 注意：接口里没有 "refreshToken" / "getCurrentUser"。
 * 前者不需要（本项目令牌 7 天，过期就重新登录）；后者放在 UserService 里。
 *
 * @author 校集后端
 */
public interface AuthService {

    /**
     * 微信登录：code → openid → 查/建用户 → 签发 token → 写 Redis 白名单。
     *
     * 参数：LoginReq（含微信临时 code、可选昵称）
     * 返回：LoginVO（token + 用户基础信息 + 是否首次登录）
     * 异常：code 无效/过期 → BizException(1001)；用户被封禁 → BizException(1010)
     */
    LoginVO loginByWx(LoginReq req);

    /**
     * 开发登录：跳过微信，直接用标识当 openid（仅 dev-login-enabled=true 时可用）。
     *
     * 参数：DevLoginReq（openid 标识、可选昵称）
     * 返回：LoginVO（同上）
     * 异常：开关关闭 → BizException(403)
     */
    LoginVO loginByDev(DevLoginReq req);

    /**
     * 退出登录：删除 Redis 白名单（令牌立即失效）。
     *
     * 参数：无。当前用户从 UserContext（ThreadLocal）取。
     * 返回：void。没有"退出失败"这种业务语义，所以不需要返回值。
     */
    void logout();
    // ┌─ 【面试考点】为什么 logout 没有参数，也不返回成功/失败？ ──────────────
    // │ Q：退出登录既不用传 userId，也没有返回值，怎么知道退成功了？
    // │ A：① userId 来自 UserContext（拦截器已解析令牌写入），调用方不该也不能伪造，
    // │       这样天然防止"注销别人账号"；
    // │    ② 退出是幂等操作：删一个不存在的 key 也不会报错，
    // │       所以"删了"和"本来就没有"对调用方没有区别，无需返回结果。
    // │    反例：若设计成 logout(Long userId)，前端就能传任意 id —— 这是一个越权入口。
    // └────────────────────────────────────────────────────
}
