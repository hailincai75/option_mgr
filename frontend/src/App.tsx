import { Layout, Menu, Typography } from 'antd'
import { Link, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { CashPage } from './pages/CashPage'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'

const NAV_ITEMS = [
  {
    key: '/cash',
    label: (
      <Link to="/cash" data-testid="nav-cash">
        资金
      </Link>
    ),
  },
]

function AppLayout() {
  const { pathname } = useLocation()
  const selected = NAV_ITEMS.filter((item) => pathname.startsWith(item.key)).map((item) => item.key)

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
        <Route path="cash" element={<CashPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
