import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App, { parseView } from './App.jsx'
import { ApiError } from './api/client.js'
import { createMockApi } from './api/mock.js'

const KEY = {
  keyPairId: '5d1e0f22-0000-4000-8000-000000000001',
  algorithm: 'ML-DSA-65',
  status: 'active',
  publicKey: 'cHVibGlj',
  createdAt: '2026-11-21T15:04:00Z',
}
const OTHER = { ...KEY, keyPairId: '7a2f91c4-0000-4000-8000-000000000002', createdAt: '2026-11-20T15:04:00Z' }

function show(api, hash = '#audit') {
  window.location.hash = hash
  const user = userEvent.setup()
  render(<App api={api} />)
  return user
}

const bodyRows = () => within(screen.getByRole('table')).getAllByRole('row').slice(1)

afterEach(() => {
  window.location.hash = ''
})

describe('the address picks the view', () => {
  it.each([
    ['', { view: 'keys', keyPairId: null }],
    ['#keys', { view: 'keys', keyPairId: null }],
    ['#audit', { view: 'audit', keyPairId: null }],
    [`#audit?keyPair=${KEY.keyPairId}`, { view: 'audit', keyPairId: KEY.keyPairId }],
    ['#audit?keyPair=not-an-id', { view: 'audit', keyPairId: null }],
    ['#audit?keyPair=5D1E0F22-0000-4000-8000-000000000001', { view: 'audit', keyPairId: null }],
    ['#elsewhere', { view: 'keys', keyPairId: null }],
  ])('%s', (hash, expected) => {
    expect(parseView(hash)).toEqual(expected)
  })
})

