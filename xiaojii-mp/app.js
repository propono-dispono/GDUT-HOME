const api = require('./utils/api')

App({
  globalData: {
    /** 状态栏高度，供自定义 appbar 留白 */
    safeTop: 20,
    /** appbar 右侧要避开的胶囊按钮宽度（自定义导航栏必须避开，否则右上角图标被盖住） */
    capsuleRight: 96,
    /** 后端接口根地址（本地联调） */
    apiBase: api.BASE
  },

  onLaunch() {
    let info = {}
    try {
      info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    } catch (e) {
      info = {}
    }
    this.globalData.safeTop = info.statusBarHeight || 20

    // 胶囊按钮位置 -> 右侧留白，避免自定义 appbar 的右上角图标被它压住
    try {
      const menu = wx.getMenuButtonBoundingClientRect()
      if (menu && menu.left && info.windowWidth) {
        this.globalData.capsuleRight = info.windowWidth - menu.left + 8
      }
    } catch (e) {
      /* 取不到就用默认值 */
    }

    // 首启静默登录：develop 走 dev-login；trial/release 走 wx.login（见 utils/api.js）
    api.ensureToken().catch((err) => {
      console.error('[校集] 登录失败：', err)
    })
  }
})
