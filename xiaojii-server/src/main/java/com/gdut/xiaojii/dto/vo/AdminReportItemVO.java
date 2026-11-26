package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端：举报列表项。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理端举报项")
public class AdminReportItemVO {

    private Long id;

    @Schema(description = "POST / USER / MSG")
    private String targetType;

    private String targetTypeText;

    private Long targetId;

    @Schema(description = "被举报内容的标题（仅 POST 有值）")
    private String targetTitle;

    private Long reporterId;

    private String reporter;

    private String reasonType;

    private String description;

    @Schema(description = "0待处理 1成立 2不成立")
    private Integer status;

    private String statusText;

    private String handleRemark;

    private String handledAt;

    private String createdAt;
}
