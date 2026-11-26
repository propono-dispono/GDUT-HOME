/** 私信会话 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { defaultAvatar } = require('../../utils/avatar')

Page({
  data: {
    safeTop: 20,
    safeBottom: 0,
    appbarH: 64,
    capsuleRight: 96,
    sessionId: 0,
    peerName: '',
    messages: [],
    draft: '',
    loading: true,
    scrollTo: '',
    refPost: null
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad(options) {
    const app = getApp()
    const sys = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    const safeBottom = Math.max(0, (sys.screenHeight || 0) - (sys.safeArea ? sys.safeArea.bottom : 0))

    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight,
      safeBottom,
      sessionId: Number(options.sessionId) || 0,
      peerName: options.peerName ? decodeURIComponent(options.peerName) : ''
    })
    // 从消息中心跳进来时只带 sessionId，标题需要自己补
    if (this.data.sessionId && !this.data.peerName) this.fillPeerName()
    this.loadRef()
    this.fetch()
  },

  /** 会话若来自某条内容，顶部展示引用卡（方案 A：把上下文带进聊天） */
  loadRef() {
    if (!this.data.sessionId) return
    api
      .chatSessions()
      .then((list) => {
        const s = (list || []).filter((x) => x.id === this.data.sessionId)[0]
        if (!s || s.refType !== 'POST' || !s.refId) return
        this.setData({ refPost: { id: s.refId, title: '查看关联内容', price: '', cover: '' } })
        return api.postDetail(s.refId).then((p) => {
          if (!p) return
          this.setData({
            refPost: {
              id: s.refId,
              title: p.title || '相关内容',
              price: p.price == null ? '' : p.price,
              cover: (p.images && p.images.length ? p.images[0] : '') || ''
            }
          })
        })
      })
      .catch(() => {})
  },

  // 事件处理：RefTap
  onRefTap() {
    const r = this.data.refPost
    if (r && r.id) wx.navigateTo({ url: '/pages/detail/detail?id=' + r.id })
  },

  /** 只带 sessionId 进来时，从会话列表里补对话方名字 */
  fillPeerName() {
    api
      .chatSessions()
      .then((list) => {
        const hit = (list || []).filter((x) => x.id === this.data.sessionId)[0]
        if (hit && hit.peerName) this.setData({ peerName: hit.peerName })
      })
      .catch(() => {})
  },

  /** 页面可见时开始轮询：3 秒拉一次，收到对方新消息就能看到 */
  onShow() {
    this.startPolling()
  },

  // 页面隐藏：暂停轮询等后台动作
  onHide() {
    this.stopPolling()
  },

  // 页面卸载：清理定时器/内存数据
  onUnload() {
    this.stopPolling()
  },

  // 方法：startPolling
  startPolling() {
    this.stopPolling()
    this._timer = setInterval(() => this.fetch(true), 3000)
  },

  // 方法：stopPolling
  stopPolling() {
    if (this._timer) {
      clearInterval(this._timer)
      this._timer = null
    }
  },

  /**
   * 拉取消息。
   * @param {boolean} silent true=轮询，不显示 loading、不打断阅读
   */
  fetch(silent) {
    if (!this.data.sessionId) {
      this.setData({ loading: false })
      return
    }
    if (!silent) this.setData({ loading: true })
    api
      .chatMessages(this.data.sessionId, { page: 1, size: 100 })
      .then((page) => {
        const messages = ((page && page.list) || []).slice()
        const oldList = this.data.messages || []
        const oldLast = oldList.length ? oldList[oldList.length - 1].id : 0
        const newLast = messages.length ? messages[messages.length - 1].id : 0
        // 轮询且没有新消息：不 setData，避免无谓渲染
        if (silent && messages.length === oldList.length && newLast === oldLast) {
          return
        }
        const withAvatar = messages.map((m) => Object.assign({}, m, {
          defaultAvatar: defaultAvatar(m.fromUserId)
        }))
        this.setData({ messages: withAvatar, loading: false })
        if (!silent || newLast !== oldLast) this.scrollToBottom()
      })
      .catch((err) => {
        this.setData({ loading: false })
        // 轮询失败不弹窗，避免网络抖动时一直打扰
        if (!silent) wx.showToast({ title: err.msg || '加载失败', icon: 'none' })
      })
  },

  /** 点头像看对方主页（自己的头像点了不跳） */
  onAvatarTap(e) {
    const uid = Number((e.currentTarget.dataset || {}).uid) || 0
    if (!uid) return
    wx.navigateTo({ url: '/pages/user/user?id=' + uid })
  },

  // 事件处理：Draft
  onDraft(e) {
    this.setData({ draft: e.detail.value })
  },

  // 事件处理：Send
  onSend() {
    const text = (this.data.draft || '').trim()
    if (!text) return
    if (this._sending) return
    this._sending = true

    // 先清空输入框，避免连点重复发送；失败时再还原
    this.setData({ draft: '' })
    api
      .sendChat(this.data.sessionId, text)
      .then((m) => {
        const messages = this.data.messages.concat([m])
        this.setData({ messages })
        this.scrollToBottom()
      })
      .catch((err) => {
        this.setData({ draft: text })
        wx.showToast({ title: err.msg || '发送失败', icon: 'none' })
      })
      .finally(() => {
        this._sending = false
      })
  },

  /** 滚到底部：用 scroll-into-view 而不是 scrollTop，兼容性更好 */
  scrollToBottom() {
    const list = this.data.messages
    if (!list.length) return
    this.setData({ scrollTo: 'm' + list[list.length - 1].id })
  },

  // 事件处理：Back
  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack()
    } else {
      wx.reLaunch({ url: '/pages/home/home' })
    }
  }
})
