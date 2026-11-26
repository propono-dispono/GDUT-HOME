package com.gdut.xiaojii.common;

import lombok.Getter;

/**
 * 统一返回码。
 *
 * 约定：0 表示成功，其余为失败。
 * 业务错误从 1000 起步，HTTP 状态码沿用 4xx/5xx 的语义值，
 * 便于前端按 code 做统一处理（401 跳登录、403 提示无权限等）。
 *
 * 【为什么不用 Java enum，而用 final 类 + 静态常量】
 * 这是本项目的统一约定（2026-10-23 全项目改造）：用 final 类模拟枚举。
 * 好处是常量就是普通对象，可以携带 name / code / msg 三个字段，序列化也更直白；
 * 同时下面手写了 name() / ordinal() / values() / valueOf() 四个方法，
 * 调用方的写法与真枚举完全一致，改造时不用改任何业务代码。
 *
 * 【登录业务用到的 4 个码】
 *   1001 WX_LOGIN_FAIL —— 微信换 openid 失败（凭证失效/太频繁/AppSecret 错）
 *   1002 TOKEN_EXPIRED —— 令牌过期（拦截器抛出，前端应跳登录页）
 *   1003 TOKEN_INVALID —— 令牌伪造/格式错/已被踢（前端也跳登录，但不提示"过期"）
 *   1010 USER_BANNED   —— 账号被封禁（登录时判定，不给发令牌）
 *
 * @author 校集后端
 */
// 【写法】@Getter 生成 getCode() / getMsg() / getName()
// 【为什么】Result.ok()/fail()、BizException、全局处理器都要读这些字段
@Getter
// 【写法】final class + 私有构造器 + public static final 常量
// 【为什么】模拟枚举的"不可变、不可继承、只能取预定义实例"三个特性：
//   final 类 → 别人不能继承出新的"码"；私有构造器 → 别人不能 new 出第 49 种错误码
// 【另一种】真 enum —— 本项目约定不用，见类注释
public final class ResultCode {

    /* ---------- 通用 ---------- */
    // 用 HTTP 语义值（400/401/403/404/405/429/500）作为通用码，
    // 好处是前端/网关看到 401 就知道该跳登录，不需要额外查表
    public static final ResultCode OK = new ResultCode("OK", 0, "ok");
    public static final ResultCode PARAM_ERROR = new ResultCode("PARAM_ERROR", 400, "参数不合法");
    public static final ResultCode UNAUTHORIZED = new ResultCode("UNAUTHORIZED", 401, "请先登录");
    /** 403 无权访问 —— 令牌类型不匹配（如学生令牌调管理端接口） */
    public static final ResultCode FORBIDDEN = new ResultCode("FORBIDDEN", 403, "无权访问");
    /** 404 资源不存在 —— 按 id 查不到记录 */
    public static final ResultCode NOT_FOUND = new ResultCode("NOT_FOUND", 404, "资源不存在");
    /** 405 请求方式不对 —— 用 GET 调了 POST 接口 */
    public static final ResultCode METHOD_NOT_ALLOWED = new ResultCode("METHOD_NOT_ALLOWED", 405, "请求方式不支持");
    /** 429 限流 —— 同一账号/IP 短时间请求过多 */
    public static final ResultCode RATE_LIMIT = new ResultCode("RATE_LIMIT", 429, "操作太频繁，请稍后再试");
    /** 500 服务端异常 —— 未预料的异常，全局兜底返回 */
    public static final ResultCode SERVER_ERROR = new ResultCode("SERVER_ERROR", 500, "服务器开小差了");

