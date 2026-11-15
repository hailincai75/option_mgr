import { Link, useNavigate } from 'react-router-dom'
import { useAccount } from '../../account/AccountContext'
import type { AuditObjectType, RowAccountFields } from '../../api/types'
import { auditUrl } from '../audit/auditLinks'

interface HistoryLinkProps {
  objectType: AuditObjectType
  objectId: number
  /** The row the link is on; in 全部账户 mode its `accountId` becomes the current account. */
  row: RowAccountFields
  testId: string
}

/**
 * The rows' "历史" link (FEAT-005), shown by the caller only when `hasAuditLog` is true.
 * FEAT-046 (Q231): the audit page never merges accounts (Q225), so in 全部账户 mode a click first
 * switches the current account to the row's account, then opens the audit page filtered to that
 * record. In single-account mode it is a plain link.
 */
export function HistoryLink({ objectType, objectId, row, testId }: HistoryLinkProps) {
  const { isAllAccounts, selectAccount } = useAccount()
  const navigate = useNavigate()
  const to = auditUrl(objectType, objectId)
  const rowAccountId = row.accountId
  if (!isAllAccounts || rowAccountId === undefined) {
    return (
      <Link to={to} data-testid={testId}>
        历史
      </Link>
    )
  }
  return (
    <Link
      to={to}
      data-testid={testId}
      data-account-id={rowAccountId}
      onClick={(event) => {
        event.preventDefault()
        selectAccount(rowAccountId)
        void navigate(to)
      }}
    >
      历史
    </Link>
  )
}
