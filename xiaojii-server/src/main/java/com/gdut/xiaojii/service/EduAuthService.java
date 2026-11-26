package com.gdut.xiaojii.service;

import com.gdut.xiaojii.dto.req.EduLoginReq;
import com.gdut.xiaojii.dto.vo.EduCaptchaVO;
import com.gdut.xiaojii.dto.vo.EduStatusVO;

/**
 * 教务登录服务。
 *
 * <p>账号密码只在内存里用于一次 AES 加密提交；落库的是 AES-GCM 密文。
 *
 * @author 校集后端
 */
public interface EduAuthService {

    // 未使用：登录走 SSO 后不需要验证码
    EduCaptchaVO captcha();

    /** 教务登录：校验验证码 → 提交账号密码 → 保存会话（可选保存账号 30 天） */
    EduStatusVO login(EduLoginReq req);

    /** 退出教务：删除会话和保存的账号 */
    void logout();

    /** 当前登录状态 */
    EduStatusVO status();

    /**
     * 用已保存的加密凭证自动重登（Cookie 失效时由数据层调用）。
     *
     * 返回：true = 重登成功，新 Cookie 已写入会话；false = 没有凭证 / 已过期 / 重登失败。
     * 说明：本方法不抛异常 —— 调用方（数据层）拿到 false 后自己决定报什么错。
     *   为什么不抛：自动重登是"尽力而为"的补救，失败不该盖掉原始错误。
     */
    boolean refreshSession();
}
