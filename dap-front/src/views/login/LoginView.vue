<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { login } from '../../api/auth'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function onSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await login({
      username: form.username.trim(),
      password: form.password,
    })
    auth.setLoginResult(result)
    ElMessage.success('登录成功')
    const redirect = (route.query.redirect as string) || '/home'
    router.replace(redirect)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="login-title">DAP 平台</h1>
      <p class="login-subtitle">用户中心登录</p>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="72px"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="onSubmit">
            登录
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--dap-gray-50);
  padding: var(--dap-space-5);
}

.login-card {
  width: 100%;
  max-width: 420px;
  padding: var(--dap-space-6);
  background: var(--dap-gray-0);
  border: 1px solid var(--dap-gray-200);
  border-radius: var(--dap-radius-md);
}

.login-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--dap-gray-900);
}

.login-subtitle {
  margin: var(--dap-space-2) 0 var(--dap-space-5);
  color: var(--dap-gray-500);
}

/* 覆盖浏览器自动填充浅蓝底 */
.login-card :deep(.el-input__wrapper) {
  background-color: var(--dap-gray-0) !important;
  box-shadow: 0 0 0 1px var(--dap-gray-200) inset !important;
}
.login-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--dap-primary) inset !important;
}
.login-card :deep(input.el-input__inner),
.login-card :deep(input.el-input__inner:-webkit-autofill),
.login-card :deep(input.el-input__inner:-webkit-autofill:hover),
.login-card :deep(input.el-input__inner:-webkit-autofill:focus),
.login-card :deep(input.el-input__inner:-webkit-autofill:active) {
  -webkit-text-fill-color: var(--dap-gray-900) !important;
  caret-color: var(--dap-gray-900);
  transition: background-color 99999s ease-in-out 0s;
  box-shadow: 0 0 0 1000px var(--dap-gray-0) inset !important;
  background-color: transparent !important;
}
</style>
