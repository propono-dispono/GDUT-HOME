package com.gdut.xiaojii.service;

import com.gdut.xiaojii.dto.req.AdminLoginReq;
import com.gdut.xiaojii.dto.vo.AdminLoginVO;

/**
 * 管理端登录服务。
 *
 * @author 校集后端
 */
public interface AdminAuthService {

    /** 管理员登录：校验密码 → 签发 typ=admin 的令牌 → 写 Redis */
    AdminLoginVO login(AdminLoginReq req);

    /** 管理员退出 */
    void logout();

    /** 修改密码：校验原密码 → 写新哈希 → 让当前令牌立即失效 */
    void changePassword(Long adminId, String oldPassword, String newPassword);
}
