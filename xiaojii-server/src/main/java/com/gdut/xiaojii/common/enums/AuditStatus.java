package com.gdut.xiaojii.common.enums;

// 【来源】第三方库：Lombok。只在编译期为字段生成 getter，运行期不存在这个注解本身。
// 【为什么必须 import】本类要手写 name()/ordinal()/values()/valueOf() 去模拟 enum，
//   但 getCode() / getLabel() 没手写 —— 它们就是靠这个注解生成的，删了 import 会一片爆红。
import lombok.Getter;

/**
 * 内容审核状态，对应数据库列 post.audit_status
 * （DDL 见 sql/schema.sql 的 post 表：TINYINT NOT NULL DEFAULT 0）。
 *
 * 四个取值，以及"谁会把它写成这个值"：
 * 　0 PENDING　　　 待审核　 新用户 / 信用不够的用户发帖，进人工队列（PostServiceImpl 发布逻辑）
 * 　1 MACHINE_PASS　机器通过 老用户（信用分 80 以上且已发 3 条以上）发帖，系统直接放行
 * 　2 MANUAL_PASS　 人工通过 管理员在后台点"通过"（AdminAuditServiceImpl 的 approvePost）
 * 　3 REJECTED　　　已驳回　 管理员在后台点"驳回"，同时写入 post.reject_reason
 *
 * 【为什么"机器通过"和"人工通过"要拆成两个值，而不是合成一个"已通过"】
 * 后台要按来源统计审核工作量：机器放行了多少、人工审了多少，一个数字分不出来。
 * 【代价】判断"是否已放行"就不能再写 code == 1，必须用 isPassed(Integer)
 * 或者显式列出 1 和 2；将来若再新增一种"通过"来源，所有手写数字的地方都会漏判。
 *
 * 【和 PostStatus 的关系，别搞混】
 * post.status 是业务状态（能不能被看到、是不是已下架），本类是审核状态（有没有被放行），
 * 两者是正交维度。但本项目的审核动作会顺手把 status 一起改掉
 * （通过 -> ONLINE，驳回 -> OFFLINE），所以信息流只按 status=1 过滤就够了，
 * 不需要再拼一次 audit_status 条件。
 *
 * 【为什么不用 Java 原生 enum】
 * 项目约定全局禁用 enum（2026-10-23 用户要求），这里用
 * "final 类 + 私有构造器 + public static final 常量"模拟枚举：
 * 实例总数在类加载时就固定，外部无法 new，效果与 enum 基本一致。
 * 【代价】失去了编译期对 switch 的穷举检查，新增取值时编译器不会提醒漏写分支，
 * 只能靠人记住"改常量就要改 VALUES"。
 *
 * 【谁在用这个类】发帖定审核策略（PostServiceImpl）、后台审核列表与通过/驳回
 * （AdminAuditServiceImpl）、后台首页待审数量统计（AdminConsoleServiceImpl）。
 *
 * @author 校集后端
 */
// 【注解作用】类上加 @Getter，Lombok 会为所有非静态字段生成 getter：
//   private final int code    -> public int getCode()
//   private final String label-> public String getLabel()
//   private final String name -> public String getName()
// 【为什么不手写】三个方法体完全机械，手写只会增加出错面；这也是项目里"少写 getter"的统一做法。
// 【注意一个容易困惑的点】getName() 和下面的 name() 都能取到常量名，是两套并存的方法：
//   name() 是对齐 enum 用法的写法（本项目内部调用风格），getName() 是 Lombok 按 JavaBean 规范生成的。
@Getter
// 【写法】final class + 私有构造器 + 静态常量，语义上等同于"只有 4 个实例的枚举"
// 【为什么 final】不允许被继承 —— 一旦有子类就能 new 出第 5 个实例，VALUES 的穷举假设立刻作废。
public final class AuditStatus {

    /**
     * 0 待审核，也是数据库列的默认值。
     * 写入点：发帖时用户不满足"老用户快速放行"条件，走 PostServiceImpl 的 else 分支，
     * 同时把 post.status 置为 AUDITING(0)，帖子暂时不进信息流。
     */
    public static final AuditStatus PENDING = new AuditStatus("PENDING", 0, "待审核");

    /**
     * 1 机器通过：没有任何人工介入，系统自己放行。
     * 当前唯一的来源是"先发后审"策略 —— 用户信用分 80 以上且历史发帖 3 条以上。
     * 叫"机器"只是为了和 MANUAL_PASS 在统计口径上分开，本系统并没有接入第三方内容安全服务，
     * 也没有独立的机审任务在跑。
     */
    public static final AuditStatus MACHINE_PASS = new AuditStatus("MACHINE_PASS", 1, "机器通过");

