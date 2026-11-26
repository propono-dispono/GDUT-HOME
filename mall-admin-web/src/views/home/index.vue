<template>
  <div class="dashboard">
    <el-row :gutter="12">
      <el-col :span="6" v-for="item in stats" :key="item.label">
        <el-card shadow="hover" class="stat">
          <div class="stat-label">{{ item.label }}</div>
          <div class="stat-num">{{ item.value }}</div>
          <div class="stat-sub">{{ item.sub }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="row2">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>
            <div class="head">
              <span class="head-title">待处理事项</span>
              <el-tag type="danger" effect="plain" size="small">共 {{ pendingTotal }} 项</el-tag>
            </div>
          </template>
          <div class="todo">
            <div class="todo-item">
              <div>
                <div class="todo-title">校园认证待审核</div>
                <div class="todo-sub">新用户认证通过后才能发布内容</div>
              </div>
              <div class="todo-right">
                <b>{{ overview.pendingVerify }}</b>
                <el-button size="small" type="primary" @click="go('/xj/verify')">去处理</el-button>
              </div>
            </div>
            <div class="todo-item">
              <div>
                <div class="todo-title">内容待审核</div>
                <div class="todo-sub">待审内容不会出现在信息流</div>
              </div>
              <div class="todo-right">
                <b>{{ overview.pendingPost }}</b>
                <el-button size="small" type="primary" @click="go('/xj/post')">去处理</el-button>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><div class="head"><span class="head-title">内容与用户概况</span></div></template>
          <div class="kv">
            <div class="kv-item"><span>展示中内容</span><b>{{ overview.onlinePost }}</b></div>
            <div class="kv-item"><span>内容总数</span><b>{{ overview.totalPost }}</b></div>
            <div class="kv-item"><span>今日新增内容</span><b>{{ overview.todayPost }}</b></div>
            <div class="kv-item"><span>注册用户</span><b>{{ overview.totalUser }}</b></div>
            <div class="kv-item"><span>已认证用户</span><b>{{ overview.verifiedUser }}</b></div>
            <div class="kv-item"><span>封禁用户</span><b>{{ overview.bannedUser }}</b></div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { xjGet } from '@/utils/xj'

interface Overview {
  pendingVerify: number
  pendingPost: number
  onlinePost: number
  totalPost: number
  todayPost: number
  totalUser: number
  verifiedUser: number
  bannedUser: number
}

const router = useRouter()
const overview = ref<Overview>({
  pendingVerify: 0,
  pendingPost: 0,
  onlinePost: 0,
  totalPost: 0,
  todayPost: 0,
  totalUser: 0,
  verifiedUser: 0,
  bannedUser: 0,
})

const stats = computed(() => [
  { label: '待认证', value: overview.value.pendingVerify, sub: '需要审核' },
  { label: '待审内容', value: overview.value.pendingPost, sub: '未上架' },
  { label: '展示中', value: overview.value.onlinePost, sub: '信息流可见' },
  { label: '注册用户', value: overview.value.totalUser, sub: `已认证 ${overview.value.verifiedUser}` },
])

const pendingTotal = computed(() => overview.value.pendingVerify + overview.value.pendingPost)

function go(path: string) {
  router.push(path)
}

async function load() {
  try {
    const data: any = await xjGet('/admin/dashboard/overview')
    overview.value = { ...overview.value, ...data }
  } catch (e: any) {
    ElMessage.error(e?.message || '概览加载失败')
  }
}

onMounted(load)
</script>

<style scoped>
.dashboard { padding: 16px; }
.stat { border-radius: 8px; }
.stat-label { color: #909399; font-size: 13px; }
.stat-num { font-size: 28px; font-weight: 700; margin: 6px 0 2px; }
.stat-sub { color: #909399; font-size: 12px; }
.row2 { margin-top: 12px; }
.head { display: flex; align-items: center; justify-content: space-between; }
.head-title { font-size: 15px; font-weight: 600; }
.todo-item { display: flex; align-items: center; justify-content: space-between; padding: 14px 0; border-bottom: 1px solid #ebeef5; }
.todo-item:last-child { border-bottom: none; }
.todo-title { font-size: 14px; font-weight: 500; }
.todo-sub { color: #909399; font-size: 12px; margin-top: 4px; }
.todo-right { display: flex; align-items: center; gap: 12px; }
.todo-right b { font-size: 20px; }
.kv { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.kv-item { display: flex; justify-content: space-between; padding: 10px 12px; background: #f7f8fa; border-radius: 6px; }
.kv-item b { font-size: 16px; }
</style>
