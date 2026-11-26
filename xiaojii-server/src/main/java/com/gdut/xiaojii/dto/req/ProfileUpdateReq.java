package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 修改个人资料请求。字段都可选，传了才更新。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "修改资料请求")
public class ProfileUpdateReq {

    @Size(max = 32, message = "昵称最多 32 个字")
    @Schema(description = "昵称")
    private String nickname;

    @Size(max = 255, message = "头像地址过长")
    @Schema(description = "头像 URL（先调上传接口拿到 url）")
    private String avatar;

    @Size(max = 200, message = "简介最多 200 个字")
    @Schema(description = "个人简介/个性签名")
    private String intro;

    @Min(0) @Max(3)
    @Schema(description = "性别：0未知 1男 2女 3保密")
    private Integer gender;

    @Schema(description = "生日 yyyy-MM-dd（只用于计算年龄）")
    private String birthday;

    @Size(max = 40, message = "专业最多 40 个字")
    private String major;

    @Min(0) @Max(2100)
    @Schema(description = "入学年份，如 2023；传 0 表示清除")
    private Integer grade;

    @Size(max = 40, message = "学院最多 40 个字")
    private String college;

    @Size(max = 20, message = "校区最多 20 个字")
    private String campus;

    @Size(max = 6, message = "标签最多 6 个")
    @Schema(description = "爱好/技能标签")
    private List<@Size(max = 12, message = "单个标签最多 12 个字") String> tags;

    @Size(max = 20, message = "可互助时间最多 20 个字")
    private String availableTime;

    @Size(max = 6, message = "想找的类型最多 6 个")
    private List<String> lookingFor;

    @Schema(description = "逐项可见性：{gender:true, major:true, ...}")
    private Map<String, Boolean> privacy;
}
