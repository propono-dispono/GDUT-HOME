package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.ChatMessageVO;
import com.gdut.xiaojii.dto.vo.ChatSessionVO;

import java.util.List;

/**
 * 私信。
 *
 * <p>会话唯一性由 {@code chat_session} 的 (user_a, user_b, ref_type, ref_id) 唯一键保证，
 * 其中 user_a 恒为较小的 user_id —— 这样 A→B 和 B→A 落在同一条会话上。</p>
 *
 * @author 校集后端
 */
public interface ChatService {

    /** 打开（不存在则创建）与某人的会话 */
    ChatSessionVO open(Long userId, Long toUserId, String refType, Long refId);

    /** 我的会话列表 */
    List<ChatSessionVO> sessions(Long userId);

    /** 会话消息（仅参与方） */
    PageResult<ChatMessageVO> messages(Long userId, Long sessionId, int page, int size);

    /** 发送消息 */
    ChatMessageVO send(Long userId, Long sessionId, String content);
}
