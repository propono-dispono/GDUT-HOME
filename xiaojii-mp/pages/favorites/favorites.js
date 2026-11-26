/** favorites页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { decorate } = require('../../utils/biz')

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    list: [],
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
    // 从详情页取消收藏后返回，需要刷新
    if (this._loadedOnce) this.fetch()
    this._loadedOnce = true
  },

  // 加载数据：h
  fetch() {
    this.setData({ loading: true, errMsg: '' })
    return api
      .favList({ page: 1, size: 50 })
      .then((page) => {
        const list = ((page && page.list) || []).map(decorate)
        this.setData({ list, loading: false })
      })
      .catch((err) => {
        this.setData({ loading: false, list: [], errMsg: (err && err.msg) || '加载失败' })
      })
  },

  // 事件处理：CardTap
  onCardTap(e) {
    const id = e.currentTarget.dataset.id
    if (id) wx.navigateTo({ url: '/pages/detail/detail?id=' + id })
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
