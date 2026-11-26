package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端审核驳回 / 强制下架的原因。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "审核驳回/下架原因")
public class AuditRejectReq {

    @NotBlank(message = "请填写原因")
    @Size(max = 100, message = "原因最多 100 个字")
    @Schema(description = "驳回或下架原因，会原样展示给用户",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;
}
