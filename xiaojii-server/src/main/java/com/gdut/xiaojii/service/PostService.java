package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.req.PostCreateReq;
import com.gdut.xiaojii.dto.req.PostQueryReq;
import com.gdut.xiaojii.dto.vo.CategoryVO;
import com.gdut.xiaojii.dto.vo.GroupbuyMemberVO;
import com.gdut.xiaojii.dto.vo.PostDetailVO;
import com.gdut.xiaojii.dto.vo.PostItemVO;
import com.gdut.xiaojii.entity.Post;

import java.util.List;

/**
 * 帖子服务：发布、信息流、详情、我的发布、关闭、删除。
 *
 * @author 校集后端
 */
public interface PostService {

    /** 发布（含敏感词校验、按信用分决定是否需要人工审核） */
    PostDetailVO create(PostCreateReq req);

    /** 信息流（只返回展示中的） */
    PageResult<PostItemVO> feed(PostQueryReq req);

    /** 详情（非本人在非展示状态下看不到） */
    PostDetailVO detail(Long id);

    /** 我的发布（含待审/下架/成交） */
    PageResult<PostItemVO> mine(PostQueryReq req);

    /** 关闭帖子（本人） */
    void close(Long id);

    /** 删除帖子（逻辑删除，本人） */
    void delete(Long id);

    /** 参加拼单（幂等：已参加直接返回；满员/截止会报错） */
    void joinGroupbuy(Long postId);

    /** 退出拼单（发起人不能退；退出后释放名额） */
    void leaveGroupbuy(Long postId);

    /**
     * 拼单成员列表：发起人永远排第一，后面按参与时间正序。
     *
     * 参数 postId：拼单帖 ID
     * 返回：发起人 + 所有状态为 JOINED 的参与者；帖子不存在或不是拼单抛 1030
     */
    List<GroupbuyMemberVO> groupbuyMembers(Long postId);

    /**
     * 我参与中的拼单（已退出的不显示）。
     *
     * 参数 page：页码，从 1 开始
     * 参数 size：每页条数，服务端压到 1~100
     * 返回：按参与时间倒序的拼单帖列表
     */
    PageResult<PostItemVO> joinedGroupbuys(int page, int size);

    /** 某个用户公开展示中的帖子（他人主页用） */
    PageResult<PostItemVO> userPosts(Long userId, int page, int size);

    /** 分类列表，可按业务线过滤 */
    List<CategoryVO> categories(String bizType);

    /**
     * 把帖子实体批量转成信息流卡片（收藏、他人主页等模块复用）。
     * 会自动补齐发布人首字、封面 URL、拼单进度、失物标签。
     */
    List<PostItemVO> assembleItems(List<Post> posts);
}
