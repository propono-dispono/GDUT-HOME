package com.gdut.xiaojii.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.mq.MessageQueue;
import com.gdut.xiaojii.service.NoticeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通知事件的消费者：从队列里取消息，落进 notice 表。
 *
 * 【一句话】它就是收件员 —— 队列里有便条，它就照着便条往用户收件箱里放一张。
 *
 * 【它跟原来的同步写法差在哪】
 *   改之前：业务方法里直接 noticeMapper.insert，通知表一慢，整个发帖/拼单接口就跟着慢；
 *          通知表一挂，业务事务直接回滚（用户发帖失败，但其实帖子本身没问题）。
 *   改之后：业务只管发事件，消费者在自己的线程里慢慢写；
 *          通知表挂了只会让消息堆在队列里，业务照常成功。
 *
 * 【代价（必须知道）】
 *   通知不再是"实时"的：正常情况下延迟 100~500ms（取决于消费线程多久轮询到），
 *   极端情况（消费者挂了）会延迟到重启后补投。
 *   对"站内通知"这种场景完全可以接受；如果是"支付成功短信"就必须重新评估。
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeEventConsumer {

    private final MessageQueue messageQueue;
    private final NoticeService noticeService;
    private final ObjectMapper objectMapper;

    /**
     * 这一段是"开工前先去队列那儿报个到，说以后通知归我处理"。
     *
     * 【为什么放在 @PostConstruct】订阅必须在应用启动完成前登记好，
     * 这样 RedisStreamMessageQueue 在 ApplicationReadyEvent 时才有东西可启动。
     * 如果放到 @EventListener(ApplicationReadyEvent) 里登记，会晚于 MQ 的启动检查，永远不生效。
     *
     * 【为什么方法引用 this::handle 而不是 lambda 里做业务】
     * 处理逻辑抽成独立方法，单元测试时可以直接调它，不用起整个 MQ。
     */
    @PostConstruct
    public void subscribe() {
        messageQueue.subscribe(NoticeEventDispatcher.TOPIC_NOTICE,
                NoticeEventDispatcher.GROUP_NOTICE, this::handle);
        log.info("[通知消费者] 已订阅 topic={} group={}",
                NoticeEventDispatcher.TOPIC_NOTICE, NoticeEventDispatcher.GROUP_NOTICE);
    }

    /**
     * 这一段是"拆开便条，按上面的内容写进收件箱"。
     *
     * 【完整预演｜正常】
     *   messageId="1731600000000-0"
     *   payload={"userId":15,"type":"GROUPBUY","title":"你的拼单成团了","content":"..." }
     *   → 反序列化 → noticeService.push(..., msgId="1731600000000-0")
     *   → 插入成功 → 方法正常返回 → 框架 ACK → 消息出队
     *
     * 【完整预演｜重复投递】
     *   同一条 messageId 第二次进来 → push 内部撞 uk_msg_id → catch 掉 → 正常返回
     *   → 框架 ACK → 出队。用户只看到一条通知。
     *
     * 【完整预演｜数据格式坏了】
     *   payload 是半截 JSON → readValue 抛 JsonProcessingException
     *   → 向上抛 → 框架不 ACK → 60 秒后重投 → 累计 3 次后进死信 mq:notice:dlq
     *   → 死信里能看到原始 payload 和错误，人工处理
     *
     * 【为什么抛异常而不是 catch 住返回】
     * catch 住 = 告诉框架"处理成功" = ACK = 消息永久消失。
     * 一条格式错误的消息如果就这么消失，将来想补都补不回来。
     * 让它进死信，至少还留着证据。
     */
    private void handle(String messageId, String payload) throws Exception {
        if (payload == null || payload.isBlank()) {
            // 空消息没有重试价值：重试一万次也还是空的。直接返回 = ACK 掉。
            log.warn("[通知消费者] 空消息，直接丢弃 msgId={}", messageId);
            return;
        }

        NoticeEvent event = objectMapper.readValue(payload, NoticeEvent.class);
        noticeService.push(event.getUserId(), event.getType(), event.getTitle(),
                event.getContent(), event.getRefType(), event.getRefId(), messageId);

        log.info("[通知消费者] 已落库 msgId={} userId={} title={}",
                messageId, event.getUserId(), event.getTitle());
    }
}
