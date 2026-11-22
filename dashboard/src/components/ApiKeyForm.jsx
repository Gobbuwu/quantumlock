import { useState } from 'react'
import { isWellFormedApiKey } from '../api/apiKey.js'
import { describeError } from '../api/client.js'

/**
 * Asks for the team's API key and checks it with the API before showing any keys (task 4.2).
 * The input is a password field, so the key is not shown on screen or offered for autofill.
 */
export default function ApiKeyForm({ api, keyStore, onAccepted, notice }) {
  const [value, setValue] = useState('')
  const [remember, setRemember] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState(null)

  async function handleSubmit(event) {
    event.preventDefault()
    const key = value.trim()
    if (!isWellFormedApiKey(key)) {
      setProblem('That is not a QuantumLock API key. A key starts with ql_test_ and is 51 characters long.')
      return
    }
    setBusy(true)
    setProblem(null)
    keyStore.set(key, remember)
    try {
      await api.listKeys()
      setValue('')
      onAccepted()
    } catch (error) {
      keyStore.clear()
      setProblem(describeError(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="api-key" onSubmit={handleSubmit} aria-labelledby="api-key-title">
      <h2 id="api-key-title">Enter your API key</h2>
      {notice && (
        <p className="problem" role="alert">
          {notice}
        </p>
      )}
      <p className="note">
        The dashboard sends it to the API with every request. Issue one with the backend’s{' '}
        <code>issue-api-key</code> command (see the README). It is shown only once when it is issued.
      </p>
      <label className="field">
        <span>API key</span>
        <input
          type="password"
          name="api-key"
          autoComplete="off"
          spellCheck={false}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          disabled={busy}
          required
        />
      </label>
      <label className="check">
        <input type="checkbox" checked={remember} onChange={(event) => setRemember(event.target.checked)} />
        <span>Remember until this browser tab is closed</span>
      </label>
      <div className="generate-actions">
        <button type="submit" className="button-primary" disabled={busy}>
          {busy ? 'Checking…' : 'Use this key'}
        </button>
      </div>
      {problem && (
        <p className="problem" role="alert">
          {problem}
        </p>
      )}
    </form>
  )
}
