package com.gdut.xiaojii.event;

import lombok.Data;

/**
 * 通知事件（走消息队列的那一条消息）。
 *
 * 【一句话】这就是"要告诉某个用户一件事"的便条，先写进队列，由消费者慢慢投递。
 *
 * 【为什么是单独一个类，而不是直接传一堆参数】
 * 消息要序列化成 JSON 塞进 Redis，参数散着传就没法统一、没法演进。
 * 用对象的好处：将来加一个字段（比如"是否也要发微信订阅消息"），
 * 老消息反序列化时该字段是 null，消费者判空即可，不会因为格式变化报错。
 *
 * 【字段为什么和 NoticeVO 差不多但又不复用】
 * VO 是"给前端看的"，字段要和页面绑定；Event 是"给消费者看的"，只带落库必需的信息。
 * 复用会导致改页面时不小心改了消息格式，进而让队列里的历史消息反序列化失败。
 *
 * @author 校集后端
 */
@Data
public class NoticeEvent {

    /** 接收人 user_id */
    private Long userId;

    /** SYSTEM / AUDIT / ORDER / CHAT / REPORT / GROUPBUY */
    private String type;

    /** 标题，消费侧还会再截断到 60 字 */
    private String title;

    /** 正文，消费侧截断到 300 字 */
    private String content;

    /** 跳转对象类型 POST / SESSION / ORDER，可空 */
    private String refType;

    /** 跳转对象 id，可空 */
    private Long refId;
}
