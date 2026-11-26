/** publish页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { BIZ, ORDER } = require('../../utils/biz')

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function todayStr() {
  const d = new Date()
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

/**
 * 各业务线的专属字段定义。
 * 字段名与后端 PostCreateReq 一一对应，改这里必须同步改后端，否则字段会被静默丢弃。
 */
const SCHEMA = {
  SECONDHAND: [
    { k: 'categoryId', type: 'category', label: '分类', required: true },
    { k: 'price', type: 'number', label: '售价（元）', placeholder: '0.00', hint: '仅作展示，线下当面结算，平台不经手资金' },
    { k: 'negotiable', type: 'switch', label: '是否可议价', value: false }
  ],
  ERRAND: [
    { k: 'categoryId', type: 'category', label: '分类', required: true },
    { k: 'price', type: 'number', label: '酬金（元）', placeholder: '0.00', hint: '酬金由双方线下当面结算，平台不代收' },
    { k: 'pickupAddr', type: 'text', label: '取件地', required: true, placeholder: '例如：菜鸟驿站（东门）' },
    { k: 'deliverAddr', type: 'text', label: '送达地', required: true, placeholder: '例如：教学1号楼 3 层' },
    { k: 'deadline', type: 'datetime', label: '截止时间', required: true },
    { k: 'advancePay', type: 'switch', label: '需要垫付', value: false }
  ],
  SKILL: [
    { k: 'categoryId', type: 'category', label: '分类', required: true },
    { k: 'price', type: 'number', label: '单次报价（元）', placeholder: '0.00' },
    { k: 'serviceType', type: 'text', label: '技能类目', required: true, placeholder: '例如：PPT 美化 / 排版' },
    { k: 'deliveryDays', type: 'number', label: '交付天数', placeholder: '例如：3' }
  ],
  RENTAL: [
    { k: 'categoryId', type: 'category', label: '分类', required: true },
    { k: 'rentPrice', type: 'number', label: '日租金（元）', required: true, placeholder: '0.00', hint: '押金由双方线下协商，平台不代收、不代管' },
    { k: 'rentUnit', type: 'select', label: '计价单位', options: [{ v: '天', label: '按天' }, { v: '周', label: '按周' }], index: 0 },
    { k: 'deposit', type: 'number', label: '押金（元，仅记录）', placeholder: '0' },
    { k: 'minDays', type: 'number', label: '起租天数', placeholder: '例如：3' }
  ],
  LOST: [
    { k: 'lostType', type: 'select', label: '类型', required: true, options: [{ v: 1, label: '寻物启事' }, { v: 2, label: '拾物招领' }], index: 0 },
    { k: 'itemName', type: 'text', label: '物品名称', required: true, placeholder: '例如：黑色长柄雨伞' },
    { k: 'lostPlace', type: 'text', label: '地点', required: true, placeholder: '例如：图书馆 3 楼' },
    { k: 'lostTime', type: 'datetime', label: '时间' }
  ],
  GROUPBUY: [
    { k: 'categoryId', type: 'category', label: '分类', required: true },
    { k: 'price', type: 'number', label: '人均（元）', placeholder: '0.00' },
    { k: 'targetCount', type: 'number', label: '目标人数（含自己）', required: true, placeholder: '至少 2 人' },
    { k: 'groupbuyDeadline', type: 'datetime', label: '截止时间', required: true },
    { k: 'pickupPoint', type: 'text', label: '自提点', placeholder: '例如：教学1号楼 门厅' },
    { k: 'withErrand', type: 'switch', label: '需要代取', value: false }
  ]
}

