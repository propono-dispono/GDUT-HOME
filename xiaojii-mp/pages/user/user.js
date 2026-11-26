/** 他人主页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { defaultAvatar } = require('../../utils/avatar')
const { decorate } = require('../../utils/biz')

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    id: 0,
    user: null,
    initial: '·',
    avatarPath: '',
    nickname: '同学',
    verified: false,
    credit: '--',
    intro: '',
    meta: '',
    tags: [],
    availableTime: '',
    lookingText: '',
    list: [],
    loading: true,
    errMsg: ''
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad(options) {
    const app = getApp()
    const id = Number(options.id) || 0
    this.setData({
      id,
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })
    if (!id) {
      this.setData({ loading: false, errMsg: '缺少用户 ID' })
      return
    }
    this.fetch()
  },

  // 加载数据：h
  fetch() {
    const id = this.data.id
    Promise.all([api.userDetail(id), api.userPosts(id, { page: 1, size: 50 })])
      .then(([u, page]) => {
        const user = u || {}
        const list = ((page && page.list) || []).map(decorate)

        // 后端已按 privacy 过滤：拿不到的字段就是对方选择不公开，这里不再猜
        const metaParts = []
        if (user.major) metaParts.push(user.major)
        if (user.grade) metaParts.push(user.grade + '级')
        if (user.college) metaParts.push(user.college)
        if (user.campus) metaParts.push(user.campus)
        if (user.age) metaParts.push(user.age + '岁')

        this.setData({
          loading: false,
          user,
          initial: user.initial || (user.nickname ? user.nickname.charAt(0) : '?'),
          avatarPath: defaultAvatar(user.id),
          nickname: user.nickname || '同学',
          verified: !!user.verified,
          credit: user.creditScore == null ? '--' : user.creditScore,
          intro: user.intro || '',
          meta: metaParts.join(' · '),
          tags: user.tags || [],
          availableTime: user.availableTime || '',
          lookingText: (user.lookingFor || []).join('、'),
          list
        })
      })
      .catch((err) => {
        this.setData({ loading: false, errMsg: (err && err.msg) || '加载失败' })
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
      wx.reLaunch({ url: '/pages/home/home' })
    }
  }
})