    /**
     * 2 人工通过：管理员在后台点"通过"。
     * 写入点：AdminAuditServiceImpl 的 approvePost，更新时带 CAS 条件
     * （where audit_status = 0），保证不会把已处理的帖子再审一遍。
     */
    public static final AuditStatus MANUAL_PASS = new AuditStatus("MANUAL_PASS", 2, "人工通过");

    /**
     * 3 已驳回：管理员拒绝。
     * 此时 post.reject_reason 记录驳回原因、post.status 被置为 OFFLINE(2)。
     * 用户看到的驳回文案来自 reject_reason，不是这里的 label。
     */
    public static final AuditStatus REJECTED = new AuditStatus("REJECTED", 3, "已驳回");

    /**
     * 常量全集，供 values() / of() / ordinal() 遍历。
     *
     * 【为什么必须声明在 4 个常量之后】静态字段严格按源码顺序初始化：
     * 如果挪到类开头，执行到这一行时 PENDING 等还是 null，数组里装的就是 4 个 null，
     * 之后所有 of() 查询都会 NPE。
     *
     * 【为什么是数组不是 List】values() 要返回数组（对齐 enum 的签名），存数组免去每次转换；
     * 长度固定也符合"取值集合编译期就定死"的事实。
     *
     * ⚠️ 新增取值时必须手工同步这里 —— 忘了的话 of() 认不出新值会兜底成 PENDING，
     * 　 ordinal() 返回 -1，且没有任何编译期报错兜底。
     */
    private static final AuditStatus[] VALUES = {
            PENDING, MACHINE_PASS, MANUAL_PASS, REJECTED,
    };

    /**
     * 常量名，等价于 enum 的 name()，例如 "PENDING"。
     * 只用于日志排查和 valueOf() 反查，不参与数据库读写（落库的是 code）。
     */
    private final String name;

    /**
     * 数值码，直接落库到 post.audit_status（TINYINT）。
     *
     * ⚠️ 这四个数字是"小程序 / 管理后台 / 数据库"三方的契约：
     * 　 管理端的筛选条件、存量数据的含义全绑在编号上。只能新增编号，不能重排或复用。
     */
    private final int code;

    /**
     * 中文名，给管理后台列表直接展示（AdminAuditServiceImpl 里 of(code).getLabel()）。
     * 放在后端而不是前端写死，是为了"加一个状态只改一处"，前端不用跟着发版。
     */
    private final String label;

    /**
     * 私有构造器：保证实例只能由本类的 4 个常量创建，外部拿不到任何新对象。
     *
     * 【为什么必须是 private】这是模拟 enum 的关键一环。一旦放开，
     * 任何人都能 new 出 code=9 的"审核状态"，of() 的穷举、isPassed() 的判断全部失效。
     */
    private AuditStatus(String name, int code, String label) {
        this.name = name;
        this.code = code;
        this.label = label;
    }

    /**
     * 判断这个审核状态算不算"已放行"（机器通过和人工通过都算）。
     *
     * 参数 code：数据库里的 audit_status 原值，允许为 null（老数据、未赋值的对象）
     * 返回 true 表示可以对外展示；null、0、3 以及任何未知值都返回 false
     *
     * 【为什么不直接写 code == 1】那样会把"人工通过"的帖子判成未通过，
     * 　 表现为"管理员审过了但用户还是看不到"，这种问题很难靠肉眼发现。
     *
     * 【为什么用 == 而不是 equals】MACHINE_PASS.code 是 int，右侧是基本类型，
     * 　 Integer 与 int 比较会自动拆箱成数值比较，不会踩 Integer 缓存那套坑；
     * 　 而且前一句已判过 null，拆箱不会 NPE。
     *
     * TODO 目前全项目没有任何调用点（信息流是按 post.status 过滤的，不读 audit_status）。
     * 　 保留是为了将来做"审核态过滤"时不用重新推一遍口径；若确认用不上，
     * 　 应连同这个方法一起删除，别留着当误导。
     */
    public static boolean isPassed(Integer code) {
        return code != null && (code == MACHINE_PASS.code || code == MANUAL_PASS.code);
    }

