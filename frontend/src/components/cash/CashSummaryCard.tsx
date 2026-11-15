import { useAccountQuery } from '../../account/useAccountQuery'
import type { HTMLAttributes, ReactNode } from 'react'
import { Card, Col, Row, Spin, Statistic, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { CashSummary, CashSummaryAccount } from '../../api/types'
import { cashQueryKeys, getCashSummary } from '../../api/cash'
import { formatMoney } from '../../utils/format'
import { QueryError } from '../QueryError'

interface CashSummaryCardProps {
  /** Card title; defaults to "现金汇总". */
  title?: string
  size?: 'medium' | 'small'
  /** Card header extra, e.g. a link to the 资金 page. */
  extra?: ReactNode
  /**
   * FEAT-035 (资金 page in 全部账户 mode): a table with one row per account (backend `accounts`)
   * and a 合计 row (backend top-level totals) instead of the three figures.
   */
  perAccount?: boolean
}

const ITEMS = [
  { key: 'totalCash', label: '总现金', testId: 'cash-summary-total' },
  { key: 'frozenCash', label: '冻结现金', testId: 'cash-summary-frozen' },
  { key: 'availableCash', label: '可用现金', testId: 'cash-summary-available' },
] as const

interface CashTableRow {
  key: string
  name: string
  totalCash: string
  frozenCash: string
  availableCash: string
  /** null for the 合计 row. */
  accountId: number | null
}

const MONEY_STYLE = { fontVariantNumeric: 'tabular-nums' } as const

const perAccountColumns: ColumnsType<CashTableRow> = [
  {
    title: '账户',
    key: 'name',
    render: (_value: unknown, row) =>
      row.accountId === null ? (
        <strong data-testid="cash-summary-sum-label">{row.name}</strong>
      ) : (
        <span data-testid="cash-summary-account-name">{row.name}</span>
      ),
  },
  ...ITEMS.map((item) => ({
    title: item.label,
    key: item.key,
    align: 'right' as const,
    render: (_value: unknown, row: CashTableRow) => (
      // 合计 row keeps the FEAT-007 testids (cash-summary-total / -frozen / -available).
      <span
        data-testid={row.accountId === null ? item.testId : `cash-summary-account-${item.key}`}
        style={row.accountId === null ? { ...MONEY_STYLE, fontWeight: 600 } : MONEY_STYLE}
      >
        {formatMoney(row[item.key])}
      </span>
    ),
  })),
]

/** FEAT-035: rows of the per-account table — backend `accounts[]` then 合计 (top-level values). */
function perAccountRows(summary: CashSummary): CashTableRow[] {
  const accountRows = (summary.accounts ?? []).map((account: CashSummaryAccount) => ({
    key: String(account.accountId),
    name: account.accountName,
    totalCash: account.totalCash,
    frozenCash: account.frozenCash,
    availableCash: account.availableCash,
    accountId: account.accountId,
  }))
  return [
    ...accountRows,
    {
      key: 'sum',
      name: '合计',
      totalCash: summary.totalCash,
      frozenCash: summary.frozenCash,
      availableCash: summary.availableCash,
      accountId: null,
    },
  ]
}

/**
 * Reusable cash summary (总现金 / 冻结现金 / 可用现金) from `GET /api/cash/summary`.
 * Values are shown exactly as returned by the backend (formatted only). Refreshes whenever
 * `cashQueryKeys.all` is invalidated.
 */
export function CashSummaryCard({
  title = '现金汇总',
  size = 'medium',
  extra,
  perAccount = false,
}: CashSummaryCardProps) {
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: cashQueryKeys.summary,
    queryFn: ({ signal }) => getCashSummary(signal),
  })

  let content
  if (isPending) {
    content = (
      <div data-testid="cash-summary-loading" style={{ textAlign: 'center', padding: 16 }}>
        <Spin />
      </div>
    )
  } else if (error) {
    content = (
      <QueryError
        title="无法加载现金汇总"
        error={error}
        retrying={isFetching}
        onRetry={() => void refetch()}
        testIdPrefix="cash-summary"
      />
    )
  } else if (perAccount) {
    content = (
      <Table<CashTableRow>
        rowKey="key"
        size="small"
        columns={perAccountColumns}
        dataSource={perAccountRows(data)}
        pagination={false}
        onRow={(row) =>
          ({
            'data-testid': row.accountId === null ? 'cash-summary-sum-row' : 'cash-summary-account-row',
            'data-account-id': row.accountId ?? undefined,
          }) as HTMLAttributes<HTMLElement>
        }
        data-testid="cash-summary-accounts"
      />
    )
  } else {
    content = (
      <Row gutter={16}>
        {ITEMS.map((item) => (
          <Col key={item.key} xs={24} sm={8}>
            <Statistic
              title={item.label}
              value={data[item.key]}
              formatter={() => <span data-testid={item.testId}>{formatMoney(data[item.key])}</span>}
            />
          </Col>
        ))}
      </Row>
    )
  }

  return (
    <Card title={title} size={size} extra={extra} data-testid="cash-summary">
      {content}
    </Card>
  )
}
