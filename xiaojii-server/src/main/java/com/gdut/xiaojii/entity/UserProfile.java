package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户扩展资料（与 user 1:1）。
 *
 * <p>只放展示型字段；登录、信用、认证仍在 {@link User}。
 * JSON 字段在实体里先按字符串存，Service 层负责解析，避免引入额外类型处理器。
 *
 * @author 校集后端
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.INPUT)
    private Long userId;

    private Integer gender;

    /** 生日允许被清空：默认策略会忽略 null，这里显式更新 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDate birthday;

    private String major;

    /** 年级允许被清空：默认策略会忽略 null，这里显式更新 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Integer grade;

    private String college;

    private String campus;

    /** JSON 数组字符串，最多 6 个标签 */
    private String tags;

    private String availableTime;

    /** JSON 数组字符串 */
    private String lookingFor;

    /** JSON 对象字符串：字段 → 是否公开 */
    private String privacy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
