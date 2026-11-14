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
export type CashSourceType =
  | 'DEPOSIT'
  | 'WITHDRAWAL'
  | 'OPTION_OPEN'
  | 'OPTION_CLOSE'
  /** FEAT-012: 到期作废 of a short put (amount "0.00", frozenChange negative). */
  | 'OPTION_EXPIRE'
  /** FEAT-013: 被指派 of a short put (amount −(purchase + fee), frozenChange −released). */
  | 'OPTION_ASSIGN'
  /** FEAT-014: 行权 of a long call (−(purchase + fee)) / put (+(sale − fee)). */
  | 'OPTION_EXERCISE'
  /** FEAT-011: 股票买入 / 股票卖出 (sourceId = stock trade id; edited on the 股票 page). */
  | 'STOCK_BUY'
  | 'STOCK_SELL'
  | (string & {})

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
  /**
   * Deposit / withdrawal rows: true when CASH_TRANSFER + sourceId has audit records (shows 历史).
   * Option rows: always false.
   */
  hasAuditLog: boolean
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
  /**
   * Open string type: FEAT-004 adds BTC / STC, FEAT-012 EXP (到期作废), FEAT-013 ASG (被指派),
   * FEAT-014 EXR (行权).
   */
  action: OpeningAction | ClosingAction | 'EXP' | 'ASG' | 'EXR' | (string & {})
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
  /** FEAT-012: null for an EXP record without a ledger entry (long / short call). */
  ledgerEntryId: number | null
  createdAt: InstantString
  /** FEAT-004: lots closed by this trade (FIFO order); null for opening trades. */
  coveredLots: CoveredLot[] | null
  /** FEAT-004: realized P&L of this close (profit +, loss −); null for opening trades. */
  realizedPnl: MoneyString | null
  /** True when OPTION_TRADE + id has audit records (shows 历史). */
  hasAuditLog: boolean
  /** FEAT-012: direction of the expired position (SHORT / LONG) for EXP records; null otherwise. */
  expiredDirection: PositionDirection | null
  /** FEAT-013: only ASG records (被指派); null otherwise. */
  assignment: OptionTradeAssignment | null
  /** FEAT-014: only EXR records (行权); null otherwise (optional: older fixtures). */
  exercise?: OptionTradeExercise | null
}

/** FEAT-014: `exercise` of an EXR record; every value is the backend's (current replay). */
export interface OptionTradeExercise {
  /** New York date; the record time is that date 16:00 NY. */
  exerciseDate: LocalDateString
  exerciseFee: MoneyString
  /** Sum of the exercised lots' opening net amounts (positive). */
  premium: MoneyString
  shares: number
  /** Call: strike × shares, rounded up to the cent (Q96); null for a put. */
  purchaseAmount: MoneyString | null
  /** Call: purchaseAmount + premium + exerciseFee; null for a put. */
  stockCost: MoneyString | null
  /** Call: HALF_UP to 4 decimals, display only; null for a put. */
  stockCostPerShare: PriceString | null
  /** Put: strike × shares, rounded down to the cent (Q99); null for a call. */
  saleAmount: MoneyString | null
  /** Put: saleAmount − premium − exerciseFee; null for a call. */
  proceeds: MoneyString | null
  /** Put: FIFO cost of the delivered shares; null for a call. */
  soldCost: MoneyString | null
  /** Put: proceeds − soldCost (may be negative); null for a call. */
  stockRealizedPnl: MoneyString | null
  /** Put: delivered stock lots (FIFO order); null for a call. */
  soldLots: StockSoldLot[] | null
  /** The generated stock record: EXERCISE_BUY (call) or EXERCISE_SELL (put). */
  stockTradeId: number
}

/** FEAT-013: `assignment` of an ASG record; every value is the backend's (current replay). */
export interface OptionTradeAssignment {
  /** New York date; the record time is that date 16:00 NY. */
  assignmentDate: LocalDateString
  assignmentFee: MoneyString
  /** Sum of the assigned lots' opening net amounts. */
  premium: MoneyString
  shares: number
  /** Put: strike × shares, rounded up to the cent (Q96); null for a call (FEAT-041). */
  purchaseAmount: MoneyString | null
  /** Put: purchaseAmount − premium + assignmentFee; null for a call. */
  stockCost: MoneyString | null
  /** Put: HALF_UP to 4 decimals, display only (Q88); null for a call. */
  stockCostPerShare: PriceString | null
  /** FEAT-041 call: strike × shares, rounded down to the cent (Q99); null for a put. */
  saleAmount?: MoneyString | null
  /** FEAT-041 call: saleAmount + premium − assignmentFee (stock sale proceeds); null for a put. */
  proceeds?: MoneyString | null
  /** FEAT-041 call: FIFO cost of the delivered shares; null for a put. */
  soldCost?: MoneyString | null
  /** FEAT-041 call: proceeds − soldCost (may be negative); null for a put. */
  stockRealizedPnl?: MoneyString | null
  /** FEAT-041 call: delivered stock lots (FIFO order); null for a put. */
  soldLots?: StockSoldLot[] | null
  /** The generated stock record: ASSIGN_BUY (put) or ASSIGN_SELL (call, FEAT-041). */
  stockTradeId: number
}

