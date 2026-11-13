import { useAccountQuery } from '../account/useAccountQuery'
import { Card, Space, Spin, Typography } from 'antd'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { getOptionPositions, optionPositionQueryKeys } from '../api/optionPositions'
import { ClosePositionModal } from '../components/position/ClosePositionModal'
import { ExpirePositionModal } from '../components/position/ExpirePositionModal'
import { PositionTable } from '../components/position/PositionTable'
import { QueryError } from '../components/QueryError'
import { StockPositionsCard } from '../components/stock/StockPositionsCard'
import type { OptionPosition } from '../api/types'
import { formatMoney } from '../utils/format'

/**
 * 持仓 page (FEAT-003): every current option position from `GET /api/option-positions`,
 * in backend order, with expandable FIFO lots, a 平仓 button (FEAT-004) on closable rows and a
 * 作废 button (FEAT-012) on expirable rows; below it the 股票持仓 section (FEAT-011). Every
 * number comes from the backend.
 */
export function PositionsPage() {
  const [closing, setClosing] = useState<OptionPosition | null>(null)
  const [expiring, setExpiring] = useState<OptionPosition | null>(null)
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: optionPositionQueryKeys.all,
    queryFn: ({ signal }) => getOptionPositions(signal),
  })

  let content
  if (isPending) {
    content = (
      <div data-testid="positions-loading" style={{ textAlign: 'center', padding: 24 }}>
        <Spin />
      </div>
    )
  } else if (error) {
    content = (
      <QueryError
        title="无法加载持仓"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="positions"
      />
    )
  } else {
    content = (
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Space size={24} wrap data-testid="positions-summary">
          <Typography.Text data-testid="positions-as-of">
            截至 {data.asOfDate}（美东）
          </Typography.Text>
          <Typography.Text data-testid="positions-count">
            共 {data.positionCount} 个持仓
          </Typography.Text>
          <Typography.Text data-testid="positions-frozen-total">
            冻结现金合计 {formatMoney(data.frozenTotal)}
          </Typography.Text>
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          开仓净额：空头为实收、多头为实付，均已含手续费；每张平均净额四舍五入到分，仅供参考。点击合约查看该合约的交易。
        </Typography.Text>
        <PositionTable
          positions={data.positions}
          loading={isFetching}
          onClosePosition={setClosing}
          onExpirePosition={setExpiring}
          empty={
            <Space orientation="vertical" data-testid="positions-empty">
              <span>暂无持仓</span>
              <Link to="/trades/new" data-testid="positions-empty-new">
                录入开仓
              </Link>
            </Space>
          }
        />
      </Space>
    )
  }

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      <Card title="期权持仓" data-testid="positions-page">
        {content}
        {closing !== null && (
          <ClosePositionModal
            key={closing.contractLabel}
            position={closing}
            onDone={() => {
              setClosing(null)
            }}
          />
        )}
        {expiring !== null && (
          <ExpirePositionModal
            key={expiring.contractLabel}
            position={expiring}
            onDone={() => {
              setExpiring(null)
            }}
          />
        )}
      </Card>
      {/* FEAT-011: stock positions below the option positions. */}
      <StockPositionsCard layoutReady={!isPending} />
    </Space>
  )
}
