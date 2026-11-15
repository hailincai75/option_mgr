import { Card, Empty, Space, Spin, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import { Link } from 'react-router-dom'
import { useAccountQuery } from '../account/useAccountQuery'
import { getOptionPositions, optionPositionQueryKeys } from '../api/optionPositions'
import { getStockPositions, stockPositionQueryKeys } from '../api/stockTrades'
import type { OptionPosition, StockPosition } from '../api/types'
import { DirectionTag, DteCell, SignedMoney } from '../components/position/PositionCells'
import { tradesUrlForContract } from '../components/position/positionLinks'
import { QueryError } from '../components/QueryError'
import { stocksUrlForSymbol } from '../components/stock/stockListFilters'
import {
  groupByUnderlying,
  underlyingSummary,
  type UnderlyingGroup,
} from '../components/underlying/groupByUnderlying'
import { formatInteger, formatMoney } from '../utils/format'

const NUMERIC = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const

/** FEAT-021 rule 2: 合约 / 方向 / 张数 / 开仓净额 / 到期日 / DTE — same values as the positions page. */
const optionColumns: ColumnsType<OptionPosition> = [
  {
    title: '合约',
    key: 'contract',
    render: (_value: unknown, position) => (
      <Link
        to={tradesUrlForContract(position)}
        data-testid="underlying-option-cell-contract"
        style={{ whiteSpace: 'nowrap' }}
      >
        {position.contractLabel}
      </Link>
    ),
  },
  {
    title: '方向',
    key: 'direction',
    render: (_value: unknown, position) => (
      <DirectionTag direction={position.direction} testId="underlying-option-cell-direction" />
    ),
  },
  {
    title: '张数',
    key: 'contracts',
    align: 'right',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-option-cell-contracts" style={NUMERIC}>
        {position.contracts}
        {position.multiplier !== 100 && `（multiplier ${String(position.multiplier)}）`}
      </span>
    ),
  },
  {
    title: '开仓净额',
    key: 'openingNetTotal',
    align: 'right',
    render: (_value: unknown, position) => (
      <SignedMoney value={position.openingNetTotal} testId="underlying-option-cell-opening-net" />
    ),
  },
  {
    title: '到期日',
    key: 'expirationDate',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-option-cell-expiration" style={{ whiteSpace: 'nowrap' }}>
        {position.expirationDate}
      </span>
    ),
  },
  {
    title: 'DTE',
    key: 'dte',
    align: 'right',
    render: (_value: unknown, position) => (
      <DteCell position={position} testIdPrefix="underlying-option" />
    ),
  },
]

/** FEAT-021 rule 2: 持股 / 被覆盖 / 可用 / 总成本 / 每股平均成本 (4 decimals, Q88) from the backend. */
const stockColumns: ColumnsType<StockPosition> = [
  {
    title: '持股',
    key: 'shares',
    align: 'right',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-stock-cell-shares" style={NUMERIC}>
        {formatInteger(position.shares)}
      </span>
    ),
  },
  {
    title: '被覆盖',
    key: 'coveredShares',
    align: 'right',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-stock-cell-covered-shares" style={NUMERIC}>
        {formatInteger(position.coveredShares)}
      </span>
    ),
  },
  {
    title: '可用',
    key: 'availableShares',
    align: 'right',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-stock-cell-available-shares" style={NUMERIC}>
        {formatInteger(position.availableShares)}
      </span>
    ),
  },
  {
    title: '总成本',
    key: 'totalCost',
    align: 'right',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-stock-cell-total-cost" style={NUMERIC}>
        {formatMoney(position.totalCost)}
      </span>
    ),
  },
  {
    title: '每股平均成本',
    key: 'averageCost',
    align: 'right',
    render: (_value: unknown, position) => (
      <span data-testid="underlying-stock-cell-average-cost" style={NUMERIC}>
        {formatMoney(position.averageCost)}
      </span>
    ),
  },
]

function UnderlyingCard({ group }: { group: UnderlyingGroup }) {
  return (
    <Card
      size="small"
      data-testid="underlying-card"
      data-symbol={group.symbol}
      title={
        <Space size={12} wrap>
          <Link
            to={stocksUrlForSymbol(group.symbol)}
            data-testid="underlying-card-symbol"
            title="查看该标的的股票交易"
          >
            {group.symbol}
          </Link>
          <Typography.Text type="secondary" data-testid="underlying-card-summary">
            {underlyingSummary(group)}
          </Typography.Text>
        </Space>
      }
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        {group.options.length > 0 && (
          <div data-testid="underlying-options">
            <Typography.Text strong>期权</Typography.Text>
            <Table<OptionPosition>
              rowKey="contractLabel"
              size="small"
              columns={optionColumns}
              dataSource={group.options}
              pagination={false}
              onRow={(position) =>
                ({
                  'data-testid': 'underlying-option-row',
                  'data-contract': position.contractLabel,
                }) as HTMLAttributes<HTMLElement>
              }
              scroll={{ x: 'max-content' }}
            />
          </div>
        )}
        {group.stock !== null && (
          <div data-testid="underlying-stock">
            <Typography.Text strong>股票</Typography.Text>
            <Table<StockPosition>
              rowKey="symbol"
              size="small"
              columns={stockColumns}
              dataSource={[group.stock]}
              pagination={false}
              onRow={(position) =>
                ({
                  'data-testid': 'underlying-stock-row',
                  'data-symbol': position.symbol,
                }) as HTMLAttributes<HTMLElement>
              }
              scroll={{ x: 'max-content' }}
            />
          </div>
        )}
      </Space>
    </Card>
  )
}

/**
 * FEAT-021 策略 → 按标的: one read-only card per symbol with current option and stock positions.
 * Reuses the positions page's queries (same keys), so every write that refreshes the positions
 * page refreshes this page too. Never shows market value, unrealized P&L or Greeks (Q187).
 */
export function UnderlyingGroupsPage() {
  const options = useAccountQuery({
    queryKey: optionPositionQueryKeys.all,
    queryFn: ({ signal }) => getOptionPositions(signal),
  })
  const stocks = useAccountQuery({
    queryKey: stockPositionQueryKeys.all,
    queryFn: ({ signal }) => getStockPositions(signal),
  })

  let content
  const error = options.error ?? stocks.error
  if (error) {
    content = (
      <QueryError
        title="无法加载持仓"
        error={error}
        retrying={options.isFetching || stocks.isFetching}
        onRetry={() => {
          if (options.error) void options.refetch()
          if (stocks.error) void stocks.refetch()
        }}
        testIdPrefix="underlying"
      />
    )
  } else if (options.data === undefined || stocks.data === undefined) {
    content = (
      <div data-testid="underlying-loading" style={{ textAlign: 'center', padding: 24 }}>
        <Spin />
      </div>
    )
  } else {
    const groups = groupByUnderlying(options.data.positions, stocks.data.positions)
    content =
      groups.length === 0 ? (
        <Empty description={<span data-testid="underlying-empty">暂无持仓</span>} />
      ) : (
        <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          {groups.map((group) => (
            <UnderlyingCard key={group.symbol} group={group} />
          ))}
        </Space>
      )
  }

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="underlying-page">
      <div>
        <Typography.Title level={3} style={{ marginTop: 0 }} data-testid="underlying-title">
          按标的
        </Typography.Title>
        <Typography.Text type="secondary" data-testid="underlying-hint">
          按标的列出当前账户正在持有的期权和股票，只读；操作请到持仓页
        </Typography.Text>
      </div>
      {content}
    </Space>
  )
}
