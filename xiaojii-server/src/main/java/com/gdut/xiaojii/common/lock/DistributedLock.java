package com.gdut.xiaojii.common.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁的统一入口（Redisson RLock）。
 *
 * 【一句话】想"同一时刻只有一个线程/一台机器能干这件事"，就调这里包一下。
 *
 * ============================================================
 * 【★★ 先想清楚：什么时候该用锁，什么时候不该】
 * ============================================================
 * 这是面试第一个要答对的问题，答错比不会更扣分：
 *
 * | 场景 | 该用什么 | 为什么 |
 * |---|---|---|
 * | 判断只依赖**一行**的当前值（拼单名额、审核状态） | **条件更新 CAS**，不用锁 | 判断和写入是同一条 SQL，中间没有窗口；加锁反而慢 |
 * | 需要**跨多步、跨表、或调外部系统**才能判断 | **分布式锁** | 一条 SQL 表达不了，必须有互斥 |
 * | 同一份**外部资源**（教务会话、第三方接口配额） | **分布式锁** | 数据库管不到外部系统 |
 * | 定时任务（每个实例都会触发一次） | **分布式锁** | 天然需要"只有一个实例执行" |
 *
 * ⛔ 最常见的错误说法："多台服务器就要用分布式锁"。
 *   拼单名额在多实例下用 `WHERE current_count < target_count` 依然正确，
 *   加锁纯属降低吞吐。**锁不是扩容的默认动作，是"一条 SQL 表达不了"时的补偿手段。**
 *
 * ============================================================
 * 【本项目用它的两个地方，以及为什么非它不可】
 * ============================================================
 * ① 教务课表缓存重建（EduDataServiceImpl）：保护的是**外部教务系统**。
 *    用户连点 5 次刷新，5 个线程共用一个 JSESSIONID 并发打教务，
 *    轻则返回空页、重则封会话 —— 这是数据库约束完全管不到的地方。
 * ② 拼单到期定时任务（GroupbuyExpireJob）：每个实例都会触发，
 *    必须保证同一时刻只有一台在跑，否则同一批单被关两次、通知发两遍。
 *
 * ============================================================
 * 【★ 看门狗（watchdog）：Redisson 相对手写 SETNX 的核心优势】
 * ============================================================
 * 手写 Redis 锁必须自己定一个 TTL，然后陷入两难：
 *   · TTL 估短了 → 任务还没跑完锁就过期，另一个实例进来同时跑（锁形同虚设）
 *   · TTL 估长了 → 进程崩了之后，这个锁要等很久才自动释放，期间的请求全被挡
 * Redisson 的解法：**不指定 leaseTime（传 -1）时启用看门狗**，
 * 后台线程每 10 秒检查一次，只要业务线程还持有锁就续期到 30 秒。
 * 进程一挂，续期线程跟着没了，30 秒后锁自动释放。
 * ⇒ 既不会"任务没跑完就丢锁"，也不会"挂了之后锁永久占着"。
 *
 * ⚠️ 看门狗只在你**不传 leaseTime** 时生效。一旦显式传了 leaseTime（比如 30 秒），
 *    Redisson 就认为"你自己管得好"，不再续期 —— 这是最常见的踩坑点：
 *    写成了 `tryLock(0, 30, SECONDS)` 然后抱怨"任务跑 40 秒后并发进来了"。
 *
 * ============================================================
 * 【★ Redlock：什么时候需要，本项目为什么不用】
 * ============================================================
 * Redlock 是"向 N 个独立 Redis 主节点投票，超过半数拿到才算成功"的算法，
 * 用来解决"主从切换时锁丢失"：主库刚写入锁就宕机，从库还没同步，另一个实例也能抢到。
 * ⚠️ 代价是：要多部署 5 个独立主节点、每次加锁要 N 次网络往返、性能下降明显。
 * ⇒ 本项目单 Redis 实例，用不上；真要上多实例时，
 *   第一步是"主从 + 哨兵"，Redlock 只在"锁丢了会造成资金损失"的场景才值得。
 *   **诚实答法**：锁丢了最多是重复执行一次任务，用幂等兜住，不值得为它上 Redlock。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLock {

    /**
     * 不指定租期：启用看门狗自动续期。
     * 适合"任务耗时不确定"的场景（定时任务批处理、外部接口调用）。
     */
    public static final long LEASE_WITH_WATCHDOG = -1L;

    private final RedissonClient redissonClient;

    /**
     * 这一段是"抢到锁才干活，抢不到就算了"。
     *
     * 类比借钥匙：去前台借 88 号房间的钥匙，最多等 waitMs；拿到才进去办事，出来一定还钥匙。
     *
     * 参数 key：锁的标识，如 lock:groupbuy:join:88 —— 带业务 id 分段，不同 id 互不阻塞
     * 参数 waitMs：最多等多久（0 = 不等，抢不到立刻返回）
     * 参数 leaseMs：钥匙有效期；传 LEASE_WITH_WATCHDOG（-1）启用看门狗自动续期
     * 参数 action：拿到锁之后才执行的那段代码
     * 返回：true = 抢到并执行完；false = 没抢到（action 一行都没执行）
     *
     * 【finally 为什么要判断两次】
     *   locked 为 false：tryLock 抛过异常，此时 unlock 会抛 IllegalMonitorStateException 盖掉真错误；
     *   isHeldByCurrentThread 为 false：锁已过期或已被别人拿到，不能删别人的锁。
     */
    public boolean tryRun(String key, long waitMs, long leaseMs, Runnable action) {
        RLock lock = redissonClient.getLock(key);   // 拿到锁对象，还没上锁
        boolean locked = false;                     // 先假设没抢到，finally 靠它判断要不要 unlock
        try {
            locked = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);   // 抢锁：最多等 waitMs
            if (!locked) {
                log.debug("[分布式锁] 未抢到，跳过 key={}", key);
                return false;
            }
            action.run();                           // 抢到了才执行传进来的活
            return true;
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉不恢复，上层就永远不知道"该停了"，服务关不掉
            Thread.currentThread().interrupt();
            log.warn("[分布式锁] 等待锁时被中断 key={}", key);
            return false;
        } finally {
            // 两个判断缺一不可：locked=false 时 unlock 会盖掉真错误；锁已过期/是别人的则不能删
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 这一段是"抢到锁就执行并返回结果，抢不到就按备用方案返回"。
     *
     * 【它和 tryRun 的区别】tryRun 只回答"做没做"，这个方法还要把结果带出来。
     * 教务缓存重建就必须用它：抢不到锁时不能"什么都不返回"，
     * 而要返回上一次的旧缓存（前端标个"数据可能不是最新"）。
     *
     * 【完整预演｜教务课表场景】
     *   key="lock:edu:timetable:12"，waitMs=200，leaseMs=30000
     *   线程 A：抢到锁 → 调教务（约 800ms）→ 写缓存 → 返回新课表
     *   线程 B：等 200ms 没抢到 → 走 onBusy → 返回 30 分钟前的旧课表（stale=true）
     *   结果：教务只被打 1 次，用户最多看到略旧的课表，而不是转圈 3 秒或报错
     *
     * @param onBusy 没抢到锁时的备用返回（不能返回 null，否则上游会 NPE）
     */
    public <T> T tryCall(String key, long waitMs, long leaseMs,
                         Supplier<T> action, Supplier<T> onBusy) {
        RLock lock = redissonClient.getLock(key);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                log.debug("[分布式锁] 未抢到，走备用逻辑 key={}", key);
                return onBusy.get();
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[分布式锁] 等待锁时被中断 key={}", key);
            return onBusy.get();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
