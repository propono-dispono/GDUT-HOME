package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.AdminUserStatusReq;
import com.gdut.xiaojii.dto.req.CreditAdjustReq;
import com.gdut.xiaojii.dto.vo.AdminUserItemVO;
import com.gdut.xiaojii.service.AdminConsoleService;
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
 * 管理端：用户管理。
 *
 * @author 校集后端
 */
@Tag(name = "94. 用户管理（管理端）")
@Validated
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminConsoleService adminConsoleService;

    @Operation(summary = "用户列表", description = "keyword 匹配昵称；status：1正常 2禁言 3封禁；verifyStatus：0未认证 1待审核 2已认证 3驳回")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<AdminUserItemVO>> list(
            @Parameter(description = "昵称关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "账号状态") @RequestParam(required = false) Integer status,
            @Parameter(description = "认证状态") @RequestParam(required = false) Integer verifyStatus,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        return Result.ok(adminConsoleService.userList(keyword, status, verifyStatus, page, size));
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{id}")
    /** 详情 */
    public Result<AdminUserItemVO> detail(@PathVariable Long id) {
        return Result.ok(adminConsoleService.userDetail(id));
    }

    @Operation(summary = "修改账号状态", description = "status：1正常（解禁）2禁言 3封禁")
    @PostMapping("/{id}/status")
    /** 变更状态 */
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody AdminUserStatusReq req) {
        adminConsoleService.changeUserStatus(id, req.getStatus(), req.getReason(),
                AdminContext.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "调整信用分", description = "delta 正数加分、负数扣分，服务端会 clamp 到 0-100")
    @PostMapping("/{id}/credit")
    /** 调整信用分 */
    public Result<Void> adjustCredit(@PathVariable Long id, @Valid @RequestBody CreditAdjustReq req) {
        adminConsoleService.adjustCredit(id, req.getDelta(), req.getReason(),
                AdminContext.requireAdminId());
        return Result.ok();
    }
}
