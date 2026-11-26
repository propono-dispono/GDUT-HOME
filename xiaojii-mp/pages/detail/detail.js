/** detail页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { defaultAvatar } = require('../../utils/avatar')
const { BIZ } = require('../../utils/biz')

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    id: 0,
    loading: true,
    errMsg: '',
    post: null,
    bizLabel: '',
    bizIcon: 'i-box',
    heroCover: '',
    fields: [],
    favored: false,
    favBusy: false,
    ctaText: '私信联系',
    groupBusy: false,
    userInitial: '',
    userAvatarPath: '',
    userName: '',
    userSub: ''
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad(options) {
    const id = Number(options.id) || 0
    const safeTop = getApp().globalData.safeTop
    this.setData({ safeTop, appbarH: safeTop + 44,
      capsuleRight: getApp().globalData.capsuleRight, id })
    if (!id) {
      this.setData({ loading: false, errMsg: '缺少内容 ID' })
      return
    }
    this.fetch()
  },

  /** @param {boolean} silent true=静默刷新（不显示 loading） */
  fetch(silent) {
    if (!silent) this.setData({ loading: true, errMsg: '' })
    api
      .postDetail(this.data.id)
      .then((d) => {
        const biz = BIZ[d.biz] || BIZ.SECONDHAND
        const u = d.user || {}
        let ctaText = d.cta || '私信联系'
        if (d.biz === 'GROUPBUY') {
          if (d.owner) ctaText = '我是发起人'
          else if (d.groupbuyExpired) ctaText = '拼单已截止'
          else if (d.joined) ctaText = '退出拼单'
          else ctaText = '参与拼单'
        }
        this.setData({
          loading: false,
          post: d,
          bizLabel: biz.label,
          bizIcon: biz.icon,
          heroCover: (d.images && d.images[0]) || '',
          fields: d.fields || [],
          ctaText,
          userInitial: u.initial || (u.nickname ? u.nickname.charAt(0) : '?'),
          userAvatarPath: defaultAvatar(u.id),
          userName: u.nickname || '同学',
          userSub:
            (u.verified ? '已认证 · ' : '') + '信用分 ' + (u.creditScore == null ? '--' : u.creditScore)
        })
        this.loadFav()
      })
      .catch((err) => {
        this.setData({ loading: false, errMsg: err.msg || '加载失败' })
      })
  },

  /** 封面加载失败：清空后回退到占位图标，避免详情页顶部一片空白 */
  onHeroError() {
    this.setData({ heroCover: '' })
  },

  // 事件处理：Back
  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack()
    } else {
      wx.reLaunch({ url: '/pages/home/home' })
    }
  },

  /** 拉收藏状态：失败静默，不影响详情浏览 */
  loadFav() {
    api
      .favStatus(this.data.id)
      .then((favored) => this.setData({ favored }))
      .catch(() => {})
  },

  // 事件处理：Fav
  onFav() {
    if (this.data.favBusy) return
    const next = !this.data.favored
    this.setData({ favBusy: true })
    const task = next ? api.favAdd(this.data.id) : api.favRemove(this.data.id)
    task
      .then(() => {
        this.setData({ favored: next })
        wx.showToast({ title: next ? '已收藏' : '已取消收藏', icon: 'none' })
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.msg) || '操作失败', icon: 'none' })
      })
      .finally(() => {
        this.setData({ favBusy: false })
      })
  },

  // 事件处理：More
  onMore() {
    const p = this.data.post
    if (!p) return
    wx.showActionSheet({
      itemList: ['举报该内容'],
      success: () => {
        const REASONS = ['广告/引流', '诈骗/欺诈', '违禁内容', '辱骂攻击', '虚假信息', '其他']
        wx.showActionSheet({
          itemList: REASONS,
          success: (r) => this.doReport(p, REASONS[r.tapIndex])
        })
      }
    })
  },

  /** 提交举报：同一内容只能举报一次，后端会拦重复 */
  doReport(post, reasonType) {
    wx.showModal({
      title: '确认举报',
      content: '举报原因：' + reasonType + '，我们会尽快核实处理。',
      confirmText: '提交举报',
      success: (res) => {
        if (!res.confirm) return
        api.request('/app/report', {
          method: 'POST',
          data: { targetType: 'POST', targetId: post.id, reasonType }
        }).then(() => {
          wx.showToast({ title: '已提交，感谢反馈', icon: 'none' })
        }).catch((err) => {
          wx.showModal({ title: '提交失败', content: err.msg || '请稍后重试', showCancel: false })
        })
      }
    })
  },

  // 事件处理：User
  onUser() {
    const p = this.data.post
    if (!p) return
    const uid = (p.user && (p.user.id || p.user.userId)) || 0
    if (!uid) {
      wx.showToast({ title: '暂时无法查看主页', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/user/user?id=' + uid })
  },

  // 事件处理：Chat
  onChat() {
    const p = this.data.post
    if (!p) return
    if (p.owner) {
      wx.showToast({ title: '这是你自己发布的内容', icon: 'none' })
      return
    }
    const uid = (p.user && (p.user.id || p.user.userId)) || 0
    if (!uid) {
      wx.showToast({ title: '暂时无法发起私信', icon: 'none' })
      return
    }
    if (this._opening) return
    this._opening = true
    wx.showLoading({ title: '打开会话…', mask: true })
    api
      .openChat(uid, 'POST', p.id)
      .then((s) => {
        wx.hideLoading()
        wx.navigateTo({
          url: '/pages/chat/chat?sessionId=' + s.id + '&peerName=' + encodeURIComponent(s.peerName || '')
        })
      })
      .catch((err) => {
        wx.hideLoading()
        wx.showModal({ title: '打不开会话', content: err.msg || '请稍后重试', showCancel: false })
      })
      .finally(() => {
        this._opening = false
      })
  },

  /**
   * 底部主按钮：
   *  - 拼单：参加 / 退出（不涉及支付）
   *  - 其他业务线：私信联系
   */
  onCta() {
    const p = this.data.post
    if (!p) return
    if (p.biz === 'GROUPBUY') {
      this.toggleGroupbuy()
      return
    }
    this.onChat()
  },

  // 方法：toggleGroupbuy
  toggleGroupbuy() {
    const p = this.data.post
    if (!p || this.data.groupBusy) return
    if (p.owner) {
      wx.showToast({ title: '你是发起人，已在拼单中', icon: 'none' })
      return
    }
    if (p.groupbuyExpired) {
      wx.showToast({ title: '该拼单已截止', icon: 'none' })
      return
    }
    const joined = !!p.joined
    const doIt = () => {
      this.setData({ groupBusy: true })
      const task = joined ? api.leaveGroupbuy(p.id) : api.joinGroupbuy(p.id)
      task
        .then(() => {
          wx.showToast({ title: joined ? '已退出拼单' : '已参加拼单', icon: 'success' })
          this.fetch(true)
        })
        .catch((err) => {
          wx.showToast({ title: (err && err.msg) || '操作失败', icon: 'none' })
        })
        .finally(() => this.setData({ groupBusy: false }))
    }

    if (!joined) {
      doIt()
      return
    }
    wx.showModal({
      title: '退出拼单',
      content: '退出后名额会释放给其他同学，确定退出吗？',
      confirmText: '退出',
      success: (r) => {
        if (r.confirm) doIt()
      }
    })
  }
})
