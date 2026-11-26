package com.gdut.xiaojii.common.mq;

/**
 * 消息队列抽象接口。
 *
 * 【一句话】业务代码只认这个接口，不认底下是 Redis Stream 还是 RocketMQ。
 *
 * 【为什么先抽接口、再选实现】
 * 这个项目的定位是"能讲清为什么"，所以必须能回答两个问题：
 *   ① 现在为什么用 Redis Stream 而不是 RocketMQ
 *   ② 将来要换 RocketMQ，要改多少地方
 * 接口抽出来以后，②的答案是"只加一个实现类 + 改一行配置，业务代码零改动"。
 * 如果一开始就写死 RedisTemplate，那②的答案是"全项目搜着改"。
 *
 * 【为什么只留 publish，不留 subscribe】
 * 订阅是"启动时注册一次、进程内常驻消费"，和 publish 的调用形态完全不同：
 * publish 是业务线程随手调，subscribe 是框架启动时编排。
 * 混在一个接口里，将来换实现时要同时兼顾两种生命周期，反而更难换。
 * 订阅能力在 RedisStreamMessageQueue 上单独暴露（subscribe）。
 *
 * @author 校集后端
 */
public interface MessageQueue {

    /**
     * 发一条消息（不阻塞业务线程，失败会抛异常由调用方决定怎么办）。
     *
     * 【为什么不在这里吞异常】发消息失败必须让调用方知道：
     * 通知丢了可以接受（业务已经成功），但如果是"扣款成功后发扣款消息"，
     * 吞掉异常就成了资损。要不要忽略，由业务侧决定，不由 MQ 决定。
     *
     * @param topic   主题，如 "notice"（对应 Redis Stream 的 key：mq:notice）
     * @param payload 消息体，本项目统一传 JSON 字符串
     * @return 消息 ID，如 "1731600000000-0"
     */
    String publish(String topic, String payload);

    /**
     * 订阅一个主题（进程内启动一个消费线程，常驻拉取）。
     *
     * @param topic   主题
     * @param group   消费组名。同一个组内多消费者分摊消息，不同组各收一份
     * @param handler 处理回调
     */
    void subscribe(String topic, String group, MessageHandler handler);

    /**
     * 查死信（最新的在前）。
     *
     * 【为什么要有这个能力】死信是"重试 3 次仍失败"的消息 —— 不提供查看，
     * 它就等于一个黑洞：消息进去了、业务没做成、还没人知道。
     * 运维排查的第一步永远是"先看看死信里有什么"。
     *
     * @param topic 主题
     * @param count 最多返回几条（建议 ≤100，太多前端也看不过来）
     */
    java.util.List<DeadLetter> listDeadLetters(String topic, int count);

    /**
     * 重投一条死信（把它重新放回主队列）。
     *
     * 【什么时候用】确认失败原因是"临时故障"（比如数据库当时挂了），现在已经恢复，
     * 就把消息放回去再跑一遍。
     *
     * ⚠️ 【重投是有副作用的】重投会产生**新的消息 id**，
     * 如果业务幂等是"按消息 id 去重"（本项目就是 notice.msg_id），
     * 那么重投会绕过去重 —— 有可能产生一条重复通知。
     * 所以重投前要确认"这条确实没处理成功"，而不是"我怀疑它没成功"。
     *
     * @param topic         主题
     * @param deadLetterId  死信记录的 id（listDeadLetters 返回的那个 id）
     * @return true = 已重新入队并从死信里删除；false = 找不到这条死信
     */
    boolean replayDeadLetter(String topic, String deadLetterId);

    /**
     * 队列健康快照（给管理端/监控用）。
     *
     * 返回字段：
     *   length   —— 主队列消息条数
     *   pending  —— 已投递但未 ACK 的条数（持续增长 = 消费者卡住）
     *   consumers —— 消费组里的消费者个数
     *   dlq      —— 死信条数（>0 需要人工看）
     *
     * 【为什么要暴露这个】"服务正常、接口正常、消息堆着没人消费"是最难发现的一类故障。
     * 只看业务接口是看不出来的，必须直接读队列本身。
     */
    java.util.Map<String, Object> stats(String topic, String group);
}
