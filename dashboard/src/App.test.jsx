import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { ApiError } from './api/client.js'
import { createMockApi } from './api/mock.js'

const KEM_KEY = {
  keyPairId: '7a2f91c4-0000-4000-8000-000000000001',
  algorithm: 'ML-KEM-1024',
  status: 'active',
  publicKey: 'TUxLRU0tcHVibGljLWtleQ==',
  createdAt: '2026-11-21T15:04:00Z',
}
const ROTATED_KEY = {
  keyPairId: '3e081a77-0000-4000-8000-000000000002',
  algorithm: 'ML-KEM-1024',
  status: 'rotated',
  publicKey: 'b2xkLWtleQ==',
  createdAt: '2026-11-12T18:12:00Z',
}

function setup(api) {
  const user = userEvent.setup()
  render(<App api={api} />)
  return user
}

describe('Key pairs page', () => {
  it('lists the team’s key pairs with their status', async () => {
    setup(createMockApi({ initialKeys: [KEM_KEY, ROTATED_KEY], delayMs: 0 }))

    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(3) // header + 2 keys
    expect(within(rows[1]).getByText('7a2f91c4')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Active')).toBeInTheDocument()
    expect(within(rows[2]).getByText('Rotated')).toBeInTheDocument()
  })

  it('shows what to do when there are no keys yet', async () => {
    setup(createMockApi({ initialKeys: [], delayMs: 0 }))

    expect(await screen.findByText(/No key pairs yet/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('generates a key with the chosen algorithm and puts it at the top of the list', async () => {
    const api = createMockApi({ initialKeys: [KEM_KEY], delayMs: 0 })
    const generateKey = vi.spyOn(api, 'generateKey')
    const user = setup(api)
    await screen.findByRole('table')

    await user.click(screen.getByRole('radio', { name: /ML-DSA-65/ }))
    await user.click(screen.getByRole('button', { name: 'Generate key pair' }))

    expect(generateKey).toHaveBeenCalledWith('ML-DSA-65')
    expect(await screen.findByText(/Generated ML-DSA-65 key pair/)).toBeInTheDocument()
    const rows = within(screen.getByRole('table')).getAllByRole('row')
    expect(rows).toHaveLength(3)
    expect(within(rows[1]).getByText('ML-DSA-65')).toBeInTheDocument()
  })

  it('preselects ML-KEM-1024', async () => {
    const api = createMockApi({ initialKeys: [], delayMs: 0 })
    const generateKey = vi.spyOn(api, 'generateKey')
    const user = setup(api)
    await screen.findByText(/No key pairs yet/)

    await user.click(screen.getByRole('button', { name: 'Generate key pair' }))

    expect(generateKey).toHaveBeenCalledWith('ML-KEM-1024')
  })

  it('explains a failed generation and keeps the list unchanged', async () => {
    const api = createMockApi({ initialKeys: [KEM_KEY], delayMs: 0 })
    api.generateKey = vi.fn().mockRejectedValue(new ApiError(500, 'key_generation_failed'))
    const user = setup(api)
    await screen.findByRole('table')

    await user.click(screen.getByRole('button', { name: 'Generate key pair' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/nothing was saved/)
    expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(2)
  })

  it('explains an unreachable backend and loads again on Try again', async () => {
    const api = createMockApi({ initialKeys: [KEM_KEY], delayMs: 0 })
    const realList = api.listKeys
    api.listKeys = vi.fn().mockRejectedValueOnce(new ApiError(0, 'api_unreachable')).mockImplementation(realList)
    const user = setup(api)

    expect(await screen.findByRole('alert')).toHaveTextContent(/Start the backend/)
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('shows the full id and public key on request, never anything private', async () => {
    const user = setup(createMockApi({ initialKeys: [KEM_KEY], delayMs: 0 }))
    await screen.findByRole('table')

    await user.click(screen.getByRole('button', { name: 'Show details of 7a2f91c4' }))

    expect(screen.getByText(KEM_KEY.keyPairId)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Public key of 7a2f91c4/ })).toHaveValue(KEM_KEY.publicKey)
    expect(screen.getByRole('button', { name: 'Hide details of 7a2f91c4' })).toHaveAttribute('aria-expanded', 'true')
    expect(document.body.textContent.toLowerCase()).not.toMatch(/private key[^s]/)
  })
})
