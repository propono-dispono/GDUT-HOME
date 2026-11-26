package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端：内容审核列表项。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理端内容审核项")
public class AdminPostItemVO {

    private Long id;

    @Schema(description = "业务线")
    private String biz;

    private String title;

    private String desc;

    @Schema(description = "金额字符串，去尾零")
    private String price;

    private String priceUnit;

    @Schema(description = "封面可访问 URL")
    private String cover;

    private Long userId;

    @Schema(description = "发布人昵称首字")
    private String user;

    @Schema(description = "业务状态：0待审 1展示中 2已下架 3已成交 4已关闭")
    private Integer status;

    private String statusText;

    @Schema(description = "审核状态文案：待审核/机器通过/人工通过/已驳回")
    private String auditStatusText;

    private Integer auditStatus;

    private String rejectReason;

    private String createdAt;
}
