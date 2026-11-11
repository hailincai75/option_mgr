import { useQuery } from '@tanstack/react-query'
import { Card, Col, Row, Spin, Statistic } from 'antd'
import { cashQueryKeys, getCashSummary } from '../../api/cash'
import { formatMoney } from '../../utils/format'
import { QueryError } from '../QueryError'

interface CashSummaryCardProps {
  /** Card title; defaults to "现金汇总". */
  title?: string
  size?: 'medium' | 'small'
}

const ITEMS = [
  { key: 'totalCash', label: '总现金', testId: 'cash-summary-total' },
  { key: 'frozenCash', label: '冻结现金', testId: 'cash-summary-frozen' },
  { key: 'availableCash', label: '可用现金', testId: 'cash-summary-available' },
] as const

/**
 * Reusable cash summary (总现金 / 冻结现金 / 可用现金) from `GET /api/cash/summary`.
 * Values are shown exactly as returned by the backend (formatted only). Refreshes whenever
 * `cashQueryKeys.all` is invalidated.
 */
export function CashSummaryCard({ title = '现金汇总', size = 'medium' }: CashSummaryCardProps) {
  const { data, error, isPending, isFetching, refetch } = useQuery({
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
    <Card title={title} size={size} data-testid="cash-summary">
      {content}
    </Card>
  )
}
