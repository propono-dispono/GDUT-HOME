package com.gdut.xiaojii.dto.vo;

import lombok.Data;

/**
 * 拼单成员（单个参与者）的展示对象。
 *
 * 【为什么单独建一个 VO 而不是直接返回 User】
 * User 实体里有 openid、unionid、status、credit_score 等字段。
 * 拼单成员列表只需要"头像 + 昵称 + 是不是发起人"，多了就是数据泄露面。
 *
 * 【为什么 owner 要单独标出来】
 * 发起人不在 groupbuy_member 表里（他没有"参与"这个动作，是创建时默认占位），
 * 所以列表是"发起人 + 成员表里的人"拼接而成。前端要靠这个字段把发起人排在最前、
 * 并给一个「发起人」标签。
 *
 * @author 校集后端
 */
@Data
public class GroupbuyMemberVO {

    /** 用户 ID，前端点头像跳个人主页时用 */
    private Long userId;

    /** 昵称，如「李同学」 */
    private String nickname;

    /** 头像 URL；没设置头像时为空串，前端用 initial 兜底显示 */
    private String avatar;

    /** 昵称首字，如「李」；头像为空时显示在圆形占位块里 */
    private String initial;

    /**
     * 是否发起人。
     * 实际值：发起人 true；普通参与者 false
     */
    private Boolean owner;

    /**
     * 参与时间，格式 yyyy-MM-dd HH:mm。
     * 发起人没有参与时间（他就是创建者），此时为 null
     */
    private String joinedAt;
}
