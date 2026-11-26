package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 信息流卡片 VO —— 字段名严格对齐前端 {@code data.js} 的 MOCK.posts。
 *
 * <p>为什么字段名是 biz/desc/cover/meta 这些"不像后端"的名字：
 * 前端原型已经按这套字段渲染（含 4 种卡片形态的分支），
 * 后端改名就要改一遍前端，得不偿失。DTO 与 VO 的职责就是把命名差异挡在边界上。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "信息流卡片")
public class PostItemVO {

    private Long id;

    @Schema(description = "业务线，同 bizType")
    private String biz;

    private String title;

    @Schema(description = "正文摘要（最多 60 字）")
    private String desc;

    @Schema(description = "金额，去尾零字符串；null 表示线下沟通")
    private String price;

    @Schema(description = "单位：时薪/日租金/人均，按业务线推导")
    private String priceUnit;

    @Schema(description = "展示串：楼栋 · 相对时间")
    private String meta;

    @Schema(description = "发布人昵称首字，用于 monogram 头像")
    private String user;

    @Schema(description = "封面完整 URL")
    private String cover;

    @Schema(description = "进度 [当前, 目标]，仅拼单有值；跑腿返回 [0,0]")
    private int[] progress;

    @Schema(description = "标签：拾物/寻物，仅失物有值")
    private String tag;

    @Schema(description = "业务状态码")
    private Integer status;

    @Schema(description = "业务状态文案")
    private String statusText;

    /** 驳回/下架原因：仅本人可见，公开信息流里为空 */
    private String rejectReason;

    @Schema(description = "创建时间 yyyy-MM-dd HH:mm:ss")
    private String createdAt;
}
