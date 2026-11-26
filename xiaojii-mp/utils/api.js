/**
 * 校集 · 接口层
 *
 * 说明：
 *  1. 后端统一返回 {code, msg, data}，code === 0 表示成功。
 *  2. 小程序端没有 cookie，登录态靠 JWT，存 storage 后每个请求带 Authorization。
 *  3. token 失效（401 / 1002 过期 / 1003 无效）自动清掉重登一次，避免用户莫名被踢。
 *
 * 登录与环境：
 *  - 开发者工具（platform=devtools）→ 本地 127.0.0.1:8080 + dev-login，免配置微信密钥。
 *  - 手机（预览 / 体验 / 正式）→ 生产 HTTPS 域名 + wx.login + 后端 jscode2session。
 *  ⚠️ 不能只看 envVersion：手机扫「预览」码时 envVersion 仍是 develop，
 *     只看它会让真机去连 127.0.0.1，表现为“连不上后端”。
 *  ⚠️ 正式版必须在微信后台把 https://xj.sxz-blog.xyz 配进合法域名。
 *  ⚠️ 生产环境必须设置 WX_DEV_LOGIN=false，否则 dev-login 会成为越权入口。
 */

/** 本地联调地址（仅 develop 使用） */
const DEV_BASE = 'http://127.0.0.1:8080/api'
/** 生产地址（trial / release 使用，必须 HTTPS） */
const PROD_BASE = 'https://xj.sxz-blog.xyz/api'

/** 读当前运行环境：develop / trial / release；取不到时按 develop 处理 */
function detectEnv() {
  try {
    const info = wx.getAccountInfoSync()
    return (info && info.miniProgram && info.miniProgram.envVersion) || 'develop'
  } catch (e) {
    return 'develop'
  }
}

/**
 * 是否运行在开发者工具里。
 * 关键：手机扫「预览」码时 envVersion 仍是 develop，不能只看 envVersion，
 * 否则真机会去连 127.0.0.1，必然提示“连不上后端”。
 */
function detectDevtools() {
  try {
    const info = wx.getDeviceInfo ? wx.getDeviceInfo() : wx.getSystemInfoSync()
    return info && info.platform === 'devtools'
  } catch (e) {
    return false
  }
}

const ENV = detectEnv()
const IS_DEVTOOLS = detectDevtools()
/** 只有开发者工具才走本地 + dev-login；手机（预览/体验/正式）一律走线上 */
const IS_DEV = IS_DEVTOOLS
const BASE = IS_DEVTOOLS ? DEV_BASE : PROD_BASE

/** 本地联调用的 openid；只在 develop 下使用 */
const DEV_OPENID = 'dev_lin'

let token = ''
try {
  token = wx.getStorageSync('token') || ''
} catch (e) {
  token = ''
}

let loginPromise = null

function getToken() {
  return token
}

function setToken(t) {
  token = t || ''
  try {
    wx.setStorageSync('token', token)
  } catch (e) {
    /* ignore */
  }
}

function clearToken() {
  setToken('')
}

/** 开发环境登录：直接用固定 openid 换 token（仅 develop 使用） */
function devLogin() {
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE + '/app/auth/dev-login',
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: { openid: DEV_OPENID },
      success(res) {
        const body = res.data || {}
        if (body.code === 0 && body.data && body.data.token) {
          resolve(body.data.token)
        } else {
          reject({ code: body.code, msg: body.msg || '登录失败' })
        }
      },
      fail() {
        reject({ code: -1, msg: '连不上后端：请确认 8080 已启动、开发者工具已勾选不校验域名' })
      }
    })
  })
}

/** 正式登录：wx.login 拿 code → 后端换 openid + token（trial / release 使用） */
function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      timeout: 10000,
      success(res) {
        if (!res.code) {
          reject({ code: -1, msg: '微信登录失败：未拿到 code' })
          return
        }
        wx.request({
          url: BASE + '/app/auth/login',
          method: 'POST',
          header: { 'Content-Type': 'application/json' },
          data: { code: res.code },
          success(r) {
            const body = r.data || {}
            if (body.code === 0 && body.data && body.data.token) {
              resolve(body.data.token)
            } else {
              reject({ code: body.code, msg: body.msg || '登录失败' })
            }
          },
          fail() {
            reject({ code: -1, msg: '连不上服务器，请检查网络或稍后重试' })
          }
        })
      },
      fail() {
        reject({ code: -1, msg: '微信登录失败，请重试' })
      }
    })
  })
}

