package com.gdut.xiaojii.dto.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑腿接单信息（详情页 / 我的接单列表共用）。
 *
 * 【一句话】把"这单被谁接了、干到哪一步了、当前这个人能点哪些按钮"一次给全前端。
 *
 * 【为什么把 canXxx 几个按钮布尔值放在后端算】
 * 状态机的判断规则如果前端再实现一遍，就会出现"后端认为不能取消、前端还显示取消按钮"
 * 这种两套真相。放在这里算，前端只负责按布尔值显示/隐藏，规则永远只有一份。
 * 代价是每次改规则都要动后端，但这本来就应该只有一处。
 *
 * @author 校集后端
 */
@Data
public class ErrandTakeVO {

    /** 跑腿帖 id */
    private Long postId;

    /** 帖子标题，列表页直接展示，省得前端再查一次 */
    private String postTitle;

    /**
     * 接单人 user_id。
     *
     * 【为什么只给 id 不给昵称】昵称/头像属于用户资料，已有 GET /app/user/{id} 接口统一提供，
     * 这里再拼一份就是两处真相（改了头像缓存没刷新就会不一致）。
     * 前端拿到 id 后按需查一次即可。
     */
    private Long takerId;

    /** 履约状态名：IN_PROGRESS / PICKED / DELIVERED / COMPLETED / CANCELLED / EXPIRED */
    private String status;

    /** 状态中文名，直接展示，避免前端维护一份翻译表 */
    private String statusLabel;

    /** 接单时刻 */
    private LocalDateTime takenAt;

    /** 取件时刻 */
    private LocalDateTime pickedAt;

    /** 送达时刻 */
    private LocalDateTime deliveredAt;

    /** 完成时刻 */
    private LocalDateTime completedAt;

    /** 终态时刻（取消/超时） */
    private LocalDateTime closedAt;

    /**
     * 当前登录者在这单里的身份：TAKER 接单人 / OWNER 发布者 / OTHER 路人。
     *
     * 【为什么需要它】同一条记录，接单人和发布者看到的按钮完全不同：
     * 接单人看到"取消/取件/送达"，发布者看到"确认完成/撤回"。
     * 后端算好身份，前端不用自己比对 user_id。
     */
    private String myRole;

    /** 能否取消：接单人 且 还在进行中（已取件就不许取消了，见设计决策 2） */
    private boolean canCancel;

    /** 能否确认取件：接单人 且 进行中 */
    private boolean canPick;

    /** 能否标记送达：接单人 且 已取件 */
    private boolean canDeliver;

    /** 能否确认完成：发布者 且 已送达 */
    private boolean canConfirm;

    /** 能否撤回：发布者 且 这单还没结束 */
    private boolean canRevoke;
}
