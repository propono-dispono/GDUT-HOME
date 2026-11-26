# -*- coding: utf-8 -*-
"""课表页重构：整学期一次加载 + swiper 左右滑动切周 + 配色加深。"""

import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JS = os.path.join(ROOT, "xiaojii-mp", "pages", "edu", "edu.js")
API = os.path.join(ROOT, "xiaojii-mp", "utils", "api.js")

# ---------- api.js ----------
a = io.open(API, encoding="utf-8").read()
a = a.replace("""function eduTimetable(week) {
  return request('/app/edu/timetable', { data: { week: week || 0 }, timeout: 25000 })
}""",
"""function eduTimetable(week, all) {
  return request('/app/edu/timetable', {
    data: { week: week || 0, all: all ? true : false },
    timeout: 25000
  })
}""", 1)
io.open(API, "w", encoding="utf-8").write(a)
print("api ok:", "all: all ? true : false" in a)

# ---------- edu.js ----------
s = io.open(JS, encoding="utf-8").read()

# 1) 配色加深（保持低饱和，但比原来深一档）
old_palette = """const PALETTE = {
  lavender: ['#E3DFF5', '#4C4569'],
  sage: ['#D2DBCE', '#3E4A3B'],
  blue: ['#C9D6EC', '#33475F'],
  cream: ['#F5E7C3', '#5B4927'],
  slate: ['#8FA3B7', '#1F2D3A'],
  mint: ['#CFE3DB', '#2E4943'],
  olive: ['#DDDCC2', '#494933'],
  tan: ['#DBCEBC', '#4D4032'],
  pink: ['#F1D9DD', '#593A40'],
  sand: ['#EFE2CD', '#534430']
}"""
new_palette = """const PALETTE = {
  lavender: ['#C9C2F0', '#302A57'],
  sage: ['#B4C7A9', '#28361F'],
  blue: ['#A6C1E6', '#1B3050'],
  cream: ['#EBD29B', '#4A3810'],
  slate: ['#6F869E', '#FFFFFF'],
  mint: ['#A5CFC1', '#153A31'],
  olive: ['#C4C48E', '#37371A'],
  tan: ['#C6B196', '#3B2F20'],
  pink: ['#E8BBC4', '#4A1F2A'],
  sand: ['#DCC5A3', '#3D2E1B']
}"""
assert old_palette in s
s = s.replace(old_palette, new_palette, 1)

# 2) 按课程名固定取色
s = s.replace("const PALETTE_KEYS = Object.keys(PALETTE)",
"""const PALETTE_KEYS = Object.keys(PALETTE)

/** 同一门课永远用同一个颜色：按课程名算哈希，切周不会变色 */
function colorIndex(name) {
  const str = String(name || '')
  let h = 7
  for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) % 9973
  return h % PALETTE_KEYS.length
}""", 1)

# 3) 课表数据层整体替换
start = s.index("  /* ==================== 课表 ==================== */")
end = s.index("  onDayTap(")

