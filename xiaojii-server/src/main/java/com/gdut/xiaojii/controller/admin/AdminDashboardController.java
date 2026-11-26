package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.vo.AdminOverviewVO;
import com.gdut.xiaojii.service.AdminConsoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端：数据概览。
 *
 * @author 校集后端
 */
@Tag(name = "93. 数据概览（管理端）")
@RestController
@RequestMapping("/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminConsoleService adminConsoleService;

    @Operation(summary = "首页概览", description = "待审认证/待审内容/在架内容/今日发布/用户与认证统计")
    @GetMapping("/overview")
    /** 总览 */
    public Result<AdminOverviewVO> overview() {
        return Result.ok(adminConsoleService.overview());
    }
}
