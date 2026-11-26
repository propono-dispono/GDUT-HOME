package com.gdut.xiaojii.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gdut.xiaojii.common.constant.RedisKey;
import com.gdut.xiaojii.common.enums.PostStatus;
import com.gdut.xiaojii.common.lock.DistributedLock;
import com.gdut.xiaojii.entity.Notice;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.entity.PostGroupbuy;
import com.gdut.xiaojii.event.NoticeEventPublisher;
import com.gdut.xiaojii.mapper.PostGroupbuyMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 拼单到期关帖任务（每 10 分钟一次）。
 *
 * 【一句话】扫出已过 deadline 还没凑满的拼单，关掉并通知发起人。
 * 没有它，过期拼单会一直挂在信息流里 —— 用户点进去还能参加，但永远不会成团。
 *
 * 【为什么用 @Scheduled 而不是 MQ 延迟消息】Redis Stream 没有原生延迟消息，
 * 为"10 分钟后关帖"搭一套时间轮不值；代价是关帖最多延迟 10 分钟，可接受。
 *
 * 【为什么必须加分布式锁】@Scheduled 是"每个实例各自触发"，不是"集群里选一个"。
 * 两台机器会同时扫到同一批过期单 → 同一单被关两次、发起人收两条通知。
 * 用 Redisson 而不是手写 SETNX：续期、持有者校验、Lua 释放都不用自己操心。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupbuyExpireJob {

    /** 任务锁的 key，来自 RedisKey.JOB_LOCK 前缀 + 任务名 */
    private static final String LOCK_KEY = RedisKey.jobLock("close-expired-groupbuy");

    /** 一次最多处理多少条，防止某个时刻积压太多把一次任务拖成几分钟 */
    private static final int BATCH = 200;

    private final PostGroupbuyMapper groupbuyMapper;
    private final PostMapper postMapper;
    private final NoticeEventPublisher noticePublisher;
    private final DistributedLock distributedLock;

    /**
     * 每 10 分钟扫一次。
     *
     * 【为什么 fixedDelay 不是 fixedRate】fixedRate 在任务耗时超过周期时会立刻补跑 → 任务堆积；
     *   fixedDelay 是"跑完再等 10 分钟"，永远不堆积，代价是实际周期被拉长。
     * 【预演】10:00 抢到锁 → 查出 88/92/95 三条过期 → 逐条条件更新（命中 1 行才算关成功）
     *   → 通知发起人 → 释放锁。10:10 再跑时它们已不是 ONLINE，不会被重复处理。
     */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L)
    public void closeExpiredGroupbuy() {
        // waitMs=0：抢不到说明别的实例在跑，直接退出，等下一轮（排队没有意义）
        // leaseMs=-1：看门狗。扫描 200 条 + 发 200 条通知，耗时不可预测
        boolean ran = distributedLock.tryRun(
                LOCK_KEY, 0, DistributedLock.LEASE_WITH_WATCHDOG, this::doCloseExpired);
        if (!ran) {
            log.debug("[拼单到期] 未抢到任务锁（可能有其他实例在执行），跳过本轮");
        }
    }

    /** 锁内的业务本体：扫过期 → 条件更新关帖 → 通知发起人。抽出来是为了让"加锁范围"一眼可见 */
    private void doCloseExpired() {
        try {
            LocalDateTime now = LocalDateTime.now();
            // 只按 deadline 筛，不筛 status：其中可能已有被手动关掉/下架的，
            //   交给下面的条件更新判断（命中 0 行 = 已经关了，跳过）
            List<PostGroupbuy> expired = groupbuyMapper.selectList(
                    Wrappers.<PostGroupbuy>lambdaQuery()
                            .lt(PostGroupbuy::getDeadline, now)
                            .last("LIMIT " + BATCH));
            if (expired.isEmpty()) {
                return;
            }

            int closed = 0;
            for (PostGroupbuy g : expired) {
                // ★ 条件更新：只关"还在展示中"的。锁只防两个实例同时跑，
                //   防不住"任务与用户请求并发"（用户最后一秒点参加），所以两道都要
                int rows = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                        .eq(Post::getId, g.getPostId())
                        .eq(Post::getStatus, PostStatus.ONLINE.getCode())
                        .set(Post::getStatus, PostStatus.CLOSED.getCode()));
                if (rows == 0) {
                    // 已经关了 / 已下架 / 已被别人处理，跳过
                    continue;
                }
                closed++;

                // 通知发起人。这里要再查一次帖子拿 userId 和标题
                Post p = postMapper.selectById(g.getPostId());
                if (p != null && p.getUserId() != null) {
                    noticePublisher.publish(p.getUserId(), Notice.TYPE_GROUPBUY,
                            "拼单已到期关闭",
                            "《" + p.getTitle() + "》到截止时间还差 "
                                    + (g.getTargetCount() - g.getCurrentCount()) + " 人，已自动关闭",
                            Notice.REF_POST, g.getPostId());
                }
            }
            if (closed > 0) {
                log.info("[拼单到期] 本轮关闭 {} 个（扫描到 {} 个过期）", closed, expired.size());
            }
        } catch (Exception e) {
            // 异常不能抛出去：Spring 会记一条错误，但日志里堆满堆栈、看不出业务含义。
            //   锁仍会在 tryRun 的 finally 里释放
            log.error("[拼单到期] 任务异常：{}", e.getMessage(), e);
        }
    }
}
