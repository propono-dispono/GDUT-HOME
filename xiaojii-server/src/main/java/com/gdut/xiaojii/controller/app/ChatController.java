package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.annotation.RateLimit;
import com.gdut.xiaojii.dto.req.ChatSendReq;
import com.gdut.xiaojii.dto.vo.ChatMessageVO;
import com.gdut.xiaojii.dto.vo.ChatSessionVO;
import com.gdut.xiaojii.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端：私信。
 *
 * @author 校集后端
 */
@Tag(name = "09. 私信")
@Validated
@RestController
@RequestMapping("/app/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "打开会话", description = "不存在则创建；同一对用户 + 同一关联对象只有一条会话")
    @PostMapping("/session")
    public Result<ChatSessionVO> open(
            @Parameter(description = "对方 user_id") @RequestParam Long toUserId,
            @Parameter(description = "关联对象类型 POST/ORDER，可空") @RequestParam(required = false) String refType,
            @Parameter(description = "关联对象 ID，可空") @RequestParam(required = false) Long refId) {
        return Result.ok(chatService.open(UserContext.requireUserId(), toUserId, refType, refId));
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> sessions() {
        return Result.ok(chatService.sessions(UserContext.requireUserId()));
    }

    @Operation(summary = "会话消息", description = "按时间正序，仅参与方可见")
    @GetMapping("/{sessionId}/messages")
    public Result<PageResult<ChatMessageVO>> messages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) Integer size) {
        return Result.ok(chatService.messages(UserContext.requireUserId(), sessionId, page, size));
    }

    @Operation(summary = "发送消息", description = "同一用户 1 分钟内最多发送 60 条，超出返回 429")
    // 【为什么是 60 条/分钟】正常聊天打不出这个量；能打出来的要么是脚本，要么是在吵架刷屏
    // 【代价】极端场景（两人快速对话 + 表情包连发）可能接近阈值，60 给了足够余量
    // 【实际效果】用户 7 在 10:00:00~10:00:59 发了 60 条 → 第 61 条返回 429，消息不入库
    @RateLimit(api = "chat:send", limit = 60, windowSeconds = 60,
            message = "发送太快了，慢一点")
    @PostMapping("/{sessionId}/messages")
    /** 发送 */
    public Result<ChatMessageVO> send(@PathVariable Long sessionId,
                                      @Valid @RequestBody ChatSendReq req) {
        return Result.ok(chatService.send(UserContext.requireUserId(), sessionId, req.getContent()));
    }
}
