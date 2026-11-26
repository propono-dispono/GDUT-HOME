package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.AdminLoginReq;
import com.gdut.xiaojii.dto.req.AdminPasswordReq;
import com.gdut.xiaojii.dto.vo.AdminLoginVO;
import com.gdut.xiaojii.service.AdminAuthService;
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
 * 管理端认证接口。
 *
 * <p>路径前缀 {@code /admin} 走独立的拦截链（AdminAuthInterceptor），
 * 与小程序用户令牌完全隔离。
 *
 * @author 校集后端
 */
@Tag(name = "90. 认证（管理端）")
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @Operation(summary = "管理员登录")
    @SecurityRequirements
    @PostMapping("/login")
    /** 登录 */
    public Result<AdminLoginVO> login(@Valid @RequestBody AdminLoginReq req) {
        return Result.ok(adminAuthService.login(req));
    }

    @Operation(summary = "修改密码", description = "校验原密码；修改成功后当前令牌立即失效，需要重新登录")
    @PostMapping("/password")
    /** 变更password */
    public Result<Void> changePassword(@Valid @RequestBody AdminPasswordReq req) {
        adminAuthService.changePassword(AdminContext.requireAdminId(),
                req.getOldPassword(), req.getNewPassword());
        return Result.ok();
    }

    @Operation(summary = "管理员退出")
    @PostMapping("/logout")
    /** 退出 */
    public Result<Void> logout() {
        adminAuthService.logout();
        return Result.ok();
    }
}
