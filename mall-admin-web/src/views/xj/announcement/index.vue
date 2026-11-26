<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">
            公告管理
            <el-tag v-if="!live" size="small" type="info" effect="plain" class="tag-src">演示数据</el-tag>
            <el-tag v-else size="small" type="success" effect="plain" class="tag-src">实时数据</el-tag>
          </span>
          <div class="head-right">
            <el-button size="small" @click="load">刷新</el-button>
            <el-button type="primary" size="small" @click="openEdit(null)">新建公告</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="100">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ scope.row.type === 'POPUP' ? '弹窗' : '站内' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.status === 1 ? 'success' : 'info'" effect="plain">
              {{ scope.row.status === 1 ? '已上线' : '已下线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="160" />
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button size="small" @click="openEdit(scope.row)">编辑</el-button>
            <el-button size="small" :type="scope.row.status === 1 ? 'warning' : 'success'" plain @click="toggle(scope.row)">
              {{ scope.row.status === 1 ? '下线' : '上线' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="tip">公告会显示在小程序首页顶部；「弹窗」类型会在用户首次进入时弹出。</div>
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑公告' : '新建公告'" width="540px">
      <el-form :model="form" label-width="64px">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="60" show-word-limit placeholder="不超过 60 字" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="5" placeholder="公告正文" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type">
            <el-radio label="POPUP">弹窗</el-radio>
            <el-radio label="INNER">站内</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { xjGet, xjPost } from '@/utils/xj'

interface Row { id: number; title: string; content: string; type: string; status: number; updatedAt?: string }

const DEMO: Row[] = [
  { id: 1, title: '校集正式上线公告', content: '欢迎使用校集，校园互助从这里开始。', type: 'POPUP', status: 1, updatedAt: '2026-10-17 10:00' },
  { id: 2, title: '严禁站外交易提醒', content: '请勿在内容中留下微信、QQ 等联系方式。', type: 'INNER', status: 1, updatedAt: '2026-10-18 09:12' },
]

const loading = ref(false)
const live = ref(false)
const visible = ref(false)
const list = ref<Row[]>(DEMO)
const form = ref<Partial<Row>>({})

async function load() {
  loading.value = true
  try {
    const page: any = await xjGet('/admin/announcement/list', { page: 1, size: 50 })
    list.value = (page.list || []) as Row[]
    live.value = true
  } catch (e) {
    live.value = false
    list.value = DEMO
  } finally {
    loading.value = false
  }
}

function openEdit(row: Row | null) {
  form.value = row ? { ...row } : { title: '', content: '', type: 'POPUP' }
  visible.value = true
}

async function save() {
  if (!form.value.title || !form.value.content) {
    ElMessage.warning('标题和内容都要填')
    return
  }
  if (!live.value) {
    ElMessage.warning('当前是演示数据，未连后端，操作不会保存')
    visible.value = false
    return
  }
  try {
    await xjPost('/admin/announcement/save', {
      id: form.value.id,
      title: form.value.title,
      content: form.value.content,
      type: form.value.type,
    })
    visible.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '保存失败')
  }
}

async function toggle(row: Row) {
  if (!live.value) {
    ElMessage.warning('当前是演示数据，未连后端')
    return
  }
  try {
    await xjPost(`/admin/announcement/${row.id}/status`, {}, { status: row.status === 1 ? 0 : 1 })
    ElMessage.success(row.status === 1 ? '已下线' : '已上线')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.app-container { padding: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 15px; font-weight: 600; }
.head-right { display: flex; gap: 8px; }
.tag-src { margin-left: 8px; font-weight: 400; }
.tip { margin-top: 12px; font-size: 12px; color: #909399; }
</style>
