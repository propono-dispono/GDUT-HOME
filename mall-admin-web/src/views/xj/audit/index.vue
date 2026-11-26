<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span class="card-title">操作日志</span>
          <span class="head-sub">记录管理员每一次审核与处置，用于事后追溯</span>
        </div>
      </template>

      <el-table :data="list" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="targetText" label="对象" width="120">
          <template #default="scope">
            <el-tag size="small" effect="plain">{{ scope.row.targetText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetId" label="对象ID" width="90" />
        <el-table-column prop="resultText" label="结果" width="110">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.result === 'PASS' ? 'success' : scope.row.result === 'REJECT' ? 'danger' : 'warning'">
              {{ scope.row.resultText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" min-width="280" show-overflow-tooltip />
        <el-table-column prop="admin" label="操作人" width="110" />
        <el-table-column prop="createdAt" label="时间" width="150" />
      </el-table>
      <div class="tip">数据来源：audit_record 表。演示数据。</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const list = ref([
  { id: 8, targetText: '内容', targetId: 15, result: 'REJECT', resultText: '驳回', remark: '含未核实的价格信息', admin: '校集管理员', createdAt: '2026-10-18 15:52' },
  { id: 7, targetText: '内容', targetId: 15, result: 'PASS', resultText: '通过', remark: '内容审核通过', admin: '校集管理员', createdAt: '2026-10-18 15:51' },
  { id: 6, targetText: '认证', targetId: 4, result: 'REJECT', resultText: '驳回', remark: '校园卡照片模糊，请重新拍摄', admin: '校集管理员', createdAt: '2026-10-18 15:48' },
  { id: 5, targetText: '认证', targetId: 3, result: 'PASS', resultText: '通过', remark: '校园认证通过', admin: '校集管理员', createdAt: '2026-10-18 15:47' },
  { id: 4, targetText: '用户', targetId: 3, result: 'ADJUST', resultText: '调整', remark: '信用分 80 → 85（+5）', admin: '校集管理员', createdAt: '2026-10-18 15:45' },
])
</script>

<style scoped>
.app-container { padding: 16px; }
.card-head { display: flex; align-items: baseline; gap: 12px; }
.card-title { font-size: 15px; font-weight: 600; }
.head-sub { font-size: 12px; color: #9ca3af; }
.tip { margin-top: 12px; font-size: 12px; color: #909399; }
</style>
