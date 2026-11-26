package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核记录：每次人工/机器审核的结果留痕，用于事后复核与纠纷追溯。
 *
 * @author 校集后端
 */
@Data
@TableName("audit_record")
public class AuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** POST / VERIFY */
    private String targetType;

    /** post.id 或 user_verify.id */
    private Long targetId;

    /** 机器审核结果：PASS / REJECT / UNKNOWN */
    private String machineResult;

    /** 命中的敏感词，便于复核 */
    private String hitWords;

    /** 人工审核人 admin_user.id；机器直接判定时为空 */
    private Long reviewerId;

    /** 最终结果：PASS / REJECT */
    private String result;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    public static final String TARGET_POST = "POST";
    public static final String TARGET_VERIFY = "VERIFY";
    /** 管理员对账号的处置（封禁/禁言/信用分调整）也走这张表留痕 */
    public static final String TARGET_USER = "USER";
    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_REJECT = "REJECT";
}
