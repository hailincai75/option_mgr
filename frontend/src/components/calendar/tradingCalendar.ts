import { useQueries, useQuery } from '@tanstack/react-query'
import {
  getNonTradingDays,
  getTradingCalendarStatus,
  tradingCalendarQueryKeys,
} from '../../api/tradingCalendar'
import type { LocalDateString, NonTradingDay } from '../../api/types'

/**
 * FEAT-009 helpers for the trading calendar. The frontend never decides what a trading day is:
 * weekends and holidays come only from `GET /api/trading-calendar/non-trading-days`. The only
 * date math here is "which calendar month is on screen" (for the request range).
 */

/** Requirement rule 8: manual input of a non-trading day. */
export const NOT_TRADING_DAY_MESSAGE = '所选日期不是美股交易日'

/** Shown under the picker when the current month's lookup failed (nothing is disabled then). */
export const CALENDAR_LOOKUP_FAILED_HINT = '休市日查询失败，暂不禁用非交易日，保存时由后端校验'

/** "YYYY-MM" */
export type YearMonth = string

const YEAR_MONTH_PATTERN = /^(\d{4})-(\d{2})$/

function parseYearMonth(month: YearMonth): { year: number; month: number } | null {
  const match = YEAR_MONTH_PATTERN.exec(month)
  if (!match) return null
  const year = Number(match[1])
  const monthNumber = Number(match[2])
  if (monthNumber < 1 || monthNumber > 12) return null
  return { year, month: monthNumber }
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function formatYearMonth(year: number, month: number): YearMonth {
  return `${String(year).padStart(4, '0')}-${pad2(month)}`
}

/** "2026-11" → first and last day of that month, e.g. 2026-11-01 … 2026-11-30. */
export function monthRange(month: YearMonth): { from: LocalDateString; to: LocalDateString } {
  const parsed = parseYearMonth(month)
  if (!parsed) throw new Error(`Invalid month: ${month}`)
  // Day 0 of the next month = last day of this one (UTC, so no time zone involved).
  const lastDay = new Date(Date.UTC(parsed.year, parsed.month, 0)).getUTCDate()
  const prefix = formatYearMonth(parsed.year, parsed.month)
  return { from: `${prefix}-01`, to: `${prefix}-${pad2(lastDay)}` }
}

/** "2026-12" + 1 → "2027-01"; "2026-01" − 1 → "2025-12". */
export function shiftMonth(month: YearMonth, delta: number): YearMonth {
  const parsed = parseYearMonth(month)
  if (!parsed) throw new Error(`Invalid month: ${month}`)
  const index = parsed.year * 12 + (parsed.month - 1) + delta
  return formatYearMonth(Math.floor(index / 12), (index % 12) + 1)
}

/** The month of a "YYYY-MM-DD" date, or null when it is not one. */
export function monthOf(date: string | null | undefined): YearMonth | null {
  if (date == null || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  const month = date.slice(0, 7)
  return parseYearMonth(month) ? month : null
}

/**
 * Months to look up for a picker showing `month`: the month itself plus its neighbours, because
 * the date panel also shows (and lets you click) the trailing / leading days of adjacent months.
 */
export function monthsToLoad(month: YearMonth): YearMonth[] {
  return [shiftMonth(month, -1), month, shiftMonth(month, 1)]
}

/** Calendar data changes at most once a day (Q120); avoid refetching on every month flip. */
const CALENDAR_STALE_TIME_MS = 10 * 60 * 1000

export interface NonTradingDaysLookup {
  /** Every non-trading day loaded so far for the months on screen, by "YYYY-MM-DD". */
  days: ReadonlyMap<LocalDateString, NonTradingDay>
  /** The month on screen failed to load (then nothing in it is disabled). */
  currentMonthFailed: boolean
  /** Changes whenever loaded data changes (stable dependency for effects). */
  version: string
}

/**
 * Non-trading days around `month` (one request per month, `from` / `to` = that month). While a
 * month is loading or after it failed, its days are simply not in `days` (not disabled).
 */
export function useNonTradingDays(month: YearMonth, enabled = true): NonTradingDaysLookup {
  const months = monthsToLoad(month)
  const results = useQueries({
    queries: months.map((m) => {
      const { from, to } = monthRange(m)
      return {
        queryKey: tradingCalendarQueryKeys.nonTradingDays(from, to),
        queryFn: ({ signal }: { signal: AbortSignal }) => getNonTradingDays(from, to, signal),
        staleTime: CALENDAR_STALE_TIME_MS,
        enabled,
      }
    }),
  })
  const days = new Map<LocalDateString, NonTradingDay>()
  for (const result of results) {
    for (const day of result.data?.days ?? []) days.set(day.date, day)
  }
  return {
    days,
    currentMonthFailed: results[1]?.isError ?? false,
    version: results.map((result) => String(result.dataUpdatedAt)).join(','),
  }
}

/** `GET /api/trading-calendar/status` (not account-scoped). */
export function useTradingCalendarStatus() {
  return useQuery({
    queryKey: tradingCalendarQueryKeys.status(),
    queryFn: ({ signal }) => getTradingCalendarStatus(signal),
    staleTime: 60 * 1000,
  })
}
