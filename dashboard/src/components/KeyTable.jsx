import { Fragment, useState } from 'react'
import { ALGORITHMS } from './algorithms.js'
import KeyActions from './KeyActions.jsx'
import { formatDate, shortId } from './format.js'
import LatticeGlyph, { glyphTone } from './LatticeGlyph.jsx'

const STATUS_LABEL = { active: 'Active', rotated: 'Rotated', revoked: 'Revoked' }
const USE = Object.fromEntries(ALGORITHMS.map((option) => [option.name, option.use]))

/** freshId: the key pair generated a moment ago, whose glyph plays its one-time settle animation. */
export default function KeyTable({ keys, freshId = null, api, onChanged, onUnauthorized }) {
  const [openId, setOpenId] = useState(null)

  return (
    <div className="table-frame">
      <table className="keys">
      <thead>
        <tr>
          <th scope="col">Key ID</th>
          <th scope="col">Algorithm</th>
          <th scope="col">Status</th>
          <th scope="col">Created</th>
          <th scope="col">
            <span className="visually-hidden">Details</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {keys.map((key) => {
          const open = openId === key.keyPairId
          const panelId = `key-detail-${key.keyPairId}`
          return (
            <Fragment key={key.keyPairId}>
              <tr className={open ? 'key-row is-open' : 'key-row'}>
                <td className="cell-id">
                  <span className="key-ident">
                    <LatticeGlyph id={key.keyPairId} tone={glyphTone(key)} settle={key.keyPairId === freshId} />
                    <code className="key-id" title={key.keyPairId} translate="no">
                      {shortId(key.keyPairId)}
                    </code>
                  </span>
                </td>
                <td className="cell-algorithm">
                  <span className="algorithm-label" translate="no">
                    {key.algorithm}
                  </span>
                  {USE[key.algorithm] && <span className="algorithm-sub">{USE[key.algorithm]}</span>}
                </td>
                <td className="cell-status">
                  <span className={`status status-${key.status}`}>{STATUS_LABEL[key.status] ?? key.status}</span>
                </td>
                <td className="cell-created">
                  <time dateTime={key.createdAt}>{formatDate(key.createdAt)}</time>
                </td>
                <td className="cell-action">
                  <button
                    type="button"
                    className="button-link"
                    aria-expanded={open}
                    aria-controls={panelId}
                    aria-label={`${open ? 'Hide' : 'Show'} details of ${shortId(key.keyPairId)}`}
                    onClick={() => setOpenId(open ? null : key.keyPairId)}
                  >
                    {open ? 'Hide details' : 'Show details'}
                  </button>
                </td>
              </tr>
              {open && (
                <tr className="detail-row">
                  <td colSpan={5} id={panelId}>
                    <KeyDetail keyPair={key} api={api} onChanged={onChanged} onUnauthorized={onUnauthorized} />
                  </td>
                </tr>
              )}
            </Fragment>
          )
        })}
      </tbody>
      </table>
    </div>
  )
}

function KeyDetail({ keyPair, api, onChanged, onUnauthorized }) {
  const [copied, setCopied] = useState(null)

  async function copy(label, text) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(label)
    } catch {
      setCopied('failed')
    }
  }

  return (
    <div className="detail">
      <LatticeGlyph id={keyPair.keyPairId} tone={glyphTone(keyPair)} size={152} className="detail-glyph" />
      <div className="detail-body">
        <dl>
          <dt>Key ID</dt>
          <dd>
            <code translate="no">{keyPair.keyPairId}</code>
          </dd>
          <dt>Picture</dt>
          <dd className="detail-hint">Drawn from the key ID to tell keys apart. It does not verify the key.</dd>
          <dt>Public key</dt>
          <dd>
            <span className="detail-hint">X.509 SubjectPublicKeyInfo, Base64. Safe to share.</span>
            <textarea
              readOnly
              rows={4}
              value={keyPair.publicKey}
              aria-label={`Public key of ${shortId(keyPair.keyPairId)}`}
            />
          </dd>
        </dl>
        <div className="detail-actions">
          <button type="button" className="button-secondary" onClick={() => copy('id', keyPair.keyPairId)}>
            Copy key ID
          </button>
          <button type="button" className="button-secondary" onClick={() => copy('key', keyPair.publicKey)}>
            Copy public key
          </button>
          <span role="status" className="detail-copied">
            {copied === 'id' && 'Key ID copied.'}
            {copied === 'key' && 'Public key copied.'}
            {copied === 'failed' && 'The browser blocked copying. Select the text and copy it instead.'}
          </span>
        </div>
        {api && <KeyActions api={api} keyPair={keyPair} onChanged={onChanged} onUnauthorized={onUnauthorized} />}
      </div>
    </div>
  )
}
