package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 课表（按周）。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "课表")
public class EduTimetableVO {

    @Schema(description = "学期信息")
    private Term term;

    @Schema(description = "当前展示的周次")
    private Integer week;

    @Schema(description = "每天节次时间表")
    private List<Period> periods;

    @Schema(description = "本周课程（已按 week 过滤）")
    private List<Course> courses;

    @Schema(description = "true 表示后端返回的是本地样例数据，不是教务真实数据")
    private Boolean fixture;

    @Schema(description = "数据抓取时刻 HH:mm，界面显示「更新于 11:20」")
    private String fetchedAt;

    @Schema(description = "true 表示教务当前不可达，这份是缓存里的旧数据，界面要提示可能不是最新")
    private Boolean stale;

    @Data
    @Schema(description = "学期")
    public static class Term {
        @Schema(description = "学年，如 2026-2027")
        private String academicYear;
        @Schema(description = "学期序号：1 上学期 / 2 下学期")
        private Integer termNo;
        @Schema(description = "学期名，如 2026-2027 第一学期")
        private String termName;
        @Schema(description = "学期起始日 yyyy-MM-dd，用于算当前周")
        private String startDate;
        @Schema(description = "当前周")
        private Integer currentWeek;
        @Schema(description = "总周数")
        private Integer weekCount;
        @Schema(description = "校区（按教室前缀推断，可能为空）")
        private String campus;
        @Schema(description = "学期来源：schedule=校历推算 / edu=教务返回")
        private String source;
    }

    @Data
    @Schema(description = "节次")
    public static class Period {
        @Schema(description = "第几节，从 1 开始")
        private Integer no;
        @Schema(description = "开始时间 HH:mm")
        private String start;
        @Schema(description = "结束时间 HH:mm")
        private String end;
    }

    @Data
    @Schema(description = "课程块")
    public static class Course {
        @Schema(description = "唯一标识")
        private String id;
        @Schema(description = "课程名")
        private String name;
        @Schema(description = "教师")
        private String teacher;
        @Schema(description = "教室")
        private String room;
        @Schema(description = "星期几，1=周一")
        private Integer day;
        @Schema(description = "起始节")
        private Integer startPeriod;
        @Schema(description = "结束节")
        private Integer endPeriod;
        @Schema(description = "上课周次")
        private List<Integer> weeks;
        @Schema(description = "配色键，前端映射为浅色块")
        private String color;
        @Schema(description = "是否与其他课程时间冲突")
        private Boolean conflict;
        @Schema(description = "同格课程数（冲突时前端并排展示）")
        private Integer slotIndex;
        @Schema(description = "同格课程总数")
        private Integer slotCount;
    }
}