// ---------------------------------------------------------------------------
// FEAT-002 交易列表与筛选
// ---------------------------------------------------------------------------

/**
 * Trade actions accepted by the list filter (FEAT-012 adds EXP 到期作废, FEAT-013 ASG 被指派,
 * FEAT-014 EXR 行权).
 */
export type TradeAction = 'BTO' | 'STO' | 'BTC' | 'STC' | 'EXP' | 'ASG' | 'EXR'

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

// ---------------------------------------------------------------------------
// FEAT-003 期权持仓汇总
// ---------------------------------------------------------------------------

/** SHORT (built by STO) / LONG (built by BTO). Open type for safety: unknown values shown as-is. */
export type PositionDirection = 'SHORT' | 'LONG' | (string & {})

/** ACTIVE (dte > 0) / EXPIRES_TODAY (dte = 0) / EXPIRED (dte < 0). */
export type ExpirationStatus = 'ACTIVE' | 'EXPIRES_TODAY' | 'EXPIRED' | (string & {})

/** Contract: `GET /api/option-positions` → `positions[].lots[]` (FIFO order). */
export interface OptionPositionLot {
  tradeId: number
  action: string
  tradedAt: InstantString
  tradedAtLocal: LocalDateTimeString
  originalContracts: number
  remainingContracts: number
  /** Signed by cash direction (short +, long −). */
  originalNetAmount: MoneyString
  remainingNetAmount: MoneyString
  originalFrozen: MoneyString
  remainingFrozen: MoneyString
  note: string | null
  /** FEAT-039: short-call lots only (null otherwise). */
  coverage?: CoverageStatus | null
  /** FEAT-039: remainingContracts × multiplier for short-call lots; null otherwise. */
  requiredShares?: number | null
  /** FEAT-039: LEGACY lots only — first instant the invariant fails, and shares held then. */
  shortfallAt?: InstantString | null
  shortfallAtLocal?: LocalDateTimeString | null
  shortfallHeldShares?: number | null
}

/** FEAT-039: coverage status of an STO call lot. */
export type CoverageStatus = 'CHECKED' | 'LEGACY' | (string & {})

/** Contract: `GET /api/option-positions` → `positions[]`. Every value is display-ready. */
export interface OptionPosition {
  /** FEAT-004: action that closes this position (short → BTC, long → STC). */
  closeAction: ClosingAction
  /** FEAT-004: contract counts that can be closed now (cumulative whole earliest lots), ascending. */
  allowedCloseContracts: number[]
  /** FEAT-012: now < expiration date 16:00 New York; the 平仓 button is shown only when true. */
  closable: boolean
  /** FEAT-012: 到期待确认 (16:00 ≤ now < 17:30 on the expiration date); no buttons. */
  expiryPending: boolean
  /** FEAT-012: now ≥ expiration date 17:30 New York; the 作废 button is shown only when true. */
  expirable: boolean
  /** FEAT-012: contracts held at 16:00 on the expiration date; sent as-is as the expire `contracts`. */
  expiryContracts: number
  /** FEAT-012: when the expiry would be recorded (expiration date 16:00 NY), UTC. */
  expiryAt: InstantString
  /** FEAT-012: same instant as New York local "YYYY-MM-DDTHH:mm:ss". */
  expiryAtLocal: LocalDateTimeString
  /** FEAT-013: short put (any time, incl. 到期待确认); the 被指派 button is shown only when true. */
  assignable: boolean
  /** FEAT-013: whole earliest-lot choices, ascending; [] when not assignable. */
  assignChoices: AssignChoice[]
  /** FEAT-013: latest selectable assignment date (NY); null when not assignable. */
  assignmentDateMax: LocalDateString | null
  /** FEAT-013: default assignment date (NY); null when not assignable. */
  assignmentDateDefault: LocalDateString | null
  symbol: string
  expirationDate: LocalDateString
  /** Trailing zeros removed; usable as-is as the FEAT-002 `strike` filter. */
  strike: string
  optionType: OptionType
  contractLabel: string
  direction: PositionDirection
  contracts: number
  multiplier: number
  /** Signed: short +, long −. */
  openingNetTotal: MoneyString
  /** HALF_UP to the cent, display only. */
  averageNetPerContract: MoneyString
  frozenCash: MoneyString
  dte: number
  expirationStatus: ExpirationStatus
  /** N in "已过期 N 天"; 0 when not expired. */
  daysPastExpiration: number
  /**
   * FEAT-009 (Q125): trading days in (asOfDate, expirationDate]; 0 on the expiry day, null once
   * expired (shown as "—"). Optional: older fixtures.
   */
  remainingTradingDays?: number | null
  lotCount: number
  lots: OptionPositionLot[]
  /**
   * FEAT-014: long position (call or put) at any time; the 行权 button is shown only when true.
   * Optional: older fixtures.
   */
  exercisable?: boolean
  /** FEAT-014: whole earliest-lot choices, ascending; [] when not exercisable. */
  exerciseChoices?: AssignChoice[]
  /** FEAT-014: latest selectable exercise date (NY); null when not exercisable. */
  exerciseDateMax?: LocalDateString | null
  /** FEAT-014: default exercise date (NY); null when not exercisable. */
  exerciseDateDefault?: LocalDateString | null
  /** FEAT-014: long call rows → current available cash (display only); null otherwise. */
  availableCash?: MoneyString | null
  /**
   * FEAT-041: short call rows → current shares of the underlying held (may be 0). FEAT-014: also
   * long put rows. null otherwise — so a non-null value does NOT mean "short call row".
   */
  underlyingShares?: number | null
  /** FEAT-039 (short call rows; null otherwise): shares needed by CHECKED lots (0 when none). */
  coveredShares?: number | null
  /** FEAT-039: remaining contracts of LEGACY lots (0 when none). */
  uncoveredContracts?: number | null
  /** FEAT-039: shares needed by LEGACY lots. */
  uncoveredShares?: number | null
  /**
   * FEAT-039: current available shares of the underlying (may be 0) on short call rows; FEAT-014:
   * also on long put rows (the other coverage fields stay null there).
   */
  availableShares?: number | null
  /** FEAT-039: earliest shortfall instant among LEGACY lots; null when none. */
  uncoveredShortfallAt?: InstantString | null
  /** FEAT-039: same instant as New York local "YYYY-MM-DDTHH:mm:ss". */
  uncoveredShortfallAtLocal?: LocalDateTimeString | null
  /** FEAT-039: shares held at that instant. */
  uncoveredShortfallHeldShares?: number | null
  /**
   * FEAT-042: the expiration date is an active ad-hoc closure (affected position); the 临时休市
   * tag is shown beside the expiration date when true. Optional: older fixtures.
   */
  expiresOnAdhocClosure?: boolean
  /** FEAT-042: that closure's `eventName` (tag tooltip) when `expiresOnAdhocClosure`; else null. */
  adhocClosureEventName?: string | null
}

