package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 认证状态回显。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "校园认证状态")
public class VerifyVO {

    @Schema(description = "认证状态：0未认证 1待审核 2已认证 3驳回")
    private Integer status;

    @Schema(description = "状态文案")
    private String statusText;

    @Schema(description = "学号（脱敏，如 3123****34）")
    private String studentNo;

    @Schema(description = "真实姓名（脱敏，只留姓）")
    private String realName;

    @Schema(description = "提交时间")
    private String submittedAt;

    @Schema(description = "审核时间")
    private String reviewedAt;

    @Schema(description = "驳回原因，仅驳回时有值")
    private String rejectReason;
}
