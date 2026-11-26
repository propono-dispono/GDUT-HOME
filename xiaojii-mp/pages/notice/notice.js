/** 通知 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const ICON_TEXT = {
  AUDIT: '审核结果',
  CHAT: '私信提醒',
  SYSTEM: '系统通知',
  GROUPBUY: '拼单消息',
  REPORT: '举报处理',
  ORDER: '订单消息'
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    notice: null
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad(options) {
    const app = getApp()
    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })

    // 列表页把整条通知放进 globalData 再跳过来：正文可能很长，不适合塞 URL
    const n = app.globalData._notice
    if (n && (!options.id || String(n.id) === String(options.id))) {
      this.setData({
        notice: Object.assign({}, n, {
          typeText: n.typeText || ICON_TEXT[n.type] || '通知'
        })
      })
      app.globalData._notice = null
      if (n.id && !n.read) {
        require('../../utils/api')
          .readNotice(n.id)
          .catch(() => {})
      }
      return
    }

    // 直接进来（或刷新）时拿不到正文，给个明确空态，别显示半截内容
    this.setData({ notice: null })
  },

  // 事件处理：Back
  onBack() {
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack()
    } else {
      wx.reLaunch({ url: '/pages/messages/messages' })
    }
  },

  // 事件处理：Copy
  onCopy() {
    const n = this.data.notice
    if (!n || !n.content) return
    wx.setClipboardData({ data: n.content })
  }
})
