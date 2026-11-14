import { Button, Space, Typography } from 'antd'
import { useAccountQuery } from '../../account/useAccountQuery'
import { ApiError } from '../../api/client'
import type { UnrealizedSummaryGroup } from '../../api/types'
import { getUnrealizedSummary, unrealizedSummaryQueryKeys } from '../../api/unrealizedSummary'
import { decimalSign, formatMoney } from '../../utils/format'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import { UNREALIZED_NOTE } from './unrealizedLabels'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

const GROUPS = [
  { key: 'option', label: '期权' },
  { key: 'stock', label: '股票' },
  { key: 'total', label: '合计' },
] as const

function SummaryGroup({
  name,
  label,
  group,
}: {
  name: string
  label: string
  group: UnrealizedSummaryGroup
}) {
  const prefix = `unrealized-summary-${name}`
  return (
    <span data-testid={prefix} style={{ whiteSpace: 'nowrap' }}>
      {label} 市值{' '}
      <span data-testid={`${prefix}-market-value`} style={NUMERIC}>
        {formatMoney(group.marketValue, { signed: true })}
      </span>{' '}
      未实现{' '}
      <span
        data-testid={`${prefix}-unrealized-pnl`}
        style={{ ...NUMERIC, color: SIGNED_MONEY_COLORS[decimalSign(group.unrealizedPnl)] }}
      >
        {formatMoney(group.unrealizedPnl, { signed: true })}
      </span>
    </span>
  )
}

/**
 * FEAT-017 合计 line of the positions page: `GET /api/positions/unrealized-summary` for the current
 * account (never filtered by the 到期 filter, Q158). All numbers and the 未计入 sentence are the
 * backend's; it reloads whenever the positions do (same invalidations).
 */
export function UnrealizedSummaryBar() {
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    queryKey: unrealizedSummaryQueryKeys.all,
    queryFn: ({ signal }) => getUnrealizedSummary(signal),
  })

  let line
  if (isPending) {
    line = (
      <Typography.Text type="secondary" data-testid="unrealized-summary-loading">
        未实现盈亏合计加载中…
      </Typography.Text>
    )
  } else if (error) {
    const message = error instanceof ApiError ? `${error.message} (${error.code})` : error.message
    line = (
      <Space size={8} wrap>
        <Typography.Text type="danger" data-testid="unrealized-summary-error">
          无法加载未实现盈亏合计：{message}
        </Typography.Text>
        <Button
          size="small"
          loading={isFetching}
          onClick={() => void refetch()}
          data-testid="unrealized-summary-retry"
        >
          重新加载合计
        </Button>
      </Space>
    )
  } else {
    line = (
      <Space size={12} wrap>
        <span data-testid="unrealized-summary-totals">
          {GROUPS.map((group, index) => (
            <span key={group.key}>
              {index > 0 && ' ｜ '}
              <SummaryGroup name={group.key} label={group.label} group={data[group.key]} />
            </span>
          ))}
        </span>
        {data.excludedLabel !== null && (
          <Typography.Text type="secondary" data-testid="unrealized-summary-excluded">
            {data.excludedLabel}
          </Typography.Text>
        )}
      </Space>
    )
  }

  return (
    <div data-testid="unrealized-summary">
      {line}
      <div>
        <Typography.Text
          type="secondary"
          style={{ fontSize: 12 }}
          data-testid="unrealized-summary-note"
        >
          {UNREALIZED_NOTE}
        </Typography.Text>
      </div>
    </div>
  )
}
