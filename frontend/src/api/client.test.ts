import { jsonResponse, mockFetch } from '../test/render'
import { ApiError, apiRequest } from './client'
import type { ApiErrorResponse } from './types'

const validationError: ApiErrorResponse = {
  code: 'VALIDATION_ERROR',
  message: 'Request validation failed',
  status: 400,
  path: '/api/trades',
  timestamp: '2026-11-10T14:30:00.123Z',
  details: [{ field: 'price', message: 'must be greater than 0' }],
}

async function catchError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise
  } catch (error) {
    return error
  }
  throw new Error('Expected the promise to reject')
}

describe('apiRequest', () => {
  it('returns the parsed JSON body on success', async () => {
    mockFetch().mockResolvedValue(jsonResponse({ status: 'UP' }))
    await expect(apiRequest('/api/health')).resolves.toEqual({ status: 'UP' })
  })

  it('sends a JSON body with Content-Type when body is given', async () => {
    const fetchMock = mockFetch()
    fetchMock.mockResolvedValue(jsonResponse({}))

    await apiRequest('/api/things', { method: 'POST', body: { price: '1.50' } })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/things',
      expect.objectContaining({
        method: 'POST',
        body: '{"price":"1.50"}',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      }),
    )
  })

  it('throws ApiError carrying the contract error body', async () => {
    mockFetch().mockResolvedValue(jsonResponse(validationError, 400))

    const error = await catchError(apiRequest('/api/trades'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      code: 'VALIDATION_ERROR',
      message: 'Request validation failed',
      status: 400,
      path: '/api/trades',
      details: [{ field: 'price', message: 'must be greater than 0' }],
    })
  })

  it('throws INVALID_RESPONSE when an error body is not in the contract format', async () => {
    mockFetch().mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502 }))

    await expect(apiRequest('/api/health')).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      status: 502,
      details: [],
    })
  })

  it('throws INVALID_RESPONSE when a success body is not JSON', async () => {
    mockFetch().mockResolvedValue(new Response('<html></html>', { status: 200 }))

    await expect(apiRequest('/api/health')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('throws NETWORK_ERROR with status 0 when fetch fails', async () => {
    mockFetch().mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(apiRequest('/api/health')).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      status: 0,
    })
  })
})
