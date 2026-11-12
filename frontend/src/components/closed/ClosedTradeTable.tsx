import { Button, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { ClosedTrade, CoveredLot } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { auditUrl } from '../audit/auditLinks'
import { tradesUrlForContract } from '../position/positionLinks'
import { SIGNED_MONEY_COLORS, tradeActionLabel } from '../trade/tradeLabels'

const DIRECTION_TAGS: Record<string, { label: string; color: string }> = {
  SHORT: { label: '空头', color: 'orange' },
  LONG: { label: '多头', color: 'blue' },
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

/** Total on top, per-contract average (display only, Q22) as small text below. */
function MoneyWithPerContract({
  total,
  perContract,
  testId,
}: {
  total: string
  perContract: string
  testId: string
}) {
  return (
    <div style={{ textAlign: 'right' }}>
      <SignedMoney value={total} testId={testId} />
      <div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid={`${testId}-per`}>
          每张 {formatMoney(perContract, { signed: true })}
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
    title: '合约',
    key: 'contract',
    render: (_value: unknown, trade) => (
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
      const tag = DIRECTION_TAGS[trade.closedDirection]
      return (
        <Space size={4} style={{ whiteSpace: 'nowrap' }}>
          <span data-testid="closed-cell-action">{tradeActionLabel(trade.action)}</span>
          <Tag color={tag?.color} data-testid="closed-cell-direction">
            {tag?.label ?? trade.closedDirection}
          </Tag>
        </Space>
      )
    },
  },
  {
    title: '张数',
    key: 'contracts',
    align: 'right',
    render: (_value: unknown, trade) => (
      <span data-testid="closed-cell-contracts" style={{ whiteSpace: 'nowrap' }}>
        {trade.contracts}
        {trade.multiplier !== 100 && `（multiplier ${String(trade.multiplier)}）`}
      </span>
    ),
  },
  {
    title: '开仓净额合计',
    key: 'openingNetTotal',
    align: 'right',
    render: (_value: unknown, trade) => (
      <MoneyWithPerContract
        total={trade.openingNetTotal}
        perContract={trade.openingNetPerContract}
        testId="closed-cell-opening-net"
      />
    ),
  },
  {
    title: '平仓净额',
    key: 'closeNetAmount',
    align: 'right',
    render: (_value: unknown, trade) => (
      <MoneyWithPerContract
        total={trade.closeNetAmount}
        perContract={trade.closeNetPerContract}
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
    render: (_value: unknown, trade) => (
      <Link to={auditUrl('OPTION_TRADE', trade.id)} data-testid="closed-history">
        历史
      </Link>
    ),
  },
]

interface ClosedTradeTableProps {
  trades: ClosedTrade[]
  loading: boolean
  empty: ReactNode
}

/** Read-only closed trades in backend order; each row expands to its covered lots (FIFO). */
export function ClosedTradeTable({ trades, loading, empty }: ClosedTradeTableProps) {
  return (
    <Table<ClosedTrade>
      rowKey="id"
      size="small"
      columns={columns}
      dataSource={trades}
      loading={loading}
      pagination={false}
      onRow={(trade) =>
        ({ 'data-testid': 'closed-row', 'data-trade-id': trade.id }) as HTMLAttributes<HTMLElement>
      }
      expandable={{
        expandedRowRender: (trade) => (
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
        ),
        expandIcon: ({ expanded, onExpand, record }) => (
          <Button
            type="text"
            size="small"
            aria-expanded={expanded}
            aria-label={expanded ? '收起被平批次' : '展开被平批次'}
            onClick={(event) => {
              onExpand(record, event)
            }}
            data-testid="closed-expand"
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
