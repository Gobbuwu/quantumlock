import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { ApiError } from './api/client.js'
import { createMockApi } from './api/mock.js'
import { allowedActions } from './components/keyRules.js'
import { utf8ToBase64 } from './components/encoding.js'

const DSA = {
  keyPairId: '5d1e0f22-0000-4000-8000-000000000001',
  algorithm: 'ML-DSA-65',
  status: 'active',
  publicKey: 'ZHNhLXB1YmxpYy1rZXk=',
  createdAt: '2026-11-21T15:04:00Z',
}
const KEM = { ...DSA, keyPairId: '7a2f91c4-0000-4000-8000-000000000002', algorithm: 'ML-KEM-1024' }
const NOW = Date.parse('2026-11-23T12:00:00Z')

function setup(keys, options = {}) {
  window.location.hash = ''
  const api = createMockApi({ initialKeys: keys, delayMs: 0, ...options })
  const user = userEvent.setup()
  render(<App api={api} />)
  return { api, user }
}

async function openDetails(user, id) {
  await screen.findByRole('table')
  await user.click(screen.getByRole('button', { name: `Show details of ${id.slice(0, 8)}` }))
  return screen.getByRole('group', { name: `Actions for ${id.slice(0, 8)}` })
}

afterEach(() => {
  window.location.hash = ''
})

describe('which actions a key pair offers', () => {
  const rotated = (ends) => ({ ...DSA, status: 'rotated', gracePeriodEndsAt: ends })
  it.each([
    ['active ML-DSA-65', DSA, { sign: true, verify: true, rotate: true, revoke: true }],
    ['active ML-KEM-1024', KEM, { sign: false, verify: false, rotate: true, revoke: true }],
    ['rotated, in grace', rotated('2026-11-24T00:00:00Z'), { sign: false, verify: true, rotate: false, revoke: true }],
    ['rotated, grace over', rotated('2026-11-23T11:59:59Z'), { sign: false, verify: false, rotate: false, revoke: true }],
    ['revoked', { ...DSA, status: 'revoked' }, { sign: false, verify: false, rotate: false, revoke: false }],
  ])('%s', (_name, key, expected) => {
    const { inGrace: _ignored, ...allowed } = allowedActions(key, NOW)
    expect(allowed).toEqual(expected)
  })

  it('shows only the allowed buttons', async () => {
    const { user } = setup([KEM])
    const actions = await openDetails(user, KEM.keyPairId)
    const names = within(actions).getAllByRole('button').map((b) => b.textContent)
    expect(names).toEqual(['Rotate', 'Revoke'])
  })

  it('a revoked key pair offers nothing but its audit log', async () => {
    const { user } = setup([{ ...DSA, status: 'revoked' }])
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /Show details/ }))
    expect(screen.getByText(/revoked and can no longer be used/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View audit log' })).toHaveAttribute(
      'href',
      `#audit?keyPair=${DSA.keyPairId}`,
    )
  })
})

