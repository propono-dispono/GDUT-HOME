/** category页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { BIZ, ORDER } = require('../../utils/biz')

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    groups: [],
    unreadCount: 0,
    loading: true,
    errMsg: ''
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
    api
      .unreadCount()
      .then((n) => this.setData({ unreadCount: n || 0 }))
      .catch(() => {})
  },

  // 加载数据：h
  fetch() {
    this.setData({ loading: true, errMsg: '' })
    return api
      .categories()
      .then((cats) => {
        const list = cats || []
        // 按业务线分组，保持 ORDER 的固定顺序，空分组不显示
        const groups = ORDER.map((biz) => ({
          biz,
          label: BIZ[biz].label,
          items: list.filter((c) => c.bizType === biz)
        })).filter((g) => g.items.length > 0)

        this.setData({ groups, loading: false })
      })
      .catch((err) => {
        this.setData({ loading: false, groups: [], errMsg: err.msg || '加载失败' })
      })
  },

  // 事件处理：Category
  onCategory(e) {
    const biz = e.currentTarget.dataset.biz
    const cat = e.currentTarget.dataset.cat
    wx.navigateTo({ url: '/pages/list/list?biz=' + biz + '&cat=' + cat })
  },

  // 事件处理：BizAll
  onBizAll(e) {
    const biz = e.currentTarget.dataset.biz
    wx.navigateTo({ url: '/pages/list/list?biz=' + biz })
  },

  // 事件处理：Tab
  onTab(e) {
    const k = e.currentTarget.dataset.k
    if (k === 'category') return
    if (k === 'home') return wx.reLaunch({ url: '/pages/home/home' })
    if (k === 'mine') return wx.reLaunch({ url: '/pages/mine/mine' })
    if (k === 'publish') return wx.navigateTo({ url: '/pages/publish/publish' })
    if (k === 'messages') return wx.reLaunch({ url: '/pages/messages/messages' })
    wx.showToast({ title: '该页面在下一批接入', icon: 'none' })
  }
})
