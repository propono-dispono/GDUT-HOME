package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.req.AnnouncementSaveReq;
import com.gdut.xiaojii.entity.Announcement;

/**
 * 系统公告。
 *
 * @author 校集后端
 */
public interface AnnouncementService {

    /** 小程序端：取当前生效的最新一条公告（没有返回 null） */
    Announcement current();

    /** 管理端：公告列表 */
    PageResult<Announcement> adminList(Integer status, int page, int size);

    /** 新建或编辑 */
    Long save(AnnouncementSaveReq req);

    /** 上线 / 下线 */
    void toggle(Long id, Integer status);
}
