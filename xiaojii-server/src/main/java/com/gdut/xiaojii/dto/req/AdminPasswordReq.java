package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改密码请求。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理员修改密码")
public class AdminPasswordReq {

    @NotBlank(message = "原密码不能为空")
    @Schema(description = "当前密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度需为 8-64 位")
    @Schema(description = "新密码（8-64 位）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
