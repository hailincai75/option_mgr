import { App, Button, Card, Space, Typography } from 'antd'
import type { ReactNode } from 'react'
import { useAccount } from '../../account/AccountContext'
import { ENTRY_NEEDS_ACCOUNT_TEXT, PAGE_NOT_SUPPORTED_TEXT } from '../../account/allAccountsLabels'

interface AllAccountsPlaceholderProps {
  /** Text above the account buttons. */
  message: string
  /** Card title, e.g. the section name; omitted for a whole page. */
  title?: ReactNode
  /** testid of the card; buttons are `${testId}-account-<id>`, the text `${testId}-message`. */
  testId?: string
}

/**
 * FEAT-035 rules 5 / 7 (Q217, Q223): shown instead of a page (or a section) in 全部账户 mode —
 * the message plus one button per account; a click switches to that account, after which the page
 * shows its normal single-account content. Sends no request.
 */
export function AllAccountsPlaceholder({
  message,
  title,
  testId = 'all-accounts-placeholder',
}: AllAccountsPlaceholderProps) {
  const { accounts, selectAccount } = useAccount()
  const { message: toast } = App.useApp()
  return (
    <Card title={title} data-testid={testId}>
      <Space orientation="vertical" size={12}>
        <Typography.Text data-testid={`${testId}-message`}>{message}</Typography.Text>
        <Space size={8} wrap>
          {accounts.map((account) => (
            <Button
              key={account.id}
              onClick={() => {
                selectAccount(account.id)
                void toast.success(`已切换到账户：${account.name}`)
              }}
              data-testid={`${testId}-account-${String(account.id)}`}
            >
              {account.name}
            </Button>
          ))}
        </Space>
      </Space>
    </Card>
  )
}

/**
 * FEAT-035 route guard: renders `children` for a single account; in 全部账户 mode renders the
 * placeholder instead (the page's queries never run, so no request is sent with `ALL`).
 * `kind="entry"` (/trades/new, /stocks/new) uses 录入需要选择一个账户; otherwise rule 7's text.
 */
export function SingleAccountOnly({
  children,
  kind = 'page',
}: {
  children: ReactNode
  kind?: 'page' | 'entry'
}) {
  const { isAllAccounts } = useAccount()
  if (!isAllAccounts) return <>{children}</>
  return kind === 'entry' ? (
    <AllAccountsPlaceholder message={ENTRY_NEEDS_ACCOUNT_TEXT} testId="all-accounts-entry" />
  ) : (
    <AllAccountsPlaceholder message={PAGE_NOT_SUPPORTED_TEXT} />
  )
}
