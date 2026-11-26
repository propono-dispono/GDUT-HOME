package com.gdut.xiaojii.common.enums;

import lombok.Getter;

/**
 * 跑腿接单记录状态（对应 errand_take.status 的字符串值）。
 *
 * 【一句话】它记录的是「这一单走到哪一步了」，跟 post.status 不是一回事：
 *   post.status 回答"别人还能不能接这个帖子"（1 可接 / 3 已锁 / 4 结束）；
 *   ErrandTakeStatus 回答"接的人现在干到哪了"（已接 / 已取件 / 已送达 / 完成）。
 *   两个状态机分开，是因为一个帖子可以有多条接单记录（取消后重新被接），
 *   但帖子本身在任一时刻只有一个"能不能接"的答案。
 *
 * 【完整流转图】
 *
 *   IN_PROGRESS ──pick──▶ PICKED ──deliver──▶ DELIVERED ──confirm──▶ COMPLETED
 *        │                   │                     │
 *        │                   │                     └─（发布者始终不确认 → 超时自动完成）
 *        ├──cancel（接单者，限 1 次）──▶ CANCELLED ──▶ 帖子回到 ONLINE
 *        ├──revoke（发布者撤回）──────▶ CANCELLED ──▶ 帖子转 OFFLINE
 *        └──超时未取件（定时任务）────▶ EXPIRED   ──▶ 帖子回到 ONLINE，扣信用分
 *
 * 【为什么 CANCELLED 和 EXPIRED 必须分开】
 * 两者对用户的后果完全不同：CANCELLED 是"人主动结束"，不扣分；
 * EXPIRED 是"系统判定违约"，要扣信用分。合成一个值就再也分不清责任在谁。
 *
 * @author 校集后端
 */
// 【写法】final class + 私有构造器 + public static final 常量
// 【为什么】本项目 2026-10-23 起全项目禁用 Java enum，统一用 final 类模拟（见 ResultCode 类注释）
@Getter
public final class ErrandTakeStatus {

    /** 1 进行中：已接单，还没去取件。这是唯一能取消的状态 */
    public static final ErrandTakeStatus IN_PROGRESS =
            new ErrandTakeStatus("IN_PROGRESS", 1, "进行中");

    /** 2 已取件：东西已经拿到了，正在送的路上。此时不能再取消（人已经在路上了） */
    public static final ErrandTakeStatus PICKED =
            new ErrandTakeStatus("PICKED", 2, "已取件");

    /** 3 已送达：接单者点了"我送到了"，等发布者确认。发布者不确认会超时自动完成 */
    public static final ErrandTakeStatus DELIVERED =
            new ErrandTakeStatus("DELIVERED", 3, "已送达");

    /** 4 已完成：发布者确认（或超时自动），帖子随之 CLOSED。终态 */
    public static final ErrandTakeStatus COMPLETED =
            new ErrandTakeStatus("COMPLETED", 4, "已完成");

    /** 5 已取消：接单者主动取消 / 发布者撤回 / 管理员强制。终态，不扣信用分 */
    public static final ErrandTakeStatus CANCELLED =
            new ErrandTakeStatus("CANCELLED", 5, "已取消");

    /** 6 已超时：接了单但超过 X 分钟没去取件，被系统释放。终态，扣信用分 */
    public static final ErrandTakeStatus EXPIRED =
            new ErrandTakeStatus("EXPIRED", 6, "已超时");

    /** 常量全集：必须声明在所有常量之后，否则静态初始化顺序会让数组装进 null */
    private static final ErrandTakeStatus[] VALUES = {
            IN_PROGRESS, PICKED, DELIVERED,
            COMPLETED, CANCELLED, EXPIRED
    };

    /** 常量名，直接对应数据库 errand_take.status 的字符串值 */
    private final String name;

    /**
     * 数值码。只用于排序和日志，不落库。
     *
     * 【为什么不落库】落库用 name（字符串），因为状态是"人要看懂"的运维字段。
     * 这里的 code 只解决"按进度排序"（1 < 2 < 3 < 4）这类内存排序需求。
     */
    private final int code;

    /** 中文名，用于日志、小程序展示、管理后台 */
    private final String label;

    private ErrandTakeStatus(String name, int code, String label) {
        this.name = name;
        this.code = code;
        this.label = label;
    }

    /**
     * 这一段是"这个状态是不是已经结束了"，用来决定还能不能继续往下推。
     *
     * 【为什么需要它】所有状态跃迁的 SQL 都要写"只更新还没结束的记录"，
     * 散着写 `status <> 'COMPLETED' AND status <> 'CANCELLED' AND status <> 'EXPIRED'`
     * 既长又容易漏一个。集中到这里，将来加状态只改一处。
     *
     * 模拟数据：
     *   IN_PROGRESS.isFinal() → false（还能取消、还能取件）
     *   COMPLETED.isFinal()   → true（谁也不能再改它）
     *   EXPIRED.isFinal()     → true
     */
    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED;
    }

    /**
     * 这一段是"这一步是不是还能由人取消"。
     *
     * 【为什么只有 IN_PROGRESS 能取消】设计决策 2：限 1 次、且仅未取件时。
     * 已经取件的人再取消，东西在他手上却没人负责，发布者会直接投诉。
     *
     * 模拟数据：
     *   IN_PROGRESS.canCancel() → true
     *   PICKED.canCancel()      → false（已取件，只能用"标记送达"往下走）
     */
    public boolean canCancel() {
        return this == IN_PROGRESS;
    }

    /**
     * 字符串转状态。
     *
     * 【入参可信度】来源是自己的数据库，所以未知值兜底成 IN_PROGRESS 而不是抛异常
     * （理由同 PostStatus.of：一条脏数据不该让整个列表页 500）。
     *
     * 模拟数据："PICKED" → PICKED；null → IN_PROGRESS；"乱码" → IN_PROGRESS
     */
    public static ErrandTakeStatus of(String name) {
        if (name == null) {
            return IN_PROGRESS;
        }
        for (ErrandTakeStatus s : VALUES) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return IN_PROGRESS;
    }

    /* ==================== 以下为 enum 自带能力的等价实现 ==================== */

    /** 等价于 enum 的 name() */
    public String name() {
        return name;
    }

    /** 等价于 enum 的 ordinal()：在常量表中的位置 */
    public int ordinal() {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == this) {
                return i;
            }
        }
        return -1;
    }

    /** 等价于 enum 的 values()：返回副本，防止外部改坏内部数组 */
    public static ErrandTakeStatus[] values() {
        return VALUES.clone();
    }

    /** 等价于 enum 的 valueOf(String)：找不到抛异常（与真枚举行为一致） */
    public static ErrandTakeStatus valueOf(String name) {
        for (ErrandTakeStatus v : VALUES) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("No enum constant ErrandTakeStatus." + name);
    }

    /** 等价于 enum 的默认 toString() */
    @Override
    public String toString() {
        return this.name;
    }
}
