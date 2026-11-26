package com.gdut.xiaojii.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.PageResult;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.enums.AuditStatus;
import com.gdut.xiaojii.common.enums.BizType;
import com.gdut.xiaojii.common.enums.CategoryEnum;
import com.gdut.xiaojii.common.enums.PostStatus;
import com.gdut.xiaojii.common.lock.DistributedLock;
import com.gdut.xiaojii.common.util.MoneyUtil;
import com.gdut.xiaojii.common.util.RelativeTimeUtil;
import com.gdut.xiaojii.common.util.TextUtil;
import com.gdut.xiaojii.config.UploadProperties;
import com.gdut.xiaojii.dto.req.PostCreateReq;
import com.gdut.xiaojii.dto.req.PostQueryReq;
import com.gdut.xiaojii.dto.vo.CategoryVO;
import com.gdut.xiaojii.dto.vo.ExtFieldVO;
import com.gdut.xiaojii.dto.vo.GroupbuyMemberVO;
import com.gdut.xiaojii.dto.vo.PostDetailVO;
import com.gdut.xiaojii.dto.vo.PostItemVO;
import com.gdut.xiaojii.dto.vo.UserVO;
import com.gdut.xiaojii.entity.Post;
import com.gdut.xiaojii.entity.GroupbuyMember;
import com.gdut.xiaojii.entity.Notice;
import com.gdut.xiaojii.event.NoticeEventPublisher;
import com.gdut.xiaojii.entity.PostErrand;
import com.gdut.xiaojii.entity.PostGroupbuy;
import com.gdut.xiaojii.entity.PostLost;
import com.gdut.xiaojii.entity.PostRent;
import com.gdut.xiaojii.entity.PostSkill;
import com.gdut.xiaojii.entity.User;
import com.gdut.xiaojii.mapper.PostErrandMapper;
import com.gdut.xiaojii.mapper.GroupbuyMemberMapper;
import com.gdut.xiaojii.mapper.PostGroupbuyMapper;
import com.gdut.xiaojii.mapper.PostLostMapper;
import com.gdut.xiaojii.mapper.PostMapper;
import com.gdut.xiaojii.mapper.PostRentMapper;
import com.gdut.xiaojii.mapper.PostSkillMapper;
import com.gdut.xiaojii.mapper.UserMapper;
import com.gdut.xiaojii.service.PostService;
import com.gdut.xiaojii.support.audit.ContentCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 帖子服务实现（六条业务线的全部业务规则都在这里）。
 *
 * 【几个关键设计】
 *   ① 发布门槛：必须校园认证通过（状态=2）；信用分 <60 直接拒绝
 *   ② 审核策略：信用分 ≥80 且历史发布 ≥3 条 → 先发后审（直接展示）；
 *      否则进人工队列（status=0 待审）。命中敏感词直接拒绝，不入库
 *   ③ 列表不做 N+1：一页 10 条最多 4 次批量查询（用户、失物、拼单），
 *      而不是每条都查一次扩展表
 *   ④ VO 字段名对齐前端：biz/desc/cover/meta/priceUnit 这些名字是前端契约，不许改
 *
 * 【这个类的结构（按调用链分五块）】
 *   A. 发布：create → checkBizRequiredFields → resolvePrice/resolveExpireAt → saveExt
 *   B. 列表：feed/mine → applyFilters/applySort → assembleItems（批量拼 VO）
 *   C. 详情：detail → buildExtFields（按业务线拼扩展字段）
 *   D. 状态操作：close / delete / joinGroupbuy / leaveGroupbuy
 *   E. 文案与工具：priceUnitOf / statusText / noticeOf / ctaOf / toJson / fromJson …
 *
 * @author 校集后端
 */
