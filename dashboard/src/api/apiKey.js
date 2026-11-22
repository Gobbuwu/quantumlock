/**
 * Holds the API key the dashboard sends in the X-API-Key header (task 4.2).
 *
 * The key is kept in memory. Only if the user ticks "Remember" is it also put in sessionStorage, which the
 * browser deletes when the tab is closed. It is never put in localStorage, a cookie, the URL or the source code.
 */

const STORAGE_NAME = 'quantumlock.apiKey'
const FORMAT = /^ql_(test|live)_[A-Za-z0-9_-]{43}$/

export function isWellFormedApiKey(value) {
  return typeof value === 'string' && FORMAT.test(value)
}

/** sessionStorage, or null where the browser refuses it (private mode, blocked site data). */
function sessionStorageOrNull() {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage
  } catch {
    return null
  }
}

export function createKeyStore(storage = sessionStorageOrNull()) {
  function read() {
    try {
      const saved = storage?.getItem(STORAGE_NAME) ?? null
      return isWellFormedApiKey(saved) ? saved : null
    } catch {
      return null
    }
  }
  function forget() {
    try {
      storage?.removeItem(STORAGE_NAME)
    } catch {
      // Nothing to remove.
    }
  }

  let key = read()

  return {
    get: () => key,
    set(value, remember = false) {
      key = value
      forget()
      if (remember) {
        try {
          storage?.setItem(STORAGE_NAME, value)
        } catch {
          // Storage refused: the key still works until the page is reloaded.
        }
      }
    },
    clear() {
      key = null
      forget()
    },
  }
}
