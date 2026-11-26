package com.gdut.xiaojii.common.aspect;

import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.annotation.RateLimit;
import com.gdut.xiaojii.common.constant.RedisKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * 限流切面（自研）。
 *
 * 【一句话】在标记了 @RateLimit 的方法执行之前，先问 Redis 一句"这个人这个接口还用剩几次"，
 * 用完了就直接拦下来，方法体根本不会被执行。
 *
 * 【为什么放在切面而不是每个 Service 里写一遍】
 * 限流和业务逻辑没有任何关系，但它要在发帖、私信、举报等多个接口上各写一段。
 * 写成切面以后：业务代码一行都不用改，只加一个注解；
 * 将来要把"滑动窗口"换成"令牌桶"，也只改这一个类。
 *
 * 【为什么是滑动窗口，不是固定窗口】
 * 固定窗口（INCR + EXPIRE）有明显的临界点漏洞：
 *   限制"1 分钟 60 条"，用户在 10:00:59 连发 60 条、10:01:01 再连发 60 条，
 *   两秒内实际通过 120 条 —— 因为两个窗口各算各的。
 * 滑动窗口记录"每一次请求的时间戳"，判断的是"最近 60 秒内有多少条"，
 * 没有窗口边界，也就没有这个漏洞。
 * 【代价】每个用户一个 ZSET，存 N 个时间戳，内存占用比一个计数器大；
 *   但本项目单用户额度最多 60，可忽略。
 *
 * 【数据结构为什么是 ZSET】
 * 要按"时间"排序、按"时间范围"删除，还要能数总数：
 *   · ZADD          写一次请求（score = 时间戳）
 *   · ZREMRANGEBYSCORE  删掉滑出窗口的老记录
 *   · ZCARD         数窗口内还剩几条
 * 有序集合天生同时满足这三个操作，且都是 O(log N)。
 *
 * @author 校集后端
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** 限流维度：按用户。将来要按 IP 限流，加一个 DIM_IP 即可 */
    // 【为什么先只做"按用户"】本校场景下用户都经过校园认证，按人限流最公平；
    //   按 IP 会误伤同一宿舍共用一个 NAT 出口的同学
    private static final String DIM_USER = "user";

    /** 未登录用户的占位 ID */
    // 【为什么用 0 而不是直接放行】理论上这些接口都要求登录（拦截器会拦），
    //   但万一某个接口配置成"可选登录"，匿名流量也得有个计数桶，否则就是无限额
    private static final Long ANON_USER_ID = 0L;

    // StringRedisTemplate 而不是 RedisTemplate<String,Object>：
    // 后者会把值序列化成带引号的 JSON，而 ZSET 的 member 我们需要原样存"时间戳:UUID"
    private final StringRedisTemplate redis;

    /**
     * 这一段是"进门先看额度"，不够就直接把人挡在门外。
     *
     * 【为什么用 @Before 而不是 @Around】
     * @Before 在方法执行前跑，抛异常就阻断，方法体不会被执行 —— 正是限流要的效果。
     * @Around 更强（能改返回值、能计时），但这里用不上；用更弱的通知类型，
     * 别人读代码时一眼就知道"它不会改方法结果"。
     *
     * 【参数 point】切点信息，本次只用来打日志（哪个方法被限流了）。
     * 【参数 rateLimit】注解实例，Spring AOP 自动注入，能直接读 limit / windowSeconds / message。
     *
     * 【完整预演】发帖接口 @RateLimit(api="post:create", limit=5, windowSeconds=3600)
     *   用户 7 在 10:00 到 10:05 之间点了 5 次发帖 → ZSET 里有 5 个时间戳
     *   第 6 次（10:06）进来：
     *     ZREMRANGEBYSCORE 删掉 09:06 之前的（没有） → ZCARD = 5
     *     5 >= 5 → 抛 BizException(RATE_LIMIT)
     *     最早那条是 10:00，窗口 1 小时 → 还要等 54 分钟
     *     前端收到 {code:429, msg:"操作太频繁，请 54 分钟后再试"}
     *   第 7 次（11:01）进来：
     *     ZREMRANGEBYSCORE 删掉 10:01 之前的 → 假设删掉 4 条 → ZCARD = 1
     *     1 < 5 → 放行，并写入第 7 次的时间戳
     */
    @Before("@annotation(rateLimit)")
    public void check(JoinPoint point, RateLimit rateLimit) {
        // 第 1 步：确定"算谁的额度"。
        // 这里的 userId 是 AuthInterceptor 在请求进入时写进 ThreadLocal 的，
        // 拦截器的 preHandle 先于本切面执行，所以这里一定能读到（除非接口允许匿名）。
        Long userId = UserContext.getUserId();
        Long bucket = userId == null ? ANON_USER_ID : userId;

        // 第 2 步：拼 key。形如 rate:user:7:post:create
        // 用 RedisKey 的方法拼，不自己拼字符串 —— 少一个冒号就会和别的 key 撞车
        String key = RedisKey.rateLimit(DIM_USER, bucket, rateLimit.api());

        // 第 3 步：窗口长度换算成毫秒（ZSET 的 score 用毫秒时间戳，精度比秒高）
        long windowMs = rateLimit.windowSeconds() * 1000L;
        long now = System.currentTimeMillis();

        // 第 4 步：把滑出窗口的老记录删掉。
        // 举例：窗口 1 小时，现在是 10:06，则删掉 score <= 09:06 的所有成员。
        // 【为什么必须删】不删的话 ZSET 会无限增长，ZCARD 数出来的也不是"最近一小时"
        redis.opsForZSet().removeRangeByScore(key, 0, now - windowMs);

        // 第 5 步：数一下窗口内还剩几次。
        // 这一步和上一步是两条命令，中间可能插入别的请求 —— 见类尾的"已知竞态"说明
        Long used = redis.opsForZSet().zCard(key);
        if (used != null && used >= rateLimit.limit()) {
            // 第 5.1 步：算"还要等多久"。
            // ZSET 按 score 升序，取第 0 个就是窗口内最早的那次请求；
            // 它 + 窗口长度 - 现在 = 额度恢复的时刻
            long waitSeconds = remainSeconds(key, now, windowMs);

            // 第 5.2 步：拼提示语。
            // 注解里写了 message 就用注解的（业务方可能想说得更具体），否则自动算时间
            String msg = rateLimit.message().isBlank()
                    ? "操作太频繁，请 " + humanize(waitSeconds) + "后再试"
                    : rateLimit.message();

            // 第 5.3 步：记日志再抛。
            // 【为什么打 warn 而不是 info】被限流是异常行为（可能是脚本），要能在日志里筛出来；
            //   但本项目量级下不会刷屏，用 warn 不会淹没别的日志
            log.warn("[限流] 命中 userId={} api={} used={}/{} 需等待={}s",
                    bucket, rateLimit.api(), used, rateLimit.limit(), waitSeconds);
            throw BizException.of(ResultCode.RATE_LIMIT, msg);
        }

        // 第 6 步：放行，并记下这一次。
        // 【member 为什么是"时间戳:UUID"而不是纯时间戳】
        //   ZSET 的 member 必须唯一，同一个 member 再次 ZADD 是"更新 score"而不是"新增一条"。
        //   如果两个请求落在同一毫秒（完全可能：前端并发、脚本连打），
        //   纯时间戳会让第二条覆盖第一条 → 实际只记了 1 次，限流被绕过。
        //   加 UUID 保证每次都不同。
        redis.opsForZSet().add(key, now + ":" + UUID.randomUUID(), now);

        // 第 7 步：给 key 设过期。
        // 【为什么每次请求都要设】窗口是滑动的，key 的寿命也应该跟着往后推；
        //   如果只在第一次设 TTL，用户持续请求时 key 会在 1 小时后突然消失，
        //   额度被重置（虽然影响不大，但语义不干净）。
        // 【代价】每次多一条 EXPIRE 命令，可忽略
        redis.expire(key, Duration.ofMillis(windowMs));
    }

    /**
     * 这一段是"算一下还得等多久"，取窗口内最早那次请求的时间。
     *
     * 返回：剩余毫秒折算成秒，至少 1 秒（避免算出 0 让前端显示"请 0 秒后再试"）
     * 模拟数据：最早一条 score=1731600000000（10:00:00），窗口 3600s，现在 10:06:00
     *   → (1731600000000 + 3600000 - 1731600360000) = 3240000ms → 3240s → 54 分钟
     */
    private long remainSeconds(String key, long now, long windowMs) {
        // rangeWithScores(key, 0, 0) = 按 score 升序取第 1 个成员（只需要最早那条）
        Set<ZSetOperations.TypedTuple<String>> head = redis.opsForZSet().rangeWithScores(key, 0, 0);
        if (head == null || head.isEmpty()) {
            // 理论上不会走到这（刚 ZCARD 说有值），但 Redis 可能在这两步之间被清空
            return 1L;
        }
        Double earliest = head.iterator().next().getScore();
        if (earliest == null) {
            return 1L;
        }
        long remainMs = (long) (earliest + windowMs) - now;
        // 向上取整到秒：(x + 999) / 1000，避免 1500ms 显示成 1 秒却还要再等
        return Math.max(1L, (remainMs + 999) / 1000);
    }

    /**
     * 这一段是"把秒数说成人话"，让提示语符合直觉。
     *
     * 模拟数据：42 → "42 秒"；150 → "3 分钟"；5400 → "2 小时"
     * 【为什么向上取整】说"还要等 53 分钟"比"还要等 53.4 分钟"友好；
     *   而且宁可说得久一点，也不要让用户在第 53 分钟试了还失败
     */
    private String humanize(long seconds) {
        if (seconds >= 3600) {
            return ((seconds + 3599) / 3600) + " 小时";
        }
        if (seconds >= 60) {
            return ((seconds + 59) / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    // ============================================================
    // 已知竞态（诚实记录，面试时可以主动说）
    // ============================================================
    // 第 4~6 步是三条独立命令（ZREMRANGEBYSCORE → ZCARD → ZADD），中间不是原子操作。
    // 极端情况下，同一用户的两个请求同时通过 ZCARD（都读到 4 < 5），
    // 于是两个都放行，实际多放过去 1 次。
    //
    // 影响：额度 5 变成 6，多放一条 —— 对"防刷"这个目的来说可以接受，
    //   因为限流本来就是软约束，不是资金扣减那种必须精确的场景。
    //
    // 要彻底消除，需要把这三步写成一段 Lua 脚本（Redis 单线程执行，天然原子），
    // 代价是脚本难调试、且无法复用 Spring Data 的 API。
    // 本项目选择"允许极小概率多放过一次"，换取代码可读性。
    //
    // ⛔ 如果哪天这个注解被用在"扣钱 / 扣库存"上，必须改成 Lua —— 那时"多放一次"就是资损。
}
