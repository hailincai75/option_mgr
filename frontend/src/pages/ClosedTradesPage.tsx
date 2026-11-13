import { useAccountQuery } from '../account/useAccountQuery'
import { Alert, Button, Card, Pagination, Radio, Space, Typography } from 'antd'
import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { closedTradeQueryKeys, listClosedTrades } from '../api/closedTrades'
import { ApiError } from '../api/client'
import type { TradeAction } from '../api/types'
import { ClosedTradeTable } from '../components/closed/ClosedTradeTable'
import { QueryError } from '../components/QueryError'
import { TradeFilterForm } from '../components/trade/TradeFilterForm'
import { SIGNED_MONEY_COLORS } from '../components/trade/tradeLabels'
import {
  EMPTY_TRADE_FILTERS,
  hasActiveFilters,
  isTradeFilterField,
  parseTradeListSearch,
  toTradeListParams,
  toTradeListSearch,
  TRADE_PAGE_SIZES,
  validateTradeFilters,
  type TradeFilterField,
  type TradeFilterValues,
  type TradeListUrlState,
} from '../components/trade/tradeListFilters'
import { decimalSign, formatMoney } from '../utils/format'

/** FEAT-006: the closed-trade list only accepts closing directions (FEAT-012 adds EXP). */
const CLOSE_ACTIONS: readonly TradeAction[] = ['BTC', 'STC', 'EXP']

const EXPIRY_NOTE = '到期作废的平仓净额为 0.00，记在到期日 16:00（美东）。'

function serverFilterErrors(error: Error | null): Partial<Record<TradeFilterField, string>> {
  const errors: Partial<Record<TradeFilterField, string>> = {}
  if (!(error instanceof ApiError)) return errors
  if (error.code !== 'VALIDATION_ERROR' && error.code !== 'BAD_REQUEST') return errors
  for (const detail of error.details) {
    if (isTradeFilterField(detail.field) && errors[detail.field] === undefined) {
      errors[detail.field] = detail.message
    }
  }
  return errors
}

/**
 * 已平仓 page (FEAT-006): read-only closed trades (BTC / STC, FEAT-012 EXP), newest first, with the count and
 * realized P&L total over ALL filtered results (Q46). Filters, page and size live in the URL with
 * the same parameter names and rules as the trade list (FEAT-002). Every number is the backend's.
 */
export function ClosedTradesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const state = parseTradeListSearch(searchParams)
  const clientErrors = validateTradeFilters(state.filters, CLOSE_ACTIONS)
  const filtersValid = Object.keys(clientErrors).length === 0
  const params = toTradeListParams(state)
  const activeFilters = hasActiveFilters(state.filters)

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: closedTradeQueryKeys.list(params),
    queryFn: ({ signal }) => listClosedTrades(params, signal),
    enabled: filtersValid,
    keepPrevious: true,
  })

  const fieldErrors = { ...serverFilterErrors(error), ...clientErrors }
  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  const go = (next: TradeListUrlState, options?: { replace?: boolean }) => {
    setSearchParams(toTradeListSearch(next), options)
  }
  const applyFilters = (filters: TradeFilterValues) => {
    go({ filters, page: 1, size: state.size })
  }
  const clearFilters = () => {
    applyFilters(EMPTY_TRADE_FILTERS)
  }

  const lastPage = data?.totalPages ?? 0
  const pastLastPage =
    data !== undefined && !isPlaceholderData && data.items.length === 0 && lastPage > 0
  useEffect(() => {
    if (pastLastPage && state.page > lastPage) {
      setSearchParams(toTradeListSearch({ ...state, page: lastPage }), { replace: true })
    }
  }, [pastLastPage, lastPage, state, setSearchParams])

  let content
  if (hasFieldErrors) {
    content = (
      <Alert
        type="warning"
        showIcon
        title={
          <span data-testid="closed-invalid">筛选条件有误，请按提示修改后重新查询，或清除筛选</span>
        }
      />
    )
  } else if (error && !isFetching) {
    content = (
      <QueryError
        title="无法加载已平仓记录"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="closed"
      />
    )
  } else {
    let empty
    if (isPending) {
      empty = <span data-testid="closed-loading"> </span>
    } else if (activeFilters) {
      empty = (
        <Space orientation="vertical" data-testid="closed-no-match">
          <span>没有符合条件的平仓记录</span>
          <Button onClick={clearFilters} data-testid="closed-no-match-clear">
            清除筛选
          </Button>
        </Space>
      )
    } else {
      empty = <span data-testid="closed-empty">暂无平仓记录</span>
    }
    content = (
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        {data && (
          <Space size={24} wrap data-testid="closed-summary">
            <Typography.Text strong data-testid="closed-count">
              共 {data.filteredCount} 笔平仓
            </Typography.Text>
            <Typography.Text strong>
              已实现盈亏合计{' '}
              <span
                data-testid="closed-pnl-total"
                style={{ color: SIGNED_MONEY_COLORS[decimalSign(data.filteredRealizedPnlTotal)] }}
              >
                {formatMoney(data.filteredRealizedPnlTotal, { signed: true })}
              </span>
            </Typography.Text>
            {activeFilters && (
              <Typography.Text type="secondary" data-testid="closed-filter-note">
                （当前筛选结果的全部记录）
              </Typography.Text>
            )}
          </Space>
        )}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          净额均已含手续费；空头开仓为实收、多头开仓为实付；每张平均四舍五入到分，仅供参考。展开行查看被平掉的开仓批次（FIFO）。
          <span data-testid="closed-expiry-note">{EXPIRY_NOTE}</span>
        </Typography.Text>
        <ClosedTradeTable
          trades={data?.items ?? []}
          loading={isPending || isFetching}
          empty={empty}
        />
        {data && data.totalItems > 0 && (
          <div
            data-testid="closed-pagination"
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              justifyContent: 'flex-end',
              alignItems: 'center',
              gap: 16,
            }}
          >
            <Pagination
              current={state.page}
              pageSize={state.size}
              total={data.totalItems}
              showSizeChanger={false}
              onChange={(page) => {
                go({ ...state, page })
              }}
            />
            <Space size={8}>
              <Typography.Text type="secondary">每页</Typography.Text>
              <Radio.Group
                size="small"
                optionType="button"
                value={state.size}
                onChange={(event) => {
                  go({ ...state, page: 1, size: Number(event.target.value) })
                }}
              >
                {TRADE_PAGE_SIZES.map((size) => (
                  <Radio.Button key={size} value={size} data-testid={`closed-size-${String(size)}`}>
                    {size}
                  </Radio.Button>
                ))}
              </Radio.Group>
            </Space>
          </div>
        )}
      </Space>
    )
  }

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="closed-page">
      <TradeFilterForm
        key={JSON.stringify(state.filters)}
        initialValues={state.filters}
        errors={fieldErrors}
        onApply={applyFilters}
        onClear={clearFilters}
        actions={CLOSE_ACTIONS}
        dateLabelPrefix="平仓"
        testIdPrefix="closed-filter"
      />
      <Card title="已平仓记录（按平仓时间倒序）" data-testid="closed-list">
        {content}
      </Card>
    </Space>
  )
}
