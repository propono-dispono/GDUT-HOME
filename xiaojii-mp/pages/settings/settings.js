/** 设置 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    nickname: '',
    saving: false,
    version: '0.2.0'
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const app = getApp()
    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })
    this.load()
  },

  // 加载数据：
  load() {
    api
      .profile()
      .then((me) => this.setData({ nickname: (me && me.nickname) || '' }))
      .catch(() => {})
  },

  // 事件处理：Nick
  onNick(e) {
    this.setData({ nickname: e.detail.value })
  },

  // 事件处理：Save
  onSave() {
    const nickname = (this.data.nickname || '').trim()
    if (!nickname) {
      wx.showToast({ title: '昵称不能为空', icon: 'none' })
      return
    }
    if (this.data.saving) return
    this.setData({ saving: true })
    api
      .updateProfile({ nickname })
      .then((me) => {
        this.setData({ nickname: (me && me.nickname) || nickname })
        wx.showToast({ title: '已保存', icon: 'success' })
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.msg) || '保存失败', icon: 'none' })
      })
      .finally(() => this.setData({ saving: false }))
  },

  // 事件处理：Clear
  onClear() {
    wx.showModal({
      title: '清除缓存',
      content: '会清掉本地登录态与草稿，下次进入自动重新登录。',
      success: (r) => {
        if (!r.confirm) return
        try {
          wx.clearStorageSync()
        } catch (e) {
          /* ignore */
        }
        api.clearToken()
        wx.showToast({ title: '已清除', icon: 'success' })
      }
    })
  },

  // 事件处理：About
  onAbout() {
    wx.showModal({
      title: '关于校集',
      content: '校集 · 广工校园互助平台\n版本 ' + this.data.version + '\n\n仅提供信息展示与私信联系，不涉及任何在线支付。',
      showCancel: false,
      confirmText: '知道了'
    })
  },

  // 事件处理：Logout
  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '退出后需要重新登录才能发布和私信。',
      confirmText: '退出',
      success: (r) => {
        if (!r.confirm) return
        wx.showLoading({ title: '退出中…', mask: true })
        api
          .logout()
          .catch(() => {})
          .finally(() => {
            wx.hideLoading()
            api.clearToken()
            wx.reLaunch({ url: '/pages/home/home' })
          })
      }
    })
  },

  // 事件处理：Back
  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack()
    } else {
      wx.reLaunch({ url: '/pages/mine/mine' })
    }
  }
})
