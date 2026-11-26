import { defineStore } from 'pinia'
import { adminLoginAPI, adminLogoutAPI } from '@/apis/admin'
import { ref } from 'vue'
import type { LoginParam, UserInfo } from '@/types/admin'

export const useUserStore = defineStore(
  'user',
  () => {
    // 用户信息
    const userInfo = ref<UserInfo>({
      username: '',
      avatar: '',
      roles: [],
      token: '',
      menus: [],
    })

    // 用户登录
    const userLogin = async (loginParam: LoginParam) => {
      const res = await adminLoginAPI(loginParam)
      const tokenStr = res.data.token
      userInfo.value.token = tokenStr
      userInfo.value.username = loginParam.username
      // ⛔ 不要把密码存进 store / localStorage
      await getUserInfo()
    }

    // 获取用户信息
    // ⚠️ 校集后端没有 /admin/info 接口，这里不再发请求，否则登录后会一直转圈。
    // 角色由登录响应给出（校集目前只有超级管理员一种角色）。
    const getUserInfo = async () => {
      if (!userInfo.value.roles || userInfo.value.roles.length === 0) {
        userInfo.value.roles = ['SUPER']
      }
    }

    // 用户登出
    const userLogout = async () => {
      try {
        await adminLogoutAPI()
      } catch (e) {
        // 服务端登出失败也要清本地，避免卡在"退不出去"
      }
      fedLogout()
    }

    // 前端登出：清掉所有敏感状态
    const fedLogout = () => {
      userInfo.value.token = ''
      userInfo.value.roles = []
      userInfo.value.menus = []
    }

    return {
      userInfo,
      userLogin,
      getUserInfo,
      userLogout,
      fedLogout,
    }
  },
  {
    // 持久化配置
    persist: true,
  },
)
