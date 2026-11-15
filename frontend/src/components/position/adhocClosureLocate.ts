/**
 * FEAT-035 (Q221) 切换到 <账户> 去调整: router state handed to the positions page after the switch,
 * so it scrolls to (and highlights) that account's first affected row like FEAT-042 去调整.
 */
export interface AdhocClosureLocateState {
  adhocClosureDate: string
  /** The account switched to; the state is ignored under any other account (e.g. after switching back). */
  adhocClosureAccountId: number
}

export function readAdhocClosureLocateState(state: unknown): AdhocClosureLocateState | null {
  if (typeof state !== 'object' || state === null) return null
  const record = state as Record<string, unknown>
  const date = record.adhocClosureDate
  const accountId = record.adhocClosureAccountId
  if (typeof date !== 'string' || typeof accountId !== 'number') return null
  return { adhocClosureDate: date, adhocClosureAccountId: accountId }
}
