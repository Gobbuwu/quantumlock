// The dashboard on a fresh setup (KAN-24): enter the API key, generate one key pair of each algorithm and see
// them in the list. Also: a wrong key is refused, the key is forgotten on reload unless the user opts in, and
// "Forget API key" removes it. e2e/run.sh then checks the database rows.
// The page is the production build under its content security policy (KAN-22, R1): any CSP violation fails a test.
import { expect, test } from '@playwright/test'

const apiKey = process.env.E2E_API_KEY
const WRONG_KEY = 'ql_test_' + 'A'.repeat(43) // well formed, but never issued

test.describe.configure({ mode: 'serial' })

test.beforeAll(() => {
  if (!apiKey) throw new Error('E2E_API_KEY is not set. Start this test with e2e/run.sh.')
})

// Collects every CSP violation the browser reports, across reloads, and fails the test if there was one.
let cspViolations = []
test.beforeEach(async ({ page }) => {
  cspViolations = []
  await page.exposeBinding('__reportCspViolation', (_source, violation) => cspViolations.push(violation))
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) =>
      window.__reportCspViolation(`${e.effectiveDirective} ${e.blockedURI}`),
    )
  })
})
test.afterEach(() => {
  expect(cspViolations, 'content security policy violations').toEqual([])
})

async function enterKey(page, key, { remember = false } = {}) {
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
  await page.getByLabel('API key', { exact: true }).fill(key)
  if (remember) await page.getByLabel('Remember until this browser tab is closed').check()
  await page.getByRole('button', { name: 'Use this key' }).click()
}

const rows = (page) => page.locator('table.keys tbody tr').filter({ has: page.locator('code.key-id') })

test('the page is served with a strict content security policy that blocks inline script', async ({ page }) => {
  const response = await page.goto('/')
  const policy = response.headers()['content-security-policy']
  expect(policy).toContain("default-src 'none'")
  expect(policy).toContain("script-src 'self'")
  expect(policy).toContain("frame-ancestors 'none'")
  expect(policy).not.toContain('unsafe-inline')
  expect(policy).not.toContain('unsafe-eval')
  expect(response.headers()['x-frame-options']).toBe('DENY')

  // An injected inline script must not run, and the browser must report it.
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
  await page.evaluate(() => {
    const script = document.createElement('script')
    script.textContent = 'window.__injected = true'
    document.head.appendChild(script)
  })
  await expect.poll(() => cspViolations.length).toBeGreaterThan(0)
  expect(await page.evaluate(() => window.__injected)).toBeUndefined()
  expect(cspViolations[0]).toMatch(/^script-src-elem inline/)
  cspViolations = [] // expected here; any other violation still fails
})

test('a key that was never issued is refused', async ({ page }) => {
  await page.goto('/')
  await enterKey(page, WRONG_KEY)
  await expect(page.getByRole('alert')).toContainText('The API did not accept this API key')
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
})

test('generate one key pair of each algorithm and see both in the list', async ({ page }) => {
  await page.goto('/')
  await enterKey(page, apiKey)
  await expect(page.getByText('No key pairs yet.')).toBeVisible()

  for (const algorithm of ['ML-KEM-1024', 'ML-DSA-65']) {
    await page.getByRole('radio', { name: new RegExp(algorithm) }).check()
    await page.getByRole('button', { name: 'Generate key pair' }).click()
    await expect(page.getByRole('status').filter({ hasText: `Generated ${algorithm} key pair` })).toBeVisible()
  }

  await expect(rows(page)).toHaveCount(2)
  await expect(rows(page).nth(0)).toContainText('ML-DSA-65') // newest first
  await expect(rows(page).nth(1)).toContainText('ML-KEM-1024')
  await expect(rows(page).nth(0)).toContainText('Active')

  // The key list comes from the API, not the page's memory: a fresh load shows the same two rows.
  await page.reload()
  await enterKey(page, apiKey)
  await expect(rows(page)).toHaveCount(2)
})

test('the key is kept only in memory unless the user opts in', async ({ page }) => {
  await page.goto('/')
  await enterKey(page, apiKey)
  await expect(page.getByRole('heading', { name: 'Your key pairs' })).toBeVisible()
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
  expect(page.url()).not.toContain(apiKey)

  await page.reload()
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
})

test('"Remember" keeps the key for the tab, and "Forget API key" removes it', async ({ page }) => {
  await page.goto('/')
  await enterKey(page, apiKey, { remember: true })
  await expect(page.getByRole('heading', { name: 'Your key pairs' })).toBeVisible()
  expect(await page.evaluate(() => localStorage.length)).toBe(0)

  await page.reload()
  await expect(page.getByRole('heading', { name: 'Your key pairs' })).toBeVisible()
  await expect(rows(page)).toHaveCount(2)

  await page.getByRole('button', { name: 'Forget API key' }).click()
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.length)).toBe(0)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
})
