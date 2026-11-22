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
    await expect(createApi(respond(503, { error: 'demo_account_missing' })).listKeys()).rejects.toMatchObject({
      code: 'demo_account_missing',
    })
  })
})

describe('describeError', () => {
  it('tells the user what to do for each known error', () => {
    expect(describeError({ code: 'api_unreachable' })).toMatch(/Start the backend/)
    expect(describeError({ code: 'demo_account_missing' })).toMatch(/seed\.sql/)
    expect(describeError({ code: 'key_generation_failed' })).toMatch(/nothing was saved/)
    expect(describeError({ code: 'something_new' })).toMatch(/something_new/)
  })
})
