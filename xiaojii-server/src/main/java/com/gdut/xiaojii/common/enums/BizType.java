package com.gdut.xiaojii.common.enums;

import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import lombok.Getter;

import java.util.Arrays;

/**
 * 业务线枚举（六条线）。
 *
 * 【为什么保留 6 个值却全部实现】
 * 前端首页金刚区、发布页都按 6 条渲染。枚举少一个，前端拿到的 bizType 就会在 BIZ[biz] 里查不到，
 * 导致卡片形态回落、按钮文案变成 undefined。
 *
 * 【命名坑（真实踩过）】
 * 设计文档 DDL 注释里写的是 RENT，但前端 data.js 与页面 URL 全部用 RENTAL。
 * 这里统一取 RENTAL（前端契约优先），扩展表名仍叫 post_rent。
 * 也就是说：**代码里的常量名和数据库表名不同步**，搜索时两个词都要试。
 *
 * 【⚠️ 两个标志位是死字段（已核实）】
 * hasExtTable / orderable 看起来是用来驱动"要不要写扩展表""要不要走订单"的，
 * 但全项目搜索确认：**除了本类的定义与赋值，没有任何一处调用**。
 * 而且 SECONDHAND 的 hasExtTable 写的是 true，但数据库里并没有 post_secondhand 表 ——
 * 语义与实际不符。
 * 真正的判断在 PostServiceImpl 里写成 if (biz == BizType.ERRAND) ... else if 这样一条链。
 * 这带来一个真实风险：**新增一条业务线时，编译器不会提醒你补扩展表 / 必填校验逻辑**。
 *
 * @author 校集后端
 */
// 【写法】@Getter 生成 getName / getLabel / isHasExtTable / isOrderable
// 【为什么】label 要给管理端和日志用；两个 boolean 的 getter 虽然当前没人调，但保留着不影响
@Getter
// 【写法】final class + 私有构造器 + public static final 常量（模拟枚举）
// 【为什么】本项目约定不使用 Java enum（2026-10-23 全项目改造）。
//   这里手写 name() / ordinal() / values() / valueOf() 四个方法，让调用方写法与真枚举保持一致。
public final class BizType {

    // 二手闲置：最常见的一条线，没有扩展表（差异字段在 post 主表里就够）
    public static final BizType SECONDHAND = new BizType("SECONDHAND", "二手闲置", true, true);
    // 跑腿代办：独有字段（取件地/送达地/截止时间/是否垫付）存在 post_errand
    public static final BizType ERRAND = new BizType("ERRAND", "跑腿代办", true, true);
    // 技能互助：独有字段（服务方式/时长/可服务时间）存在 post_skill
    public static final BizType SKILL = new BizType("SKILL", "技能互助", true, true);
    // 物品租赁：独有字段（日租金/押金/租期）存在 post_rent
    // 注意常量名是 RENTAL，但扩展表叫 post_rent（见类注释的命名坑）
    public static final BizType RENTAL = new BizType("RENTAL", "物品租赁", true, true);
    // 失物招领：独有字段（物品描述/拾获地点/联系方式）存在 post_lost
    // orderable=false：失物只走私信联系，不产生订单
    public static final BizType LOST = new BizType("LOST", "失物招领", true, false);
    // 校园拼单：独有字段（目标人数/截止时间/成团价）存在 post_groupbuy
    // orderable=false：拼单走成员表（groupbuy_member），不产生订单
    public static final BizType GROUPBUY = new BizType("GROUPBUY", "校园拼单", true, false);

    /** 常量全集：声明在常量之后，保证静态初始化顺序安全 */
    // 【为什么必须放在所有常量之后】静态字段按声明顺序初始化。
    //   若放在前面，轮到它初始化时上面的常量还是 null，数组里会装进 null。
    //   （详见 ResultCode 里同样的注释）
    private static final BizType[] VALUES = {
            SECONDHAND, ERRAND, SKILL, RENTAL,
            LOST, GROUPBUY
    };

    /** 常量名，等价于 enum 的 name */
    // 这个值会直接写进 post.biz_type 字段，所以要保证与前端约定完全一致
    private final String name;

    /** 中文名，用于日志与管理端展示 */
    private final String label;

    /** 是否需要写扩展表 */
    // ⚠️ 死字段：全项目无调用点，且 SECONDHAND 的值为 true 而实际没有 post_secondhand 表
    private final boolean hasExtTable;

    /** 是否走订单（失物只私信、拼单走成员表，都不建订单） */
    // ⚠️ 死字段：全项目无调用点。本项目已按 B 方案移除订单链路，这个语义已失效
    private final boolean orderable;

    // 【写法】私有构造器
    // 【为什么】只允许类内部创建这 6 个固定实例，外部 new 不出第 7 条业务线 —— 模拟枚举的核心约束
    private BizType(String name, String label, boolean hasExtTable, boolean orderable) {
        this.name = name;
        this.label = label;
        this.hasExtTable = hasExtTable;
        this.orderable = orderable;
    }

    /**
     * 字符串转枚举，非法值抛业务异常而不是 NPE。
     * 入参来自前端，必须当作不可信数据处理。
     */
    // 【写法】用 of() + stream 查找，找不到抛 BizException(PARAM_ERROR)
    // 【为什么】前端可能传 "secondhand"（小写）、"FOO"（乱传）、null。
    //   如果直接 valueOf(code) 会抛 IllegalArgumentException → 掉进兜底变成 500；
    //   而"业务线传错了"本质是参数问题，应该返回 400 + 明确提示。
    // 【忽略大小写】equalsIgnoreCase 让 "secondhand" 也能匹配 ——
    //   容错是为了减少联调摩擦，不是安全考虑（值仍然必须在白名单内）
    // 【另一个做法】返回 null 让调用方判空 —— 不可取，调用方很容易忘判，
    //   然后在后面某个地方 NPE，堆栈指向的位置和真正原因相距很远
    public static BizType of(String code) {
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> BizException.of(ResultCode.PARAM_ERROR, "不支持的业务线：" + code));
    }

    /* ==================== 以下为 enum 自带能力的等价实现 ==================== */

    /** 等价于 enum 的 name() */
    public String name() {
        return name;
    }

    /** 等价于 enum 的 ordinal()：在常量表中的位置 */
    // 【为什么用 == 比较】这些常量是全应用仅有的 6 个实例，== 比 equals 快且不会因重写出错
    public int ordinal() {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == this) {
                return i;
            }
        }
        return -1;
    }

    /** 等价于 enum 的 values()：返回副本，防止外部改坏内部数组 */
    // 【为什么必须 clone】直接返回内部数组的话，调用方一句 arr[0]=null 就把内部数据改坏了
    public static BizType[] values() {
        return VALUES.clone();
    }

    /** 等价于 enum 的 valueOf(String) */
    // 【为什么找不到要抛异常】与真枚举行为一致（Enum.valueOf 找不到抛 IllegalArgumentException），
    //   避免调用方误以为"返回 null 就是没找到"而漏判
    public static BizType valueOf(String name) {
        for (BizType v : VALUES) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("No enum constant BizType." + name);
    }

    /** 等价于 enum 的默认 toString() */
    @Override
    public String toString() {
        return this.name;
    }
}
