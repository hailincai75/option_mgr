import type { RowAccountFields } from '../../api/types'

/** FEAT-035 (Q215): table row key unique across accounts (same contract / symbol in two accounts). */
export function rowAccountKey(row: RowAccountFields, key: string): string {
  return row.accountId === undefined ? key : `${String(row.accountId)}|${key}`
}
