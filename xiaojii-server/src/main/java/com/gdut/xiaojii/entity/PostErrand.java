package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑腿代办扩展表 post_errand 的实体，一条帖子在这里最多有一行。
 *
 * 谁写：PostServiceImpl 的 saveExt（发布帖子、且业务线是 ERRAND 时插一行）
 * 谁读：PostServiceImpl 的 detail（第 694 行 selectById）拼出详情返回体
 *
 * 【为什么单独一张表，不塞进 post 主表】
 * 只有跑腿才需要取件地、送达地、截止时间、是否垫付，塞进主表会让其余五条业务线的行
 * 永远挂着 4 个 null。拆表的代价是详情页多查一次（按主键查，成本可忽略）。
 *
 * 【主键为什么是 post_id，而不是自增 id】
 * 一条帖子最多一条跑腿扩展，post_id 天然唯一，用它当主键顺便把"一对一"这个约束
 * 交给了数据库，不用再额外建唯一索引。
 * ⚠️ 这一列不是自增列，值来自 post 表的自增 id，由 saveExt 手动 set。
 * 全局配置是 id-type: auto，MyBatis-Plus 对这种实体生成的是"postId 非空才写入"的条件 SQL，
 * 所以漏了 setPostId 不会静默插进去，而是直接报 post_id 没有默认值。
 *
 * @author 校集后端
 */
// 【来源】第三方库：Lombok。@Data = getter + setter + toString + equals/hashCode。
// 【为什么 setter 不能省】MyBatis-Plus 查询后靠 setter 填值、Jackson 序列化也靠它，只留 getter 会读不出数据。
@Data
// 【来源】第三方库：MyBatis-Plus。把实体和表名绑死，省去在 XML 里配 resultMap。
@TableName("post_errand")
public class PostErrand {

    /**
     * 主键，同时是关联键：值等于 post.id。
     * 写入时由 saveExt 传入；查询时 selectById(postId) 就是查这一列。
     */
    // 【注解作用】第三方注解，标记本字段是表主键，MP 的 updateById / selectById 靠它拼 where 条件。
    @TableId
    private Long postId;

    /** 取件地，如"菜鸟驿站(东区)"。DDL 里是 NOT NULL，非空由 checkBizRequiredFields 提前保证 */
    private String pickupAddr;

    /** 送达地，如"3 栋 502"。同样 NOT NULL，同样是 Service 层先校验 */
    private String deliverAddr;

    /** 最晚送达时间。saveExt 之外，resolveExpireAt 还会把它写进 post.expire_at，由定时任务据此关帖 */
    private LocalDateTime deadline;

    /** 是否需垫付：0 不需要（对方先付钱） / 1 需要（接单的人先掏钱买）。前端不填时 saveExt 兜底为 0 */
    private Integer advancePay;
}
