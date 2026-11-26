package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.AuditRejectReq;
import com.gdut.xiaojii.dto.vo.AdminVerifyItemVO;
import com.gdut.xiaojii.service.AdminAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：校园认证审核。
 *
 * @author 校集后端
 */
@Tag(name = "91. 认证审核（管理端）")
@Validated
@RestController
@RequestMapping("/admin/verify")
@RequiredArgsConstructor
public class AdminVerifyController {

    private final AdminAuditService adminAuditService;

    @Operation(summary = "认证申请列表", description = "status：0 待审 / 1 通过 / 2 驳回，不传表示全部；待审排最前")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<AdminVerifyItemVO>> list(
            @Parameter(description = "审核状态") @RequestParam(required = false) Integer status,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        return Result.ok(adminAuditService.verifyList(status, page, size));
    }

    @Operation(summary = "认证通过", description = "user_verify.status=1，同时 user.verify_status=2 已认证")
    @PostMapping("/{id}/approve")
    /** 通过 */
    public Result<Void> approve(@PathVariable Long id) {
        adminAuditService.approveVerify(id, AdminContext.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "认证驳回", description = "驳回原因会通过站内通知发给用户，用户可修改后重新提交")
    @PostMapping("/{id}/reject")
    /** 驳回 */
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody AuditRejectReq req) {
        adminAuditService.rejectVerify(id, req.getReason(), AdminContext.requireAdminId());
        return Result.ok();
    }
}