// @Slf4j：生成 log 字段，发布/拼单等关键动作要留痕
@Slf4j
// @Service：注册成 Bean，Controller 注入的是 PostService 接口
@Service
// @RequiredArgsConstructor：10 个 final 字段的构造器注入。
// 【为什么这么多 Mapper】六条业务线各有扩展表，加上 groupbuy_member、user，
//   一共 8 个 Mapper + 1 个审核服务 + 1 个上传配置 = 10 个依赖
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    // 主表：post（六条线共同的部分）
    private final PostMapper postMapper;
    // 跑腿扩展表：post_errand（取件地/送达地/截止/垫付）
    private final PostErrandMapper errandMapper;
    // 失物扩展表：post_lost（寻物/拾物、物品名、地点、时间）
    private final PostLostMapper lostMapper;
    // 技能扩展表：post_skill（类目、交付天数）
    private final PostSkillMapper skillMapper;
    // 租赁扩展表：post_rent（日租金、计价单位、押金、起租天数）
    private final PostRentMapper rentMapper;
    // 拼单扩展表：post_groupbuy（目标人数、截止、代取、自提点）
    private final PostGroupbuyMapper groupbuyMapper;
    // 拼单成员表：groupbuy_member（谁参加了哪个拼单，一个用户一条）
    private final GroupbuyMemberMapper groupbuyMemberMapper;
    // 用户表：发布时要查认证状态和信用分；列表要批量查昵称头像
    private final UserMapper userMapper;
    // 内容审核：标题+正文+标签过敏感词（命中直接拒绝，不入库）
    private final ContentCheckService contentCheckService;
    // 上传配置：把图片 key 拼成完整 URL（toUrl 方法用）
    private final UploadProperties uploadProperties;
    // 通知事件发布器：只登记事件，事务提交后才投递到 MQ（通知表慢/挂都不影响拼单事务）
    private final NoticeEventPublisher noticePublisher;
    /** 拼单锁 key 前缀：lock:groupbuy:join:{postId}，按帖子分段，不同单互不阻塞 */
    private static final String GROUPBUY_LOCK_PREFIX = "lock:groupbuy:join:";
    /** 等锁 3 秒 ≈ 一个事务的正常耗时；抢不到就认为这一单太拥挤 */
    private static final long GROUPBUY_LOCK_WAIT_MS = 3000L;
    // 分布式锁：拼单抢名额
    private final DistributedLock distributedLock;
    // 事务管理器：只用来给拼单建 TransactionTemplate（锁必须包住事务）
    private final PlatformTransactionManager transactionManager;

    /**
     * 事务模板：实现「加锁 → 开事务 → 业务 → 提交 → 解锁」。
     *
     * 【为什么不用 @Transactional】它的事务边界在方法上，会出现"解锁了但还没提交"，
     *   下一个拿到锁的线程读到的仍是旧人数 → 超员。必须让提交早于解锁。
     * 【为什么手动 new】Spring Boot 只注册 PlatformTransactionManager，不注册 TransactionTemplate。
     */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTxTemplate() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // JSON 序列化工具：post.images 存的是 JSON 数组字符串，进出都要转
    private static final ObjectMapper JSON = new ObjectMapper();
    // 统一时间格式：详情页 createdAt 用这个格式输出
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 先发后审的门槛：信用分 ≥ 80 且已发布 ≥ 3 条 */
    // 【为什么是两个常量而不是写死数字】门槛会调（比如上线初期放宽），
    //   集中在这里改一处即可；写死在 if 里则要搜全类
    private static final int FAST_AUDIT_CREDIT = 80;
    private static final int FAST_AUDIT_POSTS = 3;
    /** 信用分下限：低于此值不允许发布（设计文档 §7） */
    // 注意与上面的 FAST_AUDIT_CREDIT 区别：
    //   60 是"能不能发"的硬门槛；80 是"发了要不要先审"的区别对待
    private static final int MIN_CREDIT_TO_POST = 60;

    // ============================================================
    // 发布
    // ============================================================
    // 【写法】@Transactional(rollbackFor = Exception.class)
    // 【为什么必须有事务】本方法要写 2~3 张表（post 主表 + 一张扩展表 + 可能的 groupbuy_member）。
    //   如果主表插成功、扩展表插失败而不回滚，就会留下"有帖子但没详情"的脏数据 ——
    //   列表页能看到、点进详情却报 1030。
    // 【为什么写 rollbackFor】Spring 默认只回滚 RuntimeException；写全参数才能兜住受检异常。
    @Override
    @Transactional(rollbackFor = Exception.class)
    /** 创建 */
    public PostDetailVO create(PostCreateReq req) {
        // 第 1 段：取当前用户。
        // 【注意】这里用的是 UserContext（ThreadLocal），不是前端传的 userId ——
        //   防止"传别人的 id 代发帖"。这是 Controller 不接 userId 的原因。
        Long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        // 兜底：令牌有效但用户被删（极端情况）
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        // 第 2 段：认证门槛。
        // 未认证不能发布（设计文档 §2.7：审核通过前可浏览、不可发布）。
        // 注意判断顺序：先判 null 再判值，否则 null 拆箱会 NPE
        if (user.getVerifyStatus() == null || user.getVerifyStatus() != User.VERIFY_PASSED) {
            throw BizException.of(ResultCode.USER_NOT_VERIFIED);
        }
        // 第 3 段：信用分门槛（<60 不能发）。
        // 【为什么先判 null】creditScore 理论上有值，但历史数据可能为 null；
        //   直接比较会 NPE，所以写成"不为 null 且小于 60"
        if (user.getCreditScore() != null && user.getCreditScore() < MIN_CREDIT_TO_POST) {
            throw BizException.of(ResultCode.CREDIT_NOT_ENOUGH);
        }

        // 第 4 段：把前端传的业务线字符串转成枚举。
        // 【为什么用 BizType.of】它内部做了白名单校验，传 "FOO" 会抛 400 而不是继续往下走。
        //   注意 DTO 上虽然也有 @NotBlank，但那只管非空，不管"是不是合法业务线"
        BizType biz = BizType.of(req.getBizType());
        // 第 5 段：按业务线校验扩展字段必填。
        // 【为什么不在 DTO 上用注解】注解无法表达"跑腿才要取件地"这种条件逻辑
        checkBizRequiredFields(req, biz);
        // 第 6 段：敏感词审核（标题 + 正文 + 标签一起过）。
        // 【为什么直接拒绝而不入库】命中敏感词的内容留着没意义，还会占审核队列；
        //   注意这一步发生在 insert 之前，所以被拒的帖子根本不会产生记录
        contentCheckService.check(req.getTitle() + " " + req.getContent() + " " + nullToEmpty(req.getTags()), biz);

        // 第 7 段：把 DTO 的字段搬进 Post 实体（只搬主表该有的）。
        //
        // 【为什么不是 BeanUtils.copyProperties(req, post) 一把拷过来】
        //   因为两边字段名对不上、类型也对不上，还有 5 处必须"加工"：
        //     · req.bizType 是 String "SECONDHAND"，post.bizType 也要 String，但要先经 BizType 校验；
        //     · req.images 是 List<String>，post.images 是 JSON 字符串；
        //     · req.rentPrice 在租赁线要覆盖 price；
        //     · req.deadline / groupbuyDeadline 要合并成一个 expireAt；
        //     · building / tags 可能是 null，而 DDL 是 NOT NULL。
        //   逐个 set 虽然啰嗦，但每一处加工都看得见；用拷贝工具会把这些转换藏起来，
        //   出问题时（比如图片存成了 [Ljava.lang.String;）根本不知道从哪查。
        //
        // 【本例全部入参】userId = 5，biz = SECONDHAND
        //   req = {bizType:"SECONDHAND", categoryId:1, title:" 出高数教材 ",
        //          content:" 九成新 ", price:25.00, negotiable:null,
        //          images:["2026/11/a1b2.jpg"], building:null, tags:null}

        Post post = new Post();

        // 谁发的。取自拦截器写进 ThreadLocal 的 userId，**不用 req 里的任何字段**。
        // 【为什么】如果让前端传 userId，任何人都能冒充别人发帖 —— 这是最基础的越权防线。
        // 实际值：5L
        post.setUserId(userId);

        // 业务线，存字符串 "SECONDHAND" 而不是数字 1。
        // 【为什么 biz.name() 而不是 biz.toString()】两者现在都返回 "SECONDHAND"；
        //   但 name() 是"常量名"的语义，toString() 可以被改写，用 name() 意图更明确。
        // 【⚠️ 坑】biz 已经在第 169 行经 BizType.of(req.getBizType()) 转换过，
        //   非法值在那里就抛 400 了，所以这里拿到的必定是 6 个合法值之一。
        // 实际值："SECONDHAND"
        post.setBizType(biz.name());

        // 二级分类 ID，原样搬运（合法性由 BizType + 前端下拉保证，Service 不再查一遍）。
        // 实际值：1（教材教辅）
        post.setCategoryId(req.getCategoryId());

        // 标题。trim() 去掉首尾空白：防止用户用 "     标题     " 刷长度或者绕敏感词。
        // 【为什么在 Service 再 trim 一次】DTO 上的 @NotBlank 只判断"是不是纯空白"，
        //   不会把值改掉，所以 " 出高数教材 " 会原样进库 —— 列表页显示带一堆空格。
        // 实际值：" 出高数教材 " → "出高数教材"
        post.setTitle(req.getTitle().trim());

        // 正文。同上，trim 去首尾空白（正文中间的换行、空格不动）。
        // 实际值：" 九成新 " → "九成新"
        post.setContent(req.getContent().trim());

        // 价格。不是直接搬 req.price，而是走 resolvePrice，三条规则：
        //   · 失物(LOST)  → 恒为 null（招领/寻物不该标价）
        //   · 租赁(RENTAL) 且填了 rentPrice → 用 rentPrice 覆盖 price（日租金）
        //   · 其余 → 用 req.price
        // 【为什么合并成一个字段】五条业务线的"钱"语义不同（售价/酬金/报价/日租金/人均），
        //   共用一个 DECIMAL(10,2) 列比开 5 个字段更省，代价是读代码要结合 bizType。
        // 实际值：25.00（SECONDHAND，直接用 req.price）
        // 反例：RENTAL 且 rentPrice=15.00 → 这里存 15.00，req.price 即使传 0 也被忽略
        post.setPrice(resolvePrice(req, biz));

        // 是否可议价。null 兜底成 0（不可议价）。
        // 【先说清一个容易误解的点】MP 的 insert 策略默认是 NOT_NULL：
        //   值为 null 的字段不会出现在 INSERT 语句里，数据库的 DEFAULT 0 会生效 ——
        //   所以严格说，这里不兜底也不会报错。
        // 【那兜底图什么】① 让业务默认值写在代码里，不用为了看一眼去翻 DDL；
        //   ② 万一哪天全局策略改成 IGNORED（null 也写进 SQL），这段不会突然崩。
        // 【什么情况才会真报错】列是 NOT NULL 且没有 DEFAULT（如 post_errand.pickup_addr），
        //   null 被跳过后 SQL 里没这一列，数据库报 "doesn't have a default value"。
        //   那种字段靠 checkBizRequiredFields 提前拦住，不能指望兜底。
        // 实际值：req.negotiable = null → 0
        post.setNegotiable(req.getNegotiable() == null ? 0 : req.getNegotiable());

        // 图片。List<String> → JSON 字符串，存进 JSON 列。
        // toJson 的内部行为：list 为 null 或空 → 返回 null（不是 "[]"）。
        // 【为什么存 key 不存完整 URL】域名会变（本地/线上不同），存 key 才能一处改全局生效；
        //   完整 URL 拼装发生在出参阶段（resolveImages → publicBase + key）。
        // 实际值：["2026/11/a1b2.jpg"] → "[\"2026/11/a1b2.jpg\"]"
        post.setImages(toJson(req.getImages()));

        // 楼栋。nullToEmpty = null 转空串 + trim。
        // 【为什么转成 ""，而不是让它是 null】两种做法库里的结果一样（null 会被 MP 跳过，
        //   DDL 的 DEFAULT '' 生效）。转成空串的收益是：Java 对象里的值和库里的值一致，
        //   不会出现"内存里 null、库里空串"两套状态；顺带 trim 掉用户多敲的空格。
        // 实际值：null → ""
        post.setBuilding(nullToEmpty(req.getBuilding()));

        // 标签。同上，null → ""。
        // 实际值：null → ""
        post.setTags(nullToEmpty(req.getTags()));

        // 浏览量。新帖恒为 0，绝不能从 req 取（否则前端能伪造"10000 浏览"）。
        // 实际值：0
        post.setViewCount(0);

        // 到期时间。只有跑腿(ERRAND)和拼单(GROUPBUY)有值，其余是 null。
        // 有值的会在定时任务里按 expireAt 自动下架；null 的永不过期。
        // 实际值：SECONDHAND → null
        post.setExpireAt(resolveExpireAt(req, biz));

        // ⚠️ 这里刻意不 set status 和 auditStatus —— 它们不是"内容"，而是"审核策略的结果"，
        //   要等下一段判断完"新用户还是老用户"才能定，见第 8 段。
        //   如果在这里先写死 0，第 8 段的 fastAudit 分支就得再改一次，容易漏。

        // 第 8 段：决定审核策略（先发后审 vs 进人工队列）。
        // 审核策略：老用户高信用先发后审，其余进人工队列
        // 【为什么要查一次历史发布数】这是"老用户"的判定依据 —— 发过 3 条以上且信用高，
        //   说明不是来发广告的，可以信任。
        // 【代价】每次发帖多一次 count 查询；但发帖是低频操作，可以接受
        long myPostCount = postMapper.selectCount(
                Wrappers.<Post>lambdaQuery().eq(Post::getUserId, userId));
        boolean fastAudit = user.getCreditScore() != null && user.getCreditScore() >= FAST_AUDIT_CREDIT
                && myPostCount >= FAST_AUDIT_POSTS;
        if (fastAudit) {
            // 先发后审：status=1 直接展示，auditStatus=1 标记"机器通过"
            post.setStatus(PostStatus.ONLINE.getCode());
            post.setAuditStatus(AuditStatus.MACHINE_PASS.getCode());
        } else {
            // 进人工队列：status=0 不进信息流，auditStatus=0 等管理员审
            post.setStatus(PostStatus.AUDITING.getCode());
            post.setAuditStatus(AuditStatus.PENDING.getCode());
        }
        // 第 9 段：插主表。
        // 【关键】insert 之后 post.getId() 才有值（MP 会把自增主键回填到实体）——
        //   下一行 saveExt 就是靠这个 id 去写扩展表
        postMapper.insert(post);

        // 第 10 段：写扩展表（二手线什么都不做）。
        // 这里如果抛异常，整个方法回滚，上面的主表插入也会撤销
        saveExt(post.getId(), req, biz);
        log.info("发布成功 postId={} biz={} status={}", post.getId(), biz, post.getStatus());
        // 第 11 段：返回详情 VO。
        // 【为什么再查一次而不是直接组装】详情要含扩展字段、用户信息、按钮文案，
        //   复用 detail() 能保证"发完立刻看到的"和"以后点进来看到的"完全一致
        return detail(post.getId());
    }

    /**
     * 按业务线校验必填的扩展字段。
     * 这些字段在 DDL 里是 NOT NULL，若不提前校验会抛 SQL 异常（表现为 500）。
     */
    // 【为什么不把这些校验写在 DTO 的注解上】
    // 注解是"静态规则"，无法表达"跑腿才要取件地、拼单才要人数"这种条件逻辑。
    // 而这里用 if-else 按 bizType 分支，就能精确到每一条线。
    // 【代价】新增业务线时必须记得回来加一段 if —— 编译器不会提醒（这正是 BizType.hasExtTable
    //   那个"死字段"本应解决的问题，但项目最终没用它）
    /** 校验bizrequiredfields */
    private void checkBizRequiredFields(PostCreateReq req, BizType biz) {
        // 常量类不支持 switch，改为 == 判断（实例唯一，== 与 equals 等价）
        // 【为什么不能用 switch】项目禁用了 Java enum，BizType 是普通类，
        //   switch 需要常量表达式，编译不过。所以只能用 if-else 链。
        if (biz == BizType.ERRAND) {
            // 跑腿：取件地、送达地、截止时间都是必须的 ——
            // 没有取件地接单的人不知道去哪拿，没有截止时间无法判断超时
            requireText(req.getPickupAddr(), "请填写取件地");
            requireText(req.getDeliverAddr(), "请填写送达地");
            if (req.getDeadline() == null) {
                throw BizException.of(ResultCode.PARAM_ERROR, "请填写截止时间");
            }
        } else if (biz == BizType.LOST) {
            // 失物：必须明确是"寻物"还是"拾物"（1/2），否则详情页不知道该显示哪个按钮；
            // 物品名和地点是找回的关键信息，缺了这条帖子没意义
            if (req.getLostType() == null || (req.getLostType() != 1 && req.getLostType() != 2)) {
                throw BizException.of(ResultCode.PARAM_ERROR, "请选择寻物或拾物");
            }
            requireText(req.getItemName(), "请填写物品名称");
            requireText(req.getLostPlace(), "请填写地点");
        } else if (biz == BizType.GROUPBUY) {
            // 目标人数 ≥2（含发起人）；截止时间必填且必须晚于现在
            // 允许填过去的时间 → 帖子"一发出来就过期"，紧接着被定时任务关掉，用户会当成 bug
            if (req.getTargetCount() == null || req.getTargetCount() < 2) {
                throw BizException.of(ResultCode.PARAM_ERROR, "拼单目标人数至少 2 人");
            }
            if (req.getGroupbuyDeadline() == null) {
                throw BizException.of(ResultCode.PARAM_ERROR, "请填写拼单截止时间");
            }
            if (!req.getGroupbuyDeadline().isAfter(LocalDateTime.now())) {
                throw BizException.of(ResultCode.PARAM_ERROR, "拼单截止时间必须晚于当前时间");
            }
        } else if (biz == BizType.RENTAL) {
            // 租赁：租金（price 或 rentPrice 二选一即可，因为历史原因有两个字段）
            // 【注意这里允许两个都为空以外的情况】只要有一个非空就放行，
            //   后面 resolvePrice 会决定最终用哪个值
            if (req.getRentPrice() == null && req.getPrice() == null) {
                throw BizException.of(ResultCode.PARAM_ERROR, "请填写租金");
            }
        } else if (biz == BizType.SKILL) {
            // 技能：必须说明能提供什么服务（"代做PPT"还是"修电脑"），否则没人能判断要不要联系
            requireText(req.getServiceType(), "请填写技能类目");
        } else if (biz == BizType.SECONDHAND) {
            // 二手允许不填价格（可面议），无需强校验
            // 【这是"留空分支"的价值】显式写出来并说明为什么不管，
            //   好过让别人猜"是不是漏了"
        }
    }

    /** 金额取值：租赁用 rentPrice 覆盖 price，其余用 price；失物恒为 null */
    private BigDecimal resolvePrice(PostCreateReq req, BizType biz) {
        if (biz == BizType.LOST) {
            return null;
        }
        if (biz == BizType.RENTAL && req.getRentPrice() != null) {
            return req.getRentPrice();
        }
        return req.getPrice();
    }

    /** 到期时间：跑腿取 deadline，拼单取 groupbuyDeadline，其余为空 */
    private LocalDateTime resolveExpireAt(PostCreateReq req, BizType biz) {
        if (biz == BizType.ERRAND) {
            return req.getDeadline();
        }
        if (biz == BizType.GROUPBUY) {
            return req.getGroupbuyDeadline();
        }
        return null;
    }

    /**
     * 按业务线把"扩展字段"写进对应的扩展表（主表 post + 5 张扩展表，都是一对一）。
     *
     * 调用位置：create() 里主表插入成功之后（第 292 行）。
     * 【为什么必须在主表之后】扩展表的 post_id 既是主键又是关联键，而它就是主表 post 的自增 id ——
     *   主表没插完，这个 id 根本不存在。
     *
     * 参数 postId：主表 post 的自增主键。MyBatis-Plus 的 insert 会把自增 id 回填到实体，
     *   所以调用方传进来时一定是非空（典型值：1234）
     * 参数 req：发布请求，扩展字段全从这里取；每个业务线只读自己相关的几个字段
     * 参数 biz：已解析的业务线常量，决定写哪张表（BizType.ERRAND 对应 post_errand 表）
     *
     * 【为什么拆成"主表 + 扩展表"，而不是一张大表】
     *   6 条业务线的字段完全不重叠（跑腿要取件地、拼单要人数、租赁要计价单位），
     *   全塞进 post 会得到一张 30 多列、每行一半是 null 的表，加一条业务线就得改表结构。
     * 【代价】读详情时每多一条业务线就多查一次扩展表；
     *   写入时两张表必须同时成功 —— 所以强依赖 create() 上的
     *   @Transactional(rollbackFor = Exception.class)：
     *   扩展表插入失败会连主表一起回滚，不会留下"有帖子、没扩展"的脏数据。
     *
     * 【为什么用 if-else 链，而不是 switch，也不是 Map 加函数式接口】
     *   · switch：项目禁用 Java enum，BizType 是普通类，case 需要编译期常量，编译不过；
     *   · Map 加函数式接口：能消掉 if-else，但每个分支要 set 十几个不同类型的字段，
     *     抽成 lambda 后参数传递更绕，还失去了"照着业务线从上往下读"的直观。
     * 【代价】新增业务线必须回来加一段 if —— 编译器不会提醒，这是本方法最大的维护风险；
     *   同理 checkBizRequiredFields() 也得同步加，否则新业务线可能带着 null 直接写库。
     *
     * 【为什么每个分支手写 set，不用 BeanUtils.copyProperties】
     *   字段名对不上（req.groupbuyDeadline 要写进 groupbuy.deadline），
     *   还要补业务默认值（currentCount 初始 1、rentUnit 默认"天"），
     *   拷贝工具只能省掉"刚好同名"的那部分，剩下的照样手写，不如全手写一遍看得清。
     *
     * ⚠️ 五个分支的 mapper（errandMapper / lostMapper / skillMapper / rentMapper /
     *   groupbuyMapper）都是类顶部的字段注入，本方法不校验 biz 是否合法 ——
     *   它信任调用方已经通过 BizType.of() 解析过。
     */
    private void saveExt(Long postId, PostCreateReq req, BizType biz) {
        if (biz == BizType.ERRAND) {
            PostErrand e = new PostErrand();          // 扩展表实体（自研，对应 post_errand）
            // 主键 = 外键 = post.id，查详情时就是 where post_id = ?
            e.setPostId(postId);
            // 取件地/送达地/截止时间在 checkBizRequiredFields() 已校验非空（第 313-320 行），这里直接搬
            e.setPickupAddr(req.getPickupAddr());
            e.setDeliverAddr(req.getDeliverAddr());
            e.setDeadline(req.getDeadline());
            // 【为什么这里还要 ? 0 :】advance_pay（是否需垫付）是 NOT NULL DEFAULT 0，前端不填时 req 里是 null。
            //   兜底不是为了"防 SQL 报错"（MP 默认跳过值为 null 的列，DB 默认值照样生效），
            //   而是让业务默认值只有一个出处：将来 DDL 被人改成 DEFAULT 1，这里的行为也不会跟着悄悄变。
            e.setAdvancePay(req.getAdvancePay() == null ? 0 : req.getAdvancePay());
            errandMapper.insert(e);
        } else if (biz == BizType.LOST) {
            PostLost l = new PostLost();
            l.setPostId(postId);
            // 1 寻物 / 2 拾物：取值合法性已在 checkBizRequiredFields() 卡过，只可能是 1 或 2
            l.setLostType(req.getLostType());
            l.setItemName(req.getItemName());
            l.setLostPlace(req.getLostPlace());
            // lost_time 允许为空（DDL 第 135 行 DEFAULT NULL）——发帖人可能记不清具体时间，不能强制
            l.setLostTime(req.getLostTime());
            lostMapper.insert(l);
        } else if (biz == BizType.SKILL) {
            PostSkill s = new PostSkill();
            s.setPostId(postId);
            s.setServiceType(req.getServiceType());
            // 不填按 1 天：交付天数是给人看的参考值，给个默认比空着好
            s.setDeliveryDays(req.getDeliveryDays() == null ? 1 : req.getDeliveryDays());
            // ⚠️ TODO 硬编码：service_mode / duration_desc 前端还没有录入入口，
            //   这里先写死"线上"和空串。等要做"线上/线下筛选""单次时长"时必须改成从 req 取值，
            //   否则所有技能帖在库里长得一模一样，区分不出来。
            s.setServiceMode("线上");
            s.setDurationDesc("");
            skillMapper.insert(s);
        } else if (biz == BizType.RENTAL) {
            PostRent r = new PostRent();
            r.setPostId(postId);
            // 租金取 rentPrice（不是 req.price）；主表的 post.price 由 resolvePrice() 同一口径填，
            // 两处必须一致，否则"列表显示的价格"和"详情里的租金"会对不上
            r.setRentPrice(req.getRentPrice());
            // 计价单位不填按"天"，与 DDL DEFAULT '天' 对齐
            r.setRentUnit(req.getRentUnit() == null ? "天" : req.getRentUnit());
            // 押金仅记录，平台不代收（B 方案合规红线：小程序内不出现任何资金流转）
            r.setDeposit(req.getDeposit() == null ? BigDecimal.ZERO : req.getDeposit());
            r.setMinDays(req.getMinDays() == null ? 1 : req.getMinDays());
            // ⚠️ available_from（可租起始时间）DDL 里有这一列，但 PostCreateReq 没有对应字段，
            //   所以恒为 NULL。要用它得先给 DTO 加字段 + 前端加控件，光改这里没用。
            rentMapper.insert(r);
        } else if (biz == BizType.GROUPBUY) {
            PostGroupbuy g = new PostGroupbuy();
            g.setPostId(postId);
            g.setTargetCount(req.getTargetCount());
            // 发起人自己占一席，初始 1；之后每有人参与 +1，满员时把 post.status 置 CLOSED
            g.setCurrentCount(1);
            g.setDeadline(req.getGroupbuyDeadline());
            g.setWithErrand(req.getWithErrand() == null ? 0 : req.getWithErrand());
            // 统一成空串（不用 null），前端只需判 isEmpty()
            g.setPickupPoint(nullToEmpty(req.getPickupPoint()));
            groupbuyMapper.insert(g);
        }
        // SECONDHAND（二手）没有扩展表：标题、正文、价格、图片全在主表。
        // 这是"合法的留空分支"，不是漏写 —— 显式写出来，省得后人猜。
    }

    // ============================================================
    // 信息流
    // ============================================================
    @Override
    /** 信息流 */
    public PageResult<PostItemVO> feed(PostQueryReq req) {
        LambdaQueryWrapper<Post> w = Wrappers.<Post>lambdaQuery()
                // 信息流只展示"审核通过且在架"的内容
                .eq(Post::getStatus, PostStatus.ONLINE.getCode());

        applyFilters(w, req);
        applySort(w, req.getSort());

        Page<Post> page = postMapper.selectPage(new Page<>(req.getPage(), req.getSize()), w);
        return new PageResult<>(assembleItems(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 我的发布。按状态筛选（待审/展示中/已下架/已关闭），只返回自己的帖子 */
    @Override
    public PageResult<PostItemVO> mine(PostQueryReq req) {
        Long userId = UserContext.requireUserId();
        LambdaQueryWrapper<Post> w = Wrappers.<Post>lambdaQuery().eq(Post::getUserId, userId);
        applyFilters(w, req);
        applySort(w, req.getSort());
        Page<Post> page = postMapper.selectPage(new Page<>(req.getPage(), req.getSize()), w);
        return new PageResult<>(assembleItems(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 拼查询条件：业务线、分类、关键词、楼栋、价格区间、是否有图 */
    private void applyFilters(LambdaQueryWrapper<Post> w, PostQueryReq req) {
        if (req.getBizType() != null && !req.getBizType().isBlank() && !"ALL".equalsIgnoreCase(req.getBizType())) {
            w.eq(Post::getBizType, BizType.of(req.getBizType()).name());
        }
        if (req.getCategoryId() != null && req.getCategoryId() > 0) {
            w.eq(Post::getCategoryId, req.getCategoryId());
        }
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            String kw = req.getKeyword().trim();
            // 标题或正文命中都算；MySQL LIKE 走索引前缀，5 万条以内够用（不上 ES）
            w.and(x -> x.like(Post::getTitle, kw).or().like(Post::getContent, kw));
        }
        if (req.getMinPrice() != null) {
            w.ge(Post::getPrice, req.getMinPrice());
        }
        if (req.getMaxPrice() != null) {
            w.le(Post::getPrice, req.getMaxPrice());
        }
        if (req.getBuilding() != null && !req.getBuilding().isBlank()) {
            w.eq(Post::getBuilding, req.getBuilding().trim());
        }
    }

    /** 拼排序：latest 最新 / hot 最热（浏览+收藏）/ price 价格升降 */
    private void applySort(LambdaQueryWrapper<Post> w, String sort) {
        String s = sort == null ? "latest" : sort.trim();
        switch (s) {
            // 失物没有价格（price 为 NULL），MySQL 的 ASC 会把 NULL 排在最前，
            // 导致"价格从低到高"第一屏全是失物。这里用 "price IS NULL" 先兜住，
            // 把无价格的行排到最后，再按价格排序。
            // 用 last() 而不是 orderByAsc(String)：LambdaQueryWrapper 的排序方法
            // 只接受方法引用（SFunction），传列名字符串编译不过。
            case "priceAsc" -> w.last("ORDER BY price IS NULL ASC, price ASC, id DESC");
            case "priceDesc" -> w.last("ORDER BY price IS NULL ASC, price DESC, id DESC");
            default -> w.orderByDesc(Post::getId);
        }
    }

    /**
     * 批量组装卡片 VO。
     * 一次页内最多 4 次查询：用户、失物扩展、拼单扩展，避免 N+1。
     */
    @Override
    public List<PostItemVO> assembleItems(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> userIds = posts.stream().map(Post::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<Long> lostIds = posts.stream()
                .filter(p -> BizType.LOST.name().equals(p.getBizType()))
                .map(Post::getId).toList();
        Map<Long, PostLost> lostMap = lostIds.isEmpty() ? Collections.emptyMap()
                : lostMapper.selectBatchIds(lostIds).stream()
                .collect(Collectors.toMap(PostLost::getPostId, Function.identity()));

        List<Long> gbIds = posts.stream()
                .filter(p -> BizType.GROUPBUY.name().equals(p.getBizType()))
                .map(Post::getId).toList();
        Map<Long, PostGroupbuy> gbMap = gbIds.isEmpty() ? Collections.emptyMap()
                : groupbuyMapper.selectBatchIds(gbIds).stream()
                .collect(Collectors.toMap(PostGroupbuy::getPostId, Function.identity()));

        List<PostItemVO> list = new ArrayList<>(posts.size());
        for (Post p : posts) {
            PostItemVO vo = new PostItemVO();
            vo.setId(p.getId());
            vo.setBiz(p.getBizType());
            vo.setTitle(p.getTitle());
            vo.setDesc(TextUtil.summary(p.getContent(), 60));
            vo.setPrice(MoneyUtil.plain(p.getPrice()));
            vo.setPriceUnit(priceUnitOf(p.getBizType()));
            vo.setMeta(RelativeTimeUtil.meta(p.getBuilding(), p.getCreatedAt()));
            User u = userMap.get(p.getUserId());
            vo.setUser(u == null ? "校" : TextUtil.initial(u.getNickname()));
            List<String> keys = fromJson(p.getImages());
            vo.setCover(keys.isEmpty() ? null : toUrl(keys.get(0)));
            vo.setStatus(p.getStatus());
            vo.setStatusText(statusText(p.getBizType(), p.getStatus()));
            vo.setRejectReason(p.getRejectReason());
            vo.setCreatedAt(p.getCreatedAt() == null ? null : p.getCreatedAt().format(DT));

            if (BizType.GROUPBUY.name().equals(p.getBizType())) {
                PostGroupbuy g = gbMap.get(p.getId());
                vo.setProgress(g == null ? new int[]{0, 0}
                        : new int[]{g.getCurrentCount(), g.getTargetCount()});
            } else {
                vo.setProgress(new int[]{0, 0});
            }
            if (BizType.LOST.name().equals(p.getBizType())) {
                PostLost l = lostMap.get(p.getId());
                vo.setTag(l == null ? "失物" : (l.getLostType() != null && l.getLostType() == 1 ? "寻物" : "拾物"));
            }
            list.add(vo);
        }
        return list;
    }

    // ============================================================
    // 详情
    // ============================================================
    @Override
    /** 详情 */
    public PostDetailVO detail(Long id) {
        Post p = postMapper.selectById(id);
        if (p == null) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }
        Long currentUserId = UserContext.getUserId();
        boolean owner = currentUserId != null && currentUserId.equals(p.getUserId());
        // 非本人且非展示中 → 当作不存在，避免通过 id 遍历看到下架/待审内容
        //
        // 【2026-11-15 补一行：DEALT 也要放行】
        // 跑腿接单成功后帖子会被锁成 DEALT(3)，此时接单人**不是发布者**，
        // 如果还按老规则拦，他点进详情会看到"内容不存在"——
        // 可他正需要这个页面看取件地址和截止时间，功能等于直接废掉。
        // 【为什么放行 3 不带来隐私风险】真正需要藏的是"待审核(0)"和"已下架(2)"；
        // 3 表示"有人接手了"，对路人可见反而有用：让他知道这单没了，不用白点。
        // 模拟数据：帖子 88 status=3，接单人 userId=16（发布者是 15）
        //   改前：16 打开详情 → 404「内容不存在」
        //   改后：16 打开详情 → 200，看到完整信息 + 「我已接单」状态
        if (!owner
                && !Integer.valueOf(PostStatus.ONLINE.getCode()).equals(p.getStatus())
                && !Integer.valueOf(PostStatus.DEALT.getCode()).equals(p.getStatus())) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }

        PostDetailVO vo = new PostDetailVO();
        vo.setId(p.getId());
        vo.setBiz(p.getBizType());
        vo.setTitle(p.getTitle());
        vo.setContent(p.getContent());
        vo.setPrice(MoneyUtil.plain(p.getPrice()));
        vo.setPriceUnit(priceUnitOf(p.getBizType()));
        vo.setNegotiable(p.getNegotiable());
        List<String> keys = fromJson(p.getImages());
        vo.setImageKeys(keys);
        vo.setImages(keys.stream().map(this::toUrl).toList());
        vo.setBuilding(p.getBuilding());
        vo.setTags(p.getTags());
        vo.setMeta(RelativeTimeUtil.meta(p.getBuilding(), p.getCreatedAt()));
        vo.setStatus(p.getStatus());
        vo.setStatusText(statusText(p.getBizType(), p.getStatus()));
        vo.setRejectReason(p.getRejectReason());
        vo.setViewCount(p.getViewCount());
        vo.setOwner(owner);
        vo.setCreatedAt(p.getCreatedAt() == null ? null : p.getCreatedAt().format(DT));
        vo.setFavorited(false);

        // 拼单参与状态：发起人恒为已参加，其他人查成员表
        if (BizType.GROUPBUY.name().equals(p.getBizType())) {
            PostGroupbuy gb = groupbuyMapper.selectById(p.getId());
            boolean expired = gb != null && gb.getDeadline() != null
                    && gb.getDeadline().isBefore(LocalDateTime.now());
            vo.setGroupbuyExpired(expired);

            boolean joined = owner;
            if (!owner && currentUserId != null) {
                GroupbuyMember m = groupbuyMemberMapper.selectOne(Wrappers.<GroupbuyMember>lambdaQuery()
                        .eq(GroupbuyMember::getPostId, p.getId())
                        .eq(GroupbuyMember::getUserId, currentUserId));
                joined = m != null && GroupbuyMember.JOINED.equals(m.getStatus());
            }
            vo.setJoined(joined);
        }

        User author = userMapper.selectById(p.getUserId());
        if (author != null) {
            UserVO uv = new UserVO();
            uv.setId(author.getId());
            uv.setNickname(author.getNickname());
            uv.setAvatar(author.getAvatar());
            uv.setInitial(TextUtil.initial(author.getNickname()));
            uv.setIntro(author.getIntro());
            uv.setCreditScore(author.getCreditScore());
            uv.setVerifyStatus(author.getVerifyStatus());
            uv.setVerified(author.getVerifyStatus() != null && author.getVerifyStatus() == User.VERIFY_PASSED);
            vo.setUser(uv);
        }

        BizType biz = BizType.of(p.getBizType());
        vo.setFields(buildExtFields(p.getId(), biz));
        vo.setNotice(noticeOf(biz));
        vo.setCta(ctaOf(biz));

        // 浏览量 +1：用 SQL 自增避免"读-改-写"丢失更新
        if (!owner) {
            postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                    .setSql("view_count = view_count + 1")
                    .eq(Post::getId, p.getId()));
        }
        return vo;
    }

    /** 按业务线拼详情页的扩展字段行（顺序即前端展示顺序） */
    private List<ExtFieldVO> buildExtFields(Long postId, BizType biz) {
        List<ExtFieldVO> fields = new ArrayList<>();
        if (biz == BizType.ERRAND) {
            PostErrand e = errandMapper.selectById(postId);
            if (e == null) {
                return fields;
            }
            fields.add(new ExtFieldVO("取件地", e.getPickupAddr()));
            fields.add(new ExtFieldVO("送达地", e.getDeliverAddr()));
            fields.add(new ExtFieldVO("截止时间", e.getDeadline() == null ? "" : e.getDeadline().format(DT)));
            fields.add(new ExtFieldVO("是否垫付", Integer.valueOf(1).equals(e.getAdvancePay()) ? "需垫付" : "不垫付"));
        } else if (biz == BizType.LOST) {
            PostLost l = lostMapper.selectById(postId);
            if (l == null) {
                return fields;
            }
            fields.add(new ExtFieldVO("类型", Integer.valueOf(1).equals(l.getLostType()) ? "寻物启事" : "拾物招领"));
            fields.add(new ExtFieldVO("物品名称", l.getItemName()));
            fields.add(new ExtFieldVO("地点", l.getLostPlace()));
            fields.add(new ExtFieldVO("时间", l.getLostTime() == null ? "" : l.getLostTime().format(DT)));
        } else if (biz == BizType.SKILL) {
            PostSkill s = skillMapper.selectById(postId);
            if (s == null) {
                return fields;
            }
            fields.add(new ExtFieldVO("技能类目", s.getServiceType()));
            fields.add(new ExtFieldVO("服务方式", s.getServiceMode()));
            fields.add(new ExtFieldVO("交付天数", s.getDeliveryDays() + " 天"));
            fields.add(new ExtFieldVO("单次时长", s.getDurationDesc()));
        } else if (biz == BizType.RENTAL) {
            PostRent r = rentMapper.selectById(postId);
            if (r == null) {
                return fields;
            }
            fields.add(new ExtFieldVO("计价单位", "每" + r.getRentUnit()));
            fields.add(new ExtFieldVO("起租天数", r.getMinDays() + " 天"));
            // 押金只展示金额，并明确"线下协商、平台不代收"
            fields.add(new ExtFieldVO("押金", MoneyUtil.plain(r.getDeposit()) + " 元（线下自行协商）"));
            fields.add(new ExtFieldVO("可租时间",
                    r.getAvailableFrom() == null ? "随时" : r.getAvailableFrom().format(DT)));
        } else if (biz == BizType.GROUPBUY) {
            PostGroupbuy g = groupbuyMapper.selectById(postId);
            if (g == null) {
                return fields;
            }
            fields.add(new ExtFieldVO("目标人数", g.getTargetCount() + " 人"));
            fields.add(new ExtFieldVO("已拼人数", g.getCurrentCount() + " 人"));
            fields.add(new ExtFieldVO("截止时间", g.getDeadline() == null ? "" : g.getDeadline().format(DT)));
            fields.add(new ExtFieldVO("自提点", g.getPickupPoint()));
        }
        // 二手没有扩展字段，走主表（原 switch 的 default 分支）
        return fields;
    }

    // ============================================================
    // 关闭 / 删除
    // ============================================================
    @Override
    /** 关闭 */
    public void close(Long id) {
        Post p = requireOwnPost(id);
        if (!Integer.valueOf(PostStatus.ONLINE.getCode()).equals(p.getStatus())
                && !Integer.valueOf(PostStatus.AUDITING.getCode()).equals(p.getStatus())) {
            throw BizException.of(ResultCode.POST_STATUS_ILLEGAL);
        }
        p.setStatus(PostStatus.CLOSED.getCode());
        postMapper.updateById(p);
        log.info("关闭帖子 postId={}", id);
    }

    /** 删除帖子。校验是本人后做逻辑删除（deleted=1），数据可追溯 */
    @Override
    public void delete(Long id) {
        requireOwnPost(id);
        // @TableLogic：这里执行的是 UPDATE post SET deleted=1
        postMapper.deleteById(id);
        log.info("删除帖子 postId={}", id);
    }

    /** 查帖子并校验是本人，不是则抛 1031。改/删/关帖前都走这里 */
    private Post requireOwnPost(Long id) {
        Post p = postMapper.selectById(id);
        if (p == null) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }
        if (!p.getUserId().equals(UserContext.requireUserId())) {
            throw BizException.of(ResultCode.POST_NOT_OWNER);
        }
        return p;
    }

    // ============================================================
    // 分类
    // ============================================================
    @Override
    public List<CategoryVO> categories(String bizType) {
        return Arrays.stream(CategoryEnum.values())
                .filter(c -> bizType == null || bizType.isBlank()
                        || c.getBizType().equalsIgnoreCase(bizType))
                .map(c -> new CategoryVO(c.getId(), c.getLabel(), c.getBizType()))
                .toList();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 价格单位：前端要求 SKILL=时薪、RENTAL=日租金、GROUPBUY=人均，其余不显示 */
    private String priceUnitOf(String bizType) {
        if (bizType == null) {
            return null;
        }
        return switch (bizType) {
            case "SKILL" -> "时薪";
            case "RENTAL" -> "日租金";
            case "GROUPBUY" -> "人均";
            default -> null;
        };
    }

    /** 帖子状态文案随业务线变化：二手叫"已售出"、失物叫"已招领" */
    private String statusText(String bizType, Integer status) {
        PostStatus st = PostStatus.of(status);
        if (st != PostStatus.DEALT) {
            return st.getLabel();
        }
        return switch (bizType == null ? "" : bizType) {
            case "SECONDHAND" -> "已售出";
            case "LOST" -> "已招领";
            case "ERRAND" -> "已完成";
            case "RENTAL" -> "已租出";
            default -> "已成交";
        };
    }

    /** 合规提示：金额相关的每条业务线都必须写"线下自行结算" */
    private String noticeOf(BizType biz) {
        if (biz == BizType.SECONDHAND) {
            return "请当面验货、当面结算，平台不参与资金流转。";
        }
        if (biz == BizType.ERRAND) {
            return "酬金由双方线下当面结算，平台不代收。";
        }
        if (biz == BizType.SKILL) {
            return "严禁代写、代做、代考；一经发现封号处理。";
        }
        if (biz == BizType.RENTAL) {
            return "押金由双方线下自行协商，平台不代收、不代管。";
        }
        if (biz == BizType.LOST) {
            return "请勿在公开信息中暴露证件号等敏感信息。";
        }
        if (biz == BizType.GROUPBUY) {
            return "仅限日用品 / 文具 / 水果等自提拼单，不含外卖熟食；货款线下自行结算。";
        }
        // 原 switch 对 6 条业务线穷举，无 default；这里保底返回 null（新增业务线时必须在这里补文案）
        return null;
    }

    // ============================================================
    // 拼单：参加 / 退出
    //
    // 【整体概览】
    //   目的：N 个人凑一单，名额有限先到先得；满员立即关帖并通知全体成员。
    //   主流程：
    //     发布   targetCount≥2、currentCount 初始 1（发起人自占一席）
    //     参加   插成员记录 → 人数 +1 → 判断是否满员
    //     满员   post.status ONLINE→CLOSED + 通知所有成员（含发起人）
    //     未满   只通知发起人"现在有几人"
    //     退出   成员置 CANCELLED → 人数 -1 → 未截止且原为 CLOSED 则重新 ONLINE
    //   状态：
    //     post.status    1 ONLINE：可参加 ／ 4 CLOSED：满员或到期
    //     member.status  JOINED ⇄ CANCELLED（退出不删行，留痕并占住唯一键）
    //   三道防线：
    //     ① 分段锁 lock:groupbuy:join:{postId}：让"插成员+加人数+关帖+发通知"串行
    //     ② 条件更新 CAS（current_count < target_count）：锁失效也不会超员
    //     ③ 唯一索引 uk_post_user：一人一单只有一条记录
    // ============================================================

    /**
     * 参加拼单（抢名额）。全项目最经典的锁场景，等价于秒杀抢库存。
     *
     * 【为什么必须加锁】它是一段三步临界区：插成员记录 → 人数 +1 → 满员则关帖并发通知。
     *   第 3 步要读"加完之后几个人"再决定要不要关帖，一条 SQL 表达不了；不是一条 SQL 就有窗口，
     *   两个人可能都读到 9、都以为自己是第 10 个，于是都关帖、都发一轮通知，成员收到两条。
     *   ⇒ 锁的作用不只是防超员，更是让这一整段变成串行、状态一致的临界区。
     *
     * 【三个参数为什么这么给】
     *   key 带 postId：只让抢同一单的人排队，抢 88 号单不会阻塞抢 92 号单；
     *   wait = 3 秒：抢不到不算失败，是"前面的人正在跑，等一会儿"，比直接报错体验好；
     *   lease = -1：看门狗自动续期，事务耗时不可预测，不能定死 TTL。
     */
    @Override
    public void joinGroupbuy(Long postId) {
        Long userId = UserContext.requireUserId();

        // 锁 → 事务 → 提交 → 解锁。顺序不能变：提交必须早于解锁，否则下一个人读到旧人数
        boolean locked = distributedLock.tryRun(
                GROUPBUY_LOCK_PREFIX + postId,
                GROUPBUY_LOCK_WAIT_MS,
                DistributedLock.LEASE_WITH_WATCHDOG,
                () -> txTemplate.executeWithoutResult(status -> doJoinGroupbuy(postId, userId)));

        if (!locked) {
            // 3 秒都没抢到 = 这一单此刻确实拥挤，让用户稍后重试
            log.warn("[拼单] 抢锁超时 postId={} userId={}", postId, userId);
            throw BizException.of(ResultCode.OPERATION_TOO_FREQUENT, "这一单正忙，请稍后重试");
        }
    }

    /**
     * 拼单真正的业务：写名单 → 人数 +1 → 满了就关帖并发通知。
     *
     * 执行前提：外面已经拿到锁、事务已经开启（见 joinGroupbuy）。
     * ⚠️ private 且被 lambda 调用，不能再加 @Transactional（AOP 不代理同类内部调用）。
     */
    private void doJoinGroupbuy(Long postId, Long userId) {
        Post p = postMapper.selectById(postId);                 // 先查帖子主表
        if (p == null || !BizType.GROUPBUY.name().equals(p.getBizType())) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);    // 帖子不存在，或根本不是拼单（1030）
        }
        if (!Integer.valueOf(PostStatus.ONLINE.getCode()).equals(p.getStatus())) {
            // 满员也会把帖子关掉（CLOSED），所以先分清"是满了"还是"被下架了"
            PostGroupbuy checkFull = groupbuyMapper.selectById(postId);
            if (checkFull != null && checkFull.getCurrentCount() != null
                    && checkFull.getTargetCount() != null
                    && checkFull.getCurrentCount() >= checkFull.getTargetCount()) {
                throw BizException.of(ResultCode.GROUPBUY_FULL);      // 人满了（1070）
            }
            throw BizException.of(ResultCode.GROUPBUY_ENDED);          // 已下架/已关闭/已截止（1073）
        }
        if (Objects.equals(p.getUserId(), userId)) {
            throw BizException.of(ResultCode.GROUPBUY_ALREADY_JOINED, "你是发起人，已在拼单中");  // 1074
        }
        PostGroupbuy g = groupbuyMapper.selectById(postId);     // 再查拼单扩展表：人数、目标、截止都在这
        if (g == null) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);    // 主表有、扩展表没有：脏数据，正常不会发生
        }
        if (g.getDeadline() != null && g.getDeadline().isBefore(LocalDateTime.now())) {
            throw BizException.of(ResultCode.GROUPBUY_ENDED);    // 过了截止时间（1073）
        }
        if (g.getCurrentCount() == null || g.getTargetCount() == null
                || g.getCurrentCount() >= g.getTargetCount()) {
            throw BizException.of(ResultCode.GROUPBUY_FULL);     // 人数满了（1070），上面那条是兜底再判一次
        }

        GroupbuyMember member = groupbuyMemberMapper.selectOne(Wrappers.<GroupbuyMember>lambdaQuery()
                .eq(GroupbuyMember::getPostId, postId)
                .eq(GroupbuyMember::getUserId, userId));
        // 幂等：已经参加过就直接返回，不再加人
        if (member != null && GroupbuyMember.JOINED.equals(member.getStatus())) {
            return;
        }

        // 插名单。撞唯一键 uk_post_user = 并发下别人刚替我插过，按"已参加"处理
        // ⚠️ catch 后必须 return，否则接着往下 +1，人数会多算一个
        if (member == null) {
            GroupbuyMember m = new GroupbuyMember();
            m.setPostId(postId);
            m.setUserId(userId);
            m.setStatus(GroupbuyMember.JOINED);
            try {
                groupbuyMemberMapper.insert(m);
            } catch (DuplicateKeyException e) {
                // MySQL 唯一键冲突不影响事务，可安全 return（PostgreSQL 会把整个事务标记失败）
                log.info("[拼单] 并发重复参加，按已参加处理 postId={} userId={}", postId, userId);
                return;
            }
        } else {
            // 退出过的人再参加：只把 CANCELLED 改回 JOINED；改不动说明别人刚改过，直接返回
            int revived = groupbuyMemberMapper.update(null,
                    Wrappers.<GroupbuyMember>lambdaUpdate()
                            .eq(GroupbuyMember::getPostId, postId)
                            .eq(GroupbuyMember::getUserId, userId)
                            .eq(GroupbuyMember::getStatus, GroupbuyMember.CANCELLED)
                            .set(GroupbuyMember::getStatus, GroupbuyMember.JOINED));
            if (revived == 0) {
                log.info("[拼单] 复参未命中，已在并发中恢复 postId={} userId={}", postId, userId);
                return;
            }
        }

        // 人数 +1，同时判断有没有满：只有"当前人数 < 目标人数"才改得动
        int rows = groupbuyMapper.update(null, Wrappers.<PostGroupbuy>lambdaUpdate()
                .eq(PostGroupbuy::getPostId, postId)
                .lt(PostGroupbuy::getCurrentCount, g.getTargetCount())
                .setSql("current_count = current_count + 1"));
        if (rows == 0) {
            // 改不动 = 被别人抢满了。抛异常回滚，刚才插的名单也不会留下
            throw BizException.of(ResultCode.GROUPBUY_FULL);
        }

        PostGroupbuy after = groupbuyMapper.selectById(postId);
        // 加完之后再看一眼满没满，决定发哪种通知
        boolean full = after != null && after.getCurrentCount() != null && after.getTargetCount() != null
                && after.getCurrentCount() >= after.getTargetCount();

        if (full) {
            // 关帖：只关"还在展示中"的，避免把已被下架的单又写成 CLOSED
            postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                    .eq(Post::getId, postId)
                    .eq(Post::getStatus, PostStatus.ONLINE.getCode())
                    .set(Post::getStatus, PostStatus.CLOSED.getCode()));

            // 成团了，通知所有成员。这里只登记事件，事务提交后才真正投递到 MQ
            List<GroupbuyMember> members = groupbuyMemberMapper.selectList(
                    Wrappers.<GroupbuyMember>lambdaQuery()
                            .eq(GroupbuyMember::getPostId, postId)
                            .eq(GroupbuyMember::getStatus, GroupbuyMember.JOINED));
            String title = "拼单成团了";
            String content = "《" + p.getTitle() + "》已凑满 " + after.getTargetCount() + " 人，可以联系自提了";
            // ⚠️ 发起人不在成员表里（他的名额靠 current_count 初始 1 表示），所以下面要单独补发；
            //   Set 记录谁已经通知过，防止将来改造后发两遍
            java.util.Set<Long> notified = new java.util.HashSet<>();
            for (GroupbuyMember m : members) {
                noticePublisher.publish(m.getUserId(), Notice.TYPE_GROUPBUY,
                        title, content, Notice.REF_POST, postId);
                notified.add(m.getUserId());
            }
            // 补发发起人：成员表里没有他
            Long ownerId = p.getUserId();
            if (ownerId != null && !notified.contains(ownerId)) {
                noticePublisher.publish(ownerId, Notice.TYPE_GROUPBUY,
                        title, content, Notice.REF_POST, postId);
                notified.add(ownerId);
            }
            log.info("[拼单] 成团 postId={} 人数={} 通知 {} 人", postId, after.getCurrentCount(), notified.size());
        } else if (after != null && p.getUserId() != null) {
            // 没满：只告诉发起人进度，不给其他成员发（5 人单群发会变成 20 条通知）
            noticePublisher.publish(p.getUserId(), Notice.TYPE_GROUPBUY,
                    "有人参与了你的拼单",
                    "《" + p.getTitle() + "》现在已有 " + after.getCurrentCount() + " 人参与",
                    Notice.REF_POST, postId);
        }
    }

    /**
     * 退出拼单。发起人不能退（只能关帖）；退出后释放名额，原为满员关闭且未截止则重新开放。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void leaveGroupbuy(Long postId) {
        Long userId = UserContext.requireUserId();
        Post p = postMapper.selectById(postId);
        if (p == null || !BizType.GROUPBUY.name().equals(p.getBizType())) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }
        if (Objects.equals(p.getUserId(), userId)) {
            throw BizException.of(ResultCode.GROUPBUY_OWNER_CANNOT_QUIT);
        }

        GroupbuyMember member = groupbuyMemberMapper.selectOne(Wrappers.<GroupbuyMember>lambdaQuery()
                .eq(GroupbuyMember::getPostId, postId)
                .eq(GroupbuyMember::getUserId, userId));
        if (member == null || !GroupbuyMember.JOINED.equals(member.getStatus())) {
            throw BizException.of(ResultCode.GROUPBUY_NOT_JOINED);
        }

        // 退出同样用条件更新（只有 JOINED 才改 CANCELLED），靠影响行数判胜负。
        // 用 updateById 会让两个并发退出都"成功"，人数被减两次（5→3 而不是 4）
        int left = groupbuyMemberMapper.update(null,
                Wrappers.<GroupbuyMember>lambdaUpdate()
                        .eq(GroupbuyMember::getPostId, postId)
                        .eq(GroupbuyMember::getUserId, userId)
                        .eq(GroupbuyMember::getStatus, GroupbuyMember.JOINED)
                        .set(GroupbuyMember::getStatus, GroupbuyMember.CANCELLED));
        if (left == 0) {
            // 并发下已经被另一个请求退掉了 → 当幂等处理，不再减人数
            log.info("[拼单] 并发重复退出，已幂等返回 postId={} userId={}", postId, userId);
            return;
        }

        // 释放名额，但不允许把发起人自己（初始 1）减掉
        groupbuyMapper.update(null, Wrappers.<PostGroupbuy>lambdaUpdate()
                .eq(PostGroupbuy::getPostId, postId)
                .gt(PostGroupbuy::getCurrentCount, 1)
                .setSql("current_count = current_count - 1"));

        // 如果之前因满员关帖，退出后重新开放（未截止才开放）
        PostGroupbuy g = groupbuyMapper.selectById(postId);
        boolean expired = g != null && g.getDeadline() != null && g.getDeadline().isBefore(LocalDateTime.now());
        if (!expired) {
            // 重开也用条件更新（只把 CLOSED 改回 ONLINE），而不是拿上面读到的旧 p.status 判断。
            // 好处：两个并发退出只有一个命中；期间被管理员下架的帖子也不会被"意外重新上架"
            // ⚠️ 已知边界：作者主动关闭的拼单也会被这里重新开放 —— 关闭原因没有落库，无法区分
            postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                    .eq(Post::getId, postId)
                    .eq(Post::getStatus, PostStatus.CLOSED.getCode())
                    .set(Post::getStatus, PostStatus.ONLINE.getCode()));
        }
    }

    // ============================================================
    // 拼单：成员列表 / 我参与的
    // ============================================================
    @Override
    public List<GroupbuyMemberVO> groupbuyMembers(Long postId) {
        Post p = postMapper.selectById(postId);
        if (p == null || !BizType.GROUPBUY.name().equals(p.getBizType())) {
            throw BizException.of(ResultCode.POST_NOT_FOUND);
        }

        List<GroupbuyMemberVO> list = new ArrayList<>();

        // 第 1 段：发起人固定排第一位。他创建时不写成员表，必须从 post.user_id 反查
        User author = userMapper.selectById(p.getUserId());
        if (author != null) {
            list.add(toMemberVO(author, true, null));
        }

        // 第 2 段：JOINED 成员按 id 升序 = 参与顺序（created_at 同一毫秒会相同，排序不稳）
        List<GroupbuyMember> members = groupbuyMemberMapper.selectList(
                Wrappers.<GroupbuyMember>lambdaQuery()
                        .eq(GroupbuyMember::getPostId, postId)
                        .eq(GroupbuyMember::getStatus, GroupbuyMember.JOINED)
                        .orderByAsc(GroupbuyMember::getId));
        if (members.isEmpty()) {
            return list;
        }

        // 第 3 段：批量查用户（N 条成员 → 1 次查询），并过滤发起人，免得列表里出现两次
        List<Long> userIds = members.stream()
                .map(GroupbuyMember::getUserId)
                .filter(id -> !Objects.equals(id, p.getUserId()))
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return list;
        }
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // 第 4 段：按成员顺序拼 VO；用户已被删就跳过，不能让一条脏数据打挂整个列表
        for (GroupbuyMember m : members) {
            if (Objects.equals(m.getUserId(), p.getUserId())) {
                continue;
            }
            User u = userMap.get(m.getUserId());
            if (u != null) {
                list.add(toMemberVO(u, false, m.getCreatedAt()));
            }
        }
        return list;
    }

    /**
     * User → 成员 VO。只暴露昵称/头像/首字，不带 openid 等敏感字段。
     * 发起人 joinedAt 传 null；示例：u.nickname="李同学"、owner=false
     *   → {userId:5, nickname:"李同学", initial:"李", owner:false, joinedAt:"2026-11-15 01:30"}
     */
    private GroupbuyMemberVO toMemberVO(User u, boolean owner, LocalDateTime joinedAt) {
        GroupbuyMemberVO vo = new GroupbuyMemberVO();
        vo.setUserId(u.getId());
        vo.setNickname(u.getNickname());
        vo.setAvatar(u.getAvatar());
        vo.setInitial(TextUtil.initial(u.getNickname()));
        vo.setOwner(owner);
        vo.setJoinedAt(joinedAt == null ? null : joinedAt.format(DT));
        return vo;
    }

    /** 我参加的拼单列表（分页）。先按成员表分页，再回查帖子，避免分页总数算错 */
    @Override
    public PageResult<PostItemVO> joinedGroupbuys(int page, int size) {
        Long userId = UserContext.requireUserId();
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));

        // 第 1 段：在成员表上分页。若先查帖子再过滤，total 会算错（第 2 页可能只剩 3 条）
        Page<GroupbuyMember> mp = new Page<>(p, s);
        groupbuyMemberMapper.selectPage(mp, Wrappers.<GroupbuyMember>lambdaQuery()
                .eq(GroupbuyMember::getUserId, userId)
                .eq(GroupbuyMember::getStatus, GroupbuyMember.JOINED)
                .orderByDesc(GroupbuyMember::getId));

        List<Long> postIds = mp.getRecords().stream().map(GroupbuyMember::getPostId).toList();
        if (postIds.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), mp.getTotal(), p, s);
        }

        // 第 2 段：批量取帖子并按参与顺序还原。@TableLogic 会过滤掉已删除的帖子，
        //   所以用 filter 把空位剔除，最终条数可能少于 postIds
        Map<Long, Post> postMap = postMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity(), (a, b) -> a));
        List<Post> ordered = postIds.stream()
                .map(postMap::get)
                .filter(Objects::nonNull)
                .toList();

        return new PageResult<>(assembleItems(ordered), mp.getTotal(), p, s);
    }

    /** 某人发布的公开帖子（看他人主页用，只含展示中的） */
    @Override
    public PageResult<PostItemVO> userPosts(Long userId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        var pageObj = postMapper.selectPage(new Page<>(p, s),
                Wrappers.<Post>lambdaQuery()
                        .eq(Post::getUserId, userId)
                        .eq(Post::getStatus, PostStatus.ONLINE.getCode())
                        .orderByDesc(Post::getId));
        return PageResult.of(pageObj, assembleItems(pageObj.getRecords()));
    }

    /**
     * 底部主按钮文案。
     * B 方案已移除下单/订单链路：所有业务线统一引导到私信，不再出现「购买/接单/租用」等交易动作。
     */
    private String ctaOf(BizType biz) {
        return "私信联系";
    }

    /** 图片 key → 可访问 URL */
    private String toUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return key;
        }
        return uploadProperties.getPublicBase() + "/" + key;
    }

    /** 图片列表序列化成 JSON 存库；空列表存 null 而不是 "[]"，便于判断 */
    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("图片列表序列化失败", e);
            return null;
        }
    }

    /** 校验字符串非空，空则抛参数错误。用于各业务线必填项 */
    /** JSON 反序列化，脏数据不要抛异常把整个详情页搞挂 */
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
    /** null 转空串。入库字段多为 NOT NULL，存 null 会报错 */
            List<String> list = JSON.readValue(json, new TypeReference<List<String>>() {
            });
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.warn("图片 JSON 解析失败: {}", json);
            return Collections.emptyList();
        }
    }

    /** 获取并校验文本 */
    private void requireText(String v, String msg) {
        if (v == null || v.isBlank()) {
            throw BizException.of(ResultCode.PARAM_ERROR, msg);
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 保留：后续做"收藏"时用它判断是否已收藏 */
    @SuppressWarnings("unused")
    private static Set<Long> emptyIdSet() {
        return new HashSet<>();
    }
}
