import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Content security policy of the production build (KAN-22, R1). Everything the dashboard loads comes from its own
// address: its script, its stylesheet, its fonts, its icon and the /api calls. Nothing else may load, no inline
// script or style runs, no other site may frame the page and forms cannot be sent anywhere else.
// The development server (npm run dev) does not use it, because Vite injects inline scripts there.
export const CONTENT_SECURITY_POLICY = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "font-src 'self'",
  "img-src 'self'",
  "connect-src 'self'",
  "base-uri 'none'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ')

// Sent with every page of the production build (npm run build, then npm run preview).
// To serve dist/ with another web server, send the same headers there.
export const SECURITY_HEADERS = {
  'Content-Security-Policy': CONTENT_SECURITY_POLICY,
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Opener-Policy': 'same-origin',
}

// The browser calls /api on the dashboard's own address and Vite forwards it to the backend.
// The browser therefore never makes a cross-origin request, so the backend needs no CORS setting.
// QL_API_TARGET lets the end-to-end test (e2e/run.sh) use a backend on another port.
const apiProxy = {
  '/api': { target: process.env.QL_API_TARGET || 'http://127.0.0.1:8080', changeOrigin: true },
}

export default defineConfig({
  plugins: [react()],
  build: {
    // Keep every file a file: an asset inlined as a data: URL would be blocked by the policy above.
    assetsInlineLimit: 0,
  },
  server: {
    // Only this computer can open the dashboard, like the backend.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: apiProxy,
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy: apiProxy,
    headers: SECURITY_HEADERS,
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test-setup.js',
  },
})
