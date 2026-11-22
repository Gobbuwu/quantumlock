import { useCallback, useState } from 'react'
import ApiKeyForm from './components/ApiKeyForm.jsx'
import KeysPage from './components/KeysPage.jsx'

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
      <header className="masthead">
        <div className="masthead-inner">
          <span className="wordmark">QuantumLock</span>
          <h1>Key pairs</h1>
          <p className="lede">
            Generate post-quantum key pairs for your team. Private keys stay encrypted on the server and are
            never shown here.
          </p>
          {mock && (
            <p className="mock-notice">Mock mode: this page shows made-up keys and does not call the API.</p>
          )}
          {needsKey && hasKey && (
            <button type="button" className="button-link masthead-action" onClick={() => forgetKey()}>
              Forget API key
            </button>
          )}
        </div>
      </header>
      <main id="main" className="page">
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
