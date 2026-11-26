package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.vo.PostItemVO;
import com.gdut.xiaojii.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 收藏接口。
 *
 * @author 校集后端
 */
@Tag(name = "08. 收藏（小程序）")
@RestController
@RequestMapping("/app/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "收藏", description = "重复收藏幂等，不报错")
    @PostMapping("/{postId}")
    /** 添加 */
    public Result<Void> add(@PathVariable Long postId) {
        favoriteService.add(postId);
        return Result.ok();
    }

    @Operation(summary = "取消收藏", description = "未收藏时也返回成功")
    @DeleteMapping("/{postId}")
    /** 移除 */
    public Result<Void> remove(@PathVariable Long postId) {
        favoriteService.remove(postId);
        return Result.ok();
    }

    @Operation(summary = "收藏状态")
    @GetMapping("/{postId}/status")
    /** 状态 */
    public Result<Map<String, Boolean>> status(@PathVariable Long postId) {
        return Result.ok(Map.of("favored", favoriteService.favored(postId)));
    }

    @Operation(summary = "我的收藏", description = "按收藏时间倒序，返回信息流卡片结构")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<PostItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(favoriteService.list(page, size));
    }
}
