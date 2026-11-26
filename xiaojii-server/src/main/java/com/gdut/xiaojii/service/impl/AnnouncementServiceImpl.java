package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.dto.req.AnnouncementSaveReq;
import com.gdut.xiaojii.entity.Announcement;
import com.gdut.xiaojii.mapper.AnnouncementMapper;
import com.gdut.xiaojii.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公告实现。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementMapper announcementMapper;

    @Override
    /** 当前 */
    public Announcement current() {
        // 只取「已上线」且「发布时间已到（或未设置）」的最新一条
        List<Announcement> list = announcementMapper.selectList(
                Wrappers.<Announcement>lambdaQuery()
                        .eq(Announcement::getStatus, Announcement.STATUS_ONLINE)
                        .and(w -> w.isNull(Announcement::getPublishAt)
                                .or().le(Announcement::getPublishAt, LocalDateTime.now()))
                        .orderByDesc(Announcement::getId)
                        .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public PageResult<Announcement> adminList(Integer status, int page, int size) {
        IPage<Announcement> p = announcementMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<Announcement>lambdaQuery()
                        .eq(status != null, Announcement::getStatus, status)
                        .orderByDesc(Announcement::getId));
        List<Announcement> records = p.getRecords();
        return new PageResult<>(records == null ? new ArrayList<>() : records,
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    /** 保存 */
    public Long save(AnnouncementSaveReq req) {
        Announcement a = new Announcement();
        a.setTitle(req.getTitle().trim());
        a.setContent(req.getContent().trim());
        a.setType(req.getType() == null || req.getType().isBlank()
                ? Announcement.TYPE_POPUP : req.getType());
        a.setStatus(req.getStatus() == null ? Announcement.STATUS_ONLINE : req.getStatus());
        a.setUpdatedAt(LocalDateTime.now());

        if (req.getId() == null) {
            a.setPublishAt(LocalDateTime.now());
            announcementMapper.insert(a);
            log.info("公告已新建 id={} title={}", a.getId(), a.getTitle());
            return a.getId();
        }
        Announcement old = announcementMapper.selectById(req.getId());
        if (old == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "公告不存在");
        }
        a.setId(req.getId());
        announcementMapper.updateById(a);
        log.info("公告已更新 id={} title={}", a.getId(), a.getTitle());
        return a.getId();
    }

    @Override
    public void toggle(Long id, Integer status) {
        if (status == null || (status != Announcement.STATUS_ONLINE && status != Announcement.STATUS_OFFLINE)) {
            throw BizException.of(ResultCode.PARAM_ERROR, "状态只能是 0 下线 / 1 上线");
        }
        int rows = announcementMapper.update(null, Wrappers.<Announcement>lambdaUpdate()
                .eq(Announcement::getId, id)
                .set(Announcement::getStatus, status)
                .set(Announcement::getUpdatedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ResultCode.NOT_FOUND, "公告不存在");
        }
    }
}
