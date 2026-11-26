/** list页 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { BIZ, decorate } = require('../../utils/biz')

const SORTS = [
  { k: 'latest', t: '综合' },
  { k: 'priceAsc', t: '价格 ↑' },
  { k: 'priceDesc', t: '价格 ↓' }
]

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    bizTitle: '全部',
    biz: 'ALL',
    categoryId: 0,
    keyword: '',
    sorts: SORTS,
    sort: 'latest',
    list: [],
    count: 0,
    loading: true,
    errMsg: ''
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad(options) {
    const biz = options.biz || 'ALL'
    const keyword = options.kw || ''
    const categoryId = Number(options.cat) || 0
    const title = biz === 'ALL' ? '全部' : BIZ[biz] ? BIZ[biz].label : '列表'

    const safeTop = getApp().globalData.safeTop
    this.setData({
      safeTop,
      appbarH: safeTop + 44,
      capsuleRight: getApp().globalData.capsuleRight,
      biz,
      categoryId,
      keyword,
      bizTitle: title
    })
    this.fetchList()
  },

  // 加载数据：hList
  fetchList() {
    const params = { page: 1, size: 20, sort: this.data.sort }
    if (this.data.biz !== 'ALL') params.bizType = this.data.biz
    if (this.data.categoryId) params.categoryId = this.data.categoryId
    if (this.data.keyword) params.keyword = this.data.keyword

    this.setData({ loading: true, errMsg: '' })

    return api
      .feed(params)
      .then((page) => {
        const list = ((page && page.list) || []).map(decorate)
        this.setData({
          list,
          count: (page && page.total) || list.length,
          loading: false
        })
      })
      .catch((err) => {
        this.setData({ loading: false, list: [], count: 0, errMsg: err.msg || '加载失败' })
      })
  },

  // 事件处理：Sort
  onSort(e) {
    const k = e.currentTarget.dataset.k
    if (k === this.data.sort) return
    this.setData({ sort: k })
    this.fetchList()
  },

  // 事件处理：CardTap
  onCardTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/detail/detail?id=' + id })
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