/** Contract: `GET /api/option-positions` → 200. */
export interface OptionPositionsResponse {
  /** "Today" in New York, "YYYY-MM-DD". */
  asOfDate: LocalDateString
  positionCount: number
  frozenTotal: MoneyString
  positions: OptionPosition[]
}

// ---------------------------------------------------------------------------
// FEAT-004 录入平仓交易
// ---------------------------------------------------------------------------

export type ClosingAction = 'BTC' | 'STC'

/** Contract: trade representation `coveredLots[]` (lots closed by a BTC / STC). */
export interface CoveredLot {
  tradeId: number
  action: string
  tradedAt: InstantString
  tradedAtLocal: LocalDateTimeString
  contracts: number
  /** Opening net amount, signed by cash direction (STO +, BTO −). */
  netAmount: MoneyString
  /** Frozen cash of the lot (released by BTC of a short put); "0.00" if none. */
  frozen: MoneyString
}

/** Contract: `POST /api/option-trades/closes` request body (no multiplier: taken from the position). */
export interface CreateCloseTradeRequest {
  symbol: string
  expirationDate: LocalDateString
  strike: string
  optionType: OptionType
  action: ClosingAction
  contracts: number
  /** BTC = paid incl. fees, STC = received after fees. > 0, ≤ 2 decimals. */
  netAmount: MoneyString
  tradedAtLocal: LocalDateTimeString
  note: string | null
}

// ---------------------------------------------------------------------------
// FEAT-012 到期作废
// ---------------------------------------------------------------------------

/**
 * Contract: `POST /api/option-trades/expirations` request body; 201 returns the EXP record
 * (`OptionTrade`). `contracts` is the position's `expiryContracts` passed back unchanged (it only
 * detects a position change while the dialog is open; not a user input).
 */
export interface CreateExpirationRequest {
  symbol: string
  expirationDate: LocalDateString
  strike: string
  optionType: OptionType
  contracts: number
}

// ---------------------------------------------------------------------------
// FEAT-015 到期批量作废
// ---------------------------------------------------------------------------

/**
 * Contract: `POST /api/option-trades/expirations/batch` request body. Each item is exactly a
 * FEAT-012 expire request (`contracts` = the position's `expiryContracts`, passed back unchanged).
 */
export interface CreateExpirationBatchRequest {
  items: CreateExpirationRequest[]
}

/** Contract: `POST /api/option-trades/expirations/batch` → 201. */
export interface ExpirationBatchResponse {
  count: number
  /** Sum of `expirations[].realizedPnl`, scale 2. */
  realizedPnlTotal: MoneyString
  /** Sum of `expirations[].frozenChange`, scale 2, ≤ 0. */
  frozenChangeTotal: MoneyString
  /**
   * One EXP record per item, in processing order (same representation as the FEAT-012 201
   * response). Match to rows by contract, never by index.
   */
  expirations: OptionTrade[]
}

/** FEAT-015 failure codes (FEAT-012 checks 2–5); other values are shown as-is. */
export type ExpirationFailureCode =
  | 'EXPIRY_TOO_EARLY'
  | 'NO_POSITION_TO_EXPIRE'
  | 'POSITION_CHANGED'
  | 'EXPIRY_BLOCKED_BY_LATE_TRADE'
  | (string & {})

