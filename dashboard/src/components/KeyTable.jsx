import { Fragment, useState } from 'react'
import { formatDate, shortId } from './format.js'

const STATUS_LABEL = { active: 'Active', rotated: 'Rotated', revoked: 'Revoked' }

export default function KeyTable({ keys }) {
  const [openId, setOpenId] = useState(null)

  return (
    <div className="table-scroll">
      <table className="keys">
        <thead>
          <tr>
            <th scope="col">Key ID</th>
            <th scope="col">Algorithm</th>
            <th scope="col">Status</th>
            <th scope="col">Created</th>
            <th scope="col">
              <span className="visually-hidden">Public key</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {keys.map((key) => {
            const open = openId === key.keyPairId
            const panelId = `public-key-${key.keyPairId}`
            return (
              <Fragment key={key.keyPairId}>
                <tr>
                  <td>
                    <code className="key-id" title={key.keyPairId}>
                      {shortId(key.keyPairId)}
                    </code>
                  </td>
                  <td>{key.algorithm}</td>
                  <td>
                    <span className={`status status-${key.status}`}>{STATUS_LABEL[key.status] ?? key.status}</span>
                  </td>
                  <td>
                    <time dateTime={key.createdAt}>{formatDate(key.createdAt)}</time>
                  </td>
                  <td className="row-action">
                    <button
                      type="button"
                      className="button-link"
                      aria-expanded={open}
                      aria-controls={panelId}
                      onClick={() => setOpenId(open ? null : key.keyPairId)}
                    >
                      {open ? 'Hide public key' : 'Show public key'}
                    </button>
                  </td>
                </tr>
                {open && (
                  <tr className="detail-row">
                    <td colSpan={5} id={panelId}>
                      <KeyDetail keyPair={key} />
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

function KeyDetail({ keyPair }) {
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
      <dl>
        <dt>Key ID</dt>
        <dd>
          <code>{keyPair.keyPairId}</code>
        </dd>
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
    </div>
  )
}