describe('sign and verify', () => {
  it('signs a message, and the signature verifies only for the same message and context', async () => {
    const { api, user } = setup([DSA])
    const sign = vi.spyOn(api, 'sign')
    const actions = await openDetails(user, DSA.keyPairId)

    await user.click(within(actions).getByRole('button', { name: 'Sign' }))
    const signForm = screen.getByRole('form', { name: 'Sign a message' })
    await user.type(within(signForm).getByLabelText('Message'), 'Invoice 118: 42.00 €')
    await user.type(within(signForm).getByLabelText('Context (optional)'), 'invoices')
    await user.click(within(signForm).getByRole('button', { name: 'Sign message' }))

    // UTF-8, then Base64: the euro sign is three bytes.
    expect(sign).toHaveBeenCalledWith(DSA.keyPairId, utf8ToBase64('Invoice 118: 42.00 €'), utf8ToBase64('invoices'))
    const signature = (await within(signForm).findByRole('textbox', { name: 'Signature' })).value
    expect(atob(signature)).toHaveLength(3309)

    await user.click(within(actions).getByRole('button', { name: 'Verify' }))
    const verifyForm = screen.getByRole('form', { name: 'Verify a signature' })
    const message = within(verifyForm).getByLabelText('Message')
    await user.type(message, 'Invoice 118: 42.00 €')
    await user.type(within(verifyForm).getByLabelText('Context (optional)'), 'invoices')
    await user.click(within(verifyForm).getByLabelText('Signature'))
    await user.paste(signature)
    await user.click(within(verifyForm).getByRole('button', { name: 'Verify signature' }))
    expect(await within(verifyForm).findByText(/^Valid\./)).toBeInTheDocument()

    await user.clear(message)
    await user.type(message, 'Invoice 118: 4200.00 €')
    await user.click(within(verifyForm).getByRole('button', { name: 'Verify signature' }))
    expect(await within(verifyForm).findByText(/^Not valid\./)).toBeInTheDocument()
  })

  it('checks sizes and the signature format before calling the API', async () => {
    const { api, user } = setup([DSA])
    const sign = vi.spyOn(api, 'sign')
    const verify = vi.spyOn(api, 'verify')
    const actions = await openDetails(user, DSA.keyPairId)

    await user.click(within(actions).getByRole('button', { name: 'Sign' }))
    const signForm = screen.getByRole('form', { name: 'Sign a message' })
    await user.click(within(signForm).getByRole('button', { name: 'Sign message' }))
    expect(within(signForm).getByRole('alert')).toHaveTextContent('Enter a message.')
    await user.click(within(signForm).getByLabelText('Message'))
    await user.paste('x'.repeat(32 * 1024 + 1))
    await user.click(within(signForm).getByRole('button', { name: 'Sign message' }))
    expect(within(signForm).getByRole('alert')).toHaveTextContent('32769 bytes')
    await user.clear(within(signForm).getByLabelText('Message'))
    await user.type(within(signForm).getByLabelText('Message'), 'ok')
    await user.click(within(signForm).getByLabelText('Context (optional)'))
    await user.paste('é'.repeat(128)) // 256 bytes
    await user.click(within(signForm).getByRole('button', { name: 'Sign message' }))
    expect(within(signForm).getByRole('alert')).toHaveTextContent('at most 255 bytes')
    expect(sign).not.toHaveBeenCalled()

    await user.click(within(actions).getByRole('button', { name: 'Verify' }))
    const verifyForm = screen.getByRole('form', { name: 'Verify a signature' })
    await user.type(within(verifyForm).getByLabelText('Message'), 'ok')
    await user.type(within(verifyForm).getByLabelText('Signature'), 'not base64!')
    await user.click(within(verifyForm).getByRole('button', { name: 'Verify signature' }))
    expect(within(verifyForm).getByRole('alert')).toHaveTextContent('Paste the signature as Base64.')
    expect(verify).not.toHaveBeenCalled()
  })

  it('explains an API refusal', async () => {
    const { api, user } = setup([DSA])
    api.sign = vi.fn().mockRejectedValue(new ApiError(400, 'key_not_active'))
    const actions = await openDetails(user, DSA.keyPairId)
    await user.click(within(actions).getByRole('button', { name: 'Sign' }))
    const signForm = screen.getByRole('form', { name: 'Sign a message' })
    await user.type(within(signForm).getByLabelText('Message'), 'hello')
    await user.click(within(signForm).getByRole('button', { name: 'Sign message' }))
    expect(await within(signForm).findByRole('alert')).toHaveTextContent(/no longer allows that/)
  })

  it('keeps nothing typed in browser storage or the address', async () => {
    localStorage.clear()
    sessionStorage.clear()
    const { user } = setup([DSA])
    const actions = await openDetails(user, DSA.keyPairId)
    await user.click(within(actions).getByRole('button', { name: 'Sign' }))
    await user.type(screen.getByLabelText('Message'), 'secret plan')
    await user.click(screen.getByRole('button', { name: 'Sign message' }))
    await screen.findByRole('textbox', { name: 'Signature' })
    expect(localStorage.length + sessionStorage.length).toBe(0)
    expect(window.location.href).not.toContain('secret')
  })
})

