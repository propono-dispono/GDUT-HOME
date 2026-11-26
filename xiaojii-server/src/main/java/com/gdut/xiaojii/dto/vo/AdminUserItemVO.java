package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端：用户列表项。
 * ⚠️ openid 属于用户隐私，列表里只给脱敏值；需要完整值请走单独的高权限接口。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理端用户项")
public class AdminUserItemVO {

    private Long id;

    private String nickname;

    @Schema(description = "昵称首字，列表头像用")
    private String initial;

    @Schema(description = "脱敏 openid，如 oX7b****9f2c")
    private String openidMasked;

    @Schema(description = "信用分 0-100")
    private Integer creditScore;

    @Schema(description = "认证状态：0未认证 1待审核 2已认证 3驳回")
    private Integer verifyStatus;

    private String verifyStatusText;

    @Schema(description = "账号状态：1正常 2禁言 3封禁")
    private Integer status;

    private String statusText;

    @Schema(description = "累计发布数")
    private Long postCount;

    private String createdAt;
}
