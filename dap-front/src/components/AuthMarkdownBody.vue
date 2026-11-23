<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarkdownBody from './MarkdownBody.vue'
import { useAuthStore } from '../stores/auth'

const props = defineProps<{
  content: string
}>()

const auth = useAuthStore()
const wrapRef = ref<HTMLElement | null>(null)
const blobUrls: string[] = []

function revokeBlobs() {
  while (blobUrls.length) {
    const u = blobUrls.pop()
    if (u) URL.revokeObjectURL(u)
  }
}

async function hydrateAuthImages() {
  await nextTick()
  const root = wrapRef.value
  if (!root) return
  const imgs = Array.from(
    root.querySelectorAll<HTMLImageElement>('img[src^="/api/issues/attachments/"]'),
  )
  for (const img of imgs) {
    const src = img.getAttribute('src') || ''
    if (!src || img.dataset.authHydrated === '1') continue
    try {
      const headers: Record<string, string> = {}
      if (auth.token) headers.Authorization = `Bearer ${auth.token}`
      if (auth.currentTenantId != null) headers['X-Tenant-Id'] = String(auth.currentTenantId)
      const res = await fetch(src, { headers })
      if (!res.ok) continue
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      blobUrls.push(url)
      img.src = url
      img.dataset.authHydrated = '1'
    } catch {
      /* ignore single image failure */
    }
  }
}

watch(
  () => props.content,
  () => {
    revokeBlobs()
    void hydrateAuthImages()
  },
)

onMounted(() => {
  void hydrateAuthImages()
})

onBeforeUnmount(revokeBlobs)
</script>

<template>
  <div ref="wrapRef" class="auth-md">
    <MarkdownBody :content="content || ''" />
  </div>
</template>
