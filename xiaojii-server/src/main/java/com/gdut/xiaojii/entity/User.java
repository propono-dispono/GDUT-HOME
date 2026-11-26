package com.gdut.xiaojii.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体（对应 user 表）。
 *
 * 【它在登录链路中的位置】
 * 第 7 站（数据模型）。doLogin 里 new User() 建档、或读出已有 User，都是这个类。
 *
 * 【一个关键设计：小程序用户与管理员是两套主体】
 * user 表存小程序学生，admin_user 表存后台管理员，两个实体、两套令牌、两条拦截链。
 * 混在一起会让权限判断变得非常混乱（"这个 userId 到底是学生还是管理员？"）。
 *
 * @author 校集后端
 */
// 【注解作用】@Data：生成 getter/setter/toString/equals/hashCode。
//   实体必须有 getter/setter —— MyBatis 填值靠 setter，JSON 序列化靠 getter。
@Data
// 【注解作用】@TableName("user")：指定对应的表名。
//   不加时 MP 按类名驼峰转下划线推断（User → user），本类恰好一致，
//   但显式写出来更稳：将来类名或表名任一方改动，不会静默查错表。
@TableName("user")
public class User {

    // 【注解作用】@TableId(type = IdType.AUTO)：标记主键，且用数据库自增。
    //   不加 @TableId 时 MP 默认把名为 id 的字段当主键；
    //   指定 AUTO 是因为建表语句里 id 是 AUTO_INCREMENT，交给 MySQL 生成。
    //   注意：本项目主键是自增 BIGINT，不是雪花 ID，所以暂时没有 JS 精度问题。
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 微信 openid，全局唯一，登录主键 */
    private String openid;
    // 这是登录的唯一凭据：同一个 openid 反复登录只会命中同一条记录。
    // 边界：数据库上有唯一索引，所以并发首次登录时，第二个请求会抛 DuplicateKeyException，
    // 由 GlobalExceptionHandler 兜底成"数据已存在，请刷新重试"。

    /** 微信 unionid（同一开放平台下多应用打通用，可为空） */
    private String unionid;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 个人简介 */
    private String intro;

    /** 信用分 0-100，初始 80 */
    private Integer creditScore;
    // 初始 80 而不是 100：留出上升空间，让"信用分"对用户有激励作用。

    /** 认证状态：0未认证 1待审核 2已认证 3驳回 */
    private Integer verifyStatus;

    /** 账号状态：1正常 2禁言 3封禁 */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;
    // 每次登录都会更新，用于统计活跃。注意它没有 @TableField(fill)，是手动 set 的。

    /** 创建时间，由 MybatisMetaObjectHandler 自动填充 */
    // 【注解作用】@TableField(fill = FieldFill.INSERT)：声明"插入时由自动填充处理器写入"。
    //   没有这个注解，MybatisMetaObjectHandler 不会被触发（它是被注解"点名"才处理这个字段的）。
    //   不加会怎样：createdAt 为 null，而 DDL 里 created_at 是 NOT NULL，插入直接报错。
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /* ---------- 状态常量（避免代码里到处写魔法数字） ---------- */

    /** 认证状态：未认证 */
    public static final int VERIFY_NONE = 0;
    /** 认证状态：待审核 */
    public static final int VERIFY_PENDING = 1;
    /** 认证状态：已认证 */
    public static final int VERIFY_PASSED = 2;
    /** 认证状态：已驳回 */
    public static final int VERIFY_REJECTED = 3;

    /** 账号状态：正常 */
    public static final int STATUS_NORMAL = 1;
    /** 账号状态：禁言 */
    public static final int STATUS_MUTED = 2;
    /** 账号状态：封禁 */
    public static final int STATUS_BANNED = 3;
    // ┌─ 【面试考点】状态值为什么用 int 常量，而不是 Java 枚举？ ──────────────
    // │ Q：认证状态、账号状态明显是有限集合，用枚举不是更类型安全？
    // │ A：通常确实该用枚举。但这个项目**明确禁用了 enum**（2026-10-23 全项目重构过），
    // │    原因是这些值要和数据库的 TINYINT 字段、以及前端约定的数字直接对应：
    // │      · 存库：MyBatis 存枚举默认要配 TypeHandler，写 int 零成本；
    // │      · 传输：接口里 verifyStatus 就是数字，前端按数字分支；
    // │      · 多语言/多端：数字是最稳的契约，不依赖枚举名。
    // │    用 static final int + 集中定义，兼顾了"不写魔法数字"和"零转换成本"。
    // │    代价：没有编译期穷举检查 —— 所以新增状态值时要手工检查所有 switch/if。
    // │    **这道题能答"我知道两种方案，这里是按 X 取舍的"，就已经赢了。**
    // └────────────────────────────────────────────────────
}
