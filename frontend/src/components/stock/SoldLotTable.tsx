import { Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import type { StockSoldLot } from '../../api/types'
import { formatInteger, formatMoney } from '../../utils/format'
import { stockTimeText, stockTypeLabel } from './stockLabels'

const columns: ColumnsType<StockSoldLot> = [
  {
    title: '来源',
    key: 'source',
    render: (_value: unknown, lot) => (
      <span data-testid="sold-lot-source" style={{ whiteSpace: 'nowrap' }}>
        {stockTypeLabel(lot.type)} #{lot.tradeId}
      </span>
    ),
  },
  {
    title: '批次时间（美东）',
    key: 'time',
    render: (_value: unknown, lot) => (
      <span data-testid="sold-lot-time" style={{ whiteSpace: 'nowrap' }}>
        {stockTimeText(lot)}
      </span>
    ),
  },
  {
    title: '股数',
    key: 'shares',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="sold-lot-shares">{formatInteger(lot.shares)} 股</span>
    ),
  },
  {
    title: '成本',
    key: 'cost',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="sold-lot-cost" style={{ fontVariantNumeric: 'tabular-nums' }}>
        {formatMoney(lot.cost)}
      </span>
    ),
  },
]

/** Lots a stock sale took shares from (FIFO order, backend values); rows `sold-lot-row`. */
export function SoldLotTable({ lots }: { lots: StockSoldLot[] }) {
  return (
    <Table<StockSoldLot>
      rowKey="tradeId"
      size="small"
      pagination={false}
      dataSource={lots}
      columns={columns}
      onRow={(lot) =>
        ({
          'data-testid': 'sold-lot-row',
          'data-trade-id': lot.tradeId,
        }) as HTMLAttributes<HTMLElement>
      }
    />
  )
}
