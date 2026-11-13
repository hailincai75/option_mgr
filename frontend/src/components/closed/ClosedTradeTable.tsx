import { Button, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ClosedSoldLot, ClosedTrade, CoveredLot } from '../../api/types'
import {
  decimalSign,
  EMPTY_VALUE,
  formatDateTimeNY,
  formatInteger,
  formatMoney,
} from '../../utils/format'
import { auditUrl } from '../audit/auditLinks'
import { tradesUrlForContract } from '../position/positionLinks'
import { stockTimeText, stockTypeLabel, STOCK_TYPE_COLORS } from '../stock/stockLabels'
import { stocksUrlForSymbol } from '../stock/stockListFilters'
import { isExpiryAction, SIGNED_MONEY_COLORS, tradeActionLabel } from '../trade/tradeLabels'

const DIRECTION_TAGS: Record<string, { label: string; color: string }> = {
  SHORT: { label: '空头', color: 'orange' },
  LONG: { label: '多头', color: 'blue' },
}

/** FEAT-040: record type tags (期权 / 股票 told apart by color). */
const RECORD_TYPE_TAGS: Record<string, { label: string; color: string }> = {
  OPTION: { label: '期权', color: 'geekblue' },
  STOCK: { label: '股票', color: 'cyan' },
}

/** Row key: option trade ids and stock record ids are separate sequences (FEAT-040). */
function closedRowKey(trade: Pick<ClosedTrade, 'type' | 'id'>): string {
  return `${trade.type}-${String(trade.id)}`
}

