import { useQuery } from '@tanstack/react-query'
import { Typography } from 'antd'
import { getMarketDataStatus, marketDataQueryKeys } from '../../api/marketData'
import { greeksNote } from './greeksLabels'

/**
 * FEAT-018 / FEAT-052 gray note above the positions table while 显示 Greeks is on. The text
 * depends on the backend's market-data status (`optionSource`, `riskFreeRatePercent`), read from
 * the same cached query as the 立即刷新 bar; while loading or on error the Yahoo text with `—`.
 */
export function GreeksNote() {
  const { data: status } = useQuery({
    queryKey: marketDataQueryKeys.status(),
    queryFn: ({ signal }) => getMarketDataStatus(signal),
  })
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="positions-greeks-note">
      {greeksNote(status)}
    </Typography.Text>
  )
}
