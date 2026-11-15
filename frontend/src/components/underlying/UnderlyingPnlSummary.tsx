import { Space, Typography } from 'antd'
import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAccount } from '../../account/AccountContext'
import type { PnlBreakdown, UnderlyingSummaryGroup } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatMoney } from '../../utils/format'
import { closedUrlForSymbol, EMPTY_PNL_RANGE } from '../pnl/pnlSummarySearch'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

/** What one card's summary line shows: the backend group, still loading, or failed. */
export type UnderlyingPnlState =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; group: UnderlyingSummaryGroup }

/** Gray `—` for a null backend value. */
function EmptyValue({ testId }: { testId: string }) {
  return (
    <Typography.Text type="secondary" data-testid={testId} style={NUMERIC}>
      {EMPTY_VALUE}
    </Typography.Text>
  )
}

/** P&L figure: explicit sign, 正绿负红, 0.00 uncolored; null → gray `—`. */
function PnlMoney({ value, testId }: { value: string | null; testId: string }) {
  if (value === null) return <EmptyValue testId={testId} />
  return (
    <span data-testid={testId} style={{ ...NUMERIC, color: SIGNED_MONEY_COLORS[decimalSign(value)] }}>
      {formatMoney(value, { signed: true })}
    </span>
  )
}

/** 市值 / 净权利金: explicit sign (Q162), not colored; null → gray `—`. */
function SignedPlain({ value, testId }: { value: string | null; testId: string }) {
  if (value === null) return <EmptyValue testId={testId} />
  return (
    <span data-testid={testId} style={NUMERIC}>
      {formatMoney(value, { signed: true })}
    </span>
  )
}

/** 期权 / 股票 part of the realized figure: `—` when that type has 0 records (Q72 / Q91). */
function BreakdownPart({ part, testId }: { part: PnlBreakdown; testId: string }) {
  if (part.closedCount === 0) return <EmptyValue testId={testId} />
  return <PnlMoney value={part.realizedPnl} testId={testId} />
}

/**
 * 已实现 link to the 已平仓 page for the symbol (all types and dates). FEAT-047 (Q235 B, same as
 * the Q231 "历史" link): in 全部账户 mode a card's realized figure is that account's only, so a
 * click first switches the current account to the card's account, then opens the page — whose
 * total then matches the card. In single-account mode it is a plain link.
 */
function RealizedLink({
  symbol,
  accountId,
  color,
  children,
}: {
  symbol: string
  accountId: number | undefined
  color: string | undefined
  children: ReactNode
}) {
  const { isAllAccounts, selectAccount } = useAccount()
  const navigate = useNavigate()
  const to = closedUrlForSymbol(symbol, EMPTY_PNL_RANGE)
  const style = { ...NUMERIC, color }
  if (!isAllAccounts || accountId === undefined) {
    return (
      <Link to={to} data-testid="underlying-pnl-realized" title="查看该标的的已平仓记录" style={style}>
        {children}
      </Link>
    )
  }
  return (
    <Link
      to={to}
      data-testid="underlying-pnl-realized"
      data-account-id={accountId}
      title="切换到该账户并查看该标的的已平仓记录"
      style={style}
      onClick={(event) => {
        event.preventDefault()
        selectAccount(accountId)
        void navigate(to)
      }}
    >
      {children}
    </Link>
  )
}

function Item({ label, children }: { label: string; children: ReactNode }) {
  return (
    <span style={{ whiteSpace: 'nowrap' }}>
      <Typography.Text type="secondary">{label}</Typography.Text> {children}
    </span>
  )
}

/**
 * FEAT-023 card summary line (`GET /api/positions/underlying-summary`): 已实现 (link to the 已平仓
 * page for the symbol, all types and dates) with a small 期权 · 股票 split, 未实现, 合计, 市值,
 * 净权利金, and the backend's 未计入 sentence below. Formatting only — every number is the backend's.
 */
export function UnderlyingPnlSummary({ symbol, state }: { symbol: string; state: UnderlyingPnlState }) {
  if (state.kind === 'loading') {
    return (
      <div data-testid="underlying-pnl">
        <Typography.Text type="secondary" data-testid="underlying-pnl-loading">
          盈亏加载中…
        </Typography.Text>
      </div>
    )
  }
  if (state.kind === 'error') {
    return (
      <div data-testid="underlying-pnl">
        <Typography.Text type="secondary" data-testid="underlying-pnl-unavailable">
          盈亏暂不可用
        </Typography.Text>
      </div>
    )
  }
  const { group } = state
  const realizedColor = SIGNED_MONEY_COLORS[decimalSign(group.realized.realizedPnl)]
  return (
    <div data-testid="underlying-pnl">
      <Space size={[16, 4]} wrap>
        <Item label="已实现">
          <RealizedLink symbol={symbol} accountId={group.accountId} color={realizedColor}>
            {formatMoney(group.realized.realizedPnl, { signed: true })}
          </RealizedLink>{' '}
          <span style={{ fontSize: 12 }} data-testid="underlying-pnl-realized-split">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              期权
            </Typography.Text>{' '}
            <BreakdownPart part={group.realized.option} testId="underlying-pnl-realized-option" />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {' · 股票 '}
            </Typography.Text>
            <BreakdownPart part={group.realized.stock} testId="underlying-pnl-realized-stock" />
          </span>
        </Item>
        <Item label="未实现">
          <PnlMoney value={group.unrealizedPnl} testId="underlying-pnl-unrealized" />
        </Item>
        <Item label="合计">
          <PnlMoney value={group.totalPnl} testId="underlying-pnl-total" />
        </Item>
        <Item label="市值">
          <SignedPlain value={group.marketValue} testId="underlying-pnl-market-value" />
        </Item>
        <Item label="净权利金">
          <SignedPlain value={group.netPremium} testId="underlying-pnl-net-premium" />
        </Item>
      </Space>
      {group.excludedLabel !== null && (
        <div>
          <Typography.Text type="secondary" data-testid="underlying-pnl-excluded">
            {group.excludedLabel}
          </Typography.Text>
        </div>
      )}
    </div>
  )
}
