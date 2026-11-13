import { createContext, useContext } from 'react'
import type { Account } from '../api/types'

export interface AccountContextValue {
  /** Current account id; null while unknown (no header is sent then). */
  accountId: number | null
  /** The current account, when the account list is loaded. */
  account: Account | null
  accounts: Account[]
  selectAccount: (accountId: number) => void
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
  selectAccount: () => undefined,
  accountsError: null,
  accountsFetching: false,
  retryAccounts: () => undefined,
})

export function useAccount(): AccountContextValue {
  return useContext(AccountContext)
}

/** Current account id for query keys. */
export function useAccountId(): number | null {
  return useContext(AccountContext).accountId
}

/**
 * Scopes a query key to an account (FEAT-033): `[...key, { accountId }]`. Keys stay prefixed by
 * their domain, so `invalidateQueries({ queryKey: ['cash'] })` still refreshes every account,
 * while each account has its own cache entries and switching accounts fetches fresh data.
 */
export function accountKey<K extends readonly unknown[]>(
  key: K,
  accountId: number | null,
): [...K, { accountId: number | null }] {
  return [...key, { accountId }]
}
