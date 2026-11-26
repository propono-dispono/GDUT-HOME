package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校园身份认证申请表实体（对应 {@code user_verify} 表）。
 *
 * <p>为什么不能自动校验学号：学校没有对外开放的学号校验接口，
 * 所以采用「上传校园卡照片 + 人工审核」的方式（设计文档 §2.3）。
 * {@code student_no} 加了唯一索引，用于防止一人注册多个账号。
 *
 * @author 校集后端
 */
@Data
@TableName("user_verify")
public class UserVerify {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 学号，全局唯一 */
    private String studentNo;

    /** 真实姓名，需与校园卡一致 */
    private String realName;

    /** 校园卡照片的对象存储 key */
    private String cardImage;

    /** 审核状态：0待审 1通过 2驳回 */
    private Integer status;

    /** 驳回原因，仅 status=2 时有值 */
    private String rejectReason;

    /** 审核人（管理员 ID） */
    private Long reviewerId;

    private LocalDateTime reviewedAt;

    /** 创建时间，由 MybatisMetaObjectHandler 自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /* ---------- 状态常量 ----------
     * ⚠️ 陷阱：本类的 2 = 驳回，而 User.verifyStatus 的 2 = 已认证。
     * 两个字段语义相反，写判断时务必用常量不要用魔法数字。
     */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PASSED = 1;
    public static final int STATUS_REJECTED = 2;
}
