package com.gdut.xiaojii.controller.app;

import com.gdut.xiaojii.common.Result;
import com.gdut.xiaojii.dto.req.EduLoginReq;
import com.gdut.xiaojii.dto.vo.EduCaptchaVO;
import com.gdut.xiaojii.dto.vo.EduExamVO;
import com.gdut.xiaojii.dto.vo.EduScoreVO;
import com.gdut.xiaojii.dto.vo.EduStatusVO;
import com.gdut.xiaojii.dto.vo.EduTimetableVO;
import com.gdut.xiaojii.service.EduAuthService;
import com.gdut.xiaojii.service.EduDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教务接口（小程序端），小程序的 pages/edu/* 页面调的就是这里。
 *
 * 【两组接口，别混着看】
 * 　认证组（captcha / login / logout / status）：由 EduAuthService 处理，
 * 　　负责"拿验证码、换成教务会话、查登录态"，只把会话 Cookie 存 Redis，不保存账号密码。
 * 　数据组（term / timetable / scores / exams）：由 EduDataService 处理，
 * 　　负责"学期、课表、成绩、考试"四块内容，只有教务已登录时才拿得到数据。
 *
 * 【鉴权：这 8 个接口全部要求学生登录】
 * /app/edu/** 不在 WebMvcConfig 的 APP_WHITE_LIST 里，所以拦截器会先校验学生令牌，
 * 没令牌直接 401。也就是说"教务登录"是第二层登录：先有校集账号，再绑定教务账号。
 * 好处是 userId 永远能从 UserContext 取到，Service 里不需要再问"这是谁"。
 *
 * 【这个类为什么这么薄】
 * 只做三件事：接请求、把参数交给 Service、把返回值包成 Result。
 * 不放任何 if、不查库、不处理异常 —— 异常交给全局异常处理器，重复的包装交给 Result。
 * 这样改业务只动 Service，看接口只扫这里的映射路径。
 *
 * 【教务数据的现实情况（读代码前必须知道）】
 * 课表 / 成绩 / 考试的数据来源有两套：
 * 　1. 配了 edu.fixture-dir：读本地样例 JSON，仅本地联调用，出来的数据带 fixture 标记；
 * 　2. 没配：走教务真实接口；真实接口若没接通，Service 会明确抛"未接入"类错误，
 * 　　 绝不会拿样例数据冒充真实数据 —— 这是这个模块的红线。
 *
 * @author 校集后端
 */
// 【注解作用】第三方注解（springdoc-openapi）：只影响 /doc.html 里这组接口的分组名称，
// 不参与任何业务逻辑。名字前面加 "20." 是为了在文档里把它排到最后。
@Tag(name = "20. 教务")
// 【注解作用】Spring 注解，等于 @Controller + @ResponseBody：
// 注册成 Web 层 Bean，并把每个方法的返回值直接序列化成 JSON（而不是去找同名页面）。
// 【为什么不用 @Controller】本项目全部是 JSON 接口，没有服务端渲染页面。
@RestController
// 【注解作用】Spring 注解，给整个类的所有接口加统一前缀，最终路径是 /api/app/edu/xxx
// （/api 来自 application.yml 的 context-path）。
@RequestMapping("/app/edu")
// 【注解作用】第三方注解（Lombok）：为所有 final 字段生成一个构造器，Spring 用它做构造器注入。
// 【为什么用它而不是 @Autowired 字段注入】final 字段能在编译期保证"一定会被赋值"，
// 依赖不会出现半初始化状态；也方便单元测试直接 new 出对象塞假实现。
@RequiredArgsConstructor
public class EduController {

    // 认证组：验证码、SSO 登录、退出、登录态
    private final EduAuthService eduAuthService;
    // 数据组：学期、课表、成绩、考试
    private final EduDataService eduDataService;

    // 未使用：登录走 SSO 后不需要验证码，保留此接口仅为兼容旧客户端
    @Operation(summary = "获取教务验证码")
    @GetMapping("/captcha")
    public Result<EduCaptchaVO> captcha() {
        return Result.ok(eduAuthService.captcha());
    }

