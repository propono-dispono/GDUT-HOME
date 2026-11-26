<template>
  <div class="app-container">
    <el-alert type="info" :closable="false" show-icon class="mb"
      title="聊天监管为只读视图"
      description="聊天后端尚未开发，此处用于举报核实：查看会话双方与消息记录。" />

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">
            会话列表
            <el-tag v-if="!live" size="small" type="info" effect="plain" class="tag-src">演示数据</el-tag>
            <el-tag v-else size="small" type="success" effect="plain" class="tag-src">实时数据</el-tag>
          </span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading" highlight-current-row @current-change="pick">
        <el-table-column prop="id" label="会话" width="80" />
        <el-table-column label="参与人" width="160">
          <template #default="scope">{{ scope.row.userA }} ↔ {{ scope.row.userB }}</template>
        </el-table-column>
        <el-table-column prop="lastMsg" label="最后一条消息" min-width="240" show-overflow-tooltip />
        <el-table-column prop="refType" label="关联" width="100">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ scope.row.refType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastAt" label="更新时间" width="160" />
      </el-table>
      <div class="tip">点击任意一行，下方显示该会话的消息记录。</div>
    </el-card>

    <el-card shadow="never" class="mt" v-if="current">
      <template #header>
        <span class="card-title">消息记录 · 会话 {{ current.id }}（{{ current.userA }} ↔ {{ current.userB }}）</span>
      </template>
      <div class="msgs" v-loading="msgLoading">
        <div class="msg" v-for="m in messages" :key="m.id">
          <span class="who">#{{ m.fromUser }}</span>
          <span class="text">{{ m.msgType === 2 ? '[图片] ' + m.content : m.content }}</span>
          <span class="time">{{ m.createdAt }}</span>
        </div>
        <div v-if="!messages.length" class="muted">暂无消息</div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { xjGet } from '@/utils/xj'

const DEMO_SESSIONS = [
  { id: 1, userA: 1, userB: 3, lastMsg: '好的，我十分钟后到', refType: 'POST', lastAt: '2026-10-18 15:40' },
  { id: 2, userA: 1, userB: 4, lastMsg: '拼单还差一个人，你拉个室友？', refType: 'POST', lastAt: '2026-10-18 14:02' },
]
const DEMO_MSGS: Record<number, any[]> = {
  1: [
    { id: 1, fromUser: 1, msgType: 1, content: '同学你好，快递还在菜鸟驿站吗？', createdAt: '15:20' },
    { id: 2, fromUser: 3, msgType: 1, content: '在的，我下午三点去取可以吗', createdAt: '15:22' },
    { id: 3, fromUser: 3, msgType: 1, content: '好的，我十分钟后到', createdAt: '15:40' },
  ],
  2: [
    { id: 4, fromUser: 4, msgType: 1, content: '脐橙拼单还差人吗', createdAt: '13:50' },
    { id: 5, fromUser: 1, msgType: 1, content: '拼单还差一个人，你拉个室友？', createdAt: '14:02' },
  ],
}

const loading = ref(false)
const live = ref(false)
const list = ref<any[]>(DEMO_SESSIONS)
const current = ref<any>(null)
const messages = ref<any[]>([])
const msgLoading = ref(false)

async function load() {
  loading.value = true
  try {
    const page: any = await xjGet('/admin/chat/list', { page: 1, size: 50 })
    list.value = page.list || []
    live.value = true
  } catch (e) {
    live.value = false
    list.value = DEMO_SESSIONS
  } finally {
    loading.value = false
  }
}

async function pick(row: any) {
  if (!row) return
  current.value = row
  messages.value = []
  msgLoading.value = true
  try {
    if (!live.value) {
      messages.value = DEMO_MSGS[row.id] || []
    } else {
      const page: any = await xjGet(`/admin/chat/${row.id}/messages`, { page: 1, size: 100 })
      messages.value = page.list || []
    }
  } catch (e: any) {
    ElMessage.error(e.message || '消息加载失败')
  } finally {
    msgLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.app-container { padding: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 15px; font-weight: 600; }
.tag-src { margin-left: 8px; font-weight: 400; }
.tip { margin-top: 12px; font-size: 12px; color: #909399; }
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.msgs { display: flex; flex-direction: column; gap: 10px; }
.msg { display: flex; gap: 10px; align-items: baseline; font-size: 13px; }
.who { flex: 0 0 60px; color: #6b7280; }
.text { flex: 1; }
.time { flex: 0 0 60px; color: #9ca3af; font-size: 12px; text-align: right; }
.muted { color: #a2a7ad; font-size: 13px; }
</style>
