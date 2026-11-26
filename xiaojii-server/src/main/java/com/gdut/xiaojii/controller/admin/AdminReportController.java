package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.AdminContext;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.ReportHandleReq;
import com.gdut.xiaojii.dto.vo.AdminReportItemVO;
import com.gdut.xiaojii.service.ReportService;
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
 * 管理端：举报处理。
 *
 * @author 校集后端
 */
@Tag(name = "95. 举报处理（管理端）")
@Validated
@RestController
@RequestMapping("/admin/report")
@RequiredArgsConstructor
public class AdminReportController {

    private final ReportService reportService;

    @Operation(summary = "举报列表", description = "status：0 待处理 / 1 成立 / 2 不成立，不传为全部")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<AdminReportItemVO>> list(
            @Parameter(description = "处理状态") @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        return Result.ok(reportService.adminList(status, page, size));
    }

    @Operation(summary = "处理举报",
            description = "result=CONFIRMED 成立（举报内容会自动下架并通知作者）/ REJECTED 不成立")
    @PostMapping("/{id}/handle")
    /** 处理 */
    public Result<Void> handle(@PathVariable Long id, @Valid @RequestBody ReportHandleReq req) {
        reportService.handle(id, req, AdminContext.requireAdminId());
        return Result.ok();
    }
}
