/** mine页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { defaultAvatar } = require('../../utils/avatar')

/** 两套状态码含义相反，这里只按 user.verifyStatus 判（0未认证 1待审核 2已认证 3驳回） */
function verifyLabel(status) {
  if (status === 2) return { text: '已认证', cls: 'tag--solid' }
  if (status === 1) return { text: '审核中', cls: 'tag--outline' }
  if (status === 3) return { text: '认证被驳回', cls: 'tag--outline' }
  return { text: '未认证', cls: 'tag--outline' }
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    loading: true,
    me: {
      initial: '·',
      avatar: '',
      nickname: '加载中',
      creditScore: '--',
      verifyStatus: 0,
      postCount: '--',
      dealCount: '--',
      meta: '',
      intro: '',
      tags: []
    },
    verifyText: '',
    verifyCls: 'tag--outline',
    unreadCount: 0
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const safeTop = getApp().globalData.safeTop
    this.setData({ safeTop, appbarH: safeTop + 44, capsuleRight: getApp().globalData.capsuleRight })
    this.fetch()
  },

  // 页面显示：刷新状态（从其他页返回时也会触发）
  onShow() {
    if (this._loadedOnce) this.fetch()
    this._loadedOnce = true
    api
      .unreadCount()
      .then((n) => this.setData({ unreadCount: n || 0 }))
      .catch(() => {})
  },

  // 加载数据：h
  fetch() {
    return api
      .profile()
      .then((u) => {
        const v = verifyLabel(u.verifyStatus)
        const metaParts = []
        if (u.major) metaParts.push(u.major)
        if (u.grade) metaParts.push(u.grade + '级')
        if (u.age) metaParts.push(u.age + '岁')
        this.setData({
          loading: false,
          verifyText: v.text,
          verifyCls: v.cls,
          me: {
            initial: u.initial || (u.nickname ? u.nickname.charAt(0) : '·'),
            avatar: u.avatar || '',
            defaultAvatar: defaultAvatar(u.id),
            nickname: u.nickname || '同学',
            creditScore: u.creditScore == null ? '--' : u.creditScore,
            verifyStatus: u.verifyStatus == null ? 0 : u.verifyStatus,
            postCount: u.postCount == null ? 0 : u.postCount,
            dealCount: u.dealCount == null ? 0 : u.dealCount,
            meta: metaParts.join(' · '),
            intro: u.intro || '',
            tags: u.tags || []
          }
        })
      })
      .catch((err) => {
        wx.showToast({ title: err.msg || '资料加载失败', icon: 'none' })
      })
  },

  // 事件处理：Tab
  onTab(e) {
    const k = e.currentTarget.dataset.k
    if (k === 'mine') return
    if (k === 'home') {
      wx.reLaunch({ url: '/pages/home/home' })
      return
    }
    if (k === 'publish') {
      wx.navigateTo({ url: '/pages/publish/publish' })
      return
    }
    if (k === 'category') {
      wx.reLaunch({ url: '/pages/category/category' })
      return
    }
    if (k === 'messages') {
      wx.reLaunch({ url: '/pages/messages/messages' })
      return
    }
    wx.showToast({ title: '该页面在下一批接入', icon: 'none' })
  },

  // 事件处理：Settings
  onSettings() {
    wx.navigateTo({ url: '/pages/settings/settings' })
  },

  // 事件处理：Edu
  onEdu() {
    wx.navigateTo({ url: '/pages/edu/edu' })
  },

  // 事件处理：Verify
  onVerify() {
    if (this.data.me.verifyStatus === 2) {
      wx.showToast({ title: '你已完成校园认证', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/verify/verify' })
  },

  // 事件处理：MyPosts
  onMyPosts() {
    wx.navigateTo({ url: '/pages/posts/posts' })
  },

  // 事件处理：Fav
  onFav() {
    wx.navigateTo({ url: '/pages/favorites/favorites' })
  },

  // 事件处理：EditProfile
  onEditProfile() {
    wx.navigateTo({ url: '/pages/profile-edit/profile-edit' })
  }
})
