package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 小程序端：会话。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "会话")
public class ChatSessionVO {

    private Long id;

    @Schema(description = "对方 user_id")
    private Long peerId;

    @Schema(description = "对方昵称首字，列表头像用")
    private String peerInitial;

    @Schema(description = "对方昵称")
    private String peerName;

    private String lastMsg;

    private String lastAt;

    /** 会话来源：从哪条内容发起的（POST=帖子详情），聊天页据此展示引用卡 */
    private String refType;

    private Long refId;
}
