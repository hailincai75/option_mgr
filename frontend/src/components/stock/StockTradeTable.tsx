import { Button, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { StockTrade } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatInteger, formatMoney } from '../../utils/format'
import { auditUrl } from '../audit/auditLinks'
import { tradesUrlForAssignment, tradesUrlForExercise } from '../position/positionLinks'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import { SoldLotTable } from './SoldLotTable'
import {
  ASSIGN_BUY_HINT,
  EXERCISE_STOCK_HINT,
  isAssignmentStockType,
  isExerciseStockType,
  STOCK_TYPE_COLORS,
  stockTimeText,
  stockTypeLabel,
} from './stockLabels'

const AMOUNT_PREFIX: Record<string, string> = {
  TRANSFER_IN: '总成本',
  ASSIGN_BUY: '成本',
  ASSIGN_SELL: '实收',
  EXERCISE_BUY: '成本',
  EXERCISE_SELL: '实收',
}

/**
 * "−1,500.65" / "+1,200.00" colored by cash direction; transfers "总成本 750.00" and (FEAT-013)
 * 指派买入 "成本 13,130.00", (FEAT-041) 指派卖出 "实收 2,785.00" and (FEAT-014) 行权买入
 * "成本 9,460.00" / 行权卖出 "实收 4,380.00" uncolored (no cash of their own).
 */
export function StockAmountText({ trade, testId }: { trade: StockTrade; testId: string }) {
  if (
    trade.type === 'TRANSFER_IN' ||
    isAssignmentStockType(trade.type) ||
    isExerciseStockType(trade.type)
  ) {
    return (
      <span data-testid={testId} style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
        {AMOUNT_PREFIX[trade.type] ?? '金额'} {formatMoney(trade.amount)}
      </span>
    )
  }
  return (
    <span
      data-testid={testId}
      style={{
        color: SIGNED_MONEY_COLORS[decimalSign(trade.cashChange)],
        fontVariantNumeric: 'tabular-nums',
        whiteSpace: 'nowrap',
      }}
    >
      {formatMoney(trade.cashChange, { signed: true })}
    </span>
  )
}

