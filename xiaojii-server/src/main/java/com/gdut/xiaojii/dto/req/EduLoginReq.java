package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 教务登录请求。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "教务登录")
public class EduLoginReq {

    @Schema(description = "验证码会话（统一认证登录已不需要，保留兼容旧客户端）")
    private String sessionId;

    @NotBlank(message = "请输入学号")
    @Size(max = 32, message = "学号过长")
    private String account;

    @NotBlank(message = "请输入密码")
    @Size(max = 64, message = "密码过长")
    private String password;

    @Schema(description = "图形验证码（统一认证登录已不需要，保留兼容旧客户端）")
    private String captcha;

    @Schema(description = "是否记住账号（30 天，可随时清除）")
    private Boolean remember;
}
