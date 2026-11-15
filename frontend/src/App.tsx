import { Alert, Button, Layout, Menu, Spin, Typography } from 'antd'
import { Link, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useAccountId } from './account/AccountContext'
import { AccountProvider, type AccountsStatus } from './account/AccountProvider'
import { ApiError } from './api/client'
import { AccountSwitcher } from './components/account/AccountSwitcher'
import { AccountsPage } from './pages/AccountsPage'
import { AuditLogPage } from './pages/AuditLogPage'
import { CashPage } from './pages/CashPage'
import { ClosedTradesPage } from './pages/ClosedTradesPage'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { TradeEntryPage } from './pages/TradeEntryPage'
import { PnlSummaryPage } from './pages/PnlSummaryPage'
import { PositionsPage } from './pages/PositionsPage'
import { StockTradeEntryPage } from './pages/StockTradeEntryPage'
import { StockTradesPage } from './pages/StockTradesPage'
import { TradeListPage } from './pages/TradeListPage'
import { UnderlyingGroupsPage } from './pages/UnderlyingGroupsPage'

const NAV_ITEMS = [
  {
    key: '/trades',
    label: (
      <Link to="/trades" data-testid="nav-trades">
        交易
      </Link>
    ),
  },
  {
    key: '/positions',
    label: (
      <Link to="/positions" data-testid="nav-positions">
        持仓
      </Link>
    ),
  },
  {
    key: '/stocks',
    label: (
      <Link to="/stocks" data-testid="nav-stocks">
        股票
      </Link>
    ),
  },
  // FEAT-021 (Q184): 策略 page, titled 按标的.
  {
    key: '/strategies',
    label: (
      <Link to="/strategies" data-testid="nav-strategies">
        策略
      </Link>
    ),
  },
  {
    key: '/closed',
    label: (
      <Link to="/closed" data-testid="nav-closed">
        已平仓
      </Link>
    ),
  },
  {
    key: '/pnl-summary',
    label: (
      <Link to="/pnl-summary" data-testid="nav-pnl-summary">
        盈亏汇总
      </Link>
    ),
  },
  {
    key: '/trades/new',
    label: (
      <Link to="/trades/new" data-testid="nav-open-trade">
        录入交易
      </Link>
    ),
  },
  {
    key: '/cash',
    label: (
      <Link to="/cash" data-testid="nav-cash">
        资金
      </Link>
    ),
  },
  {
    key: '/audit',
    label: (
      <Link to="/audit" data-testid="nav-audit">
        审计记录
      </Link>
    ),
  },
]

function describeError(error: Error): string {
  return error instanceof ApiError ? `${error.message} (${error.code})` : error.message
}

function AppLayout({ status }: { status: AccountsStatus }) {
  const { pathname } = useLocation()
  const accountId = useAccountId()
  // Longest matching key wins, so /trades/new selects 录入交易 only, not 交易 as well.
  const selected = NAV_ITEMS.map((item) => item.key)
    .filter((key) => pathname === key || pathname.startsWith(`${key}/`))
    .sort((a, b) => b.length - a.length)
    .slice(0, 1)

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
        <Link to="/" data-testid="nav-home">
          <Typography.Title level={4} style={{ color: '#fff', margin: 0, whiteSpace: 'nowrap' }}>
            Option Manager
          </Typography.Title>
        </Link>
        <Menu
          theme="dark"
          mode="horizontal"
          items={NAV_ITEMS}
          selectedKeys={selected}
          style={{ flex: 1, minWidth: 0 }}
        />
        <AccountSwitcher />
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        {status.error && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            title="无法加载账户列表"
            description={<span data-testid="account-error">{describeError(status.error)}</span>}
            action={
              <Button
                size="small"
                loading={status.isFetching}
                onClick={status.retry}
                data-testid="account-retry"
              >
                重试
              </Button>
            }
          />
        )}
        {status.isPending ? (
          <div data-testid="account-loading" style={{ textAlign: 'center', padding: 48 }}>
            <Spin />
          </div>
        ) : (
          // Remount the page when the account changes (FEAT-033): open dialogs and half-filled
          // forms belong to the previous account and are discarded; URL filters are kept.
          <div key={accountId ?? 'no-account'}>
            <Outlet />
          </div>
        )}
      </Layout.Content>
    </Layout>
  )
}

export function App() {
  return (
    <AccountProvider>
      {(status) => (
        <Routes>
          <Route element={<AppLayout status={status} />}>
            <Route index element={<HomePage />} />
            <Route path="trades" element={<TradeListPage />} />
            <Route path="positions" element={<PositionsPage />} />
            <Route path="stocks" element={<StockTradesPage />} />
            <Route path="stocks/new" element={<StockTradeEntryPage />} />
            <Route path="strategies" element={<UnderlyingGroupsPage />} />
            <Route path="closed" element={<ClosedTradesPage />} />
            <Route path="pnl-summary" element={<PnlSummaryPage />} />
            <Route path="trades/new" element={<TradeEntryPage />} />
            <Route path="cash" element={<CashPage />} />
            <Route path="audit" element={<AuditLogPage />} />
            <Route path="accounts" element={<AccountsPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      )}
    </AccountProvider>
  )
}
