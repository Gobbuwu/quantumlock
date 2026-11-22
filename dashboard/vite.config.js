import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    // Only this computer can open the dashboard, like the backend.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // The browser calls /api on the dashboard's own address and Vite forwards it to the backend.
    // The browser therefore never makes a cross-origin request, so the backend needs no CORS setting.
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test-setup.js',
  },
})
