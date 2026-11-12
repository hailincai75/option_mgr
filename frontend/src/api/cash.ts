import { apiRequest } from './client'
import type {
  CashLedgerPage,
  CashSummary,
  CashTransfer,
  CreateCashTransferRequest,
  UpdateCashTransferRequest,
} from './types'

/** Query keys. Invalidate `cashQueryKeys.all` after anything that changes cash. */
export const cashQueryKeys = {
  all: ['cash'] as const,
  summary: ['cash', 'summary'] as const,
  ledgerAll: ['cash', 'ledger'] as const,
  ledger: (page: number, size: number) => ['cash', 'ledger', page, size] as const,
}

/** Contract: page size must be 1–100. */
export const CASH_LEDGER_PAGE_SIZE = 20

/** `GET /api/cash/summary` */
export function getCashSummary(signal?: AbortSignal): Promise<CashSummary> {
  return apiRequest<CashSummary>('/api/cash/summary', { signal })
}

/** `GET /api/cash/ledger?page=&size=` (page starts at 1, newest first). */
export function getCashLedger(
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<CashLedgerPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest<CashLedgerPage>(`/api/cash/ledger?${query.toString()}`, { signal })
}

/** `POST /api/cash/transfers` → 201 */
export function createCashTransfer(request: CreateCashTransferRequest): Promise<CashTransfer> {
  return apiRequest<CashTransfer>('/api/cash/transfers', { method: 'POST', body: request })
}

/** `PUT /api/cash/transfers/{id}` → 200 (FEAT-005: edit type, amount, time, remark). */
export function updateCashTransfer(
  id: number,
  request: UpdateCashTransferRequest,
): Promise<CashTransfer> {
  return apiRequest<CashTransfer>(`/api/cash/transfers/${String(id)}`, {
    method: 'PUT',
    body: request,
  })
}

/** `DELETE /api/cash/transfers/{id}` → 204 (FEAT-005, Q40). */
export async function deleteCashTransfer(id: number): Promise<void> {
  await apiRequest<null>(`/api/cash/transfers/${String(id)}`, { method: 'DELETE' })
}
