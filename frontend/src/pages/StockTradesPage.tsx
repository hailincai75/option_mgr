import { Alert, Button, Card, Pagination, Radio, Space, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useIsAllAccounts } from '../account/AccountContext'
import { useAccountQuery } from '../account/useAccountQuery'
import { ApiError } from '../api/client'
import { deleteStockTrade, listStockTrades, stockTradeQueryKeys } from '../api/stockTrades'
import type { StockTrade } from '../api/types'
import { DeleteConfirmModal } from '../components/DeleteConfirmModal'
import { QueryError } from '../components/QueryError'
import { EditStockTradeModal } from '../components/stock/EditStockTradeModal'
import { StockFilterForm } from '../components/stock/StockFilterForm'
import { stockTradeSummary } from '../components/stock/stockLabels'
import {
  EMPTY_STOCK_FILTERS,
  hasActiveStockFilters,
  isStockFilterField,
  parseStockListSearch,
  STOCK_PAGE_SIZES,
  toStockListParams,
  toStockListSearch,
  validateStockFilters,
  type StockFilterField,
  type StockFilterValues,
  type StockListUrlState,
} from '../components/stock/stockListFilters'
import { StockTradeTable } from '../components/stock/StockTradeTable'

/** Field errors the backend reported for the filter query parameters (400 with details). */
function serverFilterErrors(error: Error | null): Partial<Record<StockFilterField, string>> {
  const errors: Partial<Record<StockFilterField, string>> = {}
  if (!(error instanceof ApiError)) return errors
  if (error.code !== 'VALIDATION_ERROR' && error.code !== 'BAD_REQUEST') return errors
  for (const detail of error.details) {
    if (isStockFilterField(detail.field) && errors[detail.field] === undefined) {
      errors[detail.field] = detail.message
    }
  }
  return errors
}

const PAGE_NOTE =
  '金额均已含手续费；每股价格 = 金额 ÷ 股数，仅供参考；股票已实现盈亏同时计入已平仓页、盈亏汇总和首页'

/**
 * 股票 page (FEAT-011): newest-first stock records with filters (symbol, type, NY date range),
 * pagination, expandable sold lots and 修改 / 删除 / 历史. Filters, page and size live in the URL
 * (same approach as the option trade list, FEAT-002).
 * FEAT-046: in 全部账户 mode the merged list of all accounts, with no 录入股票交易 entries (Q217).
 */
export function StockTradesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const allAccounts = useIsAllAccounts()
  const [editing, setEditing] = useState<StockTrade | null>(null)
  const [deleting, setDeleting] = useState<StockTrade | null>(null)
  const state = parseStockListSearch(searchParams)
  const clientErrors = validateStockFilters(state.filters)
  const filtersValid = Object.keys(clientErrors).length === 0
  const params = toStockListParams(state)
  const activeFilters = hasActiveStockFilters(state.filters)

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: stockTradeQueryKeys.list(params),
    queryFn: ({ signal }) => listStockTrades(params, signal),
    enabled: filtersValid,
    keepPrevious: true,
  })

  const fieldErrors = { ...serverFilterErrors(error), ...clientErrors }
  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  const go = (next: StockListUrlState, options?: { replace?: boolean }) => {
    setSearchParams(toStockListSearch(next), options)
  }
  const applyFilters = (filters: StockFilterValues) => {
    go({ filters, page: 1, size: state.size })
  }
  const clearFilters = () => {
    applyFilters(EMPTY_STOCK_FILTERS)
  }

  // Past the last page (hand-edited URL, or fewer records than before): jump to the last page.
  const lastPage = data?.totalPages ?? 0
  const pastLastPage =
    data !== undefined && !isPlaceholderData && data.items.length === 0 && lastPage > 0
  useEffect(() => {
    if (pastLastPage && state.page > lastPage) {
      setSearchParams(toStockListSearch({ ...state, page: lastPage }), { replace: true })
    }
  }, [pastLastPage, lastPage, state, setSearchParams])

  let content
  if (hasFieldErrors) {
    content = (
      <Alert
        type="warning"
        showIcon
        title={
          <span data-testid="stock-list-invalid">
            筛选条件有误，请按提示修改后重新查询，或清除筛选
          </span>
        }
      />
    )
  } else if (error && !isFetching) {
    content = (
      <QueryError
        title="无法加载股票交易"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="stock-list"
      />
    )
  } else {
    let empty
    if (isPending) {
      empty = <span data-testid="stock-list-loading"> </span>
    } else if (activeFilters) {
      empty = (
        <Space orientation="vertical" data-testid="stock-list-no-match">
          <span>没有符合条件的股票交易</span>
          <Button onClick={clearFilters} data-testid="stock-list-no-match-clear">
            清除筛选
          </Button>
        </Space>
      )
    } else {
      empty = (
        <Space orientation="vertical" data-testid="stock-list-empty">
          <span>暂无股票交易</span>
          {!allAccounts && (
            <Link to="/stocks/new" data-testid="stock-list-empty-new">
              录入股票交易
            </Link>
          )}
        </Space>
      )
    }
    content = (
      <>
        <StockTradeTable
          trades={data?.items ?? []}
          loading={isPending || isFetching}
          empty={empty}
          onSymbolClick={(trade) => {
            applyFilters({ ...state.filters, symbol: trade.symbol })
          }}
          onEdit={setEditing}
          onDelete={setDeleting}
        />
        {data && (data.totalItems > 0 || activeFilters) && (
          <div
            data-testid="stock-list-pagination"
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              justifyContent: 'flex-end',
              alignItems: 'center',
              gap: 16,
              marginTop: 16,
            }}
          >
            <Typography.Text type="secondary" data-testid="stock-list-total">
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
                    data-testid="stock-list-size"
                  >
                    {STOCK_PAGE_SIZES.map((size) => (
                      <Radio.Button
                        key={size}
                        value={size}
                        data-testid={`stock-list-size-${String(size)}`}
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
    <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="stock-list-page">
      <StockFilterForm
        key={JSON.stringify(state.filters)}
        initialValues={state.filters}
        errors={fieldErrors}
        onApply={applyFilters}
        onClear={clearFilters}
      />
      <Card
        title="股票交易（按时间倒序）"
        extra={
          allAccounts ? undefined : (
            <Link to="/stocks/new" data-testid="stock-list-new">
              录入股票交易
            </Link>
          )
        }
        data-testid="stock-list"
      >
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="stock-list-note">
            {PAGE_NOTE}
          </Typography.Text>
          <div>{content}</div>
        </Space>
      </Card>
      {editing !== null && (
        <EditStockTradeModal
          key={editing.id}
          trade={editing}
          onDone={() => {
            setEditing(null)
          }}
        />
      )}
      {deleting !== null && (
        <DeleteConfirmModal
          key={deleting.id}
          title={`删除股票交易 #${String(deleting.id)}`}
          summary={stockTradeSummary(deleting)}
          onConfirm={() => deleteStockTrade(deleting.id)}
          onDone={() => {
            setDeleting(null)
          }}
          testIdPrefix="stock-delete"
        />
      )}
    </Space>
  )
}