/** Contract: 422 `BATCH_EXPIRATION_REJECTED` → `failures[]` (extension of the unified error). */
export interface ExpirationBatchFailure {
  /** Index of the item in the request `items` (0-based). */
  index: number
  /** Normalized contract (symbol upper-case, strike without trailing zeros). */
  symbol: string
  expirationDate: LocalDateString
  strike: string
  optionType: OptionType
  contractLabel: string
  code: ExpirationFailureCode
  /** Same text as the single expire would return; shown verbatim beside the row. */
  message: string
}

/** Contract: the 422 `BATCH_EXPIRATION_REJECTED` body (unified error + `failures`). */
export interface ExpirationBatchRejectedResponse extends ApiErrorResponse {
  failures: ExpirationBatchFailure[]
}

// ---------------------------------------------------------------------------
// FEAT-013 卖出 put 被指派
// ---------------------------------------------------------------------------

/** Contract: `positions[].assignChoices[]`. */
export interface AssignChoice {
  contracts: number
  /** contracts × multiplier. */
  shares: number
}

/**
 * Contract: `POST /api/option-trades/assignments` request body; 201 returns the ASG record
 * (`OptionTrade` with `assignment`).
 */
export interface CreateAssignmentRequest {
  symbol: string
  expirationDate: LocalDateString
  strike: string
  optionType: OptionType
  /** One of the position's `assignChoices[].contracts`. */
  contracts: number
  /** New York date; recorded at that date 16:00 NY. */
  assignmentDate: LocalDateString
  /** null / empty = "0.00"; ≥ 0, ≤ 2 decimals. */
  assignmentFee: MoneyString | null
  /** At most 500 characters; null / blank = no note. */
  note: string | null
}

/** FEAT-013 error codes (HTTP 422; `message` is user-facing Chinese). */
export const ASSIGNMENT_DATE_INVALID = 'ASSIGNMENT_DATE_INVALID'
export const ASSIGNMENT_NOT_EDITABLE = 'ASSIGNMENT_NOT_EDITABLE'
export const STOCK_TRADE_FROM_ASSIGNMENT = 'STOCK_TRADE_FROM_ASSIGNMENT'
// ---------------------------------------------------------------------------
// FEAT-014 主动行权
// ---------------------------------------------------------------------------

/**
 * Contract: `POST /api/option-trades/exercises` request body; 201 returns the EXR record
 * (`OptionTrade` with `exercise`).
 */
export interface CreateExerciseRequest {
  symbol: string
  expirationDate: LocalDateString
  strike: string
  optionType: OptionType
  /** One of the position's `exerciseChoices[].contracts`. */
  contracts: number
  /** New York date; recorded at that date 16:00 NY. */
  exerciseDate: LocalDateString
  /** null / empty = "0.00"; ≥ 0, ≤ 2 decimals. */
  exerciseFee: MoneyString | null
  /** At most 500 characters; null / blank = no note. */
  note: string | null
}

/** FEAT-014 error codes (HTTP 422; `message` is user-facing Chinese, shown verbatim). */
export const EXERCISE_SHORT_POSITION = 'EXERCISE_SHORT_POSITION'
export const EXERCISE_DATE_INVALID = 'EXERCISE_DATE_INVALID'
export const NO_POSITION_TO_EXERCISE = 'NO_POSITION_TO_EXERCISE'
export const EXERCISE_PROCEEDS_NOT_POSITIVE = 'EXERCISE_PROCEEDS_NOT_POSITIVE'
export const EXERCISE_NOT_EDITABLE = 'EXERCISE_NOT_EDITABLE'
export const STOCK_TRADE_FROM_EXERCISE = 'STOCK_TRADE_FROM_EXERCISE'

/**
 * FEAT-041 error code (HTTP 422; shown verbatim). FEAT-041 also returns SHARES_INSUFFICIENT
 * (declared with FEAT-011) for a call assignment without enough shares.
 */
export const ASSIGNMENT_PROCEEDS_NOT_POSITIVE = 'ASSIGNMENT_PROCEEDS_NOT_POSITIVE'
/**
 * FEAT-039 error code (HTTP 422, `details` []): covered-call coverage check (E1 / E2 / E3) on
 * option / stock trade create, edit, delete and assignments. `message` is shown verbatim.
 */
export const COVERAGE_INSUFFICIENT = 'COVERAGE_INSUFFICIENT'

// ---------------------------------------------------------------------------
// FEAT-005 修改 / 删除与审计
// ---------------------------------------------------------------------------

/**
 * Contract: `PUT /api/option-trades/{id}` request body — full replacement of the editable fields
 * (omitting / null `note` clears it). `multiplier` only for opens (null = unchanged); omit for closes.
 */
export interface UpdateOptionTradeRequest {
  contracts: number
  netAmount: MoneyString
  tradedAtLocal: LocalDateTimeString
  multiplier?: number | null
  note: string | null
}

/** Contract: `PUT /api/cash/transfers/{id}` request body (same as create). */
export type UpdateCashTransferRequest = CreateCashTransferRequest