/** Realized P&L (sales only): green / red, "—" for other types. */
export function StockPnlText({ value, testId }: { value: string | null; testId: string }) {
  if (value === null) return <span data-testid={testId}>{EMPTY_VALUE}</span>
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

function SoldDetail({ trade }: { trade: StockTrade }) {
  return (
    <Space orientation="vertical" size={8} style={{ width: '100%' }} data-testid="stock-sold-detail">
      <SoldLotTable lots={trade.soldLots ?? []} />
      <Space size={24} wrap>
        <span>
          被卖出成本合计{' '}
          <span data-testid="stock-sold-cost">{formatMoney(trade.soldCost)}</span>
        </span>
        <span>
          已实现盈亏 <StockPnlText value={trade.realizedPnl} testId="stock-sold-pnl" />
        </span>
      </Space>
    </Space>
  )
}

interface StockTradeTableProps {
  trades: StockTrade[]
  loading: boolean
  /** Shown instead of rows when `trades` is empty. */
  empty: ReactNode
  onSymbolClick: (trade: StockTrade) => void
  onEdit: (trade: StockTrade) => void
  onDelete: (trade: StockTrade) => void
}

/**
 * Stock records in backend order (FEAT-011); values are only formatted. Sell rows expand to the
 * lots they sold (FEAT-041: 指派卖出 too). Rows have 修改 / 删除 by the backend's `editable` /
 * `deletable` (FEAT-013 / FEAT-041: not on 指派买入 / 指派卖出, which show a hint and a link to
 * their 被指派 record; FEAT-014: same for 行权买入 / 行权卖出 and their 行权 record), and 历史 when the
 * record has audit records.
 */
export function StockTradeTable({
  trades,
  loading,
  empty,
  onSymbolClick,
  onEdit,
  onDelete,
}: StockTradeTableProps) {
  const columns: ColumnsType<StockTrade> = [
    {
      title: '时间（美东）',
      key: 'time',
      render: (_value: unknown, trade) => (
        <span data-testid="stock-cell-time" style={{ whiteSpace: 'nowrap' }}>
          {stockTimeText(trade)}
        </span>
      ),
    },
    {
      title: '类型',
      key: 'type',
      render: (_value: unknown, trade) => (
        <Tag
          color={STOCK_TYPE_COLORS[trade.type]}
          data-testid="stock-cell-type"
          style={{ marginInlineEnd: 0 }}
        >
          {stockTypeLabel(trade.type)}
        </Tag>
      ),
    },
    {
      title: '标的',
      key: 'symbol',
      render: (_value: unknown, trade) => (
        <>
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            title="按此标的筛选"
            onClick={() => {
              onSymbolClick(trade)
            }}
            data-testid="stock-cell-symbol"
          >
            {trade.symbol}
          </Button>
          {trade.assignmentTradeId !== null && (
            <div>
              <Link
                to={tradesUrlForAssignment(trade.symbol, trade.tradedAtLocal)}
                style={{ fontSize: 12, whiteSpace: 'nowrap' }}
                data-testid="stock-assignment-link"
              >
                来自被指派 #{trade.assignmentTradeId}
              </Link>
            </div>
          )}
          {trade.exerciseTradeId != null && (
            <div>
              <Link
                to={tradesUrlForExercise(trade.symbol, trade.tradedAtLocal)}
                style={{ fontSize: 12, whiteSpace: 'nowrap' }}
                data-testid="stock-exercise-link"
              >
                来自行权 #{trade.exerciseTradeId}
              </Link>
            </div>
          )}
        </>
      ),
    },
    {
      title: '股数',
      key: 'shares',
      align: 'right',
      render: (_value: unknown, trade) => (
        <span data-testid="stock-cell-shares">{formatInteger(trade.shares)}</span>
      ),
    },
    {
      title: '金额',
      key: 'amount',
      align: 'right',
      render: (_value: unknown, trade) => (
        <StockAmountText trade={trade} testId="stock-cell-amount" />
      ),
    },
    {
      title: '每股',
      key: 'pricePerShare',
      align: 'right',
      render: (_value: unknown, trade) => (
        <Typography.Text
          type="secondary"
          style={{ fontSize: 12, whiteSpace: 'nowrap' }}
          data-testid="stock-cell-price"
        >
          每股 {formatMoney(trade.pricePerShare)}
        </Typography.Text>
      ),
    },
    {
      title: '已实现盈亏',
      key: 'realizedPnl',
      align: 'right',
      render: (_value: unknown, trade) => (
        <StockPnlText value={trade.realizedPnl} testId="stock-cell-pnl" />
      ),
    },
    {
      title: '备注',
      key: 'note',
      render: (_value: unknown, trade) => {
        const note = trade.note?.trim() ? trade.note : null
        return (
          <Typography.Text
            data-testid="stock-cell-note"
            style={{ maxWidth: 240 }}
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
      render: (_value: unknown, trade) => (
        <Space size={4} style={{ whiteSpace: 'nowrap' }}>
          {trade.editable && (
            <Button
              size="small"
              type="link"
              onClick={() => {
                onEdit(trade)
              }}
              data-testid="stock-edit"
            >
              修改
            </Button>
          )}
          {trade.deletable && (
            <Button
              size="small"
              type="link"
              danger
              onClick={() => {
                onDelete(trade)
              }}
              data-testid="stock-delete"
            >
              删除
            </Button>
          )}
          {isAssignmentStockType(trade.type) && (
            <Typography.Text
              type="secondary"
              style={{ fontSize: 12 }}
              data-testid="stock-assign-buy-hint"
            >
              {ASSIGN_BUY_HINT}
            </Typography.Text>
          )}
          {isExerciseStockType(trade.type) && (
            <Typography.Text
              type="secondary"
              style={{ fontSize: 12 }}
              data-testid="stock-exercise-hint"
            >
              {EXERCISE_STOCK_HINT}
            </Typography.Text>
          )}
          {trade.hasAuditLog && (
            <Link to={auditUrl('STOCK_TRADE', trade.id)} data-testid="stock-history">
              历史
            </Link>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Table<StockTrade>
      rowKey="id"
      size="small"
      columns={columns}
      dataSource={trades}
      loading={loading}
      pagination={false}
      onRow={(trade) =>
        ({
          'data-testid': 'stock-row',
          'data-stock-trade-id': trade.id,
          'data-stock-type': trade.type,
        }) as HTMLAttributes<HTMLElement>
      }
      expandable={{
        rowExpandable: (trade) => trade.soldLots !== null,
        expandedRowRender: (trade) => <SoldDetail trade={trade} />,
        expandIcon: ({ expanded, onExpand, record }) =>
          record.soldLots === null ? null : (
            <Button
              type="text"
              size="small"
              aria-expanded={expanded}
              aria-label={expanded ? '收起被卖出批次' : '展开被卖出批次'}
              onClick={(event) => {
                onExpand(record, event)
              }}
              data-testid="stock-expand"
            >
              {expanded ? '−' : '+'}
            </Button>
          ),
      }}
      locale={{ emptyText: empty }}
      scroll={{ x: 'max-content' }}
    />
  )
}