describe('rotate and revoke', () => {
  it('rotates with the chosen grace period and shows the replacement on top', async () => {
    const { api, user } = setup([DSA])
    const rotate = vi.spyOn(api, 'rotateKey')
    const actions = await openDetails(user, DSA.keyPairId)

    await user.click(within(actions).getByRole('button', { name: 'Rotate' }))
    const form = screen.getByRole('form', { name: 'Rotate this key pair' })
    const days = within(form).getByLabelText('Grace period in days')
    expect(days).toHaveValue('7')
    await user.clear(days)
    await user.type(days, '1')
    await user.click(within(form).getByRole('button', { name: 'Rotate key pair' }))

    expect(rotate).toHaveBeenCalledWith(DSA.keyPairId, 1)
    await waitFor(() => expect(within(screen.getByRole('table')).getAllByText('Rotated')).toHaveLength(1))
    const rows = screen.getAllByRole('row').filter((row) => row.classList.contains('key-row'))
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText('Active')).toBeInTheDocument() // the replacement, newest first
    // The old one stays open: it may still verify during the grace period, and says by what it was replaced.
    expect(screen.getByText(/Rotated, replaced by/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign' })).not.toBeInTheDocument()
  })

  it.each(['91', '-1', '2.5', 'seven', ''])('refuses a grace period of "%s" without calling the API', async (value) => {
    const { api, user } = setup([KEM])
    const rotate = vi.spyOn(api, 'rotateKey')
    const actions = await openDetails(user, KEM.keyPairId)
    await user.click(within(actions).getByRole('button', { name: 'Rotate' }))
    const days = screen.getByLabelText('Grace period in days')
    await user.clear(days)
    if (value) await user.type(days, value)
    await user.click(screen.getByRole('button', { name: 'Rotate key pair' }))
    expect(screen.getByRole('alert')).toHaveTextContent('from 0 to 90')
    expect(rotate).not.toHaveBeenCalled()
  })

  it('revokes only after a second, explicit confirmation, and Cancel changes nothing', async () => {
    const { api, user } = setup([KEM])
    const revoke = vi.spyOn(api, 'revokeKey')
    const actions = await openDetails(user, KEM.keyPairId)

    await user.click(within(actions).getByRole('button', { name: 'Revoke' }))
    const confirm = screen.getByRole('group', { name: 'Revoke this key pair' })
    expect(confirm).toHaveTextContent('cannot be undone')
    await user.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    expect(revoke).not.toHaveBeenCalled()
    expect(screen.queryByRole('group', { name: 'Revoke this key pair' })).not.toBeInTheDocument()

    await user.click(within(actions).getByRole('button', { name: 'Revoke' }))
    await user.click(screen.getByRole('button', { name: 'Revoke for good' }))
    expect(revoke).toHaveBeenCalledWith(KEM.keyPairId)
    expect(await within(screen.getByRole('table')).findByText('Revoked')).toBeInTheDocument()
    expect(screen.getByText(/revoked and can no longer be used/)).toBeInTheDocument()
  })

  it('a rejected API key during an action asks for a new key', async () => {
    window.location.hash = ''
    const api = createMockApi({ initialKeys: [KEM], delayMs: 0 })
    api.revokeKey = vi.fn().mockRejectedValue(new ApiError(401, 'unauthorized'))
    const keyStore = { value: 'ql_test_x', get() { return this.value }, set(v) { this.value = v }, clear() { this.value = null } }
    const user = userEvent.setup()
    render(<App api={api} keyStore={keyStore} />)
    const actions = await openDetails(user, KEM.keyPairId)
    await user.click(within(actions).getByRole('button', { name: 'Revoke' }))
    await user.click(screen.getByRole('button', { name: 'Revoke for good' }))
    expect(await screen.findByRole('heading', { name: 'Enter your API key' })).toBeInTheDocument()
    expect(keyStore.value).toBeNull()
  })
})
