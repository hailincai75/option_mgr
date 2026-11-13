import { useAccountQuery } from '../account/useAccountQuery'
import { Alert, Button, Card, Col, Form, Input, Row, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { getPnlSummary, pnlSummaryQueryKeys } from '../api/dashboard'
import type { PnlBreakdown, PnlMonthRow, PnlSymbolRow } from '../api/types'
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
import { decimalSign, EMPTY_VALUE, formatMoney } from '../utils/format'

/** Page note (FEAT-040 requirement wording). */
const PNL_NOTE =
  '已实现盈亏来自期权平仓、到期作废和股票卖出，已含手续费；不含入金、出金、股息和利息；股票转入（期初持仓）和买入没有已实现盈亏。'

function SignedMoney({
  value,
  testId,
  strong = true,
}: {
  value: string
  testId: string
  strong?: boolean
}) {
  return (
    <span
      data-testid={testId}
      style={{
        color: SIGNED_MONEY_COLORS[decimalSign(value)],
        fontVariantNumeric: 'tabular-nums',
        fontWeight: strong ? 600 : undefined,
      }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

/** 期权 / 股票 column (Q91): the type's realized P&L, `—` when that type has 0 records. */
function BreakdownMoney({ part, testId }: { part: PnlBreakdown; testId: string }) {
  if (part.closedCount === 0) return <span data-testid={testId}>{EMPTY_VALUE}</span>
  return <SignedMoney value={part.realizedPnl} testId={testId} strong={false} />
}

type PnlRow = PnlSymbolRow | PnlMonthRow

/** 笔数, 期权, 股票, 合计 columns shared by both tables (FEAT-040, Q91). */
function figureColumns<T extends PnlRow>(testIdPrefix: string): ColumnsType<T> {
  return [
    {
      title: '笔数',
      key: 'closedCount',
      align: 'right',
      render: (_value: unknown, row) => (
        <span data-testid={`${testIdPrefix}-count`}>{row.closedCount}</span>
      ),
    },
    {
      title: '期权',
      key: 'option',
      align: 'right',
      render: (_value: unknown, row) => (
        <BreakdownMoney part={row.option} testId={`${testIdPrefix}-option`} />
      ),
    },
    {
      title: '股票',
      key: 'stock',
      align: 'right',
      render: (_value: unknown, row) => (
        <BreakdownMoney part={row.stock} testId={`${testIdPrefix}-stock`} />
      ),
    },
    {
      title: '合计',
      key: 'realizedPnl',
      align: 'right',
      render: (_value: unknown, row) => (
        <SignedMoney value={row.realizedPnl} testId={`${testIdPrefix}-pnl`} />
      ),
    },
  ]
}

/** "期权 2 笔 +20.00 · 股票 3 笔 +121.51" after the totals line (Q91). */
function breakdownText(label: string, part: PnlBreakdown): string {
  return `${label} ${String(part.closedCount)} 笔 ${formatMoney(part.realizedPnl, { signed: true })}`
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
      <Form.Item label="平仓 / 卖出开始日期（美东日期）" {...itemProps('startDate')}>
        <Input type="date" data-testid="pnl-filter-start-date" />
      </Form.Item>
      <Form.Item label="平仓 / 卖出结束日期（美东日期）" {...itemProps('endDate')}>
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
 * close-date range kept in the URL. FEAT-040 (Q91): option closes and stock sales together, with
 * 期权 / 股票 columns. Totals rows use the backend totals (no client-side sums); symbols and
 * months link to the 已平仓 page with matching filters (no record type).
 */
export function PnlSummaryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const range = parsePnlSearch(searchParams)
  const clientErrors = validatePnlRange(range)
  const valid = Object.keys(clientErrors).length === 0
  const params = toPnlParams(range)
  const filtered = hasPnlRange(range)

  const { data, error, isFetching, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
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
    ...figureColumns<PnlSymbolRow>('pnl-symbol'),
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
    ...figureColumns<PnlMonthRow>('pnl-month'),
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
          <BreakdownMoney part={data.option} testId={`${testIdPrefix}-total-option`} />
        </Table.Summary.Cell>
        <Table.Summary.Cell index={3} align="right">
          <BreakdownMoney part={data.stock} testId={`${testIdPrefix}-total-stock`} />
        </Table.Summary.Cell>
        <Table.Summary.Cell index={4} align="right">
          <SignedMoney value={data.realizedPnlTotal} testId={`${testIdPrefix}-total-pnl`} />
        </Table.Summary.Cell>
      </Table.Summary.Row>
    )
    content = (
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <div>
          <Typography.Text strong data-testid="pnl-total-line">
            共 <span data-testid="pnl-total-count">{data.closedCount}</span> 笔，已实现盈亏合计{' '}
            <SignedMoney value={data.realizedPnlTotal} testId="pnl-total" />
          </Typography.Text>
          <Typography.Text
            type="secondary"
            style={{ fontSize: 12, marginLeft: 12 }}
            data-testid="pnl-total-breakdown"
          >
            {breakdownText('期权', data.option)} · {breakdownText('股票', data.stock)}
          </Typography.Text>
        </div>
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
        <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="pnl-note">
          {PNL_NOTE}
        </Typography.Text>
        {content}
      </Space>
    </Card>
  )
}
