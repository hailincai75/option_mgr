import { App, Button, Dropdown, Space, Typography } from 'antd'
import type { MenuProps } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAccount } from '../../account/AccountContext'
import { ALL_ACCOUNTS_CURRENT_TEXT, ALL_ACCOUNTS_LABEL } from '../../account/allAccountsLabels'
import { ALL_ACCOUNTS, type AccountScope } from '../../api/client'

/**
 * Global account switcher in the header (FEAT-033, Q55): shows "当前账户：<名称>" on every page
 * and lists all accounts plus "管理账户". Selecting an account switches every page at once.
 * FEAT-035: with ≥ 2 accounts the list starts with 全部账户 (Q220); while selected the button
 * reads "当前：全部账户（只读）".
 */
export function AccountSwitcher() {
  const { account, accounts, accountId, isAllAccounts, canSelectAll, selectAccount } = useAccount()
  const navigate = useNavigate()
  const { message } = App.useApp()

  const currentMark = (
    <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
      当前
    </Typography.Text>
  )
  const items: MenuProps['items'] = [
    ...(canSelectAll
      ? [
          {
            key: ALL_ACCOUNTS,
            label: (
              <span data-testid="account-option-all">
                {ALL_ACCOUNTS_LABEL}
                {isAllAccounts && currentMark}
              </span>
            ),
          },
          { type: 'divider' as const },
        ]
      : []),
    ...accounts.map((candidate) => ({
      key: String(candidate.id),
      label: (
        <span data-testid={`account-option-${String(candidate.id)}`}>
          {candidate.name}
          {candidate.id === accountId && currentMark}
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
    if (key === ALL_ACCOUNTS) {
      if (isAllAccounts) return
      selectAccount(ALL_ACCOUNTS)
      void message.success(`已切换到：${ALL_ACCOUNTS_CURRENT_TEXT}`)
      return
    }
    const next = accounts.find((candidate) => String(candidate.id) === key)
    if (!next || next.id === accountId) return
    const scope: AccountScope = next.id
    selectAccount(scope)
    void message.success(`已切换到账户：${next.name}`)
  }

  return (
    <Dropdown
      menu={{ items, onClick, selectedKeys: accountId === null ? [] : [String(accountId)] }}
      trigger={['click']}
    >
      <Button
        data-testid="account-switcher"
        data-all-accounts={isAllAccounts ? 'true' : 'false'}
        style={{ maxWidth: 300 }}
      >
        <Space size={4}>
          <span>{isAllAccounts ? '当前：' : '当前账户：'}</span>
          <strong
            data-testid="account-current"
            style={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              maxWidth: 160,
              display: 'inline-block',
              verticalAlign: 'bottom',
            }}
          >
            {isAllAccounts ? ALL_ACCOUNTS_CURRENT_TEXT : (account?.name ?? '—')}
          </strong>
          <span aria-hidden>▾</span>
        </Space>
      </Button>
    </Dropdown>
  )
}
