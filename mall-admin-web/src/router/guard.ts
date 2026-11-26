import router from '@/router/index'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { useUserStore } from '@/stores/user'
import usePermissionStore from '@/stores/permission'

/**
 * 路由守卫（真正的登录校验）。
 *
 * ⛔ 不要再加"静态演示模式"：没有 token 就必须回登录页。
 *    之前的 demo 逻辑会让任何人直接打开后台，是严重安全问题。
 */
router.beforeEach((to, _from, next) => {
  NProgress.start()
  const userStore = useUserStore()
  const permissionStore = usePermissionStore()
  const hasToken = !!userStore.userInfo.token

  // 登录页：已登录就别再进登录页，没登录才放行
  if (to.path === '/login') {
    if (hasToken) {
      next({ path: '/' })
    } else {
      next()
    }
    return
  }

  // 未登录：一律回登录页，并记住原地址
  if (!hasToken) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 已登录但动态路由还没挂：生成后重新进入目标页
  if (permissionStore.addRouters.length === 0) {
    permissionStore.generateRoutes({
      menus: userStore.userInfo.menus || [],
      username: userStore.userInfo.username || 'admin',
      roles: userStore.userInfo.roles || [],
    })
    permissionStore.addRouters.forEach(route => {
      router.addRoute(route)
    })
    next({ ...to, replace: true })
    return
  }

  next()
})

router.afterEach(() => {
  NProgress.done()
})