function SignedMoney({
  value,
  testId,
  strong,
}: {
  value: string
  testId: string
  strong?: boolean
}) {
  return (
    <span
      data-testid={testId}
      style={{
        color: SIGNED_MONEY_COLORS[decimalSign(value)],
        fontVariantNumeric: 'tabular-nums',
        fontWeight: strong ? 600 : undefined,
        whiteSpace: 'nowrap',
      }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

/**
 * Total on top, per-unit average as small text below: options "每张 ±X.XX" (Q22), stocks
 * "每股 X.XXXX" (4 decimals as returned by the backend, Q88). Display only.
 */
function MoneyWithPerUnit({
  total,
  perUnit,
  testId,
}: {
  total: string
  perUnit: string
  testId: string
}) {
  return (
    <div style={{ textAlign: 'right' }}>
      <SignedMoney value={total} testId={testId} />
      <div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid={`${testId}-per`}>
          {perUnit}
        </Typography.Text>
      </div>
    </div>
  )
}

const lotColumns: ColumnsType<CoveredLot> = [
  {
    title: '开仓时间（美东）',
    key: 'time',
    render: (_value: unknown, lot) => (
      <span data-testid="closed-lot-time">{formatDateTimeNY(lot.tradedAt)}</span>
    ),
  },
  {
    title: '方向',
    key: 'action',
    render: (_value: unknown, lot) => (
      <span data-testid="closed-lot-action">{tradeActionLabel(lot.action)}</span>
    ),
  },
  {
    title: '张数',
    key: 'contracts',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="closed-lot-contracts">{lot.contracts} 张</span>
    ),
  },
  {
    title: '开仓净额',
    key: 'netAmount',
    align: 'right',
    render: (_value: unknown, lot) => (
      <SignedMoney value={lot.netAmount} testId="closed-lot-net-amount" />
    ),
  },
]

/** FEAT-040: lots a stock sale took shares from (FIFO, earliest first). */
const soldLotColumns: ColumnsType<ClosedSoldLot> = [
  {
    title: '批次时间（美东）',
    key: 'time',
    render: (_value: unknown, lot) => (
      // 转入: the date only; 买入: "YYYY-MM-DD HH:mm" (backend local time).
      <span data-testid="closed-sold-lot-time" style={{ whiteSpace: 'nowrap' }}>
        {stockTimeText(lot)}
      </span>
    ),
  },
  {
    title: '来源',
    key: 'source',
    render: (_value: unknown, lot) => (
      <span data-testid="closed-sold-lot-source">{stockTypeLabel(lot.type)}</span>
    ),
  },
  {
    title: '股数',
    key: 'shares',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="closed-sold-lot-shares">{formatInteger(lot.shares)} 股</span>
    ),
  },
  {
    title: '成本',
    key: 'netAmount',
    align: 'right',
    render: (_value: unknown, lot) => (
      <SignedMoney value={lot.netAmount} testId="closed-sold-lot-cost" />
    ),
  },
]

const columns: ColumnsType<ClosedTrade> = [
  {
    title: '平仓时间（美东时间）',
    key: 'tradedAt',
    render: (_value: unknown, trade) => (
      <span data-testid="closed-cell-time" style={{ whiteSpace: 'nowrap' }}>
        {formatDateTimeNY(trade.tradedAt)}
      </span>
    ),
  },
  {
    title: '类型',
    key: 'type',
    render: (_value: unknown, trade) => {
      const tag = RECORD_TYPE_TAGS[trade.type]
      return (
        <Tag color={tag?.color} data-testid="closed-cell-type">
          {tag?.label ?? trade.type}
        </Tag>
      )
    },
  },
  {
    title: '合约 / 标的',
    key: 'contract',
    render: (_value: unknown, trade) =>
      trade.type === 'STOCK' ? (
        <Link
          to={stocksUrlForSymbol(trade.symbol)}
          data-testid="closed-cell-contract"
          style={{ whiteSpace: 'nowrap' }}
        >
          {trade.symbol}
        </Link>
      ) : (
        <Link
          to={tradesUrlForContract(trade)}
          data-testid="closed-cell-contract"
          style={{ whiteSpace: 'nowrap' }}
        >
          {trade.contractLabel}
        </Link>
      ),
  },
  {
    title: '方向',
    key: 'action',
    render: (_value: unknown, trade) => {
      if (trade.type === 'STOCK') {
        // Same tag as 卖出 on the 股票 page; stocks have no 空头 / 多头.
        return (
          <Tag color={STOCK_TYPE_COLORS.SELL} data-testid="closed-cell-action">
            {stockTypeLabel(trade.action)}
          </Tag>
        )
      }
      const tag = DIRECTION_TAGS[trade.closedDirection]
      return (
        <Space size={4} style={{ whiteSpace: 'nowrap' }}>
          {isExpiryAction(trade.action) ? (
            // FEAT-012: 到期作废 (close net 0.00, recorded at the expiration date 16:00 NY).
            <Tag color="purple" data-testid="closed-cell-action" style={{ marginInlineEnd: 0 }}>
              {tradeActionLabel(trade.action)}
            </Tag>
          ) : (
            <span data-testid="closed-cell-action">{tradeActionLabel(trade.action)}</span>
          )}
          <Tag color={tag?.color} data-testid="closed-cell-direction">
            {tag?.label ?? trade.closedDirection}
          </Tag>
        </Space>
      )
    },
  },
  {
    title: '张数 / 股数',
    key: 'contracts',
    align: 'right',
    render: (_value: unknown, trade) => (
      <span data-testid="closed-cell-contracts" style={{ whiteSpace: 'nowrap' }}>
        {trade.type === 'STOCK' ? (
          `${formatInteger(trade.shares)} 股`
        ) : (
          <>
            {trade.contracts}
            {trade.multiplier !== 100 && `（multiplier ${String(trade.multiplier)}）`}
          </>
        )}
      </span>
    ),
  },
  {
    title: '开仓净额合计 / 成本',
    key: 'openingNetTotal',
    align: 'right',
    render: (_value: unknown, trade) => (
      <MoneyWithPerUnit
        total={trade.openingNetTotal}
        perUnit={
          trade.type === 'STOCK'
            ? `每股 ${formatMoney(trade.costPerShare)}`
            : `每张 ${formatMoney(trade.openingNetPerContract, { signed: true })}`
        }
        testId="closed-cell-opening-net"
      />
    ),
  },
  {
    title: '平仓净额 / 卖出实收',
    key: 'closeNetAmount',
    align: 'right',
    render: (_value: unknown, trade) => (
      <MoneyWithPerUnit
        total={trade.closeNetAmount}
        perUnit={
          trade.type === 'STOCK'
            ? `每股 ${formatMoney(trade.pricePerShare)}`
            : `每张 ${formatMoney(trade.closeNetPerContract, { signed: true })}`
        }
        testId="closed-cell-close-net"
      />
    ),
  },
  {
    title: '已实现盈亏',
    key: 'realizedPnl',
    align: 'right',
    render: (_value: unknown, trade) => (
      <SignedMoney value={trade.realizedPnl} testId="closed-cell-pnl" strong />
    ),
  },
  {
    title: '备注',
    key: 'note',
    render: (_value: unknown, trade) => {
      const note = trade.note?.trim() ? trade.note : null
      return (
        <Typography.Text
          data-testid="closed-cell-note"
          style={{ maxWidth: 200 }}
          ellipsis={note === null ? false : { tooltip: note }}
          title={note ?? undefined}
        >
          {note ?? EMPTY_VALUE}
        </Typography.Text>
      )
    },
  },
  {
    title: '操作',
    key: 'actions',
    // 历史 only when the closing trade / sell record itself has audit records (FEAT-040: the
    // audit object is chosen by the row type, ids of the two types can be equal).
    render: (_value: unknown, trade) =>
      trade.hasAuditLog ? (
        <Link
          to={auditUrl(trade.type === 'STOCK' ? 'STOCK_TRADE' : 'OPTION_TRADE', trade.id)}
          data-testid="closed-history"
        >
          历史
        </Link>
      ) : null,
  },
]

function ExpandedLots({ trade }: { trade: ClosedTrade }) {
  if (trade.type === 'STOCK') {
    return (
      <Table<ClosedSoldLot>
        rowKey="tradeId"
        size="small"
        columns={soldLotColumns}
        dataSource={trade.soldLots}
        pagination={false}
        onRow={(lot) =>
          ({
            'data-testid': 'closed-sold-lot-row',
            'data-trade-id': lot.tradeId,
          }) as HTMLAttributes<HTMLElement>
        }
      />
    )
  }
  return (
    <Table<CoveredLot>
      rowKey="tradeId"
      size="small"
      columns={lotColumns}
      dataSource={trade.coveredLots}
      pagination={false}
      onRow={(lot) =>
        ({
          'data-testid': 'closed-lot-row',
          'data-trade-id': lot.tradeId,
        }) as HTMLAttributes<HTMLElement>
      }
    />
  )
}

interface ClosedTradeTableProps {
  trades: ClosedTrade[]
  loading: boolean
  empty: ReactNode
}

/**
 * Read-only closed records in backend order (option closes and FEAT-040 stock sales); each row
 * expands to its covered lots (options) or sold lots (stocks), FIFO. Rows are keyed by type + id.
 */
export function ClosedTradeTable({ trades, loading, empty }: ClosedTradeTableProps) {
  return (
    <Table<ClosedTrade>
      rowKey={closedRowKey}
      size="small"
      columns={columns}
      dataSource={trades}
      loading={loading}
      pagination={false}
      onRow={(trade) =>
        ({
          'data-testid': 'closed-row',
          'data-trade-id': trade.id,
          'data-record-type': trade.type,
        }) as HTMLAttributes<HTMLElement>
      }
      expandable={{
        expandedRowRender: (trade) => <ExpandedLots trade={trade} />,
        expandIcon: ({ expanded, onExpand, record }) => {
          const what = record.type === 'STOCK' ? '被卖出批次' : '被平批次'
          return (
            <Button
              type="text"
              size="small"
              aria-expanded={expanded}
              aria-label={expanded ? `收起${what}` : `展开${what}`}
              onClick={(event) => {
                onExpand(record, event)
              }}
              data-testid="closed-expand"
            >
              {expanded ? '−' : '+'}
            </Button>
          )
        },
      }}
      locale={{ emptyText: empty }}
      scroll={{ x: 'max-content' }}
    />
  )
}
