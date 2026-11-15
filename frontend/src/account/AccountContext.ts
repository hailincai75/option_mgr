import { createContext, useContext } from 'react'
import type { AccountScope } from '../api/client'
import type { Account } from '../api/types'

export interface AccountContextValue {
  /**
   * Current account scope: an account id, `'ALL'` (FEAT-035 全部账户, read-only), or null while
   * unknown (no header is sent then).
   */
  accountId: AccountScope | null
  /** The current account, when the account list is loaded; null in 全部账户 mode. */
  account: Account | null
  accounts: Account[]
  /** FEAT-035: true in 全部账户 mode — every write action is hidden (rule 5, Q217). */
  isAllAccounts: boolean
  /** FEAT-035 (Q220): 全部账户 is offered only when there are at least 2 accounts. */
  canSelectAll: boolean
  selectAccount: (accountId: AccountScope) => void
  /** Error of the account list while no account is known (e.g. backend was down). */
  accountsError: Error | null
  accountsFetching: boolean
  /** Reloads the account list; account-scoped queries start by themselves once it loads. */
  retryAccounts: () => void
}

/**
 * Default for components rendered outside `AccountProvider` (unit tests of single pages):
 * no account, so query keys carry `accountId: null` and no header is sent.
 */
export const AccountContext = createContext<AccountContextValue>({
  accountId: null,
  account: null,
  accounts: [],
  isAllAccounts: false,
  canSelectAll: false,
  selectAccount: () => undefined,
  accountsError: null,
  accountsFetching: false,
  retryAccounts: () => undefined,
})

export function useAccount(): AccountContextValue {
  return useContext(AccountContext)
}

/** Current account scope for query keys. */
export function useAccountId(): AccountScope | null {
  return useContext(AccountContext).accountId
}

/** FEAT-035: true in 全部账户 (read-only) mode. */
export function useIsAllAccounts(): boolean {
  return useContext(AccountContext).isAllAccounts
}

/**
 * Scopes a query key to an account (FEAT-033): `[...key, { accountId }]`. Keys stay prefixed by
 * their domain, so `invalidateQueries({ queryKey: ['cash'] })` still refreshes every account,
 * while each account has its own cache entries and switching accounts fetches fresh data.
 * FEAT-035: 全部账户 has its own entries under `{ accountId: 'ALL' }`.
 */
export function accountKey<K extends readonly unknown[]>(
  key: K,
  accountId: AccountScope | null,
): [...K, { accountId: AccountScope | null }] {
  return [...key, { accountId }]
}
