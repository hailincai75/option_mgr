import { apiRequest } from './client'
import type { Account, SaveAccountRequest } from './types'

/** Not account-scoped: the list of accounts itself. */
export const accountQueryKeys = {
  all: ['accounts'] as const,
}

/** `GET /api/accounts` → 200, ascending id. */
export function listAccounts(signal?: AbortSignal): Promise<Account[]> {
  return apiRequest<Account[]>('/api/accounts', { signal })
}

/** `POST /api/accounts` → 201. */
export function createAccount(request: SaveAccountRequest): Promise<Account> {
  return apiRequest<Account>('/api/accounts', { method: 'POST', body: request })
}

/** `PUT /api/accounts/{id}` → 200 (422 NO_CHANGES when unchanged). */
export function updateAccount(id: number, request: SaveAccountRequest): Promise<Account> {
  return apiRequest<Account>(`/api/accounts/${String(id)}`, { method: 'PUT', body: request })
}
