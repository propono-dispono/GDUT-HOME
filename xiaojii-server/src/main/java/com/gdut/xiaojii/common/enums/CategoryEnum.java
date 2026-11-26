package com.gdut.xiaojii.common.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 二手分类。
 *
 * <p><b>为什么用枚举而不是建分类表：</b>分类是运营层面的固定枚举（教材/数码/生活/服饰/运动/其他），
 * 数量少且几乎不变，建表只多一次 join 和一套增删改查。{@code post.category_id} 存的
 * 就是本枚举的 id，前端 {@code GET /app/post/categories} 直接返回本枚举列表。
 *
 * <p>将来若真要支持运营自定义分类，把本枚举换成表即可，字段类型不用改。
 *
 * @author 校集后端
 */
@Getter
public final class CategoryEnum {

    public static final CategoryEnum TEXTBOOK = new CategoryEnum("TEXTBOOK", 1, "教材教辅", "SECONDHAND");
    public static final CategoryEnum DIGITAL = new CategoryEnum("DIGITAL", 2, "数码电子", "SECONDHAND");
    public static final CategoryEnum LIFE = new CategoryEnum("LIFE", 3, "生活用品", "SECONDHAND");
    public static final CategoryEnum CLOTHES = new CategoryEnum("CLOTHES", 4, "服饰鞋包", "SECONDHAND");
    public static final CategoryEnum SPORTS = new CategoryEnum("SPORTS", 5, "运动户外", "SECONDHAND");
    public static final CategoryEnum OTHER = new CategoryEnum("OTHER", 6, "其他", "SECONDHAND");

    /* ---------- 跑腿 ---------- */
    public static final CategoryEnum EXPRESS = new CategoryEnum("EXPRESS", 11, "代取快递", "ERRAND");
    public static final CategoryEnum PRINT = new CategoryEnum("PRINT", 12, "打印代办", "ERRAND");
    public static final CategoryEnum BUY = new CategoryEnum("BUY", 13, "代买代送", "ERRAND");

    /* ---------- 技能 ---------- */
    public static final CategoryEnum PPT = new CategoryEnum("PPT", 21, "PPT 美化", "SKILL");
    public static final CategoryEnum DESIGN = new CategoryEnum("DESIGN", 22, "修图剪辑", "SKILL");
    public static final CategoryEnum TUTOR = new CategoryEnum("TUTOR", 23, "答疑辅导", "SKILL");

    /* ---------- 租赁 ---------- */
    public static final CategoryEnum CAMERA = new CategoryEnum("CAMERA", 31, "相机摄影", "RENTAL");
    public static final CategoryEnum CLOTHING_RENT = new CategoryEnum("CLOTHING_RENT", 32, "正装礼服", "RENTAL");
    public static final CategoryEnum OUTDOOR = new CategoryEnum("OUTDOOR", 33, "户外装备", "RENTAL");

    /* ---------- 拼单 ---------- */
    public static final CategoryEnum FRUIT = new CategoryEnum("FRUIT", 41, "水果生鲜", "GROUPBUY");
    public static final CategoryEnum STATIONERY = new CategoryEnum("STATIONERY", 42, "文具日用", "GROUPBUY");

    /** 常量全集：声明在常量之后，保证静态初始化顺序安全 */
    private static final CategoryEnum[] VALUES = {
            TEXTBOOK, DIGITAL, LIFE, CLOTHES,
            SPORTS, OTHER, EXPRESS, PRINT,
            BUY, PPT, DESIGN, TUTOR,
            CAMERA, CLOTHING_RENT, OUTDOOR, FRUIT,
            STATIONERY
    };

    /** 常量名，等价于 enum 的 name */
    private final String name;

    private final int id;
    private final String label;

    /** 所属业务线，前端按当前 bizType 过滤出二级分类 */
    private final String bizType;

    private CategoryEnum(String name, int id, String label, String bizType) {
        this.name = name;
        this.id = id;
        this.label = label;
        this.bizType = bizType;
    }

    public static CategoryEnum of(Integer id) {
        if (id == null) {
            return OTHER;
        }
        return Arrays.stream(values())
                .filter(c -> c.id == id)
                .findFirst()
                .orElse(OTHER);
    }

    /** 分类名，用于 VO 展示 */
    public static String labelOf(Integer id) {
        return of(id).label;
    }

    /* ==================== 以下为 enum 自带能力的等价实现 ==================== */

    /** 等价于 enum 的 name() */
    public String name() {
        return name;
    }

    /** 等价于 enum 的 ordinal()：在常量表中的位置 */
    public int ordinal() {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == this) {
                return i;
            }
        }
        return -1;
    }

    /** 等价于 enum 的 values()：返回副本，防止外部改坏内部数组 */
    public static CategoryEnum[] values() {
        return VALUES.clone();
    }

    /** 等价于 enum 的 valueOf(String) */
    public static CategoryEnum valueOf(String name) {
        for (CategoryEnum v : VALUES) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("No enum constant CategoryEnum." + name);
    }

    /** 等价于 enum 的默认 toString() */
    @Override
    public String toString() {
        return this.name;
    }
}
