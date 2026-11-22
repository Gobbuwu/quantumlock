/**
 * Talks to the QuantumLock backend. The dashboard calls /api/v1/... on its own address and
 * Vite forwards those calls to the backend (see vite.config.js). Every call carries the API key
 * in the X-API-Key header, never in the URL.
 */

export class ApiError extends Error {
  constructor(status, code) {
    super(code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
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
      if (data && typeof data.error === 'string') throw new ApiError(response.status, data.error)
      // No QuantumLock error body: the proxy answered because the backend is not running.
      if (response.status >= 500) throw new ApiError(response.status, 'api_unreachable')
      throw new ApiError(response.status, 'unexpected_response')
    }
    return data
  }

  return {
    listKeys: () => request('/keys'),
    generateKey: (algorithm) => request('/keys/generate', { method: 'POST', body: { algorithm } }),
  }
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
    case 'invalid_request':
      return 'The API rejected the request as incomplete. Reload the page and try again.'
    default:
      return `The API returned an error (${error?.code ?? 'unknown'}).`
  }
}
