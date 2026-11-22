// Browser test of the dashboard against a real backend and database. Started by e2e/run.sh at the repository
// root, which provides E2E_BASE_URL and E2E_API_KEY. Not part of "npm test".
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: '*.e2e.js',
  // One browser, one test at a time: the database check after the run expects exactly the keys made here.
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL,
    // Traces, videos and screenshots could capture the API key, so none are recorded.
    trace: 'off',
    video: 'off',
    screenshot: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
