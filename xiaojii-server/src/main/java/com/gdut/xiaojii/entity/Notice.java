package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知。
 * ⚠️ 只允许通过 {@code NoticeService} 写入，禁止在业务代码里直接 insert，
 * 否则通知格式会散落各处、将来加推送渠道时要全项目改。
 *
 * @author 校集后端
 */
@Data
@TableName("notice")
public class Notice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    private Long userId;

    /** SYSTEM / AUDIT / ORDER / CHAT / REPORT / GROUPBUY */
    private String type;

    private String title;

    private String content;

    /** 跳转对象类型：POST / ORDER / SESSION */
    private String refType;

    private Long refId;

    /** 0 未读 / 1 已读 */
    private Integer readFlag;

    /**
     * MQ 消息 ID，用于消费幂等；同步直接写入的通知为 null。
     *
     * 【它怎么起作用】表上有 UNIQUE uk_msg_id。MQ 是"至少一次"投递，
     * 同一条消息可能被投两次；第二次插入时撞唯一索引 → 被 catch 掉 → 用户只看到一条。
     * ⚠️ 这一列不能改成 NOT NULL 或加默认值，否则所有同步通知的 NULL 会变成重复值互撞。
     */
    private String msgId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String TYPE_AUDIT = "AUDIT";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_ORDER = "ORDER";
    /** 拼单：有人参加 / 成团。NoticeServiceImpl.typeText 里已有对应中文名 */
    public static final String TYPE_GROUPBUY = "GROUPBUY";
    public static final String REF_POST = "POST";
    public static final String REF_ORDER = "ORDER";
}
