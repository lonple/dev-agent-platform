import request from '../utils/request'

export type ProductVersionStatus = 'PLANNING' | 'RELEASED' | 'INVALID'

export interface ProductVersionItem {
  id: number
  productId: number
  version: string
  status: ProductVersionStatus
  statusLabel?: string
  planReleaseAt?: string | null
  description?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface ProductCodeRepo {
  id?: number
  localPath: string
  label?: string | null
  sortNo?: number
}

export interface ProductItem {
  id: number
  code: string
  name: string
  description?: string
  managerUserId?: number | null
  managerUsername?: string | null
  wikiNodeId?: number | null
  wikiPath?: string | null
  codeRepos?: ProductCodeRepo[]
  versionCount?: number
  versions?: ProductVersionItem[]
}

export interface ProductManager {
  id: number
  username: string
  description?: string
}

export interface ProductSavePayload {
  code: string
  name: string
  description?: string
  managerUserId?: number | null
  wikiNodeId?: number | null
  wikiCreate?: { parentId?: number | null; displayName: string } | null
  codeRepos?: { localPath: string; label?: string }[]
}

export interface ProductVersionSavePayload {
  version: string
  status: ProductVersionStatus
  planReleaseAt?: string | null
  description?: string
}

export function listProducts() {
  return request.get<any, ProductItem[]>('/products')
}

export function listProductManagers() {
  return request.get<any, ProductManager[]>('/products/managers')
}

export function createProduct(data: ProductSavePayload) {
  return request.post<any, { id: number }>('/products', data)
}

export function updateProduct(id: number, data: ProductSavePayload) {
  return request.put<any, void>(`/products/${id}`, data)
}

export function deleteProduct(id: number) {
  return request.delete<any, void>(`/products/${id}`)
}

export function listProductVersions(productId: number) {
  return request.get<any, ProductVersionItem[]>(`/products/${productId}/versions`)
}

export function createProductVersion(productId: number, data: ProductVersionSavePayload) {
  return request.post<any, { id: number }>(`/products/${productId}/versions`, data)
}

export function updateProductVersion(
  productId: number,
  versionId: number,
  data: ProductVersionSavePayload,
) {
  return request.put<any, void>(`/products/${productId}/versions/${versionId}`, data)
}

export function deleteProductVersion(productId: number, versionId: number) {
  return request.delete<any, void>(`/products/${productId}/versions/${versionId}`)
}
