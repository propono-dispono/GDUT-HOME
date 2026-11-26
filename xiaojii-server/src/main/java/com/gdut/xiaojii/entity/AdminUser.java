package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员实体（对应 {@code admin_user} 表）。
 *
 * <p>与 {@link User} 是两套完全独立的主体：管理员只用于后台审核/仲裁，
 * 不能发布内容、不能下单。字段也不共用（这里没有 openid/creditScore）。
 *
 * @author 校集后端
 */
@Data
@TableName("admin_user")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** PBKDF2 哈希，格式 pbkdf2$iter$salt$hash，明文永不落库 */
    private String passwordHash;

    private String nickname;

    /** ADMIN 普通管理员 / SUPER 超级管理员 */
    private String role;

    /** 1启用 0停用 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SUPER = "SUPER";
}
