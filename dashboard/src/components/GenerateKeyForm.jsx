import { useState } from 'react'
import { describeError } from '../api/client.js'
import { ALGORITHMS } from './algorithms.js'
import { shortId } from './format.js'

export default function GenerateKeyForm({ api, onGenerated }) {
  const [algorithm, setAlgorithm] = useState(ALGORITHMS[0].name)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null) // { ok: true, key } | { ok: false, message }

  async function handleSubmit(event) {
    event.preventDefault()
    setBusy(true)
    setResult(null)
    try {
      const key = await api.generateKey(algorithm)
      onGenerated(key)
      setResult({ ok: true, key })
    } catch (error) {
      setResult({ ok: false, message: describeError(error) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="generate" onSubmit={handleSubmit} aria-labelledby="generate-title">
      <h2 id="generate-title">Generate a key pair</h2>
      <fieldset className="algorithm-choice" disabled={busy}>
        <legend>Algorithm</legend>
        {ALGORITHMS.map((option) => (
          <label key={option.name} className="algorithm-option">
            <input
              type="radio"
              name="algorithm"
              value={option.name}
              checked={algorithm === option.name}
              onChange={() => setAlgorithm(option.name)}
            />
            <span className="algorithm-name">{option.name}</span>
            <span className="algorithm-use">{option.use}</span>
            <span className="algorithm-detail">{option.detail}</span>
          </label>
        ))}
      </fieldset>
      <div className="generate-actions">
        <button type="submit" className="button-primary" disabled={busy}>
          {busy ? 'Generating…' : 'Generate key pair'}
        </button>
        <p className="generate-result" role="status" aria-live="polite">
          {result?.ok &&
            `Generated ${result.key.algorithm} key pair ${shortId(result.key.keyPairId)}. It is at the top of the list.`}
        </p>
      </div>
      {result && !result.ok && (
        <p className="problem" role="alert">
          {result.message}
        </p>
      )}
    </form>
  )
}
