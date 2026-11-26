package com.gdut.xiaojii.common.constant;

/**
 * Redis key 统一收口：所有 key 只从这里生成，业务代码禁止自己拼字符串。
 *
 * 【为什么必须集中管理】
 * key 一旦散落在各处，就会出现"登录模块写 login:token:1、拦截器读 login:token:1 （多了个空格）"
 * 这种永远对不上的问题。而且它不报错、不抛异常，表现为"登录成功但一刷新就掉线"，
 * 极难定位。集中到一个类，改格式只改一处，调用方自然跟着变。
 *
 * 【命名规范】
 * 统一用 业务:子类:标识 三段，冒号分层。
 * 这样做的好处：redis-cli 里可以按前缀扫（SCAN 0 MATCH login:* ），
 * 也能一眼看出这个 key 属于哪个模块、存的是什么。
 *
 * 【为什么常量后面都带冒号】
 * 常量只到"前缀"这一层（如 login:token:），后面接 ID 由方法拼。
 * 这样调用方不可能漏掉冒号，也就不会出现 login:token1 这种畸形 key。
 *
 * 【本类的用法纪律】
 * ⛔ 业务代码不要直接引用常量拼 key，一律调用下面的静态方法。
 * 常量声明为 public 只是为了让同一个包或测试能引用，正常业务不该碰。
 *
 * @author 校集后端
 */
public final class RedisKey {

    /**
     * 私有构造：本类是纯工具类，不允许 new。
     * 不加这个的话，编译器会给一个隐式 public 构造器，别人 new RedisKey() 也能通过，
     * 虽然没实际危害，但语义上等于"允许实例化一个没有状态的对象"，容易误导后来的人。
     */
    private RedisKey() {
    }

    /* ==================== 一、登录态 ==================== */

    /**
     * 小程序用户当前有效令牌。
     * value 必须是 token 原文字符串（用 StringRedisTemplate 写，不能用 JSON 序列化包装）。
     * TTL 与 JWT 有效期一致；采用单端登录：新登录覆盖旧值，等于把旧设备踢下线。
     */
    public static final String LOGIN_TOKEN = "login:token:";

    /**
     * 管理端当前有效令牌。
     * 与用户令牌分开前缀，避免"用户 token 被当成管理员 token"这类越权。
     */
    public static final String LOGIN_ADMIN_TOKEN = "login:admin:";

    /* ==================== 二、限流与幂等 ==================== */

    /**
     * 接口限流：滑动窗口，用 ZSET 存请求时间戳。
     * 完整形态是 rate:{维度}:{userId}:{接口标识}，维度可以是 ip / user / 全局。
     */
    public static final String RATE_LIMIT = "rate:";

    /**
     * 幂等标记：用 SETNX 抢占，抢到才执行业务。
     * 完整形态是 idem:{接口}:{userId}:{业务ID}，防重复提交。
     */
    public static final String IDEMPOTENT = "idem:";

    /* ==================== 三、定时任务锁 ==================== */

    /**
     * 定时任务分布式锁。
     * 多实例部署时，同一个 job 会被每个实例都触发一次；用这把锁保证同一时刻只有一个实例在跑。
     */
    public static final String JOB_LOCK = "job:lock:";

    /* ==================== 四、用户相关缓存 ==================== */

    /** 用户资料快照，缓解每次请求都查库。TTL 5 分钟，宁可短暂不一致也要挡住高频查询 */
    public static final String USER_SNAPSHOT = "user:snap:";

    /**
     * 用户账号状态缓存（1 正常 / 2 禁言 / 3 封禁），TTL 5 分钟。
     * ⛔ 封禁用户时必须【立即删除】这个 key，否则最长 5 分钟内他还能继续用旧状态访问。
     */
    public static final String USER_STATUS = "user:status:";

    /* ==================== 五、业务缓存 ==================== */

    /** 拼单参与人数缓存，防止详情页高频 COUNT 打库。非关键数据，丢了重算即可 */
    public static final String GROUPBUY_COUNT = "groupbuy:count:";

    /* ==================== 生成方法（业务代码只用这些） ==================== */

    /** 小程序用户令牌 key：login:token:{userId} */
    public static String loginToken(Long userId) {
        return LOGIN_TOKEN + userId;
    }

    /** 管理端令牌 key：login:admin:{adminId} */
    public static String loginAdminToken(Long adminId) {
        return LOGIN_ADMIN_TOKEN + adminId;
    }

    /** 用户状态缓存 key：user:status:{userId} */
    public static String userStatus(Long userId) {
        return USER_STATUS + userId;
    }

    /** 用户资料快照 key：user:snap:{userId} */
    public static String userSnapshot(Long userId) {
        return USER_SNAPSHOT + userId;
    }

    /** 拼单人数缓存 key：groupbuy:count:{postId} */
    public static String groupbuyCount(Long postId) {
        return GROUPBUY_COUNT + postId;
    }

    /**
     * 限流 key：rate:{dimension}:{userId}:{api}。
     * 三个维度都是变量，所以必须用方法拼，不能只靠常量。
     */
    public static String rateLimit(String dimension, Long userId, String api) {
        return RATE_LIMIT + dimension + ":" + userId + ":" + api;
    }

    /** 幂等 key：idem:{api}:{userId}:{bizId} */
    public static String idempotent(String api, Long userId, String bizId) {
        return IDEMPOTENT + api + ":" + userId + ":" + bizId;
    }

    /** 任务锁 key：job:lock:{jobName} */
    public static String jobLock(String jobName) {
        return JOB_LOCK + jobName;
    }
}
