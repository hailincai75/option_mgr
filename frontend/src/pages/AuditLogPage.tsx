import { useAccountQuery } from '../account/useAccountQuery'
import { Alert, Button, Card, Form, Input, Pagination, Radio, Space, Typography } from 'antd'
import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { auditLogQueryKeys, getAuditLog } from '../api/auditLog'
import { ApiError } from '../api/client'
import {
  AUDIT_PAGE_SIZES,
  EMPTY_AUDIT_FILTERS,
  auditEmptyObjectHint,
  isAuditFilterField,
  parseAuditSearch,
  toAuditParams,
  toAuditSearch,
  validateAuditFilters,
  type AuditFilterField,
  type AuditFilterValues,
  type AuditUrlState,
} from '../components/audit/auditSearch'
import { AuditTable } from '../components/audit/AuditTable'
import { QueryError } from '../components/QueryError'

function serverFieldErrors(error: Error | null): Partial<Record<AuditFilterField, string>> {
  const errors: Partial<Record<AuditFilterField, string>> = {}
  if (!(error instanceof ApiError)) return errors
  if (error.code !== 'VALIDATION_ERROR' && error.code !== 'BAD_REQUEST') return errors
  for (const detail of error.details) {
    if (isAuditFilterField(detail.field) && errors[detail.field] === undefined) {
      errors[detail.field] = detail.message
    }
  }
  return errors
}

interface FilterFormProps {
  initialValues: AuditFilterValues
  errors: Partial<Record<AuditFilterField, string>>
  onApply: (filters: AuditFilterValues) => void
  onClear: () => void
}

function AuditFilterForm({ initialValues, errors, onApply, onClear }: FilterFormProps) {
  const itemProps = (field: AuditFilterField) => {
    const error = errors[field]
    return error === undefined
      ? { name: field }
      : { name: field, validateStatus: 'error' as const, help: error }
  }
  return (
    <Form<AuditFilterValues>
      layout="inline"
      initialValues={initialValues}
      onFinish={(values) => {
        onApply({ objectType: values.objectType, objectId: values.objectId })
      }}
      data-testid="audit-filter-form"
      style={{ rowGap: 8 }}
    >
      <Form.Item label="对象类型" {...itemProps('objectType')}>
        <Radio.Group>
          <Radio value="" data-testid="audit-filter-type-all">
            全部
          </Radio>
          <Radio value="OPTION_TRADE" data-testid="audit-filter-type-trade">
            期权交易
          </Radio>
          <Radio value="CASH_TRANSFER" data-testid="audit-filter-type-transfer">
            入金/出金
          </Radio>
          <Radio value="ACCOUNT" data-testid="audit-filter-type-account">
            账户
          </Radio>
        </Radio.Group>
      </Form.Item>
      <Form.Item label="对象 ID" {...itemProps('objectId')}>
        <Input
          inputMode="numeric"
          autoComplete="off"
          style={{ width: 120 }}
          data-testid="audit-filter-id"
        />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" data-testid="audit-filter-submit">
            查询
          </Button>
          <Button onClick={onClear} data-testid="audit-filter-clear">
            清除筛选
          </Button>
        </Space>
      </Form.Item>
    </Form>
  )
}

/**
 * 审计记录 page (FEAT-005, Q44): read-only, newest first, optional object filter. Filters, page
 * and size live in the URL (rows' "历史" links open it pre-filtered).
 */
export function AuditLogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const state = parseAuditSearch(searchParams)
  const clientErrors = validateAuditFilters(state.filters)
  const filtersValid = Object.keys(clientErrors).length === 0
  const params = toAuditParams(state)
  const neverChangedHint = filtersValid ? auditEmptyObjectHint(state.filters) : null

  const { data, error, isPending, isFetching, isPlaceholderData, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: auditLogQueryKeys.list(params),
    queryFn: ({ signal }) => getAuditLog(params, signal),
    enabled: filtersValid,
    keepPrevious: true,
  })

  const fieldErrors = { ...serverFieldErrors(error), ...clientErrors }
  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  const go = (next: AuditUrlState, options?: { replace?: boolean }) => {
    setSearchParams(toAuditSearch(next), options)
  }
  const applyFilters = (filters: AuditFilterValues) => {
    go({ filters, page: 1, size: state.size })
  }
  const clearFilters = () => {
    applyFilters(EMPTY_AUDIT_FILTERS)
  }

  const lastPage = data?.totalPages ?? 0
  const pastLastPage =
    data !== undefined && !isPlaceholderData && data.items.length === 0 && lastPage > 0
  useEffect(() => {
    if (pastLastPage && state.page > lastPage) {
      setSearchParams(toAuditSearch({ ...state, page: lastPage }), { replace: true })
    }
  }, [pastLastPage, lastPage, state, setSearchParams])

  let content
  if (hasFieldErrors) {
    content = (
      <Alert
        type="warning"
        showIcon
        title={<span data-testid="audit-invalid">筛选条件有误，请修改后重新查询，或清除筛选</span>}
      />
    )
  } else if (error && !isFetching) {
    content = (
      <QueryError
        title="无法加载审计记录"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="audit"
      />
    )
  } else {
    const empty = isPending ? (
      <span data-testid="audit-loading"> </span>
    ) : (
      <Space orientation="vertical" size={4} data-testid="audit-empty">
        <span>暂无审计记录</span>
        {neverChangedHint !== null && (
          <Typography.Text type="secondary" data-testid="audit-empty-hint">
            {neverChangedHint}
          </Typography.Text>
        )}
      </Space>
    )
    content = (
      <>
        <AuditTable records={data?.items ?? []} loading={isPending || isFetching} empty={empty} />
        {data && data.totalItems > 0 && (
          <div
            data-testid="audit-pagination"
            style={{
              display: 'flex',
              flexWrap: 'wrap',
              justifyContent: 'flex-end',
              alignItems: 'center',
              gap: 16,
              marginTop: 16,
            }}
          >
            <Typography.Text type="secondary" data-testid="audit-total">
              共 {data.totalItems} 条
            </Typography.Text>
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
                {AUDIT_PAGE_SIZES.map((size) => (
                  <Radio.Button key={size} value={size} data-testid={`audit-size-${String(size)}`}>
                    {size}
                  </Radio.Button>
                ))}
              </Radio.Group>
            </Space>
          </div>
        )}
      </>
    )
  }

  return (
    <Card title="审计记录" data-testid="audit-page">
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <AuditFilterForm
          key={JSON.stringify(state.filters)}
          initialValues={state.filters}
          errors={fieldErrors}
          onApply={applyFilters}
          onClear={clearFilters}
        />
        <Typography.Text type="secondary">
          只读：记录每次修改、删除前后的完整内容；按操作时间倒序。展开行查看修改前 / 修改后。
        </Typography.Text>
        {content}
      </Space>
    </Card>
  )
}
