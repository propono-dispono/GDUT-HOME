package com.gdut.xiaojii.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 信息流查询参数。
 *
 * <p>全部字段可选，不传就是"全部"。分页从 1 开始，size 上限 100（MP 分页插件兜底）。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "信息流查询参数")
public class PostQueryReq {

    @Schema(description = "业务线，不传表示全部", example = "SECONDHAND")
    private String bizType;

    @Schema(description = "二级分类 ID")
    private Integer categoryId;

    @Schema(description = "关键词，匹配标题与正文")
    private String keyword;

    @Schema(description = "最低价")
    private BigDecimal minPrice;

    @Schema(description = "最高价")
    private BigDecimal maxPrice;

    @Schema(description = "楼栋，如 东区宿舍")
    private String building;

    @Schema(description = "排序：latest 最新（默认）/ priceAsc 价格升 / priceDesc 价格降")
    private String sort;

    @Min(value = 1, message = "页码从 1 开始")
    @Schema(description = "页码，默认 1")
    private Integer page = 1;

    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    @Schema(description = "每页条数，默认 10")
    private Integer size = 10;
}
