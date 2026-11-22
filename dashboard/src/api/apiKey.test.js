import { describe, expect, it } from 'vitest'
import { createKeyStore, isWellFormedApiKey } from './apiKey.js'

const KEY = 'ql_test_' + 'abcDEF123_-'.repeat(4).slice(0, 43)

function memoryStorage() {
  const items = new Map()
  return {
    items,
    getItem: (name) => (items.has(name) ? items.get(name) : null),
    setItem: (name, value) => items.set(name, value),
    removeItem: (name) => items.delete(name),
  }
}

describe('isWellFormedApiKey', () => {
  it('accepts the key format the backend issues and nothing else', () => {
    expect(isWellFormedApiKey(KEY)).toBe(true)
    expect(isWellFormedApiKey(KEY.replace('ql_test_', 'ql_live_'))).toBe(true)
    for (const bad of [undefined, null, '', KEY + 'x', KEY.slice(1), ' ' + KEY, KEY.replace('_test_', '_prod_')]) {
      expect(isWellFormedApiKey(bad)).toBe(false)
    }
  })
})

describe('createKeyStore', () => {
  it('keeps the key in memory only, unless asked to remember it', () => {
    const storage = memoryStorage()
    const store = createKeyStore(storage)

    store.set(KEY)
    expect(store.get()).toBe(KEY)
    expect(storage.items.size).toBe(0)

    store.set(KEY, true)
    expect([...storage.items.values()]).toEqual([KEY])
    expect(createKeyStore(storage).get()).toBe(KEY)
  })

  it('forgets the key everywhere', () => {
    const storage = memoryStorage()
    const store = createKeyStore(storage)
    store.set(KEY, true)

    store.clear()

    expect(store.get()).toBeNull()
    expect(storage.items.size).toBe(0)
  })

  it('ignores a saved value that is not a key', () => {
    const storage = memoryStorage()
    storage.setItem('quantumlock.apiKey', '<script>')

    expect(createKeyStore(storage).get()).toBeNull()
  })

  it('still works when the browser refuses storage', () => {
    const refusing = {
      getItem() {
        throw new Error('denied')
      },
      setItem() {
        throw new Error('denied')
      },
      removeItem() {
        throw new Error('denied')
      },
    }
    const store = createKeyStore(refusing)

    store.set(KEY, true)
    expect(store.get()).toBe(KEY)
    store.clear()
    expect(store.get()).toBeNull()
  })
})
