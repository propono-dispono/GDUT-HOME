package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 小程序端：发送私信。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "发送私信")
public class ChatSendReq {

    @NotBlank(message = "消息不能为空")
    @Size(max = 1000, message = "单条消息最多 1000 字")
    @Schema(description = "文字内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;
}
