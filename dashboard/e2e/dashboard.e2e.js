// The dashboard on a fresh setup (KAN-24): enter the API key, generate one key pair of each algorithm and see
// them in the list. Also: a wrong key is refused, the key is forgotten on reload unless the user opts in, and
// "Forget API key" removes it. e2e/run.sh then checks the database rows.
import { expect, test } from '@playwright/test'

const apiKey = process.env.E2E_API_KEY
const WRONG_KEY = 'ql_test_' + 'A'.repeat(43) // well formed, but never issued

test.describe.configure({ mode: 'serial' })

test.beforeAll(() => {
  if (!apiKey) throw new Error('E2E_API_KEY is not set. Start this test with e2e/run.sh.')
})

async function enterKey(page, key, { remember = false } = {}) {
  await expect(page.getByRole('heading', { name: 'Enter your API key' })).toBeVisible()
  await page.getByLabel('API key', { exact: true }).fill(key)
  if (remember) await page.getByLabel('Remember until this browser tab is closed').check()
  await page.getByRole('button', { name: 'Use this key' }).click()
}

const rows = (page) => page.locator('table.keys tbody tr').filter({ has: page.locator('code.key-id') })

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