export type AuditObjectType = 'OPTION_TRADE' | 'CASH_TRANSFER' | 'ACCOUNT' | 'STOCK_TRADE'
export type AuditOperation = 'CREATE' | 'UPDATE' | 'DELETE' | (string & {})

/** A full snapshot of the object (trade or transfer fields, see contract); display only. */
export type AuditSnapshot = Record<string, string | number | null>

export interface AuditFieldChange {
  field: string
  label: string
  before: string
  after: string
  /** e.g. "净额：360.00 → 365.00" — displayed as-is. */
  text: string
}

export interface AuditSideEffect {
  tradeId: number
  action: string
  contractLabel: string
  field: string
  before: string
  after: string
  /** e.g. "交易 #3 BTC 冻结变动 −24.70 → −24.69" — displayed as-is. */
  text: string
}

/** Contract: `GET /api/audit-log` → `items[]`. */
export interface AuditRecord {
  id: number
  operatedAt: InstantString
  operation: AuditOperation
  objectType: AuditObjectType | (string & {})
  objectId: number
  summary: string
  /** null for CREATE (FEAT-033 account creation). */
  before: AuditSnapshot | null
  /** null for DELETE. */
  after: AuditSnapshot | null
  changes: AuditFieldChange[]
  sideEffects: AuditSideEffect[]
}