    /**
     * 数值码 -> 常量对象，未知值兜底为 PENDING。
     *
     * 参数 code：数据库里的 audit_status，可能为 null
     * 返回：对应常量；null 或 0~3 之外的值一律返回 PENDING，不抛异常
     *
     * 典型用法：管理后台把数字翻成中文 —— AuditStatus.of(r.getAuditStatus()).getLabel()，
     * 传入 1 得到 "机器通过"；传入 null 得到 "待审核"。
     *
     * 【为什么未知值兜底而不是抛异常】入参来自自己的数据库，正常一定是 0~3。
     * 　 万一出现脏值（人工改库、历史迁移），抛异常会让整个审核列表 500 打不开；
     * 　 兜底成"待审核"最多是这一条显示得不准，影响面小得多。
     * 【代价】脏数据被静默吞掉，排查时没有任何痕迹 —— 真上线应补一行 log.warn。
     * 【对照】BizType.of() 的入参来自前端（不可信），那里就必须抛错，不能兜底。
     *
     * 【为什么遍历 values() 而不是直接读 VALUES】values() 返回副本，语义上表示"只读遍历"；
     * 　 这里读 VALUES 其实也安全，写成 values() 是为了和 enum 的惯用写法保持一致，
     * 　 代价只是每次多复制一个 4 元素数组，可忽略。
     */
    public static AuditStatus of(Integer code) {
        if (code == null) {
            return PENDING;
        }
        for (AuditStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return PENDING;
    }

    /* ==================== 以下为 enum 自带能力的等价实现 ==================== */
    // 这 5 个方法原本由编译器为 enum 自动生成，项目禁用 enum 后必须手写补齐，
    // 目的是让调用点的写法与"用 enum 时"完全一致，避免业务代码被迫改造。

    /**
     * 等价于 enum 的 name()：返回常量名，如 "PENDING"。
     * 与 Lombok 生成的 getName() 等价，两个都在只是风格差异（见类上 @Getter 的说明）。
     */
    public String name() {
        return name;
    }

    /**
     * 等价于 enum 的 ordinal()：返回该常量在 VALUES 里的下标
     * （PENDING=0、MACHINE_PASS=1、MANUAL_PASS=2、REJECTED=3）。
     *
     * ⚠️ 这个下标目前恰好等于 code，但含义完全不同：下标是"声明顺序"，
     * 　 code 是"落库契约"。不要让业务代码依赖这个巧合，否则调整常量顺序就会改坏数据含义。
     *
     * 【为什么能用 == 比较】本类实例只有这 4 个静态常量，不存在"值相同但对象不同"的情况，
     * 　 比引用最快也最准。
     * 【-1 是什么】理论不可达：构造器私有，this 一定在表里，所以循环必然命中。
     * 　 留着只是防御"将来有人绕过常量手工 new"（虽然 private 已经堵住了）。
     */
    public int ordinal() {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i] == this) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 等价于 enum 的 values()：返回全部常量的副本。
     *
     * 【为什么要 clone】直接返回内部数组，调用方一句 arr[0] = null 就能把
     * 　 整个类的静态状态改坏，而单例假设一破，of() / ordinal() 全部失效。
     * 【代价】每次调用都新建一个 4 元素数组。调用频率很低（只在 of() 和调试时用），可以接受。
     */
    public static AuditStatus[] values() {
        return VALUES.clone();
    }

    /**
     * 等价于 enum 的 valueOf(String)：按常量名反查，查不到抛异常。
     *
     * 参数 name：常量名，如 "PENDING"，区分大小写
     * 返回：对应的常量对象
     * 异常：IllegalArgumentException —— name 为 null 或不匹配任何常量时抛出
     *
     * 【为什么这里抛异常，而 of() 兜底】两者入参来源不同：
     * 　 of() 的 code 来自数据库（可信）；name 通常来自配置或请求参数（不可信），
     * 　 传错必须立刻暴露，否则会静默用上错误的状态，把数据写歪。
     *
     * 【为什么 v.name.equals(name) 不会 NPE】v.name 一定非 null；
     * 　 name 传 null 时 equals 只是返回 false，最终走到抛异常分支，行为正好符合预期。
     */
    public static AuditStatus valueOf(String name) {
        for (AuditStatus v : VALUES) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("No enum constant AuditStatus." + name);
    }

    /**
     * 等价于 enum 的默认 toString()：返回常量名。
     * 好处是日志里打出来是 AuditStatus.PENDING，
     * 而不是 AuditStatus@1a2b3c 这种看不出含义的内存地址串。
     *
     * 【注解作用】@Override 来自 JDK（java.lang），让编译器校验"父类或接口里确实存在这个方法"。
     * 　 好处是将来有人把方法名写成 tostring()，编译期就报错，
     * 　 而不是悄悄多出一个没人调用的新方法，导致日志还是打地址。
     */
    @Override
    public String toString() {
        return this.name;
    }
}
