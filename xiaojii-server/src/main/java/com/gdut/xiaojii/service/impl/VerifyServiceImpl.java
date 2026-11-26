package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.util.TextUtil;
import com.gdut.xiaojii.dto.req.VerifySubmitReq;
import com.gdut.xiaojii.dto.vo.VerifyVO;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.entity.UserVerify;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.mapper.UserVerifyMapper;
import com.gdut.xiaojii.service.VerifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

/**
 * 校园认证服务实现。
 *
 * <p><b>两套状态码的映射（最容易写错的地方）：</b>
 * <pre>
 * user_verify.status : 0待审 1通过 2驳回
 * user.verify_status : 0未认证 1待审核 2已认证 3驳回
 * </pre>
 * 注意 2 的含义相反！本类统一用常量映射，绝不做 {@code user.setVerifyStatus(log.getStatus())}。
 *
 * <p><b>重复提交策略：</b>
 * <ul>
 *   <li>已认证 → 1022 拒绝</li>
 *   <li>审核中 → 1021 拒绝（避免刷审核队列）</li>
 *   <li>被驳回 → 复用原记录更新（保留同一条，方便后台看历史），同时把 user 置回待审</li>
 * </ul>
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyServiceImpl implements VerifyService {

    private final UserVerifyMapper verifyMapper;
    private final UserMapper userMapper;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 提交 */
    public VerifyVO submit(VerifySubmitReq req) {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }

        if (user.getVerifyStatus() != null && user.getVerifyStatus() == User.VERIFY_PASSED) {
            throw BizException.of(ResultCode.VERIFY_ALREADY_PASS);
        }
        if (user.getVerifyStatus() != null && user.getVerifyStatus() == User.VERIFY_PENDING) {
            throw BizException.of(ResultCode.VERIFY_PENDING);
        }

        // 学号全局唯一：先查再写，同时靠唯一索引兜住并发
        UserVerify sameNo = verifyMapper.selectOne(
                Wrappers.<UserVerify>lambdaQuery().eq(UserVerify::getStudentNo, req.getStudentNo()));
        if (sameNo != null && !sameNo.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.STUDENT_NO_EXISTS);
        }

        UserVerify record = verifyMapper.selectOne(
                Wrappers.<UserVerify>lambdaQuery()
                        .eq(UserVerify::getUserId, userId)
                        .orderByDesc(UserVerify::getId)
                        .last("limit 1"));

        if (record == null) {
            record = new UserVerify();
            record.setUserId(userId);
            record.setStudentNo(req.getStudentNo());
            record.setRealName(req.getRealName().trim());
            record.setCardImage(req.getCardImage());
            record.setStatus(UserVerify.STATUS_PENDING);
            try {
                verifyMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发下两个请求同时提交同一学号，唯一索引兜底
                throw BizException.of(ResultCode.STUDENT_NO_EXISTS);
            }
        } else {
            // 驳回后重提：复用原记录，清掉上一轮的审核痕迹
            record.setStudentNo(req.getStudentNo());
            record.setRealName(req.getRealName().trim());
            record.setCardImage(req.getCardImage());
            record.setStatus(UserVerify.STATUS_PENDING);
            record.setRejectReason(null);
            record.setReviewerId(null);
            record.setReviewedAt(null);
            verifyMapper.updateById(record);
        }

        // user 侧同步为"待审核"，这样未认证用户发帖等操作能被统一拦住
        user.setVerifyStatus(User.VERIFY_PENDING);
        userMapper.updateById(user);

        log.info("提交校园认证 userId={} studentNo={}", userId, req.getStudentNo());
        return getStatus();
    }

    @Override
    /** 取状态 */
    public VerifyVO getStatus() {
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }

        VerifyVO vo = new VerifyVO();
        vo.setStatus(user.getVerifyStatus());
        vo.setStatusText(statusText(user.getVerifyStatus()));

        UserVerify record = verifyMapper.selectOne(
                Wrappers.<UserVerify>lambdaQuery()
                        .eq(UserVerify::getUserId, userId)
                        .orderByDesc(UserVerify::getId)
                        .last("limit 1"));
        if (record != null) {
            // 学号与姓名必须脱敏：认证页截图外泄是很常见的隐私事故
            vo.setStudentNo(TextUtil.maskStudentNo(record.getStudentNo()));
            vo.setRealName(maskName(record.getRealName()));
            if (record.getCreatedAt() != null) {
                vo.setSubmittedAt(record.getCreatedAt().format(DAY));
            }
            if (record.getReviewedAt() != null) {
                vo.setReviewedAt(record.getReviewedAt().format(DAY));
            }
            vo.setRejectReason(record.getRejectReason());
        }
        return vo;
    }

    /** 状态文本 */
    private String statusText(Integer status) {
        if (status == null) {
            return "未认证";
        }
        return switch (status) {
            case User.VERIFY_NONE -> "未认证";
            case User.VERIFY_PENDING -> "审核中";
            case User.VERIFY_PASSED -> "已认证";
            case User.VERIFY_REJECTED -> "已驳回";
            default -> "未认证";
        };
    }

    /** 姓名脱敏：张三 → 张*；欧阳修 → 欧*修 */
    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.length() == 1) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }
}
