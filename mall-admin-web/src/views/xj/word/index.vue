<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">
            敏感词库
            <el-tag v-if="!live" size="small" type="info" effect="plain" class="tag-src">演示数据</el-tag>
            <el-tag v-else size="small" type="success" effect="plain" class="tag-src">实时数据</el-tag>
          </span>
          <div class="search">
            <el-input v-model="kw" placeholder="搜索词条" clearable style="width: 180px" @keyup.enter="load" />
            <el-button @click="load">搜索</el-button>
            <el-button type="primary" @click="add">新增词条</el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="word" label="词条" width="180" />
        <el-table-column label="等级" width="130">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.level === 1 ? 'danger' : 'warning'" effect="plain">
              {{ scope.row.level === 1 ? '直接拦截' : '警告' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.enabled === 1 ? 'success' : 'info'" effect="plain">
              {{ scope.row.enabled === 1 ? '启用中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="scope">
            <el-button size="small" @click="toggle(scope.row)">
              {{ scope.row.enabled === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" plain @click="remove(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="tip">
        等级 1 = 命中即拦截发布；等级 2 = 仅警告。组合词用 <code>+</code> 连接（如 <code>代取+外卖</code> 表示同时命中才拦）。
        <br />注：该表没有自增主键，唯一键是词条本身，因此所有操作按词条进行。
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { xjDelete, xjGet, xjPost } from '@/utils/xj'

interface Row { word: string; level: number; enabled: number }

const DEMO: Row[] = [
  { word: '代写', level: 1, enabled: 1 },
  { word: '代考', level: 1, enabled: 1 },
  { word: '刷单', level: 1, enabled: 1 },
  { word: '代取+外卖', level: 2, enabled: 1 },
]

const loading = ref(false)
const live = ref(false)
const kw = ref('')
const list = ref<Row[]>(DEMO)

async function load() {
  loading.value = true
  try {
    const data: any = await xjGet('/admin/word/list', { keyword: kw.value.trim() || undefined })
    list.value = (data || []) as Row[]
    live.value = true
  } catch (e) {
    live.value = false
    const k = kw.value.trim()
    list.value = k ? DEMO.filter(w => w.word.includes(k)) : DEMO
  } finally {
    loading.value = false
  }
}

async function add() {
  if (!live.value) {
    ElMessage.warning('当前是演示数据，未连后端，新增不会保存')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请输入要新增的敏感词', '新增词条', {
      inputValidator: (v: string) => (!!v && v.trim() ? true : '词条不能为空'),
    })
    await xjPost('/admin/word', {}, { word: value.trim(), level: 1 })
    ElMessage.success('已新增')
    await load()
  } catch (e: any) {
    if (e?.message && e.message !== 'cancel') {
      ElMessage.error(e.message || '新增失败')
    }
  }
}

async function toggle(row: Row) {
  if (!live.value) {
    ElMessage.warning('当前是演示数据，未连后端')
    return
  }
  try {
    await xjPost('/admin/word/toggle', {}, { word: row.word, enabled: row.enabled === 1 ? 0 : 1 })
    ElMessage.success(row.enabled === 1 ? '已停用' : '已启用')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function remove(row: Row) {
  if (!live.value) {
    ElMessage.warning('当前是演示数据，未连后端')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除词条「${row.word}」？`, '删除确认', { type: 'warning' })
    await xjDelete('/admin/word', { word: row.word })
    ElMessage.success('已删除')
    await load()
  } catch (e: any) {
    if (e?.message && e.message !== 'cancel') {
      ElMessage.error(e.message || '删除失败')
    }
  }
}

onMounted(load)
</script>

<style scoped>
.app-container { padding: 16px; }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { font-size: 15px; font-weight: 600; }
.search { display: flex; gap: 8px; }
.tag-src { margin-left: 8px; font-weight: 400; }
.tip { margin-top: 12px; font-size: 12px; color: #909399; line-height: 1.8; }
.tip code { background: #f5f5f6; padding: 1px 5px; border-radius: 4px; }
</style>
