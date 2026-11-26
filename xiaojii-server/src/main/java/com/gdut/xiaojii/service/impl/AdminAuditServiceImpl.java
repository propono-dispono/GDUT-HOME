package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.enums.AuditStatus;
import com.gdut.xiaojii.common.enums.PostStatus;
import com.gdut.xiaojii.config.UploadProperties;
import com.gdut.xiaojii.dto.vo.AdminPostItemVO;
import com.gdut.xiaojii.dto.vo.AdminVerifyItemVO;
import com.gdut.xiaojii.entity.AuditRecord;
import com.gdut.xiaojii.entity.Notice;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.entity.UserVerify;
import com.gdut.xiaojii.mapper.AuditRecordMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.mapper.UserVerifyMapper;
import com.gdut.xiaojii.service.AdminAuditService;
import com.gdut.xiaojii.event.NoticeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端审核实现。
 *
 * <p>⚠️ 全项目最容易踩反的地方就在这个类里：</p>
 * <ul>
 *   <li>{@code user_verify.status}：0 待审 / 1 通过 / 2 驳回</li>
 *   <li>{@code user.verify_status}：0 未认证 / 1 待审核 / 2 已认证 / 3 驳回</li>
 * </ul>
 * <p>两套码含义相反，<b>绝不能 setVerifyStatus(record.getStatus())</b> —— 那样通过后用户反而变成“待审核”，永远发不了帖。</p>
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditServiceImpl implements AdminAuditService {

    private final UserVerifyMapper verifyMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final AuditRecordMapper auditRecordMapper;
    // 通知走消息队列（事务提交后投递）。
    // 【为什么不是 NoticeService】同步写 notice 表会让"审核通过"这个动作依赖通知表：
    //   通知表一慢审核就慢，通知表一挂审核直接回滚（管理员看到失败，但审核记录其实已经写了）。
    //   走发布器则是事务提交后才投递，通知失败不影响审核结果。
    private final NoticeEventPublisher noticePublisher;
    private final UploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /* ==================== 认证审核 ==================== */

    /** 认证申请列表。按状态筛选（0待审/1通过/2驳回）分页返回 */
    @Override
    public PageResult<AdminVerifyItemVO> verifyList(Integer status, int page, int size) {
        IPage<UserVerify> p = verifyMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<UserVerify>lambdaQuery()
                        .eq(status != null, UserVerify::getStatus, status)
                        // 待审(0) 排最前，同状态内新的在前
                        .orderByAsc(UserVerify::getStatus)
                        .orderByDesc(UserVerify::getId));

        List<UserVerify> records = p.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), p.getTotal(), p.getCurrent(), p.getSize());
        }

        // 批量取用户，禁止 N+1
        List<Long> userIds = records.stream().map(UserVerify::getUserId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        List<AdminVerifyItemVO> list = records.stream().map(r -> {
            AdminVerifyItemVO vo = new AdminVerifyItemVO();
            vo.setId(r.getId());
            vo.setUserId(r.getUserId());
            User u = userMap.get(r.getUserId());
            if (u != null) {
                vo.setNickname(u.getNickname());
                vo.setInitial(initialOf(u.getNickname()));
            }
            vo.setStudentNo(r.getStudentNo());
            vo.setRealName(r.getRealName());
            vo.setCardImage(toUrl(r.getCardImage()));
            vo.setStatus(r.getStatus());
            vo.setStatusText(verifyStatusText(r.getStatus()));
            vo.setRejectReason(r.getRejectReason());
            vo.setSubmittedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().format(TS));
            vo.setReviewedAt(r.getReviewedAt() == null ? null : r.getReviewedAt().format(TS));
            return vo;
        }).toList();

        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 通过认证：改 verify_status=2，同时给用户发通知、写审计记录 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveVerify(Long id, Long adminId) {
        UserVerify record = verifyMapper.selectById(id);
        if (record == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "认证申请不存在");
        }

        // CAS：只有「待审核」才能通过，避免两个管理员同时点
        int rows = verifyMapper.update(null, Wrappers.<UserVerify>lambdaUpdate()
                .eq(UserVerify::getId, id)
                .eq(UserVerify::getStatus, UserVerify.STATUS_PENDING)
                .set(UserVerify::getStatus, UserVerify.STATUS_PASSED)
                .set(UserVerify::getReviewerId, adminId)
                .set(UserVerify::getReviewedAt, LocalDateTime.now())
                .set(UserVerify::getRejectReason, null));
        if (rows == 0) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL, "该申请不是待审核状态，请刷新后重试");
        }

        // user 侧同步：注意这里是 2=已认证，不是 user_verify 的 1
        User user = userMapper.selectById(record.getUserId());
        if (user != null) {
            user.setVerifyStatus(User.VERIFY_PASSED);
            userMapper.updateById(user);
        }

        writeAuditRecord(AuditRecord.TARGET_VERIFY, id, adminId, AuditRecord.RESULT_PASS, "校园认证通过");
        noticePublisher.publish(record.getUserId(), Notice.TYPE_AUDIT, "校园认证已通过",
                "你的校园认证已通过审核，现在可以发布内容了。", null, null);
        log.info("认证通过 verifyId={} userId={} adminId={}", id, record.getUserId(), adminId);
    }

    /** 驳回认证：改 verify_status=3 + 记录原因，用户能看到被拒理由 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectVerify(Long id, String reason, Long adminId) {
        UserVerify record = verifyMapper.selectById(id);
        if (record == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "认证申请不存在");
        }

        int rows = verifyMapper.update(null, Wrappers.<UserVerify>lambdaUpdate()
                .eq(UserVerify::getId, id)
                .eq(UserVerify::getStatus, UserVerify.STATUS_PENDING)
                .set(UserVerify::getStatus, UserVerify.STATUS_REJECTED)
                .set(UserVerify::getReviewerId, adminId)
                .set(UserVerify::getReviewedAt, LocalDateTime.now())
                .set(UserVerify::getRejectReason, reason));
        if (rows == 0) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL, "该申请不是待审核状态，请刷新后重试");
        }

        // user 侧：3=驳回，允许用户修改后重提
        User user = userMapper.selectById(record.getUserId());
        if (user != null) {
            user.setVerifyStatus(User.VERIFY_REJECTED);
            userMapper.updateById(user);
        }

        writeAuditRecord(AuditRecord.TARGET_VERIFY, id, adminId, AuditRecord.RESULT_REJECT, reason);
        noticePublisher.publish(record.getUserId(), Notice.TYPE_AUDIT, "校园认证未通过",
                "原因：" + reason + "。请修改后重新提交。", null, null);
        log.info("认证驳回 verifyId={} adminId={} reason={}", id, adminId, reason);
    }

    /* ==================== 内容审核 ==================== */

    /** 帖子列表。按 status（展示状态）和 auditStatus（审核状态）双维度筛选 */
    @Override
    public PageResult<AdminPostItemVO> postList(Integer status, Integer auditStatus, String bizType,
                                                String keyword, int page, int size) {
        IPage<Post> p = postMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<Post>lambdaQuery()
                        .eq(status != null, Post::getStatus, status)
                        .eq(auditStatus != null, Post::getAuditStatus, auditStatus)
                        .eq(bizType != null && !bizType.isBlank(), Post::getBizType, bizType)
                        .like(keyword != null && !keyword.isBlank(), Post::getTitle, keyword)
                        .orderByDesc(Post::getId));

        List<Post> records = p.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), p.getTotal(), p.getCurrent(), p.getSize());
        }

        List<Long> userIds = records.stream().map(Post::getUserId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        List<AdminPostItemVO> list = records.stream().map(r -> {
            AdminPostItemVO vo = new AdminPostItemVO();
            vo.setId(r.getId());
            vo.setBiz(r.getBizType());
            vo.setTitle(r.getTitle());
            vo.setDesc(abbr(r.getContent(), 60));
            vo.setPrice(priceStr(r.getPrice()));
            vo.setPriceUnit(priceUnitOf(r.getBizType()));
            vo.setCover(firstImageUrl(r.getImages()));
            vo.setUserId(r.getUserId());
            User u = userMap.get(r.getUserId());
            vo.setUser(u == null ? null : initialOf(u.getNickname()));
            vo.setStatus(r.getStatus());
            vo.setStatusText(r.getStatus() == null ? null : PostStatus.of(r.getStatus()).getLabel());
            vo.setAuditStatus(r.getAuditStatus());
            vo.setAuditStatusText(r.getAuditStatus() == null ? null
                    : AuditStatus.of(r.getAuditStatus()).getLabel());
            vo.setRejectReason(r.getRejectReason());
            vo.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().format(TS));
            return vo;
        }).toList();

        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 审核通过：audit_status=2 且 status=1（上架），用户能被别人看到 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approvePost(Long id, Long adminId) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }

        // CAS 打在 audit_status 上：只允许把「待审核」的帖子放行
        int rows = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, id)
                .eq(Post::getAuditStatus, AuditStatus.PENDING.getCode())
                .set(Post::getStatus, PostStatus.ONLINE.getCode())
                .set(Post::getAuditStatus, AuditStatus.MANUAL_PASS.getCode())
                .set(Post::getRejectReason, null));
        if (rows == 0) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL, "该内容不在待审核状态，请刷新后重试");
        }

        writeAuditRecord(AuditRecord.TARGET_POST, id, adminId, AuditRecord.RESULT_PASS, "内容审核通过");
        noticePublisher.publish(post.getUserId(), Notice.TYPE_AUDIT, "内容已通过审核",
                "《" + abbr(post.getTitle(), 20) + "》已上架，其他同学现在可以看到它了。",
                Notice.REF_POST, id);
        log.info("内容通过 postId={} adminId={}", id, adminId);
    }

    /** 审核驳回：audit_status=3 + reject_reason，帖子不进信息流，作者能看到原因 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectPost(Long id, String reason, Long adminId) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }

        int rows = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, id)
                .eq(Post::getAuditStatus, AuditStatus.PENDING.getCode())
                .set(Post::getStatus, PostStatus.OFFLINE.getCode())
                .set(Post::getAuditStatus, AuditStatus.REJECTED.getCode())
                .set(Post::getRejectReason, reason));
        if (rows == 0) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL, "该内容不在待审核状态，请刷新后重试");
        }

        writeAuditRecord(AuditRecord.TARGET_POST, id, adminId, AuditRecord.RESULT_REJECT, reason);
        noticePublisher.publish(post.getUserId(), Notice.TYPE_AUDIT, "内容未通过审核",
                "《" + abbr(post.getTitle(), 20) + "》未通过审核，原因：" + reason,
                Notice.REF_POST, id);
        log.info("内容驳回 postId={} adminId={} reason={}", id, adminId, reason);
    }

    /** 强制下架：status=2（仅作者可见），用于投诉成立或违规内容 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offlinePost(Long id, String reason, Long adminId) {
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }

        // 只能下架「展示中」的内容；已下架/已关闭的再点没意义，直接报状态不对
        int rows = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, id)
                .eq(Post::getStatus, PostStatus.ONLINE.getCode())
                .set(Post::getStatus, PostStatus.OFFLINE.getCode())
                .set(Post::getRejectReason, reason));
        if (rows == 0) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL, "只有展示中的内容才能下架");
        }

        writeAuditRecord(AuditRecord.TARGET_POST, id, adminId, AuditRecord.RESULT_REJECT, "强制下架：" + reason);
        noticePublisher.publish(post.getUserId(), Notice.TYPE_AUDIT, "内容已被下架",
                "《" + abbr(post.getTitle(), 20) + "》已被下架，原因：" + reason,
                Notice.REF_POST, id);
        log.info("强制下架 postId={} adminId={} reason={}", id, adminId, reason);
    }

    /* ==================== 私有工具 ==================== */

    /** 写审计记录：谁（adminId）在什么时候对什么（targetType+targetId）做了什么（status/reason） */
    private void writeAuditRecord(String targetType, Long targetId, Long adminId, String result, String remark) {
        AuditRecord ar = new AuditRecord();
        ar.setTargetType(targetType);
        ar.setTargetId(targetId);
        ar.setReviewerId(adminId);
        ar.setResult(result);
        ar.setRemark(abbr(remark, 200));
        auditRecordMapper.insert(ar);
    }

    /** 认证状态码转中文（0待审/1通过/2驳回） */
    private String verifyStatusText(Integer status) {
        if (status == null) {
            return "待审核";
        }
        return switch (status) {
            case UserVerify.STATUS_PENDING -> "待审核";
            case UserVerify.STATUS_PASSED -> "已通过";
            case UserVerify.STATUS_REJECTED -> "已驳回";
            default -> "未知";
        };
    }

    /** 业务线转金额单位（元/元每单/元每天/元每人） */
    private String priceUnitOf(String biz) {
        if (biz == null) {
            return null;
        }
        return switch (biz) {
            case "SKILL" -> "时薪";
            case "RENTAL" -> "日租金";
            case "GROUPBUY" -> "人均";
            default -> null;
        };
    }

    /** 金额格式化：去尾零（15.00 → 15） */
    private String priceStr(BigDecimal p) {
        return p == null ? null : p.stripTrailingZeros().toPlainString();
    }

    /** 取昵称首字，前端做 monogram 头像用 */
    private String initialOf(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "?";
        }
        return nickname.substring(0, 1);
    }

    /** 字符串截断到 max 长度，超出加省略号 */
    private String abbr(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 取图片 JSON 数组里的第一张，拼成完整 URL（列表封面用） */
    private String firstImageUrl(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return null;
        }
        try {
            List<String> list = objectMapper.readValue(imagesJson, new TypeReference<List<String>>() {
            });
            return list.isEmpty() ? null : toUrl(list.get(0));
        } catch (Exception e) {
            log.warn("解析帖子图片失败：{}", imagesJson);
            return null;
        }
    }

    /** 图片 key 转完整 URL（拼上 OSS/域名前缀） */
    private String toUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key;
        }
        return uploadProperties.getPublicBase() + "/" + key;
    }
}