    /* ---------- 业务错误（1000 起） ---------- */
    /** 通用业务失败，具体原因由 message 覆盖 */
    public static final ResultCode BIZ_ERROR = new ResultCode("BIZ_ERROR", 1000, "操作失败");
    /** 登录相关 */
    // 微信换 openid 失败。具体原因（凭证失效/太频繁/密钥错）由 BizException 的 msg 覆盖
    public static final ResultCode WX_LOGIN_FAIL = new ResultCode("WX_LOGIN_FAIL", 1001, "微信登录失败");
    // 令牌过期。由 JwtUtil.parse 的 catch(ExpiredJwtException) 抛出
    public static final ResultCode TOKEN_EXPIRED = new ResultCode("TOKEN_EXPIRED", 1002, "登录已过期，请重新登录");
    // 令牌无效。由拦截器抛出（验签失败 / Redis 白名单对不上 / 被别的设备顶掉）
    public static final ResultCode TOKEN_INVALID = new ResultCode("TOKEN_INVALID", 1003, "登录凭证无效");
    /** 用户状态相关 */
    // 登录时判定：封禁用户不给令牌，否则他拿旧令牌还能调其他接口
    public static final ResultCode USER_BANNED = new ResultCode("USER_BANNED", 1010, "账号已被封禁");
    public static final ResultCode USER_MUTED = new ResultCode("USER_MUTED", 1011, "账号已被禁言");
    public static final ResultCode USER_NOT_VERIFIED = new ResultCode("USER_NOT_VERIFIED", 1012, "请先完成校园身份认证");
    /** 认证相关 */
    public static final ResultCode STUDENT_NO_EXISTS = new ResultCode("STUDENT_NO_EXISTS", 1020, "该学号已被其他账号绑定");
    public static final ResultCode VERIFY_PENDING = new ResultCode("VERIFY_PENDING", 1021, "认证正在审核中，请勿重复提交");
    public static final ResultCode VERIFY_ALREADY_PASS = new ResultCode("VERIFY_ALREADY_PASS", 1022, "你已完成认证");
    /** 发布相关 */
    public static final ResultCode POST_NOT_FOUND = new ResultCode("POST_NOT_FOUND", 1030, "内容不存在或已下架");
    public static final ResultCode POST_NOT_OWNER = new ResultCode("POST_NOT_OWNER", 1031, "只能操作自己发布的内容");
    public static final ResultCode POST_STATUS_ILLEGAL = new ResultCode("POST_STATUS_ILLEGAL", 1032, "当前状态不允许该操作");
    /** 订单相关（状态机相关错误码，冲突统一用 1032 语义） */
    public static final ResultCode ORDER_NOT_FOUND = new ResultCode("ORDER_NOT_FOUND", 1040, "订单不存在");
    public static final ResultCode ORDER_STATUS_CHANGED = new ResultCode("ORDER_STATUS_CHANGED", 1041, "订单状态已变化，请刷新后重试");
    public static final ResultCode ORDER_NOT_PARTICIPANT = new ResultCode("ORDER_NOT_PARTICIPANT", 1042, "你不是该订单的参与方");
    /** 1043 【未使用】B 方案移除订单链路后遗留 */
    public static final ResultCode ORDER_ACTION_FORBIDDEN = new ResultCode("ORDER_ACTION_FORBIDDEN", 1043, "当前状态下你没有这个操作权限");
    /** 聊天相关 */
    public static final ResultCode SESSION_NOT_FOUND = new ResultCode("SESSION_NOT_FOUND", 1050, "会话不存在");
    public static final ResultCode CHAT_SELF = new ResultCode("CHAT_SELF", 1051, "不能和自己聊天");

    /* ---------- 1060-1069 文件上传 ---------- */
    /** 1060 类型不支持 —— 上传的不是 jpg/jpeg/png/webp */
    public static final ResultCode FILE_TYPE_NOT_ALLOWED = new ResultCode("FILE_TYPE_NOT_ALLOWED", 1060, "只支持 jpg/jpeg/png/webp 格式的图片");
    /** 1061 文件过大 —— 超过上传大小上限 */
    public static final ResultCode FILE_TOO_LARGE = new ResultCode("FILE_TOO_LARGE", 1061, "图片太大了，请压缩后再上传");
    /** 1062 上传失败 —— 写磁盘或落库出错 */
    public static final ResultCode FILE_UPLOAD_FAIL = new ResultCode("FILE_UPLOAD_FAIL", 1062, "图片上传失败，请重试");
    /** 1063 空文件 —— 上传了 0 字节内容 */
    public static final ResultCode FILE_EMPTY = new ResultCode("FILE_EMPTY", 1063, "请选择要上传的图片");

