import { useId, useState } from 'react'
import { describeError } from '../api/client.js'
import { isBase64, utf8Length, utf8ToBase64 } from './encoding.js'
import { formatDate, shortId } from './format.js'
import {
  allowedActions,
  DEFAULT_GRACE_DAYS,
  MAX_CONTEXT_BYTES,
  MAX_GRACE_DAYS,
  MAX_MESSAGE_BYTES,
} from './keyRules.js'

/**
 * What can be done with one key pair (KAN-33): sign and verify (ML-DSA-65), rotate and revoke. Only the actions
 * its state allows are shown. Messages and signatures live in this component's state only: they are never
 * stored, logged or put in the address.
 *
 * onChanged(replacementId) runs after a rotation or revocation, so the list can reload.
 */
export default function KeyActions({ api, keyPair, onChanged, onUnauthorized }) {
  const allowed = allowedActions(keyPair)
  const [open, setOpen] = useState(null) // sign | verify | rotate | revoke

  const tabs = [
    allowed.sign && ['sign', 'Sign'],
    allowed.verify && ['verify', 'Verify'],
    allowed.rotate && ['rotate', 'Rotate'],
    allowed.revoke && ['revoke', 'Revoke'],
  ].filter(Boolean)

  const props = { api, keyPair, onUnauthorized }
  return (
    <div className="key-actions">
      {keyPair.status === 'rotated' && <RotatedNote keyPair={keyPair} inGrace={allowed.inGrace} />}
      {tabs.length > 0 ? (
        <div className="action-tabs" role="group" aria-label={`Actions for ${shortId(keyPair.keyPairId)}`}>
          {tabs.map(([name, label]) => (
            <button
              key={name}
              type="button"
              className={name === 'revoke' ? 'button-secondary action-tab action-danger' : 'button-secondary action-tab'}
              aria-pressed={open === name}
              onClick={() => setOpen(open === name ? null : name)}
            >
              {label}
            </button>
          ))}
          <a className="button-link" href={`#audit?keyPair=${keyPair.keyPairId}`}>
            View audit log
          </a>
        </div>
      ) : (
        <p className="detail-hint">
          This key pair is revoked and can no longer be used.{' '}
          <a className="button-link" href={`#audit?keyPair=${keyPair.keyPairId}`}>
            View audit log
          </a>
        </p>
      )}
      {open === 'sign' && allowed.sign && <SignForm {...props} />}
      {open === 'verify' && allowed.verify && <VerifyForm {...props} />}
      {open === 'rotate' && allowed.rotate && <RotateForm {...props} onChanged={onChanged} />}
      {open === 'revoke' && allowed.revoke && (
        <RevokeConfirm {...props} onChanged={onChanged} onCancel={() => setOpen(null)} />
      )}
    </div>
  )
}

function RotatedNote({ keyPair, inGrace }) {
  const until = formatDate(keyPair.gracePeriodEndsAt)
  return (
    <p className="detail-hint">
      Rotated{keyPair.replacedBy ? `, replaced by ${shortId(keyPair.replacedBy)}` : ''}.{' '}
      {inGrace
        ? `It can still ${keyPair.algorithm === 'ML-DSA-65' ? 'verify' : 'decapsulate'} until ${until}.`
        : `Its grace period ended ${until}.`}
    </p>
  )
}

/** Runs an API call with the busy flag and turns failures into a sentence. */
function useCall(onUnauthorized) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  async function run(call) {
    setBusy(true)
    setError(null)
    try {
      return await call()
    } catch (e) {
      if (e?.code === 'unauthorized' && onUnauthorized) {
        onUnauthorized()
        return undefined
      }
      setError(describeError(e))
      return undefined
    } finally {
      setBusy(false)
    }
  }
  return { busy, error, setError, run }
}

