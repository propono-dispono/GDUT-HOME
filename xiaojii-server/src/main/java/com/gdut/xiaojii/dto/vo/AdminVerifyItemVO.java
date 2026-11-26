package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理端：认证审核列表项。
 * ⚠️ 这里返回的是**明文**学号与姓名 —— 管理员要拿它和校园卡照片核对，
 *    脱敏就没法核对了。所以该接口只允许管理端令牌访问，绝不能出现在小程序端 VO 里。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "管理端认证审核项")
public class AdminVerifyItemVO {

    @Schema(description = "认证申请 ID（user_verify.id）")
    private Long id;

    private Long userId;

    @Schema(description = "用户昵称")
    private String nickname;

    @Schema(description = "昵称首字，列表头像用")
    private String initial;

    @Schema(description = "学号（明文）")
    private String studentNo;

    @Schema(description = "真实姓名（明文）")
    private String realName;

    @Schema(description = "校园卡照片可访问 URL")
    private String cardImage;

    @Schema(description = "审核状态：0待审 1通过 2驳回")
    private Integer status;

    private String statusText;

    private String rejectReason;

    private String submittedAt;

    private String reviewedAt;
}
