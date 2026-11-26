package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.AnnouncementSaveReq;
import com.gdut.xiaojii.entity.Announcement;
import com.gdut.xiaojii.service.AnnouncementService;
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
 * 管理端：公告管理。
 *
 * @author 校集后端
 */
@Tag(name = "96. 公告管理（管理端）")
@Validated
@RestController
@RequestMapping("/admin/announcement")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "公告列表", description = "status：0 下线 / 1 上线，不传为全部")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<Announcement>> list(
            @Parameter(description = "状态") @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        return Result.ok(announcementService.adminList(status, page, size));
    }

    @Operation(summary = "新建/编辑公告", description = "不传 id 为新建，传 id 为编辑")
    @PostMapping("/save")
    /** 保存 */
    public Result<Long> save(@Valid @RequestBody AnnouncementSaveReq req) {
        return Result.ok(announcementService.save(req));
    }

    @Operation(summary = "上线/下线")
    @PostMapping("/{id}/status")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Integer status) {
        announcementService.toggle(id, status);
        return Result.ok();
    }
}
