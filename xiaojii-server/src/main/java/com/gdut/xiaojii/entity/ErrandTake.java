package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑腿接单记录表 errand_take 的实体。
 *
 * 【一句话】这条记录就是"这单被谁接了、干到哪一步了"的那张纸。
 * 一个帖子可以有多条记录（取消一次就是一条），但**同时只能有一条是进行中**。
 *
 * 谁写：ErrandServiceImpl（接单时 insert，每次状态跃迁时 update）
 * 谁读：详情页（显示"谁接了"）、我的接单列表、超时释放定时任务
 *
 * 【为什么不在 post_errand 上加 taker_id 就完事】
 * 那样只能记住"现在谁接了"，记不住"上一个人是谁、为什么被换掉"。
 * 而并发问题排查几乎全靠这份历史：出了脏数据，没有记录就只能猜。
 * 代价是多一张表 + 一次双写（insert 记录 与 update 帖子状态必须同事务）。
 *
 * 【⚠️ 两个字段必须一起维护】
 *   status          —— 履约进度
 *   activePostId    —— 进行中时等于 postId，进入终态时必须置 null
 * 漏了 activePostId，唯一索引会一直认为"这单还占着"，导致帖子再也接不了。
 * 所以 Service 里所有终态更新都统一走一个方法，不允许散着写 update。
 *
 * @author 校集后端
 */
// 【来源】第三方库：Lombok。@Data = getter + setter + toString + equals/hashCode
// 【为什么 setter 不能省】MyBatis-Plus 查询后靠 setter 填值，Jackson 序列化也靠 getter
@Data
// 【来源】第三方库：MyBatis-Plus。绑定表名
@TableName("errand_take")
public class ErrandTake {

    /**
     * 自增主键。
     *
     * 【为什么这张表用自增 id，而 post_errand 用 post_id 当主键】
     * post_errand 是"一帖一行"（1:1），post_id 天然唯一；
     * 本表是"一帖多行"（1:N，取消后重新接就是一新行），必须有自己的 id 才能定位单条记录。
     * 这也正是选方案 B 的核心收益：**同一个帖子的接单历史可以并存**。
     */
    // 【注解作用】第三方注解：声=明主键 + 指定自增策略。
    // 不写 type 也会跟随全局 id-type: auto，这里显式写出，是为了让读代码的人不必去翻 yml
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跑腿帖 id，对应 post.id */
    private Long postId;

    /** 接单人 user_id */
    private Long takerId;

    /**
     * 履约状态，取值见 {@link com.gdut.xiaojii.common.enums.ErrandTakeStatus}：
     * IN_PROGRESS / PICKED / DELIVERED / COMPLETED / CANCELLED / EXPIRED
     *
     * 【为什么存字符串而不是数字】这列是运维排查时直接 SELECT 出来看的，
     * 存 'PICKED' 一眼能懂，存 2 还要回去查常量表
     */
    private String status;

    /**
     * 接单时刻。
     *
     * 【为什么不能拿 createdAt 代替】两者正常情况下相同，但语义不同：
     * createdAt 是"这行数据什么时候写的"，takenAt 是"这一单从什么时候开始算超时"。
     * 将来如果支持"改派"（把单转给别人），takenAt 需要重置，createdAt 不能动。
     * 现在看起来冗余，是为了不改表就能支持改派。
     */
    private LocalDateTime takenAt;

    /** 确认取件时刻，未取件为 null */
    private LocalDateTime pickedAt;

    /** 标记送达时刻，未送达为 null */
    private LocalDateTime deliveredAt;

    /** 发布者确认完成时刻，未完成为 null */
    private LocalDateTime completedAt;

    /**
     * 终态时刻（取消 / 超时）。
     *
     * 【为什么不和 completedAt 共用一个字段】两者互斥但要分别统计：
     * "平均完成时长"用 completedAt，"平均被取消时长"用 closedAt，
     * 合成一列就再也算不出分类指标了
     */
    private LocalDateTime closedAt;

    /** 取消原因：TAKER_CANCEL 接单者取消 / OWNER_REVOKE 发布者撤回 / ADMIN_FORCE 管理员强制 / TIMEOUT 系统超时 */
    private String cancelReason;

    /** 本单产生的信用分变化，0 表示无奖惩，负数表示扣分 */
    private Integer creditDelta;

    /**
     * 进行中标记：进行中时等于 postId，进入终态时置 null。
     *
     * 【作用】配合表上的 UK 索引 uk_active_post，保证"一个帖子同时只有一条进行中记录"。
     * MySQL 没有部分唯一索引，这是标准替代做法（唯一索引允许多个 NULL）。
     *
     * 【为什么会话里要专门提它】它是本方案唯一一个"看起来多余、其实撑着不变量"的列。
     * 面试问"你怎么保证一个单不会被两个人同时接"，答案有两层：
     *   第一层 post.status 的条件更新（主要防线）；
     *   第二层就是这个可空唯一列（数据库级兜底，防止将来新增代码路径绕过去）。
     */
    private Long activePostId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
