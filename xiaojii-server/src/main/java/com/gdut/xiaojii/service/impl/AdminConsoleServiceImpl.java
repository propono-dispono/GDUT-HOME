package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.constant.RedisKey;
import com.gdut.xiaojii.common.enums.AuditStatus;
import com.gdut.xiaojii.common.enums.PostStatus;
import com.gdut.xiaojii.dto.vo.AdminOverviewVO;
import com.gdut.xiaojii.dto.vo.AdminUserItemVO;
import com.gdut.xiaojii.entity.AuditRecord;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.entity.UserVerify;
import com.gdut.xiaojii.mapper.AuditRecordMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.mapper.UserVerifyMapper;
import com.gdut.xiaojii.service.AdminConsoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端控制台实现。
 *
 * <p>统计口径全部按<b>业务意义</b>而不是"表里有什么"来定，例如：
 * 「待审核内容」= audit_status 待审，而不是 status=0 —— 两者在正常数据下等价，
 * 但用 audit_status 更贴合"审核队列"的语义。</p>
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminConsoleServiceImpl implements AdminConsoleService {

    private final UserMapper userMapper;
    private final UserVerifyMapper verifyMapper;
    private final PostMapper postMapper;
    private final AuditRecordMapper auditRecordMapper;
    private final StringRedisTemplate redis;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /* ==================== 概览 ==================== */

    /** 管理端首页看板：用户总数、帖子数、待审数、待处理举报数 */
    @Override
    public AdminOverviewVO overview() {
        AdminOverviewVO vo = new AdminOverviewVO();

        vo.setPendingVerify(count(verifyMapper.selectCount(
                Wrappers.<UserVerify>lambdaQuery().eq(UserVerify::getStatus, UserVerify.STATUS_PENDING))));
        vo.setPendingPost(count(postMapper.selectCount(
                Wrappers.<Post>lambdaQuery().eq(Post::getAuditStatus, AuditStatus.PENDING.getCode()))));
        vo.setOnlinePost(count(postMapper.selectCount(
                Wrappers.<Post>lambdaQuery().eq(Post::getStatus, PostStatus.ONLINE.getCode()))));
        vo.setTotalPost(count(postMapper.selectCount(null)));
        vo.setTodayPost(count(postMapper.selectCount(
                Wrappers.<Post>lambdaQuery().ge(Post::getCreatedAt, LocalDate.now().atStartOfDay()))));

        vo.setTotalUser(count(userMapper.selectCount(null)));
        vo.setVerifiedUser(count(userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getVerifyStatus, User.VERIFY_PASSED))));
        // 2禁言 + 3封禁 都算"受限制账号"
        vo.setBannedUser(count(userMapper.selectCount(
                Wrappers.<User>lambdaQuery().in(User::getStatus, 2, 3))));
        return vo;
    }

    /* ==================== 用户管理 ==================== */

    /** 用户列表。按关键词（昵称/学号）和状态筛选，分页返回 */
    @Override
    public PageResult<AdminUserItemVO> userList(String keyword, Integer status, Integer verifyStatus,
                                                int page, int size) {
        IPage<User> p = userMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<User>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), User::getNickname, keyword)
                        .eq(status != null, User::getStatus, status)
                        .eq(verifyStatus != null, User::getVerifyStatus, verifyStatus)
                        .orderByDesc(User::getId));

        List<User> records = p.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), p.getTotal(), p.getCurrent(), p.getSize());
        }

        // 批量统计每人发布数，避免 N+1
        List<Long> ids = records.stream().map(User::getId).toList();
        List<Post> posts = postMapper.selectList(
                Wrappers.<Post>lambdaQuery().select(Post::getUserId).in(Post::getUserId, ids));
        Map<Long, Long> postCount = posts.stream()
                .collect(Collectors.groupingBy(Post::getUserId, Collectors.counting()));

        List<AdminUserItemVO> list = records.stream()
                .map(u -> toItem(u, postCount.getOrDefault(u.getId(), 0L)))
                .toList();
        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /** 用户详情。含发帖数、认证状态、信用分、封禁原因 */
    @Override
    public AdminUserItemVO userDetail(Long userId) {
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        long cnt = count(postMapper.selectCount(
                Wrappers.<Post>lambdaQuery().eq(Post::getUserId, userId)));
        return toItem(u, cnt);
    }

    /** 改用户状态：1正常 / 2禁言 / 3封禁。封禁必须带原因，用户能看到 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeUserStatus(Long userId, Integer status, String reason, Long adminId) {
        if (status == null || status < 1 || status > 3) {
            throw BizException.of(ResultCode.PARAM_ERROR, "账号状态只能是 1正常 / 2禁言 / 3封禁");
        }
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        Integer from = u.getStatus();
        u.setStatus(status);
        userMapper.updateById(u);

        // ⚠️ 必须同步 Redis：AuthInterceptor 只读 Redis 的 userStatus，不查库。
        //    不同步的话，后台点了封禁，用户下次请求依然放行（等于封禁无效）。
        redis.opsForValue().set(RedisKey.userStatus(userId), String.valueOf(status));

        writeAudit(AuditRecord.TARGET_USER, userId, adminId, "ADJUST",
                "账号状态 " + statusText(from) + " → " + statusText(status)
                        + (reason == null || reason.isBlank() ? "" : "：" + reason));
        log.info("管理员修改用户状态 userId={} {}->{} adminId={}", userId, from, status, adminId);
    }

    /** 调整信用分。delta 可正可负（加分/扣分），必须带原因并写审计记录 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adjustCredit(Long userId, Integer delta, String reason, Long adminId) {
        if (delta == null || delta == 0) {
            throw BizException.of(ResultCode.PARAM_ERROR, "请填写调整分值（正数加分、负数扣分）");
        }
        User u = userMapper.selectById(userId);
        if (u == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        int before = u.getCreditScore() == null ? 80 : u.getCreditScore();
        // 信用分必须 clamp 在 0-100，否则展示与规则都会越界
        int after = Math.max(0, Math.min(100, before + delta));
        u.setCreditScore(after);
        userMapper.updateById(u);

        writeAudit(AuditRecord.TARGET_USER, userId, adminId, "ADJUST",
                "信用分 " + before + " → " + after + "（" + (delta > 0 ? "+" : "") + delta + "）"
                        + (reason == null || reason.isBlank() ? "" : "：" + reason));
        log.info("管理员调整信用分 userId={} {}->{} adminId={}", userId, before, after, adminId);
    }

    /* ==================== 内部工具 ==================== */

    /** User → 管理端列表项。附带发帖数、状态文案、认证文案 */
    private AdminUserItemVO toItem(User u, long postCount) {
        AdminUserItemVO vo = new AdminUserItemVO();
        vo.setId(u.getId());
        vo.setNickname(u.getNickname());
        vo.setInitial(initialOf(u.getNickname()));
        vo.setOpenidMasked(maskOpenid(u.getOpenid()));
        vo.setCreditScore(u.getCreditScore());
        vo.setVerifyStatus(u.getVerifyStatus());
        vo.setVerifyStatusText(verifyText(u.getVerifyStatus()));
        vo.setStatus(u.getStatus());
        vo.setStatusText(statusText(u.getStatus()));
        vo.setPostCount(postCount);
        vo.setCreatedAt(u.getCreatedAt() == null ? null : u.getCreatedAt().format(TS));
        return vo;
    }

    /** 管理员操作留痕。复用 audit_record，target_type 用 USER */
    private void writeAudit(String targetType, Long targetId, Long adminId, String result, String remark) {
        AuditRecord ar = new AuditRecord();
        ar.setTargetType(targetType);
        ar.setTargetId(targetId);
        ar.setReviewerId(adminId);
        ar.setResult(result);
        ar.setRemark(remark == null ? null : (remark.length() > 200 ? remark.substring(0, 200) : remark));
        auditRecordMapper.insert(ar);
    }

    /** null 转 0（统计数缺失时兜底，避免前端显示 null） */
    private long count(Long v) {
        return v == null ? 0 : v;
    }

    /** 取昵称首字，管理端头像占位用 */
    private String initialOf(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "?";
        }
        return nickname.substring(0, 1);
    }

    /** openid 是用户隐私，列表只给脱敏值 */
    private String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 8) {
            return openid == null ? null : "****";
        }
        return openid.substring(0, 4) + "****" + openid.substring(openid.length() - 4);
    }

    /** 认证状态码转中文（0待审 / 1通过 / 2驳回） */
    private String verifyText(Integer s) {
        if (s == null) {
            return "未认证";
        }
        return switch (s) {
            case User.VERIFY_PENDING -> "待审核";
            case User.VERIFY_PASSED -> "已认证";
            case User.VERIFY_REJECTED -> "已驳回";
            default -> "未认证";
        };
    }

    /** 用户状态码转中文（1正常 / 2禁言 / 3封禁） */
    private String statusText(Integer s) {
        if (s == null) {
            return "正常";
        }
        return switch (s) {
            case 2 -> "禁言";
            case 3 -> "封禁";
            default -> "正常";
        };
    }
}
