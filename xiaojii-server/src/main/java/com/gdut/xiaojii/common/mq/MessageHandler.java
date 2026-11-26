package com.gdut.xiaojii.common.mq;

/**
 * 消息处理回调。
 *
 * 【一句话】这是"来了一条消息，你打算怎么处理"的那个方法，由业务方实现。
 *
 * 【为什么方法签名要 throws Exception】
 * 消费方抛异常 = 这条消息**没处理成功**，框架会把消息留在 pending 里等重投。
 * 如果吞掉异常返回，框架就会 ACK 掉它 —— 消息看着"消费成功"，
 * 实际上什么都没做，而且永远不会再投递。这是最隐蔽的一类消息丢失。
 *
 * 模拟数据：
 *   正常：handle 里 insert 通知表成功 → 返回 → 框架 XACK → 消息出队
 *   异常：数据库挂了 → 抛 SQLException → 框架不 ACK → 60 秒后重投
 *
 * @author 校集后端
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * @param messageId 消息 ID（Redis Stream 的 recordId，如 "1731600000000-0"）
     *                  业务侧做幂等时用它当唯一键
     * @param payload   消息体（本项目统一是 JSON 字符串）
     * @throws Exception 抛异常表示失败，框架不会 ACK，等待重投
     */
    void handle(String messageId, String payload) throws Exception;
}
