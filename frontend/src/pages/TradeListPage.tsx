import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Pagination, Radio, Space, Typography } from 'antd'
import { useEffect } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { listOptionTrades, optionTradeQueryKeys } from '../api/optionTrades'
import type { OptionTrade } from '../api/types'
import { QueryError } from '../components/QueryError'
import { TradeFilterForm } from '../components/trade/TradeFilterForm'
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
import { TradeTable } from '../components/trade/TradeTable'

/** Field errors the backend reported for the filter query parameters (400 with details). */
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
 * 交易 page (FEAT-002): read-only, newest-first list of option trades with filters.
 * The URL query string is the single source of truth for filters, page and size, so the page
 * is bookmarkable and the browser's back button restores earlier filters. Invalid values in
 * the URL are shown in the form with an error and are not queried.
 */
export function TradeListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const state = parseTradeListSearch(searchParams)
  const clientErrors = validateTradeFilters(state.filters)
  const filtersValid = Object.keys(clientErrors).length === 0
  const params = toTradeListParams(state)
  const activeFilters = hasActiveFilters(state.filters)

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useQuery({
    queryKey: optionTradeQueryKeys.list(params),
    queryFn: ({ signal }) => listOptionTrades(params, signal),
    enabled: filtersValid,
    placeholderData: keepPreviousData,
  })

  const serverErrors = serverFilterErrors(error)
  const fieldErrors = { ...serverErrors, ...clientErrors }
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
  const filterByContract = (trade: OptionTrade) => {
    applyFilters({
      ...state.filters,
      symbol: trade.symbol,
      expirationDate: trade.expirationDate,
      strike: trade.strike,
      optionType: trade.optionType,
    })
  }

  // Past the last page (e.g. hand-edited URL, or fewer trades than before): jump to the last page.
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
          <span data-testid="trade-list-invalid">
            筛选条件有误，请按提示修改后重新查询，或清除筛选
          </span>
        }
      />
    )
  } else if (error && !isFetching) {
    content = (
      <QueryError
        title="无法加载交易列表"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="trade-list"
      />
    )
  } else {
    let empty
    if (isPending) {
      empty = <span data-testid="trade-list-loading"> </span>
    } else if (activeFilters) {
      empty = (
        <Space orientation="vertical" data-testid="trade-list-no-match">
          <span>没有符合条件的交易</span>
          <Button onClick={clearFilters} data-testid="trade-list-no-match-clear">
            清除筛选
          </Button>
        </Space>
      )
    } else {
      empty = (
        <Space orientation="vertical" data-testid="trade-list-empty">
          <span>暂无交易</span>
          <Link to="/trades/new" data-testid="trade-list-empty-new">
            录入开仓
          </Link>
        </Space>
      )
    }
    content = (
      <>
        <TradeTable
          trades={data?.items ?? []}
          loading={isPending || isFetching}
          empty={empty}
          onContractClick={filterByContract}
        />
        {/* "共 N 条" also when filters match nothing (AC 2: 共 0 条); hidden for the fully empty 暂无交易 state. */}
        {data && (data.totalItems > 0 || activeFilters) && (
          <div
            data-testid="trade-list-pagination"
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              justifyContent: 'flex-end',
              alignItems: 'center',
              gap: 16,
              marginTop: 16,
            }}
          >
            <Typography.Text type="secondary" data-testid="trade-list-total">
              共 {data.totalItems} 条
            </Typography.Text>
            {data.totalItems > 0 && (
              <>
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
                    data-testid="trade-list-size"
                  >
                    {TRADE_PAGE_SIZES.map((size) => (
                      <Radio.Button
                        key={size}
                        value={size}
                        data-testid={`trade-list-size-${String(size)}`}
                      >
                        {size}
                      </Radio.Button>
                    ))}
                  </Radio.Group>
                </Space>
              </>
            )}
          </div>
        )}
      </>
    )
  }

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="trade-list-page">
      <TradeFilterForm
        key={JSON.stringify(state.filters)}
        initialValues={state.filters}
        errors={fieldErrors}
        onApply={applyFilters}
        onClear={clearFilters}
      />
      <Card
        title="交易（按成交时间倒序）"
        extra={
          <Link to="/trades/new" data-testid="trade-list-new">
            录入开仓
          </Link>
        }
        data-testid="trade-list"
      >
        {content}
      </Card>
    </Space>
  )
}
