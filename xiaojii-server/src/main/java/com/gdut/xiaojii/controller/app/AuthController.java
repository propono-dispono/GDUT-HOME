package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.DevLoginReq;
import com.gdut.xiaojii.dto.req.LoginReq;
import com.gdut.xiaojii.dto.vo.LoginVO;
import com.gdut.xiaojii.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端认证接口。
 *
 * Controller 只做三件事：接参校验、调 Service、包 Result。
 * 任何业务判断（能不能登录、要不要建档）都在 Service 里。
 *
 * 在登录链路中的位置：第 1 站（入口）
 *   AuthController → LoginReq → AuthService → AuthServiceImpl
 *     → WxApiClient → JwtUtil → Redis → LoginVO → Result
 *
 * 对外地址怎么算：
 *   context-path(/api) + @RequestMapping(/app/auth) + @PostMapping(/login)
 *   = POST /api/app/auth/login
 * 代码里永远不写 /api，那是 Tomcat 剥掉的。
 *
 * @author 校集后端
 */
// 只影响 Swagger/Knife4j 文档分组，不参与运行；不加则接口散落在"未分组"
@Tag(name = "01. 认证（小程序）")
// @Controller + @ResponseBody 的组合；不加则类内所有路径 404（返回值会被当页面模板名）
@RestController
// 类级路径前缀 /app/auth，与方法上的路径拼接；注意拦截器配的是 /app/**（不含 /api）
@RequestMapping("/app/auth")
// Lombok 生成"只含 final 字段"的构造器，Spring 用它做构造器注入；不加则 authService 为 null
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 微信登录。
     *
     * 链路：本方法 → AuthService.loginByWx → AuthServiceImpl → WxApiClient 换 openid
     *       → UserMapper 查/建用户 → JwtUtil 签令牌 → Redis 写白名单 → 返回 LoginVO
     *
     * 参数：req 由请求体 JSON 反序列化而来（字段见 LoginReq）。
     * 返回：Result<LoginVO>，成功时 data 是 token + 用户信息；失败时 data 为 null、msg 给用户看。
     *
     * 边界：① code 为空 → 被 @Valid 拦下返回 400；② code 过期/已用 → 1001；
     *       ③ 用户被封禁 → 1010；④ 首次登录顺便建档（信用分 80、未认证）。
     * 注意：没有 try-catch，业务失败一律由 Service 抛 BizException，交给全局异常处理器。
     *
     * 面试考点：参数不写 @Valid 会怎样？
     *   校验不执行 → code=null 传到 WxApiClient 拼出 js_code=null → 微信返回 40029
     *   → 用户看到"登录凭证已失效"（误导，实际是没传参）。
     */
    // 参数注解说明：@Valid 开启参数校验（触发 LoginReq 的 @NotBlank/@Size）；
    //   @RequestBody 把请求体 JSON 反序列化成 LoginReq。两个都不能省。
    // 只影响接口文档的标题与描述，不参与运行
    @Operation(summary = "微信登录", description = "用 wx.login() 的 code 换 token；首次登录自动建档")
    // 文档标记"本接口免登录"，Swagger 上不显示锁图标；只影响文档，不做鉴权
    @SecurityRequirements
    // HTTP = POST、路径 = /login；路径只看注解里的字符串，与 Java 方法名无关
    @PostMapping("/login")
    /** 登录 */
    public Result<LoginVO> login(@Valid @RequestBody LoginReq req) {
        return Result.ok(authService.loginByWx(req));
    }

    /**
     * 开发登录（仅本地/调试用）：跳过微信换 openid，直接把传入的标识当 openid。
     *
     * 边界（重要）：是否允许由 WxProperties.devLoginEnabled 控制，生产必须为 false。
     * 否则任何人传一个 openid 就能登录别人的账号 —— 最严重的越权。
     * 判断逻辑在 AuthServiceImpl.loginByDev 里，不在 Controller。
     */
    // 参数注解说明：同 login，@Valid 开启校验（含 @Pattern 格式限制）、@RequestBody 读 body
    // 只影响接口文档描述
    @Operation(summary = "开发登录（仅本地）",
            description = "跳过微信换取 openid，直接把标识当 openid 登录；需 xiaojii.wx.dev-login-enabled=true")
    // 文档标记免登录，不做鉴权
    @SecurityRequirements
    // HTTP = POST、路径 = /dev-login
    @PostMapping("/dev-login")
    public Result<LoginVO> devLogin(@Valid @RequestBody DevLoginReq req) {
        return Result.ok(authService.loginByDev(req));
    }

    /**
     * 退出登录。
     *
     * 链路：本方法 → AuthService.logout → AuthServiceImpl 删除 Redis 白名单 key
     *
     * 参数：无。当前用户从 UserContext（ThreadLocal）取，由 AuthInterceptor 在请求进入时写入，
     * 所以前端不用再传 userId —— 如果让它接 userId，就成了"能注销别人账号"的越权入口。
     *
     * 返回：Result<Void>，只关心成功与否。
     *
     * 边界：删的是 Redis 白名单 key；JWT 在有效期内仍"验签通过"，
     * 但过不了白名单比对，等价于立即失效。
     */
    // 只影响接口文档描述
    @Operation(summary = "退出登录", description = "删除服务端白名单，令牌立即失效")
    // HTTP = POST、路径 = /logout。
    // 刻意不用 GET：GET 语义应只读不改，而这里会删 Redis 数据（有副作用），
    // 且 GET 可能被浏览器/爬虫预取，导致"用户莫名被退出登录"
    @PostMapping("/logout")
    /** 退出 */
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }
}
