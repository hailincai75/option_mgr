import { jsonResponse, mockFetch } from '../test/render'
import {
  ApiError,
  apiRequest,
  getApiAccountId,
  setAccountNotFoundHandler,
  setApiAccountId,
} from './client'
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
  beforeEach(() => {
    setApiAccountId(1)
  })

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
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-Account-Id': '1',
        },
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

describe('X-Account-Id (FEAT-033)', () => {
  function headersOf(fetchMock: ReturnType<typeof mockFetch>): Record<string, string> {
    const init = fetchMock.mock.calls.at(-1)?.[1]
    return (init?.headers ?? {}) as Record<string, string>
  }

  it('adds the current account to account-scoped requests', async () => {
    const fetchMock = mockFetch()
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({})))
    setApiAccountId(2)

    await apiRequest('/api/cash/summary')
    expect(headersOf(fetchMock)['X-Account-Id']).toBe('2')

    await apiRequest('/api/option-trades/7', { method: 'PUT', body: {} })
    expect(headersOf(fetchMock)['X-Account-Id']).toBe('2')
    expect(getApiAccountId()).toBe(2)
  })

  it.each(['/api/health', '/api/accounts', '/api/accounts/2', '/api/test-support/reset'])(
    'does not send it to %s',
    async (path) => {
      const fetchMock = mockFetch({ accounts: false })
      fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({})))
      setApiAccountId(2)

      await apiRequest(path)
      expect(headersOf(fetchMock)).not.toHaveProperty('X-Account-Id')
    },
  )

  it('sends nothing without an account and fails with a Chinese message', async () => {
    const fetchMock = mockFetch()
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({})))
    setApiAccountId(null)

    const error = await catchError(apiRequest('/api/cash/summary'))
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code: 'ACCOUNT_REQUIRED', message: '账户未就绪，请重试' })
    expect(fetchMock).not.toHaveBeenCalled()

    // Account-free endpoints still work without an account.
    await apiRequest('/api/health')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows backend account errors in Chinese without the English text or details', async () => {
    setApiAccountId(1)
    const fetchMock = mockFetch()
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        jsonResponse(
          {
            code: 'ACCOUNT_REQUIRED',
            message: 'Account required',
            status: 400,
            path: '/api/cash/summary',
            timestamp: '2026-11-12T15:00:00Z',
            details: [{ field: 'X-Account-Id', message: '请指定账户' }],
          },
          400,
        ),
      ),
    )
    const error = await catchError(apiRequest('/api/cash/summary'))
    expect(error).toMatchObject({
      code: 'ACCOUNT_REQUIRED',
      message: '账户未就绪，请重试',
      details: [],
    })
  })

  it('reports ACCOUNT_NOT_FOUND in Chinese and notifies the account handler', async () => {
    setApiAccountId(9)
    const handler = vi.fn()
    setAccountNotFoundHandler(handler)
    const fetchMock = mockFetch()
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        jsonResponse(
          {
            code: 'ACCOUNT_NOT_FOUND',
            message: 'Account 9 not found',
            status: 404,
            path: '/api/cash/summary',
            timestamp: '2026-11-12T15:00:00Z',
            details: [],
          },
          404,
        ),
      ),
    )
    const error = await catchError(apiRequest('/api/cash/summary'))
    expect(error).toMatchObject({
      code: 'ACCOUNT_NOT_FOUND',
      message: '当前账户不存在，已切换到 ID 最小的账户',
    })
    expect(handler).toHaveBeenCalledTimes(1)
    setAccountNotFoundHandler(null)
  })
})
