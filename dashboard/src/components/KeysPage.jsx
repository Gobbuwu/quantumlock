import { useCallback, useEffect, useState } from 'react'
import { describeError } from '../api/client.js'
import { describeCount } from './format.js'
import GenerateKeyForm from './GenerateKeyForm.jsx'
import KeyTable from './KeyTable.jsx'
import LatticeGlyph from './LatticeGlyph.jsx'

export default function KeysPage({ api, onUnauthorized }) {
  const [keys, setKeys] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [loadError, setLoadError] = useState(null)
  const [freshId, setFreshId] = useState(null)

  const load = useCallback(async () => {
    setStatus('loading')
    try {
      setKeys(await api.listKeys())
      setStatus('ready')
    } catch (error) {
      if (error?.code === 'unauthorized' && onUnauthorized) {
        onUnauthorized()
        return
      }
      setLoadError(describeError(error))
      setStatus('error')
    }
  }, [api, onUnauthorized])

  useEffect(() => {
    load()
  }, [load])

  return (
    <>
      <GenerateKeyForm
        api={api}
        onGenerated={(key) => {
          setKeys((current) => [key, ...current])
          setFreshId(key.keyPairId)
        }}
        onUnauthorized={onUnauthorized}
      />

      <section className="panel key-list" aria-labelledby="key-list-title">
        <div className="panel-head">
          <h2 id="key-list-title">Your key pairs</h2>
          {status === 'ready' && keys.length > 0 && <p className="panel-meta">{describeCount(keys)}</p>}
        </div>
        {status === 'loading' && (
          <p className="panel-note" role="status">
            Loading key pairs…
          </p>
        )}
        {status === 'error' && (
          <div className="problem panel-problem" role="alert">
            <p>{loadError}</p>
            <button type="button" className="button-secondary" onClick={load}>
              Try again
            </button>
          </div>
        )}
        {status === 'ready' && keys.length === 0 && (
          <div className="empty">
            <LatticeGlyph id="00000000-0000-4000-8000-000000000000" tone="empty" size={64} />
            <p className="panel-note">No key pairs yet. Choose an algorithm and generate your first one.</p>
          </div>
        )}
        {status === 'ready' && keys.length > 0 && <KeyTable keys={keys} freshId={freshId} />}
      </section>
    </>
  )
}
