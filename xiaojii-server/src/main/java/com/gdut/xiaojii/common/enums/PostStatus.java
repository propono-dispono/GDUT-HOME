package com.gdut.xiaojii.common.enums;

import lombok.Getter;

/**
 * 帖子状态（对应 post.status）。
 *
 * 【注意与 AuditStatus 区分】
 * status 是"业务状态"（能不能被别人看到、是不是卖掉了），
 * auditStatus 是"审核状态"（机器/人工有没有放行）。两者是正交的维度：
 * 一个帖子可以"审核通过但已成交"（audit=1/2 且 status=3）。
 * 判断"能不能出现在信息流"必须两个条件同时满足：status=1 且 auditStatus∈{1,2}。
 *
 * 【状态流转（谁把谁改成什么）】
 *   发帖 → AUDITING(0)
 *   机器/人工通过 → ONLINE(1)          （审核动作改的是 auditStatus，同时把 status 置 1）
 *   本人下架 / 管理员强制下架 → OFFLINE(2)
 *   拼单满员 / 过期 / 失物已找回 → CLOSED(4)
 *   DEALT(3) 是 B 方案移除订单前的遗留状态，当前业务已不再进入
 *
 * @author 校集后端
 */
// 【写法】@Getter 生成 getName / getCode / getLabel
@Getter
// 【写法】final class + 私有构造器 + 常量（模拟枚举，本项目约定不用 Java enum）
public final class PostStatus {

    /** 0 待审核：审核通过前不进信息流 */
    public static final PostStatus AUDITING = new PostStatus("AUDITING", 0, "待审核");
    /** 1 展示中 */
    public static final PostStatus ONLINE = new PostStatus("ONLINE", 1, "展示中");
    /** 2 已下架：本人下架或管理员强制下架 */
    public static final PostStatus OFFLINE = new PostStatus("OFFLINE", 2, "已下架");
    /** 3 已成交：有进行中的订单时锁定，防止同一帖子并发多单 */
    // 注释里说的"并发多单"是订单时代的语义；B 方案移除订单后，这个状态已基本不会出现，
    // 但数据库和前端仍在用这套编号，所以常量保留（删了会让老数据无法解释）
    public static final PostStatus DEALT = new PostStatus("DEALT", 3, "已成交");
    /** 4 已关闭：过期、拼单满员、失物已找回等自然结束 */
    public static final PostStatus CLOSED = new PostStatus("CLOSED", 4, "已关闭");

    /** 常量全集：声明在常量之后，保证静态初始化顺序安全 */
    // 【为什么必须在常量之后】静态字段按声明顺序初始化，放前面会装进 null
    private static final PostStatus[] VALUES = {
            AUDITING, ONLINE, OFFLINE, DEALT,
            CLOSED
    };

    /** 常量名，等价于 enum 的 name */
    private final String name;

    // 数值码，直接对应数据库 post.status 的 TINYINT 值
    // 注意：前端也按这个数字判断显示什么按钮，改动编号等于改动前后端契约
    private final int code;

    // 中文名，用于日志与管理端展示
    private final String label;

    // 私有构造器：只允许类内部创建这 5 个固定实例
    private PostStatus(String name, int code, String label) {
        this.name = name;
        this.code = code;
        this.label = label;
    }

    /**
     * 数值码转枚举。
     */
    // 【写法】null 或未知码都兜底成 AUDITING，不抛异常
    // 【为什么和 BizType.of() 不一样】两个方法的输入来源完全不同：
    //   · BizType.of() 的入参来自**前端**（不可信），传错必须立刻报错，否则会写脏数据；
    //   · 本方法的入参来自**自己的数据库**，正常情况下一定是 0~4。
    //     如果哪天出现脏值（人工改库、历史数据），抛异常会让整个列表页 500；
    //     兜底成"待审核"最多是这条帖子不显示，影响面小得多。
    // 【代价】脏数据会被静默吞掉，排查时看不到告警 —— 所以真正上线还应该加一行 log.warn
    // 【一句话原则】输入不可信 → 严格抛错；输入可信 → 容错兜底，但不能静默
    public static PostStatus of(Integer code) {
        if (code == null) {
            return AUDITING;
        }
        for (PostStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return AUDITING;
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
    public static PostStatus[] values() {
        return VALUES.clone();
    }

    /** 等价于 enum 的 valueOf(String) */
    public static PostStatus valueOf(String name) {
        for (PostStatus v : VALUES) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("No enum constant PostStatus." + name);
    }

    /** 等价于 enum 的默认 toString() */
    @Override
    public String toString() {
        return this.name;
    }
}
