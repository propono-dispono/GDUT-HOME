package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理员登录返回。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理员登录结果")
public class AdminLoginVO {

    @Schema(description = "管理端 JWT，只能用于 /api/admin/**")
    private String token;

    @Schema(description = "管理员 ID")
    private Long adminId;

    @Schema(description = "显示名")
    private String nickname;

    @Schema(description = "角色：ADMIN / SUPER")
    private String role;
}