/** Contract: `GET /api/audit-log?objectType=&objectId=&page=&size=` → 200. */
export interface AuditLogPage {
  items: AuditRecord[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface AuditLogParams {
  objectType?: AuditObjectType
  objectId?: number
  page: number
  size: number
}

// ---------------------------------------------------------------------------
// FEAT-006 已平仓记录 / 已实现盈亏
// ---------------------------------------------------------------------------

/**
 * FEAT-040: record type of a closed-trades row. Option trade ids and stock record ids are separate
 * sequences, so a row is identified by `type` + `id` (row keys, 历史 links).
 */
export type ClosedRecordType = 'OPTION' | 'STOCK'

/** Fields shared by both row types of `GET /api/closed-trades` → `items[]`. */
interface ClosedRecordBase {
  tradedAt: InstantString
  tradedAtLocal: LocalDateTimeString
  symbol: string
  /** Signed by cash direction (stock rows: −sold cost). */
  openingNetTotal: MoneyString
  /** Signed by cash direction (stock rows: +proceeds). */
  closeNetAmount: MoneyString
  realizedPnl: MoneyString
  note: string | null
  /** True when the closing trade / sell record itself has audit records (shows 历史). */
  hasAuditLog: boolean
}

/** Contract: `GET /api/closed-trades` → `items[]` with `type: "OPTION"` (FEAT-006 / FEAT-012). */
export interface ClosedOptionRecord extends ClosedRecordBase {
  type: 'OPTION'
  /** Closing trade id (audit filter: OPTION_TRADE + id). */
  id: number
  expirationDate: LocalDateString
  strike: string
  optionType: OptionType
  contractLabel: string
  /**
   * FEAT-012 adds EXP (到期作废: close net "0.00", recorded at the expiration date 16:00 NY);
   * FEAT-013 adds ASG (被指派: realized P&L always "0.00", the premium goes into the stock cost);
   * FEAT-014 adds EXR (行权: realized P&L always "0.00").
   */
  action: ClosingAction | 'EXP' | 'ASG' | 'EXR' | (string & {})
  /** Direction of the closed position: BTC → SHORT, STC → LONG, EXP → the expired position's. */
  closedDirection: PositionDirection
  contracts: number
  multiplier: number
  shares: null
  /** HALF_UP to the cent, display only. */
  openingNetPerContract: MoneyString
  costPerShare: null
  /** HALF_UP to the cent, display only. */
  closeNetPerContract: MoneyString
  pricePerShare: null
  coveredLots: CoveredLot[]
  soldLots: null
  /** FEAT-013: only ASG rows; null otherwise. */
  assignment: ClosedAssignment | null
  /** FEAT-014: only EXR rows; null otherwise (optional: older fixtures). */
  exercise?: ClosedExercise | null
  /** FEAT-014: always null on option rows. */
  exerciseTradeId?: null
}

/** FEAT-014: `exercise` of an EXR closed row (small text 权利金已计入股票成本… / 已从…扣除). */
export interface ClosedExercise {
  shares: number
  /** Call: stock cost; null for a put. */
  stockCost: MoneyString | null
  /** Put: stock sale proceeds; null for a call. */
  proceeds: MoneyString | null
  /** The generated EXERCISE_BUY (call) / EXERCISE_SELL (put) stock record. */
  stockTradeId: number
}

/** FEAT-013: `assignment` of an ASG closed row (small text 权利金已计入股票成本…). */
export interface ClosedAssignment {
  shares: number
  /** Put: stock cost; null for a call (FEAT-041). */
  stockCost: MoneyString | null
  /** FEAT-041 call: stock sale proceeds (权利金已计入股票卖出…); null for a put. */
  proceeds?: MoneyString | null
  /** The generated ASSIGN_BUY (put) / ASSIGN_SELL (call) stock record. */
  stockTradeId: number
}

/** Contract (FEAT-040): `GET /api/closed-trades` → `items[].soldLots[]` (FIFO, earliest first). */
export interface ClosedSoldLot {
  /** Source stock record id (BUY or TRANSFER_IN). */
  tradeId: number
  type: StockRecordType | (string & {})
  tradedAt: InstantString
  /** TRANSFER_IN: that date 00:00 New York. */
  tradedAtLocal: LocalDateTimeString
  /** Only TRANSFER_IN lots. */
  tradeDate: LocalDateString | null
  shares: number
  /** Signed by cash direction (negative = −cost of this part), scale 2. */
  netAmount: MoneyString
}

/** Contract (FEAT-040): `GET /api/closed-trades` → `items[]` with `type: "STOCK"` (a stock sale). */
export interface ClosedStockRecord extends ClosedRecordBase {
  type: 'STOCK'
  /** Stock record id (audit filter: STOCK_TRADE + id). */
  id: number
  expirationDate: null
  strike: null
  optionType: null
  contractLabel: null
  /** FEAT-041 adds ASSIGN_SELL (指派卖出), FEAT-014 EXERCISE_SELL (行权卖出, from a put exercise). */
  action: 'SELL' | 'ASSIGN_SELL' | 'EXERCISE_SELL'
  closedDirection: null
  contracts: null
  multiplier: null
  /** Shares sold. */
  shares: number
  openingNetPerContract: null
  /** Sold cost ÷ shares, HALF_UP to 4 decimals, positive, display only (Q88). */
  costPerShare: PriceString
  closeNetPerContract: null
  /** Proceeds ÷ shares, HALF_UP to 4 decimals, positive, display only. */
  pricePerShare: PriceString
  coveredLots: null
  soldLots: ClosedSoldLot[]
  assignment: null
  /** FEAT-041: ASSIGN_SELL rows → the ASG record that generated it (来自被指派 #N); else null. */
  assignmentTradeId?: number | null
  /** FEAT-014: always null on stock rows. */
  exercise?: null
  /** FEAT-014: EXERCISE_SELL rows → the EXR record that generated it (来自行权 #N); else null. */
  exerciseTradeId?: number | null
}

/** Contract: `GET /api/closed-trades` → `items[]`. Every amount is signed by cash direction. */
export type ClosedTrade = ClosedOptionRecord | ClosedStockRecord

/**
 * Contract: `GET /api/closed-trades` query parameters = the trade list's (FEAT-002) plus the
 * FEAT-040 record `type` (omitted = both).
 */
export interface ClosedTradeListParams extends OptionTradeListParams {
  type?: ClosedRecordType
}

/** Contract: `GET /api/closed-trades` → 200. Totals cover ALL filtered results (Q46). */
export interface ClosedTradePage {
  items: ClosedTrade[]
  page: number
  size: number
  totalItems: number
  totalPages: number
  filteredCount: number
  filteredRealizedPnlTotal: MoneyString
}

// ---------------------------------------------------------------------------
// FEAT-008 首页概览 / 盈亏汇总
// ---------------------------------------------------------------------------

/** FEAT-008 / FEAT-040: realized P&L figures include stock sales. */
export interface RealizedPnlFigure {
  closedCount: number
  /** Signed, scale 2. */
  total: MoneyString
}

/** Contract: `GET /api/dashboard` → 200. Every number is the backend's. */
export interface Dashboard {
  /** Today in New York, "YYYY-MM-DD". */
  asOfDate: LocalDateString
  /** Current New York month, "YYYY-MM". */
  currentMonth: string
  cash: CashSummary
  realizedPnl: RealizedPnlFigure
  currentMonthRealizedPnl: RealizedPnlFigure
  positions: {
    positionCount: number
    frozenTotal: MoneyString
    /** 0 ≤ DTE ≤ 7. */
    expiringWithin7DaysCount: number
    /** DTE < 0. */
    expiredUnprocessedCount: number
  }
  /** FEAT-040 (Q89): symbols with shares held > 0 (= `GET /api/stock-positions` positionCount). */
  stockPositions: {
    positionCount: number
  }
}

/** FEAT-040 (Q91): one record type's part of a P&L figure; 0 records = 0 / "0.00" (never null). */
export interface PnlBreakdown {
  closedCount: number
  realizedPnl: MoneyString
}

export interface PnlSymbolRow {
  symbol: string
  /** FEAT-040: options + stocks. */
  closedCount: number
  realizedPnl: MoneyString
  option: PnlBreakdown
  stock: PnlBreakdown
}

export interface PnlMonthRow {
  /** "YYYY-MM" (New York month). */
  month: string
  closedCount: number
  realizedPnl: MoneyString
  option: PnlBreakdown
  stock: PnlBreakdown
}

/** Contract: `GET /api/pnl-summary?startDate=&endDate=` → 200. Totals row = the response totals. */
export interface PnlSummary {
  startDate: LocalDateString | null
  endDate: LocalDateString | null
  /** FEAT-040: options + stocks. */
  closedCount: number
  realizedPnlTotal: MoneyString
  /** FEAT-040 (Q91): option part of the totals. */
  option: PnlBreakdown
  /** FEAT-040 (Q91): stock part of the totals. */
  stock: PnlBreakdown
  /** Alphabetical. */
  bySymbol: PnlSymbolRow[]
  /** Newest month first; months without closes omitted. */
  byMonth: PnlMonthRow[]
}

export interface PnlSummaryParams {
  startDate?: LocalDateString
  endDate?: LocalDateString
}

// ---------------------------------------------------------------------------
// FEAT-033 多账户
// ---------------------------------------------------------------------------

/** Contract: `GET /api/accounts` → items (ascending id). Not an account number (Q51). */
export interface Account {
  id: number
  name: string
  brokerNote: string | null
  createdAt: InstantString
}

/** Contract: `POST /api/accounts` / `PUT /api/accounts/{id}` body (PUT replaces both fields). */
export interface SaveAccountRequest {
  name: string
  brokerNote: string | null
}

// ---------------------------------------------------------------------------
// FEAT-011 股票交易与股票持仓
// ---------------------------------------------------------------------------

/** BUY 买入 / SELL 卖出 / TRANSFER_IN 转入（期初持仓） — the types that can be entered. */
export type StockTradeType = 'BUY' | 'SELL' | 'TRANSFER_IN'

/**
 * Every stock record type: the entered ones plus the generated ones — FEAT-013 ASSIGN_BUY (指派买入),
 * FEAT-041 ASSIGN_SELL (指派卖出), FEAT-014 EXERCISE_BUY (行权买入) / EXERCISE_SELL (行权卖出).
 */
export type StockRecordType =
  | StockTradeType
  | 'ASSIGN_BUY'
  | 'ASSIGN_SELL'
  | 'EXERCISE_BUY'
  | 'EXERCISE_SELL'

/** Decimal with scale 4 as a string, display only (e.g. "7.5033"). */
export type PriceString = string

/** Contract: stock trade representation → `soldLots[]` (FIFO order). */
export interface StockSoldLot {
  /** Source record id (a BUY or TRANSFER_IN stock trade). */
  tradeId: number
  type: StockRecordType | (string & {})
  tradedAt: InstantString
  tradedAtLocal: LocalDateTimeString
  /** Only for TRANSFER_IN lots. */
  tradeDate: LocalDateString | null
  /** Shares taken from this lot. */
  shares: number
  /** Cost of this part, scale 2. */
  cost: MoneyString
}

/** Contract: stock trade representation (POST / GET / PUT responses, list items). */
export interface StockTrade {
  id: number
  type: StockRecordType | (string & {})
  symbol: string
  shares: number
  /** Positive: paid (BUY), received (SELL) or total cost (TRANSFER_IN). */
  amount: MoneyString
  /** Signed effect on total cash; "0.00" for TRANSFER_IN. */
  cashChange: MoneyString
  /** amount ÷ shares, HALF_UP to 4 decimals (display only). */
  pricePerShare: PriceString
  tradedAt: InstantString
  /** New York local "YYYY-MM-DDTHH:mm:ss" (TRANSFER_IN: "…T00:00:00"). */
  tradedAtLocal: LocalDateTimeString
  /** Only TRANSFER_IN: New York date. */
  tradeDate: LocalDateString | null
  note: string | null
  /** null for TRANSFER_IN. */
  ledgerEntryId: number | null
  createdAt: InstantString
  /** True once the record has been edited (shows 历史). */
  hasAuditLog: boolean
  /** Only SELL / ASSIGN_SELL (FEAT-041) / EXERCISE_SELL (FEAT-014). */
  soldCost: MoneyString | null
  /** Only SELL / ASSIGN_SELL: amount − soldCost (profit +, loss −). */
  realizedPnl: MoneyString | null
  /** Only SELL / ASSIGN_SELL. */
  soldLots: StockSoldLot[] | null
  /** FEAT-013 / FEAT-041: ASSIGN_BUY / ASSIGN_SELL → the ASG record that generated it; else null. */
  assignmentTradeId: number | null
  /**
   * FEAT-014: EXERCISE_BUY / EXERCISE_SELL → the EXR record that generated it; else null
   * (optional: older fixtures).
   */
  exerciseTradeId?: number | null
  /** FEAT-013 / FEAT-014: false for generated records (no 修改). */
  editable: boolean
  /** FEAT-013 / FEAT-014: false for generated records (no 删除). */
  deletable: boolean
}

/**
 * Contract: `POST /api/stock-trades` request body. BUY / SELL send `tradedAtLocal`,
 * TRANSFER_IN sends `tradeDate`.
 */
export interface CreateStockTradeRequest {
  type: StockTradeType
  /** Sent as typed; the backend trims and upper-cases it. */
  symbol: string
  /** Integer 1–10,000,000. */
  shares: number
  /** > 0, ≤ 2 decimals. */
  amount: MoneyString
  tradedAtLocal?: LocalDateTimeString
  tradeDate?: LocalDateString
  note: string | null
}

/**
 * Contract: `PUT /api/stock-trades/{id}` request body. Symbol and type are not editable and are
 * not sent. Time field by the record's type; null note clears it.
 */
export interface UpdateStockTradeRequest {
  shares: number
  amount: MoneyString
  tradedAtLocal?: LocalDateTimeString
  tradeDate?: LocalDateString
  note: string | null
}

/** Contract: `GET /api/stock-trades` query parameters (all filters optional). */
export interface StockTradeListParams {
  symbol?: string
  /** Multi-valued, sent as repeated `type=`. */
  type?: StockRecordType[]
  /** New York date, inclusive. */
  startDate?: LocalDateString
  /** New York date, inclusive. */
  endDate?: LocalDateString
  page: number
  size: number
}

/** Contract: `GET /api/stock-trades` → 200. */
export interface StockTradePage {
  items: StockTrade[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

/** Contract: `GET /api/stock-positions` → `positions[].lots[]` (FIFO order). */
export interface StockPositionLot {
  tradeId: number
  type: StockRecordType | (string & {})
  tradedAt: InstantString
  tradedAtLocal: LocalDateTimeString
  tradeDate: LocalDateString | null
  originalShares: number
  remainingShares: number
  originalCost: MoneyString
  remainingCost: MoneyString
  note: string | null
}

/** Contract: `GET /api/stock-positions` → `positions[]`. */
export interface StockPosition {
  symbol: string
  shares: number
  /** FEAT-039: shares currently covering CHECKED short calls (optional: older fixtures). */
  coveredShares?: number
  /** FEAT-039: shares − coveredShares (≥ 0). */
  availableShares?: number
  totalCost: MoneyString
  /** totalCost ÷ shares, HALF_UP to 4 decimals (display only). */
  averageCost: PriceString
  lotCount: number
  lots: StockPositionLot[]
}

/** Contract: `GET /api/stock-positions` → 200 (positions by symbol ascending). */
export interface StockPositionsResponse {
  positionCount: number
  positions: StockPosition[]
}

/** FEAT-011 error codes (HTTP 422; `message` is user-facing Chinese). */
export const SHARES_INSUFFICIENT = 'SHARES_INSUFFICIENT'
export const STOCK_TRADE_IDENTITY_NOT_EDITABLE = 'STOCK_TRADE_IDENTITY_NOT_EDITABLE'

/** FEAT-009: `WEEKEND` (Sat / Sun) or `HOLIDAY` (weekday full-day market closure). */
export type NonTradingDayType = 'WEEKEND' | 'HOLIDAY'

/** FEAT-009: where a HOLIDAY comes from; null for WEEKEND. */
export type NonTradingDaySource = 'FINNHUB' | 'RULES'

export interface NonTradingDay {
  date: LocalDateString
  type: NonTradingDayType | (string & {})
  /** Display only (Finnhub eventName / Jollyday name / 星期六 / 星期日); never branched on. */
  name: string
  source: NonTradingDaySource | (string & {}) | null
}

/** Contract: `GET /api/trading-calendar/non-trading-days?from=&to=` → 200. */
export interface NonTradingDaysResponse {
  from: LocalDateString
  to: LocalDateString
  /** Ascending by date, at most one per date; [] when none. */
  days: NonTradingDay[]
}

export type TradingCalendarFetchResult =
  | 'SUCCESS'
  | 'NO_KEY'
  | 'NETWORK_ERROR'
  | 'HTTP_ERROR'
  | 'INVALID_RESPONSE'

/** Contract: `GET /api/trading-calendar/status` → 200. */
export interface TradingCalendarStatus {
  /** The page shows the warning only when true. */
  stale: boolean
  /** Complete user-facing text when stale (shown verbatim); null otherwise. */
  warning: string | null
  lastAttemptAt: InstantString | null
  lastAttemptResult: TradingCalendarFetchResult | (string & {}) | null
  lastFailureDetail: string | null
  lastSuccessAt: InstantString | null
  lastSuccessAtLocal: LocalDateTimeString | null
  coveredYears: number[]
}

/** FEAT-009 error code (HTTP 422 on BTO / STO; `message` is user-facing Chinese, shown verbatim). */
export const EXPIRATION_NOT_TRADING_DAY = 'EXPIRATION_NOT_TRADING_DAY'

// ---------------------------------------------------------------------------
// FEAT-042 临时休市提醒
// ---------------------------------------------------------------------------

/** Contract: `GET /api/adhoc-closure-alerts` → `alerts[]` (one per active ad-hoc closure date). */
export interface AdhocClosureAlert {
  /** Ad-hoc closure date "YYYY-MM-DD". */
  date: LocalDateString
  /** Finnhub `eventName`, as-is. */
  eventName: string
  /** Affected positions of the current account (≥ 1). */
  affectedPositionCount: number
  /** Complete line to show verbatim (without the trailing 去调整 link). */
  message: string
}

/** Contract: `GET /api/adhoc-closure-alerts` → 200 (current account; `[]` when none, Q129). */
export interface AdhocClosureAlertsResponse {
  /** Ascending by date; only dates with affected positions. */
  alerts: AdhocClosureAlert[]
}
