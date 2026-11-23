import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApi, describeError } from './client.js'

function respond(status, body) {
  return vi.fn().mockResolvedValue(
    new Response(body === undefined ? '' : typeof body === 'string' ? body : JSON.stringify(body), { status }),
  )
}

describe('createApi', () => {
  it('lists keys from GET /api/v1/keys', async () => {
    const fetchImpl = respond(200, [{ keyPairId: 'a' }])

    await expect(createApi(fetchImpl).listKeys()).resolves.toEqual([{ keyPairId: 'a' }])
    expect(fetchImpl).toHaveBeenCalledWith('/api/v1/keys', expect.objectContaining({ method: 'GET' }))
  })

  it('posts the chosen algorithm as JSON to generate a key', async () => {
    const fetchImpl = respond(201, { keyPairId: 'b', algorithm: 'ML-DSA-65' })

    await createApi(fetchImpl).generateKey('ML-DSA-65')

    const [url, options] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/v1/keys/generate')
    expect(options.method).toBe('POST')
    expect(options.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(options.body)).toEqual({ algorithm: 'ML-DSA-65' })
  })

  it('passes on the error code the API sends', async () => {
    const error = await createApi(respond(400, { error: 'unsupported_algorithm' }))
      .generateKey('RSA')
      .catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.code).toBe('unsupported_algorithm')
  })

  it('reports an unreachable backend when the request fails or the proxy answers instead', async () => {
    const failing = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(createApi(failing).listKeys()).rejects.toMatchObject({ code: 'api_unreachable' })
    await expect(createApi(respond(502, 'Bad Gateway')).listKeys()).rejects.toMatchObject({ code: 'api_unreachable' })
    await expect(createApi(respond(500)).listKeys()).rejects.toMatchObject({ code: 'api_unreachable' })
  })

  it('keeps a 5xx error code from the API itself', async () => {
    await expect(createApi(respond(503, { error: 'service_unavailable' })).listKeys()).rejects.toMatchObject({
      code: 'service_unavailable',
    })
  })

  it('sends the API key in the X-API-Key header and never in the URL', async () => {
    const key = 'ql_test_' + 'A'.repeat(43)
    const fetchImpl = respond(200, [])

    await createApi(fetchImpl, '/api/v1', () => key).listKeys()

    const [url, options] = fetchImpl.mock.calls[0]
    expect(options.headers['X-API-Key']).toBe(key)
    expect(url).not.toContain(key)
  })

  it('sends no key header when there is no key', async () => {
    const fetchImpl = respond(200, [])

    await createApi(fetchImpl).listKeys()

    expect(fetchImpl.mock.calls[0][1].headers).not.toHaveProperty('X-API-Key')
  })

  it('reports a refused key as unauthorized', async () => {
    await expect(createApi(respond(401, { error: 'unauthorized' })).listKeys()).rejects.toMatchObject({
      status: 401,
      code: 'unauthorized',
    })
  })
})

describe('describeError', () => {
  it('tells the user what to do for each known error', () => {
    expect(describeError({ code: 'api_unreachable' })).toMatch(/Start the backend/)
    expect(describeError({ code: 'unauthorized' })).toMatch(/did not accept this API key/)
    expect(describeError({ code: 'key_generation_failed' })).toMatch(/nothing was saved/)
    expect(describeError({ code: 'something_new' })).toMatch(/something_new/)
  })

  it('says how long to wait when the API is rate limiting', () => {
    expect(describeError({ code: 'rate_limited', retryAfterSeconds: 42 })).toMatch(/Try again in 42 seconds/)
    expect(describeError({ code: 'rate_limited', retryAfterSeconds: 1 })).toMatch(/in 1 second\./)
    expect(describeError({ code: 'rate_limited', retryAfterSeconds: null })).toMatch(/in a minute/)
  })
})

describe('rate limiting', () => {
  it('keeps the Retry-After seconds from a 429', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: 'rate_limited' }), { status: 429, headers: { 'Retry-After': '17' } }),
    )
    const error = await createApi(fetchImpl).listKeys().catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(429)
    expect(error.code).toBe('rate_limited')
    expect(error.retryAfterSeconds).toBe(17)
  })

  it('ignores a Retry-After that is not whole seconds', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: 'rate_limited' }), {
        status: 429,
        headers: { 'Retry-After': 'Wed, 21 Oct 2015 07:28:00 GMT' },
      }),
    )
    const error = await createApi(fetchImpl).listKeys().catch((e) => e)
    expect(error.retryAfterSeconds).toBeNull()
  })
})
