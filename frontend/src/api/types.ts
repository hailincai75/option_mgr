/**
 * API types, written to match docs/api/api-contract.md exactly.
 * Money/price/quantity decimals are strings; Instants are ISO-8601 UTC strings ("...Z");
 * LocalDates are "YYYY-MM-DD" strings.
 */

/** ISO-8601 UTC instant, e.g. "2026-11-10T14:30:00Z" (0–9 fractional-second digits). */
export type InstantString = string

/** Contract «统一错误格式» → one item of `details`. */
export interface ApiFieldError {
  field: string
  message: string
}

/** Machine-readable error codes from the contract's «通用错误码» table. */
export type ApiErrorCode =
  | 'VALIDATION_ERROR'
  | 'BAD_REQUEST'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'CONFLICT'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'BUSINESS_RULE_VIOLATION'
  | 'INTERNAL_ERROR'

/**
 * Contract «统一错误格式». `code` also allows feature-specific codes that the contract
 * may add per endpoint (e.g. `POSITION_INSUFFICIENT`).
 */
export interface ApiErrorResponse {
  code: ApiErrorCode | (string & {})
  message: string
  status: number
  path: string
  timestamp: InstantString
  details: ApiFieldError[]
}

/** Contract: `GET /api/health` → 200. */
export interface HealthResponse {
  /** Always "UP" when the service responds. */
  status: string
  /** Backend build version, or "dev" when build info is missing. */
  version: string
  /** Server current time. */
  time: InstantString
}

// ---------------------------------------------------------------------------
// FEAT-007 资金账户
// ---------------------------------------------------------------------------

/** Decimal money value as a JSON string with scale 2, no thousands separators, e.g. "-2500.50". */
export type MoneyString = string

/** America/New_York local date-time without zone: "YYYY-MM-DDTHH:mm" or "YYYY-MM-DDTHH:mm:ss". */
export type LocalDateTimeString = string

/** Contract: `GET /api/cash/summary` → 200. */
export interface CashSummary {
  totalCash: MoneyString
  /** Always "0.00" within FEAT-007. */
  frozenCash: MoneyString
  availableCash: MoneyString
}

/** `POST /api/cash/transfers` → `type`. */
export type CashTransferType = 'DEPOSIT' | 'WITHDRAWAL'

/** Contract: `POST /api/cash/transfers` request body. */
export interface CreateCashTransferRequest {
  type: CashTransferType
  /** > 0, at most 2 decimals. */
  amount: MoneyString
  /** New York local time; the backend converts it to UTC. */
  occurredAtLocal: LocalDateTimeString
  /** At most 500 characters; null / blank = no remark. */
  remark: string | null
}

/** Contract: `POST /api/cash/transfers` → 201. */
export interface CashTransfer {
  id: number
  type: CashTransferType
  amount: MoneyString
  occurredAt: InstantString
  remark: string | null
  ledgerEntryId: number
  createdAt: InstantString
}

/**
 * Ledger source type. Later features add values (e.g. option open); unknown values must be
 * displayed as-is, so the type stays open.
 */
export type CashSourceType = 'DEPOSIT' | 'WITHDRAWAL' | (string & {})

/** Contract: `GET /api/cash/ledger` → `items[]`. */
export interface CashLedgerEntry {
  id: number
  occurredAt: InstantString
  sourceType: CashSourceType
  sourceId: number
  /** Signed: inflow positive, outflow negative. */
  amount: MoneyString
  /** Total cash after this entry (in (occurredAt, id) order). */
  balanceAfter: MoneyString
  description: string | null
  remark: string | null
}

/** Contract: `GET /api/cash/ledger?page=&size=` → 200. `page` starts at 1. */
export interface CashLedgerPage {
  items: CashLedgerEntry[]
  page: number
  size: number
  totalItems: number
  /** 0 when there are no entries. */
  totalPages: number
}

/** Contract: FEAT-007 feature-specific error code (HTTP 422); `message` is user-facing Chinese. */
export const CASH_INSUFFICIENT = 'CASH_INSUFFICIENT'
