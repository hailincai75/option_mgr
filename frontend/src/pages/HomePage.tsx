import { useQuery } from '@tanstack/react-query'
import { useIsAllAccounts } from '../account/AccountContext'
import { useAccountQuery } from '../account/useAccountQuery'
import { Alert, Button, Card, Col, Row, Space, Spin, Statistic, Tag, Typography } from 'antd'
import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { dashboardQueryKeys, getDashboard } from '../api/dashboard'
import { getHealth, healthQueryKey } from '../api/health'
import type { Dashboard } from '../api/types'
import { AdhocClosureAlerts } from '../components/calendar/AdhocClosureAlerts'
import { CashSummaryCard } from '../components/cash/CashSummaryCard'
import { positionsUrlForExpiry } from '../components/position/expiryFilter'
import { STOCK_POSITIONS_URL } from '../components/stock/stockPositionsAnchor'
import { QueryError } from '../components/QueryError'
import { EMPTY_TRADE_FILTERS, toTradeListSearch } from '../components/trade/tradeListFilters'
import { SIGNED_MONEY_COLORS } from '../components/trade/tradeLabels'
import { decimalSign, formatInstantNY, formatMoney } from '../utils/format'

function describeError(error: Error): string {
  if (error instanceof ApiError) return `${error.message} (${error.code})`
  return error.message
}

/** Backend status as one small line at the bottom (FEAT-008; testids kept from the old home page). */
function HealthFooter() {
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: healthQueryKey,
    queryFn: ({ signal }) => getHealth(signal),
  })

  let content
  if (isPending) {
    content = (
      <span data-testid="health-loading">
        <Spin size="small" /> 正在检查后端…
      </span>
    )
  } else if (error) {
    content = (
      <Alert
        type="error"
        showIcon
        title="后端不可用"
        description={<span data-testid="health-error">{describeError(error)}</span>}
        action={
          <Button
            size="small"
            data-testid="health-retry"
            loading={isFetching}
            onClick={() => {
              void refetch()
            }}
          >
            重试
          </Button>
        }
      />
    )
  } else {
    content = (
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        后端：
        <Tag color={data.status === 'UP' ? 'success' : 'error'} data-testid="health-status">
          {data.status}
        </Tag>
        · 版本 <span data-testid="health-version">{data.version}</span> · 服务器时间（美东）{' '}
        <span data-testid="health-time">{formatInstantNY(data.time)}</span>
      </Typography.Text>
    )
  }
  return <div data-testid="health-card">{content}</div>
}

