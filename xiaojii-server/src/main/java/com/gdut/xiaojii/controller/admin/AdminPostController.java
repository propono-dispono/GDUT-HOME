package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.AuditRejectReq;
import com.gdut.xiaojii.dto.vo.AdminPostItemVO;
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
 * 管理端：内容审核与强制下架。
 *
 * @author 校集后端
 */
@Tag(name = "92. 内容审核（管理端）")
@Validated
@RestController
@RequestMapping("/admin/post")
@RequiredArgsConstructor
public class AdminPostController {

    private final AdminAuditService adminAuditService;

    @Operation(summary = "内容列表",
            description = "status：业务状态(0待审 1展示中 2已下架 3已成交 4已关闭)；auditStatus：审核状态(0待审 1机器通过 2人工通过 3驳回)；均可留空")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<AdminPostItemVO>> list(
            @Parameter(description = "业务状态") @RequestParam(required = false) Integer status,
            @Parameter(description = "审核状态") @RequestParam(required = false) Integer auditStatus,
            @Parameter(description = "业务线，如 SECONDHAND") @RequestParam(required = false) String bizType,
            @Parameter(description = "标题关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        return Result.ok(adminAuditService.postList(status, auditStatus, bizType, keyword, page, size));
    }

    @Operation(summary = "内容通过", description = "status=1 展示中，audit_status=2 人工通过")
    @PostMapping("/{id}/approve")
    /** 通过 */
    public Result<Void> approve(@PathVariable Long id) {
        adminAuditService.approvePost(id, AdminContext.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "内容驳回", description = "status=2 已下架，audit_status=3 驳回；原因通知作者")
    @PostMapping("/{id}/reject")
    /** 驳回 */
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody AuditRejectReq req) {
        adminAuditService.rejectPost(id, req.getReason(), AdminContext.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "强制下架", description = "只对「展示中」的内容生效，用于已上架后出现违规的情形")
    @PostMapping("/{id}/offline")
    /** 下架 */
    public Result<Void> offline(@PathVariable Long id, @Valid @RequestBody AuditRejectReq req) {
        adminAuditService.offlinePost(id, req.getReason(), AdminContext.requireAdminId());
        return Result.ok();
    }
}
