package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 小程序端：提交举报。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "提交举报")
public class ReportSubmitReq {

    @NotBlank(message = "请选择举报对象类型")
    @Schema(description = "POST / USER / MSG", requiredMode = Schema.RequiredMode.REQUIRED)
    private String targetType;

    @NotNull(message = "缺少举报对象")
    @Schema(description = "被举报对象的 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long targetId;

    @NotBlank(message = "请选择举报原因")
    @Schema(description = "广告 / 诈骗 / 违禁 / 辱骂 / 其他", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reasonType;

    @Size(max = 300, message = "补充说明最多 300 字")
    @Schema(description = "补充说明")
    private String description;

    @Size(max = 3, message = "最多 3 张证据截图")
    @Schema(description = "证据截图 key 列表（先调 /app/upload）")
    private List<String> images;
}
