import { useCallback, useEffect, useRef, useState } from 'react'
import ApiKeyForm from './components/ApiKeyForm.jsx'
import AuditPage from './components/AuditPage.jsx'
import KeysPage from './components/KeysPage.jsx'
import LatticeGlyph from './components/LatticeGlyph.jsx'

// The logo mark: a fixed lattice glyph, the same picture as public/favicon.svg.
const MARK_ID = '7f3a9c21-0000-4000-8000-5a5a5a5a5a5a'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** The view in the address: #keys (default), #audit or #audit?keyPair=<id>. Anything else is the key list. */
export function parseView(hash) {
  const [path, query = ''] = hash.replace(/^#/, '').split('?')
  if (path !== 'audit') return { view: 'keys', keyPairId: null }
  const keyPairId = new URLSearchParams(query).get('keyPair')
  return { view: 'audit', keyPairId: keyPairId && UUID.test(keyPairId) ? keyPairId : null }
}

function useView() {
  const [view, setView] = useState(() => parseView(window.location.hash))
  useEffect(() => {
    const update = () => {
      setView(parseView(window.location.hash))
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', update)
    return () => window.removeEventListener('hashchange', update)
  }, [])
  return view
}

const TITLES = {
  keys: {
    title: 'Key pairs',
    lede: 'Generate post-quantum key pairs for your team, sign and verify, rotate and revoke. Private keys stay encrypted on the server and are never shown here.',
  },
  audit: {
    title: 'Audit log',
    lede: 'Every key operation of your team, newest first: who did what to which key pair, and whether it worked. The log holds no messages or secrets.',
  },
}

const REVOKED_NOTICE = 'The API no longer accepts this key. It may have been revoked. Enter another key.'

/**
 * keyStore holds the API key (see api/apiKey.js). Without one (mock mode) the page needs no key.
 */
export default function App({ api, keyStore = null, mock = import.meta.env.VITE_API_MODE === 'mock' }) {
  const needsKey = keyStore !== null
  const [hasKey, setHasKey] = useState(() => !needsKey || keyStore.get() !== null)
  const [notice, setNotice] = useState(null)
  const { view, keyPairId } = useView()
  const signedIn = !needsKey || hasKey

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

  // The skip link moves focus into the page without touching the address: "#main" in the address would be read
  // by the view router as the key list, and on the Audit log page the link would switch pages (KAN-38, A3).
  const mainRef = useRef(null)
  const skipToContent = (event) => {
    event.preventDefault()
    mainRef.current.focus()
  }

  return (
    <>
      <a className="skip-link" href="#main" onClick={skipToContent}>
        Skip to content
      </a>
      <header className="topbar">
        <div className="wrap topbar-inner">
          <span className="brand" translate="no">
            <LatticeGlyph id={MARK_ID} tone="mark" size={32} />
            QuantumLock
          </span>
          {signedIn && (
            <nav className="views" aria-label="Views">
              <a href="#keys" aria-current={view === 'keys' ? 'page' : undefined}>
                Key pairs
              </a>
              <a href="#audit" aria-current={view === 'audit' ? 'page' : undefined}>
                Audit log
              </a>
            </nav>
          )}
          {needsKey && hasKey && (
            <button type="button" className="button-secondary topbar-action" onClick={() => forgetKey()}>
              Forget API key
            </button>
          )}
        </div>
      </header>
      {/* The title is part of the main landmark, so every piece of content sits in a landmark (KAN-38, A2). */}
      <main id="main" ref={mainRef} tabIndex={-1}>
        <div className="wrap page-title">
          <h1>{TITLES[view].title}</h1>
          <p className="lede">{TITLES[view].lede}</p>
          {mock && <p className="mock-notice">Mock mode: this page shows made-up keys and does not call the API.</p>}
        </div>
        <div className={!signedIn ? 'wrap page page-single' : view === 'audit' ? 'wrap page page-wide' : 'wrap page'}>
          {!signedIn ? (
            <ApiKeyForm
              api={api}
              keyStore={keyStore}
              notice={notice}
              onAccepted={() => {
                setNotice(null)
                setHasKey(true)
              }}
            />
          ) : view === 'audit' ? (
            <AuditPage api={api} keyPairId={keyPairId} onUnauthorized={needsKey ? keyRejected : undefined} />
          ) : (
            <KeysPage api={api} onUnauthorized={needsKey ? keyRejected : undefined} />
          )}
        </div>
      </main>
    </>
  )
}
