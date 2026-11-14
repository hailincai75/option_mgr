import { Alert } from 'antd'
import { useTradingCalendarStatus } from './tradingCalendar'

/**
 * FEAT-009 rule 6: "休市日数据未更新（…）" above the positions page and the trade entry form while
 * the latest holiday fetch did not succeed. Shown only when the backend says `stale`, with its
 * `warning` text verbatim. Nothing is shown while loading or when the status request fails (the
 * pages keep working; this is only a hint).
 */
export function TradingCalendarStaleAlert() {
  const { data } = useTradingCalendarStatus()
  if (data?.stale !== true || data.warning == null || data.warning === '') return null
  return (
    <Alert
      type="warning"
      showIcon
      title={<span data-testid="trading-calendar-stale-warning">{data.warning}</span>}
    />
  )
}
