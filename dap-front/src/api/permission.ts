import request from '../utils/request'
import type { SysPermission } from '../types/api'

export function listPermissions() {
  return request.get<unknown, SysPermission[]>('/permissions')
}
