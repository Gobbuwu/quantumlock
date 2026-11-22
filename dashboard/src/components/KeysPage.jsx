import { useCallback, useEffect, useState } from 'react'
import { describeError } from '../api/client.js'
import GenerateKeyForm from './GenerateKeyForm.jsx'
import KeyTable from './KeyTable.jsx'

export default function KeysPage({ api, onUnauthorized }) {
  const [keys, setKeys] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [loadError, setLoadError] = useState(null)

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
        onGenerated={(key) => setKeys((current) => [key, ...current])}
        onUnauthorized={onUnauthorized}
      />

      <section className="key-list" aria-labelledby="key-list-title">
        <h2 id="key-list-title">Your key pairs</h2>
        {status === 'loading' && <p className="note" role="status">Loading key pairs…</p>}
        {status === 'error' && (
          <div className="problem" role="alert">
            <p>{loadError}</p>
            <button type="button" className="button-secondary" onClick={load}>
              Try again
            </button>
          </div>
        )}
        {status === 'ready' && keys.length === 0 && (
          <p className="note">No key pairs yet. Choose an algorithm above and generate your first one.</p>
        )}
        {status === 'ready' && keys.length > 0 && <KeyTable keys={keys} />}
      </section>
    </>
  )
}
