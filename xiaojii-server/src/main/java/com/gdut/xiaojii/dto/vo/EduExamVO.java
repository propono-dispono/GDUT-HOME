package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 考试安排。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "考试安排")
public class EduExamVO {

    @Schema(description = "考试列表（按时间升序）")
    private List<Exam> exams;

    @Schema(description = "true 表示本地样例数据")
    private Boolean fixture;

    @Data
    @Schema(description = "单场考试")
    public static class Exam {
        private String name;
        @Schema(description = "考试日期 yyyy-MM-dd")
        private String date;
        @Schema(description = "星期展示，如 周二")
        private String weekday;
        @Schema(description = "开始时间 HH:mm")
        private String startTime;
        @Schema(description = "结束时间 HH:mm")
        private String endTime;
        private String room;
        @Schema(description = "座位号")
        private String seat;
        @Schema(description = "考试形式，如 闭卷 / 开卷 / 上机")
        private String type;
        @Schema(description = "距今天数，负数表示已考完")
        private Integer daysLeft;
    }
}
