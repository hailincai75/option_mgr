import { ApiError, apiRequest } from './client'
import type {
  CreateAssignmentRequest,
  CreateCloseTradeRequest,
  CreateExerciseRequest,
  CreateExpirationBatchRequest,
  CreateExpirationAdjustmentRequest,
  CreateExpirationRequest,
  ExpirationAdjustmentResponse,
  ExpirationBatchFailure,
  ExpirationBatchResponse,
  CreateOptionTradeRequest,
  OptionTrade,
  OptionTradeListParams,
  OptionTradePage,
  UpdateOptionTradeRequest,
} from './types'

/**
 * Query keys for option trades. Creating a trade changes cash and the trade list →
 * invalidate `["cash"]` and `optionTradeQueryKeys.lists`.
 */
export const optionTradeQueryKeys = {
  all: ['option-trades'] as const,
  lists: ['option-trades', 'list'] as const,
  list: (params: OptionTradeListParams) => ['option-trades', 'list', params] as const,
  detail: (id: number) => ['option-trades', id] as const,
}

/** `POST /api/option-trades` → 201 (opening trade, BTO / STO). */
export function createOptionTrade(request: CreateOptionTradeRequest): Promise<OptionTrade> {
  return apiRequest<OptionTrade>('/api/option-trades', { method: 'POST', body: request })
}

/** `POST /api/option-trades/closes` → 201 (closing trade, BTC / STC; FEAT-004). */
export function createCloseTrade(request: CreateCloseTradeRequest): Promise<OptionTrade> {
  return apiRequest<OptionTrade>('/api/option-trades/closes', { method: 'POST', body: request })
}

/**
 * `POST /api/option-trades/expirations` → 201 (FEAT-012 到期作废: every remaining lot of the
 * contract at the expiration date 16:00 NY; returns the EXP record).
 */
export function createExpiration(request: CreateExpirationRequest): Promise<OptionTrade> {
  return apiRequest<OptionTrade>('/api/option-trades/expirations', {
    method: 'POST',
    body: request,
  })
}

/**
 * `POST /api/option-trades/expirations/batch` → 201 (FEAT-015 批量作废: all items expire or nothing
 * is saved). A 422 `BATCH_EXPIRATION_REJECTED` carries `failures[]`; read them with
 * `expirationBatchFailures(error)`.
 */
export function createExpirationBatch(
  request: CreateExpirationBatchRequest,
): Promise<ExpirationBatchResponse> {
  return apiRequest<ExpirationBatchResponse>('/api/option-trades/expirations/batch', {
    method: 'POST',
    body: request,
  })
}

/**
 * `POST /api/option-trades/expiration-adjustments` → 200 (FEAT-043 调整到期日: every record of the
 * contract in the current account moves to `newExpirationDate`; all or nothing).
 */
export function createExpirationAdjustment(
  request: CreateExpirationAdjustmentRequest,
): Promise<ExpirationAdjustmentResponse> {
  return apiRequest<ExpirationAdjustmentResponse>('/api/option-trades/expiration-adjustments', {
    method: 'POST',
    body: request,
  })
}

/** FEAT-015 error code whose body has the `failures` extension field. */
export const BATCH_EXPIRATION_REJECTED = 'BATCH_EXPIRATION_REJECTED'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isExpirationBatchFailure(value: unknown): value is ExpirationBatchFailure {
  return (
    isRecord(value) &&
    typeof value.index === 'number' &&
    typeof value.symbol === 'string' &&
    typeof value.expirationDate === 'string' &&
    typeof value.strike === 'string' &&
    (value.optionType === 'CALL' || value.optionType === 'PUT') &&
    typeof value.contractLabel === 'string' &&
    typeof value.code === 'string' &&
    typeof value.message === 'string'
  )
}

/**
 * The `failures[]` of a `BATCH_EXPIRATION_REJECTED` error (malformed entries dropped); `[]` for any
 * other error.
 */
export function expirationBatchFailures(error: unknown): ExpirationBatchFailure[] {
  if (!(error instanceof ApiError) || error.code !== BATCH_EXPIRATION_REJECTED) return []
  const body = error.body
  if (!isRecord(body) || !Array.isArray(body.failures)) return []
  const failures: unknown[] = body.failures
  return failures.filter(isExpirationBatchFailure)
}

/**
 * `POST /api/option-trades/assignments` → 201 (FEAT-013 被指派 of a short put: the ASG record with
 * `assignment`; also creates the ASSIGN_BUY stock record and the OPTION_ASSIGN ledger entry).
 */
export function createAssignment(request: CreateAssignmentRequest): Promise<OptionTrade> {
  return apiRequest<OptionTrade>('/api/option-trades/assignments', {
    method: 'POST',
    body: request,
  })
}

/**
 * `POST /api/option-trades/exercises` → 201 (FEAT-014 行权 of a long call / put: the EXR record with
 * `exercise`; also creates the EXERCISE_BUY / EXERCISE_SELL stock record and the OPTION_EXERCISE
 * ledger entry).
 */
export function createExercise(request: CreateExerciseRequest): Promise<OptionTrade> {
  return apiRequest<OptionTrade>('/api/option-trades/exercises', {
    method: 'POST',
    body: request,
  })
}

/** `GET /api/option-trades/{id}` → 200. */
export function getOptionTrade(id: number, signal?: AbortSignal): Promise<OptionTrade> {
  return apiRequest<OptionTrade>(`/api/option-trades/${String(id)}`, { signal })
}

/** Builds the query string for `GET /api/option-trades`; empty filters are left out. */
export function optionTradeListQuery(params: OptionTradeListParams): string {
  const query = new URLSearchParams()
  const single = [
    'symbol',
    'expirationDate',
    'strike',
    'optionType',
    'startDate',
    'endDate',
  ] as const
  for (const key of single) {
    const value = params[key]
    if (value !== undefined && value !== '') query.append(key, value)
  }
  for (const action of params.action ?? []) query.append('action', action)
  query.append('page', String(params.page))
  query.append('size', String(params.size))
  return query.toString()
}

/** `GET /api/option-trades?…` → 200 (newest first, filtered, paginated). */
export function listOptionTrades(
  params: OptionTradeListParams,
  signal?: AbortSignal,
): Promise<OptionTradePage> {
  return apiRequest<OptionTradePage>(`/api/option-trades?${optionTradeListQuery(params)}`, {
    signal,
  })
}

/** `PUT /api/option-trades/{id}` → 200 (FEAT-005: edit contracts, net amount, time, multiplier, note). */
export function updateOptionTrade(
  id: number,
  request: UpdateOptionTradeRequest,
): Promise<OptionTrade> {
  return apiRequest<OptionTrade>(`/api/option-trades/${String(id)}`, {
    method: 'PUT',
    body: request,
  })
}

/** `DELETE /api/option-trades/{id}` → 204 (FEAT-005). */
export async function deleteOptionTrade(id: number): Promise<void> {
  await apiRequest<null>(`/api/option-trades/${String(id)}`, { method: 'DELETE' })
}
