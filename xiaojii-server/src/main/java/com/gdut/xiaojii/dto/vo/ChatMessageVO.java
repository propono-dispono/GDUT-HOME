package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 小程序端：消息。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "聊天消息")
public class ChatMessageVO {

    private Long id;

    private Long fromUserId;

    @Schema(description = "是否我发的，前端据此决定气泡左右")
    private Boolean mine;

    @Schema(description = "1 文字 / 2 图片 / 3 订单卡片")
    private Integer msgType;

    private String content;

    private String createdAt;

    /** 发送者昵称/头像/首字母：聊天页直接渲染，不用再按 fromUserId 逐个查 */
    private String fromNickname;

    private String fromAvatar;

    private String fromInitial;
}
