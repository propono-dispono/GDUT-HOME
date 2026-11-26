package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.annotation.RateLimit;
import com.gdut.xiaojii.dto.req.ReportSubmitReq;
import com.gdut.xiaojii.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端：举报。
 *
 * @author 校集后端
 */
@Tag(name = "06. 举报")
@RestController
@RequestMapping("/app/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "提交举报",
            description = "同一用户对同一对象只能举报一次；不能举报自己发布的内容。"
                    + "同一用户 1 天内最多举报 10 次，超出返回 429")
    // 【为什么举报也要限流】举报是"给管理员派活"的接口，被恶意刷会让审核台堆满无效工单
    // 【为什么 10 次/天而不是 100 次】正常用户一天举报 10 条已经很积极；
    //   真正需要批量投诉的场景（如同一卖家骗了 20 个人）应由管理员统一处理，不靠单用户刷
    // 【实际效果】用户 7 在一天内举报 10 次后，第 11 次返回
    //   {code:429, msg:"操作太频繁，请 X 小时后再试"}
    @RateLimit(api = "report:submit", limit = 10, windowSeconds = 86400)
    @PostMapping
    /** 提交 */
    public Result<Void> submit(@Valid @RequestBody ReportSubmitReq req) {
        reportService.submit(UserContext.requireUserId(), req);
        return Result.ok();
    }
}
