/** 编辑资料 —— 页面逻辑：数据加载、交互处理、页面跳转 */
const api = require('../../utils/api')
const { defaultAvatar } = require('../../utils/avatar')

const GENDERS = ['未知', '男', '女', '保密']
const GRADES = ['未选择', '2018', '2019', '2020', '2021', '2022', '2023', '2024', '2025', '2026']
const CAMPUSES = ['未选择', '大学城', '东风路', '龙洞', '番禺']
const TIMES = ['未选择', '工作日白天', '工作日晚上', '周末', '时间灵活']
const TAG_OPTIONS = ['PPT', '摄影', '编程', '考研', '篮球', '音乐', '游戏', '旅行', '阅读', '健身', '乐器', '外语']
const LOOKING_OPTIONS = ['二手闲置', '跑腿代办', '技能互助', '物品租赁', '失物招领', '校园拼单']

const PRIVACY_DEFS = [
  { key: 'gender', label: '公开性别' },
  { key: 'age', label: '公开年龄' },
  { key: 'major', label: '公开专业' },
  { key: 'grade', label: '公开年级' },
  { key: 'college', label: '公开学院' },
  { key: 'campus', label: '公开校区' },
  { key: 'tags', label: '公开爱好标签' },
  { key: 'availableTime', label: '公开可互助时间' },
  { key: 'lookingFor', label: '公开想找类型' }
]

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function todayStr() {
  const d = new Date()
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

function calcAge(birthday) {
  if (!birthday) return ''
  const b = new Date(birthday.replace(/-/g, '/'))
  if (isNaN(b.getTime())) return ''
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const m = now.getMonth() - b.getMonth()
  if (m < 0 || (m === 0 && now.getDate() < b.getDate())) age--
  return age > 0 && age < 120 ? age : ''
}

Page({
  data: {
    safeTop: 20,
    appbarH: 64,
    capsuleRight: 96,
    today: todayStr(),
    avatar: '',
    initial: '·',
    nickname: '',
    intro: '',
    genderIndex: 0,
    genderOptions: GENDERS,
    birthday: '',
    age: '',
    major: '',
    gradeIndex: 0,
    gradeOptions: GRADES,
    college: '',
    campusIndex: 0,
    campusOptions: CAMPUSES,
    timeIndex: 0,
    timeOptions: TIMES,
    tagList: [],
    lookingList: [],
    privacyFields: PRIVACY_DEFS.map((x) => ({ ...x, value: false })),
    uploading: false,
    saving: false
  },

  // 页面加载：初始化数据、拉取首屏内容
  onLoad() {
    const app = getApp()
    this.setData({
      safeTop: app.globalData.safeTop,
      appbarH: app.globalData.safeTop + 44,
      capsuleRight: app.globalData.capsuleRight
    })
    this.load()
  },

  // 加载数据：
  load() {
    api
      .profile()
      .then((u) => {
        const tags = u.tags || []
        const looking = u.lookingFor || []
        const extraTags = tags.filter((t) => TAG_OPTIONS.indexOf(t) < 0)
        const extraLooking = looking.filter((t) => LOOKING_OPTIONS.indexOf(t) < 0)

        const gradeOptions = GRADES.slice()
        if (u.grade && gradeOptions.indexOf(String(u.grade)) < 0) gradeOptions.push(String(u.grade))

        const campusOptions = CAMPUSES.slice()
        if (u.campus && campusOptions.indexOf(u.campus) < 0) campusOptions.push(u.campus)

        const privacy = u.privacy || {}

        this.setData({
          avatar: u.avatar || this.data.avatar || '',
          defaultAvatar: defaultAvatar(u.id),
          initial: u.initial || (u.nickname ? u.nickname.charAt(0) : '·'),
          nickname: u.nickname || '',
          intro: u.intro || '',
          genderIndex: u.gender == null ? 0 : Math.max(0, Math.min(3, u.gender)),
          birthday: u.birthday || '',
          age: u.age || calcAge(u.birthday || ''),
          major: u.major || '',
          gradeIndex: u.grade ? Math.max(0, gradeOptions.indexOf(String(u.grade))) : 0,
          gradeOptions,
          college: u.college || '',
          campusIndex: u.campus ? Math.max(0, campusOptions.indexOf(u.campus)) : 0,
          campusOptions,
          timeIndex: u.availableTime ? Math.max(0, TIMES.indexOf(u.availableTime)) : 0,
          tagList: TAG_OPTIONS.concat(extraTags).map((name) => ({ name, on: tags.indexOf(name) >= 0 })),
          lookingList: LOOKING_OPTIONS.concat(extraLooking).map((name) => ({
            name,
            on: looking.indexOf(name) >= 0
          })),
          privacyFields: PRIVACY_DEFS.map((x) => ({ ...x, value: !!privacy[x.key] }))
        })
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.msg) || '资料加载失败', icon: 'none' })
      })
  },

  // 事件处理：ChooseAvatar
  onChooseAvatar(e) {
    const path = e.detail && e.detail.avatarUrl
    if (!path) return
    if (this.data.uploading) return
    this.setData({ uploading: true })
    api
      .uploadImage(path, 'avatar')
      .then((vo) => {
        const url = (vo && vo.url) || ''
        if (!url) throw { msg: '上传返回的地址为空' }
        this.setData({ avatar: url, uploading: false })
        // ★ 立刻落库：不能让用户「上传了但没点保存」导致头像丢失，
        //   也避免随后 load() 用旧的空值回填覆盖（历史上头像存不进去就是这个原因）
        return api.updateProfile({ avatar: url }).then(() => {
          wx.showToast({ title: '头像已更新', icon: 'success' })
        })
      })
      .catch((err) => {
        this.setData({ uploading: false })
        wx.showToast({ title: (err && err.msg) || '头像上传失败', icon: 'none' })
      })
  },

  // 事件处理：Nickname
  onNickname(e) {
    this.setData({ nickname: e.detail.value })
  },

  // 事件处理：Intro
  onIntro(e) {
    this.setData({ intro: e.detail.value })
  },

  // 事件处理：Gender
  onGender(e) {
    this.setData({ genderIndex: Number(e.detail.value) })
  },

  // 事件处理：Birthday
  onBirthday(e) {
    const birthday = e.detail.value
    this.setData({ birthday, age: calcAge(birthday) })
  },

  // 事件处理：Major
  onMajor(e) {
    this.setData({ major: e.detail.value })
  },

  // 事件处理：Grade
  onGrade(e) {
    this.setData({ gradeIndex: Number(e.detail.value) })
  },

  // 事件处理：College
  onCollege(e) {
    this.setData({ college: e.detail.value })
  },

  // 事件处理：Campus
  onCampus(e) {
    this.setData({ campusIndex: Number(e.detail.value) })
  },

  // 事件处理：Time
  onTime(e) {
    this.setData({ timeIndex: Number(e.detail.value) })
  },

  // 事件处理：Tag
  onTag(e) {
    const i = Number(e.currentTarget.dataset.i)
    const list = this.data.tagList.slice()
    const onCount = list.filter((x) => x.on).length
    if (!list[i].on && onCount >= 6) {
      wx.showToast({ title: '最多选 6 个标签', icon: 'none' })
      return
    }
    list[i].on = !list[i].on
    this.setData({ tagList: list })
  },

  // 事件处理：Looking
  onLooking(e) {
    const i = Number(e.currentTarget.dataset.i)
    const list = this.data.lookingList.slice()
    const onCount = list.filter((x) => x.on).length
    if (!list[i].on && onCount >= 6) {
      wx.showToast({ title: '最多选 6 个', icon: 'none' })
      return
    }
    list[i].on = !list[i].on
    this.setData({ lookingList: list })
  },

  // 事件处理：PrivacyTap
  onPrivacyTap(e) {
    const i = Number(e.currentTarget.dataset.i)
    this.setData({ ['privacyFields[' + i + '].value']: !this.data.privacyFields[i].value })
  },

  // 事件处理：Save
  onSave() {
    if (this.data.saving) return
    const nickname = (this.data.nickname || '').trim()
    if (!nickname) {
      wx.showToast({ title: '昵称不能为空', icon: 'none' })
      return
    }

    const tags = this.data.tagList.filter((x) => x.on).map((x) => x.name)
    const lookingFor = this.data.lookingList.filter((x) => x.on).map((x) => x.name)
    const privacy = {}
    this.data.privacyFields.forEach((f) => {
      privacy[f.key] = !!f.value
    })

    const grade =
      this.data.gradeIndex > 0 ? Number(this.data.gradeOptions[this.data.gradeIndex]) : 0
    const campus =
      this.data.campusIndex > 0 ? this.data.campusOptions[this.data.campusIndex] : ''
    const availableTime =
      this.data.timeIndex > 0 ? this.data.timeOptions[this.data.timeIndex] : ''

    this.setData({ saving: true })
    api
      .updateProfile({
        nickname,
        avatar: this.data.avatar || '',
        intro: (this.data.intro || '').trim(),
        gender: Number(this.data.genderIndex),
        birthday: this.data.birthday || '',
        major: (this.data.major || '').trim(),
        grade,
        college: (this.data.college || '').trim(),
        campus,
        tags,
        availableTime,
        lookingFor,
        privacy
      })
      .then(() => {
        this.setData({ saving: false })
        wx.showToast({ title: '已保存', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 600)
      })
      .catch((err) => {
        this.setData({ saving: false })
        wx.showToast({ title: (err && err.msg) || '保存失败', icon: 'none' })
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
