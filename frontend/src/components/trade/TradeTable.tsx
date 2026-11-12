import { Button, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { auditUrl } from '../audit/auditLinks'
import type { OptionTrade } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { SIGNED_MONEY_COLORS, tradeActionLabel } from './tradeLabels'

interface TradeTableProps {
  trades: OptionTrade[]
  loading: boolean
  /** Shown instead of rows when `trades` is empty. */
  empty: ReactNode
  onContractClick: (trade: OptionTrade) => void
  /** FEAT-005 row actions. */
  onEdit: (trade: OptionTrade) => void
  onDelete: (trade: OptionTrade) => void
}

/**
 * Trade rows in the order returned by the backend; values are only formatted. Each row has
 * 修改 / 删除 / 历史 (FEAT-005).
 */
export function TradeTable({
  trades,
  loading,
  empty,
  onContractClick,
  onEdit,
  onDelete,
}: TradeTableProps) {
  const columns: ColumnsType<OptionTrade> = [
    {
      title: '成交时间（美东时间）',
      key: 'tradedAt',
      render: (_value: unknown, trade) => (
        <span data-testid="trade-cell-time" style={{ whiteSpace: 'nowrap' }}>
          {formatDateTimeNY(trade.tradedAt)}
        </span>
      ),
    },
    {
      title: '方向',
      key: 'action',
      render: (_value: unknown, trade) => (
        <span data-testid="trade-cell-action" style={{ whiteSpace: 'nowrap' }}>
          {tradeActionLabel(trade.action)}
        </span>
      ),
    },
    {
      title: '合约',
      key: 'contract',
      render: (_value: unknown, trade) => (
        <Button
          type="link"
          size="small"
          style={{ padding: 0 }}
          title="按此合约筛选"
          onClick={() => {
            onContractClick(trade)
          }}
          data-testid="trade-cell-contract"
        >
          {trade.contractLabel}
        </Button>
      ),
    },
    {
      title: '张数',
      key: 'contracts',
      align: 'right',
      render: (_value: unknown, trade) => (
        <span data-testid="trade-cell-contracts" style={{ whiteSpace: 'nowrap' }}>
          {trade.contracts}
          {trade.multiplier !== 100 && `（multiplier ${String(trade.multiplier)}）`}
        </span>
      ),
    },
    {
      title: '净额',
      key: 'cashChange',
      align: 'right',
      render: (_value: unknown, trade) => (
        <span
          data-testid="trade-cell-net-amount"
          style={{
            color: SIGNED_MONEY_COLORS[decimalSign(trade.cashChange)],
            fontVariantNumeric: 'tabular-nums',
          }}
        >
          {formatMoney(trade.cashChange, { signed: true })}
        </span>
      ),
    },
    {
      title: '冻结变动',
      key: 'frozenChange',
      align: 'right',
      render: (_value: unknown, trade) => (
        <span data-testid="trade-cell-frozen" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {decimalSign(trade.frozenChange) === 0
            ? EMPTY_VALUE
            : formatMoney(trade.frozenChange, { signed: true })}
        </span>
      ),
    },
    {
      title: '备注',
      key: 'note',
      render: (_value: unknown, trade) => {
        const note = trade.note?.trim() ? trade.note : null
        return (
          <Typography.Text
            data-testid="trade-cell-note"
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
          <Button
            size="small"
            type="link"
            onClick={() => {
              onEdit(trade)
            }}
            data-testid="trade-edit"
          >
            修改
          </Button>
          <Button
            size="small"
            type="link"
            danger
            onClick={() => {
              onDelete(trade)
            }}
            data-testid="trade-delete"
          >
            删除
          </Button>
          <Link to={auditUrl('OPTION_TRADE', trade.id)} data-testid="trade-history">
            历史
          </Link>
        </Space>
      ),
    },
  ]

  return (
    <Table<OptionTrade>
      rowKey="id"
      size="small"
      columns={columns}
      dataSource={trades}
      loading={loading}
      pagination={false}
      onRow={(trade) =>
        ({ 'data-testid': 'trade-row', 'data-trade-id': trade.id }) as HTMLAttributes<HTMLElement>
      }
      locale={{ emptyText: empty }}
      scroll={{ x: 'max-content' }}
    />
  )
}
