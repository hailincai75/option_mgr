import type { Account } from '../api/types'

/** localStorage key for the last selected account (Q55: remembered in the browser). */
export const ACCOUNT_STORAGE_KEY = 'option-mgr.accountId'

/** The remembered account id, or null (missing, not a number, or storage unavailable). */
export function readStoredAccountId(): number | null {
  try {
    const text = window.localStorage.getItem(ACCOUNT_STORAGE_KEY)
    if (text === null || !/^\d+$/.test(text)) return null
    return Number(text)
  } catch {
    return null
  }
}

export function writeStoredAccountId(accountId: number): void {
  try {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, String(accountId))
  } catch {
    // Private mode / blocked storage: the choice is just not remembered.
  }
}

/**
 * The account to use: the preferred one if it still exists, otherwise the lowest id (Q55).
 * Returns null only when there are no accounts at all.
 */
export function resolveAccountId(accounts: Account[], preferred: number | null): number | null {
  if (preferred !== null && accounts.some((account) => account.id === preferred)) return preferred
  if (accounts.length === 0) return null
  return Math.min(...accounts.map((account) => account.id))
}
