# -*- coding: utf-8 -*-
"""教务数据服务：成绩/考试改为读真实接口，fixture 仅本地样例模式使用。"""

import io
import os

BASE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "xiaojii-server",
                    "src", "main", "java", "com", "gdut", "xiaojii")
AUTH = os.path.join(BASE, "service", "impl", "EduAuthServiceImpl.java")
DATA = os.path.join(BASE, "service", "impl", "EduDataServiceImpl.java")

# ---------- 1. SSL 上下文改为可共享 ----------
s = io.open(AUTH, encoding="utf-8").read()
s = s.replace("    private SSLContext eduSslContext() {",
              "    public static SSLContext eduSslContext() {")
s = s.replace("            return EDU_SSL_CONTEXT;\n        }\n        synchronized",
              "            return EDU_SSL_CONTEXT;\n        }\n        synchronized")
io.open(AUTH, "w", encoding="utf-8").write(s)
print("auth ssl shared:", "public static SSLContext eduSslContext()" in s)

# ---------- 2. 数据服务 ----------
d = io.open(DATA, encoding="utf-8").read()

# imports
if "import com.fasterxml.jackson.databind.JsonNode;" not in d:
    d = d.replace("import com.fasterxml.jackson.databind.ObjectMapper;",
                  "import com.fasterxml.jackson.databind.JsonNode;\nimport com.fasterxml.jackson.databind.ObjectMapper;", 1)
if "import org.springframework.data.redis.core.StringRedisTemplate;" not in d:
    d = d.replace("import org.springframework.stereotype.Service;",
                  "import org.springframework.data.redis.core.StringRedisTemplate;\nimport org.springframework.stereotype.Service;", 1)
if "import java.net.http.HttpClient;" not in d:
    d = d.replace("import java.io.File;",
                  "import java.io.File;\n"
                  "import java.net.URI;\n"
                  "import java.net.http.HttpClient;\n"
                  "import java.net.http.HttpRequest;\n"
                  "import java.net.http.HttpResponse;\n"
                  "import java.nio.charset.StandardCharsets;\n"
                  "import java.time.Duration;", 1)

# 字段
d = d.replace("    private final UserProfileMapper profileMapper;",
              "    private final UserProfileMapper profileMapper;\n"
              "    private final StringRedisTemplate redis;\n\n"
              "    /** 与 EduAuthServiceImpl 共用同一个 Redis 键，存教务会话 Cookie。 */\n"
              "    private static final String SESSION_PREFIX = \"edu:session:\";\n"
              "    private static final String UA = \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) \"\n"
              "            + \"AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36\";\n\n"
              "    @Value(\"${edu.base-url:https://jxfw.gdut.edu.cn}\")\n"
              "    private String eduBaseUrl;", 1)

# 成绩：数据源分流
d = d.replace('        FixtureScores fx = load("edu-scores.json", FixtureScores.class);',
              "        boolean real = !isFixtureMode();\n"
              '        FixtureScores fx = real ? fetchRealScores() : load("edu-scores.json", FixtureScores.class);', 1)
d = d.replace("        vo.setTerms(terms);\n        vo.setStats(stats);\n        vo.setFixture(true);",
              "        vo.setTerms(terms);\n        vo.setStats(stats);\n        vo.setFixture(!real);", 1)

# 考试：数据源分流
d = d.replace('        FixtureExams fx = load("edu-exams.json", FixtureExams.class);\n'
              '        List<EduExamVO.Exam> list = fx.getExams() == null ? new ArrayList<>() : new ArrayList<>(fx.getExams());',
              "        boolean real = !isFixtureMode();\n"
              "        List<EduExamVO.Exam> list = real\n"
              "                ? fetchRealExams()\n"
              '                : (load("edu-exams.json", FixtureExams.class).getExams() == null\n'
              "                        ? new ArrayList<>()\n"
              '                        : new ArrayList<>(load("edu-exams.json", FixtureExams.class).getExams()));', 1)
d = d.replace("        vo.setExams(list);\n        vo.setFixture(true);",
              "        vo.setExams(list);\n        vo.setFixture(!real);", 1)

# 新增真实数据方法
ANCHOR = "    private <T> T load(String name, Class<T> type) {"
REAL = '''    /* ==================== 真实数据（教务接口） ==================== */

    private boolean isFixtureMode() {
        return fixtureDir != null && !fixtureDir.isBlank();
    }

    /** 带会话 Cookie 请求教务接口，返回 JSON。 */
    private JsonNode jxfwJson(String path) {
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
                            .header("Referer", eduBaseUrl + "/login!welcome.action")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = r.body() == null ? "" : r.body();
            if (r.statusCode() != 200 || body.contains("非法访问") || body.contains("请输入密码")) {
                log.warn("[教务] 数据接口异常 path={} status={}", path, r.statusCode());
                throw BizException.of(ResultCode.PARAM_ERROR, "教务会话已失效，请重新登录教务");
            }
            return JSON.readTree(body);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
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

'''
assert ANCHOR in d, "锚点没找到"
d = d.replace(ANCHOR, REAL + ANCHOR, 1)

io.open(DATA, "w", encoding="utf-8").write(d)
print("data patched, size =", len(d))
for key in ("fetchRealScores", "fetchRealExams", "jxfwJson", "isFixtureMode"):
    print(" ", key, key in d)
