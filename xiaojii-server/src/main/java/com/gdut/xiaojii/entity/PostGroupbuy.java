package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拼单扩展表 post_groupbuy，一条帖子最多一行。
 *
 * 写入：PostServiceImpl.saveExt（发布时插一行，currentCount=1 表示发起人自占一席）
 * 改：参加 +1 / 退出 -1，都是条件更新（见 joinGroupbuy / leaveGroupbuy）
 *
 * 【为什么把人数冗余在这里】详情页要显示"3/5"，每次实时 count 成员表会让列表页变成 N+1；
 * 冗余一份，写入时与成员表同事务，保证两者不打架。
 * 【拼单不走订单】成团后成员记在 groupbuy_member，没有支付环节，双方线下结算。
 *
 * @author 校集后端
 */
// 【来源】第三方库：Lombok。@Data 生成 getter/setter，MP 填值依赖 setter
@Data
// 【来源】第三方库：MyBatis-Plus。绑定表名
@TableName("post_groupbuy")
public class PostGroupbuy {

    /** 主键兼关联键，值 = post.id；非自增列，由 saveExt 手动赋值 */
    @TableId
    private Long postId;

    /** 目标人数（含发起人），至少 2，下限由 checkBizRequiredFields 保证 */
    private Integer targetCount;

    /** 当前人数（含发起人）。创建时 1；参加 +1、退出 -1；达到 target 即满员并关帖 */
    private Integer currentCount;

    /** 截止时间，同时写进 post.expire_at，到期由 GroupbuyExpireJob 关帖 */
    private LocalDateTime deadline;

    /** 成团后是否需代取：0 否 / 1 是。不填兜底 0 */
    private Integer withErrand;

    /** 自提点，如"一饭门口"。NOT NULL DEFAULT ''，统一用空串，前端只判 isEmpty() */
    private String pickupPoint;
}