NEW = r'''  /* ==================== 课表 ==================== */

  /**
   * 一次拿整学期（all=true），每个 swiper 面板就是一周。
   * 优点：左右滑动零请求、切周不等加载；课程周次信息完整。
   */
  loadTimetable(week, force) {
    if (this._cache.all && !force) {
      this.applyAll(this._cache.all)
      return Promise.resolve()
    }
    this.setData({ ttLoading: true, ttErr: '' })
    return api
      .eduTimetable(0, true)
      .then((d) => {
        this._cache.all = d
        this.applyAll(d)
      })
      .catch((err) => {
        this.setData({ ttLoading: false, ttErr: (err && err.msg) || '课表加载失败' })
      })
  },

  applyAll(d) {
    const term = (d && d.term) || {}
    const courses = (d && d.courses) || []
    const periodCount = (d && d.periods && d.periods.length) || 12
    const weekCount = term.weekCount || 18
    const currentWeek = term.currentWeek || (d && d.week) || 1

    const panels = []
    for (let w = 1; w <= weekCount; w++) {
      const blocks = []
      courses.forEach((c) => {
        if (c.weeks && c.weeks.length && c.weeks.indexOf(w) < 0) return
        blocks.push(this.toBlock(c))
      })
      panels.push({ week: w, blocks })
    }

    this.setData({
      ttLoading: false,
      ttErr: '',
      fixture: !!(d && d.fixture),
      term,
      week: currentWeek,
      weekCount,
      gridH: periodCount * ROW_H,
      panelH: periodCount * ROW_H + 24,
      periodNos: Array.from({ length: periodCount }, (_, i) => i + 1),
      days: buildDays(term.startDate, currentWeek),
      weekPanels: panels
    })
  },

  /** 课程 → 绝对定位块（几何全在这里算，样式改动不影响布局逻辑） */
  toBlock(c) {
    const key = c.color && PALETTE[c.color] ? c.color : PALETTE_KEYS[colorIndex(c.name)]
    const pair = PALETTE[key]
    const span = Math.max(1, (c.endPeriod || c.startPeriod) - c.startPeriod + 1)
    const slotCount = c.slotCount || 1
    const slotW = COL_W / slotCount
    const left = GUTTER + (c.day - 1) * COL_W + (c.slotIndex || 0) * slotW + 2
    const width = slotW - 4
    const top = (c.startPeriod - 1) * ROW_H + 3
    const height = span * ROW_H - 6
    return {
      id: c.id,
      name: c.name || '',
      room: c.room || '',
      teacher: c.teacher || '',
      style:
        'left:' + left.toFixed(1) + 'rpx;top:' + top.toFixed(1) + 'rpx;width:' + width.toFixed(1) +
        'rpx;height:' + height.toFixed(1) + 'rpx;background:' + pair[0] + ';color:' + pair[1],
      tight: span <= 1,
      periodText: '第' + c.startPeriod + '-' + (c.endPeriod || c.startPeriod) + '节',
      weekText: fmtWeeks(c.weeks),
      conflict: !!c.conflict,
      raw: c
    }
  },

  /** 左右滑动切周：只更新表头日期，不重新请求 */
  onSwiperChange(e) {
    const w = ((e.detail && e.detail.current) || 0) + 1
    if (w === this.data.week) return
    this.setData({ week: w, days: buildDays(this.data.term && this.data.term.startDate, w) })
  },

  onPrevWeek() {
    if (this.data.week <= 1) {
      wx.showToast({ title: '已经是第 1 周', icon: 'none' })
      return
    }
    const w = this.data.week - 1
    this.setData({ week: w, days: buildDays(this.data.term && this.data.term.startDate, w) })
  },

  onNextWeek() {
    if (this.data.week >= this.data.weekCount) {
      wx.showToast({ title: '已经是最后一周', icon: 'none' })
      return
    }
    const w = this.data.week + 1
    this.setData({ week: w, days: buildDays(this.data.term && this.data.term.startDate, w) })
  },

  onBackToday() {
    const cur = (this.data.term && this.data.term.currentWeek) || 1
    this.setData({ week: cur, days: buildDays(this.data.term && this.data.term.startDate, cur) })
    wx.showToast({ title: '回到第 ' + cur + ' 周', icon: 'none' })
  },

'''
s = s[:start] + NEW + s[end:]

# 4) 其他调用点统一改为整学期加载
s = s.replace("this.loadTimetable(this.data.week, true)", "this.loadTimetable(0, true)")
s = s.replace("return this.loadTimetable(this.data.week || 0)", "return this.loadTimetable(0, true)")

# 5) data 里补 weekPanels / panelH
s = s.replace("    blocks: [],", "    blocks: [],\n    weekPanels: [],\n    panelH: 1320,", 1)

io.open(JS, "w", encoding="utf-8").write(s)
print("edu.js ok")
for k in ("applyAll", "onSwiperChange", "weekPanels", "colorIndex"):
    print(" ", k, k in s)
