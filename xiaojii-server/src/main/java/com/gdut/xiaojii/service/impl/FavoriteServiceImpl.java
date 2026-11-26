package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.dto.vo.PostItemVO;
import com.gdut.xiaojii.entity.Favorite;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.mapper.FavoriteMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import com.gdut.xiaojii.service.FavoriteService;
import com.gdut.xiaojii.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏实现。
 *
 * <p><b>幂等：</b>重复收藏、重复取消都不报错 —— 用户快速连点不应该看到错误。
 *
 * @author 校集后端
 */
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    /** post.status = 1 表示展示中（与前端 PostStatus 一致） */
    private static final int POST_ONLINE = 1;

    private final FavoriteMapper favoriteMapper;
    private final PostMapper postMapper;
    private final PostService postService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 添加 */
    public void add(Long postId) {
        Long userId = UserContext.requireUserId();
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() == null || post.getStatus() != POST_ONLINE) {
            throw BizException.of(ResultCode.NOT_FOUND, "内容不存在或已下架");
        }
        Long exists = favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getPostId, postId));
        if (exists != null && exists > 0) {
            return;
        }
        Favorite f = new Favorite();
        f.setUserId(userId);
        f.setPostId(postId);
        favoriteMapper.insert(f);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 移除 */
    public void remove(Long postId) {
        Long userId = UserContext.requireUserId();
        favoriteMapper.delete(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getPostId, postId));
    }

    @Override
    public boolean favored(Long postId) {
        Long userId = UserContext.requireUserId();
        Long count = favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getPostId, postId));
        return count != null && count > 0;
    }

    @Override
    /** 列表 */
    public PageResult<PostItemVO> list(int page, int size) {
        Long userId = UserContext.requireUserId();
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));

        IPage<Favorite> favPage = favoriteMapper.selectPage(
                new Page<>(p, s),
                Wrappers.<Favorite>lambdaQuery()
                        .eq(Favorite::getUserId, userId)
                        .orderByDesc(Favorite::getId));

        List<Long> postIds = favPage.getRecords().stream()
                .map(Favorite::getPostId)
                .toList();
        if (postIds.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), favPage.getTotal(), p, s);
        }

        // selectBatchIds 会自动过滤逻辑删除的帖子；再按收藏顺序还原
        Map<Long, Post> postMap = postMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Post> ordered = postIds.stream()
                .map(postMap::get)
                .filter(Objects::nonNull)
                .toList();

        List<PostItemVO> list = postService.assembleItems(ordered);
        return new PageResult<>(list, favPage.getTotal(), p, s);
    }
}
