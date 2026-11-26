package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.NoticeVO;

/**
 * 站内通知：全站唯一通知出口（写） + 用户侧读取。
 * 任何业务需要"告诉用户一件事"，都必须走 {@link #push}，不要在业务代码里直接 insert notice。
 *
 * @author 校集后端
 */
public interface NoticeService {

    /**
     * 推送一条站内通知
     *
     * @param userId  接收人
     * @param type    SYSTEM / AUDIT / ORDER / CHAT / REPORT / GROUPBUY
     * @param title   标题（≤60 字）
     * @param content 正文（≤300 字）
     * @param refType 跳转对象类型，可为 null
     * @param refId   跳转对象 ID，可为 null
     */
    void push(Long userId, String type, String title, String content, String refType, Long refId);

    /**
     * 推送一条站内通知（带消息 ID，消费幂等）。
     *
     * 【为什么需要这个重载，而不是把所有调用方都改掉】
     * 走 MQ 的通知必须能幂等（同一条消息被重投时要能识别出来），
     * 所以要把消息 ID 一并落库、靠唯一索引挡重复。
     * 而项目里还有 8 处是同步直接调用 push 的，它们没有消息 ID，
     * 强行改造会让这次改动扩散到审核、私信、举报四个模块 —— 风险远大于收益。
     * 用重载，同步路径保持原样（msgId=null），异步路径传 ID。
     *
     * 【为什么 msgId 为 null 不会互相冲突】
     * MySQL 唯一索引允许多个 NULL 同时存在（NULL 不参与唯一性比较），
     * 所以几万条同步通知的 msg_id 都是 NULL 也不会报错。
     *
     * 模拟数据：
     *   同步：push(15,"AUDIT","内容已通过",null,null)        → msg_id=NULL，可重复写
     *   异步：push(15,...,"1731600000000-0")                → 首次成功
     *        同一条消息重投 → push 同 msgId → 撞 uk_msg_id → 静默忽略，用户只看到一条
     *
     * @param msgId   MQ 消息 ID；同步调用传 null
     */
    void push(Long userId, String type, String title, String content,
              String refType, Long refId, String msgId);

    /** 我的通知列表，新的在前 */
    PageResult<NoticeVO> list(Long userId, int page, int size);

    /** 未读条数 */
    long unreadCount(Long userId);

    /** 标记单条已读（只能操作自己的） */
    void markRead(Long userId, Long noticeId);

    /** 全部标记已读 */
    void markAllRead(Long userId);
}
