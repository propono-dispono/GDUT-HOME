package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.PostItemVO;

/**
 * 收藏服务：收藏 / 取消 / 状态 / 我的收藏列表。
 *
 * @author 校集后端
 */
public interface FavoriteService {

    /** 收藏（幂等：已收藏再调不报错） */
    void add(Long postId);

    /** 取消收藏（幂等：没收藏也不报错） */
    void remove(Long postId);

    /** 当前用户是否已收藏 */
    boolean favored(Long postId);

    /** 我的收藏，按收藏时间倒序 */
    PageResult<PostItemVO> list(int page, int size);
}
