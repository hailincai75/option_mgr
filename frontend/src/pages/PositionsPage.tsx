import { useAccountQuery } from '../account/useAccountQuery'
import { Button, Card, Radio, Space, Spin, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { getOptionPositions, optionPositionQueryKeys } from '../api/optionPositions'
import { AdjustExpirationModal } from '../components/position/AdjustExpirationModal'
import { AssignPositionModal } from '../components/position/AssignPositionModal'
import { BatchExpireModal } from '../components/position/BatchExpireModal'
import {
  batchExpirableRows,
  batchExpireButtonLabel,
  showsBatchExpire,
} from '../components/position/batchExpire'
import { ClosePositionModal } from '../components/position/ClosePositionModal'
import { ExercisePositionModal } from '../components/position/ExercisePositionModal'
import { ExpirePositionModal } from '../components/position/ExpirePositionModal'
import {
  EXPIRY_FILTER_OPTIONS,
  expiryEmptyMessage,
  filterPositionsByExpiry,
  parseExpirySearch,
  STOCK_HIDDEN_NOTICE,
  withExpirySearch,
  type ExpiryFilter,
} from '../components/position/expiryFilter'
import { AdhocClosureAlerts } from '../components/calendar/AdhocClosureAlerts'
import { TradingCalendarStaleAlert } from '../components/calendar/TradingCalendarStaleAlert'
import { firstAdhocClosureRow } from '../components/position/adhocClosure'
import { PositionTable } from '../components/position/PositionTable'
import { QueryError } from '../components/QueryError'
import { StockPositionsCard } from '../components/stock/StockPositionsCard'
import type { AdhocClosureAlert, OptionPosition } from '../api/types'
import { formatMoney } from '../utils/format'

/**
 * 持仓 page (FEAT-003): every current option position from `GET /api/option-positions`,
 * in backend order, with expandable FIFO lots, a 平仓 button (FEAT-004) on closable rows and a
 * 作废 button (FEAT-012) on expirable rows, a 被指派 button (FEAT-013) on short puts / calls and a
 * 行权 button (FEAT-014) on long calls / puts; below it the 股票持仓 section (FEAT-011). Every
 * number comes from the backend. FEAT-010: the 到期 filter (`?expiry=`) shows only the matching
 * option rows (by the backend's DTE, backend order kept) and hides the stock section while active.
 */
export function PositionsPage() {
  const [closing, setClosing] = useState<OptionPosition | null>(null)
  const [expiring, setExpiring] = useState<OptionPosition | null>(null)
  const [assigning, setAssigning] = useState<OptionPosition | null>(null)
  const [exercising, setExercising] = useState<OptionPosition | null>(null)
  /** FEAT-043: row whose 调整到期日 dialog is open. */
  const [adjusting, setAdjusting] = useState<OptionPosition | null>(null)
  /** FEAT-015: snapshot of the batch-expirable rows when 批量作废 was clicked. */
  const [batchExpiring, setBatchExpiring] = useState<OptionPosition[] | null>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const expiryFilter = parseExpirySearch(searchParams)
  const filtering = expiryFilter !== 'all'
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: optionPositionQueryKeys.all,
    queryFn: ({ signal }) => getOptionPositions(signal),
  })
  /** FEAT-042 去调整: highlighted row and a pending scroll to it (cleared once scrolled). */
  const [highlighted, setHighlighted] = useState<string | null>(null)
  const pendingScroll = useRef<string | null>(null)
  const [scrollRequest, setScrollRequest] = useState(0)
  const shownPositions = useMemo(
    () => (data ? filterPositionsByExpiry(data.positions, expiryFilter) : null),
    [data, expiryFilter],
  )

  useEffect(() => {
    const contract = pendingScroll.current
    if (contract === null || shownPositions === null) return
    // Waits for the row when the 到期 filter was just cleared (runs again with the new rows).
    const row = Array.from(document.querySelectorAll('[data-testid="position-row"]')).find(
      (element) => element.getAttribute('data-contract') === contract,
    )
    if (row === undefined) return
    pendingScroll.current = null
    row.scrollIntoView({ block: 'center' })
  }, [scrollRequest, shownPositions])

  const adjustTo = (alert: AdhocClosureAlert) => {
    const target = data ? firstAdhocClosureRow(data.positions, alert.date) : undefined
    if (target === undefined) return
    // The 到期 filter may hide the row: show every position so it can be scrolled to.
    if (filterPositionsByExpiry([target], expiryFilter).length === 0) {
      setSearchParams(withExpirySearch(searchParams, 'all'))
    }
    setHighlighted(target.contractLabel)
    pendingScroll.current = target.contractLabel
    setScrollRequest((count) => count + 1)
  }

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
    const shown = shownPositions ?? []
    const filteredEmpty = expiryEmptyMessage(expiryFilter)
    // FEAT-015 rule 1 (Q118): 已过期未处理 / 今天到期 only; N = shown rows the backend lets expire.
    const batchRows = showsBatchExpire(expiryFilter) ? batchExpirableRows(shown) : []
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
          {filtering && (
            <Typography.Text data-testid="positions-filter-count">
              显示 {shown.length} / {data.positionCount} 个持仓
            </Typography.Text>
          )}
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          开仓净额：空头为实收、多头为实付，均已含手续费；每张平均净额四舍五入到分，仅供参考。点击合约查看该合约的交易。
        </Typography.Text>
        {batchRows.length > 0 && (
          <div>
            <Button
              danger
              onClick={() => {
                setBatchExpiring(batchRows)
              }}
              data-testid="positions-batch-expire"
            >
              {batchExpireButtonLabel(batchRows.length)}
            </Button>
          </div>
        )}
        <PositionTable
          positions={shown}
          loading={isFetching}
          onClosePosition={setClosing}
          onExpirePosition={setExpiring}
          onAssignPosition={setAssigning}
          onExercisePosition={setExercising}
          onAdjustExpiration={setAdjusting}
          highlightedContract={highlighted}
          empty={
            filteredEmpty !== null && data.positions.length > 0 ? (
              <span data-testid="positions-expiry-empty">{filteredEmpty}</span>
            ) : (
              <Space orientation="vertical" data-testid="positions-empty">
                <span>暂无持仓</span>
                <Link to="/trades/new" data-testid="positions-empty-new">
                  录入开仓
                </Link>
              </Space>
            )
          }
        />
      </Space>
    )
  }

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }}>
      {/* FEAT-042 rule 4: 去调整 scrolls to (and highlights) the first affected row. */}
      <AdhocClosureAlerts onAdjust={adjustTo} />
      {/* FEAT-009 rule 6: shown only while the holiday data is stale. */}
      <TradingCalendarStaleAlert />
      <Card title="期权持仓" data-testid="positions-page">
        <div
          style={{ marginBottom: 12 }}
          data-testid="positions-expiry-filter"
          data-value={expiryFilter}
        >
          <Space size={8} wrap>
            <Typography.Text>到期</Typography.Text>
            <Radio.Group
              optionType="button"
              size="small"
              value={expiryFilter}
              onChange={(event) => {
                const value: unknown = event.target.value
                const next: ExpiryFilter =
                  EXPIRY_FILTER_OPTIONS.find((option) => option.value === value)?.value ?? 'all'
                setSearchParams(withExpirySearch(searchParams, next))
              }}
            >
              {EXPIRY_FILTER_OPTIONS.map((option) => (
                <Radio.Button key={option.value} value={option.value}>
                  <span data-testid={`positions-expiry-filter-${option.value}`}>
                    {option.label}
                  </span>
                </Radio.Button>
              ))}
            </Radio.Group>
          </Space>
        </div>
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
        {batchExpiring !== null && (
          <BatchExpireModal
            positions={batchExpiring}
            onDone={() => {
              setBatchExpiring(null)
            }}
          />
        )}
        {assigning !== null && (
          <AssignPositionModal
            key={assigning.contractLabel}
            position={assigning}
            onDone={() => {
              setAssigning(null)
            }}
          />
        )}
        {exercising !== null && (
          <ExercisePositionModal
            key={exercising.contractLabel}
            position={exercising}
            onDone={() => {
              setExercising(null)
            }}
          />
        )}
        {adjusting !== null && (
          <AdjustExpirationModal
            key={adjusting.contractLabel}
            position={adjusting}
            onDone={() => {
              setAdjusting(null)
            }}
          />
        )}
      </Card>
      {/* FEAT-011: stock positions below the option positions; FEAT-010 (Q109): hidden while filtering. */}
      {filtering ? (
        <Card title="股票持仓" data-testid="positions-stock-hidden-card">
          <Typography.Text type="secondary" data-testid="positions-stock-hidden">
            {STOCK_HIDDEN_NOTICE}
          </Typography.Text>
        </Card>
      ) : (
        <StockPositionsCard layoutReady={!isPending} />
      )}
    </Space>
  )
}
