package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端：首页数据概览。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理端数据概览")
public class AdminOverviewVO {

    @Schema(description = "待审核认证数")
    private long pendingVerify;

    @Schema(description = "待审核内容数")
    private long pendingPost;

    @Schema(description = "展示中的内容数")
    private long onlinePost;

    @Schema(description = "内容总数（不含已删除）")
    private long totalPost;

    @Schema(description = "今日新增内容数")
    private long todayPost;

    @Schema(description = "用户总数")
    private long totalUser;

    @Schema(description = "已认证用户数")
    private long verifiedUser;

    @Schema(description = "被封禁/禁言用户数")
    private long bannedUser;
}
