package com.gdut.xiaojii.controller.admin;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.entity.ChatMessage;
import com.gdut.xiaojii.entity.ChatSession;
import com.gdut.xiaojii.mapper.ChatMessageMapper;
import com.gdut.xiaojii.mapper.ChatSessionMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理端：聊天监管（只读）。
 *
 * <p>只读的原因：聊天后端（B4）尚未实现。现在提供会话与消息查询，
 * 主要用于举报取证与违规核实；不提供发消息、封会话等写操作。</p>
 *
 * @author 校集后端
 */
@Tag(name = "99. 聊天监管（管理端·只读）")
@Validated
@RestController
@RequestMapping("/admin/chat")
@RequiredArgsConstructor
public class AdminChatController {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Operation(summary = "会话列表（只读）", description = "按最后消息时间倒序")
    @GetMapping("/list")
    /** 列表 */
    public Result<PageResult<ChatSession>> list(
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) Integer size) {
        IPage<ChatSession> p = chatSessionMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<ChatSession>lambdaQuery().orderByDesc(ChatSession::getLastAt));
        List<ChatSession> records = p.getRecords();
        return Result.ok(new PageResult<ChatSession>(records == null ? new ArrayList<>() : records,
                p.getTotal(), p.getCurrent(), p.getSize()));
    }

    @Operation(summary = "会话消息（只读）", description = "按时间正序，用于举报取证")
    @GetMapping("/{sessionId}/messages")
    public Result<PageResult<ChatMessage>> messages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") @Min(1) Integer page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) Integer size) {
        IPage<ChatMessage> p = chatMessageMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));
        List<ChatMessage> records = p.getRecords();
        return Result.ok(new PageResult<ChatMessage>(records == null ? new ArrayList<>() : records,
                p.getTotal(), p.getCurrent(), p.getSize()));
    }
}
