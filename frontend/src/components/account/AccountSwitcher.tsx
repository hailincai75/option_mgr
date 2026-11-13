import { App, Button, Dropdown, Space, Typography } from 'antd'
import type { MenuProps } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAccount } from '../../account/AccountContext'

/**
 * Global account switcher in the header (FEAT-033, Q55): shows "当前账户：<名称>" on every page
 * and lists all accounts plus "管理账户". Selecting an account switches every page at once.
 */
export function AccountSwitcher() {
  const { account, accounts, accountId, selectAccount } = useAccount()
  const navigate = useNavigate()
  const { message } = App.useApp()

  const items: MenuProps['items'] = [
    ...accounts.map((candidate) => ({
      key: String(candidate.id),
      label: (
        <span data-testid={`account-option-${String(candidate.id)}`}>
          {candidate.name}
          {candidate.id === accountId && (
            <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
              当前
            </Typography.Text>
          )}
        </span>
      ),
    })),
    { type: 'divider' as const },
    { key: 'manage', label: <span data-testid="account-manage-link">管理账户</span> },
  ]

  const onClick: MenuProps['onClick'] = ({ key }) => {
    if (key === 'manage') {
      void navigate('/accounts')
      return
    }
    const next = accounts.find((candidate) => String(candidate.id) === key)
    if (!next || next.id === accountId) return
    selectAccount(next.id)
    void message.success(`已切换到账户：${next.name}`)
  }

  return (
    <Dropdown
      menu={{ items, onClick, selectedKeys: accountId === null ? [] : [String(accountId)] }}
      trigger={['click']}
    >
      <Button data-testid="account-switcher" style={{ maxWidth: 260 }}>
        <Space size={4}>
          <span>当前账户：</span>
          <strong
            data-testid="account-current"
            style={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              maxWidth: 140,
              display: 'inline-block',
              verticalAlign: 'bottom',
            }}
          >
            {account?.name ?? '—'}
          </strong>
          <span aria-hidden>▾</span>
        </Space>
      </Button>
    </Dropdown>
  )
}
