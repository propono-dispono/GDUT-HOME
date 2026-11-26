package com.gdut.xiaojii.common.mq;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis Stream 的消息队列实现。
 *
 * 【一句话】生产者往一条 Redis 的"流水账"上追加一条记录，消费者从账本上按顺序取走、处理完打勾
 * （ACK），没打勾的下次还能再拿到 —— 这就是消息队列最核心的三件事。
 *
 * 【为什么这个项目用 Redis Stream，而不是 RocketMQ / RabbitMQ】
 * 三条硬理由，都能在面试里顶住追问：
 *   ① 已有依赖：项目本来就要用 Redis（限流、token 白名单、教务会话都在里面），
 *      引 Stream 零新增中间件；引 RocketMQ 要多跑 NameServer + Broker 两个进程，
 *      本地 2C4G 的机器光 broker 默认就要 1G 堆。
 *   ② 需要的语义它都有：消费组（同组内分摊）、ACK、pending 重投、按 ID 顺序读。
 *      这三个正是"通知不能丢、不能重复太多"所必需的。
 *   ③ 量级匹配：本项目的消息量是"每天几百条通知"，RocketMQ 的十万级吞吐用不上，
 *      用了只能证明"会装中间件"，证明不了"会选型"。
 * 【代价】Redis Stream 没有 RocketMQ 的事务消息、顺序消息、消息轨迹，
 *   持久化强度也弱于 MQ（RDB/AOF 策略决定）。所以这里靠"ACK 前不删 + 死信兜底"来补。
 * 【什么时候会换】如果要做"秒杀扣库存"，需要 RocketMQ 的事务消息或 Kafka 的分区顺序，
 *   那时只需换一个 MessageQueue 实现类，业务代码一行不改 —— 这就是把接口抽出来的原因。
 *
 * 【投递语义：至少一次（at-least-once）】
 * 消费者处理成功才 ACK；没 ACK 的消息留在 pending 里，超时后被重新领取。
 * 代价是**可能重复投递**（处理成功了但 ACK 前进程挂了），
 * 所以消费侧必须自己幂等 —— 本项目用 notice.msg_id 唯一索引兜住。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStreamMessageQueue implements MessageQueue {

    /** Stream key 前缀，最终形态 mq:{topic}，如 mq:notice */
    private static final String KEY_PREFIX = "mq:";
    /** 消息体字段名 */
    private static final String FIELD_PAYLOAD = "payload";
    /** 创建时间的字段名，纯粹方便排查（消费时不用它） */
    private static final String FIELD_TS = "ts";
    /** 死信 stream 后缀 */
    private static final String DLQ_SUFFIX = ":dlq";
    /** 重试次数计数 key 前缀 */
    private static final String RETRY_PREFIX = "mq:retry:";

    private final StringRedisTemplate redis;

    /** 总开关：本地调试想绕过 MQ 时置 false（消息会直接丢，不推荐） */
    @Value("${xiaojii.mq.enabled:true}")
    private boolean enabled;

    /** 一次最多取几条 */
    @Value("${xiaojii.mq.batch-size:10}")
    private int batchSize;

    /** 阻塞等待时长（没有消息时挂起这么久，避免空转打爆 Redis） */
    @Value("${xiaojii.mq.block-ms:2000}")
    private long blockMs;

    /** 失败重试上限，超过就进死信 */
    @Value("${xiaojii.mq.max-retry:3}")
    private int maxRetry;

    /** pending 消息空闲多久算"上一个消费者可能挂了"，可以重新领取 */
    @Value("${xiaojii.mq.pending-idle-ms:60000}")
    private long pendingIdleMs;

    /**
     * 多久检查一次 pending（毫秒）。
     *
     * 【为什么做成可配置】排查线上问题时经常需要临时把它调小
     * （比如调成 1000，几秒内就能看到 pending 重投的效果），
     * 写死成常量就只能改代码重启。默认 30 秒。
     */
    @Value("${xiaojii.mq.recover-interval-ms:30000}")
    private long recoverIntervalMs;

    /** 每次最多捞多少条 pending */
    private static final long RECOVER_BATCH = 50L;

    /** 死信 stream 的保留条数：留证据但不无限增长 */
    private static final int DLQ_MAX_LEN = 2000;

    /** 消费者空闲多久算"已经死了"，可以从消费组名单里划掉（5 分钟） */
    private static final long IDLE_CONSUMER_MS = 300_000L;

    /** 注册表：启动时遍历它，为每个主题起一个消费线程 */
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /** 消费线程池。线程数 = 订阅数，每个主题一个专属线程（保证同主题内尽量有序） */
    private ThreadPoolExecutor executor;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 每个实例的消费者名，用随机后缀区分不同进程/重启 */
    private final String consumerName = "c-" + UUID.randomUUID().toString().substring(0, 8);

    /* ==================== 生产 ==================== */

    /**
     * 这一段是"往账本上记一笔"。
     *
     * 【模拟数据】topic="notice"，payload={"userId":15,"title":"拼单成团"}
     *   → XADD mq:notice * payload <json> ts 1731600000000
     *   → 返回消息 id "1731600000000-0"
     *
     * 【为什么要返回消息 ID】业务侧偶尔需要它做排障（"这条通知到底发出去没有"），
     * 以及消费端做幂等键。拿不到就说明写失败了。
     */
    @Override
    public String publish(String topic, String payload) {
        if (!enabled) {
            log.warn("[MQ] 已关闭（xiaojii.mq.enabled=false），消息被丢弃 topic={}", topic);
            return null;
        }
        Map<String, String> body = new LinkedHashMap<>(4);
        body.put(FIELD_PAYLOAD, payload);
        body.put(FIELD_TS, String.valueOf(System.currentTimeMillis()));

        // 【为什么 add 之后不设过期】Stream 就是持久化账本，设 TTL 会在业务低峰期把还没消费的消息删掉。
        //   清理靠 MAXLEN（见下）而不是过期时间。
        RecordId id = redis.opsForStream().add(
                StreamRecords.newRecord().in(key(topic)).ofMap(body));

        // 【为什么要 MAXLEN ≈ 10000】不裁剪的 Stream 会无限增长，一年后占几百兆。
        //   10000 条 ≈ 本项目一个多月的通知量，超出部分被裁掉的是"早就该消费完的"。
        //   ⚠️ 裁剪只影响"未被消费的旧消息"（按 ID 从旧到新裁），
        //   本项目消费是实时的，被裁到的概率可忽略。
        if (id != null) {
            redis.opsForStream().trim(key(topic), 10000, true);
            log.debug("[MQ] 已投递 topic={} id={}", topic, id.getValue());
        }
        return id == null ? null : id.getValue();
    }

    /* ==================== 订阅 ==================== */

    /**
     * 这一段是"登记一下：这个主题我要消费"。
     *
     * 【为什么只登记、不立刻起线程】subscribe 通常在 Bean 初始化阶段被调用，
     * 那时数据库连接池可能还没就绪。统一等到 ApplicationReadyEvent 再启动，
     * 保证第一条消息进来时依赖都是好的。
     */
    @Override
    public void subscribe(String topic, String group, MessageHandler handler) {
        subscriptions.add(new Subscription(topic, group, handler));
        log.info("[MQ] 已登记订阅 topic={} group={}", topic, group);
    }

    /**
     * 这一段是"应用起来了，开始干活"。
     *
     * 【线程池为什么是固定 1 个线程 × N 个主题】每个主题一个专属线程：
     *   · 同一主题内单线程 → 天然有序（通知这类消息顺序错乱影响不大，但有序更好排查）
     *   · 不同主题互不阻塞 → 通知表挂了不影响别的主题
     * 【为什么不用 @Async】@Async 默认线程池是共享的，消息处理里一旦阻塞（比如 DB 慢），
     *   会把别的 @Async 任务一起拖住。这里要的是"隔离"，所以自己建池。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled || subscriptions.isEmpty()) {
            log.info("[MQ] 未启用或没有订阅，跳过启动");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = new ThreadPoolExecutor(
                subscriptions.size(), subscriptions.size(),
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "mq-consumer");
                    t.setDaemon(true);   // 守护线程：不阻止 JVM 退出
                    return t;
                });
        for (Subscription sub : subscriptions) {
            executor.submit(() -> consumeLoop(sub));
        }
        log.info("[MQ] 已启动 {} 个消费线程，consumerName={}", subscriptions.size(), consumerName);
    }

    /**
     * 这一段是"关掉时把线程收干净"。
     *
     * 【为什么必须停】消费线程在 block 里等消息，不主动停会一直挂着，
     * 本地重启时会出现"两个进程同时消费同一个组"的怪现象。
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdown();
            try {
                // 给正在处理的消息一点时间，别硬杀
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 【为什么还要从消费组注销自己】
        // 实测发现的：重启几次后 `mq_status.py` 里 consumers 变成了 2、3、4……
        // 原因是消费者退出时不会自动从消费组里消失，Redis 只在它**下次连上来**时才更新。
        // 不注销的后果：XINFO 里挂着一堆"幽灵消费者"，排查 pending 时会被误导
        // （以为是它们卡住了，其实是早就退出的进程）。
        // 【为什么这里要 try-catch 掉】如果本消费者还有未 ACK 的消息，
        // Redis 会拒绝删除（NOGROUP / 有 pending）。那种情况保留它反而合理，
        // 因为下次启动还能靠 pending 恢复把消息捞回来。
        for (Subscription sub : subscriptions) {
            try {
                redis.opsForStream().deleteConsumer(key(sub.topic),
                        Consumer.from(sub.group, consumerName));
            } catch (Exception e) {
                log.debug("[MQ] 注销消费者跳过（可能有未确认消息）topic={}：{}", sub.topic, e.getMessage());
            }
        }
        log.info("[MQ] 消费线程已停止 consumerName={}", consumerName);
    }

    /* ==================== 消费循环 ==================== */

    /**
     * 这一段是"一直盯着账本，有新消息就处理"。
     *
     * 【为什么用 while(true) + block 而不是定时轮询】
     * XREADGROUP 支持 BLOCK 参数，没有消息时 Redis 会把连接挂起，不消耗 CPU。
     * 定时轮询（比如每秒一次）会空转出大量无用请求，Redis 的连接数也会被占满。
     *
     * 【循环里 catch 住所有异常的原因】消费者线程一旦抛出去就死了，
     * 之后再也不会消费任何消息 —— 表现为"通知突然全停了"，但服务看起来一切正常。
     * 所以外层必须兜住，睡一秒重试。
     */
    private void consumeLoop(Subscription sub) {
        log.info("[MQ] 消费线程启动 topic={} group={}", sub.topic, sub.group);
        ensureGroup(sub);
        // 启动时顺手清理被强杀的进程留下的幽灵消费者（见方法注释）
        cleanupIdleConsumers(sub);

        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(sub.group, consumerName),
                        StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(blockMs)),
                        StreamOffset.create(key(sub.topic), ReadOffset.lastConsumed()));

                // 【★ 为什么改成"每轮都检查、按时间间隔触发"】
                // 初版写的是"这一轮没读到新消息才 recover"，看起来更省 Redis 请求，实际是个 bug：
                // 只要业务持续产生消息（私信高峰、审核批处理），recover 永远轮不到执行，
                // 那些处理失败的消息就一直卡在 pending 里，要等流量彻底停了才被捡回来。
                // 改成与流量无关的时间间隔（30 秒），才是真的"定期抢救"。
                maybeRecoverPending(sub);

                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    dispatch(sub, record);
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                // 【★ 为什么专门识别 NOGROUP】全新环境（Redis 里还没有这个 stream）第一次部署时：
                //   启动阶段 ensureGroup 会失败（Redis 的 XGROUP CREATE 要求 key 已存在），
                //   之后每次 XREADGROUP 都报 NOGROUP，消费线程看起来在跑，其实一条都读不到，
                //   消息全堆在 stream 里没人消费 —— 而且不会报错，日志里只有一行 warn。
                //   这里捕获到就重建组：此时 stream 已被第一条消息创建出来了。
                if (msg.contains("NOGROUP")) {
                    log.warn("[MQ] 消费组不存在，尝试重建 topic={} group={}", sub.topic, sub.group);
                    ensureGroup(sub);
                } else {
                    log.error("[MQ] 消费循环异常 topic={}，1 秒后重试：{}", sub.topic, msg);
                }
                sleepQuietly(1000);
            }
        }
        log.info("[MQ] 消费线程退出 topic={}", sub.topic);
    }

    /**
     * 这一段是"处理一条消息，成功了才打勾"。
     *
     * 【完整预演 1｜正常】
     *   消息 id=1731-0，payload 是一条通知 → handler 插入 notice 表成功
     *   → XACK → 消息退出 pending → 下次再也读不到它
     *
     * 【完整预演 2｜处理失败】
     *   handler 抛异常（数据库挂了）→ 不 ACK → 消息留在 pending
     *   → 下次 recoverPending 发现它已经空闲超过 60 秒 → 重新领取 → 再试
     *   → 重试次数记在 mq:retry:{topic}:{id}，超过 3 次 → 进死信 + ACK
     *
     * 【为什么重试要在 MQ 层做，而不是"失败就立刻重试三次"】
     * 立刻重试遇到"数据库挂了"这种故障是无效的（三次都在 1 毫秒内失败），
     * 反而把故障放大成雪崩。让它留在 pending、等一个周期后再来，才是正确的退避。
     */
    private void dispatch(Subscription sub, MapRecord<String, Object, Object> record) {
        String id = record.getId().getValue();
        String payload = record.getValue().get(FIELD_PAYLOAD) == null
                ? null : String.valueOf(record.getValue().get(FIELD_PAYLOAD));

        try {
            sub.handler.handle(id, payload);
            ack(sub, record.getId());
            clearRetry(sub.topic, id);
        } catch (Exception e) {
            int attempts = incrRetry(sub.topic, id);
            if (attempts > maxRetry) {
                // 【为什么超过上限要 ACK 掉】不 ACK 它会永远留在 pending，
                // 每轮都重试一次，把消费线程的时间全耗在一条永远失败的消息上。
                // 挪到死信流里留证据，比"卡住整个队列"好。
                deadLetter(sub, record, payload, e, attempts);
                ack(sub, record.getId());
                clearRetry(sub.topic, id);
            } else {
                // 不 ACK：留在 pending，等 recoverPending 重投
                log.warn("[MQ] 处理失败（第 {} 次），等重投 topic={} id={} err={}",
                        attempts, sub.topic, id, e.getMessage());
            }
        }
    }

    /**
     * 这一段是"把上次没来得及打勾的消息重新捡回来"。
     *
     * 【它救的是什么场景】进程在处理到一半时被 kill（或重启、或 OOM），
     * 消息已经读出来了但没 ACK。没有这一步，这些消息会永远躺在 pending 里没人管，
     * 表现就是"通知偶尔丢一条，且永远不补"。
     *
     * 【为什么用 claim 而不是重新读】
     * 重新 XREADGROUP 是读不到 pending 里的消息的（它们已经被分配给某个消费者了）。
     * XCLAIM 的作用就是"把这条消息的所有权转移给我"，转过来才能再处理。
     *
     * 【为什么只捡空闲超过 60 秒的】正在被别的消费者处理的消息（空闲时间很短）不能抢，
     * 否则两个消费者会同时处理同一条 —— 虽然幂等能兜住，但白干一遍。
     */
    /**
     * 按时间间隔触发 pending 恢复（避免每轮都查 Redis）。
     *
     * 模拟数据：间隔=30000
     *   10:00:00 第一次进入 → lastRecoverAt=0，差值巨大 → 执行恢复，记下时间
     *   10:00:05 再来 → 只过了 5 秒 < 30 秒 → 跳过
     *   10:00:31 再来 → 过了 31 秒 → 再执行一次
     */
    private void maybeRecoverPending(Subscription sub) {
        long now = System.currentTimeMillis();
        if (now - sub.lastRecoverAt < recoverIntervalMs) {
            return;
        }
        sub.lastRecoverAt = now;
        recoverPending(sub);
    }

    /**
     * 这一段是"把上次没来得及打勾的消息重新捡回来"。
     *
     * 【它救的是什么场景】进程在处理到一半时被 kill（或重启、或 OOM），
     * 消息已经读出来了但没 ACK。没有这一步，这些消息会永远躺在 pending 里没人管，
     * 表现就是"通知偶尔丢一条，且永远不补"。
     *
     * 【为什么用 claim 而不是重新读】
     * 重新 XREADGROUP 是读不到 pending 里的消息的（它们已经被分配给某个消费者了）。
     * XCLAIM 的作用就是"把这条消息的所有权转移给我"，转过来才能再处理。
     *
     * 【为什么只捡空闲超过 60 秒的】正在被别的消费者处理的消息（空闲时间很短）不能抢，
     * 否则两个消费者会同时处理同一条 —— 虽然幂等能兜住，但白干一遍。
     *
     * ============================================================
     * 【★★ 实测修掉的 bug：必须查「整个消费组」，不能只查自己】
     * ============================================================
     * 初版写的是 `pending(key, Consumer.from(group, consumerName))` —— 只返回**当前消费者**名下的消息。
     * 看起来没问题，实际上漏掉了最需要救的那一批：
     *   consumerName 是随机 UUID（`c-93abf484`），每次重启都会换一个新的。
     *   进程被 kill 时留下的 pending 消息，owner 是**上一轮那个已经不存在的消费者**。
     *   新进程只查自己名下 → 一条都查不到 → 那些消息永远没人处理，而且不报任何错。
     *   ⇒ 必须用 `pending(key, group, ...)` 查全组，才能看到"孤儿消息"。
     * 【证据】验证时用两个消费者名模拟：A 读走消息不 ACK 然后退出，
     *   新起的 B 用旧写法查不到这条，改用全组查询后 XCLAIM 成功接手。
     */
    private void recoverPending(Subscription sub) {
        try {
            // ★ 查整个消费组的 pending（含其他实例、以及本实例上一轮遗留的）
            PendingMessages pending = redis.opsForStream()
                    .pending(key(sub.topic), sub.group, Range.unbounded(), RECOVER_BATCH);
            if (pending == null) {
                return;
            }
            List<RecordId> stale = new ArrayList<>();
            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().toMillis() >= pendingIdleMs) {
                    stale.add(pm.getId());
                }
                if (stale.size() >= batchSize) {
                    break;
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            log.warn("[MQ] 发现 {} 条超时未确认消息，重新领取 topic={} group={}",
                    stale.size(), sub.topic, sub.group);
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                    key(sub.topic), sub.group, consumerName,
                    Duration.ofMillis(pendingIdleMs), stale.toArray(new RecordId[0]));
            if (claimed != null) {
                for (MapRecord<String, Object, Object> record : claimed) {
                    dispatch(sub, record);
                }
            }
        } catch (Exception e) {
            // pending 查询失败不影响主循环，下一轮再来
            log.warn("[MQ] pending 恢复失败 topic={}：{}", sub.topic, e.getMessage());
        }
    }

    /* ==================== 运维：死信查看 / 重投 / 队列统计 ==================== */

    /**
     * 这一段是"把死信里的纸条摊开给人看"，最新的在最前面。
     *
     * 【完整预演】topic=notice，count=20
     *   → XREVRANGE mq:notice:dlq + - COUNT 20
     *   → 返回 3 条：[{id:1794-0, error:"Unknown column 'msg_id'", payload:{...}}, ...]
     *   → 运维一眼看出"是通知表缺列"，而不是笼统的"通知发不出去"
     */
    @Override
    public List<DeadLetter> listDeadLetters(String topic, int count) {
        List<DeadLetter> list = new ArrayList<>();
        int limit = Math.max(1, Math.min(count, 100));
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().reverseRange(
                    key(topic) + DLQ_SUFFIX, Range.unbounded(), Limit.limit().count(limit));
            if (records == null) {
                return list;
            }
            for (MapRecord<String, Object, Object> r : records) {
                Map<Object, Object> v = r.getValue();
                DeadLetter d = new DeadLetter();
                d.setId(r.getId().getValue());
                d.setPayload(str(v.get(FIELD_PAYLOAD)));
                d.setSourceId(str(v.get("sourceId")));
                d.setTopic(str(v.get("topic")));
                d.setAttempts(str(v.get("attempts")));
                d.setError(str(v.get("error")));
                d.setTs(str(v.get("ts")));
                list.add(d);
            }
        } catch (Exception e) {
            // 死信 stream 不存在（从来没失败过）时会走到这里，属于正常情况
            log.debug("[MQ] 读取死信失败（可能还没有死信）topic={}：{}", topic, e.getMessage());
        }
        return list;
    }

    /**
     * 这一段是"把一张纸条重新塞回队列，让人再试一次"。
     *
     * 【完整预演】重投 topic=notice 的死信 1794-1-0
     *   ① XRANGE mq:notice:dlq 1794-1-0 1794-1-0 → 取出 payload
     *   ② publish(topic, payload) → 新消息 id 1795-0-0（注意：id 变了）
     *   ③ XDEL mq:notice:dlq 1794-1-0 → 从死信里删掉，避免重复重投
     *   ④ 消费线程几百毫秒后处理它
     *
     * 【为什么"先入队、再删死信"，顺序不能反】
     *   反过来的话，删完死信、入队失败（Redis 抖动）→ 消息彻底丢了，还查不到。
     *   现在这个顺序最坏情况是"入队成功、删除失败"→ 死信里残留一条，
     *   运维会看到它并可能再点一次重投 → 重复消费。两害相权，宁可重复也不能丢。
     */
    @Override
    public boolean replayDeadLetter(String topic, String deadLetterId) {
        String dlqKey = key(topic) + DLQ_SUFFIX;
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                    .range(dlqKey, Range.closed(deadLetterId, deadLetterId));
            if (records == null || records.isEmpty()) {
                log.warn("[MQ] 死信不存在 topic={} id={}", topic, deadLetterId);
                return false;
            }
            String payload = str(records.get(0).getValue().get(FIELD_PAYLOAD));
            if (payload == null) {
                log.warn("[MQ] 死信缺 payload，无法重投 topic={} id={}", topic, deadLetterId);
                return false;
            }
            String newId = publish(topic, payload);
            if (newId == null) {
                // publish 返回 null 只有一种情况：MQ 总开关被关了
                log.warn("[MQ] 重投被跳过（MQ 已关闭）topic={} id={}", topic, deadLetterId);
                return false;
            }
            redis.opsForStream().delete(dlqKey, deadLetterId);
            log.warn("[MQ] 死信已重投 topic={} 原死信={} 新消息={}", topic, deadLetterId, newId);
            return true;
        } catch (Exception e) {
            log.error("[MQ] 死信重投失败 topic={} id={}：{}", topic, deadLetterId, e.getMessage());
            return false;
        }
    }

    /**
     * 这一段是"给队列做一次体检"，回答四个问题。
     *
     * 判读方法（写在这里省得每次查文档）：
     *   length 大且 pending=0    → 正常，消息都消费完但还没被裁剪
     *   pending 持续增长         → 消费者卡住了 / 处理太慢
     *   consumers = 0 但 length>0 → 没有消费者，服务没启动或订阅没注册
     *   dlq > 0                  → 有消息重试 3 次仍失败，必须人工看
     */
    @Override
    public Map<String, Object> stats(String topic, String group) {
        Map<String, Object> m = new LinkedHashMap<>(8);
        String k = key(topic);
        try {
            Long len = redis.opsForStream().size(k);
            m.put("length", len == null ? 0L : len);
        } catch (Exception e) {
            m.put("length", -1L);
        }
        try {
            PendingMessagesSummary summary = redis.opsForStream().pending(k, group);
            m.put("pending", summary == null ? 0L : summary.getTotalPendingMessages());
        } catch (Exception e) {
            // 消费组还不存在时会抛异常，pending 记 -1 表示"查不到"，与 0 区分开
            m.put("pending", -1L);
        }
        try {
            StreamInfo.XInfoGroups groups = redis.opsForStream().groups(k);
            long consumers = 0;
            if (groups != null) {
                for (StreamInfo.XInfoGroup g : groups) {
                    if (group.equals(g.groupName())) {
                        consumers = g.consumerCount();
                        break;
                    }
                }
            }
            m.put("consumers", consumers);
        } catch (Exception e) {
            m.put("consumers", -1L);
        }
        try {
            Long dlq = redis.opsForStream().size(k + DLQ_SUFFIX);
            m.put("dlq", dlq == null ? 0L : dlq);
        } catch (Exception e) {
            m.put("dlq", 0L);
        }
        return m;
    }

    /** Stream 里取出来的是 Object，统一转成字符串；null 保持 null（不要变成 "null"） */
    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 这一段是"把已经死掉的消费者从名单里划掉"。
     *
     * 【为什么需要它】实测发现：反复用 `taskkill /F` 杀进程后，
     * XINFO 里的 consumers 从 1 变成 2、3……9 —— 每次启动都留一个"幽灵"。
     * 原因是 Redis 不会自动清理消费者，只有它下次连上来才会更新。
     *
     * 【为什么这算个问题】运维看 `consumers=9` 会以为"有 9 个实例在消费我的消息"，
     * 排查 pending 时也会误以为"是那些幽灵卡住了"。数字失真比没有数字更糟。
     *
     * 【删除条件（三个都要满足）】
     *   ① 不是自己  ② 名下没有 pending（有 pending 说明人家还在干活或崩在干活途中，删了消息就没人认领）
     *   ③ 空闲超过 5 分钟（排除正在启动中的新实例）
     *
     * 【正常关闭时会怎样】@PreDestroy 里已经会注销自己，这条清理只是兜底
     * （用于 kill -9 / 断电 / 容器被强删这类没法优雅关闭的情况）。
     */
    private void cleanupIdleConsumers(Subscription sub) {
        try {
            StreamInfo.XInfoConsumers consumers = redis.opsForStream()
                    .consumers(key(sub.topic), sub.group);
            if (consumers == null) {
                return;
            }
            int removed = 0;
            for (StreamInfo.XInfoConsumer c : consumers) {
                if (consumerName.equals(c.consumerName())) {
                    continue;
                }
                if (c.pendingCount() > 0) {
                    continue;
                }
                if (c.idleTimeMs() < IDLE_CONSUMER_MS) {
                    continue;
                }
                redis.opsForStream().deleteConsumer(key(sub.topic),
                        Consumer.from(sub.group, c.consumerName()));
                removed++;
            }
            if (removed > 0) {
                log.info("[MQ] 清理空闲消费者 {} 个 topic={} group={}", removed, sub.topic, sub.group);
            }
        } catch (Exception e) {
            // 组还不存在 / Redis 不支持 XINFO 时走到这里，属于正常情况
            log.debug("[MQ] 清理消费者跳过 topic={}：{}", sub.topic, e.getMessage());
        }
    }

    /* ==================== 基础设施 ==================== */

    /**
     * 这一段是"确保账本和消费组存在"，不存在就建。
     *
     * 【为什么必须 try/catch BUSYGROUP】重复建组会报 "BUSYGROUP Consumer Group name already exists"，
     * 这是正常情况（第二次启动就会遇到），不该当错误。
     *
     * 【XGROUP 的 ReadOffset.from("0") 为什么是 0】表示从最早的消息开始消费 ——
     * 应用启动时如果队列里还有没处理的历史消息，应该补上，而不是跳过。
     * 用 "$" 就是从最新开始、丢掉历史，通知场景不该那样。
     */
    private void ensureGroup(Subscription sub) {
        String k = key(sub.topic);
        try {
            redis.opsForStream().createGroup(k, ReadOffset.from("0"), sub.group);
            log.info("[MQ] 已创建消费组 stream={} group={}", k, sub.group);
        } catch (Exception e) {
            // BUSYGROUP = 组已存在；也包含"stream 还不存在"的情况
            log.debug("[MQ] 消费组已存在或 stream 未创建 stream={} group={}", k, sub.group);
        }
    }

    /** 打勾：告诉 Redis 这条我处理完了，别再投给我 */
    private void ack(Subscription sub, RecordId id) {
        try {
            redis.opsForStream().acknowledge(key(sub.topic), sub.group, id);
        } catch (Exception e) {
            log.warn("[MQ] ACK 失败 topic={} id={}：{}", sub.topic, id.getValue(), e.getMessage());
        }
    }

    /**
     * 这一段是"这条实在处理不了，扔到停尸房"。
     *
     * 【为什么死信要保留原始 payload】排查时必须能看到"到底哪条消息处理失败了"。
     * 只记一句 error 日志，过一周根本还原不出现场。
     */
    private void deadLetter(Subscription sub, MapRecord<String, Object, Object> record,
                            String payload, Exception e, int attempts) {
        try {
            Map<String, String> body = new LinkedHashMap<>(8);
            body.put(FIELD_PAYLOAD, payload == null ? "" : payload);
            body.put("sourceId", record.getId().getValue());
            body.put("topic", sub.topic);
            body.put("attempts", String.valueOf(attempts));
            body.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            body.put("ts", String.valueOf(System.currentTimeMillis()));
            redis.opsForStream().add(StreamRecords.newRecord()
                    .in(key(sub.topic) + DLQ_SUFFIX).ofMap(body));
            // 死信也要裁剪：它同样是一个 Stream，不裁会无限增长（而且死信没人消费，只会越积越多）
            redis.opsForStream().trim(key(sub.topic) + DLQ_SUFFIX, DLQ_MAX_LEN, true);
            log.error("[MQ] 消息进入死信 topic={} id={} attempts={} err={}",
                    sub.topic, record.getId().getValue(), attempts, e.getMessage());
        } catch (Exception ex) {
            log.error("[MQ] 写死信失败（消息已 ACK 掉，只能靠日志追溯）id={}", record.getId().getValue());
        }
    }

    /** 重试计数 +1 并返回当前次数。计数 7 天过期，防止 key 无限堆积 */
    private int incrRetry(String topic, String id) {
        String k = RETRY_PREFIX + topic + ":" + id;
        Long n = redis.opsForValue().increment(k);
        redis.expire(k, Duration.ofDays(7));
        return n == null ? 1 : n.intValue();
    }

    /** 清空retry */
    private void clearRetry(String topic, String id) {
        redis.delete(RETRY_PREFIX + topic + ":" + id);
    }

    private String key(String topic) {
        return KEY_PREFIX + topic;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一条订阅：主题 + 消费组 + 回调 */
    private static final class Subscription {
        private final String topic;
        private final String group;
        private final MessageHandler handler;

        /**
         * 上一次检查 pending 的时间戳。
         *
         * 【为什么需要它】初版只在线程"这一轮没读到新消息"时才去 recover，
         * 结果业务持续有消息时（私信高峰）recover 永远不执行 ——
         * 处理失败的消息就一直卡在 pending 里，直到流量低谷才被捡回来。
         * 改成按时间间隔触发，与流量完全无关。
         *
         * volatile：只在消费线程读写，但为了避免将来别的地方（比如监控接口）读它时看到脏值
         */
        private volatile long lastRecoverAt = 0L;

        private Subscription(String topic, String group, MessageHandler handler) {
            this.topic = topic;
            this.group = group;
            this.handler = handler;
        }
    }
}
