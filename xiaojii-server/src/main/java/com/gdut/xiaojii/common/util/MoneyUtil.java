package com.gdut.xiaojii.common.util;

import java.math.BigDecimal;

/**
 * 金额格式化工具。
 *
 * <p><b>为什么需要它：</b>数据库是 {@code DECIMAL(10,2)}，Jackson 默认序列化成
 * {@code 25.00}，而设计稿上写的是 {@code ¥25}。多出来的两个 0 会让前端价格标签变宽、
 * 破坏"价格用黑色 18/700"的版式。所以在 VO 里把金额转成字符串下发，去尾零。
 *
 * <p><b>红线：</b>金额在本项目里只做<b>记录与展示</b>，平台不经手资金，
 * 没有任何扣款、退款、分账逻辑。所有涉及钱的文案必须带"线下自行结算"。
 *
 * @author 校集后端
 */
public final class MoneyUtil {

    private MoneyUtil() {
    }

    /**
     * 数据库金额 → 前端展示串。
     * 25.00 → "25"，25.50 → "25.5"，null → null（前端据此显示"线下沟通"）。
     */
    public static String plain(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        // stripTrailingZeros 对整数会返回 2.5E+1 这种科学计数法，必须 toPlainString
        return amount.stripTrailingZeros().toPlainString();
    }

    /** 从可空字符串安全转 BigDecimal，非法值返回 null 而不是抛异常 */
    public static BigDecimal parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 是否为正数金额（用于校验） */
    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
    }
}