    /**
     * 教务登录（走学校统一认证 CAS，不是教务子系统的账号密码）。
     *
     * 参数 req：EduLoginReq，字段为 account（学号）、password（密码）、
     * 　remember（已废弃：新版本不再保存账号密码，传什么值都不影响行为）。
     * 返回：EduStatusVO，即登录成功后的状态（已登录、脱敏账号、会话到期时间）。
     *
     * 【为什么是 POST】请求体里带密码，POST 不会把内容留在浏览器历史、代理日志、访问日志的 URL 里；
     * 用 GET 加查询参数传密码是最常见的低级事故。
     *
     * 【password 传的是明文吗】是明文走 HTTPS，到服务端后立刻用于一次 SSO 加密提交，
     * 只在内存里存在这一小段时间，用完即弃，不落库、不进日志。
     *
     * 【会话有效期】12 小时滑动续期：一直在用就一直有效，超过 12 小时没动过才要求重登；
     * 从首次登录起最多 24 小时，避免"每天打开一次就能永久免密"。
     * 之所以不用"保存密码自动重登"：密码泄漏不可撤销，Cookie 泄漏最多 24 小时且能退出撤销。
     *
     * 【失败会怎样】学号或密码错 → 400 且提示"学号或密码不正确"；
     * 教务站点自身异常 → 500 且提示"教务系统暂时无法访问"。两种情况都不会写入任何凭据。
     */
    // 【注解作用】第三方注解（springdoc）：接口文档里的摘要与补充说明。
    @Operation(summary = "教务登录", description = "账号密码只在服务端加密提交，不落库；登录后会话保持 12 小时")
    // 【注解作用】Spring 注解：把 HTTP POST /app/edu/login 映射到这个方法。
    @PostMapping("/login")
    // 【注解作用】@Valid 来自 Jakarta Bean Validation（第三方规范，由 Spring 触发）：
    //   让 EduLoginReq 上的 @NotBlank 之类的校验生效，不合法直接 400，进不来方法体。
    // 【注解作用】@RequestBody 是 Spring 注解：把 JSON 请求体反序列化成 EduLoginReq 对象。
    // 【为什么两个注解都要】少了 @Valid 就只反序列化不校验，空学号也会一路走到 SSO 请求。
    /** 登录 */
    public Result<EduStatusVO> login(@Valid @RequestBody EduLoginReq req) {
        return Result.ok(eduAuthService.login(req));
    }

    /**
     * 退出教务：清掉服务端的教务会话，同时删掉"记住的账号"。
     *
     * 返回：Result 空体，前端只需判断 code。
     *
     * 【为什么还要删历史凭据】旧版本曾在 edu_credential 表里存过加密的账号密码。
     * 用户点"退出"的语义是"这台设备不再保留我的教务身份"，只删 Redis 会话、留着旧密文，
     * 等于没退干净。新版本不再写入，但退出时顺手清掉存量。
     *
     * 【幂等】本来就没登录、没保存过凭据时调用也返回成功，不报错：
     * 退出这种操作重复执行不应该失败，否则前端要额外判状态。
     */
    // 【注解作用】第三方注解（springdoc）：文档里的接口说明。
    @Operation(summary = "退出教务并清除会话")
    // 【注解作用】Spring 注解：映射 POST /app/edu/logout。
    // 【为什么是 POST 不是 DELETE】这不是删除某个资源，而是"改变登录状态"的动作；
    //   而且前端退出按钮走的是统一的 POST 请求封装，用 DELETE 反而要单开一条通道。
    @PostMapping("/logout")
    /** 退出 */
    public Result<Void> logout() {
        eduAuthService.logout();
        return Result.ok();
    }

    /**
     * 查当前教务登录状态，页面进入时先调它决定显示"去登录"还是"已连接"。
     *
     * 返回：EduStatusVO，三个关键字段 ——
     * 　loggedIn：当前有没有有效的教务会话（Redis 里有 edu:session:userId 且未过期）；
     * 　saved：数据库里有没有未过期的"记住的账号"；
     * 　accountMasked：脱敏账号，如 3124****95，用于页面显示"已连接 3124****95"。
     *
     * 【为什么这里不去"验票"】验票要额外请求一次教务，而 status 每次进页面都调。
     * 即使票已失效，课表接口会降级返回缓存并标 stale，用户不会撞到硬错误，
     * 所以这里只做本地判断，把成本留给真正需要数据的那次请求。
     *
     * 【saved 字段】保留只为兼容旧客户端，恒为 false；新版本不再保存账号密码。
     */
    // 【注解作用】第三方注解（springdoc）：文档里的接口说明。
    @Operation(summary = "教务登录状态")
    // 【注解作用】Spring 注解：映射 GET /app/edu/status；纯查询，重复调用不会改变结果。
    @GetMapping("/status")
    /** 状态 */
    public Result<EduStatusVO> status() {
        return Result.ok(eduAuthService.status());
    }

    /**
     * 当前学年学期（课表页、成绩页的默认值都从这里取）。
     *
     * 返回：EduTimetableVO.Term，含 academicYear（如 2026-2027）、term（1 或 2）、
     * 　currentWeek（当前第几周）、weekCount（本学期总周数，当前固定 20）。
     *
     * 【为什么由后端推算，而不是让学生手填】
     * 手填有两个必然的坑：一是学生不知道自己算第几周，二是填错之后课表整页偏移。
     * 后端按日期推：9-12 月算第一学期、2-8 月算第二学期、1 月归上一学年第一学期，
     * 起始周取该学期第一个周一，再用"今天减起始日 除以 7"算出当前周。
     * 好处是全端只有一处口径，小程序和后台看到的周次永远一致。
     *
     * 【代价】学校校历若有调整（比如提前开学），这里不会自动跟着变，要改代码里的规则。
     */
    // 【注解作用】第三方注解（springdoc）：文档里的摘要与说明。
    @Operation(summary = "当前学期", description = "默认学年学期由后端按校历推算，学生不用手填")
    // 【注解作用】Spring 注解：映射 GET /app/edu/term。
    @GetMapping("/term")
    public Result<EduTimetableVO.Term> term() {
        return Result.ok(eduDataService.currentTerm());
    }

