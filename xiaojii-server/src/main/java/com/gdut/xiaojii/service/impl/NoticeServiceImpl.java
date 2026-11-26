package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.NoticeVO;
import com.gdut.xiaojii.entity.Notice;
import com.gdut.xiaojii.mapper.NoticeMapper;
import com.gdut.xiaojii.service.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 站内通知实现。当前只落库 + 读取，将来接订阅消息/短信时只改这里。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeServiceImpl implements NoticeService {

    private final NoticeMapper noticeMapper;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 发一条通知。type 区分系统/互动/审核，写入 notice 表 */
    @Override
    public void push(Long userId, String type, String title, String content, String refType, Long refId) {
        // 老签名：同步路径（审核、举报、私信等），没有消息 ID
        push(userId, type, title, content, refType, refId, null);
    }

    /**
     * 这一段是"把一条通知写进用户的收件箱"，异步路径（MQ 消费）走这里。
     *
     * 【完整预演｜正常】
     *   msgId="1731600000000-0"，userId=15，title="你的拼单成团了"
     *   → insert 成功 → 用户收件箱多一条
     *
     * 【完整预演｜重复投递】
     *   同一条消息被重投第二次 → insert 撞 uk_msg_id
     *   → catch 住 → 直接 return（当作成功）
     *   → MQ 那边看到没抛异常就会 ACK，消息正常出队
     *   → 用户收件箱仍然只有一条 ✅
     *
     * 【为什么这里 catch 掉而不是往上抛】
     * 往上抛会让 MQ 认为"处理失败"→ 不 ACK → 无限重投一条已经处理成功过的消息。
     * "重复"不是错误，是 at-least-once 语义下必然会发生的正常情况。
     */
    @Override
    public void push(Long userId, String type, String title, String content,
                     String refType, Long refId, String msgId) {
        if (userId == null) {
            return;
        }
        Notice n = new Notice();
        n.setUserId(userId);
        n.setType(type == null ? Notice.TYPE_SYSTEM : type);
        // 字段长度兜底：库是 VARCHAR(60)/VARCHAR(300)，超长会直接抛 SQL 异常
        n.setTitle(cut(title, 60));
        n.setContent(cut(content, 300));
        n.setRefType(refType);
        n.setRefId(refId);
        n.setReadFlag(0);
        n.setMsgId(msgId);
        try {
            noticeMapper.insert(n);
        } catch (DuplicateKeyException e) {
            // 同一条消息重复投递，幂等忽略
            log.info("[通知] 重复消息已忽略 msgId={} userId={}", msgId, userId);
            return;
        }
        log.info("通知已推送 userId={} type={} title={} msgId={}", userId, type, n.getTitle(), msgId);
    }

    /** 通知列表（分页，按时间倒序） */
    @Override
    public PageResult<NoticeVO> list(Long userId, int page, int size) {
        IPage<Notice> p = noticeMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<Notice>lambdaQuery()
                        .eq(Notice::getUserId, userId)
                        .orderByDesc(Notice::getId));

        List<NoticeVO> list = new ArrayList<>();
        for (Notice n : p.getRecords()) {
            NoticeVO vo = new NoticeVO();
            vo.setId(n.getId());
            vo.setType(n.getType());
            vo.setTypeText(typeText(n.getType()));
            vo.setTitle(n.getTitle());
            vo.setContent(n.getContent());
            vo.setRefType(n.getRefType());
            vo.setRefId(n.getRefId());
            vo.setRead(n.getReadFlag() != null && n.getReadFlag() == 1);
            vo.setCreatedAt(n.getCreatedAt() == null ? null : n.getCreatedAt().format(TS));
            list.add(vo);
        }
        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 未读通知数（首页红点用） */
    @Override
    public long unreadCount(Long userId) {
        Long c = noticeMapper.selectCount(
                Wrappers.<Notice>lambdaQuery()
                        .eq(Notice::getUserId, userId)
                        .eq(Notice::getReadFlag, 0));
        return c == null ? 0 : c;
    }

    /** 标记单条已读 */
    @Override
    public void markRead(Long userId, Long noticeId) {
        // 带上 userId 条件：防止传别人的通知 ID 把别人的通知标记掉
        noticeMapper.update(null, Wrappers.<Notice>lambdaUpdate()
                .eq(Notice::getId, noticeId)
                .eq(Notice::getUserId, userId)
                .set(Notice::getReadFlag, 1));
    }

    /** 全部标记已读 */
    @Override
    public void markAllRead(Long userId) {
        noticeMapper.update(null, Wrappers.<Notice>lambdaUpdate()
                .eq(Notice::getUserId, userId)
                .eq(Notice::getReadFlag, 0)
                .set(Notice::getReadFlag, 1));
    }

    /** 通知类型码转中文 */
    private String typeText(String type) {
        if (type == null) {
            return "系统";
        }
        return switch (type) {
            case "AUDIT" -> "审核";
            case "ORDER" -> "订单";
            case "CHAT" -> "私信";
            case "REPORT" -> "举报";
            case "GROUPBUY" -> "拼单";
            default -> "系统";
        };
    }

    /** 字符串截断到 max，超出加省略号 */
    private String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
