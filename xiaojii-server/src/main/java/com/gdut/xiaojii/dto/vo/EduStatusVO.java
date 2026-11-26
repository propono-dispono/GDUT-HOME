package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 教务登录状态。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "教务登录状态")
public class EduStatusVO {

    @Schema(description = "当前会话是否有效")
    private Boolean loggedIn;

    @Schema(description = "已废弃，恒为 false：新版本不再保存账号密码")
    private Boolean saved;

    @Schema(description = "脱敏学号，如 3124****95")
    private String accountMasked;

    @Schema(description = "教务会话到期时间 yyyy-MM-dd HH:mm（滑动 12 小时，硬顶 24 小时）")
    private String expiresAt;
}
