import type { QueryClient } from '@tanstack/react-query'
import type { RuleObject } from 'antd/es/form'
import { auditLogQueryKeys } from '../../api/auditLog'
import { cashQueryKeys } from '../../api/cash'
import { ApiError } from '../../api/client'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { optionTradeQueryKeys } from '../../api/optionTrades'
import type { ClosingAction, OptionTrade } from '../../api/types'

/** Shared pieces of the trade entry form (录入交易) and the close form (持仓页"平仓"). */

export interface SubmitError {
  code: string
  message: string
}

/** antd rule from a `(value) => message | null` validator. */
export function toRule(validate: (value: unknown) => string | null): RuleObject {
  return {
    validator: (_rule, value: unknown) => {
      const error = validate(value)
      return error === null ? Promise.resolve() : Promise.reject(new Error(error))
    },
  }
}

/**
 * Puts backend `details[]` under the form fields of the same name and returns the error to show
 * above the submit button (422 messages verbatim, unmapped details appended), or null when every
 * detail went to a field.
 */
export function applySubmitError<F extends string>(
  fieldNames: readonly F[],
  error: Error,
  setFieldErrors: (fields: { name: F; errors: string[] }[]) => void,
): SubmitError | null {
  if (!(error instanceof ApiError)) return { code: 'UNKNOWN', message: error.message }
  const isField = (field: string): field is F =>
    (fieldNames as readonly string[]).includes(field)
  const grouped = new Map<F, string[]>()
  const unmapped: string[] = []
  for (const detail of error.details) {
    if (isField(detail.field)) {
      grouped.set(detail.field, [...(grouped.get(detail.field) ?? []), detail.message])
    } else {
      unmapped.push(`${detail.field}: ${detail.message}`)
    }
  }
  setFieldErrors([...grouped.entries()].map(([name, errors]) => ({ name, errors })))
  if (grouped.size > 0 && unmapped.length === 0) return null
  return {
    code: error.code,
    message: unmapped.length > 0 ? `${error.message}（${unmapped.join('；')}）` : error.message,
  }
}

/**
 * After any saved, edited or deleted trade / transfer: cash summary and ledger, trades (lists
 * and details), positions and the audit log refresh without reload.
 */
export async function refreshAfterChange(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: cashQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: optionTradeQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: auditLogQueryKeys.all }),
  ])
}

/** After a newly recorded trade (FEAT-001 / FEAT-004). */
export async function refreshAfterTradeSaved(
  queryClient: QueryClient,
  trade: OptionTrade,
): Promise<void> {
  await refreshAfterChange(queryClient)
  queryClient.setQueryData(optionTradeQueryKeys.detail(trade.id), trade)
}

/** Net-amount label by action: paid (BTO / BTC) or received (STO / STC). */
export function netAmountLabel(action: string | undefined): string {
  if (action === 'STO' || action === 'STC') return '实收净额（已扣手续费）'
  if (action === 'BTO' || action === 'BTC') return '实付总额（含手续费）'
  return '净额（含手续费）'
}

export function isClosingAction(action: string | undefined): action is ClosingAction {
  return action === 'BTC' || action === 'STC'
}
