import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { quasar, transformAssetUrls } from '@quasar/vite-plugin'

// `npm run dev` serves the console on :9000 and proxies the API to a local cms-core.
// Override the target with CMS_API=http://host:port npm run dev
export default defineConfig({
  plugins: [
    vue({ template: { transformAssetUrls } }),
    quasar({ sassVariables: fileURLToPath(new URL('./src/quasar-variables.sass', import.meta.url)) })
  ],
  server: {
    port: 9000,
    proxy: { '/api': process.env.CMS_API || 'http://localhost:8080' }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  }
})
