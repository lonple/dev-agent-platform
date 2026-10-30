<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listPermissions } from '../../api/permission'
import type { SysPermission } from '../../types/api'

const loading = ref(false)
const permissions = ref<SysPermission[]>([])

async function loadData() {
  loading.value = true
  try {
    permissions.value = await listPermissions()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载权限失败')
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div>
    <h1 class="page-title">权限管理</h1>
    <div class="page-card">
      <el-table v-loading="loading" :data="permissions" row-key="id">
        <el-table-column prop="code" label="权限码" min-width="160" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="module" label="模块" min-width="120" />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>
