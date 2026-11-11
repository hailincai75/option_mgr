import type { ApiErrorResponse, ApiFieldError } from './types'

/** Client-side codes used when the request never produced a contract-shaped error body. */
export type ClientErrorCode = 'NETWORK_ERROR' | 'INVALID_RESPONSE'

/**
 * Error thrown by every API call. When the backend returned the contract's unified error body,
 * `code` / `message` / `details` / `path` come from it; otherwise the client fills them in
 * (`NETWORK_ERROR` with status 0, or `INVALID_RESPONSE`).
 */
export class ApiError extends Error {
  readonly code: string
  /** HTTP status; 0 when the request did not reach the server. */
  readonly status: number
  readonly details: ApiFieldError[]
  readonly path: string | null

  constructor(params: {
    code: string
    message: string
    status: number
    details?: ApiFieldError[]
    path?: string | null
    cause?: unknown
  }) {
    super(params.message, { cause: params.cause })
    this.name = 'ApiError'
    this.code = params.code
    this.status = params.status
    this.details = params.details ?? []
    this.path = params.path ?? null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isFieldError(value: unknown): value is ApiFieldError {
  return isRecord(value) && typeof value.field === 'string' && typeof value.message === 'string'
}

export function isApiErrorResponse(value: unknown): value is ApiErrorResponse {
  return (
    isRecord(value) &&
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    typeof value.status === 'number' &&
    Array.isArray(value.details) &&
    value.details.every(isFieldError)
  )
}

/** Returns parsed JSON, `null` for an empty body, or `undefined` when the body is not JSON. */
async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text === '') return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** Serialized as JSON. Send money values as strings (see API contract). */
  body?: unknown
  signal?: AbortSignal
}

/**
 * Calls a backend JSON endpoint. `path` should start with `/api` (proxied to the backend in dev).
 * Resolves with the parsed body, typed by the caller per the contract, or throws `ApiError`.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal } = options
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError({
      code: 'NETWORK_ERROR',
      message: 'Unable to reach the server',
      status: 0,
      cause,
    })
  }

  const data = await readJson(response)

  if (!response.ok) {
    if (isApiErrorResponse(data)) {
      throw new ApiError({
        code: data.code,
        message: data.message,
        status: data.status,
        details: data.details,
        path: data.path,
      })
    }
    throw new ApiError({
      code: 'INVALID_RESPONSE',
      message: `Request failed with HTTP ${response.status}`,
      status: response.status,
    })
  }

  if (data === undefined) {
    throw new ApiError({
      code: 'INVALID_RESPONSE',
      message: 'Server returned a response that is not valid JSON',
      status: response.status,
    })
  }

  return data as T
}
