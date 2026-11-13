import { Button, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { auditUrl } from '../audit/auditLinks'
import type { OptionTrade } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import {
  assignmentRowText,
  isAssignmentAction,
  isDeleteOnlyAction,
  SIGNED_MONEY_COLORS,
  SPECIAL_ACTION_COLORS,
  tradeActionLabel,
} from './tradeLabels'

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
 * 修改 / 删除 / 历史 (FEAT-005); 到期作废 (FEAT-012) and 被指派 (FEAT-013) rows have no 修改.
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
      render: (_value: unknown, trade) =>
        // FEAT-012 / FEAT-013: 到期作废 and 被指派 rows stand out with their own colors.
        isDeleteOnlyAction(trade.action) ? (
          <Tag
            color={SPECIAL_ACTION_COLORS[trade.action]}
            data-testid="trade-cell-action"
            style={{ marginInlineEnd: 0 }}
          >
            {tradeActionLabel(trade.action)}
          </Tag>
        ) : (
          <span data-testid="trade-cell-action" style={{ whiteSpace: 'nowrap' }}>
            {tradeActionLabel(trade.action)}
          </span>
        ),
    },
    {
      title: '合约',
      key: 'contract',
      render: (_value: unknown, trade) => {
        const assignmentText = assignmentRowText(trade)
        return (
          <>
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
            {assignmentText !== null && (
              <div>
                <Typography.Text
                  type="secondary"
                  style={{ fontSize: 12, whiteSpace: 'nowrap' }}
                  data-testid="trade-cell-assignment"
                >
                  {assignmentText}
                </Typography.Text>
              </div>
            )}
          </>
        )
      },
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
      render: (_value: unknown, trade) =>
        // FEAT-013: a 被指派 has no option cash of its own — net 0.00 uncolored (its cash change,
        // the stock purchase, is in the small text under the contract).
        isAssignmentAction(trade.action) ? (
          <span data-testid="trade-cell-net-amount" style={{ fontVariantNumeric: 'tabular-nums' }}>
            {formatMoney(trade.netAmount)}
          </span>
        ) : (
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
          {/* FEAT-012 / FEAT-013: 到期作废 and 被指派 cannot be edited (delete and re-record). */}
          {!isDeleteOnlyAction(trade.action) && (
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
          )}
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
          {trade.hasAuditLog && (
            <Link to={auditUrl('OPTION_TRADE', trade.id)} data-testid="trade-history">
              历史
            </Link>
          )}
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
