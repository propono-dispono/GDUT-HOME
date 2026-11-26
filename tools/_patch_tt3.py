# -*- coding: utf-8 -*-
"""课表改用真实 JSON 接口 xsgrkbcx!getKbRq.action（不再解析 HTML 网格）。"""

import io
import os

P = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "xiaojii-server",
                 "src", "main", "java", "com", "gdut", "xiaojii",
                 "service", "impl", "EduDataServiceImpl.java")
s = io.open(P, encoding="utf-8").read()

start = s.index("    private EduTimetableVO realTimetable(int week) {")
end = s.index("    private List<EduTimetableVO.Course> parseTimetable(String html, int week) {")

NEW = r'''    private EduTimetableVO realTimetable(int week) {
        EduTimetableVO.Term term = currentTerm();
        int showWeek = week > 0 ? week : term.getCurrentWeek();
        String termCode = termCodeOf(term);

        // zc 留空 = 一次取回整学期的课，weeks 才有完整信息；前端按当前周过滤
        String path = "/xsgrkbcx!getKbRq.action?xnxqdm=" + termCode + "&zc=&_="
                + System.currentTimeMillis();
        JsonNode root = jxfwJson(path, eduBaseUrl + "/xsgrkbcx!xsgrkbMain.action");

        List<EduTimetableVO.Course> all = parseKbRq(root);
        List<EduTimetableVO.Course> courses = new ArrayList<>();
        for (EduTimetableVO.Course c : all) {
            if (c.getWeeks() != null && c.getWeeks().contains(showWeek)) {
                courses.add(c);
            }
        }
        if (courses.isEmpty()) {
            throw BizException.of(ResultCode.SERVER_ERROR, "第 " + showWeek + " 周暂无课程安排");
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
        java.util.List<JsonNode> items = new ArrayList<>();
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

    /** "03,04" 或 "0304" → [3, 4] */
    private static int[] parseJcdm(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.replace(",", "-").trim();
        Matcher m = TT_SPAN.matcher(clean);
        if (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return new int[]{Math.max(1, Math.min(a, b)), Math.min(12, Math.max(a, b))};
        }
        // "0304" 这种四位数
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() == 4) {
            int a = Integer.parseInt(digits.substring(0, 2));
            int b = Integer.parseInt(digits.substring(2, 4));
            return new int[]{Math.max(1, Math.min(a, b)), Math.min(12, Math.max(a, b))};
        }
        return null;
    }

'''
s = s[:start] + NEW + s[end:]
io.open(P, "w", encoding="utf-8").write(s)
print("patched, size =", len(s))
print(" parseKbRq:", "parseKbRq" in s, " getKbRq:", "getKbRq.action" in s)
