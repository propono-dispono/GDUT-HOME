package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理员登录请求。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理员登录请求")
public class AdminLoginReq {

    @NotBlank(message = "请输入账号")
    @Schema(description = "管理员账号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "请输入密码")
    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
