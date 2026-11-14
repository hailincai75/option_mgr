import { test as base, expect, type Locator, type Page } from '@playwright/test'
import {
  BACKEND_URL,
  backendIsUp,
  e2eBackend,
  ensureWorkerStack,
  FRONTEND_URL,
  setApiAccount,
  startBackend,
  type E2EBackend,
} from './backend'
import { releaseBackendClock } from './clock'
import { TradeForm } from './trade'

export { expect }

/** U+2212, used by the app for negative money. */
export const MINUS = '−'

type WorkerFixtures = {
  /**
   * This worker's own stack (e2e-profile backend with its own in-memory DB + Vite dev server proxying to it, see
   * backend.ts), started on first use and kept for the whole run (stopped by global teardown). Auto-used.
   */
  workerStack: void
}

type Fixtures = {
  /**
   * This worker's own e2e-profile backend (started by workerStack), reset to an empty database before each
   * test via POST /api/test-support/reset. Auto-used. Tests may stop/start it to simulate an outage.
   */
  backend: E2EBackend
  cash: CashPage
  trade: TradeForm
}

export const test = base.extend<Fixtures, WorkerFixtures>({
  workerStack: [
    async ({}, use) => {
      await ensureWorkerStack()
      await use()
    },
    { scope: 'worker', auto: true, timeout: 240_000 },
  ],
  // Pages go to this worker's Vite dev server (and through its /api proxy to this worker's backend).
  baseURL: async ({}, use) => {
    await use(FRONTEND_URL)
  },
  backend: [
    async ({}, use) => {
      // Self-heal if a previous test left the backend stopped (e.g. it failed during the outage test).
      if (!(await backendIsUp())) await startBackend('recovered')
      setApiAccount(1)
      await e2eBackend.reset()
      const s = (await (await fetch(`${BACKEND_URL}/api/cash/summary`)).json()) as { totalCash?: string }
      if (s.totalCash !== '0.00') throw new Error(`database not empty after reset: ${JSON.stringify(s)}`)
      await use(e2eBackend)
      // FEAT-009: a test may have fixed the backend clock; give the next test (and a reused backend) the real one.
      await releaseBackendClock().catch(() => undefined)
    },
    { auto: true, timeout: 30_000 },
  ],
  cash: async ({ page }, use) => {
    await use(new CashPage(page))
  },
  trade: async ({ page }, use) => {
    await use(new TradeForm(page))
  },
})

export interface TransferInput {
  type?: 'DEPOSIT' | 'WITHDRAWAL'
  amount?: string
  time?: string
  remark?: string
}

export interface LedgerRow {
  time: string
  type: string
  amount: string
  balance: string
  frozen: string
  note: string
}

