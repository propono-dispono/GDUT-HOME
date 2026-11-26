package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拼单成员（groupbuy_member）。一个用户在一个拼单里只有一行（唯一键 uk_post_user）。
 *
 * 退出不改物理行，只把 status 置 CANCELLED：既留痕"谁参加过又退了"，
 * 又靠这一行继续占住唯一键 —— 所以"退出后再参加"走的是条件更新，不是新插入。
 *
 * @author 校集后端
 */
@Data
@TableName("groupbuy_member")
public class GroupbuyMember {

    /** 自增主键，大小即参与顺序（成员列表按它升序） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拼单帖 id */
    private Long postId;

    /** 参与人 user_id */
    private Long userId;

    /** JOINED 参与中 / CANCELLED 已退出；切换一律用条件更新，不用 updateById */
    private String status;

    /** 参与时间，插入时自动填充；发起人没有这一行 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public static final String JOINED = "JOINED";
    public static final String CANCELLED = "CANCELLED";
}
