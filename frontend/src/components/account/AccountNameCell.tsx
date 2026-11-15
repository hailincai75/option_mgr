import type { RowAccountFields } from '../../api/types'
import { EMPTY_VALUE } from '../../utils/format'

/** FEAT-035 (Q214): the 账户 cell of a position row in 全部账户 mode (backend `accountName`). */
export function AccountNameCell({ row, testId }: { row: RowAccountFields; testId: string }) {
  return (
    <span data-testid={testId} data-account-id={row.accountId} style={{ whiteSpace: 'nowrap' }}>
      {row.accountName ?? EMPTY_VALUE}
    </span>
  )
}
