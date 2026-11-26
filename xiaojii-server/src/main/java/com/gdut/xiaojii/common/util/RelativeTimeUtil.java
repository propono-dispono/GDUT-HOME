package com.gdut.xiaojii.common.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 相对时间格式化。
 *
 * <p>前端卡片上显示的是 "10分钟前" / "2小时前" / "昨天" 这种口语化文案，
 * 由后端算好直接下发（字段名 {@code meta}）。放在后端算的好处：
 * 手机时间不准、时区不一致时不会显示"3分钟后"。
 *
 * <p>规则：
 * <ul>
 *   <li>&lt; 1 分钟 → 刚刚</li>
 *   <li>&lt; 1 小时 → N分钟前</li>
 *   <li>当天 → N小时前</li>
 *   <li>昨天 → 昨天</li>
 *   <li>本年内 → MM-dd</li>
 *   <li>更早 → yyyy-MM-dd</li>
 * </ul>
 *
 * @author 校集后端
 */
public final class RelativeTimeUtil {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private RelativeTimeUtil() {
    }

    /** 格式化 */
    public static String format(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        LocalDateTime now = LocalDateTime.now();
        // 未来时间（时钟偏差）按"刚刚"处理，避免出现"−3分钟前"
        if (time.isAfter(now)) {
            return "刚刚";
        }
        long minutes = Duration.between(time, now).toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + "分钟前";
        }
        LocalDate today = now.toLocalDate();
        LocalDate date = time.toLocalDate();
        if (date.equals(today)) {
            return Duration.between(time, now).toHours() + "小时前";
        }
        if (date.equals(today.minusDays(1))) {
            return "昨天";
        }
        if (date.getYear() == today.getYear()) {
            return date.format(MD);
        }
        return date.format(YMD);
    }

    /** 聊天气泡上的时间只显示 HH:mm，跨天补日期 */
    public static String formatChat(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        LocalDate today = LocalDate.now();
        if (time.toLocalDate().equals(today)) {
            return time.format(HM);
        }
        if (time.toLocalDate().equals(today.minusDays(1))) {
            return "昨天 " + time.format(HM);
        }
        return time.format(MD) + " " + time.format(HM);
    }

    /** 列表卡片的 meta 串：楼栋 + 相对时间，两者都有才拼分隔符 */
    public static String meta(String building, LocalDateTime time) {
        String t = format(time);
        boolean hasBuilding = building != null && !building.isBlank();
        if (hasBuilding && !t.isEmpty()) {
            return building + " · " + t;
        }
        return hasBuilding ? building : t;
    }
}
