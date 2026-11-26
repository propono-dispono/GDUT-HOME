package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端：修改账号状态。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "修改账号状态")
public class AdminUserStatusReq {

    @NotNull(message = "请选择账号状态")
    @Min(value = 1, message = "状态只能是 1正常 / 2禁言 / 3封禁")
    @Max(value = 3, message = "状态只能是 1正常 / 2禁言 / 3封禁")
    @Schema(description = "1正常 2禁言 3封禁", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer status;

    @Size(max = 100, message = "原因最多 100 个字")
    @Schema(description = "处置原因，会写入审核留痕")
    private String reason;
}
