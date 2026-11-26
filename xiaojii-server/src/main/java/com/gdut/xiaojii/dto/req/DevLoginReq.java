package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 本地开发登录请求。
 *
 * 【它在登录链路中的位置】
 * 第 2 站（并列分支）—— 和 LoginReq 二选一：
 *   正式登录：wx.login() → code → LoginReq → 换 openid
 *   开发登录：直接传一个标识 → DevLoginReq → 当作 openid
 * 它跳过了"微信换 openid"这一步，所以只在本地/联调时用。
 *
 * 【安全红线】
 * 只有 xiaojii.wx.dev-login-enabled=true 时才允许调用（判断在 AuthServiceImpl.loginByDev）。
 * 生产环境必须关掉，否则任何人传一个 openid 就能登录别人的账号。
 *
 * @author 校集后端
 */
// 【注解作用】@Data：生成 getter/setter。Jackson 反序列化需要 setter，去掉后参数全为 null。
@Data
// 【注解作用】@Schema：只影响接口文档的模型说明。
@Schema(description = "开发登录请求（仅本地调试）")
public class DevLoginReq {

    // 【注解作用】@NotBlank：校验"不能为 null、不能是空串、不能全是空白"。
    //   需要 Controller 参数上的 @Valid 才会执行。
    @NotBlank(message = "openid 不能为空")
    // 【注解作用】@Pattern：按正则校验格式。正则 ^[A-Za-z0-9_-]{3,64}$ 的含义：
    //   只允许字母、数字、下划线、中划线，长度 3~64。
    //   为什么开发登录要限制格式：openid 会被直接写进 user 表的 openid 字段，
    //   不限制的话可能被塞进超长字符串或特殊字符，污染数据、撑爆字段长度。
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$", message = "openid 只能包含字母数字下划线中划线，3-64 位")
    // 【注解作用】@Schema：仅文档说明，example 会显示在接口文档里当示例值。
    @Schema(description = "任意标识，会被当作 openid 建档", example = "dev_student_01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String openid;

    // 【注解作用】@Size(max=32)：长度上限 32 字符，与 user.nickname 的 VARCHAR(32) 对齐。
    @Size(max = 32, message = "昵称最多 32 个字")
    @Schema(description = "昵称，可选", example = "林同学")
    private String nickname;
    // ┌─ 【面试考点】为什么项目里要留一个"能伪造任意用户"的登录接口？ ──────────
    // │ Q：dev-login 看起来很危险，为什么不禁掉？
    // │ A：① 微信 AppID/Secret 未配置时（本地开发、新环境），正式登录根本走不通；
    // │    ② 真机扫码调试成本高，联调需要快速切换不同用户看效果；
    // │    ③ 关键不是"删掉它"，而是"用开关控制 + 生产关闭"。
    // │    项目用配置项 dev-login-enabled 控制，默认 true 便于开发，
    // │    部署时通过环境变量 WX_DEV_LOGIN=false 关掉。
    // │    面试点：这类"后门接口"的正确做法是**开关 + 环境隔离 + 日志留痕**
    // │    （AuthServiceImpl 里对 dev-login 打了 log.warn），而不是硬编码删除。
    // └────────────────────────────────────────────────────
}
