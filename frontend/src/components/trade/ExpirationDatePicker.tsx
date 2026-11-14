import { DatePicker } from 'antd'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useEffectEvent, useMemo, useRef } from 'react'
import type { LocalDateString, NonTradingDay } from '../../api/types'
import { monthOf, type YearMonth } from '../calendar/tradingCalendar'

const DATE_FORMAT = 'YYYY-MM-DD'
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/

/** A real calendar date typed as "YYYY-MM-DD" (e.g. not 2026-02-30). */
function isCompleteDate(text: string): boolean {
  if (!DATE_PATTERN.test(text)) return false
  const parsed = dayjs(text)
  return parsed.isValid() && parsed.format(DATE_FORMAT) === text
}

function toDayjs(value: string | undefined): Dayjs | null {
  if (value === undefined || !isCompleteDate(value)) return null
  return dayjs(value)
}

export interface ExpirationDatePickerProps {
  /** "YYYY-MM-DD" or "" (from Form.Item). */
  value?: string
  /** Called with "YYYY-MM-DD" or "" (to Form.Item). */
  onChange?: (value: string) => void
  id?: string
  /** Non-trading days loaded from the backend; disabled in the panel when `restrict`. */
  nonTradingDays: ReadonlyMap<LocalDateString, NonTradingDay>
  /** FEAT-009: only opens (BTO / STO) must expire on a trading day; closes are not restricted. */
  restrict: boolean
  /** Month shown in the panel ("YYYY-MM"); the parent loads that month's non-trading days. */
  viewMonth: YearMonth
  onViewMonthChange: (month: YearMonth) => void
  /** Month to show when opening the panel without a value (today in New York). */
  defaultMonth: YearMonth
  /** FEAT-043: earliest / latest selectable date (inclusive, from the backend); others disabled. */
  minDate?: LocalDateString | null
  maxDate?: LocalDateString | null
  /** Prefix of each day cell's testid (`<prefix>-YYYY-MM-DD`). */
  dayTestIdPrefix?: string
  'data-testid'?: string
}

/**
 * 到期日 picker of the trade entry form (FEAT-009 rule 8). Form value stays a "YYYY-MM-DD" string.
 * - Panel: non-trading days from the backend are disabled (only for opens); the frontend never
 *   decides itself what a weekend or holiday is.
 * - Typing: a complete date is taken over as the value at once — including a non-trading day,
 *   which the picker itself would silently drop; the form's rule then shows
 *   "所选日期不是美股交易日" under the field.
 */
export function ExpirationDatePicker({
  value,
  onChange,
  id,
  nonTradingDays,
  restrict,
  viewMonth,
  onViewMonthChange,
  defaultMonth,
  minDate = null,
  maxDate = null,
  dayTestIdPrefix = 'open-trade-expiration-day',
  'data-testid': testId,
}: ExpirationDatePickerProps) {
  const wrapperRef = useRef<HTMLDivElement>(null)
  // Same object while the value is unchanged: a new Dayjs on every render makes the picker
  // treat it as a new value and jump the panel back to its month (month flips were undone).
  const pickerDate = useMemo(() => toDayjs(value), [value])

  const commit = (next: string) => {
    if (next === (value ?? '')) return
    onChange?.(next)
    const month = monthOf(next)
    if (month !== null) onViewMonthChange(month)
  }

  const onTyped = useEffectEvent((text: string) => {
    const trimmed = text.trim()
    if (trimmed === '') commit('')
    else if (isCompleteDate(trimmed)) commit(trimmed)
  })

  // The picker only commits typed text on blur / Enter and drops disabled dates; listen to the
  // native input ourselves so typed dates (and pasted / autofilled ones) reach the form directly.
  useEffect(() => {
    const input = wrapperRef.current?.querySelector('input')
    if (!input) return
    const listener = () => {
      onTyped(input.value)
    }
    input.addEventListener('input', listener)
    input.addEventListener('change', listener)
    return () => {
      input.removeEventListener('input', listener)
      input.removeEventListener('change', listener)
    }
  }, [])

  const isDisabled = (date: Dayjs) => {
    const text = date.format(DATE_FORMAT)
    // "YYYY-MM-DD" strings sort in date order; the bounds themselves come from the backend.
    if (minDate !== null && text < minDate) return true
    if (maxDate !== null && text > maxDate) return true
    return restrict && nonTradingDays.has(text)
  }

  return (
    <div ref={wrapperRef}>
      <DatePicker
        id={id}
        value={pickerDate}
        format={DATE_FORMAT}
        placeholder={DATE_FORMAT}
        style={{ width: '100%' }}
        data-testid={testId}
        disabledDate={isDisabled}
        pickerValue={dayjs(`${viewMonth}-01`)}
        onPickerValueChange={(date) => {
          onViewMonthChange(date.format('YYYY-MM'))
        }}
        onOpenChange={(open) => {
          if (open) onViewMonthChange(monthOf(value) ?? defaultMonth)
        }}
        onChange={(date) => {
          commit(date === null ? '' : date.format(DATE_FORMAT))
        }}
        cellRender={(current, info) => {
          if (info.type !== 'date' || typeof current === 'number' || typeof current === 'string') {
            return info.originNode
          }
          const date = current.format(DATE_FORMAT)
          const nonTrading = restrict ? nonTradingDays.get(date) : undefined
          return (
            <div
              className="ant-picker-cell-inner"
              title={nonTrading?.name}
              data-testid={`${dayTestIdPrefix}-${date}`}
              data-non-trading={nonTrading !== undefined ? 'true' : undefined}
              data-disabled={isDisabled(current) ? 'true' : undefined}
            >
              {current.date()}
            </div>
          )
        }}
      />
    </div>
  )
}
