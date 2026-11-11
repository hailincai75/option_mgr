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
export type CashSourceType = 'DEPOSIT' | 'WITHDRAWAL' | 'OPTION_OPEN' | (string & {})

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
  /**
   * Signed change of frozen cash caused by this entry (FEAT-001, compatible addition):
   * "0.00" for deposits/withdrawals/BTO, +collateral for STO put.
   */
  frozenChange: MoneyString
  /** System description, e.g. "STO 1 张 AAPL 2026-12-18 200 P"; null for deposits/withdrawals. */
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

// ---------------------------------------------------------------------------
// FEAT-001 录入开仓期权交易
// ---------------------------------------------------------------------------

/** "YYYY-MM-DD". */
export type LocalDateString = string

export type OptionType = 'CALL' | 'PUT'

/** FEAT-001 only accepts opening actions (BTC / STC come with FEAT-004). */
export type OpeningAction = 'BTO' | 'STO'

/** Contract: `POST /api/option-trades` request body. */
export interface CreateOptionTradeRequest {
  /** Sent as typed; the backend trims and upper-cases it. */
  symbol: string
  expirationDate: LocalDateString
  /** > 0, at most 3 decimals. */
  strike: string
  optionType: OptionType
  action: OpeningAction
  /** Positive integer. */
  contracts: number
  /** Net amount incl. fees: STO = received after fees, BTO = paid incl. fees. > 0, ≤ 2 decimals. */
  netAmount: MoneyString
  /** New York local time; the backend converts it to UTC. */
  tradedAtLocal: LocalDateTimeString
  /** Positive integer; null → 100. */
  multiplier: number | null
  /** At most 500 characters; null / blank = no note. */
  note: string | null
}

/** Contract: `POST /api/option-trades` → 201 and `GET /api/option-trades/{id}` → 200. */
export interface OptionTrade {
  id: number
  symbol: string
  expirationDate: LocalDateString
  /** Trailing zeros removed, e.g. "200", "12.5". */
  strike: string
  optionType: OptionType
  /** Open string type: FEAT-004 adds BTC / STC. */
  action: OpeningAction | (string & {})
  contracts: number
  multiplier: number
  netAmount: MoneyString
  tradedAt: InstantString
  /** Same instant as New York local "YYYY-MM-DDTHH:mm:ss". */
  tradedAtLocal: LocalDateTimeString
  /** Signed effect on total cash. */
  cashChange: MoneyString
  /** Effect on frozen cash. */
  frozenChange: MoneyString
  /** e.g. "AAPL 2026-12-18 200 P". */
  contractLabel: string
  note: string | null
  ledgerEntryId: number
  createdAt: InstantString
}

// ---------------------------------------------------------------------------
// FEAT-002 交易列表与筛选
// ---------------------------------------------------------------------------

/** All trade actions accepted by the list filter (BTC / STC are valid now, results empty until FEAT-004). */
export type TradeAction = 'BTO' | 'STO' | 'BTC' | 'STC'

/**
 * Contract: `GET /api/option-trades` query parameters. All filters optional; omitted = no filter.
 * `action` is multi-valued (sent as repeated `action=` params).
 */
export interface OptionTradeListParams {
  symbol?: string
  expirationDate?: LocalDateString
  strike?: string
  optionType?: OptionType
  action?: TradeAction[]
  /** New York date, inclusive. */
  startDate?: LocalDateString
  /** New York date, inclusive. */
  endDate?: LocalDateString
  /** From 1. */
  page: number
  /** 1–100. */
  size: number
}

/** Contract: `GET /api/option-trades` → 200. Items have the same shape as `GET /api/option-trades/{id}`. */
export interface OptionTradePage {
  items: OptionTrade[]
  page: number
  size: number
  totalItems: number
  /** 0 when nothing matches. */
  totalPages: number
}