    /* ---------- 1070-1079 拼单 ---------- */
    /** 1070 已满员 —— 加入时人数已达 targetCount */
    public static final ResultCode GROUPBUY_FULL = new ResultCode("GROUPBUY_FULL", 1070, "该拼单已满员");
    /** 1071 未参与 —— 退出一个没参加过的拼单 */
    public static final ResultCode GROUPBUY_NOT_JOINED = new ResultCode("GROUPBUY_NOT_JOINED", 1071, "你还没有参与该拼单");
    /** 1072 发起人不能退 —— 只能关闭帖子 */
    public static final ResultCode GROUPBUY_OWNER_CANNOT_QUIT = new ResultCode("GROUPBUY_OWNER_CANNOT_QUIT", 1072, "发起人不能退出拼单，如需取消请关闭帖子");
    /** 1073 已结束 —— 超过截止时间或已关闭 */
    public static final ResultCode GROUPBUY_ENDED = new ResultCode("GROUPBUY_ENDED", 1073, "该拼单已结束");
    /** 1074 重复参加 —— 同一个拼单加入两次 */
    public static final ResultCode GROUPBUY_ALREADY_JOINED = new ResultCode("GROUPBUY_ALREADY_JOINED", 1074, "你已经参与过该拼单");

    /* ---------- 1080-1089 举报 / 评价 ---------- */
    /** 1080 重复举报 —— 同一内容同一人只能举报一次 */
    public static final ResultCode REPORT_DUPLICATE = new ResultCode("REPORT_DUPLICATE", 1080, "你已举报过该内容，请耐心等待处理");
    /** 1081 举报对象无效 —— 帖子/用户已不存在 */
    public static final ResultCode REPORT_TARGET_INVALID = new ResultCode("REPORT_TARGET_INVALID", 1081, "举报对象不存在");
    /** 1082 重复评价 —— 同一订单已评价过 */
    public static final ResultCode REVIEW_DUPLICATE = new ResultCode("REVIEW_DUPLICATE", 1082, "你已评价过该订单");
    /** 1083 不可评价 —— 订单未完成【未使用】订单链路已移除 */
    public static final ResultCode REVIEW_NOT_ALLOWED = new ResultCode("REVIEW_NOT_ALLOWED", 1083, "订单未完成，暂时不能评价");

    /* ---------- 1090-1099 管理端 ---------- */
    /** 1090 管理员登录失败 —— 账号或密码错 */
    public static final ResultCode ADMIN_LOGIN_FAIL = new ResultCode("ADMIN_LOGIN_FAIL", 1090, "账号或密码错误");
    /** 1091 管理员停用 —— 账号状态为停用 */
    public static final ResultCode ADMIN_DISABLED = new ResultCode("ADMIN_DISABLED", 1091, "该管理员账号已停用");

    /* ---------- 1093-1099 其他业务 ---------- */
    /** 1093 业务线不支持 —— 该线不支持下单【注意】下单已移除 */
    public static final ResultCode BIZ_TYPE_NOT_SUPPORTED = new ResultCode("BIZ_TYPE_NOT_SUPPORTED", 1093, "该业务线暂不支持下单");
    /** 1094 信用分不足 —— 低于 60 不能发帖 */
    public static final ResultCode CREDIT_NOT_ENOUGH = new ResultCode("CREDIT_NOT_ENOUGH", 1094, "信用分不足，暂时无法进行该操作");
    /** 1095 内容违规 —— 命中敏感词库 */
    public static final ResultCode CONTENT_ILLEGAL = new ResultCode("CONTENT_ILLEGAL", 1095, "内容包含违规信息，请修改后重试");
    /** 1096 已过期 —— 跑腿/拼单超过截止时间 */
    public static final ResultCode POST_EXPIRED = new ResultCode("POST_EXPIRED", 1096, "内容已过期");
    /** 1097 已关闭 —— 作者或管理员关闭 */
    public static final ResultCode POST_CLOSED = new ResultCode("POST_CLOSED", 1097, "内容已关闭");
    /** 1098 操作过频 —— 如刷新限速 */
    public static final ResultCode OPERATION_TOO_FREQUENT = new ResultCode("OPERATION_TOO_FREQUENT", 1098, "操作过于频繁，请稍后再试");

