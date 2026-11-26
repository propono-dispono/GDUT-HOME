package com.gdut.xiaojii.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 通知事件的发布入口（业务代码只认这个类）。
 *
 * 【一句话】业务里想说"通知某人"，就调这里；它会等到**数据库事务提交之后**才真正塞进队列。
 *
 * 【为什么不能直接 mq.publish(...)】这是本项目最容易踩的一个坑，必须讲清：
 * 业务方法带 @Transactional，如果事务里直接 XADD 发消息，会出现两种事故：
 *   ① 消息先发出去了，事务随后回滚 → 消费者按消息去查数据，查不到 → 用户收到"你拼单成功了"但根本没成功；
 *   ② 消费者比事务提交还快 → 读到旧数据，通知内容错乱（比如"已满员"写成"当前 9 人"）。
 * 正确做法是：事务内只登记事件，提交成功后才投递。Spring 的 @TransactionalEventListener(AFTER_COMMIT)
 * 就是干这个的（见 NoticeEventDispatcher）。
 *
 * 【这叫什么】事务性消息投递（transactional outbox 的轻量版）。
 * 严格版本要一张 outbox 表 + 定时重投，保证"数据库提交了消息一定发出去"；
 * 本项目用"投递失败降级为同步落库"来兜（见 Dispatcher），够用且简单。
 *
 * 【模拟数据】
 *   拼单 88 第 9 个人参加 → 事务内 publish(发起人,"GROUPBUY","有人拼了你的单"...)
 *   → 事务提交 → Dispatcher 收到回调 → XADD mq:notice
 *   → 消费者 200ms 后落库 → 发起人在消息中心看到
 *
 * @author 校集后端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoticeEventPublisher {

    private final ApplicationEventPublisher springPublisher;

    /**
     * 发一条通知事件。
     *
     * 【为什么方法里不 try-catch】这里只是把事件交给 Spring 的事件广播器，
     * 是纯内存操作（同步执行所有监听器），不会失败。
     * 真正的投递在 Dispatcher 里，那里才需要兜异常。
     *
     * @param userId  接收人
     * @param type    通知类型
     * @param title   标题
     * @param content 正文
     * @param refType 跳转类型，可空
     * @param refId   跳转 id，可空
     */
    public void publish(Long userId, String type, String title, String content,
                        String refType, Long refId) {
        if (userId == null) {
            // 接收人为空说明上游数据就有问题（比如帖子没有作者），发出去也没人收
            log.warn("[通知事件] 接收人为空，跳过 type={} title={}", type, title);
            return;
        }
        NoticeEvent event = new NoticeEvent();
        event.setUserId(userId);
        event.setType(type);
        event.setTitle(title);
        event.setContent(content);
        event.setRefType(refType);
        event.setRefId(refId);
        springPublisher.publishEvent(event);
    }
}
