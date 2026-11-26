package com.gdut.xiaojii.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 教务 GPA 计算（纯函数，便于单测）。
 *
 * <p><b>口径（必须与页面展示一致）：</b>
 * <ul>
 *   <li>单科绩点：优先用教务系统返回的「学分绩点」；系统没给才换算。</li>
 *   <li>换算（广工常用 5 分制）：成绩 ≥ 60 时 绩点 = (成绩 - 50) / 10，上限 5.0；&lt; 60 记 0。</li>
 *   <li>等级制（优秀/良好/中等/及格/不及格）按映射表折算。</li>
 *   <li>平均学分绩点 = Σ(学分 × 绩点) / Σ学分，分母含挂科（绩点为 0，会拉低 GPA）。</li>
 *   <li>学分或绩点缺失的课程不计入分子分母，用 {@code skipped} 记数，避免静默失真。</li>
 * </ul>
 *
 * @author 校集后端
 */
public final class EduGpaCalculator {

    /** 5 分制上限 */
    public static final double MAX_POINT = 5.0;

    /** 等级制 → 绩点 */
    private static final Map<String, Double> LEVEL_POINTS = Map.of(
            "优秀", 4.5,
            "良好", 3.5,
            "中等", 2.5,
            "及格", 1.5,
            "不及格", 0.0,
            "通过", 3.0,
            "不通过", 0.0);

    private EduGpaCalculator() {
    }

    /**
     * 计算单科绩点。
     *
     * @param score    原始成绩文本，可能是 "88"、"88.5"、"优秀"、"" 或 null
     * @param eduPoint 教务系统直接给的绩点，可能为 null
     * @return 绩点；无法判定时返回 null（由调用方计为 skipped）
     */
    public static Double point(String score, Double eduPoint) {
        // 教务给的绩点最权威，直接用
        if (eduPoint != null && eduPoint >= 0 && eduPoint <= MAX_POINT) {
            return round(eduPoint, 2);
        }
        if (score == null) {
            return null;
        }
        String s = score.trim();
        if (s.isEmpty() || "缓考".equals(s) || "缺考".equals(s) || "--".equals(s)) {
            return null;
        }
        Double level = LEVEL_POINTS.get(s);
        if (level != null) {
            return level;
        }
        // 去掉 "88分" 这类后缀
        String num = s.replaceAll("[^0-9.]", "");
        if (num.isEmpty()) {
            return null;
        }
        try {
            double v = Double.parseDouble(num);
            if (v < 0 || v > 100) {
                return null;
            }
            return v >= 60 ? round(Math.min((v - 50) / 10.0, MAX_POINT), 2) : 0.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 加权平均学分绩点。
     *
     * @param credits 学分
     * @param points  绩点（同下标；null 表示该课无法计算）
     * @return gpa；没有任何有效课程时返回 null
     */
    public static Double weighted(List<Double> credits, List<Double> points) {
        if (credits == null || points == null || credits.size() != points.size()) {
            return null;
        }
        double sumCredit = 0;
        double sumProduct = 0;
        for (int i = 0; i < credits.size(); i++) {
            Double c = credits.get(i);
            Double p = points.get(i);
            if (c == null || p == null || c <= 0) {
                continue;
            }
            sumCredit += c;
            sumProduct += c * p;
        }
        if (sumCredit <= 0) {
            return null;
        }
        return round(sumProduct / sumCredit, 2);
    }

    /** 计入 GPA 的学分合计（与 weighted 的分母保持一致） */
    public static double countedCredits(List<Double> credits, List<Double> points) {
        double sum = 0;
        if (credits == null || points == null) {
            return 0;
        }
        for (int i = 0; i < credits.size(); i++) {
            Double c = credits.get(i);
            Double p = points.get(i);
            if (c != null && p != null && c > 0) {
                sum += c;
            }
        }
        return round(sum, 2);
    }

    /** 成绩是否通过（用于「已修学分」，挂科不计入已修） */
    public static boolean passed(String score, Double point) {
        if (point != null) {
            return point > 0;
        }
        if (score == null) {
            return false;
        }
        String s = score.trim();
        if ("不及格".equals(s) || "不通过".equals(s)) {
            return false;
        }
        String num = s.replaceAll("[^0-9.]", "");
        if (num.isEmpty()) {
            return "优秀".equals(s) || "良好".equals(s) || "中等".equals(s)
                    || "及格".equals(s) || "通过".equals(s);
        }
        try {
            return Double.parseDouble(num) >= 60;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 统一保留两位小数（四舍五入） */
    public static Double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