/** Seeds data directly through the backend API (used for "Given" states; the "When" goes through the UI). */
export async function apiTransfer(
  type: 'DEPOSIT' | 'WITHDRAWAL',
  amount: string,
  occurredAtLocal: string,
  remark: string | null = null,
): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/cash/transfers`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, amount, occurredAtLocal, remark }),
  })
  if (r.status !== 201) throw new Error(`seed ${type} ${amount} @${occurredAtLocal} failed: ${r.status} ${await r.text()}`)
}

export async function apiLedger(page = 1, size = 100): Promise<{
  items: { occurredAt: string; sourceType: string; amount: string; balanceAfter: string; remark: string | null }[]
  totalItems: number
}> {
  const r = await fetch(`${BACKEND_URL}/api/cash/ledger?page=${page}&size=${size}`)
  return (await r.json()) as never
}

/** "YYYY-MM-DDTHH:mm" in America/New_York for the given instant (independent of machine time zone). */
export function nyLocal(date: Date): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: 'America/New_York',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(date)
      .map((p) => [p.type, p.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}

export class CashPage {
  readonly summaryTotal: Locator
  readonly summaryFrozen: Locator
  readonly summaryAvailable: Locator
  readonly typeDeposit: Locator
  readonly typeWithdrawal: Locator
  readonly amount: Locator
  readonly time: Locator
  readonly remark: Locator
  readonly submit: Locator
  readonly submitError: Locator
  readonly ledgerRows: Locator
  readonly ledgerEmpty: Locator
  readonly ledgerTotal: Locator
  readonly ledgerPagination: Locator

  constructor(readonly page: Page) {
    this.summaryTotal = page.getByTestId('cash-summary-total')
    this.summaryFrozen = page.getByTestId('cash-summary-frozen')
    this.summaryAvailable = page.getByTestId('cash-summary-available')
    this.typeDeposit = page.getByTestId('cash-transfer-type-deposit')
    this.typeWithdrawal = page.getByTestId('cash-transfer-type-withdrawal')
    this.amount = page.getByTestId('cash-transfer-amount')
    this.time = page.getByTestId('cash-transfer-time')
    this.remark = page.getByTestId('cash-transfer-remark')
    this.submit = page.getByTestId('cash-transfer-submit')
    this.submitError = page.getByTestId('cash-transfer-error')
    this.ledgerRows = page.getByTestId('cash-ledger-row')
    this.ledgerEmpty = page.getByTestId('cash-ledger-empty')
    this.ledgerTotal = page.getByTestId('cash-ledger-total')
    this.ledgerPagination = page.getByTestId('cash-ledger-pagination')
  }

  async open(): Promise<void> {
    await this.page.goto('/cash')
    await expect(this.page.getByTestId('cash-summary')).toBeVisible()
    await expect(this.summaryTotal).toBeVisible()
  }

  /** Form-item error text under the given field. */
  fieldError(field: Locator | 'type'): Locator {
    const anchor = field === 'type' ? this.page.getByTestId('cash-transfer-type') : field
    return this.page
      .locator('.ant-form-item')
      .filter({ has: anchor })
      .locator('.ant-form-item-explain-error')
  }

  async chooseType(type: 'DEPOSIT' | 'WITHDRAWAL'): Promise<void> {
    await (type === 'DEPOSIT' ? this.typeDeposit : this.typeWithdrawal).click()
  }

  async fill(input: TransferInput): Promise<void> {
    if (input.type) await this.chooseType(input.type)
    if (input.amount !== undefined) await this.amount.fill(input.amount)
    if (input.time !== undefined) await this.time.fill(input.time)
    if (input.remark !== undefined) await this.remark.fill(input.remark)
  }

  /** Fill + submit and wait for the success toast. */
  async save(input: TransferInput): Promise<void> {
    await this.fill(input)
    await this.submit.click()
    const text = input.type === 'WITHDRAWAL' ? '出金已保存' : '入金已保存'
    await expect(this.page.locator('.ant-message').getByText(text).last()).toBeVisible()
    await expect(this.submit).not.toHaveClass(/ant-btn-loading/)
  }

  async expectSummary(total: string, frozen: string, available: string): Promise<void> {
    await expect(this.summaryTotal).toHaveText(total)
    await expect(this.summaryFrozen).toHaveText(frozen)
    await expect(this.summaryAvailable).toHaveText(available)
  }

  async rows(): Promise<LedgerRow[]> {
    return this.ledgerRows.evaluateAll((rows) =>
      rows.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        return {
          time: t('cash-ledger-time'),
          type: t('cash-ledger-type'),
          amount: t('cash-ledger-amount'),
          balance: t('cash-ledger-balance'),
          note: t('cash-ledger-note'),
          frozen: t('cash-ledger-frozen'),
        }
      }),
    )
  }

  /** Waits until the ledger shows exactly these rows (time, type, amount, balance[, note]). */
  async expectRows(expected: (Partial<LedgerRow> & Pick<LedgerRow, 'amount' | 'balance'>)[]): Promise<void> {
    await expect
      .poll(async () => {
        const rows = await this.rows()
        return rows.map((r, i) => {
          const e = expected[i]
          if (!e) return r
          return Object.fromEntries(Object.keys(e).map((k) => [k, r[k as keyof LedgerRow]]))
        })
      })
      .toEqual(expected)
  }
}
