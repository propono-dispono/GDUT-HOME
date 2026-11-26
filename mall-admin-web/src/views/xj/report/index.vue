<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">
            举报处理
            <el-tag v-if="!live" size="small" type="info" effect="plain" class="tag-src">演示数据</el-tag>
            <el-tag v-else size="small" type="success" effect="plain" class="tag-src">实时数据</el-tag>
          </span>
          <div class="head-right">
            <el-radio-group v-model="filter" size="small" @change="load">
              <el-radio-button label="0">待处理</el-radio-button>
              <el-radio-button label="1">成立</el-radio-button>
              <el-radio-button label="2">不成立</el-radio-button>
              <el-radio-button label="ALL">全部</el-radio-button>
            </el-radio-group>
            <el-button size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="targetTypeText" label="对象" width="90">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ scope.row.targetTypeText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetTitle" label="被举报内容" min-width="180" show-overflow-tooltip>
          <template #default="scope">
            <span v-if="scope.row.targetTitle">{{ scope.row.targetTitle }}</span>
            <span v-else class="muted">#{{ scope.row.targetId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="reasonType" label="原因" width="110" />
        <el-table-column prop="description" label="补充说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="reporter" label="举报人" width="110" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="150" />
        <el-table-column prop="statusText" label="状态" width="100">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.status === 0 ? 'warning' : scope.row.status === 1 ? 'danger' : 'info'">
              {{ scope.row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button size="small" type="danger" plain :disabled="scope.row.status !== 0" @click="handle(scope.row, true)">确认违规</el-button>
            <el-button size="small" :disabled="scope.row.status !== 0" @click="handle(scope.row, false)">不成立</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="tip">
        举报成立且对象是内容时，系统会自动下架该内容并通知作者与举报人。
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { xjGet, xjPost } from '@/utils/xj'

interface ReportRow {
  id: number
  targetType: string
  targetTypeText: string
  targetId: number
  targetTitle?: string
  reporter?: string
  reasonType: string
  description: string
  status: number
  statusText: string
  createdAt: string
}

const DEMO: ReportRow[] = [
  { id: 1, targetType: 'POST', targetTypeText: '内容', targetId: 15, targetTitle: '代取快递：菜鸟驿站 → 教学1号楼', reporter: '林同学', reasonType: '广告/引流', description: '描述里留了微信，引导站外交易', status: 0, statusText: '待处理', createdAt: '2026-10-18 15:45' },
  { id: 2, targetType: 'USER', targetTypeText: '用户', targetId: 4, targetTitle: '', reporter: '张同学', reasonType: '诈骗/欺诈', description: '疑似冒充他人身份发布信息', status: 0, statusText: '待处理', createdAt: '2026-10-18 14:10' },
]

const loading = ref(false)
const live = ref(false)
const filter = ref('0')
const list = ref<ReportRow[]>(DEMO)

async function load() {
  loading.value = true
  try {
    const status = filter.value === 'ALL' ? undefined : Number(filter.value)
    const page: any = await xjGet('/admin/report/list', { status, page: 1, size: 50 })
    list.value = (page.list || []) as ReportRow[]
    live.value = true
  } catch (e) {
    // 后端没起 / 未登录：退回演示数据，页面仍可验收
    live.value = false
    const status = filter.value === 'ALL' ? null : Number(filter.value)
    list.value = status === null ? DEMO : DEMO.filter(r => r.status === status)
  } finally {
    loading.value = false
  }
}

async function handle(row: ReportRow, guilty: boolean) {
  if (!live.value) {
    ElMessage.warning('当前是演示数据，未连后端，操作不会保存')
    row.status = guilty ? 1 : 2
    row.statusText = guilty ? '已处理' : '不成立'
    return
  }
  try {
    const { value } = await ElMessageBox.prompt(
      guilty ? '确认违规，请填写处置说明（会通知双方）' : '请填写不成立的理由',
      guilty ? '确认违规' : '举报不成立',
      { inputValidator: (v: string) => (!!v && v.trim() ? true : '请填写说明') }
    )
    await xjPost(`/admin/report/${row.id}/handle`, {
      result: guilty ? 'CONFIRMED' : 'REJECTED',
      remark: value,
    })
    ElMessage.success(guilty ? '已处理，相关内容已下架' : '已标记为不成立')
    await load()
  } catch (e: any) {
    if (e !== 'cancel' && e?.message && e.message !== 'cancel') {
      ElMessage.error(e.message || '操作失败')
    }
  }
}

onMounted(load)
</script>

<style scoped>
.app-container { padding: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 15px; font-weight: 600; }
.head-right { display: flex; gap: 8px; align-items: center; }
.tag-src { margin-left: 8px; font-weight: 400; }
.tip { margin-top: 12px; font-size: 12px; color: #909399; }
.muted { color: #a2a7ad; }
</style>
