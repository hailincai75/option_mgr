import { Button, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { StockPosition, StockPositionLot } from '../../api/types'
import { EMPTY_VALUE, formatInteger, formatMoney } from '../../utils/format'
import { stockTimeText, stockTypeLabel } from './stockLabels'
import { stocksUrlForSymbol } from './stockListFilters'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

const lotColumns: ColumnsType<StockPositionLot> = [
  {
    title: '来源',
    key: 'source',
    render: (_value: unknown, lot) => (
      <span data-testid="stock-lot-source" style={{ whiteSpace: 'nowrap' }}>
        {stockTypeLabel(lot.type)} #{lot.tradeId}
      </span>
    ),
  },
  {
    title: '时间（美东）',
    key: 'time',
    render: (_value: unknown, lot) => (
      <span data-testid="stock-lot-time" style={{ whiteSpace: 'nowrap' }}>
        {stockTimeText(lot)}
      </span>
    ),
  },
  {
    title: '原股数',
    key: 'originalShares',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="stock-lot-original-shares" style={NUMERIC}>
        {formatInteger(lot.originalShares)}
      </span>
    ),
  },
  {
    title: '剩余股数',
    key: 'remainingShares',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="stock-lot-remaining-shares" style={NUMERIC}>
        {formatInteger(lot.remainingShares)}
      </span>
    ),
  },
  {
    title: '剩余成本',
    key: 'remainingCost',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="stock-lot-remaining-cost" style={NUMERIC}>
        {formatMoney(lot.remainingCost)}
      </span>
    ),
  },
  {
    title: '备注',
    key: 'note',
    render: (_value: unknown, lot) => {
      const note = lot.note?.trim() ? lot.note : null
      return (
        <Typography.Text
          data-testid="stock-lot-note"
          style={{ maxWidth: 240 }}
          ellipsis={note === null ? false : { tooltip: note }}
          title={note ?? undefined}
        >
          {note ?? EMPTY_VALUE}
        </Typography.Text>
      )
    },
  },
]

function StockLotTable({ lots }: { lots: StockPositionLot[] }) {
  return (
    <Table<StockPositionLot>
      rowKey="tradeId"
      size="small"
      columns={lotColumns}
      dataSource={lots}
      pagination={false}
      onRow={(lot) =>
        ({
          'data-testid': 'stock-lot-row',
          'data-trade-id': lot.tradeId,
        }) as HTMLAttributes<HTMLElement>
      }
      data-testid="stock-position-lots"
    />
  )
}

interface StockPositionTableProps {
  positions: StockPosition[]
  loading: boolean
  empty: ReactNode
  /** "卖出": opens the stock entry form with type SELL and the symbol prefilled. */
  onSell: (position: StockPosition) => void
}

/** Stock positions in backend order (symbol ascending); rows expand to their FIFO lots. */
export function StockPositionTable({ positions, loading, empty, onSell }: StockPositionTableProps) {
  const columns: ColumnsType<StockPosition> = [
    {
      title: '标的',
      key: 'symbol',
      render: (_value: unknown, position) => (
        <Link
          to={stocksUrlForSymbol(position.symbol)}
          data-testid="stock-position-symbol"
          title="查看该标的的股票交易"
        >
          {position.symbol}
        </Link>
      ),
    },
    {
      title: '持股',
      key: 'shares',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="stock-position-shares" style={NUMERIC}>
          {formatInteger(position.shares)}
        </span>
      ),
    },
    // FEAT-039: shares covering short calls and the rest (both from the backend).
    {
      title: '被覆盖',
      key: 'coveredShares',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="stock-position-covered-shares" style={NUMERIC}>
          {formatInteger(position.coveredShares)}
        </span>
      ),
    },
    {
      title: '可用',
      key: 'availableShares',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="stock-position-available-shares" style={NUMERIC}>
          {formatInteger(position.availableShares)}
        </span>
      ),
    },
    {
      title: '总成本',
      key: 'totalCost',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="stock-position-total-cost" style={NUMERIC}>
          {formatMoney(position.totalCost)}
        </span>
      ),
    },
    {
      title: '每股平均成本',
      key: 'averageCost',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="stock-position-average-cost" style={NUMERIC}>
          {formatMoney(position.averageCost)}
        </span>
      ),
    },
    {
      title: '批次数',
      key: 'lotCount',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="stock-position-lot-count">{position.lotCount}</span>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_value: unknown, position) => (
        <Button
          size="small"
          onClick={() => {
            onSell(position)
          }}
          data-testid="stock-position-sell"
        >
          卖出
        </Button>
      ),
    },
  ]

  return (
    <Table<StockPosition>
      rowKey="symbol"
      size="small"
      columns={columns}
      dataSource={positions}
      loading={loading}
      pagination={false}
      onRow={(position) =>
        ({
          'data-testid': 'stock-position-row',
          'data-symbol': position.symbol,
        }) as HTMLAttributes<HTMLElement>
      }
      expandable={{
        expandedRowRender: (position) => <StockLotTable lots={position.lots} />,
        expandIcon: ({ expanded, onExpand, record }) => (
          <Button
            type="text"
            size="small"
            aria-expanded={expanded}
            aria-label={expanded ? `收起 ${record.symbol} 的批次` : `展开 ${record.symbol} 的批次`}
            onClick={(event) => {
              onExpand(record, event)
            }}
            data-testid="stock-position-expand"
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