    /**
     * 课表。
     *
     * 参数 week：要看第几周；传 0 或负数表示"按当前日期推算"（前端默认就传 0，不用自己算周次）。
     * 参数 all：true 表示一次返回整学期的课，false 表示只返回 week 这一周的课。
     * 参数 refresh：true 表示跳过缓存、强制请求教务（用户点刷新时用；平时走 30 分钟缓存）。
     * 返回：EduTimetableVO，含课程列表、所属周次、fetchedAt（抓取时刻）和 stale（是否降级数据）。
     *
     * 【all=true 是给谁用的】课表页支持左右滑动切周，如果每滑一次都请求一次后端，
     * 会变成"手指一动一个请求"。所以前端进入页面时用 all=true 拿整学期，
     * 之后切周全在本地过滤 —— 一次请求换几十次流畅切换。
     * 代价是首屏响应体更大（一学期几十条课），但这是静态数据，网络开销远小于交互卡顿。
     *
     * 【参数为什么有默认值】@RequestParam(defaultValue=...) 让 /timetable 不带参数也能调通，
     * 前端首次进入、调试、接口文档的"Try it out"都不用拼参数。
     *
     * 【拿不到数据时】没登录教务 → 提示未登录；教务挂了但缓存里还有旧数据 → 返回旧数据并标 stale，
     * 前端提示"可能不是最新"；既没会话也没缓存 → 才报错。单周没课时返回空数组，页面显示"本周没有课"。
     */
    // 【注解作用】第三方注解（springdoc）：文档里的摘要与参数说明。
    @Operation(summary = "课表", description = "week<=0 表示按当前日期推算；refresh=true 跳过缓存强制刷新")
    // 【注解作用】Spring 注解：映射 GET /app/edu/timetable。
    @GetMapping("/timetable")
    // 【注解作用】@RequestParam 是 Spring 注解：从 URL 查询串取值，如 ?week=3&all=true。
    //   defaultValue 表示"参数缺省时用什么值"，省得前端每次都传。
    //   注意基本类型 int / boolean 一定要给 defaultValue：@RequestParam 默认 required=true，
    //   不给默认值又不传参数，会被 Spring 直接判成"缺少必需参数"返回 400。
    public Result<EduTimetableVO> timetable(@RequestParam(defaultValue = "0") int week,
                                            @RequestParam(defaultValue = "false") boolean all,
                                            @RequestParam(defaultValue = "false") boolean refresh) {
        return Result.ok(eduDataService.timetable(week, all, refresh));
    }

    /**
     * 成绩与 GPA。
     *
     * 返回：EduScoreVO，含四组统计口径（按学期 / 按学年 / 按年级 / 全部）+ 课程明细。
     *
     * 【为什么一次返回四种口径，而不是让前端传参数选】四种口径都是同一批成绩的不同聚合，
     * 服务端算一遍就能全给；让学生切换时再请求一次，等于把同一份数据读四遍。
     * 代价是响应体稍大，但成绩数据量小（几十门课），可忽略。
     *
     * 【GPA 口径统一在 EduGpaCalculator】教务给了绩点就用教务的；没给才按
     * "成绩 60 分以上按 (成绩-50)/10 折算、上限 5.0、低于 60 记 0"补算，
     * 分母包含挂科课程。不要在前端另写一套算法，否则同一个学生两处显示不同 GPA。
     */
    // 【注解作用】第三方注解（springdoc）：文档里的摘要与说明。
    @Operation(summary = "成绩与GPA", description = "按学期/学年/年级/全部四个口径统一计算")
    // 【注解作用】Spring 注解：映射 GET /app/edu/scores。
    @GetMapping("/scores")
    public Result<EduScoreVO> scores() {
        return Result.ok(eduDataService.scores());
    }

    /**
     * 考试安排。
     *
     * 返回：EduExamVO，按考试时间排好序的场次列表（科目、时间、地点、座位号等）。
     *
     * 【为什么单独一个接口，不塞进课表】考试是"某几天集中发生的事件"，课表是"每周重复的规律"，
     * 两者在页面上也是两个 tab。合并返回会让课表接口在不考试的时候也带着一个空数组。
     */
    // 【注解作用】第三方注解（springdoc）：文档里的接口说明。
    @Operation(summary = "考试安排")
    // 【注解作用】Spring 注解：映射 GET /app/edu/exams。
    @GetMapping("/exams")
    public Result<EduExamVO> exams() {
        return Result.ok(eduDataService.exams());
    }
}
