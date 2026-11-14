import type { Page } from '@playwright/test'
import { BACKEND_URL } from './backend'

/**
 * FEAT-009: fixed "now" for backend and browser. Since Q126 an opening cannot expire on a weekend / NYSE holiday, so
 * tests whose expirations are relative to "today" use a fixed today (a trading day) instead of the real clock.
 * - Backend: PUT /api/test-support/clock (e2e profile). POST /api/test-support/reset (before every test) and the
 *   fixture teardown (DELETE) restore the system clock.
 * - Browser: page.clock.install at the same instant (time keeps flowing from there, timers run).
 */
export async function setBackendNow(instant: string): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/clock`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ now: instant }),
  })
  if (r.status !== 204) throw new Error(`PUT /api/test-support/clock ${instant}: ${r.status} ${await r.text()}`)
}

export async function releaseBackendClock(): Promise<void> {
  await fetch(`${BACKEND_URL}/api/test-support/clock`, { method: 'DELETE' })
}

const installed = new WeakSet<Page>()

/**
 * Backend fixed at `instant` (ISO-8601 UTC); browser clock set to the same instant and then running on from there
 * (page.clock.install / setSystemTime). Not setFixedTime: antd computes toast lifetimes and some animations from
 * Date.now(), so with a frozen Date message toasts never close and cover the navigation.
 */
export async function fixNow(page: Page, instant: string): Promise<void> {
  await setBackendNow(instant)
  if (installed.has(page)) {
    await page.clock.setSystemTime(new Date(instant))
  } else {
    await page.clock.install({ time: new Date(instant) })
    installed.add(page)
  }
}

/** UTC instant of a New York local time "YYYY-MM-DDTHH:mm" (EST/EDT handled). */
export function nyInstant(local: string): string {
  const [d, hm] = local.split('T')
  // try both offsets; keep the one that formats back to the same NY local time
  for (const off of ['-05:00', '-04:00']) {
    const dt = new Date(`${d}T${hm}:00${off}`)
    const back = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).format(dt).replace(', ', 'T')
    if (back === local) return dt.toISOString()
  }
  throw new Error(`not a New York local time: ${local}`)
}

/**
 * FEAT-012 phases of a position expiring today: before 16:00 New York it can be closed; 16:00 to before 17:30 it is
 * "到期待确认"; from 17:30 it can be expired. With a fixed clock each phase is tested at a chosen time.
 */
export type Phase = 'closable' | 'pending' | 'expirable'
export const PHASES: readonly Phase[] = ['closable', 'pending', 'expirable']
const PHASE_TIME: Record<Phase, string> = { closable: '10:00', pending: '16:30', expirable: '17:45' }

/** Instant of `phase` on the New York date `date`. */
export function phaseInstant(date: string, phase: Phase): string {
  return nyInstant(`${date}T${PHASE_TIME[phase]}`)
}

/** The phase as a widened `Phase` (so branches on other phases still type-check). */
export function phaseOf(phase: Phase): Phase {
  return phase
}
