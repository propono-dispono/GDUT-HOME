<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">校园认证审核</span>
          <div class="filters">
            <el-radio-group v-model="status" size="small" @change="onFilter">
              <el-radio-button label="0">待审核</el-radio-button>
              <el-radio-button label="1">已通过</el-radio-button>
              <el-radio-button label="2">已驳回</el-radio-button>
              <el-radio-button label="">全部</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="nickname" label="用户" width="120" />
        <el-table-column prop="studentNo" label="学号" width="140" />
        <el-table-column prop="realName" label="姓名" width="100" />
        <el-table-column label="校园卡" width="110">
          <template #default="scope">
            <el-button link type="primary" :disabled="!scope.row.cardImage" @click="preview(scope.row)">
              {{ scope.row.cardImage ? '查看照片' : '未上传' }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="submittedAt" label="提交时间" width="170" />
        <el-table-column prop="statusText" label="状态" width="100">
          <template #default="scope">
            <el-tag size="small" :type="tagType(scope.row.status)">{{ scope.row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rejectReason" label="驳回原因" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="scope">
            <el-button size="small" type="success" :disabled="scope.row.status !== 0" @click="approve(scope.row)">通过</el-button>
            <el-button size="small" type="danger" plain :disabled="scope.row.status !== 0" @click="reject(scope.row)">驳回</el-button>
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

    <el-dialog v-model="dialogVisible" title="校园卡照片" width="520px">
      <el-image v-if="current.cardImage" :src="current.cardImage" fit="contain" style="width:100%;max-height:420px" />
      <p class="img-hint">学号 {{ current.studentNo }}　姓名 {{ current.realName }}</p>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { xjGet, xjPost } from '@/utils/xj'

interface VerifyRow {
  id: number
  nickname: string
  studentNo: string
  realName: string
  cardImage?: string
  submittedAt: string
  status: number
  statusText: string
  rejectReason?: string
}

const loading = ref(false)
const list = ref<VerifyRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const status = ref('0')
const dialogVisible = ref(false)
const current = ref<Partial<VerifyRow>>({})

function tagType(s: number) {
  if (s === 1) return 'success'
  if (s === 2) return 'danger'
  return 'warning'
}

async function load() {
  loading.value = true
  try {
    const data: any = await xjGet('/admin/verify/list', {
      status: status.value === '' ? undefined : Number(status.value),
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

function preview(row: VerifyRow) {
  current.value = row
  dialogVisible.value = true
}

async function approve(row: VerifyRow) {
  try {
    await ElMessageBox.confirm(`确认通过「${row.realName}」的校园认证？`, '审核确认', { type: 'warning' })
    await xjPost(`/admin/verify/${row.id}/approve`)
    ElMessage.success('已通过')
    load()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '操作失败')
  }
}

async function reject(row: VerifyRow) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因，会通过站内信发给用户', '驳回认证', {
      inputPlaceholder: '例如：校园卡照片模糊，请重新拍摄',
      inputValidator: (v: string) => (!!v && v.trim().length > 0 ? true : '请填写原因'),
    })
    await xjPost(`/admin/verify/${row.id}/reject`, { reason: value })
    ElMessage.success('已驳回')
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
.pager { margin-top: 16px; justify-content: flex-end; }
.img-hint { margin-top: 12px; color: #606266; font-size: 13px; }
</style>
