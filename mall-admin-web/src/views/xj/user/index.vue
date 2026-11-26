<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">用户管理</span>
          <div class="filters">
            <el-input v-model="keyword" size="small" placeholder="搜索昵称" clearable style="width: 180px" @keyup.enter="onFilter" />
            <el-select v-model="statusFilter" size="small" style="width: 120px" @change="onFilter">
              <el-option label="全部状态" value="" />
              <el-option label="正常" value="1" />
              <el-option label="禁言" value="2" />
              <el-option label="封禁" value="3" />
            </el-select>
            <el-button size="small" type="primary" @click="onFilter">搜索</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column prop="openidMasked" label="openid" width="180">
          <template #default="scope">
            <span class="muted">{{ scope.row.openidMasked }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="verifyStatusText" label="认证" width="100">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.verifyStatus === 2 ? 'success' : 'info'" effect="plain">
              {{ scope.row.verifyStatusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="creditScore" label="信用分" width="90" />
        <el-table-column prop="statusText" label="账号状态" width="100">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.status === 1 ? 'success' : 'danger'">
              {{ scope.row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="postCount" label="发布" width="80" />
        <el-table-column prop="createdAt" label="注册时间" width="170" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="scope">
            <el-button size="small" @click="adjustCredit(scope.row)">信用分</el-button>
            <el-button v-if="scope.row.status === 1" size="small" type="danger" plain @click="ban(scope.row)">封禁</el-button>
            <el-button v-else size="small" type="success" plain @click="unban(scope.row)">解封</el-button>
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

interface UserRow {
  id: number
  nickname: string
  openidMasked: string
  creditScore: number
  verifyStatus: number
  verifyStatusText: string
  status: number
  statusText: string
  postCount: number
  createdAt: string
}

const loading = ref(false)
const list = ref<UserRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const statusFilter = ref('')

async function load() {
  loading.value = true
  try {
    const data: any = await xjGet('/admin/user/list', {
      keyword: keyword.value || undefined,
      status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
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

async function adjustCredit(row: UserRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      `当前信用分 ${row.creditScore}，输入调整值（正数加分、负数扣分）`,
      '调整信用分',
      {
        inputPlaceholder: '例如：5 或 -3',
        inputValidator: (v: string) => (/^-?\d+$/.test(v) ? true : '请输入整数'),
      }
    )
    await xjPost(`/admin/user/${row.id}/credit`, {
      delta: Number(value),
      reason: '管理员手动调整',
    })
    ElMessage.success('已调整')
    load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '操作失败')
  }
}

async function ban(row: UserRow) {
  try {
    const { value } = await ElMessageBox.prompt('请填写封禁原因', '封禁用户', {
      inputPlaceholder: '例如：发布违规内容',
      inputValidator: (v: string) => (!!v && v.trim().length > 0 ? true : '请填写原因'),
    })
    await xjPost(`/admin/user/${row.id}/status`, { status: 3, reason: value })
    ElMessage.success('已封禁')
    load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '操作失败')
  }
}

async function unban(row: UserRow) {
  try {
    await ElMessageBox.confirm(`确认解封「${row.nickname}」？`, '解封用户', { type: 'warning' })
    await xjPost(`/admin/user/${row.id}/status`, { status: 1, reason: '管理员解禁' })
    ElMessage.success('已解封')
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
