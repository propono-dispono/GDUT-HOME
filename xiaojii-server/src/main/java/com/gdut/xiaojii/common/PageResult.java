package com.gdut.xiaojii.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一分页返回结构。
 *
 * <p><b>为什么不直接把 MyBatis-Plus 的 {@code IPage} 返回给前端：</b>
 * IPage 里有 {@code orders}、{@code optimizeCountSql}、{@code searchCount}、
 * {@code countId}、{@code maxLimit} 等纯内部字段，暴露出去既冗余又泄露实现细节。
 * 前端真正需要的只有"这一页的列表 + 总数 + 还有没有下一页"。
 *
 * <p>约定：页码从 <b>1</b> 开始；{@code size} 默认 10、上限 100（MP 分页插件会截断）。
 *
 * @author 校集后端
 */
@Data
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> list;

    /** 总记录数 */
    private long total;

    /** 当前页码，从 1 开始 */
    private long page;

    /** 每页条数 */
    private long size;

    /** 是否还有下一页，前端做无限滚动时用它判断，省得自己算 */
    private boolean hasMore;

    public PageResult() {
    }

    public PageResult(List<T> list, long total, long page, long size) {
        this.list = list == null ? new ArrayList<>() : list;
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasMore = page * size < total;
    }

    /** 直接用 MP 的分页对象构造（最常用） */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 分页对象 + 手动转换后的 VO 列表。
     * 典型用法：查出来是实体分页，返回前统一转 VO，避免把 entity 的敏感字段带出去。
     */
    public static <T> PageResult<T> of(IPage<?> page, List<T> voList) {
        return new PageResult<>(voList, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 内存分页（数据量小、已在内存里筛过的场景，例如分类计数） */
    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(new ArrayList<>(), 0, page, size);
    }
}
