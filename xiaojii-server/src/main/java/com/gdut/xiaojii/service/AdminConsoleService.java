package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.AdminOverviewVO;
import com.gdut.xiaojii.dto.vo.AdminUserItemVO;

/**
 * 管理端控制台：数据概览 + 用户管理。
 *
 * @author 校集后端
 */
public interface AdminConsoleService {

    /** 首页概览统计 */
    AdminOverviewVO overview();

    /**
     * 用户列表
     *
     * @param keyword      昵称模糊搜索
     * @param status       账号状态 1正常/2禁言/3封禁，null 全部
     * @param verifyStatus 认证状态，null 全部
     */
    PageResult<AdminUserItemVO> userList(String keyword, Integer status, Integer verifyStatus, int page, int size);

    /** 用户详情 */
    AdminUserItemVO userDetail(Long userId);

    /**
     * 修改账号状态
     *
     * @param status 1正常 / 2禁言 / 3封禁
     */
    void changeUserStatus(Long userId, Integer status, String reason, Long adminId);

    /**
     * 调整信用分
     *
     * @param delta  正数加分、负数扣分
     * @param reason 变动原因（写审计日志）
     */
    void adjustCredit(Long userId, Integer delta, String reason, Long adminId);
}
