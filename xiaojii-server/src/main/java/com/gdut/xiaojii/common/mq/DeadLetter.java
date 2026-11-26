package com.gdut.xiaojii.common.mq;

import lombok.Data;

/**
 * 一条死信（重试超过上限仍失败的消息）。
 *
 * 【一句话】它是"这条消息我实在处理不了，先放这儿留证据"的那张纸条。
 *
 * 【为什么要把这些字段都留着】排查死信时必须能回答三个问题：
 *   ① 原来是什么消息？（payload）
 *   ② 它本来是哪条？（sourceId —— 对应主队列里的记录 id）
 *   ③ 为什么失败？（error + attempts）
 * 只留一句 error 日志，过一周根本还原不出现场。
 *
 * @author 校集后端
 */
@Data
public class DeadLetter {

    /** 死信自己的记录 id（在 mq:{topic}:dlq 里的位置），重投时要用它定位 */
    private String id;

    /** 原始消息体（JSON 字符串） */
    private String payload;

    /** 原消息在主队列里的 id，如 1731600000000-0 */
    private String sourceId;

    /** 主题，如 notice */
    private String topic;

    /** 一共尝试了几次 */
    private String attempts;

    /** 最后一次的异常信息 */
    private String error;

    /** 进入死信的时间戳（毫秒） */
    private String ts;
}