/** 登录：没有 token 时按环境自动选择登录方式 */
function ensureToken() {
  if (token) return Promise.resolve(token)
  if (loginPromise) return loginPromise

  loginPromise = (IS_DEV ? devLogin() : wxLogin())
    .then((t) => {
      setToken(t)
      return t
    })
    .catch((err) => {
      throw err
    })
    .finally(() => {
      loginPromise = null
    })

  return loginPromise
}

function isAuthError(code) {
  return code === 401 || code === 1002 || code === 1003
}

function rawRequest(path, opt, hasRetried) {
  return ensureToken().then(
    () =>
      new Promise((resolve, reject) => {
        wx.request({
          url: BASE + path,
          method: opt.method || 'GET',
          data: opt.data || {},
          header: Object.assign(
            { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
            opt.header || {}
          ),
          timeout: opt.timeout || 15000,
          success(res) {
            const body = res.data || {}
            if (body.code === 0) {
              resolve(body.data)
              return
            }
            if (isAuthError(body.code) && !hasRetried) {
              clearToken()
              rawRequest(path, opt, true).then(resolve, reject)
              return
            }
            reject({ code: body.code, msg: body.msg || '请求失败' })
          },
          fail(err) {
            reject({
              code: -1,
              msg: '网络异常：' + (err && err.errMsg ? err.errMsg : '请检查后端是否启动')
            })
          }
        })
      })
  )
}

function request(path, opt) {
  return rawRequest(path, opt || {}, false)
}

/* ---------------- 业务接口 ---------------- */

/** 信息流：sort 支持 latest / priceAsc / priceDesc */
function feed(params) {
  return request('/app/post/feed', { data: params })
}

/** 帖子详情 */
function postDetail(id) {
  return request('/app/post/' + id)
}

/** 分类列表 */
function categories(bizType) {
  return request('/app/post/categories', { data: bizType ? { bizType } : {} })
}

/** 我的资料 */
function profile() {
  return request('/app/user/profile')
}

/** 我的发布 */
function myPosts(params) {
  return request('/app/post/mine', { data: params })
}

/** 发布帖子（需已认证且信用 ≥60） */
function createPost(payload) {
  return request('/app/post', { method: 'POST', data: payload })
}

/* ---------------- 站内通知 ---------------- */

/** 我的通知列表 */
function noticeList(params) {
  return request('/app/notice/list', { data: params })
}

/** 未读条数（底栏红点用） */
function unreadCount() {
  return request('/app/notice/unread-count').then((d) => (d && d.count) || 0)
}

/** 标记单条已读 */
function readNotice(id) {
  return request('/app/notice/' + id + '/read', { method: 'POST' })
}

/* ---------------- 私信 ---------------- */

/** 打开（或创建）与某人的会话 */
function openChat(toUserId, refType, refId) {
  let q = '?toUserId=' + toUserId
  if (refType) q += '&refType=' + refType
  if (refId) q += '&refId=' + refId
  return request('/app/chat/session' + q, { method: 'POST' })
}

/** 我的会话列表 */
function chatSessions() {
  return request('/app/chat/sessions')
}

/** 会话消息 */
function chatMessages(sessionId, params) {
  return request('/app/chat/' + sessionId + '/messages', { data: params })
}

/** 发送消息 */
function sendChat(sessionId, content) {
  return request('/app/chat/' + sessionId + '/messages', { method: 'POST', data: { content } })
}

/* ---------------- 举报 ---------------- */

/** 提交举报 */
function submitReport(payload) {
  return request('/app/report', { method: 'POST', data: payload })
}

/* ---------------- 收藏 ---------------- */

/** 收藏（幂等） */
function favAdd(postId) {
  return request('/app/favorite/' + postId, { method: 'POST' })
}

/** 取消收藏（幂等） */
function favRemove(postId) {
  return request('/app/favorite/' + postId, { method: 'DELETE' })
}

/** 是否已收藏 */
function favStatus(postId) {
  return request('/app/favorite/' + postId + '/status').then((d) => !!(d && d.favored))
}

/** 我的收藏 */
function favList(params) {
  return request('/app/favorite/list', { data: params })
}

/* ---------------- 拼单 / 他人主页 ---------------- */

/** 参加拼单（幂等） */
function joinGroupbuy(postId) {
  return request('/app/post/' + postId + '/join', { method: 'POST' })
}

/** 退出拼单 */
function leaveGroupbuy(postId) {
  return request('/app/post/' + postId + '/leave', { method: 'POST' })
}

/** 某个用户公开的帖子 */
function userPosts(userId, params) {
  return request('/app/post/user/' + userId, { data: params })
}

/** 他人主页资料 */
function userDetail(userId) {
  return request('/app/user/' + userId)
}

/* ---------------- 账号 ---------------- */

/** 修改资料（昵称/头像/简介，传了才更新） */
function updateProfile(data) {
  return request('/app/user/profile', { method: 'PUT', data })
}

/** 退出登录（服务端令牌立即失效） */
function logout() {
  return request('/app/auth/logout', { method: 'POST' })
}

/** 当前公告（未登录可访问） */
function currentAnnouncement() {
  return request('/app/announcement/current')
}

/** 全部标记已读 */
function readAllNotices() {
  return request('/app/notice/read-all', { method: 'POST' })
}

/**
 * 上传图片：wx.uploadFile 不能走 request 封装，这里单独处理
 * 返回 { key, url }：key 入库用，url 用于预览
 */
function uploadImage(filePath, scene) {
  return ensureToken().then(
    () =>
      new Promise((resolve, reject) => {
        wx.uploadFile({
          url: BASE + '/app/upload?scene=' + (scene || 'post'),
          filePath,
          name: 'file',
          header: { Authorization: 'Bearer ' + token },
          timeout: 30000,
          success(res) {
            let body = null
            try {
              body = JSON.parse(res.data)
            } catch (e) {
              reject({ code: -1, msg: '上传返回格式异常' })
              return
            }
            if (body && body.code === 0) {
              resolve(body.data)
            } else {
              reject({ code: body && body.code, msg: (body && body.msg) || '上传失败' })
            }
          },
          fail(err) {
            reject({ code: -1, msg: '上传失败：' + (err && err.errMsg ? err.errMsg : '网络异常') })
          }
        })
      })
  )
}

/* ---------------- 教务 ----------------
 * 说明：密码由后端加密后提交给教务系统，小程序端不做任何本地持久化。
 * 勾选「记住账号」时，由后端用 AES-GCM 加密保存 30 天（90 天硬顶），可随时清除。
 */

/** 教务验证码：{ sessionId, imageBase64, expiresInSeconds } */
function eduCaptcha() {
  // 后端要串行取登录页 + 验证码图，给足超时
  return request('/app/edu/captcha', { timeout: 25000 })
}

/** 教务登录：payload = { sessionId, account, password, captcha, remember } */
function eduLogin(payload) {
  return request('/app/edu/login', { method: 'POST', data: payload, timeout: 25000 })
}

/** 退出教务（同时清除已保存的账号密码） */
function eduLogout() {
  return request('/app/edu/logout', { method: 'POST' })
}

/** 教务登录状态：{ loggedIn, saved, accountMasked, expiresAt } */
function eduStatus() {
  return request('/app/edu/status')
}

/** 当前学期（后端按校历推算，学生不用手填学年学期） */
function eduTerm() {
  return request('/app/edu/term')
}

/**
 * 课表：week<=0 表示当前周
 * refresh=true 跳过服务端缓存、强制请求教务（用户点刷新时传）
 */
function eduTimetable(week, all, refresh) {
  return request('/app/edu/timetable', {
    data: { week: week || 0, all: all ? true : false, refresh: refresh ? true : false },
    timeout: 25000
  })
}

/** 成绩 + GPA（按学期 / 学年 / 年级 / 全部） */
function eduScores() {
  return request('/app/edu/scores', { timeout: 25000 })
}

/** 考试安排 */
function eduExams() {
  return request('/app/edu/exams', { timeout: 25000 })
}

module.exports = {
  BASE,
  ENV,
  IS_DEV,
  IS_DEVTOOLS,
  ensureToken,
  getToken,
  clearToken,
  request,
  feed,
  postDetail,
  categories,
  profile,
  myPosts,
  createPost,
  uploadImage,
  noticeList,
  unreadCount,
  readNotice,
  readAllNotices,
  submitReport,
  favAdd,
  favRemove,
  favStatus,
  favList,
  joinGroupbuy,
  leaveGroupbuy,
  userPosts,
  userDetail,
  updateProfile,
  logout,
  currentAnnouncement,
  openChat,
  chatSessions,
  chatMessages,
  sendChat,
  eduCaptcha,
  eduLogin,
  eduLogout,
  eduStatus,
  eduTerm,
  eduTimetable,
  eduScores,
  eduExams
}
