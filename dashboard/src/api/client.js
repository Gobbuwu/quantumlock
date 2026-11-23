/**
 * Talks to the QuantumLock backend. The dashboard calls /api/v1/... on its own address and
 * Vite forwards those calls to the backend (see vite.config.js). Every call carries the API key
 * in the X-API-Key header, never in the URL.
 */

export class ApiError extends Error {
  constructor(status, code, retryAfterSeconds = null) {
    super(code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
  }
}

export function createApi(fetchImpl = (...args) => fetch(...args), baseUrl = '/api/v1', getApiKey = () => null) {
  async function request(path, { method = 'GET', body } = {}) {
    const headers = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const apiKey = getApiKey()
    if (apiKey) headers['X-API-Key'] = apiKey

    let response
    try {
      response = await fetchImpl(baseUrl + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new ApiError(0, 'api_unreachable')
    }

    const text = await response.text()
    let data = null
    if (text) {
      try {
        data = JSON.parse(text)
      } catch {
        data = null
      }
    }

    if (!response.ok) {
      if (data && typeof data.error === 'string') {
        throw new ApiError(response.status, data.error, retryAfter(response))
      }
      // No QuantumLock error body: the proxy answered because the backend is not running.
      if (response.status >= 500) throw new ApiError(response.status, 'api_unreachable')
      throw new ApiError(response.status, 'unexpected_response')
    }
    return data
  }

  const key = (id) => `/keys/${encodeURIComponent(id)}`

  return {
    listKeys: () => request('/keys'),
    generateKey: (algorithm) => request('/keys/generate', { method: 'POST', body: { algorithm } }),
    // message, signature and context are Base64. An empty context is left out, which the API treats the same.
    sign: (id, message, context) =>
      request(`${key(id)}/sign`, { method: 'POST', body: context ? { message, context } : { message } }),
    verify: (id, message, signature, context) =>
      request(`${key(id)}/verify`, {
        method: 'POST',
        body: context ? { message, signature, context } : { message, signature },
      }),
    rotateKey: (id, gracePeriodDays) => request(`${key(id)}/rotate`, { method: 'POST', body: { gracePeriodDays } }),
    revokeKey: (id) => request(`${key(id)}/revoke`, { method: 'POST' }),
    auditLog: ({ keyPairId, action, status, before, limit } = {}) => {
      const query = new URLSearchParams()
      if (limit) query.set('limit', String(limit))
      if (keyPairId) query.set('keyPairId', keyPairId)
      if (action) query.set('action', action)
      if (status) query.set('status', status)
      if (before) query.set('before', before)
      const text = query.toString()
      return request(text ? `/audit-logs?${text}` : '/audit-logs')
    },
  }
}

/** Seconds from a Retry-After header (the backend sends whole seconds), or null. */
function retryAfter(response) {
  const value = response.headers?.get?.('Retry-After')
  return value && /^\d{1,6}$/.test(value) ? Number(value) : null
}

/** Turns an error into a sentence that says what happened and what to do about it. */
export function describeError(error) {
  switch (error?.code) {
    case 'api_unreachable':
      return 'The QuantumLock API is not answering on localhost:8080. Start the backend (see the README), then try again.'
    case 'unauthorized':
      return 'The API did not accept this API key. Check that you copied all of it, or issue a new one (see the README).'
    case 'unsupported_algorithm':
      return 'That algorithm is not supported. Choose ML-KEM-1024 or ML-DSA-65.'
    case 'key_generation_failed':
      return 'The key pair could not be generated, and nothing was saved. Try again.'
    case 'rate_limited': {
      const wait = error.retryAfterSeconds
      const when = wait ? `in ${wait} second${wait === 1 ? '' : 's'}` : 'in a minute'
      return `Too many requests in the last minute. Nothing was done. Try again ${when}.`
    }
    case 'key_not_active':
      return 'This key pair no longer allows that. A rotated key pair can only verify until its grace period ends, and a revoked one nothing. Reload the list to see its state.'
    case 'wrong_key_type':
      return 'This key pair has the wrong algorithm for that. Signing and verifying need an ML-DSA-65 key pair.'
    case 'key_not_found':
      return 'The API has no such key pair for your team. Reload the list.'
    case 'operation_failed':
      return 'The server could not complete that, and nothing was changed. Try again.'
    case 'payload_too_large':
      return 'That is too much data for one request. Messages can be up to 32 KiB.'
    case 'invalid_request':
      return 'The API refused the request as malformed. Check the values you entered and try again.'
    default:
      return `The API returned an error (${error?.code ?? 'unknown'}).`
  }
}
