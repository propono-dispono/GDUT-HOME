package com.gdut.xiaojii.service;

import com.gdut.xiaojii.dto.req.VerifySubmitReq;
import com.gdut.xiaojii.dto.vo.VerifyVO;

/**
 * 校园认证服务。
 *
 * @author 校集后端
 */
public interface VerifyService {

    /** 提交认证申请（重复提交按"驳回后重提"处理，不新建脏数据） */
    VerifyVO submit(VerifySubmitReq req);

    /** 查询当前用户的认证状态 */
    VerifyVO getStatus();
}
