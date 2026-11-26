package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交校园认证请求。
 *
 * <p>为什么不用学校接口自动校验：学校没有对外开放的学号查询接口，
 * 所以走「上传校园卡照片 + 人工审核」。学号在库里有唯一索引，防一人多号。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "提交校园认证请求")
public class VerifySubmitReq {

    @NotBlank(message = "学号不能为空")
    @Pattern(regexp = "^[0-9A-Za-z]{6,20}$", message = "学号格式不正确")
    @Schema(description = "学号，全局唯一", example = "3123001234",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String studentNo;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 32, message = "姓名最多 32 个字")
    @Schema(description = "真实姓名，需与校园卡一致", requiredMode = Schema.RequiredMode.REQUIRED)
    private String realName;

    @NotBlank(message = "请上传校园卡照片")
    @Size(max = 255)
    @Schema(description = "校园卡照片 key（先调 /app/upload 上传）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String cardImage;
}
