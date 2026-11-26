/** messages页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { defaultAvatar } = require('../../utils/avatar')

/** 通知类型 → 图标，与后端 Notice.type 对应 */
const ICON = {
  AUDIT: 'i-shield',
  ORDER: 'i-bag',
  CHAT: 'i-chat',
  REPORT: 'i-more',
  GROUPBUY: 'i-users',
  SYSTEM: 'i-bell'
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    list: [],
    chatGroups: [],
    notices: [],
    unreadCount: 0,
    loading: true,
    errMsg: '',
    filter: 'all',
    cntChat: 0,
    cntNotice: 0
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const app = getApp()
    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })
    this.fetch()
  },

  // 页面显示：刷新状态（从其他页返回时也会触发）
  onShow() {
    if (this._loadedOnce) this.fetch()
    this._loadedOnce = true
  },

  /**
   * 消息中心：私信按「会话」聚合（同一个人的 N 条提醒合成一条），其余按通知类型归到通知区。
   * 不这么做的话，对方连发 5 条就会在列表里堆 5 条一模一样的「收到一条新私信」。
   */
  fetch() {
    this.setData({ loading: true, errMsg: '' })
    return Promise.all([
      api.noticeList({ page: 1, size: 50 }).catch(() => ({ list: [] })),
      api.chatSessions().catch(() => [])
    ])
      .then(([page, sessions]) => {
        const sessMap = {}
        ;(sessions || []).forEach((x) => {
          sessMap[x.id] = x
        })

        const groups = {}
        const notices = []
        ;((page && page.list) || []).forEach((n) => {
          const isChat = n.refType === 'CHAT' && n.refId && sessMap[n.refId]
          if (!isChat) {
            notices.push(Object.assign({}, n, { _icon: ICON[n.type] || 'i-bell' }))
            return
          }
          const k = String(n.refId)
          if (!groups[k]) {
            const sess = sessMap[n.refId]
            groups[k] = {
              sessionId: n.refId,
              peerName: sess.peerName || '同学',
              peerId: sess.peerId,
              avatar: defaultAvatar(sess.peerId),
              preview: n.content || '',
              at: n.createdAt || '',
              count: 0,
              unread: 0
            }
          }
          groups[k].count += 1
          if (!n.read) groups[k].unread += 1
          if (n.createdAt && n.createdAt > groups[k].at) {
            groups[k].at = n.createdAt
            groups[k].preview = n.content || groups[k].preview
          }
        })

        const chatGroups = Object.keys(groups)
          .map((k) => groups[k])
          .sort((a, b) => (a.at < b.at ? 1 : -1))

        this.setData({
          chatGroups,
          notices,
          cntChat: chatGroups.length,
          cntNotice: notices.length,
          loading: false
        })
        return api.unreadCount()
      })
      .then((count) => {
        this.setData({ unreadCount: count || 0 })
      })
      .catch((err) => {
        this.setData({ loading: false, errMsg: err.msg || '加载失败' })
      })
  },

  // 事件处理：Filter
  onFilter(e) {
    const k = (e.currentTarget.dataset || {}).k || 'all'
    this.setData({ filter: k })
  },

  /** 点私信聚合卡 → 进对应会话 */
  onChatGroup(e) {
    const sid = Number((e.currentTarget.dataset || {}).sid) || 0
    if (!sid) return
    wx.navigateTo({ url: '/pages/chat/chat?sessionId=' + sid })
  },

  // 事件处理：Tap
  onTap(e) {
    const i = e.currentTarget.dataset.i
    const n = this.data.notices[i]
    if (!n) return

    // 先标记已读（失败也不挡跳转），再按 refType 跳转
    const markThenGo = () => {
      if (n.read) {
        this.goRef(n)
        return
      }
      api
        .readNotice(n.id)
        .then(() => {
          this.setData({
            ['notices[' + i + '].read']: true,
            unreadCount: Math.max(0, this.data.unreadCount - 1)
          })
          this.goRef(n)
        })
        .catch(() => this.goRef(n))
    }
    markThenGo()
  },

  // 方法：goRef
  goRef(n) {
    // 私信 → 直接进对应会话，可继续对话（peerName 由聊天页自行补全）
    if (n.refType === 'CHAT' && n.refId) {
      wx.navigateTo({ url: '/pages/chat/chat?sessionId=' + n.refId })
      return
    }
    // 帖子相关 → 帖子详情
    if (n.refType === 'POST' && n.refId) {
      wx.navigateTo({ url: '/pages/detail/detail?id=' + n.refId })
      return
    }
    // 审核类及其它：进通知详情页（正文可能很长，系统弹窗排版不可控）
    getApp().globalData._notice = n
    wx.navigateTo({ url: '/pages/notice/notice?id=' + (n.id || '') })
  },

  // 事件处理：ReadAll
  onReadAll() {
    api
      .readAllNotices()
      .then(() => {
        const list = this.data.list.map((n) => Object.assign({}, n, { read: true }))
        this.setData({ list, unreadCount: 0 })
        wx.showToast({ title: '已全部标为已读', icon: 'none' })
      })
      .catch((err) => {
        wx.showToast({ title: err.msg || '操作失败', icon: 'none' })
      })
  },

  // 事件处理：Tab
  onTab(e) {
    const k = e.currentTarget.dataset.k
    if (k === 'messages') return
    if (k === 'home') return wx.reLaunch({ url: '/pages/home/home' })
    if (k === 'mine') return wx.reLaunch({ url: '/pages/mine/mine' })
    if (k === 'category') return wx.reLaunch({ url: '/pages/category/category' })
    if (k === 'publish') return wx.navigateTo({ url: '/pages/publish/publish' })
    wx.showToast({ title: '该页面在下一批接入', icon: 'none' })
  }
})
