import { useQuery } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { accountQueryKeys, listAccounts } from '../api/accounts'
import { setAccountNotFoundHandler, setApiAccountId } from '../api/client'
import { AccountContext, type AccountContextValue } from './AccountContext'
import { readStoredAccountId, resolveAccountId, writeStoredAccountId } from './accountStorage'

export interface AccountsStatus {
  isPending: boolean
  error: Error | null
  isFetching: boolean
  retry: () => void
}

interface AccountProviderProps {
  children: (status: AccountsStatus) => ReactNode
}

/**
 * FEAT-033 current-account state. Loads `GET /api/accounts`, picks the remembered account (or the
 * lowest id when it is missing or no longer exists, Q55), keeps it in localStorage and tells the
 * API client which `X-Account-Id` to send. Children get the loading / error status to render.
 */
export function AccountProvider({ children }: AccountProviderProps) {
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: accountQueryKeys.all,
    queryFn: ({ signal }) => listAccounts(signal),
    // Recover by itself when the backend comes back: retry every 10 s while the list is failing
    // (also refetched when the browser goes back online).
    refetchInterval: (query) => (query.state.status === 'error' ? 10_000 : false),
    refetchOnReconnect: true,
  })
  const [chosen, setChosen] = useState<number | null>(() => readStoredAccountId())

  const accounts = useMemo(() => data ?? [], [data])
  const accountId = data === undefined ? null : resolveAccountId(accounts, chosen)
  // Keep the client in sync before any child query runs (render order: parent first).
  setApiAccountId(accountId)

  const selectAccount = useCallback((next: number) => {
    setApiAccountId(next)
    writeStoredAccountId(next)
    setChosen(next)
  }, [])

  const retryAccounts = useCallback(() => {
    void refetch()
  }, [refetch])

  // ACCOUNT_NOT_FOUND (the remembered account no longer exists): reload the list; the current
  // account then falls back to the lowest id (Q55).
  useEffect(() => {
    setAccountNotFoundHandler(retryAccounts)
    return () => {
      setAccountNotFoundHandler(null)
    }
  }, [retryAccounts])

  const value = useMemo<AccountContextValue>(
    () => ({
      accountId,
      account: accounts.find((candidate) => candidate.id === accountId) ?? null,
      accounts,
      selectAccount,
      accountsError: accountId === null ? error : null,
      accountsFetching: isFetching,
      retryAccounts,
    }),
    [accountId, accounts, selectAccount, error, isFetching, retryAccounts],
  )

  return (
    <AccountContext.Provider value={value}>
      {children({
        isPending,
        error,
        isFetching,
        retry: retryAccounts,
      })}
    </AccountContext.Provider>
  )
}