    /* ---------- 1100-1109 跑腿接单 ---------- */
    // 【这一段为什么单独占一段编号】跑腿接单有 4 种"接不到"的原因，前端要按 code 分支
    // 给出不同文案（"被别人抢了" vs "你已经接过" vs "已结束"），
    // 把它们塞进同一个 BIZ_ERROR 会让前端只能显示同一句话，用户会以为系统出错了。
    //
    // 模拟数据：用户 7 对帖子 88 点「我接」，四种可能的响应
    //   {code:1101, msg:"手慢了，这单已被接走"}      ← 别人先接走了
    //   {code:1102, msg:"你已经接过这单了"}          ← 自己双击了两次
    //   {code:1100, msg:"跑腿单不存在或已结束"}      ← 帖子被下架/已过期
    //   {code:0,    msg:"ok"}                        ← 接单成功

    /** 帖子不存在、不是跑腿类型、或已过期下架 —— 统一成一句，避免暴露"到底为何不存在" */
    public static final ResultCode ERRAND_NOT_FOUND = new ResultCode("ERRAND_NOT_FOUND", 1100, "跑腿单不存在或已结束");

    /** ★ 并发被抢：别人先 UPDATE 成功了。这是 S1「多人抢同一单」的失败出口 */
    public static final ResultCode ERRAND_TAKEN = new ResultCode("ERRAND_TAKEN", 1101, "手慢了，这单已被接走");

    /**
     * 自己已经接过这一单（重复提交）。
     *
     * 【为什么要和 ERRAND_TAKEN 分开】两者在数据库层都是"条件更新影响 0 行"，
     * 但用户感受完全不同：报"被别人抢走"会让他以为自己丢了单，其实他早就接到了。
     * 所以命中 0 行之后必须**再读一次**记录，才能判断到底是哪一种 —— 这就是 CAS 的代价。
     */
    public static final ResultCode ERRAND_ALREADY_TAKEN = new ResultCode("ERRAND_ALREADY_TAKEN", 1102, "你已经接过这单了");

    /** 接自己发的单 —— 现有限制，禁止刷单 */
    public static final ResultCode ERRAND_TAKE_SELF = new ResultCode("ERRAND_TAKE_SELF", 1103, "不能接自己发布的单");

    /** 履约状态不允许当前操作，例如已取件还想取消、已完成还想标记送达 */
    public static final ResultCode ERRAND_STATUS_ILLEGAL = new ResultCode("ERRAND_STATUS_ILLEGAL", 1104, "当前状态不允许该操作");

    /** 不是这单的接单人却想操作（越权） */
    public static final ResultCode ERRAND_NOT_TAKER = new ResultCode("ERRAND_NOT_TAKER", 1105, "你不是该单的接单人");

    /** 不是发布者却想确认完成 / 撤回 */
    public static final ResultCode ERRAND_NOT_OWNER = new ResultCode("ERRAND_NOT_OWNER", 1106, "只有发布者可以操作");

    /** 每单只能取消一次（设计决策 2：防止用取消来刷单占坑） */
    public static final ResultCode ERRAND_CANCEL_LIMIT = new ResultCode("ERRAND_CANCEL_LIMIT", 1107, "每单只能取消一次");

    /** 手上进行中的单已达上限（阶段四才启用，码先占位） */
    public static final ResultCode ERRAND_TAKING_LIMIT = new ResultCode("ERRAND_TAKING_LIMIT", 1108, "手上的单太多了，先完成再接新的");

