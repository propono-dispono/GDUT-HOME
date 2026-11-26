package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.enums.PostStatus;
import com.gdut.xiaojii.dto.req.ReportHandleReq;
import com.gdut.xiaojii.dto.req.ReportSubmitReq;
import com.gdut.xiaojii.dto.vo.AdminReportItemVO;
import com.gdut.xiaojii.entity.AuditRecord;
import com.gdut.xiaojii.entity.Notice;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.entity.Report;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.mapper.AuditRecordMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import com.gdut.xiaojii.mapper.ReportMapper;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.event.NoticeEventPublisher;
import com.gdut.xiaojii.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 举报实现。
 *
 * <p>处理「成立」时会联动下架被举报的内容 —— 这是举报功能真正的价值，
 * 否则管理员还得手动再去内容审核里找一遍。</p>
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final AuditRecordMapper auditRecordMapper;
    // 通知走消息队列（事务提交后投递），举报处理不再被通知表拖慢
    private final NoticeEventPublisher noticePublisher;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 提交举报。校验被举报对象存在，且同一人不能重复举报同一内容 */
    @Override
    public void submit(Long userId, ReportSubmitReq req) {
        String type = req.getTargetType();
        if (!Report.TARGET_POST.equals(type) && !Report.TARGET_USER.equals(type)
                && !Report.TARGET_MSG.equals(type)) {
            throw BizException.of(ResultCode.PARAM_ERROR, "举报对象类型不正确");
        }

        // 不能举报自己
        if (Report.TARGET_POST.equals(type)) {
            Post post = postMapper.selectById(req.getTargetId());
            if (post == null) {
                throw BizException.of(ResultCode.POST_NOT_FOUND);
            }
            if (post.getUserId().equals(userId)) {
                throw BizException.of(ResultCode.PARAM_ERROR, "不能举报自己发布的内容");
            }
        } else if (Report.TARGET_USER.equals(type)) {
            if (req.getTargetId().equals(userId)) {
                throw BizException.of(ResultCode.PARAM_ERROR, "不能举报自己");
            }
        }

        Report r = new Report();
        r.setTargetType(type);
        r.setTargetId(req.getTargetId());
        r.setReporterId(userId);
        r.setReasonType(req.getReasonType());
        r.setDescription(req.getDescription() == null ? "" : req.getDescription());
        r.setImages(toJson(req.getImages()));
        r.setStatus(Report.STATUS_PENDING);
        try {
            reportMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 表上有 uk_reporter_target：同一人对同一对象只能举报一次
            throw BizException.of(ResultCode.OPERATION_TOO_FREQUENT, "你已经举报过这条内容了");
        }
        log.info("举报已提交 reporterId={} target={}#{}", userId, type, req.getTargetId());
    }

    /** 举报列表。按状态（0待处理/1已处理/2驳回）分页，供管理端处理 */
    @Override
    public PageResult<AdminReportItemVO> adminList(Integer status, int page, int size) {
        IPage<Report> p = reportMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<Report>lambdaQuery()
                        .eq(status != null, Report::getStatus, status)
                        // 待处理排前
                        .orderByAsc(Report::getStatus)
                        .orderByDesc(Report::getId));

        List<Report> records = p.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), p.getTotal(), p.getCurrent(), p.getSize());
        }

        // 批量取举报人昵称
        List<Long> reporterIds = records.stream().map(Report::getReporterId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(reporterIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // 批量取被举报内容标题（只有 POST 需要）
        List<Long> postIds = records.stream()
                .filter(r -> Report.TARGET_POST.equals(r.getTargetType()))
                .map(Report::getTargetId).distinct().toList();
        Map<Long, Post> postMap = postIds.isEmpty() ? Map.of()
                : postMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity(), (a, b) -> a));

        List<AdminReportItemVO> list = records.stream().map(r -> {
            AdminReportItemVO vo = new AdminReportItemVO();
            vo.setId(r.getId());
            vo.setTargetType(r.getTargetType());
            vo.setTargetTypeText(targetTypeText(r.getTargetType()));
            vo.setTargetId(r.getTargetId());
            Post post = postMap.get(r.getTargetId());
            vo.setTargetTitle(post == null ? null : post.getTitle());
            vo.setReporterId(r.getReporterId());
            User u = userMap.get(r.getReporterId());
            vo.setReporter(u == null ? null : u.getNickname());
            vo.setReasonType(r.getReasonType());
            vo.setDescription(r.getDescription());
            vo.setStatus(r.getStatus());
            vo.setStatusText(statusText(r.getStatus()));
            vo.setHandleRemark(r.getHandleRemark());
            vo.setHandledAt(r.getHandledAt() == null ? null : r.getHandledAt().format(TS));
            vo.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().format(TS));
            return vo;
        }).toList();

        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 处理举报。成立则执行处置（下架帖子/封禁用户），驳回则关闭；两种都写审计 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long reportId, ReportHandleReq req, Long adminId) {
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "举报记录不存在");
        }

        boolean confirmed = "CONFIRMED".equalsIgnoreCase(req.getResult());
        int targetStatus = confirmed ? Report.STATUS_CONFIRMED : Report.STATUS_REJECTED;

        // CAS：只有待处理才能处理，避免两个管理员同时点
        int rows = reportMapper.update(null, Wrappers.<Report>lambdaUpdate()
                .eq(Report::getId, reportId)
                .eq(Report::getStatus, Report.STATUS_PENDING)
                .set(Report::getStatus, targetStatus)
                .set(Report::getHandlerId, adminId)
                .set(Report::getHandleRemark, req.getRemark())
                .set(Report::getHandledAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL, "该举报已处理过，请刷新后重试");
        }

        // 成立且举报的是内容 → 联动下架
        if (confirmed && Report.TARGET_POST.equals(report.getTargetType())) {
            int affected = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                    .eq(Post::getId, report.getTargetId())
                    .eq(Post::getStatus, PostStatus.ONLINE.getCode())
                    .set(Post::getStatus, PostStatus.OFFLINE.getCode())
                    .set(Post::getRejectReason, "举报成立：" + safe(req.getRemark())));
            if (affected > 0) {
                Post post = postMapper.selectById(report.getTargetId());
                if (post != null) {
                    noticePublisher.publish(post.getUserId(), Notice.TYPE_AUDIT, "内容已被下架",
                            "《" + abbr(post.getTitle(), 20) + "》因举报成立被下架。"
                                    + (safe(req.getRemark()).isEmpty() ? "" : "原因：" + safe(req.getRemark())),
                            Notice.REF_POST, post.getId());
                }
            }
        }

        writeAudit(reportId, adminId, confirmed ? AuditRecord.RESULT_REJECT : AuditRecord.RESULT_PASS,
                (confirmed ? "举报成立：" : "举报不成立：") + safe(req.getRemark()));

        // 通知举报人处理结果
        noticePublisher.publish(report.getReporterId(), Notice.TYPE_SYSTEM, "你的举报已处理",
                confirmed ? "举报成立，我们已处理相关内容，感谢反馈。" : "经核实举报不成立，该内容暂不作处理。",
                Report.TARGET_POST.equals(report.getTargetType()) ? Notice.REF_POST : null,
                Report.TARGET_POST.equals(report.getTargetType()) ? report.getTargetId() : null);

        log.info("举报已处理 reportId={} confirmed={} adminId={}", reportId, confirmed, adminId);
    }

    /* ==================== 工具 ==================== */

    /** 写举报处理记录：谁处理、结论是什么、什么时候 */
    private void writeAudit(Long reportId, Long adminId, String result, String remark) {
        AuditRecord ar = new AuditRecord();
        ar.setTargetType("REPORT");
        ar.setTargetId(reportId);
        ar.setReviewerId(adminId);
        ar.setResult(result);
        ar.setRemark(abbr(remark, 200));
        auditRecordMapper.insert(ar);
    }

    /** 举报对象类型转中文（post帖子 / user用户 / comment评论） */
    private String targetTypeText(String t) {
        if (t == null) {
            return "未知";
        }
        return switch (t) {
            case Report.TARGET_POST -> "内容";
            case Report.TARGET_USER -> "用户";
            case Report.TARGET_MSG -> "私信";
            default -> t;
        };
    }

    /** 举报状态转中文（0待处理 / 1已处理 / 2驳回） */
    private String statusText(Integer s) {
        if (s == null) {
            return "待处理";
        }
        return switch (s) {
            case Report.STATUS_CONFIRMED -> "已处理";
            case Report.STATUS_REJECTED -> "不成立";
            default -> "待处理";
        };
    }

    /** null 转空串，避免前端显示 null */
    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    /** 字符串截断到 max，超出加省略号 */
    private String abbr(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** List 转 JSON 字符串（举报图片证据存库用） */
    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(list.get(i).replace("\"", "")).append('"');
        }
        return sb.append(']').toString();
    }
}
