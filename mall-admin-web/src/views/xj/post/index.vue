<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">内容审核</span>
          <div class="filters">
            <el-select v-model="status" size="small" style="width: 130px" @change="onFilter">
              <el-option label="待审核" value="0" />
              <el-option label="展示中" value="1" />
              <el-option label="已下架" value="2" />
              <el-option label="已关闭" value="4" />
              <el-option label="全部" value="" />
            </el-select>
            <el-select v-model="biz" size="small" style="width: 130px" @change="onFilter">
              <el-option label="全部业务线" value="" />
              <el-option label="二手闲置" value="SECONDHAND" />
              <el-option label="跑腿代办" value="ERRAND" />
              <el-option label="技能互助" value="SKILL" />
              <el-option label="物品租赁" value="RENTAL" />
              <el-option label="失物招领" value="LOST" />
              <el-option label="校园拼单" value="GROUPBUY" />
            </el-select>
            <el-input v-model="keyword" size="small" placeholder="标题关键词" clearable style="width: 180px" @keyup.enter="onFilter" />
            <el-button size="small" type="primary" @click="onFilter">搜索</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="业务线" width="100">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ bizName(scope.row.biz) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="金额" width="110">
          <template #default="scope">
            <span v-if="scope.row.price">¥{{ scope.row.price }}</span>
            <span v-else class="muted">未填</span>
          </template>
        </el-table-column>
        <el-table-column prop="user" label="作者" width="80" />
        <el-table-column prop="createdAt" label="提交时间" width="170" />
        <el-table-column prop="statusText" label="状态" width="100">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.status === 0 ? 'warning' : scope.row.status === 1 ? 'success' : 'info'">
              {{ scope.row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditStatusText" label="审核" width="100" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="scope">
            <el-button size="small" type="success" :disabled="scope.row.status !== 0" @click="approve(scope.row)">通过</el-button>
            <el-button size="small" type="danger" plain :disabled="scope.row.status !== 0" @click="reject(scope.row)">驳回</el-button>
            <el-button size="small" :disabled="scope.row.status !== 1" @click="offline(scope.row)">下架</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPage"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { xjGet, xjPost } from '@/utils/xj'

interface PostRow {
  id: number
  biz: string
  title: string
  price?: string
  user: string
  createdAt: string
  status: number
  statusText: string
  auditStatusText?: string
}

const BIZ: Record<string, string> = {
  SECONDHAND: '二手闲置',
  ERRAND: '跑腿代办',
  SKILL: '技能互助',
  RENTAL: '物品租赁',
  LOST: '失物招领',
  GROUPBUY: '校园拼单',
}

const loading = ref(false)
const list = ref<PostRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const status = ref('0')
const biz = ref('')
const keyword = ref('')

function bizName(k: string) {
  return BIZ[k] || k
}

async function load() {
  loading.value = true
  try {
    const data: any = await xjGet('/admin/post/list', {
      status: status.value === '' ? undefined : Number(status.value),
      bizType: biz.value || undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: size.value,
    })
    list.value = data.list || []
    total.value = data.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function onFilter() {
  page.value = 1
  load()
}

function onPage(p: number) {
  page.value = p
  load()
}

async function approve(row: PostRow) {
  try {
    await ElMessageBox.confirm(`确认通过《${row.title}》？通过后将在首页展示。`, '内容审核', { type: 'warning' })
    await xjPost(`/admin/post/${row.id}/approve`)
    ElMessage.success('已通过')
    load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '操作失败')
  }
}

async function reject(row: PostRow) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因，会通知作者', '驳回内容', {
      inputPlaceholder: '例如：含未核实的信息',
      inputValidator: (v: string) => (!!v && v.trim().length > 0 ? true : '请填写原因'),
    })
    await xjPost(`/admin/post/${row.id}/reject`, { reason: value })
    ElMessage.success('已驳回')
    load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '操作失败')
  }
}

async function offline(row: PostRow) {
  try {
    const { value } = await ElMessageBox.prompt('请填写下架原因', '强制下架', {
      inputPlaceholder: '例如：涉嫌违规内容',
      inputValidator: (v: string) => (!!v && v.trim().length > 0 ? true : '请填写原因'),
    })
    await xjPost(`/admin/post/${row.id}/offline`, { reason: value })
    ElMessage.success('已下架')
    load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.app-container { padding: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 15px; font-weight: 600; }
.filters { display: flex; gap: 8px; align-items: center; }
.pager { margin-top: 16px; justify-content: flex-end; }
.muted { color: #909399; }
</style>
