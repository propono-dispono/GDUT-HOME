/** 登录 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../../utils/api')

/**
 * 教务登录页。
 *
 * 设计要点：
 *  1. 密码不落本地、不进 storage，只在内存里活到这一次请求结束。
 *  2. 走统一认证（CAS）：只需要学号 + 密码，没有图形验证码。
 *  3. 勾选「记住密码」后，账号密码加密保存在服务端（30 天滑动 / 90 天硬顶），
 *     用于 Cookie 失效时自动重登；不勾选则只保留 12 小时的会话（旧行为）。
 */
Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    account: '',
    password: '',
    // 默认勾选：用户来绑定教务就是为了少输密码；取消勾选则不保存（每次需重登）
    remember: true,
    captcha: '',
    showPwd: false,
    sessionId: '',
    captchaImg: '',
    captchaLoading: true,
    submitting: false,
    errMsg: ''
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const app = getApp()
    const safeTop = app.globalData.safeTop
    this.setData({
      safeTop,
      appbarH: safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })
  },

  // 页面卸载：清理定时器/内存数据
  onUnload() {
    // 离开页面即丢弃内存里的密码
    this.setData({ password: '' })
  },

  /* ---------- 输入 ---------- */

  // 事件处理：Account
  onAccount(e) {
    this.setData({ account: e.detail.value, errMsg: '' })
  },

  // 事件处理：Password
  onPassword(e) {
    this.setData({ password: e.detail.value, errMsg: '' })
  },

  // 事件处理：TogglePwd
  onTogglePwd() {
    this.setData({ showPwd: !this.data.showPwd })
  },

  // 事件处理：ToggleRemember
  onToggleRemember() {
    this.setData({ remember: !this.data.remember })
  },

  /* ---------- 提交 ---------- */

  // 事件处理：Submit
  onSubmit() {
    if (this.data.submitting) return

    const account = (this.data.account || '').trim()
    const password = this.data.password || ''

    if (!account) {
      this.setData({ errMsg: '请输入学号' })
      return
    }
    if (!password) {
      this.setData({ errMsg: '请输入教务密码' })
      return
    }

    this.setData({ submitting: true, errMsg: '' })

    api
      .eduLogin({
        account,
        password,
        remember: this.data.remember
      })
      .then(() => {
        this.setData({ submitting: false, password: '' })
        wx.showToast({ title: '登录成功', icon: 'success', duration: 900 })
        setTimeout(() => this.backOrEdu(), 900)
      })
      .catch((err) => {
        this.setData({ submitting: false, errMsg: (err && err.msg) || '登录失败，请重试' })
          })
  },

  /* ---------- 导航 ---------- */

  // 方法：backOrEdu
  backOrEdu() {
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack()
      return
    }
    wx.reLaunch({ url: '/pages/edu/edu' })
  },

  // 事件处理：Back
  onBack() {
    this.backOrEdu()
  }
})
