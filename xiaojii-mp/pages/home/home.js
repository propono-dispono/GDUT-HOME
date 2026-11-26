/** home页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { BIZ, ORDER, decorate } = require('../../utils/biz')

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    bizList: [],
    activeBiz: 'ALL',
    keyword: '',
    list: [],
    unreadCount: 0,
    announce: null,
    searchFocus: false,
    todayLoading: true,
    todayCourses: [],
    todayHint: '',
    todayText: '',
    loading: true,
    errMsg: ''
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const safeTop = getApp().globalData.safeTop
    this.setData({
      safeTop,
      appbarH: safeTop + 44,
      capsuleRight: getApp().globalData.capsuleRight,
      bizList: ORDER.map((k) => ({ k, short: BIZ[k].short, icon: BIZ[k].icon }))
    })
    this.fetchList()
    this.fetchAnnouncement()
    this.fetchToday()
  },

  // 页面显示：刷新状态（从其他页返回时也会触发）
  onShow() {
    // 从详情页返回时刷新一次，保证浏览量/关闭状态是最新的
    if (this._loadedOnce) this.fetchList()
    this._loadedOnce = true
    this.refreshUnread()
  },

  /** 首页公告条：后端没起/没有公告时静默失败，不打扰用户 */
  fetchAnnouncement() {
    api
      .currentAnnouncement()
      .then((a) => {
        if (a && a.title) this.setData({ announce: a })
      })
      .catch(() => {})
  },

  /** 今日课程：只有教务已登录才拿得到；未登录时给一句引导，不打扰 */
  fetchToday() {
    const now = new Date()
    const wd = now.getDay() || 7
    const wdName = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][wd - 1]
    this.setData({
      todayText: now.getMonth() + 1 + '月' + now.getDate() + '日 ' + wdName
    })
    api
      .eduTimetable(0)
      .then((d) => {
        const periods = (d && d.periods) || []
        const list = ((d && d.courses) || []).filter((c) => c.day === wd)
        const items = list.map((c, i) => {
          const end = c.endPeriod || c.startPeriod
          const p1 = periods[c.startPeriod - 1]
          const p2 = periods[end - 1]
          const span = p1 && p2 ? p1.start + '-' + p2.end : ''
          return {
            id: c.id || 't' + i,
            name: c.name || '',
            periodText: '第' + c.startPeriod + (end !== c.startPeriod ? '-' + end : '') + '节',
            metaText: [span, c.room, c.teacher].filter(Boolean).join(' · ')
          }
        })
        this.setData({
          todayLoading: false,
          todayCourses: items,
          todayHint: items.length ? '' : '今天没有课，好好休息'
        })
      })
      .catch((err) => {
        const msg = (err && err.msg) || ''
        this.setData({
          todayLoading: false,
          todayCourses: [],
          todayHint: msg.indexOf('登录') >= 0 ? '登录教务后就能看到今日课程' : '课表暂时读不到，稍后再试'
        })
      })
  },

  // 事件处理：Announce
  onAnnounce() {
    const a = this.data.announce
    if (!a) return
    // 与消息中心共用同一个详情页，长公告也能正常排版
    getApp().globalData._notice = {
      id: a.id,
      title: a.title,
      content: a.content,
      type: 'SYSTEM',
      typeText: '公告',
      createdAt: a.publishAt || a.createdAt || ''
    }
    wx.navigateTo({ url: '/pages/notice/notice?id=' + (a.id || '') })
  },

  /** 轮播第二屏（或没有公告时的单条）：进教务中心，未登录会先落到教务登录页 */
  onEduTip() {
    wx.navigateTo({ url: '/pages/edu/edu' })
  },

  /** 底栏「消息」红点：有未读才亮 */
  refreshUnread() {
    api
      .unreadCount()
      .then((n) => this.setData({ unreadCount: n || 0 }))
      .catch(() => {})
  },

  // 加载数据：hList
  fetchList() {
    const params = { page: 1, size: 20 }
    if (this.data.activeBiz !== 'ALL') params.bizType = this.data.activeBiz
    const kw = (this.data.keyword || '').trim()
    if (kw) params.keyword = kw

    this.setData({ loading: true, errMsg: '' })

    return api
      .feed(params)
      .then((page) => {
        const list = ((page && page.list) || []).map(decorate)
        this.setData({ list, loading: false })
      })
      .catch((err) => {
        this.setData({ loading: false, list: [], errMsg: err.msg || '加载失败' })
      })
  },

  /* ---------- 搜索 ---------- */
  onSearchFocus() {
    this.setData({ searchFocus: true })
  },

  // 事件处理：SearchBlur
  onSearchBlur() {
    this.setData({ searchFocus: false })
  },

  // 事件处理：Keyword
  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
    clearTimeout(this._timer)
    this._timer = setTimeout(() => this.fetchList(), 320)
  },

  // 事件处理：SearchConfirm
  onSearchConfirm() {
    clearTimeout(this._timer)
    this.fetchList()
  },

  // 事件处理：ClearKeyword
  onClearKeyword() {
    this.setData({ keyword: '' })
    this.fetchList()
  },

  /* ---------- 筛选 ---------- */
  onBiz(e) {
    const k = e.currentTarget.dataset.k
    if (k === this.data.activeBiz) return
    this.setData({ activeBiz: k })
    this.fetchList()
  },

  // 事件处理：Chip
  onChip(e) {
    const k = e.currentTarget.dataset.k
    if (k === this.data.activeBiz) return
    this.setData({ activeBiz: k })
    this.fetchList()
  },

  /* ---------- 跳转 ---------- */
  onCardTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/detail/detail?id=' + id })
  },

  // 事件处理：Tab
  onTab(e) {
    const k = e.currentTarget.dataset.k
    if (k === 'home') return
    if (k === 'mine') {
      wx.reLaunch({ url: '/pages/mine/mine' })
      return
    }
    if (k === 'publish') {
      wx.navigateTo({ url: '/pages/publish/publish' })
      return
    }
    if (k === 'category') {
      wx.reLaunch({ url: '/pages/category/category' })
      return
    }
    if (k === 'messages') {
      wx.reLaunch({ url: '/pages/messages/messages' })
      return
    }
    wx.showToast({ title: '该页面在下一批接入', icon: 'none' })
  },

  // 事件处理：Bell
  onBell() {
    wx.reLaunch({ url: '/pages/messages/messages' })
  },

  // 事件处理：Retry
  onRetry() {
    this.fetchList()
  }
})