/** Checks message and context sizes the way the API does, so the user hears about it before sending. */
function checkText(message, context) {
  const size = utf8Length(message)
  if (size === 0) return 'Enter a message.'
  if (size > MAX_MESSAGE_BYTES) return `The message is ${size} bytes. The most is ${MAX_MESSAGE_BYTES} (32 KiB).`
  if (utf8Length(context) > MAX_CONTEXT_BYTES) return `The context can be at most ${MAX_CONTEXT_BYTES} bytes.`
  return null
}

function MessageFields({ message, setMessage, context, setContext, disabled }) {
  const id = useId()
  return (
    <>
      <div className="field">
        <label htmlFor={`${id}-message`}>Message</label>
        <textarea
          id={`${id}-message`}
          aria-describedby={`${id}-message-hint`}
          rows={3}
          value={message}
          disabled={disabled}
          onChange={(e) => setMessage(e.target.value)}
          spellCheck={false}
        />
        <span id={`${id}-message-hint`} className="field-hint">Sent as UTF-8 text, 1 byte to 32 KiB.</span>
      </div>
      <div className="field">
        <label htmlFor={`${id}-context`}>Context (optional)</label>
        <input
          id={`${id}-context`}
          aria-describedby={`${id}-context-hint`}
          type="text"
          value={context}
          disabled={disabled}
          onChange={(e) => setContext(e.target.value)}
          spellCheck={false}
          autoComplete="off"
        />
        <span id={`${id}-context-hint`} className="field-hint">Ties the signature to one purpose, such as invoices. It must match to verify.</span>
      </div>
    </>
  )
}

function SignForm({ api, keyPair, onUnauthorized }) {
  const [message, setMessage] = useState('')
  const [context, setContext] = useState('')
  const [signature, setSignature] = useState(null)
  const [copied, setCopied] = useState(null)
  const { busy, error, setError, run } = useCall(onUnauthorized)

  async function submit(event) {
    event.preventDefault()
    setSignature(null)
    setCopied(null)
    const problem = checkText(message, context)
    if (problem) {
      setError(problem)
      return
    }
    const result = await run(() =>
      api.sign(keyPair.keyPairId, utf8ToBase64(message), context ? utf8ToBase64(context) : undefined),
    )
    if (result) setSignature(result.signature)
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(signature)
      setCopied('Signature copied.')
    } catch {
      setCopied('The browser blocked copying. Select the text and copy it instead.')
    }
  }

  return (
    <form className="action-form" onSubmit={submit} aria-label="Sign a message">
      <MessageFields {...{ message, setMessage, context, setContext }} disabled={busy} />
      <button type="submit" className="button-primary" disabled={busy}>
        {busy ? 'Signing…' : 'Sign message'}
      </button>
      {error && (
        <p className="problem" role="alert">
          {error}
        </p>
      )}
      {signature && (
        <div className="action-result">
          <p role="status">Signed. ML-DSA-65 signature, 3309 bytes, Base64:</p>
          <textarea readOnly rows={4} value={signature} aria-label="Signature" />
          <div className="detail-actions">
            <button type="button" className="button-secondary" onClick={copy}>
              Copy signature
            </button>
            <span role="status" className="detail-copied">
              {copied}
            </span>
          </div>
        </div>
      )}
    </form>
  )
}

