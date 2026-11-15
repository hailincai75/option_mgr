import { ALL_ACCOUNTS, type AccountScope } from '../api/client'
import type { Account } from '../api/types'

/** localStorage key for the last selected account (Q55: remembered in the browser). */
export const ACCOUNT_STORAGE_KEY = 'option-mgr.accountId'

/**
 * The remembered account id or `'ALL'` (FEAT-035, Q219), or null (missing, not a number, or
 * storage unavailable).
 */
export function readStoredAccountId(): AccountScope | null {
  try {
    const text = window.localStorage.getItem(ACCOUNT_STORAGE_KEY)
    if (text === ALL_ACCOUNTS) return ALL_ACCOUNTS
    if (text === null || !/^\d+$/.test(text)) return null
    return Number(text)
  } catch {
    return null
  }
}

export function writeStoredAccountId(accountId: AccountScope): void {
  try {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, String(accountId))
  } catch {
    // Private mode / blocked storage: the choice is just not remembered.
  }
}

/** FEAT-035 (Q220): 全部账户 exists only with at least 2 accounts. */
export function canSelectAllAccounts(accounts: readonly Account[]): boolean {
  return accounts.length >= 2
}

/**
 * The account to use: the preferred one if it still exists, otherwise the lowest id (Q55).
 * FEAT-035: a preferred `'ALL'` is kept while there are at least 2 accounts (Q219 / Q220); with a
 * single account it falls back to that account. Returns null only when there are no accounts.
 */
export function resolveAccountId(
  accounts: Account[],
  preferred: AccountScope | null,
): AccountScope | null {
  if (preferred === ALL_ACCOUNTS && canSelectAllAccounts(accounts)) return ALL_ACCOUNTS
  if (preferred !== null && accounts.some((account) => account.id === preferred)) return preferred
  if (accounts.length === 0) return null
  return Math.min(...accounts.map((account) => account.id))
}
