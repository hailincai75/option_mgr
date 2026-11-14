import { Typography } from 'antd'
import type { UnrealizedFields } from '../../api/types'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import { unrealizedDisplay } from './unrealizedLabels'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

interface CellProps {
  row: UnrealizedFields
  /** Test id prefix: `position` (option rows) or `stock-position` (stock rows). */
  testIdPrefix: string
}

/**
 * FEAT-017 市值 cell: the backend's `marketValue` with an explicit sign (option and stock rows:
 * `+370.00` / `−125.00` / `0.00`), or the backend's `unrealizedLabel` in gray when it cannot be computed.
 */
export function MarketValueCell({ row, testIdPrefix }: CellProps) {
  const display = unrealizedDisplay(row)
  const testId = `${testIdPrefix}-market-value`
  const status = row.unrealizedStatus ?? ''
  if (display.kind === 'label') {
    return (
      <Typography.Text type="secondary" data-testid={testId} data-status={status} style={NUMERIC}>
        {display.label}
      </Typography.Text>
    )
  }
  return (
    <span data-testid={testId} data-status={status} style={NUMERIC}>
      {display.marketValue}
    </span>
  )
}

/**
 * FEAT-017 未实现盈亏 cell: the backend's signed `unrealizedPnl` (正绿负红) with the percent in small
 * text below (`+22.80%`, `—` when null), or the backend's `unrealizedLabel` in gray.
 */
export function UnrealizedPnlCell({ row, testIdPrefix }: CellProps) {
  const display = unrealizedDisplay(row)
  const testId = `${testIdPrefix}-unrealized-pnl`
  const status = row.unrealizedStatus ?? ''
  if (display.kind === 'label') {
    return (
      <Typography.Text type="secondary" data-testid={testId} data-status={status} style={NUMERIC}>
        {display.label}
      </Typography.Text>
    )
  }
  const color = SIGNED_MONEY_COLORS[display.sign]
  return (
    <div style={{ ...NUMERIC, textAlign: 'right', lineHeight: 1.3 }}>
      <div data-testid={testId} data-status={status} style={{ color }}>
        {display.pnl}
      </div>
      <div data-testid={`${testId}-percent`} style={{ color, fontSize: 12 }}>
        {display.percent}
      </div>
    </div>
  )
}
