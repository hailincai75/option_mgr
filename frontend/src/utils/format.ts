/**
 * Shared display formatters. The frontend never computes P&L or other business values;
 * these functions only format values returned by the backend.
 */

/** US options market time zone. All dates/times are shown in this zone. */
export const MARKET_TIME_ZONE = 'America/New_York'

/** Placeholder shown for null / missing / unparseable values. */
export const EMPTY_VALUE = '—'

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: MARKET_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
  timeZoneName: 'short',
})

/**
 * Formats an ISO-8601 UTC instant (e.g. "2026-11-10T22:09:08.676Z") in America/New_York
 * as "YYYY-MM-DD HH:mm:ss EST" / "... EDT". Returns EMPTY_VALUE for null, empty or invalid input.
 */
export function formatInstantNY(instant: string | null | undefined): string {
  if (instant == null || instant.trim() === '') return EMPTY_VALUE
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return EMPTY_VALUE

  const parts: Partial<Record<Intl.DateTimeFormatPartTypes, string>> = {}
  for (const part of dateTimeFormatter.formatToParts(date)) {
    parts[part.type] = part.value
  }
  const { year, month, day, hour, minute, second, timeZoneName } = parts
  if (!year || !month || !day || !hour || !minute || !second) return EMPTY_VALUE
  const zone = timeZoneName ? ` ${timeZoneName}` : ''
  return `${year}-${month}-${day} ${hour}:${minute}:${second}${zone}`
}

/** U+2212 MINUS SIGN, used for negative money (matches backend user-facing messages). */
export const MINUS_SIGN = '−'

const DECIMAL_PATTERN = /^([+-]?)(\d+)(?:\.(\d+))?$/

export interface FormatMoneyOptions {
  /** Prefix positive values with "+" (ledger amounts). Zero never gets a sign. */
  signed?: boolean
}

/**
 * Formats a decimal string from the backend (e.g. "-2500.50") for display: thousands separators,
 * at least 2 decimals, "−" (U+2212) for negatives, optional "+" for positives.
 * Pure string manipulation — no floating-point conversion, no rounding (extra decimals are kept).
 * Returns EMPTY_VALUE for null / empty / non-decimal input.
 */
export function formatMoney(
  value: string | null | undefined,
  { signed = false }: FormatMoneyOptions = {},
): string {
  if (value == null) return EMPTY_VALUE
  const match = DECIMAL_PATTERN.exec(value.trim())
  if (!match) return EMPTY_VALUE
  const [, sign = '', rawInteger = '0', rawFraction = ''] = match

  const integer = rawInteger.replace(/^0+(?=\d)/, '')
  const fraction = rawFraction.padEnd(2, '0')
  const isZero = /^0+$/.test(integer) && /^0*$/.test(fraction)
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const body = `${grouped}.${fraction}`

  if (isZero) return body
  if (sign === '-') return `${MINUS_SIGN}${body}`
  return signed ? `+${body}` : body
}

/** Sign of a decimal string without converting it to a number: 1, -1 or 0 (also for invalid). */
export function decimalSign(value: string | null | undefined): -1 | 0 | 1 {
  if (value == null) return 0
  const match = DECIMAL_PATTERN.exec(value.trim())
  if (!match) return 0
  const [, sign = '', integer = '', fraction = ''] = match
  if (/^0*$/.test(integer + fraction)) return 0
  return sign === '-' ? -1 : 1
}

const minuteFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: MARKET_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function nyMinuteParts(date: Date): { date: string; time: string } | null {
  if (Number.isNaN(date.getTime())) return null
  const parts: Partial<Record<Intl.DateTimeFormatPartTypes, string>> = {}
  for (const part of minuteFormatter.formatToParts(date)) {
    parts[part.type] = part.value
  }
  const { year, month, day, hour, minute } = parts
  if (!year || !month || !day || !hour || !minute) return null
  return { date: `${year}-${month}-${day}`, time: `${hour}:${minute}` }
}

/**
 * Formats an ISO-8601 UTC instant in America/New_York to minute precision, without zone name:
 * "2026-11-02T15:00:00Z" → "2026-11-02 10:00". Returns EMPTY_VALUE for null / invalid input.
 */
export function formatDateTimeNY(instant: string | null | undefined): string {
  if (instant == null || instant.trim() === '') return EMPTY_VALUE
  const parts = nyMinuteParts(new Date(instant))
  return parts ? `${parts.date} ${parts.time}` : EMPTY_VALUE
}

/**
 * Current New York local date-time as "YYYY-MM-DDTHH:mm" — the format of
 * `<input type="datetime-local">` and of the API's `occurredAtLocal` fields.
 */
export function nowLocalDateTimeNY(now: Date = new Date()): string {
  const parts = nyMinuteParts(now)
  if (!parts) throw new Error('Invalid date')
  return `${parts.date}T${parts.time}`
}
