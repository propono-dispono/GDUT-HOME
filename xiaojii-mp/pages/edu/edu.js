/** 教务中心（课表/成绩/考试） —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')

/** 刷新限速：1 分钟 1 次（本地存储，重进页面也生效） */
const REFRESH_GAP = 60 * 1000
const REFRESH_KEY = 'edu:lastRefresh'

/* ---------- 课表网格几何（rpx，设计宽 750） ---------- */
const GUTTER = 56 // 左侧节次栏
const COLS = 7
const COL_W = Math.round(((750 - GUTTER) / COLS) * 100) / 100
const ROW_H = 108

/** 课程块配色：设计图里的低饱和色系（[背景, 文字]） */
const PALETTE = {
  lavender: ['#DDD6EC', '#3B3556'],
  sage: ['#B8C4AE', '#2C3A26'],
  blue: ['#C5CBE8', '#2C3350'],
  cream: ['#E8DCC8', '#4A3F2B'],
  slate: ['#5C7A93', '#FFFFFF'],
  mint: ['#C4D0BC', '#23321F'],
  olive: ['#D5D3B8', '#3B3A24'],
  tan: ['#CDBBA6', '#3B3020'],
  pink: ['#EBD3D6', '#4A2830'],
  sand: ['#EADFC8', '#40341F']
}
const PALETTE_KEYS = Object.keys(PALETTE)

/** 同一门课永远用同一个颜色：按课程名算哈希，切周不会变色 */
function colorIndex(name) {
  const str = String(name || '')
  let h = 7
  for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) % 9973
  return h % PALETTE_KEYS.length
}

const WD = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 解析 yyyy-MM-dd（iOS 不认横线，要换成斜杠） */
function parseDate(s) {
  if (!s) return null
  const d = new Date(String(s).replace(/-/g, '/'))
  return isNaN(d.getTime()) ? null : d
}

/** 某周 7 天的日期与星期（周一开始） */
function buildDays(startDate, week) {
  const start = parseDate(startDate)
  const out = []
  const now = new Date()
  const sameDay = (a, b) =>
    a && b && a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()

  for (let i = 0; i < COLS; i++) {
    const d = start ? new Date(start.getTime()) : new Date()
    if (start) d.setDate(start.getDate() + (week - 1) * 7 + i)
    out.push({
      day: i + 1,
      wd: WD[i],
      date: pad(d.getDate()),
      month: d.getMonth() + 1,
      isToday: sameDay(d, now)
    })
  }
  return out
}

