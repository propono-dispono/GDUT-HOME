package com.gdut.xiaojii.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.mq.MessageQueue;
import com.gdut.xiaojii.service.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 通知事件的投递器：事务提交后把它塞进消息队列。
 *
 * 【一句话】它就是那个"等业务真正办完了，再去发消息"的人。
 *
 * 【为什么用 @TransactionalEventListener 而不是 @EventListener】
 * @EventListener 在 publishEvent 的那一刻就同步执行 —— 而那一刻业务事务还没提交，
 * 所以消息会先于数据落到队列里（消费者可能读到不存在的数据）。
 * @TransactionalEventListener(phase = AFTER_COMMIT) 会把这个方法挂到事务的"提交完成"回调上，
 * 事务成功提交才执行；事务回滚则**根本不执行**（这正是我们要的）。
 *
 * 【fallbackExecution = true 是干什么的】
 * 如果发布事件的地方根本没有事务（比如某个纯查询方法顺手发了通知），
 * 默认行为是"永远不执行监听器" —— 通知就静默丢了。
 * 设为 true 表示"没有事务时也执行"，避免这种静默丢失。
 *
 * 【⛔ 为什么这里必须把所有异常吞掉】
 * AFTER_COMMIT 阶段抛出的异常，Spring 会继续往上传给业务调用方。
 * 于是用户看到"操作失败"，但数据库其实**已经改了**（事务早提交了）——
 * 这种"假失败"最难排查，用户会重复点，产生重复数据。
 * 所以投递失败只能记日志 + 降级，绝不能让它冒出去。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeEventDispatcher {

    /** 通知主题，对应 Redis Stream 的 key：mq:notice */
    public static final String TOPIC_NOTICE = "notice";

    /** 消费组名。改成别的名字 = 从零重新消费一遍（旧组的进度不共享） */
    public static final String GROUP_NOTICE = "notice-group";

    private final MessageQueue messageQueue;
    private final NoticeService noticeService;
    private final ObjectMapper objectMapper;

    /**
     * 事务提交后投递；没有事务时立即投递。
     *
     * 【完整预演｜正常路径】
     *   拼单满员的事务提交 → 本方法被调用
     *   → 序列化成 {"userId":15,"type":"GROUPBUY","title":"你的拼单成团了",...}
     *   → XADD mq:notice → 返回消息 id 1731600000000-0
     *   → 消费者在 200ms 内落库
     *
     * 【完整预演｜Redis 挂了的降级路径】
     *   XADD 抛连接异常 → catch → 记 error 日志
     *   → 直接调 noticeService.push 同步落库（不带 msgId，因为这条不是从队列来的）
     *   → 通知仍然送达，只是失去了异步的好处
     *   → 用户完全无感
     *
     * 【为什么降级是"同步落库"而不是"重试三次"】
     * Redis 挂了的恢复时间通常是分钟级，立刻重试三次是无效的（三次都在 1 毫秒内失败），
     * 只会让请求变慢。直接落库最稳：通知本来就是写数据库，只是换了个写法。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNoticeEvent(NoticeEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化失败说明事件对象本身有问题（比如加了不支持的类型），只能记日志
            log.error("[通知事件] 序列化失败，通知丢失 userId={} title={}",
                    event.getUserId(), event.getTitle(), e);
            return;
        }

        try {
            String id = messageQueue.publish(TOPIC_NOTICE, json);
            log.debug("[通知事件] 已投递 msgId={} userId={} title={}", id, event.getUserId(), event.getTitle());
        } catch (Exception e) {
            log.error("[通知事件] 投递失败，降级为同步落库 userId={} title={} err={}",
                    event.getUserId(), event.getTitle(), e.getMessage());
            fallbackSync(event);
        }
    }

    /**
     * 这一段是"消息发不出去，那就直接写库"，保证通知不丢。
     *
     * 【为什么降级里还要再 try-catch 一层】
     * 如果 MySQL 也挂了，这里会再抛一次。再往上抛就变成"业务报错但数据已改"的假失败，
     * 比丢一条通知严重得多。所以到这里只记日志，认了。
     *
     * 【为什么 msgId 传 null】这条通知不是从队列消费来的，没有消息 ID。
     * 传 null 意味着它不参与幂等去重 —— 但这里只会执行一次（就在这次请求里），
     * 不存在重复问题。
     */
    private void fallbackSync(NoticeEvent event) {
        try {
            noticeService.push(event.getUserId(), event.getType(), event.getTitle(),
                    event.getContent(), event.getRefType(), event.getRefId(), null);
            log.warn("[通知事件] 降级落库成功 userId={}", event.getUserId());
        } catch (Exception ex) {
            log.error("[通知事件] 降级落库也失败，通知丢失 userId={} title={}",
                    event.getUserId(), event.getTitle(), ex);
        }
    }
}
