package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 举报。
 *
 * @author 校集后端
 */
@Data
@TableName("report")
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** POST / USER / MSG */
    private String targetType;

    private Long targetId;

    private Long reporterId;

    /** 广告 / 诈骗 / 违禁 / 辱骂 / 其他 */
    private String reasonType;

    private String description;

    /** 证据截图 key 数组（JSON） */
    private String images;

    /** 0 待处理 / 1 已处理（成立） / 2 不成立 */
    private Integer status;

    private Long handlerId;

    private String handleRemark;

    private LocalDateTime handledAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String TARGET_POST = "POST";
    public static final String TARGET_USER = "USER";
    public static final String TARGET_MSG = "MSG";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_REJECTED = 2;
}
