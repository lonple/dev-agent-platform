import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 8201,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8200',
        changeOrigin: true,
        // SSE 长连接：避免代理超时/缓冲截断流式响应
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req, res) => {
            const ct = String(proxyRes.headers['content-type'] || '')
            const url = req.url || ''
            if (ct.includes('text/event-stream') || url.includes('/chat/completions')) {
              // 强制分块、禁压缩，避免 Vite/http-proxy 把 SSE 攒成整包再吐
              proxyRes.headers['cache-control'] = 'no-cache, no-transform'
              proxyRes.headers['x-accel-buffering'] = 'no'
              proxyRes.headers['connection'] = 'keep-alive'
              delete proxyRes.headers['content-encoding']
              delete proxyRes.headers['content-length']
              try {
                res.setHeader('Cache-Control', 'no-cache, no-transform')
                res.setHeader('X-Accel-Buffering', 'no')
                res.setHeader('Connection', 'keep-alive')
              } catch {
                /* headers may already be sent */
              }
            }
          })
        },
      },
    },
  },
})
