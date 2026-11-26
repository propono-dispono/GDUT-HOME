package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.req.ReportHandleReq;
import com.gdut.xiaojii.dto.req.ReportSubmitReq;
import com.gdut.xiaojii.dto.vo.AdminReportItemVO;

/**
 * 举报：用户提交 + 管理端处理。
 *
 * @author 校集后端
 */
public interface ReportService {

    /** 小程序端提交举报 */
    void submit(Long userId, ReportSubmitReq req);

    /** 管理端举报列表 */
    PageResult<AdminReportItemVO> adminList(Integer status, int page, int size);

    /**
     * 管理端处理举报
     *
     * @param result 成立时：若举报的是内容，会自动下架该内容并通知作者
     */
    void handle(Long reportId, ReportHandleReq req, Long adminId);
}
