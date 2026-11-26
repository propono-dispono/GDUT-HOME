package com.gdut.xiaojii.common.util;

/**
 * 文本处理小工具：脱敏、截断、首字头像。
 *
 * @author 校集后端
 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 取昵称首字作为头像文字（前端用 monogram 头像，不下载真人照片） */
    public static String initial(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "校";
        }
        return nickname.trim().substring(0, 1);
    }

    /**
     * 学号脱敏：2023xxxx17。
     * 认证页要回显学号，但不能把完整学号下发到前端（截图外泄风险）。
     */
    public static String maskStudentNo(String studentNo) {
        if (studentNo == null || studentNo.length() < 6) {
            return studentNo == null ? "" : "****";
        }
        int keepHead = Math.min(4, studentNo.length() - 2);
        int keepTail = 2;
        return studentNo.substring(0, keepHead)
                + "*".repeat(Math.max(4, studentNo.length() - keepHead - keepTail))
                + studentNo.substring(studentNo.length() - keepTail);
    }

    /** 列表摘要：去掉换行、截断到指定长度，超出加省略号 */
    public static String summary(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxLen ? flat : flat.substring(0, maxLen) + "…";
    }

    /** 空白串归一为 null，便于判空 */
    public static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }
}
