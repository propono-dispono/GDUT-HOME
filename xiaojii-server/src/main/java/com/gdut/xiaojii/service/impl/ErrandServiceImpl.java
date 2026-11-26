package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.enums.BizType;
import com.gdut.xiaojii.common.enums.ErrandTakeStatus;
import com.gdut.xiaojii.common.enums.PostStatus;
import com.gdut.xiaojii.dto.vo.ErrandTakeVO;
import com.gdut.xiaojii.entity.ErrandTake;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.entity.PostErrand;
import com.gdut.xiaojii.mapper.ErrandTakeMapper;
import com.gdut.xiaojii.mapper.PostErrandMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import com.gdut.xiaojii.service.ErrandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 跑腿接单实现。
 *
 * 【一句话】这一整个类的核心只有一句话：把"能不能接"的判断写进 SQL 的 WHERE 里，
 * 用影响行数当结果 —— 谁先改成功谁就是接单人，一个 Java 锁都不用。
 *
 * 【为什么全程不用 synchronized / Redis 锁】
 * 接单的冲突对象是**一个帖子的 status 这一行**，判定只依赖这一行的当前值。
 * 这种"单行条件判断"用一条 UPDATE 就能表达完整语义（判断和写入是同一条语句，中间没有窗口），
 * 加锁反而更慢：synchronized 在 Spring 单例下会把全站接单串行化，
 * Redis 锁每次多两次网络往返，还要处理锁超时和续期。
 *
 * 【本项目所有写操作的并发手段，就三样】
 *   ① 条件更新 CAS（WHERE 里带状态）—— 抢单、状态跃迁、双释放
 *   ② 唯一索引 + catch DuplicateKeyException —— 防止同一单出现两条进行中记录
 *   ③ @Transactional —— 保证"记录表 + 帖子表"两次写入要么都成功要么都回滚
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrandServiceImpl implements ErrandService {

    /** 取消原因：接单者主动取消 */
    private static final String REASON_TAKER_CANCEL = "TAKER_CANCEL";
    /** 取消原因：发布者撤回 */
    private static final String REASON_OWNER_REVOKE = "OWNER_REVOKE";

    /** 身份：接单人 */
    private static final String ROLE_TAKER = "TAKER";
    /** 身份：发布者 */
    private static final String ROLE_OWNER = "OWNER";
    /** 身份：路人 */
    private static final String ROLE_OTHER = "OTHER";

    private final PostMapper postMapper;
    private final PostErrandMapper errandMapper;
    private final ErrandTakeMapper takeMapper;

    /* ============================================================
     * 一、接单（本项目并发价值最高的一段）
     * ============================================================ */

    /**
     * 这一段是"抢单"，全类最关键的方法。
     *
     * 【完整预演：帖子 88，发布者用户 3，酬金 5 元，target 只有一个名额】
     *
     * 场景 A｜A(用户 7) 和 B(用户 9) 在 1 秒内同时点「我接」
     *   两个请求都先读到 post.status=1(ONLINE)，都通过前置校验
     *   A 的 UPDATE ... WHERE id=88 AND status=1  → 命中 1 行，status 变 3
     *   B 的 UPDATE ... WHERE id=88 AND status=1  → 命中 0 行（status 已是 3）
     *   → A 插入接单记录成功，返回 code=0
     *   → B 进入失败判定，报 {code:1101,"手慢了，这单已被接走"}
     *   ✅ 全程第 88 行这一行被 InnoDB 加了排他锁，B 的 UPDATE 会等 A 提交后才执行
     *
     * 场景 B｜用户 7 自己手抖双击
     *   请求 1 走完，status=3，记录 active_post_id=88
     *   请求 2 进来：开头的"查我有没有进行中记录"命中 → 直接 1102
     *   → 用户看到 {code:1102,"你已经接过这单了"}，而不是"被别人抢走"
     *
     * 场景 C｜帖子已被发布者撤回（status=2 OFFLINE）
     *   UPDATE 命中 0 行 → 失败判定读到 status=2 → 1100"跑腿单不存在或已结束"
     *
     * 场景 D｜帖子已过 deadline
     *   前置的 deadline 校验直接拦掉 → 1100，连 UPDATE 都不发
     *
     * @param postId 跑腿帖 id
     * @return 新建的接单记录（含前端要用的按钮位）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErrandTakeVO take(Long postId) {
        Long userId = UserContext.requireUserId();

        // 第 1 步：帖子必须存在，且是跑腿业务线
        // 模拟数据：postId=88, bizType="ERRAND" ✅；postId=77 是二手 → 抛 1100
        Post p = postMapper.selectById(postId);
        if (p == null || !BizType.ERRAND.name().equals(p.getBizType())) {
            throw BizException.of(ResultCode.ERRAND_NOT_FOUND);
        }

        // 第 2 步：不能接自己的单
        // 模拟数据：post.userId=3，当前登录 userId=3 → 抛 1103
        if (Objects.equals(p.getUserId(), userId)) {
            throw BizException.of(ResultCode.ERRAND_TAKE_SELF);
        }

        // 第 3 步：★ 先查"我自己在这单上有没有进行中的记录"
        //
        // 【为什么这一步要放在 CAS 之前】
        // 因为它解决的是"同一个人重复提交"（S2），而 CAS 只能回答"是不是被别人抢了"。
        // 放在前面还有一个原因：此时事务刚开始，读到的快照是最新的，
        // 能看见上一次请求已经提交的记录。若放到 CAS 失败之后再查，
        // 受 REPEATABLE READ 快照读影响，可能读不到刚提交的那条（见第 5 步注释）。
        //
        // 模拟数据：用户 7 之前接过这单且还没取消 → 查到 1 条 → 抛 1102
        ErrandTake mine = takeMapper.selectOne(Wrappers.<ErrandTake>lambdaQuery()
                .eq(ErrandTake::getPostId, postId)
                .eq(ErrandTake::getTakerId, userId)
                .isNotNull(ErrandTake::getActivePostId));
        if (mine != null) {
            throw BizException.of(ResultCode.ERRAND_ALREADY_TAKEN);
        }

        // 第 4 步：帖子必须在"展示中"，并校验没超过最晚送达时间
        //
        // 【⚠️ 这里不能直接抛 1100】status 不是 ONLINE 有两种完全不同的原因：
        //   · 被别人接了（status=3 DEALT）→ 用户该看到"手慢了"（1101）
        //   · 已下架/已关闭（2 / 4）      → 用户该看到"这单已结束"（1100）
        // 两种都笼统报"已结束"，第二个人会以为自己点错了，而不是被别人抢走。
        // 所以这里交给 classifyTakeFailure 去分辨。
        // 模拟数据：status=1 → 继续往下抢；status=3 → 1101；status=2 → 1100
        if (!Integer.valueOf(PostStatus.ONLINE.getCode()).equals(p.getStatus())) {
            throw classifyTakeFailure(postId, userId);
        }
        PostErrand errand = errandMapper.selectById(postId);
        if (errand == null) {
            // 数据不一致：主表有、扩展表没有。正常不会发生（saveExt 同事务写入）
            log.warn("[跑腿] 扩展表缺失 postId={}", postId);
            throw BizException.of(ResultCode.ERRAND_NOT_FOUND);
        }
        // 模拟数据：deadline=2026-11-15 12:00，现在 13:30 → 已过期，抛 1100
        // 【为什么必须在 SQL 之前拦】过期判断依赖的是"读到的 deadline"，没法写进 UPDATE 的 WHERE
        //   （deadline 在另一张表）。所以这里确实存在"判完到 UPDATE 之间的窗口"：
        //   窗口内刚好过期 → 接了也只会由定时任务释放，不会产生超卖。可接受。
        if (errand.getDeadline() != null && errand.getDeadline().isBefore(LocalDateTime.now())) {
            throw BizException.of(ResultCode.ERRAND_NOT_FOUND, "该跑腿单已超过最晚送达时间");
        }

        // 第 5 步：★★ 核心 —— 条件更新抢单
        //
        // 【写法】UPDATE post SET status=3 WHERE id=? AND status=1
        // 【为什么】把"还能不能接"这个判断放进 WHERE，和写入合成一条语句 ——
        //   判断和修改之间没有时间窗口，这是"不用锁也能防超卖"的根本原因。
        // 【代价】失败时拿不到原因（只有 rows=0），必须再读一次才能给准确文案，
        //   而"再读一次"在 REPEATABLE READ 下有坑，见第 6 步。
        // 【另一种】先 select 判断再 update —— 两个并发请求都会读到 status=1，
        //   然后都去 update，第二个把第一个覆盖掉，两个人同时"接单成功"。
        //
        // 模拟数据：postId=88, status 当前 1 → 命中 1 行；已被别人改成 3 → 命中 0 行
        int rows = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .eq(Post::getStatus, PostStatus.ONLINE.getCode())//条件是必须要状态为1才能修改
                .set(Post::getStatus, PostStatus.DEALT.getCode())); //这里是把帖子的状态从1正在线上展示修改为3已被接单
        if (rows == 0) {
            // 抢输了 —— 但"输给谁"要判清楚，否则文案会误导用户
            throw classifyTakeFailure(postId, userId);
        }

        // 第 6 步：写接单记录
        //
        // 【为什么这里还要 catch DuplicateKeyException】CAS 已经挡住了绝大多数重复接单，
        //   但表上 uk_active_post 是数据库层的最后一道闸：万一将来有别的代码路径
        //   （比如管理员改派）绕过 CAS 直接写记录，唯一索引会让它直接失败，而不是留下脏数据。
        // 【模拟数据】正常情况下不会命中；若命中说明有并行路径已插入一条进行中记录
        //   → 抛 1101，事务回滚，帖子 status 恢复成 1
        ErrandTake record = new ErrandTake();
        record.setPostId(postId);
        record.setTakerId(userId);
        record.setStatus(ErrandTakeStatus.IN_PROGRESS.name());
        record.setTakenAt(LocalDateTime.now());
        // ★ 必须同时写 active_post_id，否则唯一索引不生效、这单永远"不算被占"
        record.setActivePostId(postId);
        record.setCreditDelta(0);
        try {
            takeMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.warn("[跑腿] 唯一索引兜底命中，说明有并行路径已接单 postId={} userId={}", postId, userId);
            throw BizException.of(ResultCode.ERRAND_TAKEN);
        }

        log.info("[跑腿] 接单成功 postId={} takerId={} takeId={}", postId, userId, record.getId());
        return toVO(record, p, userId);
    }

    /**
     * 这一段是"抢输了以后，搞清楚到底为什么输"。
     *
     * 【为什么单独抽成一个方法】接单失败有四种完全不同的原因，用户看到的话必须不同：
     *   被别人接走 → "手慢了"（他要去抢别的单）
     *   自己接过   → "你已经接过这单了"（他只需要去我的接单里看）
     *   已下架/结束 → "这单已结束"（别再试了）
     *
     * 【⚠️ 这里有一个非常容易踩的坑：REPEATABLE READ 的快照读】
     * MySQL InnoDB 默认隔离级别是 REPEATABLE READ。在同一个事务里：
     *   · 普通 SELECT 是**快照读**——读的是事务开始时（第一次读时）的那个版本；
     *   · UPDATE / SELECT ... FOR UPDATE 是**当前读**——读的是最新的已提交版本。
     * 所以如果这里直接 `selectById(postId)`，会读回 status=1 的旧快照，
     * 于是明明抢输了，却判断成"帖子还在展示中"，最后给出一个错误的兜底文案。
     *
     * 【写法】用 `.last("FOR UPDATE")` 强制当前读
     * 【为什么可以加锁】这条路径只在失败时走，拿到结果马上就要抛异常回滚，
     *   锁的持有时间是毫秒级，不会影响正常接单的吞吐。
     * 【代价】多一次加锁；且如果这一行正被别的事务长时间持有，这里会等锁
     *   （默认 innodb_lock_wait_timeout 50 秒，超时抛异常 → 500）。
     *   本项目接单事务极短，不会发生。
     *
     * 模拟数据：B 抢输后进来，FOR UPDATE 读到 status=3(DEALT)
     *   → 查进行中记录，takerId=7（A）≠ 9（B）→ 返回 1101"手慢了"
     *
     *   postid是帖子的id userid是该用户点击了拼单后的id
     */
    private BizException classifyTakeFailure(Long postId, Long userId) {
        // 当前读：绕过快照，看到别人刚提交的 status
        Post latest = postMapper.selectOne(Wrappers.<Post>lambdaQuery()
                .eq(Post::getId, postId)
                .last("FOR UPDATE"));

        if (latest == null) {
            return BizException.of(ResultCode.ERRAND_NOT_FOUND);
        }

        // 已下架 / 待审核 / 已关闭 → 都不是"被别人抢了"，而是"这单不能接了"
        int status = latest.getStatus() == null ? -1 : latest.getStatus();
        if (status != PostStatus.DEALT.getCode()) {
            return BizException.of(ResultCode.ERRAND_NOT_FOUND, "该跑腿单已结束或已下架");
        }

        // status=3 说明被接了，再看是不是我自己（极端情况：前面的 mine 查询没覆盖到）
        ErrandTake active = takeMapper.selectOne(Wrappers.<ErrandTake>lambdaQuery()
                .eq(ErrandTake::getPostId, postId)
                .isNotNull(ErrandTake::getActivePostId));
        if (active != null && Objects.equals(active.getTakerId(), userId)) {
            return BizException.of(ResultCode.ERRAND_ALREADY_TAKEN);
        }
        // 记录还没写进来（对方事务未提交）也算"被别人抢了"—— 对用户来说结论一样
        return BizException.of(ResultCode.ERRAND_TAKEN);
    }

    /* ============================================================
     * 二、取消 / 撤回（两条路径收敛到同一个终态）
     * ============================================================ */

    /**
     * 这一段是"接单人自己放弃这单"，帖子重新回到可接状态。
     *
     * 【完整预演：帖子 88 被用户 7 接了，7 发现东西太重不想跑了】
     *   第 1 步：查到 7 在这单上有一条 IN_PROGRESS 记录 ✅
     *   第 2 步：每单只能取消一次 —— 查"7 在 88 上取消过的条数"，0 条 → 允许
     *   第 3 步：UPDATE errand_take SET status='CANCELLED', active_post_id=NULL
     *            WHERE id=? AND status='IN_PROGRESS'  → 命中 1 行
     *   第 4 步：UPDATE post SET status=1 WHERE id=88 AND status=3 → 命中 1 行，帖子重新可接
     *   → 用户 9 刷新后就能接了
     *
     * 【并发场景 S6 双释放：用户点取消的同一毫秒，定时任务判定超时】
     *   两个路径的 UPDATE 都带 `WHERE status='IN_PROGRESS'` 或 `WHERE status=3`，
     *   只有一个能命中，另一个命中 0 行 → 报"状态已变化，请刷新"。
     *   不会出现"帖子被放出两次"或"信用分扣两次"。
     *
     * @param postId 跑腿帖 id
     * @return 取消后的记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErrandTakeVO cancel(Long postId) {
        Long userId = UserContext.requireUserId();

        // 找到"我在这单上的进行中记录"
        ErrandTake active = takeMapper.selectOne(Wrappers.<ErrandTake>lambdaQuery()
                .eq(ErrandTake::getPostId, postId)
                .eq(ErrandTake::getTakerId, userId)
                .isNotNull(ErrandTake::getActivePostId));
        if (active == null) {
            throw BizException.of(ResultCode.ERRAND_NOT_TAKER, "这单没有你进行中的接单记录");
        }

        // 已取件就不能取消了（设计决策 2：限 1 次且仅未取件时）
        // 模拟数据：status="PICKED" → 抛 1104；status="IN_PROGRESS" → 放行
        if (!ErrandTakeStatus.of(active.getStatus()).canCancel()) {
            throw BizException.of(ResultCode.ERRAND_STATUS_ILLEGAL, "已取件的单不能取消，请标记送达");
        }

        // 每单只能取消一次（防止用取消来反复占坑）
        // 模拟数据：用户 7 在帖子 88 上曾有 1 条 CANCELLED → 抛 1107
        Long canceledBefore = takeMapper.selectCount(Wrappers.<ErrandTake>lambdaQuery()
                .eq(ErrandTake::getPostId, postId)
                .eq(ErrandTake::getTakerId, userId)
                .eq(ErrandTake::getStatus, ErrandTakeStatus.CANCELLED.name()));
        if (canceledBefore != null && canceledBefore > 0) {
            throw BizException.of(ResultCode.ERRAND_CANCEL_LIMIT);
        }

        // 条件更新记录：只有还是 IN_PROGRESS 才能改，顺带释放 active 标记
        // 【为什么 active_post_id 必须置 NULL】不置，唯一索引会一直认为"这单还占着"，
        //   帖子就算回到 ONLINE 也再也没人能接（接单时 insert 会撞 uk_active_post）
        int rows = takeMapper.update(null, Wrappers.<ErrandTake>lambdaUpdate()
                .eq(ErrandTake::getId, active.getId())
                .eq(ErrandTake::getStatus, ErrandTakeStatus.IN_PROGRESS.name())
                .set(ErrandTake::getStatus, ErrandTakeStatus.CANCELLED.name())
                .set(ErrandTake::getActivePostId, null)
                .set(ErrandTake::getClosedAt, LocalDateTime.now())
                .set(ErrandTake::getCancelReason, REASON_TAKER_CANCEL));
        if (rows == 0) {
            // 走到这里说明有人在并发里改过这行（比如定时任务刚好把它置成 EXPIRED）
            throw BizException.of(ResultCode.ERRAND_STATUS_ILLEGAL, "这单状态已经变了，请刷新");
        }

        // 帖子放回可接状态：条件是"还是被锁着(DEALT)"
        // 【为什么不直接 setStatus(ONLINE)】那样会把已经完成的单也拉回可接
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .eq(Post::getStatus, PostStatus.DEALT.getCode())
                .set(Post::getStatus, PostStatus.ONLINE.getCode()));

        log.info("[跑腿] 接单人取消 postId={} takerId={}", postId, userId);
        return reloadVO(postId, userId);
    }

    /**
     * 这一段是"发布者不想做了，撤掉这单"，帖子直接下架、不再放出。
     *
     * 【和 cancel 的区别】cancel 之后帖子还能被别人接；revoke 之后帖子彻底下架。
     * 所以两条路径虽然都把记录置成 CANCELLED，但帖子状态去向不同：
     *   cancel → ONLINE；revoke → OFFLINE
     *
     * 模拟数据：帖子 88 发布者 3，被 7 接了，3 点撤回
     *   → post.status: 3 → 2(OFFLINE)
     *   → errand_take: IN_PROGRESS → CANCELLED，active_post_id 置 NULL
     *   → 用户 7 再打开详情页看到"已被发布者撤回"
     *
     * @param postId 跑腿帖 id
     * @return 撤回后的记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErrandTakeVO revoke(Long postId) {
        Long userId = UserContext.requireUserId();

        Post p = postMapper.selectById(postId);
        if (p == null || !BizType.ERRAND.name().equals(p.getBizType())) {
            throw BizException.of(ResultCode.ERRAND_NOT_FOUND);
        }
        // 模拟数据：post.userId=3，当前 userId=7 → 抛 1106
        if (!Objects.equals(p.getUserId(), userId)) {
            throw BizException.of(ResultCode.ERRAND_NOT_OWNER);
        }

        // 条件更新帖子：必须是"已被接"(DEALT) 才能撤回
        // 【为什么还没人接时不让走这个接口】那种情况应该走"下架帖子"的通用接口，
        //   两个入口做同一件事会让状态机出现两条不同的路径，日后难排查
        int rows = postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .eq(Post::getStatus, PostStatus.DEALT.getCode())
                .set(Post::getStatus, PostStatus.OFFLINE.getCode()));
        if (rows == 0) {
            throw BizException.of(ResultCode.ERRAND_STATUS_ILLEGAL, "当前没有进行中的接单，可直接下架帖子");
        }

        // 把进行中的记录一并取消（同事务，两步要么都成要么都回滚）
        takeMapper.update(null, Wrappers.<ErrandTake>lambdaUpdate()
                .eq(ErrandTake::getPostId, postId)
                .isNotNull(ErrandTake::getActivePostId)
                .set(ErrandTake::getStatus, ErrandTakeStatus.CANCELLED.name())
                .set(ErrandTake::getActivePostId, null)
                .set(ErrandTake::getClosedAt, LocalDateTime.now())
                .set(ErrandTake::getCancelReason, REASON_OWNER_REVOKE));

        log.info("[跑腿] 发布者撤回 postId={} ownerId={}", postId, userId);
        return reloadVO(postId, userId);
    }

    /* ============================================================
     * 三、履约推进：取件 → 送达 → 完成
     * ============================================================ */

    /**
     * 这一段是"确认取件"，接单人告诉系统东西拿到了。
     *
     * 【完整预演：帖子 88 被 7 接了，7 到了菜鸟驿站】
     *   UPDATE errand_take SET status='PICKED', picked_at=now
     *    WHERE post_id=88 AND taker_id=7 AND status='IN_PROGRESS'
     *   → 命中 1 行：状态推进成功
     *   → 命中 0 行：可能已经取过件（PICKED）、已取消、或不是他的单 → 报 1104
     *
     * 【并发场景 S7：接单者点"已取件"的同时点"取消"】
     *   两个 UPDATE 都带 `WHERE status='IN_PROGRESS'`，只有一个能命中 1 行。
     *   命中 0 行的那个报"状态已变化"，绝不会出现"既 PICKED 又 CANCELLED"。
     *
     * @param postId 跑腿帖 id
     * @return 更新后的记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErrandTakeVO pick(Long postId) {
        Long userId = UserContext.requireUserId();
        advance(postId, userId, ErrandTakeStatus.IN_PROGRESS, ErrandTakeStatus.PICKED,
                "当前不是「已接单未取件」状态");
        log.info("[跑腿] 确认取件 postId={} takerId={}", postId, userId);
        return reloadVO(postId, userId);
    }

    /**
     * 这一段是"标记送达"，东西已经放到对方手里了。
     *
     * 【为什么送达不直接算完成】设计决策 4：要发布者确认，避免接单人自己点"完成"刷单。
     * 发布者一直不确认的话，由超时任务自动完成（阶段四）。
     *
     * 模拟数据：当前 status='PICKED' → 改成 'DELIVERED'，命中 1 行
     *   若当前还是 'IN_PROGRESS'（没点取件就想点送达）→ 命中 0 行 → 报 1104
     *
     * @param postId 跑腿帖 id
     * @return 更新后的记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErrandTakeVO deliver(Long postId) {
        Long userId = UserContext.requireUserId();
        advance(postId, userId, ErrandTakeStatus.PICKED, ErrandTakeStatus.DELIVERED,
                "请先确认取件，再标记送达");
        log.info("[跑腿] 标记送达 postId={} takerId={}", postId, userId);
        return reloadVO(postId, userId);
    }

    /**
     * 这一段是"发布者确认收到"，这单正式完成，帖子关闭。
     *
     * 【完整预演：帖子 88 已送达，发布者 3 点确认】
     *   第 1 步：UPDATE errand_take SET status='COMPLETED', completed_at=now, active_post_id=NULL
     *            WHERE post_id=88 AND status='DELIVERED' AND active_post_id IS NOT NULL
     *            → 命中 1 行
     *   第 2 步：UPDATE post SET status=4(CLOSED) WHERE id=88 AND status=3(DEALT) → 命中 1 行
     *   → 帖子从列表消失，接单人的"我的接单"里显示已完成
     *
     * 【并发场景 S8：发布者连点两次"确认完成"】
     *   第一次命中 1 行；第二次因为 status 已是 COMPLETED，命中 0 行。
     *   这里刻意**不报错**而是返回成功 —— 重复确认是幂等的，
     *   用户连点不该看到红色报错。但也不能重复发通知/加信用分，所以判断必须准。
     *   ⚠️ 实现上通过"命中 0 行时再查一次，若已完成就当成功返回"来做到。
     *
     * @param postId 跑腿帖 id
     * @return 更新后的记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ErrandTakeVO confirm(Long postId) {
        Long userId = UserContext.requireUserId();

        Post p = postMapper.selectById(postId);
        if (p == null || !BizType.ERRAND.name().equals(p.getBizType())) {
            throw BizException.of(ResultCode.ERRAND_NOT_FOUND);
        }
        // 只有发布者能确认完成
        if (!Objects.equals(p.getUserId(), userId)) {
            throw BizException.of(ResultCode.ERRAND_NOT_OWNER);
        }

        // 条件更新：必须是"已送达"
        int rows = takeMapper.update(null, Wrappers.<ErrandTake>lambdaUpdate()
                .eq(ErrandTake::getPostId, postId)
                .isNotNull(ErrandTake::getActivePostId)
                .eq(ErrandTake::getStatus, ErrandTakeStatus.DELIVERED.name())
                .set(ErrandTake::getStatus, ErrandTakeStatus.COMPLETED.name())
                .set(ErrandTake::getActivePostId, null)
                .set(ErrandTake::getCompletedAt, LocalDateTime.now()));
        if (rows == 0) {
            // 幂等处理：连点第二次时状态已是 COMPLETED，不该报错。
            //
            // 【⚠️ 必须用 FOR UPDATE 当前读】实测踩到的坑：
            //   初版写成普通 selectOne，并发点 3 次确认只成功 1 次、另外 2 次报错。
            //   原因是：本事务开头已经 selectById 读过 post，快照就定在那里了；
            //   别人的 UPDATE 提交后，普通 SELECT 在 REPEATABLE READ 下**看不到**，
            //   于是读到 status 还是 DELIVERED，判断成"还没送达"→ 误报。
            //   加 FOR UPDATE 变成当前读，才能看到刚提交的 COMPLETED。
            // 【代价】这一行会短暂加锁；但只在重复确认这条路径上，毫秒级释放。
            ErrandTake latest = takeMapper.selectOne(Wrappers.<ErrandTake>lambdaQuery()
                    .eq(ErrandTake::getPostId, postId)
                    .orderByDesc(ErrandTake::getId)
                    .last("LIMIT 1 FOR UPDATE"));
            if (latest != null && ErrandTakeStatus.COMPLETED.name().equals(latest.getStatus())) {
                return toVO(latest, p, userId);
            }
            throw BizException.of(ResultCode.ERRAND_STATUS_ILLEGAL, "接单人还没标记送达，暂时不能确认完成");
        }

        // 帖子关闭：条件仍是 DEALT，防止把已被撤回(2)/已关闭(4) 的帖子再写一遍
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .eq(Post::getStatus, PostStatus.DEALT.getCode())
                .set(Post::getStatus, PostStatus.CLOSED.getCode()));

        log.info("[跑腿] 发布者确认完成 postId={} ownerId={}", postId, userId);
        return reloadVO(postId, userId);
    }

    /**
     * 这一段是"把状态往前推一格"的公共实现，pick / deliver 共用。
     *
     * 【为什么抽出来】两个方法除了"从哪个状态到哪个状态"不同，其余完全一样：
     *   带 taker_id 条件（防止别人操作我的单）、带 status 条件（防止并发重复推进）、
     *   命中 0 行就报同一个错。复制两遍迟早会改漏一处。
     *
     * 【关键：taker_id 也写在 WHERE 里】这样"不属于我的单"和"状态不对"
     *   在数据库层被同一个 UPDATE 一起挡住，不需要先查一次再判断（少一个并发窗口）。
     *
     * 模拟数据：from=IN_PROGRESS, to=PICKED
     *   postId=88, takerId=7 的记录当前 IN_PROGRESS → 命中 1 行
     *   takerId=9（路人）调用 → 命中 0 行 → 抛 1104（不知道文案，但结果正确）
     */
    private void advance(Long postId, Long userId,
                         ErrandTakeStatus from, ErrandTakeStatus to, String failMsg) {
        var wrapper = Wrappers.<ErrandTake>lambdaUpdate()
                .eq(ErrandTake::getPostId, postId)
                .eq(ErrandTake::getTakerId, userId)
                .eq(ErrandTake::getStatus, from.name())
                .set(ErrandTake::getStatus, to.name());
        // 取件/送达各自记自己的时间戳，方便将来算耗时
        if (to == ErrandTakeStatus.PICKED) {
            wrapper.set(ErrandTake::getPickedAt, LocalDateTime.now());
        } else if (to == ErrandTakeStatus.DELIVERED) {
            wrapper.set(ErrandTake::getDeliveredAt, LocalDateTime.now());
        }
        int rows = takeMapper.update(null, wrapper);
        if (rows == 0) {
            throw BizException.of(ResultCode.ERRAND_STATUS_ILLEGAL, failMsg);
        }
    }

    /* ============================================================
     * 四、查询
     * ============================================================ */

    /**
     * 这一段是"这单现在是谁在跑、走到哪了"，详情页进来就调它。
     *
     * 【为什么没人接时返回 null 而不是抛异常】"没人接"是最常见、最正常的初始状态，
     * 抛异常会让前端把正常情况当错误处理（弹 toast、打日志告警）。
     * 返回 null，前端判空显示"暂无人接单，点我接单"即可。
     *
     * 模拟数据：帖子 88 刚发布、没人接 → {"code":0,"data":null}
     *          帖子 88 被 7 接了 → {"code":0,"data":{"takerId":7,"status":"IN_PROGRESS",...}}
     */
    @Override
    public ErrandTakeVO current(Long postId) {
        Long userId = UserContext.getUserId();

        ErrandTake active = takeMapper.selectOne(Wrappers.<ErrandTake>lambdaQuery()
                .eq(ErrandTake::getPostId, postId)
                .isNotNull(ErrandTake::getActivePostId)
                .last("LIMIT 1"));
        if (active == null) {
            return null;
        }
        Post p = postMapper.selectById(postId);
        return toVO(active, p, userId);
    }

    /**
     * 这一段是"我接过的单"，含已完成和已取消的历史。
     *
     * 【为什么要返回历史而不只是进行中】跑腿员需要看到"我这周跑了多少单、
     * 有几单被取消了"，这是他判断自己该不该多接的依据。
     *
     * 模拟数据：用户 7 接过 3 单（1 完成 / 1 进行中 / 1 取消）
     *   → total=3，按接单时间倒序，最新的在最前
     */
    @Override
    public PageResult<ErrandTakeVO> mine(int page, int size) {
        Long userId = UserContext.requireUserId();
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));

        Page<ErrandTake> query = new Page<>(p, s);
        Page<ErrandTake> result = takeMapper.selectPage(query,
                Wrappers.<ErrandTake>lambdaQuery()
                        .eq(ErrandTake::getTakerId, userId)
                        .orderByDesc(ErrandTake::getTakenAt));

        List<ErrandTake> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            return PageResult.empty(p, s);
        }

        // 批量取标题：一次 IN 查询代替 N 次单查（列表 20 条就是 20 次往返 vs 1 次）
        List<Long> postIds = records.stream().map(ErrandTake::getPostId).collect(Collectors.toList());
        List<Post> posts = postMapper.selectBatchIds(postIds);
        // 转成 Map 便于按 id 取；post 可能被物理删除（逻辑删除后 selectBatchIds 查不到），所以要判空
        var postMap = posts.stream().collect(Collectors.toMap(Post::getId, x -> x, (a, b) -> a));

        List<ErrandTakeVO> list = records.stream()
                .map(r -> toVO(r, postMap.get(r.getPostId()), userId))
                .collect(Collectors.toList());
        return PageResult.of(result, list);
    }

    /* ============================================================
     * 五、内部工具
     * ============================================================ */

    /**
     * 这一段是"改完数据后把最新状态读出来返回给前端"。
     *
     * 【为什么要重新读一次，而不是在内存里改完直接返回】
     * 条件更新之后，数据库里的值才是真相（可能被并发改过、可能有默认值填充）。
     * 用内存对象返回会出现"接口说成功了，但刷新页面状态不一样"的诡异现象。
     *
     * 【为什么用 LIMIT 1 而不是 selectOne 直接查】理论上进行中记录只有一条，
     * 但取消/完成后 active_post_id 是 NULL，按它查不到；
     * 这里要看的是"这单最近一条记录"，所以按 id 倒序取第一条。
     */
    private ErrandTakeVO reloadVO(Long postId, Long currentUserId) {
        ErrandTake latest = takeMapper.selectOne(Wrappers.<ErrandTake>lambdaQuery()
                .eq(ErrandTake::getPostId, postId)
                .orderByDesc(ErrandTake::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return null;
        }
        Post p = postMapper.selectById(postId);
        return toVO(latest, p, currentUserId);
    }

    /**
     * 这一段是"把数据库里的一行拼成前端要的 VO"，顺带算好按钮位。
     *
     * 【为什么按钮位在后端算】状态机规则只有一份，前端再实现一遍就会出现
     * "后端认为不能取消、前端还显示取消按钮"的两套真相。
     *
     * 模拟数据：记录 status=PICKED，takerId=7，post.userId=3，当前登录 7
     *   → myRole=TAKER，canCancel=false（已取件），canPick=false，canDeliver=true
     *   换成当前登录 3 → myRole=OWNER，canConfirm=false（还没送达），canRevoke=true
     */
    private ErrandTakeVO toVO(ErrandTake t, Post p, Long currentUserId) {
        ErrandTakeVO vo = new ErrandTakeVO();
        vo.setPostId(t.getPostId());
        vo.setPostTitle(p == null ? null : p.getTitle());
        vo.setTakerId(t.getTakerId());

        ErrandTakeStatus st = ErrandTakeStatus.of(t.getStatus());
        vo.setStatus(st.name());
        vo.setStatusLabel(st.getLabel());

        vo.setTakenAt(t.getTakenAt());
        vo.setPickedAt(t.getPickedAt());
        vo.setDeliveredAt(t.getDeliveredAt());
        vo.setCompletedAt(t.getCompletedAt());
        vo.setClosedAt(t.getClosedAt());

        Long ownerId = p == null ? null : p.getUserId();
        boolean isTaker = currentUserId != null && Objects.equals(currentUserId, t.getTakerId());
        boolean isOwner = currentUserId != null && Objects.equals(currentUserId, ownerId);
        vo.setMyRole(isTaker ? ROLE_TAKER : (isOwner ? ROLE_OWNER : ROLE_OTHER));

        // 终态之后谁都不能再操作，先挡一层，下面的布尔判断都依赖它
        boolean active = t.getActivePostId() != null && !st.isFinal();

        vo.setCanCancel(active && isTaker && st.canCancel());
        vo.setCanPick(active && isTaker && st == ErrandTakeStatus.IN_PROGRESS);
        vo.setCanDeliver(active && isTaker && st == ErrandTakeStatus.PICKED);
        vo.setCanConfirm(active && isOwner && st == ErrandTakeStatus.DELIVERED);
        vo.setCanRevoke(active && isOwner);
        return vo;
    }
}
