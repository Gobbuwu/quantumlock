import { useCallback, useState } from 'react'
import ApiKeyForm from './components/ApiKeyForm.jsx'
import KeysPage from './components/KeysPage.jsx'
import LatticeGlyph from './components/LatticeGlyph.jsx'

// The logo mark: a fixed lattice glyph, the same picture as public/favicon.svg.
const MARK_ID = '7f3a9c21-0000-4000-8000-5a5a5a5a5a5a'

const REVOKED_NOTICE = 'The API no longer accepts this key. It may have been revoked. Enter another key.'

/**
 * keyStore holds the API key (see api/apiKey.js). Without one (mock mode) the page needs no key.
 */
export default function App({ api, keyStore = null, mock = import.meta.env.VITE_API_MODE === 'mock' }) {
  const needsKey = keyStore !== null
  const [hasKey, setHasKey] = useState(() => !needsKey || keyStore.get() !== null)
  const [notice, setNotice] = useState(null)

  const forgetKey = useCallback(
    (message = null) => {
      keyStore.clear()
      setHasKey(false)
      setNotice(message)
    },
    [keyStore],
  )
  // Stable, so the key list does not reload on every render.
  const keyRejected = useCallback(() => forgetKey(REVOKED_NOTICE), [forgetKey])

  return (
    <>
      <a className="skip-link" href="#main">Skip to content</a>
      <header className="topbar">
        <div className="wrap topbar-inner">
          <span className="brand" translate="no">
            <LatticeGlyph id={MARK_ID} tone="mark" size={32} />
            QuantumLock
          </span>
          {needsKey && hasKey && (
            <button type="button" className="button-secondary topbar-action" onClick={() => forgetKey()}>
              Forget API key
            </button>
          )}
        </div>
      </header>
      <div className="wrap page-title">
        <h1>Key pairs</h1>
        <p className="lede">
          Generate post-quantum key pairs for your team. Private keys stay encrypted on the server and are never
          shown here.
        </p>
        {mock && <p className="mock-notice">Mock mode: this page shows made-up keys and does not call the API.</p>}
      </div>
      <main id="main" className={needsKey && !hasKey ? 'wrap page page-single' : 'wrap page'}>
        {needsKey && !hasKey ? (
          <ApiKeyForm
            api={api}
            keyStore={keyStore}
            notice={notice}
            onAccepted={() => {
              setNotice(null)
              setHasKey(true)
            }}
          />
        ) : (
          <KeysPage api={api} onUnauthorized={needsKey ? keyRejected : undefined} />
        )}
      </main>
    </>
  )
}
