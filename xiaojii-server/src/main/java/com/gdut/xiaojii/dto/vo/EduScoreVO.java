package com.gdut.xiaojii.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 成绩与 GPA。
 *
 * <p>三个层级都给：按学期（区分上下学期）、按学年、按年级，再加一个全量，
 * 口径由 {@code EduGpaCalculator} 统一保障，前端只做展示不重算。
 *
 * @author 校集后端
 */
@Data
@Schema(description = "成绩与GPA")
public class EduScoreVO {

    @Schema(description = "按学期分组（新在前）")
    private List<TermScore> terms;

    @Schema(description = "汇总统计")
    private Stats stats;

    @Schema(description = "true 表示本地样例数据")
    private Boolean fixture;

    @Data
    @Schema(description = "单学期成绩")
    public static class TermScore {
        @Schema(description = "唯一键，如 2026-2027-1")
        private String key;
        private String academicYear;
        @Schema(description = "1 上学期 / 2 下学期")
        private Integer termNo;
        @Schema(description = "如 2026-2027 第一学期")
        private String termName;
        @Schema(description = "学期 GPA")
        private Double gpa;
        @Schema(description = "计入 GPA 的学分")
        private Double credits;
        @Schema(description = "已修学分（通过的课）")
        private Double earnedCredits;
        @Schema(description = "课程数")
        private Integer courseCount;
        private List<CourseScore> courses;
    }

    @Data
    @Schema(description = "单科成绩")
    public static class CourseScore {
        private String name;
        private Double credit;
        @Schema(description = "原始成绩文本，如 88 / 优秀")
        private String score;
        @Schema(description = "绩点（教务给了就用教务的）")
        private Double point;
        @Schema(description = "课程性质，必修/选修")
        private String nature;
        @Schema(description = "考核方式，考试/考查")
        private String examType;
        @Schema(description = "是否通过")
        private Boolean passed;
    }

    @Data
    @Schema(description = "汇总")
    public static class Stats {
        @Schema(description = "全部课程 GPA")
        private Double gpa;
        private Double credits;
        private Double earnedCredits;
        private Integer courseCount;
        @Schema(description = "参与计算的学期数")
        private Integer termCount;
        private List<GroupStat> byYear;
        private List<GroupStat> byGrade;
    }

    @Data
    @Schema(description = "分组统计")
    public static class GroupStat {
        @Schema(description = "分组键：学年 或 年级序号")
        private String key;
        @Schema(description = "展示名：2026-2027 学年 / 大三")
        private String label;
        private Double gpa;
        private Double credits;
        private Double earnedCredits;
        private Integer courseCount;
    }
}
