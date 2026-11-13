import { Card, Space, Spin, Typography } from 'antd'
import { useEffect, useRef } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAccountQuery } from '../../account/useAccountQuery'
import { getStockPositions, stockPositionQueryKeys } from '../../api/stockTrades'
import { QueryError } from '../QueryError'
import { stockSellUrl } from './stockListFilters'
import { STOCK_POSITIONS_ANCHOR } from './stockPositionsAnchor'
import { StockPositionTable } from './StockPositionTable'

/**
 * 股票持仓 section of the positions page (FEAT-011): `GET /api/stock-positions`, backend order,
 * expandable FIFO lots, "卖出" opens the stock entry form prefilled. Every number is the backend's.
 * FEAT-040: the section has the anchor `#stock-positions` (home page link); when the URL has that
 * hash it scrolls into view once it and the content above it (`layoutReady`) have loaded.
 */
export function StockPositionsCard({ layoutReady = true }: { layoutReady?: boolean }) {
  const navigate = useNavigate()
  const { hash } = useLocation()
  const scrolled = useRef(false)
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: stockPositionQueryKeys.all,
    queryFn: ({ signal }) => getStockPositions(signal),
  })

  useEffect(() => {
    if (hash !== `#${STOCK_POSITIONS_ANCHOR}`) {
      scrolled.current = false
      return
    }
    if (scrolled.current || !layoutReady || isPending) return
    scrolled.current = true
    document.getElementById(STOCK_POSITIONS_ANCHOR)?.scrollIntoView({ block: 'start' })
  }, [hash, layoutReady, isPending])

  let content
  if (isPending) {
    content = (
      <div data-testid="stock-positions-loading" style={{ textAlign: 'center', padding: 24 }}>
        <Spin />
      </div>
    )
  } else if (error) {
    content = (
      <QueryError
        title="无法加载股票持仓"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="stock-positions"
      />
    )
  } else {
    content = (
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          总成本含手续费，部分卖出按比例扣减成本（四舍五入到分）；暂不检查 covered call 的股票覆盖
        </Typography.Text>
        <StockPositionTable
          positions={data.positions}
          loading={isFetching}
          onSell={(position) => {
            void navigate(stockSellUrl(position.symbol))
          }}
          empty={
            <Space orientation="vertical" data-testid="stock-positions-empty">
              <span>暂无股票持仓</span>
              <Link to="/stocks/new" data-testid="stock-positions-empty-new">
                录入股票交易
              </Link>
            </Space>
          }
        />
      </Space>
    )
  }

  return (
    <Card
      title={
        <Space size={16}>
          <span>股票持仓</span>
          {data !== undefined && (
            <Typography.Text type="secondary" data-testid="stock-positions-count">
              共 {data.positionCount} 个标的
            </Typography.Text>
          )}
        </Space>
      }
      extra={
        <Link to="/stocks/new" data-testid="stock-positions-new">
          录入股票交易
        </Link>
      }
      id={STOCK_POSITIONS_ANCHOR}
      data-testid="stock-positions"
    >
      {content}
    </Card>
  )
}
