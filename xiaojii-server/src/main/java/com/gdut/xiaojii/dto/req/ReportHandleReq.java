package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端：处理举报。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "处理举报")
public class ReportHandleReq {

    @NotBlank(message = "请选择处理结果")
    @Schema(description = "CONFIRMED 成立 / REJECTED 不成立", requiredMode = Schema.RequiredMode.REQUIRED)
    private String result;

    @Size(max = 200, message = "处理结论最多 200 字")
    @Schema(description = "处理结论，会通知举报人")
    private String remark;
}