describe('Audit log page', () => {
  it('lists every operation newest first, and the nav marks the current view', async () => {
    const api = createMockApi({ initialKeys: [KEY, OTHER], delayMs: 0 })
    await api.sign(KEY.keyPairId, 'aGk=')
    show(api)

    expect(await screen.findByRole('heading', { name: 'Audit log', level: 1 })).toBeInTheDocument()
    await screen.findByRole('table')
    expect(bodyRows().map((row) => row.cells[1].textContent)).toEqual(['sign', 'generate', 'generate'])
    expect(within(bodyRows()[0]).getByText('Success')).toBeInTheDocument()
    expect(within(bodyRows()[0]).getByText('5d1e0f22')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Audit log' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Key pairs' })).not.toHaveAttribute('aria-current')
  })

  it('filters by action and result', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    const auditLog = vi.spyOn(api, 'auditLog')
    await api.sign(KEY.keyPairId, 'aGk=')
    await api.sign('00000000-0000-4000-8000-000000000000', 'aGk=').catch(() => {})
    const user = show(api)
    await screen.findByRole('table')

    await user.selectOptions(screen.getByLabelText('Action'), 'sign')
    await waitFor(() => expect(bodyRows()).toHaveLength(2))
    await user.selectOptions(screen.getByLabelText('Result'), 'failure')
    await waitFor(() => expect(bodyRows()).toHaveLength(1))
    expect(within(bodyRows()[0]).getByText('Failure')).toBeInTheDocument()
    expect(within(bodyRows()[0]).getByText('none')).toBeInTheDocument() // no key pair of this team
    expect(auditLog).toHaveBeenLastCalledWith(expect.objectContaining({ action: 'sign', status: 'failure' }))

    await user.selectOptions(screen.getByLabelText('Action'), 'revoke')
    expect(await screen.findByText('No entries match these filters.')).toBeInTheDocument()
  })

  it('shows one key pair from "View audit log", and can show all again', async () => {
    const api = createMockApi({ initialKeys: [KEY, OTHER], delayMs: 0 })
    const user = show(api, `#audit?keyPair=${KEY.keyPairId}`)
    await screen.findByRole('table')
    expect(bodyRows()).toHaveLength(1)
    expect(document.querySelector('.filter-chip')).toHaveTextContent('Key pair 5d1e0f22')

    await user.click(screen.getByRole('link', { name: 'Show all key pairs' }))
    await waitFor(() => expect(bodyRows()).toHaveLength(2))
  })

  it('"View audit log" on a key pair opens its filtered log', async () => {
    const api = createMockApi({ initialKeys: [KEY, OTHER], delayMs: 0 })
    const user = show(api, '#keys')
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: 'Show details of 5d1e0f22' }))
    await user.click(screen.getByRole('link', { name: 'View audit log' }))

    expect(await screen.findByRole('heading', { name: 'Audit log', level: 1 })).toBeInTheDocument()
    await waitFor(() => expect(bodyRows()).toHaveLength(1))
  })

  it('loads older entries page by page without repeats', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    for (let i = 0; i < 59; i++) await api.verify(KEY.keyPairId, 'aGk=', 'AAAA')
    const user = show(api)
    await screen.findByRole('table')
    expect(bodyRows()).toHaveLength(25)
    expect(screen.getByText(/25 entries shown, older ones exist/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Load older' }))
    await waitFor(() => expect(bodyRows()).toHaveLength(50))
    await user.click(screen.getByRole('button', { name: 'Load older' }))
    await waitFor(() => expect(bodyRows()).toHaveLength(60))
    expect(screen.queryByRole('button', { name: 'Load older' })).not.toBeInTheDocument()
    expect(bodyRows().at(-1).cells[1].textContent).toBe('generate') // the oldest row
    // The three pages together are exactly the whole log, in order: nothing repeated, nothing skipped.
    const all = await api.auditLog({ limit: 100 })
    const shown = bodyRows().map((row) => row.cells[0].querySelector('time').dateTime + row.cells[1].textContent)
    expect(shown).toEqual(all.entries.map((entry) => entry.time + entry.action))
  })

  it('Refresh shows new entries', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    const user = show(api)
    await screen.findByRole('table')
    expect(bodyRows()).toHaveLength(1)
    await api.sign(KEY.keyPairId, 'aGk=')
    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(bodyRows()).toHaveLength(2))
  })

  it('explains a failure and tries again', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    const real = api.auditLog
    api.auditLog = vi.fn().mockRejectedValueOnce(new ApiError(429, 'rate_limited', 12)).mockImplementation(real)
    const user = show(api)
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 12 seconds')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('an answer for old filters never replaces the newer one', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    await api.sign(KEY.keyPairId, 'aGk=')
    const real = api.auditLog
    let releaseSlow
    api.auditLog = vi.fn((query) =>
      query.action ? real(query) : new Promise((resolve) => (releaseSlow = () => resolve(real(query)))),
    )
    const user = show(api)
    await waitFor(() => expect(api.auditLog).toHaveBeenCalledTimes(1))
    await user.selectOptions(screen.getByLabelText('Action'), 'sign')
    await waitFor(() => expect(bodyRows()).toHaveLength(1))
    releaseSlow()
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(bodyRows()).toHaveLength(1)
  })
})

describe('keyboard and landmarks (KAN-38)', () => {
  it('"Skip to content" moves focus into the page and keeps the Audit log open', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    const user = show(api)
    await screen.findByRole('table')

    await user.tab()
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus()
    await user.keyboard('{Enter}')

    expect(screen.getByRole('main')).toHaveFocus()
    expect(window.location.hash).toBe('#audit')
    expect(screen.getByRole('heading', { name: 'Audit log', level: 1 })).toBeInTheDocument()
    // The next Tab goes to the first control on the page, not back to the top bar.
    await user.tab()
    expect(screen.getByRole('button', { name: 'Refresh' })).toHaveFocus()
  })

  it('lets the keyboard reach the audit table, which scrolls sideways on a narrow screen', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    show(api)
    const frame = await screen.findByRole('region', { name: 'Audit log entries' })

    expect(frame).toHaveAttribute('tabindex', '0')
    expect(within(frame).getByRole('table')).toBeInTheDocument()
  })

  it('puts the page title inside the main landmark', async () => {
    const api = createMockApi({ initialKeys: [KEY], delayMs: 0 })
    show(api, '#keys')

    const main = screen.getByRole('main')
    expect(within(main).getByRole('heading', { name: 'Key pairs', level: 1 })).toBeInTheDocument()
  })
})