function VerifyForm({ api, keyPair, onUnauthorized }) {
  const id = useId()
  const [message, setMessage] = useState('')
  const [context, setContext] = useState('')
  const [signature, setSignature] = useState('')
  const [valid, setValid] = useState(null)
  const { busy, error, setError, run } = useCall(onUnauthorized)

  async function submit(event) {
    event.preventDefault()
    setValid(null)
    const pasted = signature.replace(/\s+/g, '')
    const problem = checkText(message, context) ?? (isBase64(pasted) ? null : 'Paste the signature as Base64.')
    if (problem) {
      setError(problem)
      return
    }
    const result = await run(() =>
      api.verify(keyPair.keyPairId, utf8ToBase64(message), pasted, context ? utf8ToBase64(context) : undefined),
    )
    if (result) setValid(result.valid === true)
  }

  return (
    <form className="action-form" onSubmit={submit} aria-label="Verify a signature">
      <MessageFields {...{ message, setMessage, context, setContext }} disabled={busy} />
      <div className="field">
        <label htmlFor={`${id}-signature`}>Signature</label>
        <textarea
          id={`${id}-signature`}
          className="mono"
          aria-describedby={`${id}-signature-hint`}
          rows={3}
          value={signature}
          disabled={busy}
          onChange={(e) => {
            setSignature(e.target.value)
            setValid(null)
          }}
          spellCheck={false}
        />
        <span id={`${id}-signature-hint`} className="field-hint">Base64, as Sign gives it.</span>
      </div>
      <button type="submit" className="button-primary" disabled={busy}>
        {busy ? 'Verifying…' : 'Verify signature'}
      </button>
      {error && (
        <p className="problem" role="alert">
          {error}
        </p>
      )}
      {valid !== null && (
        <p role="status" className={valid ? 'verdict verdict-valid' : 'verdict verdict-invalid'}>
          {valid
            ? 'Valid. This key pair signed exactly this message and context.'
            : 'Not valid. The message, context or signature differs from what this key pair signed.'}
        </p>
      )}
    </form>
  )
}

function RotateForm({ api, keyPair, onChanged, onUnauthorized }) {
  const id = useId()
  const [days, setDays] = useState(String(DEFAULT_GRACE_DAYS))
  const { busy, error, setError, run } = useCall(onUnauthorized)

  async function submit(event) {
    event.preventDefault()
    if (!/^\d{1,2}$/.test(days) || Number(days) > MAX_GRACE_DAYS) {
      setError(`Enter a whole number of days from 0 to ${MAX_GRACE_DAYS}.`)
      return
    }
    const replacement = await run(() => api.rotateKey(keyPair.keyPairId, Number(days)))
    if (replacement) onChanged(replacement.keyPairId)
  }

  const use = keyPair.algorithm === 'ML-DSA-65' ? 'verify signatures' : 'decapsulate'
  return (
    <form className="action-form" onSubmit={submit} aria-label="Rotate this key pair">
      <p className="detail-hint">
        Makes a new {keyPair.algorithm} key pair to use from now on. This one stops signing and encapsulating at once,
        and can still {use} during the grace period, so data made for it can still be read.
      </p>
      <div className="field field-narrow">
        <label htmlFor={`${id}-days`}>Grace period in days</label>
        <input
          id={`${id}-days`}
          aria-describedby={`${id}-days-hint`}
          type="text"
          inputMode="numeric"
          value={days}
          disabled={busy}
          onChange={(e) => setDays(e.target.value.trim())}
          autoComplete="off"
        />
        <span id={`${id}-days-hint`} className="field-hint">0 to {MAX_GRACE_DAYS}. 0 ends this key pair&apos;s use at once.</span>
      </div>
      <button type="submit" className="button-primary" disabled={busy}>
        {busy ? 'Rotating…' : 'Rotate key pair'}
      </button>
      {error && (
        <p className="problem" role="alert">
          {error}
        </p>
      )}
    </form>
  )
}

function RevokeConfirm({ api, keyPair, onChanged, onCancel, onUnauthorized }) {
  const { busy, error, run } = useCall(onUnauthorized)

  async function revoke() {
    const revoked = await run(() => api.revokeKey(keyPair.keyPairId))
    if (revoked) onChanged(null)
  }

  return (
    <div className="action-form revoke-confirm" role="group" aria-label="Revoke this key pair">
      <p>
        Revoke <code translate="no">{shortId(keyPair.keyPairId)}</code> for good? It can no longer be used for
        anything, and this cannot be undone. It stays in the list and the audit log for the record.
      </p>
      <div className="detail-actions">
        <button type="button" className="button-danger" onClick={revoke} disabled={busy}>
          {busy ? 'Revoking…' : 'Revoke for good'}
        </button>
        <button type="button" className="button-secondary" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
      {error && (
        <p className="problem" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
