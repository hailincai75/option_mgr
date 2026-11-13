import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query'
import { ACCOUNT_ERROR_MESSAGES, ApiError } from '../api/client'
import { accountKey, useAccount } from './AccountContext'

interface AccountQueryOptions<T> {
  /** Domain key, e.g. `cashQueryKeys.summary`; the account is appended automatically. */
  queryKey: readonly unknown[]
  queryFn: (context: { signal: AbortSignal }) => Promise<T>
  enabled?: boolean
  /** Keep the previous page's data while the next one loads (paginated lists). */
  keepPrevious?: boolean
}

/**
 * `useQuery` for account-scoped data (FEAT-033):
 * - the key ends with `{ accountId }`, so each account has its own cache entries;
 * - the query only runs once a current account is known — no request without `X-Account-Id`;
 * - while the account list is failing (e.g. backend was down at load time), it reports that
 *   failure as its own error ("账户未就绪，请重试") and its `refetch` (the page's 重试) reloads
 *   the account list; when the list loads, the page remounts with the account and queries run.
 */
export function useAccountQuery<T>(options: AccountQueryOptions<T>): UseQueryResult<T> {
  const { accountId, accountsError, accountsFetching, retryAccounts } = useAccount()
  const query = useQuery({
    queryKey: accountKey(options.queryKey, accountId),
    queryFn: ({ signal }) => options.queryFn({ signal }),
    enabled: accountId !== null && (options.enabled ?? true),
    placeholderData: options.keepPrevious ? keepPreviousData : undefined,
  })
  if (accountId !== null || accountsError === null) return query

  const error = new ApiError({
    code: accountsError instanceof ApiError ? accountsError.code : 'ACCOUNT_REQUIRED',
    message: ACCOUNT_ERROR_MESSAGES.ACCOUNT_REQUIRED,
    status: accountsError instanceof ApiError ? accountsError.status : 0,
    cause: accountsError,
  })
  return {
    ...query,
    status: 'error',
    isError: true,
    isPending: false,
    isLoading: false,
    isSuccess: false,
    isFetching: accountsFetching,
    error,
    refetch: () => {
      retryAccounts()
      return Promise.resolve(query)
    },
  } as unknown as UseQueryResult<T>
}
