import { Tag, Tooltip, Typography } from 'antd'
import type { OptionPosition } from '../../api/types'
import { decimalSign, formatMoney } from '../../utils/format'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'

/**
 * Option position cells shared by the positions page (FEAT-003) and the 按标的 page (FEAT-021),
 * so both show the backend values the same way. `testIdPrefix` defaults to the positions page's.
 */

const DIRECTION_TAGS: Record<string, { label: string; color: string }> = {
  SHORT: { label: '空头', color: 'orange' },
  LONG: { label: '多头', color: 'blue' },
}

/** `SHORT` → 空头, `LONG` → 多头 (unknown values shown as-is). */
export function DirectionTag({ direction, testId }: { direction: string; testId: string }) {
  const tag = DIRECTION_TAGS[direction]
  return (
    <Tag color={tag?.color} data-testid={testId}>
      {tag?.label ?? direction}
    </Tag>
  )
}

/** Backend money with "+" / "−" and green / red by sign. */
export function SignedMoney({ value, testId }: { value: string; testId: string }) {
  return (
    <span
      data-testid={testId}
      style={{
        color: SIGNED_MONEY_COLORS[decimalSign(value)],
        fontVariantNumeric: 'tabular-nums',
        whiteSpace: 'nowrap',
      }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

const EXPIRY_PENDING_HINT = '16:00 已停止交易；17:30 前仍可能被指派，17:30（美东）起可作废'

/**
 * FEAT-012 (Q10 / Q83): 到期待确认 between 16:00 and 17:30 NY on the expiration date, shown from
 * the backend's `expiryPending` (never computed here); meanwhile short rows only have 被指派
 * (FEAT-013 / FEAT-041) and long rows only 行权 (FEAT-014).
 */
function ExpiryPendingTag({ testId }: { testId: string }) {
  return (
    <Tooltip title={EXPIRY_PENDING_HINT}>
      <Tag color="processing" data-testid={testId} style={{ cursor: 'help' }}>
        到期待确认
      </Tag>
    </Tooltip>
  )
}

/**
 * DTE from the backend: number, "0 今天到期", or red "已过期 N 天" (no client-side date math).
 * Test ids: `{prefix}-cell-dte`, `{prefix}-cell-dte-today`, `{prefix}-expiry-pending`.
 */
export function DteCell({
  position,
  testIdPrefix = 'position',
}: {
  position: OptionPosition
  testIdPrefix?: string
}) {
  const testId = `${testIdPrefix}-cell-dte`
  if (position.expirationStatus === 'EXPIRED') {
    return (
      <Typography.Text type="danger" strong data-testid={testId}>
        已过期 {position.daysPastExpiration} 天
      </Typography.Text>
    )
  }
  if (position.expirationStatus === 'EXPIRES_TODAY') {
    return (
      <span data-testid={testId} style={{ whiteSpace: 'nowrap' }}>
        {position.dte}{' '}
        <Tag color="warning" data-testid={`${testId}-today`}>
          今天到期
        </Tag>
        {position.expiryPending && <ExpiryPendingTag testId={`${testIdPrefix}-expiry-pending`} />}
      </span>
    )
  }
  return <span data-testid={testId}>{position.dte}</span>
}
