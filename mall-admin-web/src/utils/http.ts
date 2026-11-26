import { useUserStore } from '@/stores/user'
import type { CommonResult } from '@/types/common'
import axios from 'axios'
import { ElMessage } from 'element-plus'

// 创建axios实例
const http = axios.create({
  baseURL: import.meta.env.VITE_BASE_SERVER_URL,
  timeout: 5000,
})

// axios请求拦截器
http.interceptors.request.use(
  config => {
    //从pinia获取token
    const userStore = useUserStore()
    const token = userStore.userInfo.token
    if (token) {
      config.headers.Authorization = 'Bearer ' + token
    }
    return config
  },
  e => Promise.reject(e),
)

// axios响应拦截器
http.interceptors.response.use(
  response => {
    const res: CommonResult<unknown> = response.data
    if (res.code !== 0) {
      ElMessage({
        message: (res as any).msg || res.message,
        type: 'error',
        duration: 3 * 1000,
      })
      // 401/1003：登录态失效，清掉本地状态并回登录页
      if (res.code === 401 || res.code === 1003) {
        const userStore = useUserStore()
        userStore.fedLogout()
        if (!location.hash.startsWith('#/login')) {
          location.hash = '#/login'
        }
      }
      return Promise.reject(new Error((res as any).msg || '请求失败'))
    }
    // 返回响应 JSON 中的 data 属性
    return response.data
  },
  error => {
    // ⛔ 不允许再"静默返回空数据"：那会把后端故障伪装成"暂无数据"
    const status = error && error.response && error.response.status
    const msg =
      (error && error.response && error.response.data && error.response.data.msg) ||
      (error && error.message) ||
      '网络异常'
    ElMessage.error(msg)
    if (status === 401) {
      const userStore = useUserStore()
      userStore.fedLogout()
      if (!location.hash.startsWith('#/login')) {
        location.hash = '#/login'
      }
    }
    return Promise.reject(error)
  },
)

export default http
