import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { describeError } from '../api/client.js'
import { formatDate, shortId } from './format.js'
import LatticeGlyph from './LatticeGlyph.jsx'

export const AUDIT_ACTIONS = ['generate', 'encapsulate', 'decapsulate', 'sign', 'verify', 'rotate', 'revoke']
const PAGE_SIZE = 25

/**
 * The team's audit log (KAN-33), newest first, with filters and "Load older" paging. keyPairId comes from the
 * address (#audit?keyPair=...), set by "View audit log" on a key pair.
 */
export default function AuditPage({ api, keyPairId = null, onUnauthorized }) {
  const id = useId()
  const [action, setAction] = useState('')
  const [result, setResult] = useState('')
  const [entries, setEntries] = useState([])
  const [nextBefore, setNextBefore] = useState(null)
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [error, setError] = useState(null)
  // Only the newest request may update the page, so a slow answer for old filters cannot overwrite a new one.
  const latest = useRef(0)

  const fetchPage = useCallback(
    async (before) => {
      const request = ++latest.current
      try {
        const page = await api.auditLog({
          keyPairId: keyPairId ?? undefined,
          action: action || undefined,
          status: result || undefined,
          before: before ?? undefined,
          limit: PAGE_SIZE,
        })
        if (request !== latest.current) return
        setEntries((current) => (before ? [...current, ...page.entries] : page.entries))
        setNextBefore(page.nextBefore ?? null)
        setStatus('ready')
        setError(null)
      } catch (e) {
        if (request !== latest.current) return
        if (e?.code === 'unauthorized' && onUnauthorized) {
          onUnauthorized()
          return
        }
        setError(describeError(e))
        if (!before) setStatus('error')
      } finally {
        if (request === latest.current) setLoadingOlder(false)
      }
    },
    [api, keyPairId, action, result, onUnauthorized],
  )

  const reload = useCallback(() => {
    setStatus('loading')
    setError(null)
    fetchPage(null)
  }, [fetchPage])

  useEffect(() => {
    reload()
  }, [reload])

  return (
    <section className="panel audit" aria-labelledby={`${id}-title`}>
      <div className="panel-head">
        <h2 id={`${id}-title`}>Entries</h2>
        <button type="button" className="button-secondary" onClick={reload} disabled={status === 'loading'}>
          Refresh
        </button>
      </div>
      <div className="audit-filters">
        <label className="filter" htmlFor={`${id}-action`}>
          Action
          <select id={`${id}-action`} value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="">All actions</option>
            {AUDIT_ACTIONS.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <label className="filter" htmlFor={`${id}-result`}>
          Result
          <select id={`${id}-result`} value={result} onChange={(e) => setResult(e.target.value)}>
            <option value="">Success and failure</option>
            <option value="success">Success</option>
            <option value="failure">Failure</option>
          </select>
        </label>
        {keyPairId && (
          <p className="filter-chip">
            Key pair <code translate="no">{shortId(keyPairId)}</code>
            <a className="button-link" href="#audit">
              Show all key pairs
            </a>
          </p>
        )}
      </div>

      {status === 'loading' && (
        <p className="panel-note" role="status">
          Loading the audit log…
        </p>
      )}
      {status === 'error' && (
        <div className="problem panel-problem" role="alert">
          <p>{error}</p>
          <button type="button" className="button-secondary" onClick={reload}>
            Try again
          </button>
        </div>
      )}
      {status === 'ready' && entries.length === 0 && (
        <p className="panel-note">No entries{keyPairId || action || result ? ' match these filters' : ' yet'}.</p>
      )}
      {status === 'ready' && entries.length > 0 && (
        <>
          {/* On a narrow screen the table scrolls sideways. Without links or buttons inside, the frame itself must
              take keyboard focus so the arrow keys can scroll it (KAN-38, A1, WCAG 2.1.1). */}
          <div className="table-frame" tabIndex={0} role="region" aria-label="Audit log entries">
            <table className="keys audit-table">
              <thead>
                <tr>
                  <th scope="col">Time</th>
                  <th scope="col">Action</th>
                  <th scope="col">Result</th>
                  <th scope="col">Key pair</th>
                  <th scope="col">API key</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id} className="audit-row">
                    <td className="cell-created">
                      <time dateTime={entry.time}>{formatDate(entry.time)}</time>
                    </td>
                    <td className="audit-action" translate="no">
                      {entry.action}
                    </td>
                    <td>
                      <span className={entry.status === 'success' ? 'status status-active' : 'status status-revoked'}>
                        {entry.status === 'success' ? 'Success' : 'Failure'}
                      </span>
                    </td>
                    <td>
                      {entry.keyPairId ? (
                        <span className="key-ident">
                          <LatticeGlyph id={entry.keyPairId} tone="other" size={24} />
                          <code className="key-id" title={entry.keyPairId} translate="no">
                            {shortId(entry.keyPairId)}
                          </code>
                        </span>
                      ) : (
                        <span className="detail-hint">none</span>
                      )}
                    </td>
                    <td>
                      {entry.apiKeyId ? (
                        <code className="key-id" title={entry.apiKeyId} translate="no">
                          {shortId(entry.apiKeyId)}
                        </code>
                      ) : (
                        <span className="detail-hint">none</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="audit-foot">
            <p className="panel-meta">
              {entries.length} {entries.length === 1 ? 'entry' : 'entries'} shown
              {nextBefore ? ', older ones exist' : ''}
            </p>
            {nextBefore && (
              <button
                type="button"
                className="button-secondary"
                disabled={loadingOlder}
                onClick={() => {
                  setLoadingOlder(true)
                  fetchPage(nextBefore)
                }}
              >
                {loadingOlder ? 'Loading…' : 'Load older'}
              </button>
            )}
          </div>
          {error && (
            <p className="problem panel-problem" role="alert">
              {error}
            </p>
          )}
        </>
      )}
    </section>
  )
}
