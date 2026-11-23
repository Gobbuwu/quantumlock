// The dashboard on a fresh setup (KAN-24): enter the API key, generate one key pair of each algorithm and see
// them in the list. Also: a wrong key is refused, the key is forgotten on reload unless the user opts in, and
// "Forget API key" removes it. e2e/run.sh then checks the database rows.
// The page is the production build under its content security policy (KAN-22, R1): any CSP violation fails a test.
import AxeBuilder from '@axe-core/playwright'
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

test('sign, verify, rotate and revoke an ML-DSA-65 key pair, then read its audit log (KAN-33)', async ({ page }) => {
  await page.goto('/')
  await enterKey(page, apiKey)
  await expect(rows(page)).toHaveCount(2)
  await page.getByRole('radio', { name: /ML-DSA-65/ }).check()
  await page.getByRole('button', { name: 'Generate key pair' }).click()
  await expect(rows(page)).toHaveCount(3)
  const shortId = (await rows(page).nth(0).locator('code.key-id').textContent()).trim()
  await page.getByRole('button', { name: `Show details of ${shortId}` }).click()
  const actions = page.getByRole('group', { name: `Actions for ${shortId}` })

  // Sign, then verify the signature through the real API: the same message is valid, a changed one is not.
  await actions.getByRole('button', { name: 'Sign' }).click()
  const signForm = page.getByRole('form', { name: 'Sign a message' })
  await signForm.getByLabel('Message').fill('Invoice 2026-118: 42.00 €')
  await signForm.getByLabel('Context (optional)').fill('invoices')
  await signForm.getByRole('button', { name: 'Sign message' }).click()
  const signature = await signForm.getByRole('textbox', { name: 'Signature' }).inputValue()
  expect(Buffer.from(signature, 'base64')).toHaveLength(3309)

  await actions.getByRole('button', { name: 'Verify' }).click()
  const verifyForm = page.getByRole('form', { name: 'Verify a signature' })
  await verifyForm.getByLabel('Message').fill('Invoice 2026-118: 42.00 €')
  await verifyForm.getByLabel('Context (optional)').fill('invoices')
  await verifyForm.getByLabel('Signature').fill(signature)
  await verifyForm.getByRole('button', { name: 'Verify signature' }).click()
  await expect(verifyForm.getByText(/^Valid\./)).toBeVisible()
  await verifyForm.getByLabel('Message').fill('Invoice 2026-118: 4200.00 €')
  await verifyForm.getByRole('button', { name: 'Verify signature' }).click()
  await expect(verifyForm.getByText(/^Not valid\./)).toBeVisible()

  // Rotate with a 1-day grace period: a new active key pair on top, the old one rotated and still able to verify.
  await actions.getByRole('button', { name: 'Rotate' }).click()
  await page.getByLabel('Grace period in days').fill('1')
  await page.getByRole('button', { name: 'Rotate key pair' }).click()
  await expect(rows(page)).toHaveCount(4)
  await expect(rows(page).nth(0)).toContainText('Active')
  await expect(rows(page).filter({ hasText: shortId })).toContainText('Rotated')
  await expect(actions.getByRole('button', { name: 'Verify' })).toBeVisible()
  await expect(actions.getByRole('button', { name: 'Sign' })).toHaveCount(0)

  // Revoke the old one, after the second confirmation.
  await actions.getByRole('button', { name: 'Revoke' }).click()
  await page.getByRole('button', { name: 'Revoke for good' }).click()
  await expect(rows(page).filter({ hasText: shortId })).toContainText('Revoked')

  // Its audit log, newest first: revoke, rotate, the two verify checks, sign and generate.
  await page.getByRole('link', { name: 'View audit log' }).click()
  await expect(page.getByRole('heading', { name: 'Audit log', level: 1 })).toBeVisible()
  const auditRows = page.locator('table.audit-table tbody tr')
  await expect(auditRows).toHaveCount(6)
  await expect(auditRows.locator('td.audit-action')).toHaveText(['revoke', 'rotate', 'verify', 'verify', 'sign', 'generate'])
  await page.getByLabel('Action').selectOption('verify')
  await expect(auditRows).toHaveCount(2)
  await expect(auditRows.first()).toContainText('Success') // "not valid" is still a successful check
  expect(page.url()).not.toContain('Invoice')
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0])
})

// KAN-38: no axe-core violations on any view, at desktop and phone width, in the dark and the light theme.
test('axe-core finds no accessibility violations on any view', async ({ page }) => {
  const check = async (what) => {
    const results = await new AxeBuilder({ page }).analyze()
    const found = results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
    expect(found, what).toEqual([])
  }
  for (const size of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
    for (const colorScheme of ['dark', 'light']) {
      await page.setViewportSize(size)
      await page.emulateMedia({ colorScheme })
      const where = `${size.width}px ${colorScheme}`
      await page.goto('/')
      await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
      await check(`API key form, ${where}`)
      await enterKey(page, apiKey)
      await expect(rows(page)).toHaveCount(4)
      await check(`Key pairs, ${where}`)
      await page.getByRole('link', { name: 'Audit log' }).click()
      await expect(page.locator('table.audit-table tbody tr').first()).toBeVisible()
      await check(`Audit log, ${where}`)
    }
  }
})

test('"Skip to content" keeps the Audit log open and moves focus into the page (KAN-38)', async ({ page }) => {
  await page.goto('/')
  await enterKey(page, apiKey)
  await page.getByRole('link', { name: 'Audit log' }).click()
  const table = page.getByRole('region', { name: 'Audit log entries' })
  await expect(table).toBeVisible()

  // Focus is on the "Audit log" link just clicked; the skip link is the first stop of the page.
  await page.getByRole('link', { name: 'Skip to content' }).focus()
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
  expect(new URL(page.url()).hash).toBe('#audit')
  await expect(page.getByRole('heading', { name: 'Audit log', level: 1 })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: 'Refresh' })).toBeFocused()

  // On a phone the table scrolls sideways, and the keyboard can scroll it.
  await page.setViewportSize({ width: 390, height: 844 })
  await table.focus()
  await expect(table).toBeFocused()
  const before = await table.evaluate((el) => el.scrollLeft)
  await page.keyboard.press('ArrowRight')
  await expect.poll(() => table.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before)
})
