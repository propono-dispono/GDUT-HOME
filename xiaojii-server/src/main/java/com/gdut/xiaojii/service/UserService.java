package com.gdut.xiaojii.service;

import com.gdut.xiaojii.dto.req.ProfileUpdateReq;
import com.gdut.xiaojii.dto.vo.UserVO;

/**
 * 用户资料服务。
 *
 * @author 校集后端
 */
public interface UserService {

    /** 当前登录用户自己的资料（含发布数/成交数） */
    UserVO getSelf();

    /** 修改自己的资料，只更新传了值的字段 */
    UserVO updateSelf(ProfileUpdateReq req);

    /** 查看他人主页（不含 publish 计数之外的隐私字段） */
    UserVO getPublic(Long userId);
}
