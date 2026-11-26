package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

// 未使用：只服务已废弃的 /app/edu/captcha 接口
@Data
@AllArgsConstructor
@Schema(description = "教务验证码")
/** EduCaptcha 返回结构 */
public class EduCaptchaVO {

    @Schema(description = "验证码会话 ID，登录时带回")
    private String sessionId;

    @Schema(description = "验证码图片 base64（不含 data: 前缀）")
    private String imageBase64;

    @Schema(description = "有效期（秒）")
    private long expiresInSeconds;
}
