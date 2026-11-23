/**
 * Made-up API used by "npm run dev:mock" and by the tests. It follows the real API's responses and rules
 * (key states, audit rows, paging), so the dashboard can be built and shown without the backend.
 * Nothing here is real key material and no signature here means anything.
 */
import { ApiError } from './client.js'
import { allowedActions } from '../components/keyRules.js'

const DELAY_MS = 300
const MOCK_API_KEY_ID = '4c1d2e3f-0000-4000-8000-00000000a11e'
const DAY_MS = 24 * 60 * 60 * 1000

function randomBase64(byteCount) {
  const bytes = new Uint8Array(byteCount)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes))
}

function mockKey(algorithm, status, createdAt) {
  return {
    keyPairId: crypto.randomUUID(),
    algorithm,
    status,
    // Same length as the real X.509 encodings: 1590 bytes for ML-KEM-1024, 1974 for ML-DSA-65.
    publicKey: randomBase64(algorithm === 'ML-KEM-1024' ? 1590 : 1974),
    createdAt,
    rotatedAt: null,
    gracePeriodEndsAt: null,
    replacedBy: null,
  }
}

export function createMockApi({ initialKeys, delayMs = DELAY_MS, now = () => Date.now() } = {}) {
  const keys = (
    initialKeys ?? [
      mockKey('ML-KEM-1024', 'active', '2026-11-21T15:04:00Z'),
      mockKey('ML-DSA-65', 'active', '2026-11-20T09:30:00Z'),
      mockKey('ML-KEM-1024', 'rotated', '2026-11-12T18:12:00Z'),
    ]
  ).map((key) => ({ rotatedAt: null, gracePeriodEndsAt: null, replacedBy: null, ...key }))
  const audit = []
  const signatures = new Map()
  const wait = () => new Promise((resolve) => setTimeout(resolve, delayMs))
  const iso = () => new Date(now()).toISOString()

  function record(action, ok, keyPairId, time = iso()) {
    audit.push({
      id: String(audit.length + 1),
      time,
      action,
      status: ok ? 'success' : 'failure',
      keyPairId: keyPairId ?? null,
      apiKeyId: MOCK_API_KEY_ID,
    })
  }
  ;[...keys].sort((a, b) => a.createdAt.localeCompare(b.createdAt)).forEach((key) => {
    record('generate', true, key.keyPairId, key.createdAt)
  })

  /** The key pair, if the action is allowed on it; otherwise the API's error, audited like the API does. */
  function usable(id, action, needsSigning = false) {
    const key = keys.find((k) => k.keyPairId === id)
    if (!key) {
      record(action, false, null)
      throw new ApiError(404, 'key_not_found')
    }
    if (needsSigning && key.algorithm !== 'ML-DSA-65') {
      record(action, false, id)
      throw new ApiError(400, 'wrong_key_type')
    }
    if (!allowedActions(key, now())[action]) {
      record(action, false, id)
      throw new ApiError(400, 'key_not_active')
    }
    return key
  }

  return {
    async listKeys() {
      await wait()
      return keys.map((key) => ({ ...key })).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    },
    async generateKey(algorithm) {
      await wait()
      const key = mockKey(algorithm, 'active', iso())
      keys.push(key)
      record('generate', true, key.keyPairId)
      return { ...key }
    },
    async sign(id, message, context) {
      await wait()
      usable(id, 'sign', true)
      const signature = randomBase64(3309)
      const known = `${id}|${message}|${context ?? ''}`
      signatures.set(known, [...(signatures.get(known) ?? []), signature])
      record('sign', true, id)
      return { signature }
    },
    async verify(id, message, signature, context) {
      await wait()
      usable(id, 'verify', true)
      const valid = (signatures.get(`${id}|${message}|${context ?? ''}`) ?? []).includes(signature)
      record('verify', true, id)
      return { valid }
    },
    async rotateKey(id, gracePeriodDays) {
      await wait()
      if (!Number.isInteger(gracePeriodDays) || gracePeriodDays < 0 || gracePeriodDays > 90) {
        throw new ApiError(400, 'invalid_request')
      }
      const old = usable(id, 'rotate')
      const replacement = mockKey(old.algorithm, 'active', iso())
      keys.push(replacement)
      Object.assign(old, {
        status: 'rotated',
        rotatedAt: iso(),
        gracePeriodEndsAt: new Date(now() + gracePeriodDays * DAY_MS).toISOString(),
        replacedBy: replacement.keyPairId,
      })
      record('rotate', true, id)
      record('generate', true, replacement.keyPairId)
      return { ...replacement }
    },
    async revokeKey(id) {
      await wait()
      const key = usable(id, 'revoke')
      key.status = 'revoked'
      record('revoke', true, id)
      return { ...key }
    },
    async auditLog({ keyPairId, action, status, before, limit = 50 } = {}) {
      await wait()
      if (before && !audit.some((row) => row.id === before)) throw new ApiError(400, 'invalid_request')
      const rows = audit
        .filter((row) => !keyPairId || row.keyPairId === keyPairId)
        .filter((row) => !action || row.action === action)
        .filter((row) => !status || row.status === status)
        .filter((row) => !before || Number(row.id) < Number(before))
        .sort((a, b) => Number(b.id) - Number(a.id))
      const entries = rows.slice(0, limit).map((row) => ({ ...row }))
      return { entries, nextBefore: rows.length > limit ? entries[entries.length - 1].id : null }
    },
  }
}
