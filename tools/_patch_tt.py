# -*- coding: utf-8 -*-
"""课表接真实数据：解析教务周课表 HTML（fc-event 网格）。"""

import io
import os

P = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "xiaojii-server",
                 "src", "main", "java", "com", "gdut", "xiaojii",
                 "service", "impl", "EduDataServiceImpl.java")
s = io.open(P, encoding="utf-8").read()

# 1) imports
if "import java.util.regex.Matcher;" not in s:
    s = s.replace("import java.util.stream.Collectors;",
                  "import java.util.regex.Matcher;\nimport java.util.regex.Pattern;\n"
                  "import java.util.stream.Collectors;", 1)

# 2) timetable 分流
old_head = '''    public EduTimetableVO timetable(int week) {
        FixtureTimetable fx = load("edu-timetable.json", FixtureTimetable.class);'''
new_head = '''    public EduTimetableVO timetable(int week) {
        if (!isFixtureMode()) {
            return realTimetable(week);
        }
        FixtureTimetable fx = load("edu-timetable.json", FixtureTimetable.class);'''
assert old_head in s
s = s.replace(old_head, new_head, 1)

# 3) 真实课表实现
ANCHOR = "    /** 同一格（星期 + 起止节）多门课 → 标记冲突，前端并排压缩展示 */"
REAL = r'''    /* ---------- 真实课表（教务周课表网格） ---------- */

    /** 课程块：class="fc-event fc-event-vert ..." style="... left: 62px ..." */
    private static final Pattern TT_EVENT = Pattern.compile(
            "fc-event fc-event-vert.{0,160}?left:\\s*([\\d.]+)px", Pattern.DOTALL);
    private static final Pattern TT_TIME = Pattern.compile(
            "fc-event-time.{0,40}?>([^<]*)<", Pattern.DOTALL);
    private static final Pattern TT_TITLE = Pattern.compile("<div>([^<]{1,40})</div>");
    private static final Pattern TT_SPAN = Pattern.compile("(\\d{1,2})\\s*-\\s*(\\d{1,2})");

    /** 表头第一列（周一）起点 62px，每列约 128px —— 由教务页面实测得出 */
    private static final double TT_COL_START = 62.0;
    private static final double TT_COL_WIDTH = 128.0;

    private EduTimetableVO realTimetable(int week) {
        EduTimetableVO.Term term = currentTerm();
        int showWeek = week > 0 ? week : term.getCurrentWeek();
        String termCode = termCodeOf(term);
        String path = "/xsgrkbcx!xskbList.action?xnxqdm=" + termCode
                + "&zc=" + showWeek + "&_=" + System.currentTimeMillis();
        String html = jxfwHtml(path, eduBaseUrl + "/xsgrkbcx!xsgrkbMain.action");

        List<EduTimetableVO.Course> courses = parseTimetable(html, showWeek);
        if (courses.isEmpty()) {
            throw BizException.of(ResultCode.SERVER_ERROR, "本周暂无课程安排");
        }
        markConflicts(courses);
        courses.sort(Comparator.comparing(EduTimetableVO.Course::getDay)
                .thenComparing(EduTimetableVO.Course::getStartPeriod));
        if (term.getCampus() == null || term.getCampus().isBlank()) {
            term.setCampus(inferCampus(courses));
        }

        EduTimetableVO vo = new EduTimetableVO();
        vo.setTerm(term);
        vo.setWeek(showWeek);
        vo.setPeriods(periods());
        vo.setCourses(courses);
        vo.setFixture(false);
        return vo;
    }

    private List<EduTimetableVO.Course> parseTimetable(String html, int week) {
        List<EduTimetableVO.Course> list = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return list;
        }
        Matcher m = TT_EVENT.matcher(html);
        int lastStart = -1;
        while (m.find()) {
            int segStart = m.start();
            if (segStart <= lastStart) {
                continue;
            }
            lastStart = segStart;
            int segEnd = html.indexOf("fc-event-bg", segStart);
            String seg = html.substring(segStart, segEnd > 0 ? segEnd
                    : Math.min(segStart + 2500, html.length()));

            double left = TT_COL_START;
            try {
                left = Double.parseDouble(m.group(1));
            } catch (Exception ignore) {
                // 坐标缺失就按周一处理，总比整条丢掉好
            }
            Matcher tm = TT_TIME.matcher(seg);
            int[] span = tm.find() ? parseSpan(tm.group(1)) : null;
            if (span == null) {
                continue;
            }
            List<String> titles = new ArrayList<>();
            Matcher im = TT_TITLE.matcher(seg);
            while (im.find()) {
                titles.add(im.group(1).trim());
            }
            if (titles.isEmpty()) {
                continue;
            }

            EduTimetableVO.Course c = new EduTimetableVO.Course();
            c.setName(titles.get(0));
            c.setDay(dayOfLeft(left));
            c.setStartPeriod(span[0]);
            c.setEndPeriod(span[1]);
            // 教务固定格式：课程名 / 教学班 / 教师 / 教室；只有 3 段时没有教室
            if (titles.size() >= 4) {
                c.setTeacher(titles.get(2));
                c.setRoom(titles.get(3));
            } else if (titles.size() == 3) {
                c.setTeacher(titles.get(2));
                c.setRoom("");
            } else {
                c.setTeacher("");
                c.setRoom("");
            }
            c.setWeeks(new ArrayList<>(List.of(week)));
            c.setConflict(false);
            c.setSlotIndex(0);
            c.setSlotCount(1);
            String id = c.getName() + "@" + c.getDay() + "-" + c.getStartPeriod();
            c.setId(Integer.toHexString(id.hashCode()));
            list.add(c);
        }
        return list;
    }

    private static int dayOfLeft(double left) {
        int col = (int) Math.round((left - TT_COL_START) / TT_COL_WIDTH);
        return Math.min(7, Math.max(1, col + 1));
    }

    /** "03 - 05" → [3, 5] */
    private static int[] parseSpan(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TT_SPAN.matcher(text);
        if (!m.find()) {
            return null;
        }
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        if (b < a) {
            int t = a;
            a = b;
            b = t;
        }
        return new int[]{Math.max(1, a), Math.min(12, b)};
    }

    /** 学期代码：2026-2027 第 1 学期 → 202601 */
    private static String termCodeOf(EduTimetableVO.Term term) {
        String y = term.getAcademicYear() == null || term.getAcademicYear().length() < 4
                ? String.valueOf(LocalDate.now().getYear())
                : term.getAcademicYear().substring(0, 4);
        int no = term.getTermNo() == null ? 1 : term.getTermNo();
        return y + (no == 1 ? "01" : "02");
    }

    /** 教务页面都要求带 Referer，否则返回「你没有该权限」 */
    private String jxfwHtml(String path, String referer) {
        Long userId = UserContext.requireUserId();
        String raw = redis.opsForValue().get(SESSION_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            throw BizException.of(ResultCode.PARAM_ERROR, "请先登录教务");
        }
        String cookies;
        try {
            cookies = JSON.readTree(raw).path("cookies").asText("");
        } catch (Exception e) {
            throw BizException.of(ResultCode.SERVER_ERROR, "教务会话解析失败，请重新登录");
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(EduAuthServiceImpl.eduSslContext())
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(eduBaseUrl + path))
                            .timeout(Duration.ofSeconds(25))
                            .header("Cookie", cookies)
                            .header("User-Agent", UA)
                            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .header("Referer", referer)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = r.body() == null ? "" : r.body();
            if (r.statusCode() != 200 || body.contains("非法访问") || body.contains("你没有该权限")) {
                log.warn("[教务] 课表页异常 status={} path={}", r.statusCode(), path);
                throw BizException.of(ResultCode.PARAM_ERROR, "教务会话已失效，请重新登录教务");
            }
            return body;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[教务] 课表请求失败 path={}", path, e);
            throw BizException.of(ResultCode.SERVER_ERROR, "课表获取失败，请稍后重试");
        }
    }

'''
assert ANCHOR in s
s = s.replace(ANCHOR, REAL + ANCHOR, 1)

io.open(P, "w", encoding="utf-8").write(s)
print("patched, size =", len(s))
for k in ("realTimetable", "parseTimetable", "jxfwHtml", "dayOfLeft"):
    print(" ", k, k in s)
