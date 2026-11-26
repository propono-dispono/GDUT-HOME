package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 站内通知列表项。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "站内通知")
public class NoticeVO {

    private Long id;

    @Schema(description = "类型：SYSTEM/AUDIT/ORDER/CHAT/REPORT/GROUPBUY")
    private String type;

    @Schema(description = "类型中文文案")
    private String typeText;

    private String title;

    private String content;

    @Schema(description = "跳转对象类型：POST/ORDER/SESSION")
    private String refType;

    private Long refId;

    @Schema(description = "是否已读")
    private Boolean read;

    @Schema(description = "创建时间 yyyy-MM-dd HH:mm")
    private String createdAt;
}
