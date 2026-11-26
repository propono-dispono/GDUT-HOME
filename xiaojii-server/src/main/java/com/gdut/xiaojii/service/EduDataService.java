package com.gdut.xiaojii.service;

import com.gdut.xiaojii.dto.vo.EduExamVO;
import com.gdut.xiaojii.dto.vo.EduScoreVO;
import com.gdut.xiaojii.dto.vo.EduTimetableVO;

/**
 * 教务数据（课表 / 成绩 / 考试）。
 *
 * <p>当前实现分两种数据源：
 * <ul>
 *   <li>{@code edu.fixture-dir} 配置后读本地样例 JSON（仅本地联调，生产 jar 不含样例文件）；</li>
 *   <li>否则走教务真实接口——真实接口路径需在真实账号登录后确认，未确认前明确报错，不返回假数据。</li>
 * </ul>
 *
 * @author 校集后端
 */
public interface EduDataService {

    /**
     * 课表。
     *
     * 参数 week：周次，小于等于 0 表示按当前日期推算
     * 参数 all：true 返回整学期，false 只返回 week 那一周
     * 参数 refresh：true 跳过缓存直接请求教务，用户点「刷新」时传
     * 返回：课表视图，带 fetchedAt（抓取时刻，界面显示"更新于"）和 stale（是否降级数据）
     */
    EduTimetableVO timetable(int week, boolean all, boolean refresh);

    /** 成绩 + GPA（按学期 / 学年 / 年级 / 全部） */
    EduScoreVO scores();

    /** 考试安排 */
    EduExamVO exams();

    /** 当前学期（用于页面默认值，避免让学生手填学年学期） */
    EduTimetableVO.Term currentTerm();
}
