package com.gdut.xiaojii.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdut.xiaojii.common.BizException;
import com.gdut.xiaojii.common.EduGpaCalculator;
import com.gdut.xiaojii.common.EduSessionStore;
import com.gdut.xiaojii.common.ResultCode;
import com.gdut.xiaojii.common.UserContext;
import com.gdut.xiaojii.common.lock.DistributedLock;
import com.gdut.xiaojii.dto.vo.EduExamVO;
import com.gdut.xiaojii.dto.vo.EduScoreVO;
import com.gdut.xiaojii.dto.vo.EduTimetableVO;
import com.gdut.xiaojii.entity.UserProfile;
import com.gdut.xiaojii.mapper.UserProfileMapper;
import com.gdut.xiaojii.service.EduAuthService;
import com.gdut.xiaojii.service.EduDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教务数据实现。
 *
 * <p>真实接口（课表/成绩/考试）的路径必须在「真实账号登录后的会话」下确认，
 * 本类在未配置样例目录时直接抛错，避免把假数据当真实数据展示。
 *
 * @author 校集后端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EduDataServiceImpl implements EduDataService {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");
    /** 界面上的「更新于 11:20」，只要时分 */
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 默认 12 节作息（大学城），真实课表解析后会以教务返回为准 */
    /**
     * 广工作息（用户 2026-10-20 确认的口径，全项目唯一标准）：
     *   1-2 节 08:30-10:25   3-4 节 10:25-12:00   5 节 13:50-14:35
     *   6-7 节 14:40-16:15   8-9 节 16:30-18:05   10-12 节 18:30-20:55
     * 每节 45 分钟；1-2 节之间是 25 分钟大课间，其余课间 5 分钟。
     * ⛔ 改这张表前先确认上面几组区间仍然成立。
     */
    private static final String[][] PERIOD_TIMES = {
            {"08:30", "09:15"}, {"09:40", "10:25"}, {"10:25", "11:10"}, {"11:15", "12:00"},
            {"13:50", "14:35"}, {"14:40", "15:25"}, {"15:30", "16:15"}, {"16:30", "17:15"},
            {"17:20", "18:05"}, {"18:30", "19:15"}, {"19:20", "20:05"}, {"20:10", "20:55"}
    };

    private static final String[] GRADE_LABELS = {"一", "二", "三", "四", "五", "六"};

    /** 课表缓存键前缀，完整形态 edu:timetable:{userId}:{termCode}，如 edu:timetable:12:202601 */
    private static final String TT_PREFIX = "edu:timetable:";

    /**
     * 新鲜期：缓存写入后多久之内直接返回，不再请求教务。
     *
     * 【写法】30 分钟，而不是按天或按学期。
     * 【为什么】一节课 45 分钟，调课换教室的概率在 30 分钟内极低；同时 30 分钟足以挡掉
     *   「切 Tab、退出再进、多人共用一台设备」这类重复请求，把外部请求量压到原来的几十分之一。
     * 【代价】极端情况下最多看到 30 分钟前的课表；用户点刷新可以立刻穿透（refresh=true）。
     */
    private static final Duration TT_FRESH = Duration.ofMinutes(30);

    /**
     * 缓存保留时长：即使过了新鲜期也不删，留着给降级用。
     *
     * 【为什么保留 24 小时】教务临时挂了或会话失效时，返回 24 小时内的旧课表
     *   并标记 stale，比直接报错有用得多 —— 课表一天内的变化概率很低，
     *   而「打不开」是 100% 的损失。
     */
    private static final Duration TT_KEEP = Duration.ofHours(24);

    private final UserProfileMapper profileMapper;
    private final StringRedisTemplate redis;
    private final EduSessionStore sessionStore;
    // 分布式锁：课表缓存重建互斥（缓存击穿的经典解法）
    private final DistributedLock distributedLock;
    // 教务登录服务：仅用于 Cookie 失效时的自动重登。
    // 【依赖方向】Data → Auth 单向（Auth 不依赖 Data），不会形成循环依赖
    private final EduAuthService eduAuthService;

    /** 课表重建锁前缀，完整形态 lock:edu:timetable:{userId}:{termCode}，如 lock:edu:timetable:16:202601 */
    private static final String LOCK_TT_PREFIX = "lock:edu:timetable:";

    /** 等锁时长：200 毫秒。抢不到就立刻用旧缓存顶上，不让用户为了"可能是最新的"转圈 3 秒 */
    private static final long LOCK_TT_WAIT_MS = 200L;

    /**
     * 锁租期：30 秒 —— ⚠️ 这里故意不用看门狗，和定时任务的取舍相反。
     *
     * 【为什么教务锁不续期，而定时任务锁续期】
     *   定时任务的耗时是"长但可预期"（扫 N 条 + 发 N 条通知），中途丢锁代价大 → 用看门狗续期。
     *   教务抓取是"可能 hang 住"：教务系统卡死时，如果锁一直续期，
     *   这个用户就永远打不开课表了（锁永不释放）。
     *   固定 30 秒租期保证最坏情况：30 秒后锁自动释放，下一次请求能重试。
     * 【判据】锁要保护的任务，失败后能不能自己恢复？
     *   能（定时任务下一轮会重来）→ 续期；不能（用户被卡死）→ 固定租期兜底。
     */
    private static final long LOCK_TT_LEASE_MS = 30_000L;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Value("${edu.base-url:https://jxfw.gdut.edu.cn}")
    private String eduBaseUrl;

    /** 未使用（生产）：样例目录只有本地 application-local.yml 会配置，生产为空。
     *  相关方法 isFixtureMode() / load() 同样只服务本地调试，生产走不到。 */
    @Value("${edu.fixture-dir:}")
    private String fixtureDir;

    /* ==================== 课表 ==================== */

    /** 课表入口。week=0 按今天推算当前周；all=true 一次返回整学期（前端切周零请求）；refresh=true 跳过缓存 */
    @Override
    public EduTimetableVO timetable(int week, boolean all, boolean refresh) {
        if (!isFixtureMode()) {
            return realTimetable(week, all, refresh);
        }
        FixtureTimetable fx = load("edu-timetable.json", FixtureTimetable.class);
        EduTimetableVO.Term term = currentTerm();
        int showWeek = week > 0 ? week : term.getCurrentWeek();

        List<EduTimetableVO.Course> courses = new ArrayList<>();
        List<FixtureCourse> fixtureCourses = fx.getCourses() == null ? List.of() : fx.getCourses();
        for (FixtureCourse c : fixtureCourses) {
            if (c.getWeeks() != null && !c.getWeeks().isEmpty() && !c.getWeeks().contains(showWeek)) {
                continue;
            }
            EduTimetableVO.Course vo = new EduTimetableVO.Course();
            String id = c.getName() + "@" + c.getDay() + "-" + c.getStartPeriod();
            vo.setId(Integer.toHexString(id.hashCode()));
            vo.setName(c.getName());
            vo.setTeacher(c.getTeacher());
            vo.setRoom(c.getRoom());
            vo.setDay(c.getDay());
            vo.setStartPeriod(c.getStartPeriod());
            vo.setEndPeriod(c.getEndPeriod() == null ? c.getStartPeriod() : c.getEndPeriod());
            vo.setWeeks(c.getWeeks());
            vo.setColor(c.getColor());
            vo.setConflict(false);
            vo.setSlotIndex(0);
            vo.setSlotCount(1);
            courses.add(vo);
        }

        markConflicts(courses);
        courses.sort(Comparator.comparing(EduTimetableVO.Course::getDay)
                .thenComparing(EduTimetableVO.Course::getStartPeriod));

        // 个人资料没填校区时，用教室前缀兜一层，避免页面空着或让学生再选一次
        if (term.getCampus() == null || term.getCampus().isBlank()) {
            term.setCampus(inferCampus(courses));
        }

        EduTimetableVO vo = new EduTimetableVO();
        vo.setTerm(term);
        vo.setWeek(showWeek);
        vo.setPeriods(periods());
        vo.setCourses(courses);
        vo.setFixture(true);
        // 样例模式没有「缓存」概念，标成刚刚取到的，前端统一显示更新时间
        vo.setFetchedAt(nowHHmm());
        vo.setStale(false);
        return vo;
    }


    /**
     * 课表入口（真实模式）：缓存三档 + 分布式锁防「缓存击穿」。
     *
     * ============================================================
     * 【完整预演｜用户 16 连点 5 次下拉刷新（300ms 内）】
     * ============================================================
     * 无锁：
     *   5 个线程同时发现"缓存不新鲜" → 5 个都去打教务 →
     *   教务侧看到同一个 JSESSIONID 并发发来 5 个请求 →
     *   轻则返回 2 个空页 + 3 个正常页（用户轮询时看到课表闪一下变空），
     *   重则被判定为异常访问、直接封掉这个会话。
     * 有锁：
     *   线程 1 抢到锁去抓；线程 2~5 等 200ms 后放弃 → 直接返回旧缓存并标 stale
     *   → 教务只被打 1 次；用户看到的是 30 分钟前的课表，界面提示"数据可能不是最新"
     *
     * ============================================================
     * 【为什么这里必须用分布式锁，而不是本地锁】
     * ============================================================
     * 冲突对象不是自己的数据库，而是**外部教务系统**。
     * 数据库约束管不到它、事务管不到它、CAS 更管不到它 ——
     * 这是"锁保护的是一份外部共享资源"的教科书场景。
     * 用本地 ReentrantLock 今天够用，但一旦部署第二台机器就失效（两台各锁各的），
     * 所以直接用分布式锁，不为将来留返工。
     *
     * 【为什么要按 userId + termCode 分段】
     * 用户 16 和用户 17 查课表互不影响（各自的教务会话、各自的缓存）；
     * 同一用户换学期也是不同的缓存，没必要互相阻塞。
     * ⛔ 写成全局锁 lock:edu:timetable 会让全校学生排队查课表。
     *
     * @param week    周次，0 = 按今天推算
     * @param all     true = 一次返回整学期
     * @param refresh true = 用户主动下拉刷新，跳过新鲜期判断
     */
    private EduTimetableVO realTimetable(int week, boolean all, boolean refresh) {
        Long userId = UserContext.requireUserId();
        EduTimetableVO.Term term = currentTerm();
        String termCode = termCodeOf(term);
        String cacheKey = TT_PREFIX + userId + ":" + termCode;

        CachedSemester cached = readCache(cacheKey);

        // 第一优先：缓存还新鲜，且用户没有主动要求刷新 —— 直接返回，不打教务
        if (!refresh && cached != null && cached.ageMillis() < TT_FRESH.toMillis()) {
            return build(cached, week, all, false);
        }

        // ★ 缓存击穿防线的核心：同一用户同一学期，只允许一个线程去抓教务
        String lockKey = LOCK_TT_PREFIX + userId + ":" + termCode;
        return distributedLock.tryCall(lockKey, LOCK_TT_WAIT_MS, LOCK_TT_LEASE_MS,
                () -> rebuildUnderLock(cacheKey, term, termCode, week, all, refresh, cached),
                () -> fallbackWhileBusy(cached, week, all, termCode));
    }

    /**
     * 这一段是"拿到锁之后，再确认一次没人写过，然后才真正去打教务"。
     *
     * 【为什么进锁之后还要再读一次缓存（双重检查）】
     *   线程 A 抢到锁正在抓教务（要 2 秒），线程 B 在门外等了 200ms 认输走了。
     *   但如果 B 等的是 3 秒，它抢到锁时 A 早就把缓存写好了 ——
     *   这时候再打一次教务就是纯浪费，还会让教务以为"这个会话在并发请求"。
     *   所以进锁第一件事是重读缓存。
     *
     * 【为什么任务失败要降级而不是直接抛】
     *   「看到 20 分钟前的课表」远好于「打不开」，教务抖动时功能不至于整块消失。
     *   代价是用户可能看到已调课的旧安排，所以必须把 stale 标记传给前端，
     *   让界面明说"数据不是最新的"，不能装作没事。
     */
    private EduTimetableVO rebuildUnderLock(String cacheKey, EduTimetableVO.Term term, String termCode,
                                            int week, boolean all, boolean refresh,
                                            CachedSemester staleBeforeLock) {
        // ★★ 双重检查：等锁期间别人可能已经把缓存写好了
        CachedSemester again = readCache(cacheKey);
        if (!refresh && again != null && again.ageMillis() < TT_FRESH.toMillis()) {
            return build(again, week, all, false);
        }

        try {
            CachedSemester fresh = fetchSemester(term);
            writeCache(cacheKey, fresh);
            return build(fresh, week, all, false);
        } catch (BizException e) {
            // 优先用"锁内刚读到的"，它比进锁前的更接近现在
            CachedSemester fallback = again != null ? again : staleBeforeLock;
            if (fallback != null) {
                log.warn("[教务] 课表请求失败，降级返回缓存 termCode={} ageMin={} err={}",
                        termCode, fallback.ageMillis() / 60000, e.getMessage());
                return build(fallback, week, all, true);
            }
            throw e;
        }
    }

    /**
     * 这一段是"没抢到锁，那就用上次的课表顶着"。
     *
     * 【两种情况要分开处理】
     *   ① 有旧缓存 → 直接返回它，标 stale=true。用户几乎无感，最多数据旧一点。
     *   ② 没有缓存（首次访问就撞上别人在抓）→ 只能让用户稍等，
     *      给一句人话"课表正在加载"，而不是把 500 堆栈甩给他。
     * 【为什么第二种不"排队等锁"】首次加载本来就要等几秒，
     *   让他多等 3 秒抢锁，体验和直接重试差不多，不如立刻给明确提示。
     */
    private EduTimetableVO fallbackWhileBusy(CachedSemester cached, int week, boolean all, String termCode) {
        if (cached != null) {
            log.warn("[教务] 课表正在重建，先用旧缓存顶上 termCode={} ageMin={}",
                    termCode, cached.ageMillis() / 60000);
            return build(cached, week, all, true);
        }
        log.info("[教务] 课表首次加载中，请求被挡住 termCode={}", termCode);
        throw BizException.of(ResultCode.SERVER_ERROR, "课表正在加载，请稍后重试");
    }

    /**
     * 请求教务并整理成「整学期」快照。
     *
     * 【为什么总是取整学期】教务接口按周次返回，zc 传空才能一次拿全。
     *   缓存单周会产生 20 份碎片缓存，切周命中率极低；存整学期只需一份，
     *   之后按周过滤在内存里做，成本可以忽略。
     */
    private CachedSemester fetchSemester(EduTimetableVO.Term term) {
        String termCode = termCodeOf(term);
        // zc 留空 = 一次取回整学期的课，weeks 才有完整信息
        String path = "/xsgrkbcx!getKbRq.action?xnxqdm=" + termCode + "&zc=&_="
                + System.currentTimeMillis();
        JsonNode root = jxfwJson(path, eduBaseUrl + "/xsgrkbcx!xsgrkbMain.action");

        List<EduTimetableVO.Course> list = parseKbRq(root);
        if (list.isEmpty()) {
            // 整学期一门课都没有，通常意味着接口返回异常，而不是学生真的没课
            throw BizException.of(ResultCode.SERVER_ERROR, "暂无课程安排");
        }
        if (term.getCampus() == null || term.getCampus().isBlank()) {
            term.setCampus(inferCampus(list));
        }

        CachedSemester snapshot = new CachedSemester();
        snapshot.setTerm(term);
        snapshot.setCourses(list);
        snapshot.setFetchedAt(System.currentTimeMillis());
        return snapshot;
    }

    /**
     * 把整学期快照按周裁剪成前端要的视图。
     *
     * 参数 snapshot：整学期快照（可能来自缓存，也可能是刚抓的）
     * 参数 week：周次，小于等于 0 表示按当前日期推算
     * 参数 all：true 返回整学期，false 只返回 week 那一周
     * 参数 stale：true 表示这份数据来自旧缓存，教务当前不可达
     * 返回：前端直接渲染的视图
     */
    private EduTimetableVO build(CachedSemester snapshot, int week, boolean all, boolean stale) {
        EduTimetableVO.Term term = snapshot.getTerm();
        int showWeek = week > 0 ? week
                : (term.getCurrentWeek() == null ? 1 : term.getCurrentWeek());

        List<EduTimetableVO.Course> courses = new ArrayList<>();
        for (EduTimetableVO.Course c : snapshot.getCourses()) {
            // all=true 时把整学期课程一次返回，前端按周自行过滤（左右滑动切周用）
            if (all || (c.getWeeks() != null && c.getWeeks().contains(showWeek))) {
                courses.add(c);
            }
        }

        // 【写法】冲突标记必须在过滤之后做，不能提前在整学期上做
        // 【为什么】slotCount 表示「同一格里挤了几门课」，要按当前这一周的实际课程算；
        //   提前在整学期标记会让「某周只剩一门课」的格子仍显示成两门并排，布局直接错乱
        markConflicts(courses);
        courses.sort(Comparator.comparing(EduTimetableVO.Course::getDay)
                .thenComparing(EduTimetableVO.Course::getStartPeriod));

        EduTimetableVO vo = new EduTimetableVO();
        vo.setTerm(term);
        vo.setWeek(showWeek);
        vo.setPeriods(periods());
        vo.setCourses(courses);
        vo.setFixture(false);
        vo.setStale(stale);
        vo.setFetchedAt(hhmmOf(snapshot.getFetchedAt()));
        return vo;
    }

    /** 从 Redis 读整学期快照。解析失败当作没有（防止旧结构卡死功能），返回 null 由上层重新拉 */
    private CachedSemester readCache(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return JSON.readValue(raw, CachedSemester.class);
        } catch (Exception e) {
            // 缓存结构变了（比如 VO 加了字段）就当成没有，让它重新拉一次，别让旧缓存卡死功能
            log.warn("[教务] 课表缓存解析失败，忽略 key={}", key);
            return null;
        }
    }

    /** 把整学期快照写进 Redis（TTL 24 小时）。写失败只记日志不抛，不影响本次返回 */
    private void writeCache(String key, CachedSemester snapshot) {
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(snapshot), TT_KEEP);
        } catch (Exception e) {
            // 写缓存失败不影响本次返回，只是下次还得再问一次教务
            log.warn("[教务] 课表缓存写入失败 key={}", key, e);
        }
    }

    /** 毫秒时间戳 → HH:mm，用于界面显示「更新于 11:20」 */
    private static String hhmmOf(long millis) {
        if (millis <= 0L) {
            return "";
        }
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(HHMM);
    }

    /** 当前时刻 HH:mm（如 15:20），用于 VO 的 fetchedAt 字段，告诉前端这份数据是什么时候抓的 */
    private static String nowHHmm() {
        return LocalTime.now().format(HHMM);
    }

    /**
     * 解析 getKbRq 的返回：外层是分组数组，每条形如
     * {kcmc 课程名, teaxms 教师, jxcdmc 教室, xq 星期, jcdm2 "03,04", zc 周次}
     * 同一门课会有多条（每个周次一条），这里按 课程+星期+节次+教室 合并 weeks。
     */
    private List<EduTimetableVO.Course> parseKbRq(JsonNode root) {
        List<EduTimetableVO.Course> list = new ArrayList<>();
        if (root == null || root.isNull()) {
            return list;
        }
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode g : root) {
                if (g.isArray()) {
                    for (JsonNode it : g) {
                        items.add(it);
                    }
                } else {
                    items.add(g);
                }
            }
        }

        Map<String, EduTimetableVO.Course> merged = new LinkedHashMap<>();
        for (JsonNode n : items) {
            String name = n.path("kcmc").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            int day = n.path("xq").asInt(0);
            if (day < 1 || day > 7) {
                continue;
            }
            int[] span = parseJcdm(n.path("jcdm2").asText(""));
            if (span == null) {
                continue;
            }
            String room = n.path("jxcdmc").asText("").trim();
            String teacher = n.path("teaxms").asText("").trim();
            int zc = n.path("zc").asInt(0);

            String key = name + "|" + day + "|" + span[0] + "-" + span[1] + "|" + room;
            EduTimetableVO.Course c = merged.get(key);
            if (c == null) {
                c = new EduTimetableVO.Course();
                c.setId(Integer.toHexString(key.hashCode()));
                c.setName(name);
                c.setTeacher(teacher);
                c.setRoom(room);
                c.setDay(day);
                c.setStartPeriod(span[0]);
                c.setEndPeriod(span[1]);
                c.setWeeks(new ArrayList<>());
                c.setConflict(false);
                c.setSlotIndex(0);
                c.setSlotCount(1);
                merged.put(key, c);
            }
            if (zc > 0 && !c.getWeeks().contains(zc)) {
                c.getWeeks().add(zc);
            }
        }
        for (EduTimetableVO.Course c : merged.values()) {
            c.getWeeks().sort(Integer::compareTo);
            list.add(c);
        }
        return list;
    }

    /**
     * 节次串 → [起始节, 结束节]。
     * 教务实际会给出 "03,04" / "05,06,07" / "05,06,07,08" / "05"（单节）/ "0304" 多种形态，
     * 所以取所有数字的最值，不能只取前两个（否则 3 节、4 节连上的课会被截断，单节课会被丢掉）。
     */
    private static int[] parseJcdm(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int min = 99;
        int max = 0;
        for (String part : text.split("[^0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() <= 2) {
                int v = Integer.parseInt(part);
                min = Math.min(min, v);
                max = Math.max(max, v);
            } else if (part.length() == 4) {
                // "0304" 这种没有分隔符的四位数
                int a = Integer.parseInt(part.substring(0, 2));
                int b = Integer.parseInt(part.substring(2, 4));
                min = Math.min(min, Math.min(a, b));
                max = Math.max(max, Math.max(a, b));
            }
        }
        if (max == 0) {
            return null;
        }
        return new int[]{Math.max(1, min), Math.min(12, max)};
    }




    /** 学期代码：2026-2027 第 1 学期 → 202601 */
    private static String termCodeOf(EduTimetableVO.Term term) {
        String y = term.getAcademicYear() == null || term.getAcademicYear().length() < 4
                ? String.valueOf(LocalDate.now().getYear())
                : term.getAcademicYear().substring(0, 4);
        int no = term.getTermNo() == null ? 1 : term.getTermNo();
        return y + (no == 1 ? "01" : "02");
    }

    /** 同一格（星期 + 起止节）多门课 → 标记冲突，前端并排压缩展示 */
    private void markConflicts(List<EduTimetableVO.Course> courses) {
        Map<String, List<EduTimetableVO.Course>> slots = new LinkedHashMap<>();
        for (EduTimetableVO.Course c : courses) {
            String key = c.getDay() + "-" + c.getStartPeriod() + "-" + c.getEndPeriod();
            slots.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        for (List<EduTimetableVO.Course> group : slots.values()) {
            if (group.size() > 1) {
                for (int i = 0; i < group.size(); i++) {
                    group.get(i).setConflict(true);
                    group.get(i).setSlotIndex(i);
                    group.get(i).setSlotCount(group.size());
                }
            }
        }
    }

    /**
     * 按教室前缀推断校区（个人资料没填时的兜底）：
     * 教1—教6、工1—工3 属大学城；其余含校区名的直接取。
     */
    private String inferCampus(List<EduTimetableVO.Course> courses) {
        for (EduTimetableVO.Course c : courses) {
            String room = c.getRoom();
            if (room == null || room.isBlank()) {
                continue;
            }
            if (room.contains("东风")) return "东风路";
            if (room.contains("龙洞")) return "龙洞";
            if (room.contains("番禺")) return "番禺";
            if (room.matches("^(教[1-6]|工[1-3]).*")) return "大学城";
        }
        return null;
    }

    /** 第 1~12 节课的起止时间表（如 1: 08:00-08:45）。前端用它把节次换算成可读的时间段 */
    private List<EduTimetableVO.Period> periods() {        List<EduTimetableVO.Period> list = new ArrayList<>();
        for (int i = 0; i < PERIOD_TIMES.length; i++) {
            EduTimetableVO.Period p = new EduTimetableVO.Period();
            p.setNo(i + 1);
            p.setStart(PERIOD_TIMES[i][0]);
            p.setEnd(PERIOD_TIMES[i][1]);
            list.add(p);
        }
        return list;
    }

    /* ==================== 学期（不让学生手填） ==================== */

    /** 推算当前学期：按今天日期套校历（9-12月=第一学期），起始周=该学期第一个周一；校区优先取用户资料 */
    @Override
    public EduTimetableVO.Term currentTerm() {
        LocalDate today = LocalDate.now();
        int y = today.getYear();
        int m = today.getMonthValue();

        String academicYear;
        int termNo;
        LocalDate start;
        if (m >= 9) {
            // 9—12 月：本学年第一学期
            academicYear = y + "-" + (y + 1);
            termNo = 1;
            start = mondayOf(LocalDate.of(y, 9, 1));
        } else if (m == 1) {
            // 1 月：仍是上一年 9 月开学的第一学期
            academicYear = (y - 1) + "-" + y;
            termNo = 1;
            start = mondayOf(LocalDate.of(y - 1, 9, 1));
        } else {
            // 2—8 月：第二学期（寒假后 2 月下旬开学）
            academicYear = (y - 1) + "-" + y;
            termNo = 2;
            start = mondayOf(LocalDate.of(y, 2, 17));
        }

        int weekCount = 20;
        long weeks = ChronoUnit.DAYS.between(start, today) / 7 + 1;
        int currentWeek = (int) Math.max(1, Math.min(weeks, weekCount));

        EduTimetableVO.Term term = new EduTimetableVO.Term();
        term.setAcademicYear(academicYear);
        term.setTermNo(termNo);
        term.setTermName(academicYear + " 第" + (termNo == 1 ? "一" : "二") + "学期");
        term.setStartDate(start.format(DATE));
        term.setCurrentWeek(currentWeek);
        term.setWeekCount(weekCount);
        term.setSource("schedule");
        term.setCampus(defaultCampus());
        return term;
    }

    /**
     * 取该日期所在周的周一。
     * 学校按"开学那一周"算第 1 周，并不要求 9 月 1 日恰好是周一，
     * 所以这里往前回退到本周周一（之前写成"之后第一个周一"会让周次整体少一周）。
     */
    private static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - 1L);
    }

    /**
     * 校区默认值：优先用个人资料里已经填过的校区，其次按教室前缀推断，都不中就给空。
     * 目的是让学生不用每次手填。
     */
    private String defaultCampus() {
        UserProfile p = profile();
        if (p != null && p.getCampus() != null && !p.getCampus().isBlank()) {
            return p.getCampus();
        }
        return null;
    }

    /** 读当前用户的扩展资料（主要为了取 campus 校区，用于课表默认校区） */
    private UserProfile profile() {
        try {
            Long userId = UserContext.getUserId();
            return userId == null ? null : profileMapper.selectById(userId);
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 成绩 + GPA ==================== */

    /** 成绩 + GPA。一次返回四种口径（按学期/学年/年级/全部），避免前端切换时重复请求 */
    @Override
    public EduScoreVO scores() {
        boolean real = !isFixtureMode();
        FixtureScores fx = real ? fetchRealScores() : load("edu-scores.json", FixtureScores.class);
        List<FixtureScore> raws = fx.getCourses() == null ? List.of() : fx.getCourses();
        if (raws.isEmpty()) {
            throw BizException.of(ResultCode.SERVER_ERROR, "暂无成绩数据");
        }

        // ---- 按学期聚合（同一学期内课程用统一口径算 GPA）----
        Map<String, List<FixtureScore>> byTerm = new LinkedHashMap<>();
        for (FixtureScore r : raws) {
            String key = r.getAcademicYear() + "-" + r.getTermNo();
            byTerm.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<EduScoreVO.TermScore> terms = new ArrayList<>();
        byTerm.forEach((key, list) -> terms.add(buildTerm(key, list)));
        terms.sort(Comparator.comparing(EduScoreVO.TermScore::getAcademicYear).reversed()
                .thenComparing(EduScoreVO.TermScore::getTermNo, Comparator.reverseOrder()));

        // ---- 全部 / 按学年 / 按年级 ----
        EduScoreVO.Stats stats = new EduScoreVO.Stats();
        stats.setGpa(gpaOf(raws));
        stats.setCredits(EduGpaCalculator.round(creditsOf(raws), 2));
        stats.setEarnedCredits(EduGpaCalculator.round(earnedOf(raws), 2));
        stats.setCourseCount(raws.size());
        stats.setTermCount(terms.size());
        stats.setByYear(groupBy(raws, r -> r.getAcademicYear(),
                year -> year + " 学年"));
        stats.setByGrade(byGrade(raws));

        EduScoreVO vo = new EduScoreVO();
        vo.setTerms(terms);
        vo.setStats(stats);
        vo.setFixture(!real);
        return vo;
    }

    /** 把一个学期的成绩聚合成一组统计：GPA、总学分、已获学分、门数 */
    private EduScoreVO.TermScore buildTerm(String key, List<FixtureScore> list) {
        EduScoreVO.TermScore t = new EduScoreVO.TermScore();
        FixtureScore first = list.get(0);
        t.setKey(key);
        t.setAcademicYear(first.getAcademicYear());
        t.setTermNo(first.getTermNo());
        t.setTermName(first.getAcademicYear() + " 第" + (first.getTermNo() == 1 ? "一" : "二") + "学期");
        t.setGpa(gpaOf(list));
        t.setCredits(EduGpaCalculator.round(creditsOf(list), 2));
        t.setEarnedCredits(EduGpaCalculator.round(earnedOf(list), 2));
        t.setCourseCount(list.size());

        List<EduScoreVO.CourseScore> courses = new ArrayList<>();
        for (FixtureScore r : list) {
            EduScoreVO.CourseScore c = new EduScoreVO.CourseScore();
            c.setName(r.getName());
            c.setCredit(r.getCredit());
            c.setScore(r.getScore());
            Double point = EduGpaCalculator.point(r.getScore(), r.getPoint());
            c.setPoint(point);
            c.setNature(r.getNature());
            c.setExamType(r.getExamType());
            c.setPassed(EduGpaCalculator.passed(r.getScore(), point));
            courses.add(c);
        }
        courses.sort(Comparator.comparing(EduScoreVO.CourseScore::getName));
        t.setCourses(courses);
        return t;
    }

    /** 按学分加权算 GPA。口径统一走 EduGpaCalculator（教务有绩点就用，没有才按分数折算） */
    private Double gpaOf(List<FixtureScore> list) {
        List<Double> credits = new ArrayList<>();
        List<Double> points = new ArrayList<>();
        for (FixtureScore r : list) {
            credits.add(r.getCredit());
            points.add(EduGpaCalculator.point(r.getScore(), r.getPoint()));
        }
        return EduGpaCalculator.weighted(credits, points);
    }

    /** 统计总学分（分母，含挂科课程 —— 挂科也占学分分母，这是教务口径） */
    private double creditsOf(List<FixtureScore> list) {
        List<Double> credits = new ArrayList<>();
        List<Double> points = new ArrayList<>();
        for (FixtureScore r : list) {
            credits.add(r.getCredit());
            points.add(EduGpaCalculator.point(r.getScore(), r.getPoint()));
        }
        return EduGpaCalculator.countedCredits(credits, points);
    }

    /** 统计已获得学分（成绩 ≥60 的课程学分之和） */
    private double earnedOf(List<FixtureScore> list) {
        double sum = 0;
        for (FixtureScore r : list) {
            Double point = EduGpaCalculator.point(r.getScore(), r.getPoint());
            if (r.getCredit() != null && EduGpaCalculator.passed(r.getScore(), point)) {
                sum += r.getCredit();
            }
        }
        return sum;
    }

    /** 按维度分组统计：term=按学期 / year=按学年 / grade=按年级 / all=全部 */
    private List<EduScoreVO.GroupStat> groupBy(List<FixtureScore> raws,
                                               Function<FixtureScore, String> keyFn,
                                               Function<String, String> labelFn) {
        Map<String, List<FixtureScore>> groups = new LinkedHashMap<>();
        for (FixtureScore r : raws) {
            groups.computeIfAbsent(keyFn.apply(r), k -> new ArrayList<>()).add(r);
        }
        List<EduScoreVO.GroupStat> list = new ArrayList<>();
        groups.forEach((key, items) -> {
            EduScoreVO.GroupStat g = new EduScoreVO.GroupStat();
            g.setKey(key);
            g.setLabel(labelFn.apply(key));
            g.setGpa(gpaOf(items));
            g.setCredits(EduGpaCalculator.round(creditsOf(items), 2));
            g.setEarnedCredits(EduGpaCalculator.round(earnedOf(items), 2));
            g.setCourseCount(items.size());
            list.add(g);
        });
        list.sort(Comparator.comparing(EduScoreVO.GroupStat::getKey).reversed());
        return list;
    }

    /**
     * 按年级分组：年级 = 学年起始年 - 入学年 + 1。
     * 入学年取个人资料的 grade（2018—2026 这类）；没填就不给年级分组，不猜。
     */
    private List<EduScoreVO.GroupStat> byGrade(List<FixtureScore> raws) {
        UserProfile p = profile();
        Integer enroll = p == null ? null : p.getGrade();
        if (enroll == null || enroll <= 0) {
            return List.of();
        }
        Map<Integer, List<FixtureScore>> groups = new LinkedHashMap<>();
        for (FixtureScore r : raws) {
            int startYear = Integer.parseInt(r.getAcademicYear().substring(0, 4));
            int grade = startYear - enroll + 1;
            if (grade >= 1 && grade <= GRADE_LABELS.length) {
                groups.computeIfAbsent(grade, k -> new ArrayList<>()).add(r);
            }
        }
        List<EduScoreVO.GroupStat> list = new ArrayList<>();
        groups.forEach((grade, items) -> {
            EduScoreVO.GroupStat g = new EduScoreVO.GroupStat();
            g.setKey(String.valueOf(grade));
            g.setLabel("大" + GRADE_LABELS[grade - 1]);
            g.setGpa(gpaOf(items));
            g.setCredits(EduGpaCalculator.round(creditsOf(items), 2));
            g.setEarnedCredits(EduGpaCalculator.round(earnedOf(items), 2));
            g.setCourseCount(items.size());
            list.add(g);
        });
        list.sort(Comparator.comparing(g -> Integer.parseInt(g.getKey())));
        return list;
    }

    /* ==================== 考试 ==================== */

    /** 考试安排。含考试日期、时间段、考场、座位号、监考老师 */
    @Override
    public EduExamVO exams() {
        boolean real = !isFixtureMode();
        List<EduExamVO.Exam> list = real
                ? fetchRealExams()
                : (load("edu-exams.json", FixtureExams.class).getExams() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(load("edu-exams.json", FixtureExams.class).getExams()));
        LocalDate today = LocalDate.now();
        for (EduExamVO.Exam e : list) {
            if (e.getDate() != null && !e.getDate().isBlank()) {
                try {
                    LocalDate d = LocalDate.parse(e.getDate(), DATE);
                    e.setDaysLeft((int) ChronoUnit.DAYS.between(today, d));
                    e.setWeekday(weekdayName(d.getDayOfWeek()));
                } catch (Exception ignore) {
                    // 日期格式异常就只展示原始文本
                }
            }
        }
        // 最近的考试排最前（含尚未到来的），历史考试沉底
        list.sort(Comparator.comparing((EduExamVO.Exam e) -> e.getDate() == null ? "" : e.getDate()).reversed());
        EduExamVO vo = new EduExamVO();
        vo.setExams(list);
        vo.setFixture(!real);
        return vo;
    }

    /** DayOfWeek 转中文（MONDAY → 周一），考试日期展示用 */
    private static String weekdayName(DayOfWeek d) {
        return "周" + "一二三四五六日".charAt(d.getValue() - 1);
    }

    /* ==================== 真实数据（教务接口） ==================== */
    // 注：isFixtureMode() / load() 两个方法是本地样例专用（生产 fixture-dir 为空，不启用）

    /** 是否走本地样例模式（edu.fixture-dir 有配置才 true）。生产为空，恒 false */
    private boolean isFixtureMode() {
        return fixtureDir != null && !fixtureDir.isBlank();
    }

    /** 带会话 Cookie 请求教务接口，返回 JSON。 */
    private JsonNode jxfwJson(String path) {
        return jxfwJson(path, eduBaseUrl + "/login!welcome.action");
    }

    /**
     * 请求教务接口，失败时会尝试用保存的凭证自动重登一次。
     *
     * 【这一层的职责】把"会话失效"这件事对上游完全隐藏：
     *   上游（查成绩/查课表）只管拿到 JSON，不需要知道中间有没有重登过。
     *
     * 【为什么要拆成 fetchOnce】"请求一次"和"失败后重试"是两件事：
     *   混在一个方法里，重试逻辑会和 HTTP 细节纠缠，改一处容易碰坏另一处。
     *   拆开后 fetchOnce 只做一件事：发请求、判断会话是否有效。
     */
    private JsonNode jxfwJson(String path, String referer) {
        Long userId = UserContext.requireUserId();
        // 第一次尝试
        JsonNode node = fetchOnce(userId, path, referer);
        if (node != null) {
            return node;
        }
        // 会话失效 → 用保存的密码自动重登，然后重试一次
        // 【为什么只重试一次】如果密码错了，无限重试会把教务账号锁死（多数教务系统有失败次数限制）。
        //   一次不成功就交给用户，代价是"偶尔多点一次"，换来账号安全
        log.info("[教务] 会话失效，尝试自动重登 userId={} path={}", userId, path);
        if (eduAuthService.refreshSession()) {
            node = fetchOnce(userId, path, referer);
            if (node != null) {
                return node;
            }
        }
        // 重登也失败（或没有凭证）→ 只能让用户出面
        throw BizException.of(ResultCode.PARAM_ERROR, "教务会话已失效，请重新登录教务");
    }

    /**
     * 单次请求教务。
     *
     * 返回：解析好的 JSON。
     * 返回 null：表示"会话失效"（没有 Cookie，或教务返回了登录页/非法访问提示）——
     *   这种情况交给上层决定要不要重登，本方法不抛异常。
     * 其他错误（网络、JSON 解析失败）照常抛 SERVER_ERROR，因为那不是"掉线"能解释的。
     */
    private JsonNode fetchOnce(Long userId, String path, String referer) {
        // 会话统一由 EduSessionStore 管理：登录与查课表共用同一份过期规则，避免两边对不上
        String cookies = sessionStore.cookies(userId);
        // 没有 Cookie = 从没登录过或会话已过期 → 返回 null（而不是抛错），让上层尝试重登
        if (cookies == null || cookies.isBlank()) {
            return null;
        }
        String url = eduBaseUrl + path;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(EduAuthServiceImpl.eduSslContext())
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(25))
                            .header("Cookie", cookies)
                            .header("User-Agent", UA)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Accept", "application/json, text/javascript, */*; q=0.01")
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .header("Referer", referer)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = r.body() == null ? "" : r.body();
            // 【为什么靠 body 内容判断掉线】教务不是标准 API，会话失效时它不返回 401，
            //   而是返回一个登录页 HTML（含"请输入密码"）或"非法访问"提示，HTTP 状态码仍可能是 200。
            //   所以只能看内容，不能只看状态码
            if (r.statusCode() != 200 || body.contains("非法访问") || body.contains("请输入密码")) {
                log.warn("[教务] 数据接口异常 path={} status={}", path, r.statusCode());
                return null;
            }
            JsonNode node = JSON.readTree(body);
            // 【为什么只在这里续期】只有教务真的认了这张票（返回了正常数据）才说明它有效；
            //   出错时续期，等于给一张废票延长寿命，用户会一直卡在「显示已登录但打不开」
            sessionStore.touch(userId);
            return node;
        } catch (Exception e) {
            // 网络异常、超时、JSON 结构变了 —— 这些不是"掉线"能解释的，按系统错误抛
            log.error("[教务] 请求失败 path={}", path, e);
            throw BizException.of(ResultCode.SERVER_ERROR, "教务数据获取失败，请稍后重试");
        }
    }

    /** 课程成绩：/xskccjxx!getDataList.action */
    private FixtureScores fetchRealScores() {
        JsonNode rows = jxfwJson("/xskccjxx!getDataList.action?page=1&rows=500").path("rows");
        List<FixtureScore> list = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode n : rows) {
                String code = n.path("xnxqdm").asText("");
                int year = termYear(code);
                FixtureScore s = new FixtureScore();
                s.setAcademicYear(year + "-" + (year + 1));
                s.setTermNo(termNo(code));
                s.setName(n.path("kcmc").asText(""));
                s.setCredit(number(n.path("xf").asText("")));
                s.setScore(n.path("zcj").asText(""));
                s.setPoint(number(n.path("cjjd").asText("")));
                s.setNature(n.path("kcdlmc").asText(""));
                s.setExamType(n.path("ksxzmc").asText(""));
                list.add(s);
            }
        }
        if (list.isEmpty()) {
            throw BizException.of(ResultCode.SERVER_ERROR, "暂无成绩数据");
        }
        FixtureScores fx = new FixtureScores();
        fx.setCourses(list);
        return fx;
    }

    /** 考试安排：/xsksap!getDataList.action */
    private List<EduExamVO.Exam> fetchRealExams() {
        JsonNode rows = jxfwJson("/xsksap!getDataList.action?page=1&rows=500").path("rows");
        List<EduExamVO.Exam> list = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode n : rows) {
                EduExamVO.Exam e = new EduExamVO.Exam();
                e.setName(n.path("kcmc").asText(""));
                e.setDate(n.path("ksrq").asText(""));
                String span = n.path("kssj").asText("");
                String[] parts = span.split("--|~|至");
                e.setStartTime(parts.length > 0 ? parts[0].trim() : span);
                e.setEndTime(parts.length > 1 ? parts[parts.length - 1].trim() : "");
                e.setRoom(n.path("kscdmc").asText(""));
                e.setSeat(n.path("zwh").asText(""));
                e.setType(n.path("ksaplxmc").asText(""));
                list.add(e);
            }
        }
        if (list.isEmpty()) {
            throw BizException.of(ResultCode.SERVER_ERROR, "暂无考试安排");
        }
        return list;
    }

    /** 学期代码 202601 → 学年起始年 2026 */
    private static int termYear(String code) {
        try {
            return Integer.parseInt(code.substring(0, 4));
        } catch (Exception e) {
            return LocalDate.now().getYear();
        }
    }

    /** 学期代码 202601 → 第 1 学期；202502 → 第 2 学期 */
    private static int termNo(String code) {
        if (code.length() < 6) {
            return 1;
        }
        char c = code.charAt(5);
        return c == '2' ? 2 : 1;
    }

    /** 字符串转 Double，转不了返回 null（不用异常打断整批解析） */
    private static Double number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 读本地样例 JSON 文件（仅本地开发用）。生产未配置 fixture-dir 时抛接口尚未接入 */
    private <T> T load(String name, Class<T> type) {
        if (fixtureDir == null || fixtureDir.isBlank()) {
            throw BizException.of(ResultCode.SERVER_ERROR,
                    "教务数据接口尚未接入：需要真实账号登录一次确认接口路径，当前不返回任何示例数据");
        }
        File f = new File(fixtureDir, name);
        if (!f.isFile()) {
            throw BizException.of(ResultCode.SERVER_ERROR, "样例文件缺失：" + name);
        }
        try {
            return JSON.readValue(f, type);
        } catch (Exception e) {
            log.error("[教务] 样例解析失败 file={}", name, e);
            throw BizException.of(ResultCode.SERVER_ERROR, "样例数据解析失败：" + name);
        }
    }

    /* ---------- 课表缓存结构 ---------- */

    /**
     * 整学期课表快照，Redis 里存的就是它。
     *
     * 【为什么单独一个类，不直接缓存 EduTimetableVO】
     * VO 是「某一周」的视图，week 和 courses 都已按周裁剪，存下来只能服务那一周。
     * 快照保留整学期 courses 和 term，读出后再按周裁剪，一份缓存就能服务所有周次
     * 以及 all=true / all=false 两种请求，命中率最高。
     */
    @Data
    public static class CachedSemester {
        private EduTimetableVO.Term term;
        private List<EduTimetableVO.Course> courses;
        /** 抓取时刻的毫秒时间戳，用来算新鲜度，也给界面显示「更新于 11:20」 */
        private long fetchedAt;

        /** 距今过了多久（毫秒）；时钟回拨时按 0 处理，避免负数被误判成「永久新鲜」 */
        public long ageMillis() {
            return Math.max(0L, System.currentTimeMillis() - fetchedAt);
        }
    }

    /* ---------- 样例结构 ---------- */

    @Data
    public static class FixtureTimetable {
        private String academicYear;
        private Integer termNo;
        private Integer weekCount;
        private List<FixtureCourse> courses;
    }

    @Data
    public static class FixtureCourse {
        private String name;
        private String teacher;
        private String room;
        private Integer day;
        private Integer startPeriod;
        private Integer endPeriod;
        private List<Integer> weeks;
        private String color;
    }

    @Data
    public static class FixtureScores {
        private List<FixtureScore> courses;
    }

    @Data
    public static class FixtureScore {
        private String academicYear;
        private Integer termNo;
        private String name;
        private Double credit;
        private String score;
        private Double point;
        private String nature;
        private String examType;
    }

    @Data
    public static class FixtureExams {
        private List<EduExamVO.Exam> exams;
    }
}
