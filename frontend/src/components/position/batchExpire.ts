import type {
  CreateExpirationRequest,
  ExpirationBatchFailure,
  OptionPosition,
  OptionTrade,
  OptionType,
} from '../../api/types'
import type { ExpiryFilter } from './expiryFilter'

/**
 * FEAT-015 批量作废 helpers: where the button shows (Q118), which rows it covers (backend
 * `expirable` only, Q117), the request items, and matching results / failures back to rows by
 * contract (never by array position). No date or money logic here.
 */

/** Rule 1 / Q118: only the 已过期未处理 and 今天到期 filters get the button. */
export function showsBatchExpire(filter: ExpiryFilter): boolean {
  return filter === 'expired' || filter === 'today'
}

/** Rows of the shown table that have the 作废 button, in table order (Q35). */
export function batchExpirableRows<T extends Pick<OptionPosition, 'expirable'>>(shown: T[]): T[] {
  return shown.filter((position) => position.expirable)
}

/** Button text: `批量作废（N）`. */
export function batchExpireButtonLabel(count: number): string {
  return `批量作废（${String(count)}）`
}

/** `已选 K / N 个`. */
export function selectedCountText(selected: number, total: number): string {
  return `已选 ${String(selected)} / ${String(total)} 个`
}

/** Result headline: `作废 N 个`. */
export function expiredCountText(count: number): string {
  return `作废 ${String(count)} 个`
}

interface ContractFields {
  symbol: string
  expirationDate: string
  strike: string
  optionType: OptionType | (string & {})
}

/**
 * Drops trailing fractional zeros ("45.00" → "45", "12.50" → "12.5") so the same strike written
 * two ways matches. Pure string handling, no number conversion.
 */
function normalizeStrike(strike: string): string {
  const trimmed = strike.trim()
  if (!trimmed.includes('.')) return trimmed
  return trimmed.replace(/0+$/, '').replace(/\.$/, '')
}

/** Identity of a contract: symbol (upper-case) + expiration date + strike + Call/Put. */
export function contractKey(contract: ContractFields): string {
  return [
    contract.symbol.trim().toUpperCase(),
    contract.expirationDate,
    normalizeStrike(contract.strike),
    contract.optionType,
  ].join('|')
}

/** One request item per selected position: the FEAT-012 fields, `contracts` = `expiryContracts`. */
export function toExpirationItem(position: OptionPosition): CreateExpirationRequest {
  return {
    symbol: position.symbol,
    expirationDate: position.expirationDate,
    strike: position.strike,
    optionType: position.optionType,
    contracts: position.expiryContracts,
  }
}

export interface MatchedFailures {
  /** Row contract key → its failures (normally one). */
  byRow: Map<string, ExpirationBatchFailure[]>
  /** Failures that match no row (shown above the buttons so nothing is lost). */
  unmatched: ExpirationBatchFailure[]
}

/**
 * Puts each failure on its row: by contract first; if the contract does not match any row (it
 * should), by `index` into the items that were sent.
 */
export function matchFailuresToRows(
  failures: ExpirationBatchFailure[],
  rows: OptionPosition[],
  sentItems: CreateExpirationRequest[],
): MatchedFailures {
  const rowKeys = new Set(rows.map(contractKey))
  const byRow = new Map<string, ExpirationBatchFailure[]>()
  const unmatched: ExpirationBatchFailure[] = []
  for (const failure of failures) {
    let key = contractKey(failure)
    if (!rowKeys.has(key)) {
      const sent = sentItems[failure.index]
      key = sent === undefined ? '' : contractKey(sent)
    }
    if (rowKeys.has(key)) {
      byRow.set(key, [...(byRow.get(key) ?? []), failure])
    } else {
      unmatched.push(failure)
    }
  }
  return { byRow, unmatched }
}

export interface BatchResultRow {
  key: string
  /** The dialog row (null when the response has a record that matches no row). */
  position: OptionPosition | null
  /** The EXP record for the row (null if the response has none for it). */
  expiration: OptionTrade | null
}

/**
 * Result rows in dialog order: each submitted row with its EXP record matched by contract; any
 * record that matches no submitted row is appended so every backend value is shown.
 */
export function matchExpirationsToRows(
  expirations: OptionTrade[],
  submitted: OptionPosition[],
): BatchResultRow[] {
  const byKey = new Map<string, OptionTrade>()
  for (const expiration of expirations) byKey.set(contractKey(expiration), expiration)
  const rows: BatchResultRow[] = submitted.map((position) => {
    const key = contractKey(position)
    const expiration = byKey.get(key) ?? null
    byKey.delete(key)
    return { key, position, expiration }
  })
  for (const [key, expiration] of byKey) rows.push({ key, position: null, expiration })
  return rows
}
