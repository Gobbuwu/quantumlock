/**
 * Made-up API used by "npm run dev:mock" and by the tests. It follows the real API's responses,
 * so the dashboard can be built and shown without the backend. Nothing here is real key material.
 */

const DELAY_MS = 300

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
  }
}

export function createMockApi({ initialKeys, delayMs = DELAY_MS } = {}) {
  const keys = initialKeys ?? [
    mockKey('ML-KEM-1024', 'active', '2026-11-21T15:04:00Z'),
    mockKey('ML-DSA-65', 'active', '2026-11-20T09:30:00Z'),
    mockKey('ML-KEM-1024', 'rotated', '2026-11-12T18:12:00Z'),
  ]
  const wait = () => new Promise((resolve) => setTimeout(resolve, delayMs))

  return {
    async listKeys() {
      await wait()
      return [...keys].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    },
    async generateKey(algorithm) {
      await wait()
      const key = mockKey(algorithm, 'active', new Date().toISOString())
      keys.push(key)
      return key
    },
  }
}
