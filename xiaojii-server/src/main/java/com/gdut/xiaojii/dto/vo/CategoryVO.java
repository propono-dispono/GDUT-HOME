package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二级分类 VO。
 *
 * @author 校集后端
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分类")
public class CategoryVO {

    private Integer id;

    private String label;

    @Schema(description = "所属业务线")
    private String bizType;
}