function SignedTotal({ value, testId }: { value: string; testId: string }) {
  return (
    <span
      data-testid={testId}
      style={{ color: SIGNED_MONEY_COLORS[decimalSign(value)], fontWeight: 600 }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

/** /closed filtered to the current month so far (1st of the month .. today, New York). */
function currentMonthClosedUrl(dashboard: Dashboard): string {
  const search = toTradeListSearch({
    filters: {
      ...EMPTY_TRADE_FILTERS,
      startDate: `${dashboard.currentMonth}-01`,
      endDate: dashboard.asOfDate,
    },
    page: 1,
    size: 20,
  })
  return `/closed?${search.toString()}`
}

type DashboardQuery = ReturnType<typeof useDashboard>

function useDashboard() {
  return useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: dashboardQueryKeys.all,
    queryFn: ({ signal }) => getDashboard(signal),
  })
}

/** Loading / error handled per card, so one failing card does not hide the others. */
function DashboardCardBody({
  query,
  testIdPrefix,
  render,
}: {
  query: DashboardQuery
  testIdPrefix: string
  render: (dashboard: Dashboard) => ReactNode
}) {
  if (query.isPending) {
    return (
      <div data-testid={`${testIdPrefix}-loading`} style={{ textAlign: 'center', padding: 16 }}>
        <Spin />
      </div>
    )
  }
  if (query.error) {
    return (
      <QueryError
        title="无法加载"
        error={query.error}
        retrying={query.isFetching}
        onRetry={() => void query.refetch()}
        testIdPrefix={testIdPrefix}
      />
    )
  }
  return <>{render(query.data)}</>
}

function CountLine({
  label,
  count,
  testId,
  tone,
  to,
  linkTestId,
}: {
  label: string
  count: number
  testId: string
  tone?: 'warning' | 'danger'
  /** FEAT-010 (Q105, Q108): the whole line links to the filtered positions page, also for 0. */
  to: string
  linkTestId: string
}) {
  const highlight = count > 0 ? tone : undefined
  return (
    <Link to={to} data-testid={linkTestId} style={{ display: 'block' }}>
      {label}{' '}
      <Typography.Text
        strong={highlight !== undefined}
        type={highlight}
        data-testid={testId}
        data-highlight={highlight ?? 'none'}
      >
        {count}
      </Typography.Text>{' '}
      个
    </Link>
  )
}

/**
 * 首页概览 (FEAT-008, Q48): read-only figures from `GET /api/dashboard` plus the cash summary
 * card; every number is the backend's. Backend status is a small line at the bottom.
 */
export function HomePage() {
  const dashboard = useDashboard()
  const navigate = useNavigate()
  /** FEAT-035: 全部账户 — every figure is the backend's all-account total; 录入交易 hidden. */
  const allAccounts = useIsAllAccounts()

  return (
    <Space orientation="vertical" size={16} style={{ width: '100%' }} data-testid="dashboard">
      {/* FEAT-042 rule 4: 去调整 opens the positions page (FEAT-035: 切换到 <账户> 去调整). */}
      <AdhocClosureAlerts />
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <Typography.Title level={4} style={{ margin: 0 }}>
          概览
          {dashboard.data && (
            <Typography.Text type="secondary" style={{ fontSize: 14, marginLeft: 12 }}>
              <span data-testid="dashboard-as-of">截至 {dashboard.data.asOfDate}（美东）</span>
            </Typography.Text>
          )}
        </Typography.Title>
        {!allAccounts && (
          <Button
            type="primary"
            onClick={() => {
              void navigate('/trades/new')
            }}
            data-testid="dashboard-new-trade"
          >
            录入交易
          </Button>
        )}
      </Space>

      <CashSummaryCard
        title="现金"
        size="small"
        extra={
          <Link to="/cash" data-testid="dashboard-cash-link">
            资金明细
          </Link>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card
            title="已实现盈亏"
            size="small"
            extra={
              <Link to="/pnl-summary" data-testid="dashboard-pnl-link">
                盈亏汇总
              </Link>
            }
            data-testid="dashboard-pnl-card"
            style={{ height: '100%' }}
          >
            <DashboardCardBody
              query={dashboard}
              testIdPrefix="dashboard-pnl"
              render={(d) => (
                <Row gutter={16}>
                  <Col span={12}>
                    <Statistic
                      title="总计"
                      value={d.realizedPnl.total}
                      formatter={() => (
                        <span>
                          <SignedTotal
                            value={d.realizedPnl.total}
                            testId="dashboard-realized-total"
                          />
                          <Typography.Text type="secondary" style={{ fontSize: 14 }}>
                            （
                            <span data-testid="dashboard-realized-count">
                              {d.realizedPnl.closedCount}
                            </span>{' '}
                            笔）
                          </Typography.Text>
                        </span>
                      )}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title={
                        <Link to={currentMonthClosedUrl(d)} data-testid="dashboard-month-link">
                          本月（<span data-testid="dashboard-month">{d.currentMonth}</span>）
                        </Link>
                      }
                      value={d.currentMonthRealizedPnl.total}
                      formatter={() => (
                        <span>
                          <SignedTotal
                            value={d.currentMonthRealizedPnl.total}
                            testId="dashboard-month-total"
                          />
                          <Typography.Text type="secondary" style={{ fontSize: 14 }}>
                            （
                            <span data-testid="dashboard-month-count">
                              {d.currentMonthRealizedPnl.closedCount}
                            </span>{' '}
                            笔）
                          </Typography.Text>
                        </span>
                      )}
                    />
                  </Col>
                </Row>
              )}
            />
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card
            title="持仓"
            size="small"
            extra={
              <Link to="/positions" data-testid="dashboard-positions-link">
                查看持仓
              </Link>
            }
            data-testid="dashboard-positions-card"
            style={{ height: '100%' }}
          >
            <DashboardCardBody
              query={dashboard}
              testIdPrefix="dashboard-positions"
              render={(d) => (
                <Space orientation="vertical" size={4}>
                  <div>
                    期权持仓{' '}
                    <strong data-testid="dashboard-position-count">
                      {d.positions.positionCount}
                    </strong>{' '}
                    个
                  </div>
                  <div>
                    冻结现金合计{' '}
                    <strong data-testid="dashboard-frozen-total">
                      {formatMoney(d.positions.frozenTotal)}
                    </strong>
                  </div>
                  <CountLine
                    label="7 天内到期"
                    count={d.positions.expiringWithin7DaysCount}
                    testId="dashboard-expiring-count"
                    tone="warning"
                    to={positionsUrlForExpiry('7')}
                    linkTestId="dashboard-expiring-link"
                  />
                  <CountLine
                    label="已过期未处理"
                    count={d.positions.expiredUnprocessedCount}
                    testId="dashboard-expired-count"
                    tone="danger"
                    to={positionsUrlForExpiry('expired')}
                    linkTestId="dashboard-expired-link"
                  />
                  {/* FEAT-040 (Q89, Q93): shown also for 0, never highlighted. */}
                  <Link to={STOCK_POSITIONS_URL} data-testid="dashboard-stock-positions-link">
                    股票持仓{' '}
                    <strong data-testid="dashboard-stock-position-count">
                      {d.stockPositions.positionCount}
                    </strong>{' '}
                    个标的
                  </Link>
                </Space>
              )}
            />
          </Card>
        </Col>
      </Row>

      <HealthFooter />
    </Space>
  )
}
