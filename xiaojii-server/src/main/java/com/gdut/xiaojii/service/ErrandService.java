package com.gdut.xiaojii.service;

import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.dto.vo.ErrandTakeVO;

/**
 * 跑腿接单（履约状态机）。
 *
 * 【一句话】让一条跑腿帖在某一时刻只属于一个跑腿员，并记录它走到哪一步。
 *
 * 【设计取舍：为什么不做成 PostService 里的几个方法】
 * 接单是一条完整的履约链路（接单 → 取件 → 送达 → 完成 / 取消 / 超时），
 * 有自己的一张表和一套状态机。塞进已经 1000 多行的 PostServiceImpl 里，
 * 会和"发帖/列表/审核/拼单"混在一起，将来改跑腿要先在一堆无关代码里找。
 * 拆出来的代价是跨 Service 调用（要读 post 状态），但依赖方向是单向的：
 * ErrandService → PostMapper（只读），不会和 PostService 形成循环依赖。
 *
 * 【所有方法的并发设计遵循一条原则】
 * 凡是"同一时刻只能有一个人成功"的判断，一律写成 SQL 的 WHERE 条件，
 * 用"影响行数是否为 0"当失败信号 —— 不在 Java 里先查再判，也不用任何锁。
 * 具体每个方法挡的是哪个场景，写在实现类的注释里。
 *
 * @author 校集后端
 */
public interface ErrandService {

    /**
     * 接单（S1 多人抢同一单 / S2 同一人重复提交 / S3 与撤回竞争 / S4 与过期竞争）。
     *
     * @param postId 跑腿帖 id
     * @return 刚创建的接单记录
     */
    ErrandTakeVO take(Long postId);

    /**
     * 接单者主动取消（仅进行中可取消，每单限一次）。
     *
     * @param postId 跑腿帖 id
     * @return 取消后的记录
     */
    ErrandTakeVO cancel(Long postId);

    /**
     * 发布者撤回（帖子转 OFFLINE，不再放出）。
     *
     * 【和 cancel 的区别】cancel 是接单人放弃、帖子重新可接；
     * revoke 是发布人自己不想做了、帖子直接下架。终点不同，所以不能合成一个接口。
     *
     * @param postId 跑腿帖 id
     * @return 撤回后的记录
     */
    ErrandTakeVO revoke(Long postId);

    /**
     * 接单者确认已取件（进行中 → 已取件）。
     *
     * @param postId 跑腿帖 id
     * @return 更新后的记录
     */
    ErrandTakeVO pick(Long postId);

    /**
     * 接单者标记已送达（已取件 → 已送达），等发布者确认。
     *
     * @param postId 跑腿帖 id
     * @return 更新后的记录
     */
    ErrandTakeVO deliver(Long postId);

    /**
     * 发布者确认完成（已送达 → 已完成，帖子转 CLOSED）。
     *
     * @param postId 跑腿帖 id
     * @return 更新后的记录
     */
    ErrandTakeVO confirm(Long postId);

    /**
     * 查当前进行中的接单记录（详情页用）。
     *
     * @param postId 跑腿帖 id
     * @return 没有进行中的记录时返回 null（不是抛异常 —— "没人接"是正常状态）
     */
    ErrandTakeVO current(Long postId);

    /**
     * 我接过的单（分页，含已完成/已取消的历史）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 分页结果
     */
    PageResult<ErrandTakeVO> mine(int page, int size);
}
