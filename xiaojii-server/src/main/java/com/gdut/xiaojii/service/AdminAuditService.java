package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.AdminPostItemVO;
import com.gdut.xiaojii.dto.vo.AdminVerifyItemVO;

/**
 * 管理端审核：认证审核 + 内容审核 + 强制下架。
 * 所有状态变更都是 CAS（WHERE 旧状态），并写 audit_record 留痕 + 发 notice 通知用户。
 *
 * @author 校集后端
 */
public interface AdminAuditService {

    /**
     * 认证申请列表
     *
     * @param status 0待审 1通过 2驳回，null 表示全部
     */
    PageResult<AdminVerifyItemVO> verifyList(Integer status, int page, int size);

    /** 认证通过：user_verify.status=1，user.verify_status=2 */
    void approveVerify(Long id, Long adminId);

    /** 认证驳回：user_verify.status=2，user.verify_status=3 */
    void rejectVerify(Long id, String reason, Long adminId);

    /**
     * 内容审核列表
     *
     * @param status      业务状态，null 表示全部
     * @param auditStatus 审核状态，null 表示全部
     */
    PageResult<AdminPostItemVO> postList(Integer status, Integer auditStatus, String bizType,
                                         String keyword, int page, int size);

    /** 内容通过：status=1 展示中，audit_status=2 人工通过 */
    void approvePost(Long id, Long adminId);

    /** 内容驳回：status=2 已下架，audit_status=3 已驳回 */
    void rejectPost(Long id, String reason, Long adminId);

    /** 强制下架（针对已上架内容）：status 1 → 2 */
    void offlinePost(Long id, String reason, Long adminId);
}
