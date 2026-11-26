/** 校园认证 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')

/** 认证状态：0 未认证 / 1 待审核 / 2 已认证 / 3 驳回（与 user.verifyStatus 同义） */
const STATE = {
  0: { title: '未认证', desc: '完成校园认证后才能发布内容', tag: '未认证', cls: 'tag--outline' },
  1: { title: '认证审核中', desc: '通常 1 个工作日内完成，请耐心等待', tag: '审核中', cls: 'tag--outline' },
  2: { title: '已通过校园认证', desc: '你可以正常发布内容与接单', tag: '已认证', cls: 'tag--solid' },
  3: { title: '认证未通过', desc: '请根据驳回原因修改后重新提交', tag: '已驳回', cls: 'tag--outline' }
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    status: 0,
    statusTitle: '加载中',
    statusDesc: '',
    statusTag: '',
    statusTagCls: 'tag--outline',
    canSubmit: false,
    maskedNo: '',
    maskedName: '',
    submittedAt: '',
    reviewedAt: '',
    rejectReason: '',
    studentNo: '',
    realName: '',
    cardImage: { key: '', url: '' },
    uploading: false,
    submitting: false
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const app = getApp()
    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })
    this.fetchState()
  },

  // 加载数据：hState
  fetchState() {
    return api
      .request('/app/user/verify')
      .then((vo) => {
        const st = vo.status == null ? 0 : vo.status
        const info = STATE[st] || STATE[0]
        this.setData({
          status: st,
          statusTitle: vo.statusText || info.title,
          statusDesc: info.desc,
          statusTag: info.tag,
          statusTagCls: info.cls,
          canSubmit: st === 0 || st === 3,
          maskedNo: vo.studentNo || '',
          maskedName: vo.realName || '',
          submittedAt: vo.submittedAt || '',
          reviewedAt: vo.reviewedAt || '',
          rejectReason: vo.rejectReason || ''
        })
      })
      .catch((err) => {
        this.setData({ statusTitle: '状态加载失败', statusDesc: err.msg || '请稍后重试' })
      })
  },

  // 事件处理：StudentNo
  onStudentNo(e) {
    this.setData({ studentNo: e.detail.value })
  },

  // 事件处理：RealName
  onRealName(e) {
    this.setData({ realName: e.detail.value })
  },

  // 事件处理：ChooseCard
  onChooseCard() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: (res) => {
        this.setData({ uploading: true })
        api
          .uploadImage(res.tempFiles[0].tempFilePath, 'card')
          .then((vo) => {
            this.setData({ uploading: false, cardImage: { key: vo.key, url: vo.url } })
          })
          .catch((err) => {
            this.setData({ uploading: false })
            wx.showModal({ title: '照片上传失败', content: err.msg || '请重试', showCancel: false })
          })
      }
    })
  },

  // 事件处理：PreviewCard
  onPreviewCard() {
    if (this.data.cardImage.url) {
      wx.previewImage({ urls: [this.data.cardImage.url] })
    }
  },

  // 事件处理：RemoveCard
  onRemoveCard() {
    this.setData({ cardImage: { key: '', url: '' } })
  },

  // 事件处理：Submit
  onSubmit() {
    if (this.data.submitting) return
    const no = this.data.studentNo.trim()
    const name = this.data.realName.trim()

    if (!no) return wx.showToast({ title: '请填写学号', icon: 'none' })
    if (!/^[0-9A-Za-z]{6,20}$/.test(no)) return wx.showToast({ title: '学号格式不正确', icon: 'none' })
    if (!name) return wx.showToast({ title: '请填写真实姓名', icon: 'none' })
    if (!this.data.cardImage.key) return wx.showToast({ title: '请上传校园卡照片', icon: 'none' })
    if (this.data.uploading) return wx.showToast({ title: '照片还在上传', icon: 'none' })

    this.setData({ submitting: true })
    api
      .request('/app/user/verify', {
        method: 'POST',
        data: { studentNo: no, realName: name, cardImage: this.data.cardImage.key }
      })
      .then(() => {
        this.setData({ submitting: false })
        wx.showModal({
          title: '已提交',
          content: '认证进入人工审核，通过后即可发布内容。',
          showCancel: false,
          success: () => {
            this.setData({ studentNo: '', realName: '', cardImage: { key: '', url: '' } })
            this.fetchState()
          }
        })
      })
      .catch((err) => {
        this.setData({ submitting: false })
        wx.showModal({ title: '提交未成功', content: err.msg || '请稍后重试', showCancel: false })
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
