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

describe('key actions and the audit log (KAN-33)', () => {
  // A fresh response for every call: a response body can be read only once.
  const respondEach = (status, body) => vi.fn(async () => new Response(JSON.stringify(body), { status }))
  const ID = '5d1e0f22-0000-4000-8000-000000000001'
  const call = (fetchImpl, i = 0) => {
    const [url, options] = fetchImpl.mock.calls[i]
    return { url, method: options.method, body: options.body === undefined ? undefined : JSON.parse(options.body) }
  }

  it('sends sign, verify, rotate and revoke to their endpoints', async () => {
    const fetchImpl = respondEach(200, {})
    const api = createApi(fetchImpl)
    await api.sign(ID, 'bWVzc2FnZQ==')
    await api.sign(ID, 'bWVzc2FnZQ==', 'Y3R4')
    await api.verify(ID, 'bWVzc2FnZQ==', 'c2ln')
    await api.verify(ID, 'bWVzc2FnZQ==', 'c2ln', 'Y3R4')
    await api.rotateKey(ID, 7)
    await api.revokeKey(ID)

    expect(call(fetchImpl, 0)).toEqual({ url: `/api/v1/keys/${ID}/sign`, method: 'POST', body: { message: 'bWVzc2FnZQ==' } })
    expect(call(fetchImpl, 1).body).toEqual({ message: 'bWVzc2FnZQ==', context: 'Y3R4' })
    expect(call(fetchImpl, 2)).toEqual({
      url: `/api/v1/keys/${ID}/verify`,
      method: 'POST',
      body: { message: 'bWVzc2FnZQ==', signature: 'c2ln' },
    })
    expect(call(fetchImpl, 3).body).toEqual({ message: 'bWVzc2FnZQ==', signature: 'c2ln', context: 'Y3R4' })
    expect(call(fetchImpl, 4)).toEqual({ url: `/api/v1/keys/${ID}/rotate`, method: 'POST', body: { gracePeriodDays: 7 } })
    expect(call(fetchImpl, 5)).toEqual({ url: `/api/v1/keys/${ID}/revoke`, method: 'POST', body: undefined })
  })

  it('escapes a key pair id so it can never change the path', async () => {
    const fetchImpl = respond(200, {})
    await createApi(fetchImpl).revokeKey('../api-keys?x=1')
    expect(call(fetchImpl).url).toBe('/api/v1/keys/..%2Fapi-keys%3Fx%3D1/revoke')
  })

  it('builds the audit log query from the filters that are set', async () => {
    const fetchImpl = respondEach(200, { entries: [], nextBefore: null })
    const api = createApi(fetchImpl)
    await api.auditLog()
    await api.auditLog({ keyPairId: ID, action: 'sign', status: 'failure', before: '42', limit: 25 })
    await api.auditLog({ action: 'a&limit=1' })

    expect(call(fetchImpl, 0)).toEqual({ url: '/api/v1/audit-logs', method: 'GET', body: undefined })
    expect(call(fetchImpl, 1).url).toBe(`/api/v1/audit-logs?limit=25&keyPairId=${ID}&action=sign&status=failure&before=42`)
    expect(call(fetchImpl, 2).url).toBe('/api/v1/audit-logs?action=a%26limit%3D1')
  })

  it('explains the errors the new calls can give', () => {
    expect(describeError({ code: 'key_not_active' })).toMatch(/no longer allows that/)
    expect(describeError({ code: 'wrong_key_type' })).toMatch(/ML-DSA-65/)
    expect(describeError({ code: 'key_not_found' })).toMatch(/no such key pair/)
    expect(describeError({ code: 'operation_failed' })).toMatch(/nothing was changed/)
    expect(describeError({ code: 'payload_too_large' })).toMatch(/32 KiB/)
    expect(describeError({ code: 'invalid_request' })).toMatch(/malformed/)
  })
})
