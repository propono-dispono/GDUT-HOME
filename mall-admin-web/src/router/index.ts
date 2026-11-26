import { createRouter, createWebHashHistory } from 'vue-router'
import Layout from '@/views/layout/Layout.vue'
import type { RouteRecordExt } from '@/types/router'

export const constantRouterMap: RouteRecordExt[] = [
  { path: '/404', component: () => import('@/views/normal/404/index.vue'), hidden: true },
  { path: '/login', component: () => import('@/views/normal/login/index.vue'), hidden: true },
  {
    path: '',
    component: Layout,
    redirect: '/home',
    meta: { title: '概览', icon: 'home' },
    children: [
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '数据分析', icon: 'dashboard' },
      },
      // {
      //   path: 'test',
      //   name: 'test',
      //   component: () => import('@/views/test/index.vue'),
      //   meta: { title: '组件测试', icon: 'product-cate' },
      // },
    ],
  },
]

export const asyncRouterMap: RouteRecordExt[] = [
  /* ========== 校集核心（优先展示） ========== */
  {
    path: '/xj',
    component: Layout,
    redirect: '/xj/verify',
    name: 'xj',
    meta: { title: '审核中心', icon: 'product' },
    children: [
      { path: 'verify', name: 'verify', component: () => import('@/views/xj/verify/index.vue'), meta: { title: '认证审核', icon: 'product-list' } },
      { path: 'post', name: 'post', component: () => import('@/views/xj/post/index.vue'), meta: { title: '内容审核', icon: 'order' } },
      { path: 'report', name: 'report', component: () => import('@/views/xj/report/index.vue'), meta: { title: '举报处理', icon: 'form' } },
      { path: 'user', name: 'user', component: () => import('@/views/xj/user/index.vue'), meta: { title: '用户管理', icon: 'user' } },
    ],
  },
  {
    path: '/monitor',
    component: Layout,
    redirect: '/monitor/chat',
    name: 'monitor',
    meta: { title: '业务监管', icon: 'eye' },
    children: [
      // B 方案已移除「订单监管」：小程序不再提供下单/订单能力
      { path: 'chat', name: 'chat', component: () => import('@/views/xj/chat/index.vue'), meta: { title: '聊天监管', icon: 'document' } },
    ],
  },
  {
    path: '/config',
    component: Layout,
    redirect: '/config/announcement',
    name: 'config',
    meta: { title: '运营配置', icon: 'tree' },
    children: [
      { path: 'announcement', name: 'announcement', component: () => import('@/views/xj/announcement/index.vue'), meta: { title: '公告管理', icon: 'document' } },
      { path: 'word', name: 'word', component: () => import('@/views/xj/word/index.vue'), meta: { title: '敏感词库', icon: 'tree' } },
    ],
  },
]

// createWebHistory（History 模式）地址格式（需要服务器配置）：http://domain.com/admin/home
// createWebHashHistory（Hash 模式）地址格式（会多带一个#号）：http://domain.com/admin/#/home
const router = createRouter({
  history: createWebHashHistory(),
  routes: constantRouterMap,
})

export default router
