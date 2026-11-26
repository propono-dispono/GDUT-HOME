package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端：新建/编辑公告。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "保存公告")
public class AnnouncementSaveReq {

    @Schema(description = "不传为新建，传了为编辑")
    private Long id;

    @NotBlank(message = "请填写标题")
    @Size(max = 60, message = "标题最多 60 字")
    @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @NotBlank(message = "请填写内容")
    @Schema(description = "内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "POPUP 弹窗 / INNER 站内，默认 POPUP")
    private String type;

    @Schema(description = "0 下线 / 1 上线，默认 1")
    private Integer status;
}
