import { Layout, Menu, Typography } from 'antd'
import { Link, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { AuditLogPage } from './pages/AuditLogPage'
import { CashPage } from './pages/CashPage'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { TradeEntryPage } from './pages/TradeEntryPage'
import { PositionsPage } from './pages/PositionsPage'
import { TradeListPage } from './pages/TradeListPage'

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

function AppLayout() {
  const { pathname } = useLocation()
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
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  )
}

export function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="trades" element={<TradeListPage />} />
        <Route path="positions" element={<PositionsPage />} />
        <Route path="trades/new" element={<TradeEntryPage />} />
        <Route path="cash" element={<CashPage />} />
        <Route path="audit" element={<AuditLogPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
