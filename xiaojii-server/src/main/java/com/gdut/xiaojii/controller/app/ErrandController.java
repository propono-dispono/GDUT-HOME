package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.vo.ErrandTakeVO;
import com.gdut.xiaojii.service.ErrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端：跑腿接单。
 *
 * 【为什么单独一个 Controller，不塞进 PostController】
 * 发帖/列表属于"内容"，接单属于"履约"，是两条不同的链路：
 * 前者所有用户都会用，后者只有跑腿员用；路径前缀 /app/errand 也更符合直觉。
 *
 * 【这一组接口并发最激烈的是 take，其余都是状态推进】
 * take 是"多人抢同一个名额"，所以它的实现里有条件更新 + 唯一索引两道闸；
 * pick / deliver / confirm 是"一个人推进自己那单"，冲突只可能来自他自己双击。
 *
 * @author 校集后端
 */
@Tag(name = "10. 跑腿接单")
@Validated
@RestController
@RequestMapping("/app/errand")
@RequiredArgsConstructor
public class ErrandController {

    private final ErrandService errandService;

    /**
     * 接单。
     *
     * 【为什么不加 @RateLimit】接单是"抢"，用户抢不到会立刻重试，这是正常行为。
     * 加 5 次/小时这类限流会把真实用户挡在门外，而防重复提交已经由
     * "查我自己的进行中记录 + 唯一索引"解决了，不需要限流这一层。
     */
    @Operation(summary = "接单", description = "多人同时接同一单，只有一个人成功；重复提交返回 1102 而不是报错")
    @PostMapping("/{postId}/take")
    public Result<ErrandTakeVO> take(@PathVariable Long postId) {
        return Result.ok(errandService.take(postId));
    }

    /** 取消跑腿需求（发布者主动取消） */
    @Operation(summary = "取消接单", description = "仅「已接单未取件」可取消，每单限一次；取消后帖子重新可接")
    @PostMapping("/{postId}/cancel")
    public Result<ErrandTakeVO> cancel(@PathVariable Long postId) {
        return Result.ok(errandService.cancel(postId));
    }

    /** 撤回接单（接单者反悔，回到待接状态） */
    @Operation(summary = "发布者撤回", description = "帖子转下架，不再放出；仅发布者可调，且必须有进行中的接单")
    @PostMapping("/{postId}/revoke")
    public Result<ErrandTakeVO> revoke(@PathVariable Long postId) {
        return Result.ok(errandService.revoke(postId));
    }

    /** 接单：跑腿者确认接下这一单 */
    @Operation(summary = "确认取件", description = "接单人操作，进行中 → 已取件")
    @PostMapping("/{postId}/pick")
    public Result<ErrandTakeVO> pick(@PathVariable Long postId) {
        return Result.ok(errandService.pick(postId));
    }

    /** 送达：跑腿者标记已送到 */
    @Operation(summary = "标记送达", description = "接单人操作，已取件 → 已送达")
    @PostMapping("/{postId}/deliver")
    public Result<ErrandTakeVO> deliver(@PathVariable Long postId) {
        return Result.ok(errandService.deliver(postId));
    }

    /** 确认完成：发布者确认收到，流程结束 */
    @Operation(summary = "确认完成", description = "发布者操作，已送达 → 已完成，帖子同时关闭；重复确认幂等返回成功")
    @PostMapping("/{postId}/confirm")
    public Result<ErrandTakeVO> confirm(@PathVariable Long postId) {
        return Result.ok(errandService.confirm(postId));
    }

    /** 查某帖子当前进行中的跑腿单（前端轮询状态用） */
    @Operation(summary = "当前接单信息", description = "没人接时 data 为 null（这是正常状态，不是错误）")
    @GetMapping("/{postId}/current")
    public Result<ErrandTakeVO> current(@PathVariable Long postId) {
        return Result.ok(errandService.current(postId));
    }

    /** 我接的跑腿单列表（分页） */
    @Operation(summary = "我的接单列表", description = "含已完成/已取消的历史，按接单时间倒序")
    @GetMapping("/mine")
    public Result<PageResult<ErrandTakeVO>> mine(
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer size) {
        return Result.ok(errandService.mine(page, size));
    }
}
