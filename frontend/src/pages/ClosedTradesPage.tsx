import { useAccountQuery } from '../account/useAccountQuery'
import { Alert, Button, Card, Pagination, Radio, Space, Typography } from 'antd'
import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { closedTradeQueryKeys, listClosedTrades } from '../api/closedTrades'
import { ApiError } from '../api/client'
import { ClosedTradeTable } from '../components/closed/ClosedTradeTable'
import {
  EMPTY_CLOSED_FILTERS,
  hasActiveClosedFilters,
  isClosedFilterField,
  OPTION_ONLY_HINT,
  parseClosedListSearch,
  toClosedListParams,
  toClosedListSearch,
  validateClosedFilters,
  CLOSE_ACTIONS,
  type ClosedFilterField,
  type ClosedFilterValues,
  type ClosedListUrlState,
} from '../components/closed/closedListFilters'
import { QueryError } from '../components/QueryError'
import { TradeFilterForm } from '../components/trade/TradeFilterForm'
import { SIGNED_MONEY_COLORS } from '../components/trade/tradeLabels'
import { TRADE_PAGE_SIZES } from '../components/trade/tradeListFilters'
import { decimalSign, formatMoney } from '../utils/format'

const EXPIRY_NOTE = '到期作废的平仓净额为 0.00，记在到期日 16:00（美东）。'

/** FEAT-013: page note sentence about assignments (requirement wording). */
const ASSIGNMENT_NOTE =
  '被指派（卖出 put）的期权已实现盈亏为 0.00，权利金计入股票成本，股票卖出时体现在股票盈亏中。'

/** FEAT-041: page note sentence about call assignments (Q8 provisional rule, Q100). */
const CALL_ASSIGNMENT_NOTE =
  '被指派（卖出 call，暂定口径）的期权已实现盈亏为 0.00，权利金计入股票卖出实收；股票“指派卖出”行按 FIFO 显示股票盈亏，一次被指派计 2 笔。'

/** FEAT-014: page note sentence about exercises (Q111, Q114). */
const EXERCISE_NOTE =
  '行权（多头）的期权已实现盈亏为 0.00：call 行权的权利金计入股票成本，put 行权的权利金从股票卖出实收中扣除；put 行权另有“行权卖出”股票行，一次计 2 笔。'

/** FEAT-040: page note sentence about stock sales (requirement wording). */
const STOCK_NOTE =
  '股票卖出：成本为按 FIFO 被卖出批次的成本（部分卖出按比例四舍五入到分），每股数字保留 4 位小数，仅供参考；股票转入和买入没有已实现盈亏。'

function serverFilterErrors(error: Error | null): Partial<Record<ClosedFilterField, string>> {
  const errors: Partial<Record<ClosedFilterField, string>> = {}
  if (!(error instanceof ApiError)) return errors
  if (error.code !== 'VALIDATION_ERROR' && error.code !== 'BAD_REQUEST') return errors
  for (const detail of error.details) {
    if (isClosedFilterField(detail.field) && errors[detail.field] === undefined) {
      errors[detail.field] = detail.message
    }
  }
  return errors
}

/**
 * 已平仓 page (FEAT-006): read-only closed trades (BTC / STC, FEAT-012 EXP, FEAT-013 ASG, FEAT-014 EXR) and (FEAT-040) stock
 * sales, newest first, with the count and realized P&L total over ALL filtered results (Q46).
 * Filters, page and size live in the URL with the same parameter names and rules as the trade
 * list (FEAT-002) plus the record `type` (OPTION / STOCK). Every number is the backend's.
 */
export function ClosedTradesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const state = parseClosedListSearch(searchParams)
  const clientErrors = validateClosedFilters(state.filters)
  const filtersValid = Object.keys(clientErrors).length === 0
  const params = toClosedListParams(state)
  const activeFilters = hasActiveClosedFilters(state.filters)

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: closedTradeQueryKeys.list(params),
    queryFn: ({ signal }) => listClosedTrades(params, signal),
    enabled: filtersValid,
    keepPrevious: true,
  })

  const fieldErrors = { ...serverFilterErrors(error), ...clientErrors }
  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  const go = (next: ClosedListUrlState, options?: { replace?: boolean }) => {
    setSearchParams(toClosedListSearch(next), options)
  }
  const applyFilters = (filters: ClosedFilterValues) => {
    go({ filters, page: 1, size: state.size })
  }
  const clearFilters = () => {
    applyFilters(EMPTY_CLOSED_FILTERS)
  }

  const lastPage = data?.totalPages ?? 0
  const pastLastPage =
    data !== undefined && !isPlaceholderData && data.items.length === 0 && lastPage > 0
  useEffect(() => {
    if (pastLastPage && state.page > lastPage) {
      setSearchParams(toClosedListSearch({ ...state, page: lastPage }), { replace: true })
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
          净额均已含手续费；空头开仓为实收、多头开仓为实付；每张平均四舍五入到分，仅供参考。展开行查看被平掉的开仓批次。
          <span data-testid="closed-expiry-note">{EXPIRY_NOTE}</span>
          <span data-testid="closed-assignment-note">{ASSIGNMENT_NOTE}</span>
          <span data-testid="closed-call-assignment-note">{CALL_ASSIGNMENT_NOTE}</span>
          <span data-testid="closed-exercise-note">{EXERCISE_NOTE}</span>
          <span data-testid="closed-stock-note">{STOCK_NOTE}</span>
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
        onApply={(filters) => {
          applyFilters({ ...filters, type: filters.type ?? '' })
        }}
        onClear={clearFilters}
        actions={CLOSE_ACTIONS}
        dateLabelPrefix="平仓 / 卖出"
        optionTypeLabel="Call / Put"
        recordType={{ optionOnlyHint: OPTION_ONLY_HINT }}
        testIdPrefix="closed-filter"
      />
      <Card title="已平仓记录（按平仓 / 卖出时间倒序）" data-testid="closed-list">
        {content}
      </Card>
    </Space>
  )
}
