package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端：调整信用分。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "调整信用分")
public class CreditAdjustReq {

    @NotNull(message = "请填写调整分值")
    @Min(value = -100, message = "单次最多扣 100")
    @Max(value = 100, message = "单次最多加 100")
    @Schema(description = "调整分值：正数加分、负数扣分",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer delta;

    @Size(max = 100, message = "原因最多 100 个字")
    @Schema(description = "调整原因，会写入留痕")
    private String reason;
}
