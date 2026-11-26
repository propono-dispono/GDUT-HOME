package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话（管理端只读监管用）。
 * user_a 恒为较小的 user_id，user_b 恒为较大的，保证一对用户只有一条会话。
 *
 * @author 校集后端
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userA;

    private Long userB;

    /** POST / ORDER / NONE */
    private String refType;

    private Long refId;

    private String lastMsg;

    private LocalDateTime lastAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
