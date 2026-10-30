import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult } from '../types/api'
import { useAuthStore } from '../stores/auth'

const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  if (auth.currentTenantId != null) {
    config.headers['X-Tenant-Id'] = String(auth.currentTenantId)
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return response.data
  },
  async (error) => {
    const status = error.response?.status
    if (status === 401) {
      const auth = useAuthStore()
      auth.clearAuth()
      const { default: router } = await import('../router')
      const redirect = router.currentRoute.value.fullPath
      await router.push({ path: '/login', query: { redirect } })
    }
    const message =
      error.response?.data?.message || error.message || '网络错误'
    return Promise.reject(new Error(message))
  },
)

export default request