/** 周次文本：全周 / 单双周 / 区间 */
function fmtWeeks(weeks) {
  if (!weeks || !weeks.length) return ''
  if (weeks.length >= 16) return '全周'
  const allOdd = weeks.every((w) => w % 2 === 1)
  const allEven = weeks.every((w) => w % 2 === 0)
  if (allOdd && weeks.length > 2) return '单周'
  if (allEven && weeks.length > 2) return '双周'
  // 压缩成 1-8、10-16 形式
  const parts = []
  let s = weeks[0]
  let p = weeks[0]
  for (let i = 1; i < weeks.length; i++) {
    if (weeks[i] === p + 1) {
      p = weeks[i]
    } else {
      parts.push(s === p ? String(s) : s + '-' + p)
      s = p = weeks[i]
    }
  }
  parts.push(s === p ? String(s) : s + '-' + p)
  return parts.join('、') + '周'
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,

    loading: true,
    tab: 'schedule',
    status: { loggedIn: false, saved: false, accountMasked: '', expiresAt: '' },
    fixture: false,

    /* 课表 */
    gutterW: GUTTER,
    colW: COL_W,
    rowH: ROW_H,
    gridH: ROW_H * 12,
    term: null,
    week: 1,
    weekCount: 20,
    days: [],
    periodNos: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12],
    blocks: [],
    weekPanels: [],
    panelH: 1320,
    ttLoading: true,
    ttErr: '',
    /* 数据抓取时刻 HH:mm，空串表示不显示 */
    fetchedAt: '',
    /* true 表示后端给的是缓存旧数据，教务当前不可达 */
    stale: false,
    /* true 表示错误与教务登录态有关，应引导重登而不是让用户反复点重试 */
    needLogin: false,

    /* 成绩 */
    scLoading: true,
    scErr: '',
    stats: null,
    terms: [],
    scMode: 'term',
    openTerm: '',
    groupRows: [],

    /* 考试 */
    exLoading: true,
    exErr: '',
    exams: [],
    examsShown: [],
    exFilter: 'all',
    exTotal: 0,
    exTodo: 0,
    exDone: 0,

    /* 课程详情弹层 */
    sheet: null
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const app = getApp()
    const safeTop = app.globalData.safeTop
    this.setData({ safeTop, appbarH: safeTop + 44, capsuleRight: app.globalData.capsuleRight })
    try {
      this._lastRefresh = Number(wx.getStorageSync(REFRESH_KEY)) || 0
    } catch (e) {
      this._lastRefresh = 0
    }
    this._cache = {}
    this.fetchStatus()
  },

  // 页面显示：刷新状态（从其他页返回时也会触发）
  onShow() {
    if (this._loadedOnce) this.fetchStatus()
    this._loadedOnce = true
  },

  /* ==================== 登录态 ==================== */

  // 加载数据：hStatus
  fetchStatus() {
    return api
      .eduStatus()
      .then((s) => {
        const status = {
          loggedIn: !!(s && s.loggedIn),
          saved: !!(s && s.saved),
          accountMasked: (s && s.accountMasked) || '',
          expiresAt: (s && s.expiresAt) || ''
        }
        this.setData({ loading: false, status })
        if (status.loggedIn) {
          this.loadTab(this.data.tab)
        } else if (this.data.tab === 'schedule') {
          // 未登录时不能让课表停在「加载中」转圈：直接给一句说明和重登入口，
          // 用户才知道该干什么，而不是以为网络卡了
          this.setData({ ttLoading: false, ttErr: '还没有登录教务', needLogin: true })
        }
      })
      .catch((err) => {
        this.setData({ loading: false })
        wx.showToast({ title: (err && err.msg) || '登录状态获取失败', icon: 'none' })
      })
  },

  /* ==================== 课表 ==================== */

  /**
   * 一次拿整学期（all=true），每个 swiper 面板就是一周。
   * 优点：左右滑动零请求、切周不等加载；课程周次信息完整。
   *
   * force=true 会透传给后端跳过缓存，用于用户主动点刷新；
   * 平时 force=false，后端有 30 分钟缓存，进页面几乎立刻出数据。
   */
  loadTimetable(week, force) {
    if (this._cache.all && !force) {
      this.applyAll(this._cache.all)
      return Promise.resolve()
    }
    this.setData({ ttLoading: true, ttErr: '', needLogin: false })
    return api
      .eduTimetable(0, true, force)
      .then((d) => {
        this._cache.all = d
        this.applyAll(d)
      })
      .catch((err) => {
        const msg = (err && err.msg) || '课表加载失败'
        // 提示里出现「登录 / 会话」说明是教务身份失效，给重登入口；
        // 否则（网络抖动等）给重试，两者不能混成一个按钮，否则用户会白点很多次
        this.setData({
          ttLoading: false,
          ttErr: msg,
          needLogin: msg.indexOf('登录') >= 0 || msg.indexOf('会话') >= 0
        })
      })
  },

  // 方法：applyAll
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

    this._periods = (d && d.periods) || []

    this.setData({
      ttLoading: false,
      ttErr: '',
      needLogin: false,
      fixture: !!(d && d.fixture),
      fetchedAt: (d && d.fetchedAt) || '',
      stale: !!(d && d.stale),
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
      timeText: this.timeOf(c.startPeriod, c.endPeriod || c.startPeriod),
      weekText: fmtWeeks(c.weeks),
      conflict: !!c.conflict,
      raw: c
    }
  },

  /** 节次 → 具体上下课时间（来自后端 periods，和教务一致） */
  timeOf(a, b) {
    const ps = this._periods || []
    const s1 = ps[a - 1]
    const s2 = ps[b - 1]
    if (!s1 || !s2) return ''
    return s1.start + ' - ' + s2.end
  },

  /** 左右滑动切周：只更新表头日期，不重新请求 */
  onSwiperChange(e) {
    const w = ((e.detail && e.detail.current) || 0) + 1
    if (w === this.data.week) return
    this.setData({ week: w, days: buildDays(this.data.term && this.data.term.startDate, w) })
  },

  // 事件处理：PrevWeek
  onPrevWeek() {
    if (this.data.week <= 1) {
      wx.showToast({ title: '已经是第 1 周', icon: 'none' })
      return
    }
    const w = this.data.week - 1
    this.setData({ week: w, days: buildDays(this.data.term && this.data.term.startDate, w) })
  },

  // 事件处理：NextWeek
  onNextWeek() {
    if (this.data.week >= this.data.weekCount) {
      wx.showToast({ title: '已经是最后一周', icon: 'none' })
      return
    }
    const w = this.data.week + 1
    this.setData({ week: w, days: buildDays(this.data.term && this.data.term.startDate, w) })
  },

  // 事件处理：BackToday
  onBackToday() {
    const cur = (this.data.term && this.data.term.currentWeek) || 1
    this.setData({ week: cur, days: buildDays(this.data.term && this.data.term.startDate, cur) })
    wx.showToast({ title: '回到第 ' + cur + ' 周', icon: 'none' })
  },

  // 事件处理：DayTap
  onDayTap(e) {
    if (e.currentTarget.dataset.today) this.onBackToday()
  },

  // 事件处理：CourseTap
  onCourseTap(e) {
    const id = e.currentTarget.dataset.id
    const panel = this.data.weekPanels[this.data.week - 1]
    const list = (panel && panel.blocks) || []
    const b = list.find((x) => x.id === id)
    if (b) this.setData({ sheet: b })
  },

  // 事件处理：CloseSheet
  onCloseSheet() {
    this.setData({ sheet: null })
  },

  /** 弹层内的空处理器，避免点击穿透到遮罩 */
  noop() {},

  /** 加载失败后的重试：跳过 1 分钟限速 */
  onRetry() {
    const tab = this.data.tab
    if (tab === 'schedule') this.loadTimetable(0, true)
    else if (tab === 'score') this.loadScores(true)
    else this.loadExams(true)
  },

  /** 会话失效后直接去教务登录页，登录完返回会自动重新拉课表 */
  onRelogin() {
    wx.navigateTo({
      url: '/pages/edu/login/login',
      fail: () => wx.reLaunch({ url: '/pages/edu/login/login' })
    })
  },

  /* ==================== 成绩 ==================== */

  // 加载数据：Scores
  loadScores(force) {
    if (this._scores && !force) return Promise.resolve()
    this.setData({ scLoading: true, scErr: '' })
    return api
      .eduScores()
      .then((d) => {
        this._scores = d
        const stats = (d && d.stats) || null
        const terms = ((d && d.terms) || []).map((t) => ({
          ...t,
          gpaText: t.gpa == null ? '--' : t.gpa.toFixed(2),
          creditText: t.credits == null ? '--' : t.credits.toFixed(1)
        }))
        this.setData({
          scLoading: false,
          fixture: !!(d && d.fixture),
          stats: stats
            ? {
                ...stats,
                gpaText: stats.gpa == null ? '--' : stats.gpa.toFixed(2),
                creditText: stats.credits == null ? '--' : stats.credits.toFixed(1),
                earnedText: stats.earnedCredits == null ? '--' : stats.earnedCredits.toFixed(1)
              }
            : null,
          terms,
          groupRows: this.buildGroup(stats, this.data.scMode)
        })
      })
      .catch((err) => {
        this.setData({ scLoading: false, scErr: (err && err.msg) || '成绩加载失败' })
      })
  },

  // 构建 Group
  buildGroup(stats, mode) {
    if (!stats) return []
    const list = mode === 'year' ? stats.byYear : mode === 'grade' ? stats.byGrade : []
    return (list || []).map((g) => ({
      key: g.key,
      label: g.label,
      gpaText: g.gpa == null ? '--' : g.gpa.toFixed(2),
      creditText: g.credits == null ? '--' : g.credits.toFixed(1),
      earnedText: g.earnedCredits == null ? '--' : g.earnedCredits.toFixed(1),
      courseCount: g.courseCount
    }))
  },

  // 事件处理：ScoreMode
  onScoreMode(e) {
    const mode = e.currentTarget.dataset.mode
    if (mode === this.data.scMode) return
    this.setData({ scMode: mode, groupRows: this.buildGroup(this.data.stats, mode) })
  },

  // 事件处理：TermToggle
  onTermToggle(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ openTerm: this.data.openTerm === key ? '' : key })
  },

  /* ==================== 考试 ==================== */

  // 加载数据：Exams
  loadExams(force) {
    if (this._exams && !force) return Promise.resolve()
    this.setData({ exLoading: true, exErr: '' })
    return api
      .eduExams()
      .then((d) => {
        const exams = ((d && d.exams) || []).map((x) => {
          const parts = (x.date || '').split('-')
          // 时间/地点行在 JS 里拼好：任一字段为空就不留多余的分隔符，避免出现「· 号 ·」这种错位
          const span = [x.startTime, x.endTime].filter(Boolean).join('-')
          const meta = []
          if (x.room) meta.push(x.room)
          if (x.seat) meta.push(x.seat + ' 号')
          if (x.type) meta.push(x.type)
          return {
            ...x,
            monthText: parts.length === 3 ? Number(parts[1]) + '月' : '',
            dayText: parts.length === 3 ? parts[2] : '',
            timeText: [x.weekday, span].filter(Boolean).join(' '),
            metaText: meta.join(' · '),
            soon: x.daysLeft != null && x.daysLeft >= 0 && x.daysLeft <= 7,
            passed: x.daysLeft != null && x.daysLeft < 0
          }
        })
        // ⚠️ 这里必须存加工后的数组：筛选要按 passed 过滤，存整个响应对象会直接抛错
        this._exams = exams
        this.setData({ exLoading: false, fixture: !!(d && d.fixture), exams })
        this.applyExFilter()
      })
      .catch((err) => {
        this.setData({ exLoading: false, exErr: (err && err.msg) || '考试安排加载失败' })
      })
  },

  /* ==================== 考试筛选 ==================== */

  // 事件处理：ExFilter
  onExFilter(e) {
    const k = (e.currentTarget.dataset && e.currentTarget.dataset.k) || 'all'
    this.applyExFilter(k)
  },

  /** 已考 / 未考 分开统计并按当前筛选渲染 */
  applyExFilter(k) {
    const all = Array.isArray(this._exams) ? this._exams : []
    const filter = k || this.data.exFilter || 'all'
    const done = all.filter((x) => x.passed)
    const todo = all.filter((x) => !x.passed)
    let list = all
    if (filter === 'todo') list = todo
    if (filter === 'done') list = done
    this.setData({
      exFilter: filter,
      examsShown: list,
      exTotal: all.length,
      exTodo: todo.length,
      exDone: done.length
    })
  },

  /* ==================== Tab / 刷新 ==================== */

  // 加载数据：Tab
  loadTab(tab) {
    // 切 Tab 进来用缓存（force=false）：后端有 30 分钟缓存，前端还有内存缓存，
    // 只有用户主动点刷新时才 force=true 穿透到教务
    if (tab === 'schedule') return this.loadTimetable(0, false)
    if (tab === 'score') return this.loadScores()
    return this.loadExams()
  },

  // 事件处理：Tab
  onTab(e) {
    const k = e.currentTarget.dataset.k
    if (k === this.data.tab) return
    this.setData({ tab: k })
    this.loadTab(k)
  },

  // 事件处理：Refresh
  onRefresh() {
    const now = Date.now()
    const gap = now - (this._lastRefresh || 0)
    if (gap < REFRESH_GAP) {
      wx.showToast({ title: Math.ceil((REFRESH_GAP - gap) / 1000) + ' 秒后可再刷新', icon: 'none' })
      return
    }
    this._lastRefresh = now
    try {
      wx.setStorageSync(REFRESH_KEY, now)
    } catch (e) {
      /* ignore */
    }
    const tab = this.data.tab
    if (tab === 'schedule') {
      this.loadTimetable(0, true).then(() => wx.showToast({ title: '已刷新', icon: 'none' }))
    } else if (tab === 'score') {
      this.loadScores(true).then(() => wx.showToast({ title: '已刷新', icon: 'none' }))
    } else {
      this.loadExams(true).then(() => wx.showToast({ title: '已刷新', icon: 'none' }))
    }
  },

  /* ==================== 登录 / 退出 ==================== */

  // 事件处理：Login
  onLogin() {
    wx.navigateTo({ url: '/pages/edu/login/login' })
  },

  // 事件处理：Logout
  onLogout() {
    wx.showModal({
      title: '退出教务',
      content: '将退出教务会话，并清除已保存的账号密码。下次查看课表需要重新登录（含验证码）。',
      confirmText: '退出',
      confirmColor: '#D92D20',
      success: (res) => {
        if (!res.confirm) return
        api
          .eduLogout()
          .then(() => {
            this._cache = {}
            this._scores = null
            this._exams = null
            this.setData({
              status: { loggedIn: false, saved: false, accountMasked: '', expiresAt: '' },
              blocks: [],
              terms: [],
              exams: [],
              stats: null
            })
            wx.showToast({ title: '已退出教务', icon: 'none' })
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.msg) || '退出失败', icon: 'none' })
          })
      }
    })
  },

  // 事件处理：Back
  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack()
      return
    }
    wx.reLaunch({ url: '/pages/mine/mine' })
  }
})