    /** 常量全集：声明在常量之后，保证静态初始化顺序安全 */
    // 【写法】VALUES 必须声明在所有常量之后（不能提到最前面）
    // 【为什么】静态字段按**声明顺序**初始化。如果 VALUES 写在上面的常量之前，
    //          轮到它初始化时，OK / PARAM_ERROR 这些字段还是 null，
    //          数组里会装进一堆 null —— 编译能过，运行时 values() 直接空指针。
    //          这是 Java 静态初始化的经典坑。
    private static final ResultCode[] VALUES = {
            OK, PARAM_ERROR, UNAUTHORIZED, FORBIDDEN,
            NOT_FOUND, METHOD_NOT_ALLOWED, RATE_LIMIT, SERVER_ERROR,
            BIZ_ERROR, WX_LOGIN_FAIL, TOKEN_EXPIRED, TOKEN_INVALID,
            USER_BANNED, USER_MUTED, USER_NOT_VERIFIED, STUDENT_NO_EXISTS,
            VERIFY_PENDING, VERIFY_ALREADY_PASS, POST_NOT_FOUND, POST_NOT_OWNER,
            POST_STATUS_ILLEGAL, ORDER_NOT_FOUND, ORDER_STATUS_CHANGED, ORDER_NOT_PARTICIPANT,
            ORDER_ACTION_FORBIDDEN, SESSION_NOT_FOUND, CHAT_SELF, FILE_TYPE_NOT_ALLOWED,
            FILE_TOO_LARGE, FILE_UPLOAD_FAIL, FILE_EMPTY, GROUPBUY_FULL,
            GROUPBUY_NOT_JOINED, GROUPBUY_OWNER_CANNOT_QUIT, GROUPBUY_ENDED, GROUPBUY_ALREADY_JOINED,
            REPORT_DUPLICATE, REPORT_TARGET_INVALID, REVIEW_DUPLICATE, REVIEW_NOT_ALLOWED,
            ADMIN_LOGIN_FAIL, ADMIN_DISABLED, BIZ_TYPE_NOT_SUPPORTED, CREDIT_NOT_ENOUGH,
            CONTENT_ILLEGAL, POST_EXPIRED, POST_CLOSED, OPERATION_TOO_FREQUENT,
            ERRAND_NOT_FOUND, ERRAND_TAKEN, ERRAND_ALREADY_TAKEN, ERRAND_TAKE_SELF,
            ERRAND_STATUS_ILLEGAL, ERRAND_NOT_TAKER, ERRAND_NOT_OWNER, ERRAND_CANCEL_LIMIT,
            ERRAND_TAKING_LIMIT,
    };

    /** 常量名，等价于 enum 的 name */
    private final String name;

    /** 业务码，前端按它做分支（0 成功，其余失败） */
    private final int code;

    /** 默认提示文案，会被 BizException 的 msg 覆盖 */
    private final String msg;

    // 【写法】私有构造器
    // 【为什么】只允许类内部创建 48 个固定实例，外部无法 new 出第 49 种错误码，
    //          保证"错误码集合"是封闭的 —— 这也是模拟枚举的核心约束
    private ResultCode(String name, int code, String msg) {
        this.name = name;
        this.code = code;
        this.msg = msg;
    }

    /* ==================== 以下为 enum 自带能力的等价实现 ==================== */

    /** 等价于 enum 的 name() */
    public String name() {
        return name;
    }

    /**
     * 等价于 enum 的 ordinal()：在常量表中的位置
     *
     * 【为什么用 == 比较】这些常量是单例（全应用只有 48 个实例），
     * 用 == 比 equals 更快，也不会因为 equals 被重写而出错
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
     * 等价于 enum 的 values()：返回副本，防止外部改坏内部数组
     *
     * 【为什么必须 clone】如果直接返回 VALUES，调用方一句 arr[0] = null
     * 就把内部数组改坏了，之后所有 values() 调用都会拿到脏数据。
     * 真枚举的 values() 也是每次返回新数组，这里保持同样语义。
     */
    public static ResultCode[] values() {
        return VALUES.clone();
    }

    /**
     * 等价于 enum 的 valueOf(String)
     *
     * 【为什么找不到要抛异常】与真枚举的行为保持一致：
     * Enum.valueOf 找不到时抛 IllegalArgumentException，
     * 这里照做，避免调用方以为"返回 null 就是没找到"而漏判。
     */
    public static ResultCode valueOf(String name) {
        for (ResultCode v : VALUES) {
            if (v.name.equals(name)) {
                return v;
            }
        }
        throw new IllegalArgumentException("No enum constant ResultCode." + name);
    }

    /** 等价于 enum 的默认 toString() */
    @Override
    public String toString() {
        return this.name;
    }
}
