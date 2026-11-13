import { nyLocal } from './fixtures'

/**
 * New York date/time helpers for tests that run on the real clock (FEAT-012 Q10 / Q83).
 * On the expiration date: before 16:00 a position can be closed; from 16:00 to before 17:30 it is
 * "到期待确认" (no buttons); from 17:30 it can be expired.
 */

/** Today's New York date, YYYY-MM-DD. */
export const nyToday = (): string => nyLocal(new Date()).slice(0, 10)

/** YYYY-MM-DD plus n days (calendar arithmetic, no time zone involved). */
export function addDays(date: string, n: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}

export type ExpiryPhase = 'closable' | 'pending' | 'expirable'

function minutesOfDayNy(): number {
  const hm = nyLocal(new Date()).slice(11, 16)
  return Number(hm.slice(0, 2)) * 60 + Number(hm.slice(3, 5))
}

/** Phase of a position expiring today, from the current New York time. */
export function phaseNow(): ExpiryPhase {
  const m = minutesOfDayNy()
  if (m < 16 * 60) return 'closable'
  if (m < 17 * 60 + 30) return 'pending'
  return 'expirable'
}

/**
 * Like {@link phaseNow}, but if a boundary (16:00 / 17:30 NY) is less than `marginSec` seconds away it first waits
 * until it has passed, so the page and the assertions see the same phase.
 */
export async function stablePhase(marginSec = 45): Promise<ExpiryPhase> {
  const now = new Date()
  const secOfDay = minutesOfDayNy() * 60 + now.getSeconds()
  for (const boundary of [16 * 3600, 17 * 3600 + 30 * 60]) {
    const until = boundary - secOfDay
    if (until > 0 && until < marginSec) await new Promise((r) => setTimeout(r, (until + 2) * 1000))
  }
  return phaseNow()
}
