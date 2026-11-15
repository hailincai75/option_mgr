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
  /**
   * The parsed contract error body, when there was one (`undefined` otherwise). Lets callers read
   * endpoint-specific extension fields, e.g. FEAT-015 `failures` of `BATCH_EXPIRATION_REJECTED`.
   */
  readonly body: unknown

  constructor(params: {
    code: string
    message: string
    status: number
    details?: ApiFieldError[]
    path?: string | null
    body?: unknown
    cause?: unknown
  }) {
    super(params.message, { cause: params.cause })
    this.name = 'ApiError'
    this.code = params.code
    this.status = params.status
    this.details = params.details ?? []
    this.path = params.path ?? null
    this.body = params.body
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

/** FEAT-033: request header naming the account every business endpoint reads / writes. */
export const ACCOUNT_HEADER = 'X-Account-Id'

/** Endpoints that are not account-scoped (the header is not sent to them). */
const ACCOUNT_FREE_PREFIXES = [
  '/api/health',
  '/api/accounts',
  '/api/test-support',
  // FEAT-009: one calendar for the whole system (Q53); usable before an account is resolved.
  '/api/trading-calendar',
  // FEAT-016: one set of quotes and manual overrides for the whole system (rule 1).
  '/api/market-data',
] as const

/** FEAT-035: `X-Account-Id` value for 全部账户 (read-only; accepted by 7 GETs only). */
export const ALL_ACCOUNTS = 'ALL'

/** FEAT-035: the current account scope — one account id, or every account (`ALL`, read-only). */
export type AccountScope = number | typeof ALL_ACCOUNTS

let currentAccountId: AccountScope | null = null

/**
 * Sets the account sent as `X-Account-Id` on every account-scoped request. Called by the account
 * context when the current account is resolved or switched; `null` sends no header (the backend
 * then rejects the request with ACCOUNT_REQUIRED, so nothing is ever written to a guessed account).
 * FEAT-035: `ALL` sends `X-Account-Id: ALL`; the backend rejects writes with ALL_ACCOUNTS_READ_ONLY.
 */
export function setApiAccountId(accountId: AccountScope | null): void {
  currentAccountId = accountId
}

export function getApiAccountId(): AccountScope | null {
  return currentAccountId
}

/** User-facing (Chinese) messages for account errors; the backend's English text is not shown. */
export const ACCOUNT_ERROR_MESSAGES = {
  ACCOUNT_REQUIRED: '账户未就绪，请重试',
  ACCOUNT_NOT_FOUND: '当前账户不存在，已切换到 ID 最小的账户',
} as const

let accountNotFoundHandler: (() => void) | null = null

/** Called when the backend says the current account does not exist (e.g. deleted later). */
export function setAccountNotFoundHandler(handler: (() => void) | null): void {
  accountNotFoundHandler = handler
}

function isAccountScoped(path: string): boolean {
  return !ACCOUNT_FREE_PREFIXES.some(
    (prefix) => path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`),
  )
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
  if (currentAccountId === null && isAccountScoped(path)) {
    // Never send an account-scoped request without an account (FEAT-033): it could not succeed,
    // and a write must never land in a guessed account.
    throw new ApiError({
      code: 'ACCOUNT_REQUIRED',
      message: ACCOUNT_ERROR_MESSAGES.ACCOUNT_REQUIRED,
      status: 0,
      path,
    })
  }
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (currentAccountId !== null && isAccountScoped(path)) {
    headers[ACCOUNT_HEADER] = String(currentAccountId)
  }

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
    if (
      isApiErrorResponse(data) &&
      (data.code === 'ACCOUNT_REQUIRED' || data.code === 'ACCOUNT_NOT_FOUND')
    ) {
      if (data.code === 'ACCOUNT_NOT_FOUND') accountNotFoundHandler?.()
      throw new ApiError({
        code: data.code,
        message:
          data.code === 'ACCOUNT_NOT_FOUND'
            ? ACCOUNT_ERROR_MESSAGES.ACCOUNT_NOT_FOUND
            : ACCOUNT_ERROR_MESSAGES.ACCOUNT_REQUIRED,
        status: data.status,
        path: data.path,
      })
    }
    if (isApiErrorResponse(data)) {
      throw new ApiError({
        code: data.code,
        message: data.message,
        status: data.status,
        details: data.details,
        path: data.path,
        body: data,
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
