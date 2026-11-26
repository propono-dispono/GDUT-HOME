package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.util.TextUtil;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.dto.vo.ChatMessageVO;
import com.gdut.xiaojii.dto.vo.ChatSessionVO;
import com.gdut.xiaojii.entity.ChatMessage;
import com.gdut.xiaojii.entity.ChatSession;
import com.gdut.xiaojii.entity.Notice;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.mapper.ChatMessageMapper;
import com.gdut.xiaojii.mapper.ChatSessionMapper;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.service.ChatService;
import com.gdut.xiaojii.event.NoticeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 私信实现。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final UserMapper userMapper;
    // 通知走消息队列（事务提交后投递），发私信不再被通知表拖慢/拖垮
    private final NoticeEventPublisher noticePublisher;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO open(Long userId, Long toUserId, String refType, Long refId) {
        if (toUserId == null) {
            throw BizException.of(ResultCode.PARAM_ERROR, "缺少对方用户");
        }
        if (toUserId.equals(userId)) {
            throw BizException.of(ResultCode.PARAM_ERROR, "不能和自己私信");
        }
        User peer = userMapper.selectById(toUserId);
        if (peer == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "对方用户不存在");
        }

        long a = Math.min(userId, toUserId);
        long b = Math.max(userId, toUserId);
        String rt = (refType == null || refType.isBlank()) ? "NONE" : refType;
        long rid = refId == null ? 0L : refId;

        ChatSession s = sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserA, a)
                .eq(ChatSession::getUserB, b)
                .eq(ChatSession::getRefType, rt)
                .eq(ChatSession::getRefId, rid)
                .last("limit 1"));

        if (s == null) {
            s = new ChatSession();
            s.setUserA(a);
            s.setUserB(b);
            s.setRefType(rt);
            s.setRefId(rid);
            s.setLastMsg("");
            s.setLastAt(LocalDateTime.now());
            try {
                sessionMapper.insert(s);
            } catch (DuplicateKeyException e) {
                // 并发下两个请求同时创建，唯一键兜底后重新取一次
                s = sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getUserA, a)
                        .eq(ChatSession::getUserB, b)
                        .eq(ChatSession::getRefType, rt)
                        .eq(ChatSession::getRefId, rid)
                        .last("limit 1"));
                if (s == null) {
                    throw BizException.of(ResultCode.PARAM_ERROR, "会话创建失败，请重试");
                }
            }
        }
        return toVO(s, peer, userId);
    }

    @Override
    public List<ChatSessionVO> sessions(Long userId) {
        List<ChatSession> list = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .and(w -> w.eq(ChatSession::getUserA, userId).or().eq(ChatSession::getUserB, userId))
                .orderByDesc(ChatSession::getLastAt)
                .last("limit 50"));
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> peerIds = list.stream()
                .map(s -> s.getUserA().equals(userId) ? s.getUserB() : s.getUserA())
                .distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(peerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (x, y) -> x));

        return list.stream().map(s -> {
            Long peerId = s.getUserA().equals(userId) ? s.getUserB() : s.getUserA();
            return toVO(s, userMap.get(peerId), userId);
        }).toList();
    }

    @Override
    public PageResult<ChatMessageVO> messages(Long userId, Long sessionId, int page, int size) {
        requireParticipant(userId, sessionId);

        IPage<ChatMessage> p = messageMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId));

        List<ChatMessage> records = p.getRecords() == null ? new ArrayList<>() : new ArrayList<>(p.getRecords());
        // 查询是倒序（新的在前），展示要正序（新的在下）
        Collections.reverse(records);

        // 一次查出本页涉及的用户，避免逐条查库
        Set<Long> uids = records.stream().map(ChatMessage::getFromUser)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = uids.isEmpty() ? new HashMap<>()
                : userMapper.selectBatchIds(uids).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        List<ChatMessageVO> list = records.stream().map(m -> {
            ChatMessageVO vo = new ChatMessageVO();
            vo.setId(m.getId());
            vo.setFromUserId(m.getFromUser());
            vo.setMine(m.getFromUser().equals(userId));
            vo.setMsgType(m.getMsgType());
            vo.setContent(m.getContent());
            vo.setCreatedAt(m.getCreatedAt() == null ? null : m.getCreatedAt().format(TS));
            User u = userMap.get(m.getFromUser());
            if (u != null) {
                vo.setFromNickname(u.getNickname());
                vo.setFromAvatar(u.getAvatar());
                vo.setFromInitial(TextUtil.initial(u.getNickname()));
            }
            return vo;
        }).toList();

        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 发送 */
    public ChatMessageVO send(Long userId, Long sessionId, String content) {
        ChatSession s = requireParticipant(userId, sessionId);

        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "消息不能为空");
        }
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }

        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setFromUser(userId);
        m.setMsgType(1);
        m.setContent(text);
        m.setReadFlag(0);
        messageMapper.insert(m);

        // 会话列表摘要同步更新
        sessionMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getLastMsg, text.length() > 100 ? text.substring(0, 100) : text)
                .set(ChatSession::getLastAt, LocalDateTime.now()));

        Long peerId = s.getUserA().equals(userId) ? s.getUserB() : s.getUserA();
        // refType/refId 必须带上：消息中心要靠它跳回这个会话（之前传 null，导致点了没反应）
        noticePublisher.publish(peerId, Notice.TYPE_SYSTEM, "收到一条新私信",
                text.length() > 40 ? text.substring(0, 40) + "…" : text, "CHAT", sessionId);

        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(m.getId());
        vo.setFromUserId(userId);
        vo.setMine(true);
        vo.setMsgType(1);
        vo.setContent(text);
        vo.setCreatedAt(m.getCreatedAt() == null ? null : m.getCreatedAt().format(TS));
        User me = userMapper.selectById(userId);
        if (me != null) {
            vo.setFromNickname(me.getNickname());
            vo.setFromAvatar(me.getAvatar());
            vo.setFromInitial(TextUtil.initial(me.getNickname()));
        }
        return vo;
    }

    /* ==================== 内部 ==================== */

    /** 获取并校验participant */
    private ChatSession requireParticipant(Long userId, Long sessionId) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "会话不存在");
        }
        if (!s.getUserA().equals(userId) && !s.getUserB().equals(userId)) {
            throw BizException.of(ResultCode.POST_NOT_OWNER, "你不是该会话的参与方");
        }
        return s;
    }

    /** 转为vo */
    private ChatSessionVO toVO(ChatSession s, User peer, Long viewerId) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(s.getId());
        Long peerId = s.getUserA().equals(viewerId) ? s.getUserB() : s.getUserA();
        vo.setPeerId(peerId);
        if (peer != null) {
            vo.setPeerName(peer.getNickname());
            String n = peer.getNickname();
            vo.setPeerInitial(n == null || n.isBlank() ? "?" : n.substring(0, 1));
        } else {
            vo.setPeerName("未知用户");
            vo.setPeerInitial("?");
        }
        vo.setLastMsg(s.getLastMsg());
        vo.setLastAt(s.getLastAt() == null ? null : s.getLastAt().format(TS));
        vo.setRefType(s.getRefType());
        vo.setRefId(s.getRefId());
        return vo;
    }
}
