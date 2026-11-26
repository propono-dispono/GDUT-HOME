import axios from 'axios'
import { useUserStore } from '@/stores/user'

/**
 * 校集后台专用请求实例。
 *
 * ⛔ 这里不允许再出现任何"静默登录 / 演示模式 / 写死账号密码"。
 *    没有 token 就由路由守卫送回登录页；token 失效则清掉并跳登录页。
 */
const BASE = import.meta.env.VITE_BASE_SERVER_URL

const inst = axios.create({ baseURL: BASE, timeout: 8000 })

inst.interceptors.request.use(cfg => {
  const store = useUserStore()
  const token = store.userInfo.token
  if (token) {
    cfg.headers.Authorization = 'Bearer ' + token
  }
  return cfg
})

/** token 失效：清本地登录态并回登录页 */
function toLogin() {
  const store = useUserStore()
  store.fedLogout()
  if (!window.location.hash.startsWith('#/login')) {
    window.location.hash = '#/login'
  }
}

inst.interceptors.response.use(
  res => {
    const body = res.data
    // 校集后端的成功码是 0
    if (body && body.code === 0) {
      return body.data
    }
    if (body && (body.code === 401 || body.code === 1002 || body.code === 1003)) {
      toLogin()
    }
    return Promise.reject(new Error((body && body.msg) || '请求失败'))
  },
  err => {
    if (err && err.response && err.response.status === 401) {
      toLogin()
    }
    return Promise.reject(new Error((err && err.message) || '网络异常'))
  }
)

/* ==================== 对外方法 ==================== */

export const xjGet = async <T = any>(url: string, params?: Record<string, any>): Promise<T> => {
  return inst.get(url, { params })
}

export const xjPost = async <T = any>(
  url: string,
  body?: Record<string, any>,
  params?: Record<string, any>
): Promise<T> => {
  return inst.post(url, body ?? {}, { params })
}

export const xjDelete = async <T = any>(url: string, params?: Record<string, any>): Promise<T> => {
  return inst.delete(url, { params })
}
