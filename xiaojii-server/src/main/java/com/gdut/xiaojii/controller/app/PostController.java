package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.common.annotation.RateLimit;
import com.gdut.xiaojii.dto.req.PostCreateReq;
import com.gdut.xiaojii.dto.req.PostQueryReq;
import com.gdut.xiaojii.dto.vo.CategoryVO;
import com.gdut.xiaojii.dto.vo.GroupbuyMemberVO;
import com.gdut.xiaojii.dto.vo.PostDetailVO;
import com.gdut.xiaojii.dto.vo.PostItemVO;
import com.gdut.xiaojii.service.PostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 帖子接口：发布、信息流、详情、我的发布、拼单参加/退出/成员、关闭、删除、分类。
 * 六条业务线（二手/跑腿/失物/技能/租赁/拼单）共用 /app/post，靠 bizType 区分。
 *
 * 【四种接口形态】
 *   ① 写入 POST + @Valid @RequestBody     → create
 *   ② 查询 GET + 查询参数                  → feed / mine / categories / detail / userPosts
 *   ③ 状态变更 POST /{id}/动作             → join / leave / close（有副作用，所以不用 GET）
 *   ④ 删除 DELETE /{id}                    → delete（全项目唯一用 DELETE 的地方）
 *
 * 【架构上的一处不一致】本类完全不碰 UserContext，当前用户由 Service 内部自己取；
 * 而 Notice / Chat / Report 是"Controller 取 userId 再显式传给 Service"。
 *
 * @author 校集后端
 */
@Tag(name = "04. 发布与信息流")
@RestController
@RequestMapping("/app/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @Operation(summary = "发布帖子",
            description = "需已认证且信用分 ≥60；信用分 ≥80 且历史发布 ≥3 条则先发后审，否则进人工审核。"
                    + "同一用户 1 小时内最多发布 5 条，超出返回 429")
    // 限流 5 次/小时：在校生一天发 5 条闲置已够多；真刷屏的是脚本，5 条足够挡
    @RateLimit(api = "post:create", limit = 5, windowSeconds = 3600)
    @PostMapping
    /** 创建 */
    public Result<PostDetailVO> create(@Valid @RequestBody PostCreateReq req) {
        return Result.ok(postService.create(req));
    }

    /** 信息流列表。分页 + 按业务线/分类/关键词筛选 + 排序 */
    @Operation(summary = "信息流", description = "只返回展示中的内容；sort 支持 latest/priceAsc/priceDesc")
    @GetMapping("/feed")
    public Result<PageResult<PostItemVO>> feed(@Valid PostQueryReq req) {
        return Result.ok(postService.feed(req));
    }

    /** 我的发布列表 */
    @Operation(summary = "我的发布", description = "含待审/下架/成交等全部状态")
    @GetMapping("/mine")
    public Result<PageResult<PostItemVO>> mine(@Valid PostQueryReq req) {
        return Result.ok(postService.mine(req));
    }

    /** 分类列表。按业务线返回二级分类（前端发帖时选） */
    @Operation(summary = "分类列表", description = "不传 bizType 返回全部；分类来自枚举，不查库")
    @GetMapping("/categories")
    public Result<List<CategoryVO>> categories(
            @Parameter(description = "业务线，如 SECONDHAND") @RequestParam(required = false) String bizType) {
        return Result.ok(postService.categories(bizType));
    }

    /** 某用户发布的帖子（看他人主页用） */
    @Operation(summary = "某个用户公开的帖子", description = "他人主页用；只返回展示中的内容")
    @GetMapping("/user/{userId}")
    public Result<PageResult<PostItemVO>> userPosts(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(postService.userPosts(userId, page, size));
    }

    /** 帖子详情。含扩展字段、发布人、按钮文案、是否已收藏 */
    @Operation(summary = "帖子详情", description = "含扩展字段、发布人、合规提示；非本人只能看展示中的")
    @GetMapping("/{id}")
    public Result<PostDetailVO> detail(@PathVariable Long id) {
        return Result.ok(postService.detail(id));
    }

    /**
     * 参加拼单 —— 并发入口，锁与 CAS 都在 Service 里（PostServiceImpl.joinGroupbuy）。
     * 错误码：满员 1070 / 已结束或已截止 1073 / 发起人自己参加 1074。
     */
    @Operation(summary = "参加拼单", description = "幂等；满员/截止报错；满员后自动关帖")
    @PostMapping("/{id}/join")
    public Result<Void> joinGroupbuy(@PathVariable Long id) {
        postService.joinGroupbuy(id);
        return Result.ok();
    }

    /** 退出拼单。发起人不能退（1072），没参加不能退（1071）；退出后释放名额 */
    @Operation(summary = "退出拼单", description = "发起人不能退；退出后释放名额")
    @PostMapping("/{id}/leave")
    public Result<Void> leaveGroupbuy(@PathVariable Long id) {
        postService.leaveGroupbuy(id);
        return Result.ok();
    }

    // 成员数上限就是目标人数（通常 3~10 人），一次全返回，不分页
    @Operation(summary = "拼单成员列表",
            description = "发起人固定排第一位，其余按参与先后排列；非拼单帖返回 1030")
    @GetMapping("/{id}/groupbuy/members")
    public Result<List<GroupbuyMemberVO>> groupbuyMembers(@PathVariable Long id) {
        return Result.ok(postService.groupbuyMembers(id));
    }

    // 字面量路径优先于 /{id}，所以 "groupbuy" 不会被当成 id 去做类型转换
    @Operation(summary = "我参与的拼单",
            description = "只返回仍在参与中的（已退出的不显示），按参与时间倒序")
    @GetMapping("/groupbuy/joined")
    public Result<PageResult<PostItemVO>> joinedGroupbuys(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(postService.joinedGroupbuys(page, size));
    }

    /** 关闭帖子（作者主动结束，如已卖出或不再需要） */
    @Operation(summary = "关闭帖子", description = "本人操作；展示中/待审可关，成交后不可关")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        postService.close(id);
        return Result.ok();
    }

    /** 删除帖子（逻辑删除，仅本人可操作） */
    @Operation(summary = "删除帖子", description = "逻辑删除，仅本人")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(id);
        return Result.ok();
    }

}