function buildFields(biz, cats) {
  const defs = SCHEMA[biz] || []
  return defs.map((d) => {
    const f = Object.assign({}, d)
    if (f.type === 'category') {
      const opts = cats
        .filter((c) => c.bizType === biz)
        .map((c) => ({ id: c.id, label: c.label }))
      f.options = opts
      f.index = 0
      f.selected = opts.length ? opts[0].label : '暂无可用分类'
    } else if (f.type === 'select') {
      f.options = d.options
      f.index = d.index || 0
      f.selected = d.options[f.index].label
    } else if (f.type === 'datetime') {
      f.date = ''
      f.time = ''
    } else if (f.type === 'switch') {
      f.value = d.value === true
    } else {
      f.value = ''
    }
    return f
  })
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    biz: 'SECONDHAND',
    bizLabel: '二手闲置',
    noticeText: '',
    bizList: [],
    today: '',
    title: '',
    content: '',
    building: '',
    tags: '',
    images: [],
    uploading: false,
    fields: [],
    submitting: false
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad(options) {
    const app = getApp()
    const biz = BIZ[options.type] ? options.type : 'SECONDHAND'
    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight,
      biz,
      bizLabel: BIZ[biz].label,
      noticeText: BIZ[biz].notice,
      today: todayStr(),
      bizList: ORDER.map((k) => ({ k, short: BIZ[k].short }))
    })
    this.loadCategories()
  },

  // 加载数据：Categories
  loadCategories() {
    api
      .categories()
      .then((cats) => {
        this._cats = cats || []
        this.setData({ fields: buildFields(this.data.biz, this._cats) })
      })
      .catch((err) => {
        this._cats = []
        this.setData({ fields: buildFields(this.data.biz, []) })
        wx.showToast({ title: err.msg || '分类加载失败', icon: 'none' })
      })
  },

  /* ---------- 基础字段 ---------- */
  onTitle(e) {
    this.setData({ title: e.detail.value })
  },
  // 事件处理：Content
  onContent(e) {
    this.setData({ content: e.detail.value })
  },
  // 事件处理：Building
  onBuilding(e) {
    this.setData({ building: e.detail.value })
  },
  // 事件处理：Tags
  onTags(e) {
    this.setData({ tags: e.detail.value })
  },

  /* ---------- 类型切换 ---------- */
  onBiz(e) {
    const biz = e.currentTarget.dataset.k
    if (biz === this.data.biz) return
    this.setData({
      biz,
      bizLabel: BIZ[biz].label,
      noticeText: BIZ[biz].notice,
      fields: buildFields(biz, this._cats || [])
    })
  },

  /* ---------- 动态字段 ---------- */
  onFieldInput(e) {
    const i = e.currentTarget.dataset.i
    this.setData({ ['fields[' + i + '].value']: e.detail.value })
  },

  // 事件处理：CategoryChange
  onCategoryChange(e) {
    const i = e.currentTarget.dataset.i
    const idx = Number(e.detail.value)
    const f = this.data.fields[i]
    this.setData({
      ['fields[' + i + '].index']: idx,
      ['fields[' + i + '].selected']: f.options[idx] ? f.options[idx].label : ''
    })
  },

  // 事件处理：SelectChange
  onSelectChange(e) {
    const i = e.currentTarget.dataset.i
    const idx = Number(e.detail.value)
    const f = this.data.fields[i]
    this.setData({
      ['fields[' + i + '].index']: idx,
      ['fields[' + i + '].selected']: f.options[idx] ? f.options[idx].label : ''
    })
  },

  // 事件处理：DateChange
  onDateChange(e) {
    const i = e.currentTarget.dataset.i
    this.setData({ ['fields[' + i + '].date']: e.detail.value })
  },

  // 事件处理：TimeChange
  onTimeChange(e) {
    const i = e.currentTarget.dataset.i
    this.setData({ ['fields[' + i + '].time']: e.detail.value })
  },

  // 事件处理：SwitchChange
  onSwitchChange(e) {
    const i = e.currentTarget.dataset.i
    this.setData({ ['fields[' + i + '].value']: e.detail.value })
  },

  /* ---------- 图片 ---------- */
  onChooseImage() {
    const left = 9 - this.data.images.length
    if (left <= 0) return
    wx.chooseMedia({
      count: left,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: (res) => {
        res.tempFiles.forEach((f) => this.uploadOne(f.tempFilePath))
      }
    })
  },

  // 方法：uploadOne
  uploadOne(filePath) {
    this.setData({ uploading: true })
    api
      .uploadImage(filePath, 'post')
      .then((vo) => {
        const images = this.data.images.concat([{ key: vo.key, url: vo.url }])
        this.setData({ images, uploading: false })
      })
      .catch((err) => {
        this.setData({ uploading: false })
        wx.showModal({ title: '图片上传失败', content: err.msg || '请重试', showCancel: false })
      })
  },

  // 事件处理：PreviewImage
  onPreviewImage(e) {
    const i = e.currentTarget.dataset.i
    const urls = this.data.images.map((x) => x.url)
    wx.previewImage({ current: urls[i], urls })
  },

  // 事件处理：RemoveImage
  onRemoveImage(e) {
    const i = e.currentTarget.dataset.i
    const images = this.data.images.slice()
    images.splice(i, 1)
    this.setData({ images })
  },

  /* ---------- 提交 ---------- */
  buildPayload() {
    const d = this.data
    const payload = {
      bizType: d.biz,
      title: d.title.trim(),
      content: d.content.trim(),
      images: d.images.map((x) => x.key)
    }
    if (d.building.trim()) payload.building = d.building.trim()
    if (d.tags.trim()) payload.tags = d.tags.trim()

    d.fields.forEach((f) => {
      if (f.type === 'category') {
        const opt = f.options[f.index]
        if (opt) payload.categoryId = opt.id
      } else if (f.type === 'select') {
        payload[f.k] = f.options[f.index].v
      } else if (f.type === 'switch') {
        payload[f.k] = f.value ? 1 : 0
      } else if (f.type === 'datetime') {
        if (f.date && f.time) payload[f.k] = f.date + ' ' + f.time + ':00'
      } else if (f.type === 'number') {
        const v = String(f.value).trim()
        if (v !== '') payload[f.k] = Number(v)
      } else {
        const v = String(f.value).trim()
        if (v !== '') payload[f.k] = v
      }
    })

    // 失物招领在后端 CategoryEnum 里没有对应分类，但 categoryId 是 @NotNull，用「其他(6)」占位
    if (!payload.categoryId) payload.categoryId = 6

    return payload
  },

  // 方法：validate
  validate() {
    const d = this.data
    if (!d.title.trim()) return '请填写标题'
    if (!d.content.trim()) return '请填写描述'
    if (d.uploading) return '图片还在上传，请稍等'

    for (let i = 0; i < d.fields.length; i++) {
      const f = d.fields[i]
      if (!f.required) continue
      if (f.type === 'datetime') {
        if (!f.date || !f.time) return '请选择' + f.label
      } else if (f.type === 'category') {
        if (!f.options.length) return '该类型暂无可用分类'
      } else if (f.type === 'text' || f.type === 'number') {
        if (!String(f.value).trim()) return '请填写' + f.label
      }
    }

    const target = d.fields.filter((f) => f.k === 'targetCount')[0]
    if (target && Number(target.value) < 2) return '拼单目标人数至少 2 人'

    return ''
  },

  // 事件处理：Submit
  onSubmit() {
    if (this.data.submitting) return
    const err = this.validate()
    if (err) {
      wx.showToast({ title: err, icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    api
      .createPost(this.buildPayload())
      .then((post) => {
        this.setData({ submitting: false })
        const online = post && post.status === 1
        wx.showModal({
          title: online ? '发布成功' : '已提交审核',
          content: online
            ? '内容已上架，可在首页看到'
            : '内容进入人工审核队列，通过后才会在首页展示。可在「我的发布」查看状态。',
          confirmText: '知道了',
          showCancel: false,
          success: () => {
            wx.reLaunch({ url: '/pages/posts/posts' })
          }
        })
      })
      .catch((e) => {
        this.setData({ submitting: false })
        const msg = (e && e.msg) || '发布失败'
        wx.showModal({ title: '发布未成功', content: msg, showCancel: false })
      })
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
