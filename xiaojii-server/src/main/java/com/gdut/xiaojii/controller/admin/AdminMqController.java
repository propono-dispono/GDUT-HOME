package com.gdut.xiaojii.controller.admin;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.common.mq.DeadLetter;
import com.gdut.xiaojii.common.mq.MessageQueue;
import com.gdut.xiaojii.event.NoticeEventDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：消息队列运维。
 *
 * 【一句话】让管理员能看到"队列有没有堵、有哪些消息处理不了、并且能把处理不了的重新塞回去"。
 *
 * 【为什么这块必须有】
 * 消息队列最危险的状态不是"报错"，而是"安静地堆积"：
 * 服务运行正常、接口返回正常、但通知一条都没发出去 —— 因为消费线程挂了或者消费组丢了。
 * 这种故障只有直接读队列才看得出来，所以需要一个能被监控和人工介入的入口。
 *
 * 【为什么放在 /admin 下】队列操作是运维动作，只有管理员能碰。
 * 项目已有的管理端拦截器会自动校验 token，不需要在这里再写一遍鉴权。
 *
 * @author 校集后端
 */
@Tag(name = "98. 消息队列（管理端）")
@Validated
@RestController
@RequestMapping("/admin/mq")
@RequiredArgsConstructor
public class AdminMqController {

    /** 默认看的就是通知主题 —— 目前项目里只有这一个主题 */
    private static final String DEFAULT_TOPIC = NoticeEventDispatcher.TOPIC_NOTICE;
    private static final String DEFAULT_GROUP = NoticeEventDispatcher.GROUP_NOTICE;

    private final MessageQueue messageQueue;

    /**
     * 队列体检。前端可以定时拉这个接口做看板/告警。
     *
     * 模拟返回：
     *   {"length":12, "pending":0, "consumers":1, "dlq":0}  → 一切正常
     *   {"length":1200, "pending":1180, "consumers":1, "dlq":0} → 消费太慢或卡住
     *   {"length":300, "pending":0, "consumers":0, "dlq":0}  → 服务没起/订阅没注册
     *   {"length":12, "pending":0, "consumers":1, "dlq":3}   → 有 3 条处理不了，要人工看
     */
    @Operation(summary = "队列健康快照",
            description = "length=积压总数，pending=未确认数（持续增长说明消费卡住），consumers=消费者数，dlq=死信数（>0 需人工处理）")
    @GetMapping("/status")
    public Result<Map<String, Object>> status(
            @Parameter(description = "主题，默认 notice") @RequestParam(required = false) String topic,
            @Parameter(description = "消费组，默认 notice-group") @RequestParam(required = false) String group) {
        String t = blankToDefault(topic, DEFAULT_TOPIC);
        String g = blankToDefault(group, DEFAULT_GROUP);
        return Result.ok(messageQueue.stats(t, g));
    }

    /**
     * 看死信。最新的在最前面。
     *
     * 模拟数据：返回
     *   [{"id":"1794-1-0","sourceId":"1794-0","error":"Unknown column 'msg_id'",
     *     "payload":"{\"userId\":15,...}","attempts":"4"}]
     *   → 一眼看出是通知表缺列，而不是笼统的"通知发不出去"
     */
    @Operation(summary = "死信列表", description = "重试超过上限仍失败的消息，含原始 payload、失败原因、重试次数")
    @GetMapping("/dead-letters")
    public Result<List<DeadLetter>> deadLetters(
            @Parameter(description = "主题，默认 notice") @RequestParam(required = false) String topic,
            @Parameter(description = "最多返回条数") @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer count) {
        return Result.ok(messageQueue.listDeadLetters(blankToDefault(topic, DEFAULT_TOPIC), count));
    }

    /**
     * 重投一条死信。
     *
     * 【⚠️ 操作前必须确认失败原因已经修好】
     * 重投会产生新的消息 id，如果业务幂等靠消息 id 去重（本项目就是这样），
     * 那么重投会绕过去重 —— 有可能给用户发一条重复通知。
     * 所以正确用法是"修好根因之后再重投"，而不是"点一下试试"。
     *
     * 模拟返回：{"code":0,"msg":"ok","data":true} 表示已重新入队
     *          {"code":0,"msg":"ok","data":false} 表示这条死信不存在（可能已被别人重投）
     */
    @Operation(summary = "重投死信", description = "把一条死信重新放回主队列。⚠️ 会产生新消息 ID，重投前请确认根因已修复")
    @PostMapping("/dead-letters/{id}/replay")
    public Result<Boolean> replay(
            @PathVariable String id,
            @Parameter(description = "主题，默认 notice") @RequestParam(required = false) String topic) {
        boolean ok = messageQueue.replayDeadLetter(blankToDefault(topic, DEFAULT_TOPIC), id);
        return Result.ok(ok);
    }

    private String blankToDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }
}
