import { useQuery } from '@tanstack/react-query'
import { Alert, Button, Card, Col, Form, Input, Row, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { getPnlSummary, pnlSummaryQueryKeys } from '../api/dashboard'
import type { PnlMonthRow, PnlSymbolRow } from '../api/types'
import {
  closedUrlForMonth,
  closedUrlForSymbol,
  EMPTY_PNL_RANGE,
  hasPnlRange,
  isPnlRangeField,
  parsePnlSearch,
  toPnlParams,
  toPnlSearch,
  validatePnlRange,
  type PnlRange,
  type PnlRangeField,
} from '../components/pnl/pnlSummarySearch'
import { QueryError } from '../components/QueryError'
import { SIGNED_MONEY_COLORS } from '../components/trade/tradeLabels'
import { decimalSign, formatMoney } from '../utils/format'

function SignedMoney({ value, testId }: { value: string; testId: string }) {
  return (
    <span
      data-testid={testId}
      style={{
        color: SIGNED_MONEY_COLORS[decimalSign(value)],
        fontVariantNumeric: 'tabular-nums',
        fontWeight: 600,
      }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

function serverFieldErrors(error: Error | null): Partial<Record<PnlRangeField, string>> {
  const errors: Partial<Record<PnlRangeField, string>> = {}
  if (!(error instanceof ApiError)) return errors
  if (error.code !== 'VALIDATION_ERROR' && error.code !== 'BAD_REQUEST') return errors
  for (const detail of error.details) {
    if (isPnlRangeField(detail.field) && errors[detail.field] === undefined) {
      errors[detail.field] = detail.message
    }
  }
  return errors
}

interface RangeFormProps {
  initialValues: PnlRange
  errors: Partial<Record<PnlRangeField, string>>
  onApply: (range: PnlRange) => void
  onClear: () => void
}

function RangeForm({ initialValues, errors, onApply, onClear }: RangeFormProps) {
  const itemProps = (field: PnlRangeField) => {
    const error = errors[field]
    return error === undefined
      ? { name: field }
      : { name: field, validateStatus: 'error' as const, help: error }
  }
  return (
    <Form<PnlRange>
      layout="inline"
      initialValues={initialValues}
      onFinish={(values) => {
        onApply({ startDate: values.startDate, endDate: values.endDate })
      }}
      data-testid="pnl-filter-form"
      style={{ rowGap: 8 }}
    >
      <Form.Item label="平仓开始日期（美东日期）" {...itemProps('startDate')}>
        <Input type="date" data-testid="pnl-filter-start-date" />
      </Form.Item>
      <Form.Item label="平仓结束日期（美东日期）" {...itemProps('endDate')}>
        <Input type="date" data-testid="pnl-filter-end-date" />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button type="primary" htmlType="submit" data-testid="pnl-filter-submit">
            查询
          </Button>
          <Button onClick={onClear} data-testid="pnl-filter-clear">
            清除
          </Button>
        </Space>
      </Form.Item>
    </Form>
  )
}

/**
 * 盈亏汇总 page (FEAT-008, Q49/Q50): realized P&L by symbol and by month for an optional New York
 * close-date range kept in the URL. Totals rows use the backend totals (no client-side sums);
 * symbols and months link to the 已平仓 page with matching filters.
 */
export function PnlSummaryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const range = parsePnlSearch(searchParams)
  const clientErrors = validatePnlRange(range)
  const valid = Object.keys(clientErrors).length === 0
  const params = toPnlParams(range)
  const filtered = hasPnlRange(range)

  const { data, error, isFetching, refetch } = useQuery({
    queryKey: pnlSummaryQueryKeys.detail(params),
    queryFn: ({ signal }) => getPnlSummary(params, signal),
    enabled: valid,
  })

  const fieldErrors = { ...serverFieldErrors(error), ...clientErrors }
  const hasFieldErrors = Object.keys(fieldErrors).length > 0

  const applyRange = (next: PnlRange) => {
    setSearchParams(toPnlSearch(next))
  }

  const symbolColumns: ColumnsType<PnlSymbolRow> = [
    {
      title: '标的',
      key: 'symbol',
      render: (_value: unknown, row) => (
        <Link to={closedUrlForSymbol(row.symbol, range)} data-testid="pnl-symbol-link">
          {row.symbol}
        </Link>
      ),
    },
    {
      title: '笔数',
      key: 'closedCount',
      align: 'right',
      render: (_value: unknown, row) => (
        <span data-testid="pnl-symbol-count">{row.closedCount}</span>
      ),
    },
    {
      title: '已实现盈亏',
      key: 'realizedPnl',
      align: 'right',
      render: (_value: unknown, row) => (
        <SignedMoney value={row.realizedPnl} testId="pnl-symbol-pnl" />
      ),
    },
  ]

  const monthColumns: ColumnsType<PnlMonthRow> = [
    {
      title: '月份（美东）',
      key: 'month',
      render: (_value: unknown, row) => (
        <Link to={closedUrlForMonth(row.month, range)} data-testid="pnl-month-link">
          {row.month}
        </Link>
      ),
    },
    {
      title: '笔数',
      key: 'closedCount',
      align: 'right',
      render: (_value: unknown, row) => (
        <span data-testid="pnl-month-count">{row.closedCount}</span>
      ),
    },
    {
      title: '已实现盈亏',
      key: 'realizedPnl',
      align: 'right',
      render: (_value: unknown, row) => (
        <SignedMoney value={row.realizedPnl} testId="pnl-month-pnl" />
      ),
    },
  ]

  let content
  if (hasFieldErrors) {
    content = (
      <Alert
        type="warning"
        showIcon
        title={<span data-testid="pnl-invalid">日期范围有误，请修改后重新查询，或清除</span>}
      />
    )
  } else if (error && !isFetching) {
    content = (
      <QueryError
        title="无法加载盈亏汇总"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="pnl"
      />
    )
  } else if (data === undefined) {
    content = (
      <div data-testid="pnl-loading" style={{ textAlign: 'center', padding: 24 }}>
        <Table loading dataSource={[]} columns={[]} pagination={false} />
      </div>
    )
  } else {
    const totalsRow = (testIdPrefix: string) => (
      <Table.Summary.Row data-testid={`${testIdPrefix}-total-row`}>
        <Table.Summary.Cell index={0}>
          <strong>合计</strong>
        </Table.Summary.Cell>
        <Table.Summary.Cell index={1} align="right">
          <strong data-testid={`${testIdPrefix}-total-count`}>{data.closedCount}</strong>
        </Table.Summary.Cell>
        <Table.Summary.Cell index={2} align="right">
          <SignedMoney value={data.realizedPnlTotal} testId={`${testIdPrefix}-total-pnl`} />
        </Table.Summary.Cell>
      </Table.Summary.Row>
    )
    content = (
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <Typography.Text strong data-testid="pnl-total-line">
          共 <span data-testid="pnl-total-count">{data.closedCount}</span> 笔平仓，已实现盈亏合计{' '}
          <SignedMoney value={data.realizedPnlTotal} testId="pnl-total" />
        </Typography.Text>
        {data.closedCount === 0 ? (
          <Typography.Text type="secondary" data-testid="pnl-empty">
            {filtered ? '所选范围内没有平仓记录' : '暂无平仓记录'}
          </Typography.Text>
        ) : (
          <Row gutter={[16, 16]}>
            <Col xs={24} lg={12}>
              <Card title="按标的" size="small" data-testid="pnl-by-symbol">
                <Table<PnlSymbolRow>
                  rowKey="symbol"
                  size="small"
                  columns={symbolColumns}
                  dataSource={data.bySymbol}
                  loading={isFetching}
                  pagination={false}
                  onRow={() => ({ 'data-testid': 'pnl-symbol-row' }) as HTMLAttributes<HTMLElement>}
                  summary={() => <Table.Summary>{totalsRow('pnl-symbol')}</Table.Summary>}
                />
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title="按月" size="small" data-testid="pnl-by-month">
                <Table<PnlMonthRow>
                  rowKey="month"
                  size="small"
                  columns={monthColumns}
                  dataSource={data.byMonth}
                  loading={isFetching}
                  pagination={false}
                  onRow={() => ({ 'data-testid': 'pnl-month-row' }) as HTMLAttributes<HTMLElement>}
                  summary={() => <Table.Summary>{totalsRow('pnl-month')}</Table.Summary>}
                />
              </Card>
            </Col>
          </Row>
        )}
      </Space>
    )
  }

  return (
    <Card title="盈亏汇总" data-testid="pnl-page">
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <RangeForm
          key={JSON.stringify(range)}
          initialValues={range}
          errors={fieldErrors}
          onApply={applyRange}
          onClear={() => {
            applyRange(EMPTY_PNL_RANGE)
          }}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          已实现盈亏只来自平仓，已含手续费；不含入金、出金和利息等。按平仓时间的美东日期归属；点击标的或月份查看对应的平仓记录。
        </Typography.Text>
        {content}
      </Space>
    </Card>
  )
}
