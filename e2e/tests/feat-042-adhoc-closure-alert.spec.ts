import { chooseFifoLots } from '../support/closeLots'
import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, setBackendNow } from '../support/clock'
import { apiTransfer, expect, test } from '../support/fixtures'
import { apiLegacyTrade, apiTrade } from '../support/trade'

/**
 * FEAT-042 临时休市提醒 E2E (fixed backend clock + page.clock, fake Finnhub via the FEAT-009 test-support fetch).
 *
 * Scenario (requirement): fake Finnhub returns 2023–2027 at 2026-11-30 08:00 New York (with 2025-01-09 National
 * Mourning Day, without 2026-12-04); at 2026-12-01 08:00 it additionally lists 2026-12-04 National Day of Mourning.
 * Account A (default, id 1): P1 = STO 1 QRTV 2026-12-04 50P, P2 = BTO 2 IXQZ 2026-12-04 9C, P3 = STO 1 QRTV
 * 2026-12-18 45P. Account B: only a 2026-12-18 position. Account C: STO 1 QRTV 2025-01-09 40C (legacy, instead of the AC's 40P; opened before any
 * Finnhub data) and P4 = STO 1 IXQZ 2026-12-04 8P. "Today" = 2026-12-01
 * 10:00 New York. Fictional tickers only.
 *
 * Test ids assumed (to reconcile with frontend):
 *   adhoc-closure-alerts            container on top of the home page and the positions page (absent when no alerts)
 *   adhoc-closure-alert             one line per date, attribute data-date="YYYY-MM-DD"
 *   adhoc-closure-alert-message     the backend `message`, verbatim
 *   adhoc-closure-alert-adjust      "去调整" (home: link to /positions; positions page: scrolls to first affected row)
 *   position-adhoc-closure-tag      red "临时休市" tag next to the expiration date in a position row (tooltip = eventName)
 */

const T_PRE = '2026-11-30T12:00:00Z'
const T_FETCH1 = '2026-11-30T13:00:00Z' // 08:00 New York
const T_TRADE = '2026-11-30T15:00:00Z'
const T_FETCH2 = '2026-12-01T13:00:00Z'
const T_DAY2 = '2026-12-01T15:00:00Z' // 10:00 New York
const T_FETCH3 = '2026-12-02T13:00:00Z'
const T_DAY3 = '2026-12-02T15:00:00Z'

const P1 = 'QRTV 2026-12-04 50 P'
const P2 = 'IXQZ 2026-12-04 9 C'
const P3 = 'QRTV 2026-12-18 45 P'
const P4 = 'IXQZ 2026-12-04 8 P'
const OLD = 'QRTV 2025-01-09 40 C'

const MOURNING_2025 = 'National Mourning Day'
const MOURNING_2026 = 'National Day of Mourning'

const msg = (date: string, eventName: string, n: number): string =>
  `${date} 临时休市（${eventName}），你有 ${n} 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日`

// ------------------------------------------------------------------------------------------------ fake Finnhub data

type Row = { eventName: string; atDate: string; tradingHour: string; postMarket: string }
const closed = (eventName: string, atDate: string): Row => ({ eventName, atDate, tradingHour: '', postMarket: '' })
const early = (eventName: string, atDate: string): Row => ({ eventName, atDate, tradingHour: '09:30-13:00', postMarket: '' })

const FINNHUB_2023_2027: Row[] = [
  closed("New Year's Day", '2023-01-02'), closed('Martin Luther King Jr. Day', '2023-01-16'),
  closed("Washington's Birthday", '2023-02-20'), closed('Good Friday', '2023-04-07'), closed('Memorial Day', '2023-05-29'),
  closed('Juneteenth', '2023-06-19'), early('Independence Day', '2023-07-03'), closed('Independence Day', '2023-07-04'),
  closed('Labor Day', '2023-09-04'), closed('Thanksgiving Day', '2023-11-23'), early('Thanksgiving Day', '2023-11-24'),
  closed('Christmas', '2023-12-25'),
  closed("New Year's Day", '2024-01-01'), closed('Martin Luther King Jr. Day', '2024-01-15'),
  closed("Washington's Birthday", '2024-02-19'), closed('Good Friday', '2024-03-29'), closed('Memorial Day', '2024-05-27'),
  closed('Juneteenth', '2024-06-19'), early('Independence Day', '2024-07-03'), closed('Independence Day', '2024-07-04'),
  closed('Labor Day', '2024-09-02'), closed('Thanksgiving Day', '2024-11-28'), early('Thanksgiving Day', '2024-11-29'),
  early('Christmas', '2024-12-24'), closed('Christmas', '2024-12-25'),
  closed("New Year's Day", '2025-01-01'), closed(MOURNING_2025, '2025-01-09'),
  closed('Martin Luther King Jr. Day', '2025-01-20'), closed("Washington's Birthday", '2025-02-17'),
  closed('Good Friday', '2025-04-18'), closed('Memorial Day', '2025-05-26'), closed('Juneteenth', '2025-06-19'),
  early('Independence Day', '2025-07-03'), closed('Independence Day', '2025-07-04'), closed('Labor Day', '2025-09-01'),
  closed('Thanksgiving Day', '2025-11-27'), early('Thanksgiving Day', '2025-11-28'), early('Christmas', '2025-12-24'),
  closed('Christmas', '2025-12-25'),
  closed("New Year's Day", '2026-01-01'), closed('Martin Luther King Jr. Day', '2026-01-19'),
  closed("Washington's Birthday", '2026-02-16'), closed('Good Friday', '2026-04-03'), closed('Memorial Day', '2026-05-25'),
  closed('Juneteenth', '2026-06-19'), closed('Independence Day', '2026-07-03'), closed('Labor Day', '2026-09-07'),
  closed('Thanksgiving Day', '2026-11-26'), early('Thanksgiving Day', '2026-11-27'), early('Christmas', '2026-12-24'),
  closed('Christmas', '2026-12-25'),
  closed("New Year's Day", '2027-01-01'), closed('Martin Luther King Jr. Day', '2027-01-18'),
  closed("Washington's Birthday", '2027-02-15'), closed('Good Friday', '2027-03-26'), closed('Memorial Day', '2027-05-31'),
  closed('Juneteenth', '2027-06-18'), closed('Independence Day', '2027-07-05'), closed('Labor Day', '2027-09-06'),
  closed('Thanksgiving Day', '2027-11-25'), early('Thanksgiving Day', '2027-11-26'), closed('Christmas', '2027-12-24'),
]
const WITH_DEC4: Row[] = [...FINNHUB_2023_2027, closed(MOURNING_2026, '2026-12-04')]

// ------------------------------------------------------------------------------------------------ test support

async function fakeFetch(body: unknown): Promise<{ lastAttemptResult: string }> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/trading-calendar/fetch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (r.status !== 200) throw new Error(`fake fetch: ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

/** Fake successful Finnhub fetch at the backend instant `at`. */
async function fetchAt(at: string, rows: Row[]): Promise<void> {
  await setBackendNow(at)
  const s = await fakeFetch({ httpStatus: 200, body: { data: rows, exchange: 'US', timezone: 'America/New_York' } })
  if (s.lastAttemptResult !== 'SUCCESS') throw new Error(`fake fetch not SUCCESS: ${JSON.stringify(s)}`)
}

async function apiCreateAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

const ACCOUNT_B = '账户B'
const ACCOUNT_C = '账户C'

/**
 * Requirement scenario up to AC 2: account C's 2025 put before any data; first fetch 11-30; trades of A, B, C on
 * 11-30; second fetch 12-01 with 2026-12-04. `beforeSecondFetch` may add more trades on 11-30 (12-04 still open-able).
 * Leaves backend and browser at 2026-12-01 10:00 New York.
 */
async function seed(page: Page, beforeSecondFetch?: () => Promise<void>): Promise<{ b: number; c: number }> {
  await setBackendNow(T_PRE)
  const c = await apiCreateAccount(ACCOUNT_C)
  setApiAccount(c)
  await apiTransfer('DEPOSIT', '10000.00', '2025-01-02T10:00')
  // Jollyday knows 2025-01-09 (one-time, Q134) so Q126 rejects a normal opening; the e2e legacy endpoint (STO CALL
  // only, not checked) stands in for "entered before FEAT-009". The system test covers the exact 40P.
  await apiLegacyTrade({ symbol: 'QRTV', expiration: '2025-01-09', strike: '40', netAmount: '80.00', time: '2025-01-06T10:00' })
  setApiAccount(1)

  await fetchAt(T_FETCH1, FINNHUB_2023_2027)

  await setBackendNow(T_TRADE)
  await apiTransfer('DEPOSIT', '20000.00', '2026-11-25T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-04', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-30T09:40' })
  await apiTrade({ action: 'BTO', symbol: 'IXQZ', expiration: '2026-12-04', strike: '9', type: 'CALL', contracts: 2, netAmount: '60.00', time: '2026-11-30T09:41' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '150.00', time: '2026-11-30T09:42' })

  const b = await apiCreateAccount(ACCOUNT_B)
  setApiAccount(b)
  await apiTransfer('DEPOSIT', '10000.00', '2026-11-25T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '150.00', time: '2026-11-30T09:45' })
  setApiAccount(c)
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-04', strike: '8', type: 'PUT', netAmount: '30.00', time: '2026-11-30T09:50' })
  setApiAccount(1)
  if (beforeSecondFetch) await beforeSecondFetch()

  await fetchAt(T_FETCH2, WITH_DEC4)
  await fixNow(page, T_DAY2)
  return { b, c }
}

// ------------------------------------------------------------------------------------------------ page objects

class Ui {
  readonly alerts: Locator
  readonly alert: Locator
  readonly staleWarning: Locator

  constructor(readonly page: Page) {
    this.alerts = page.getByTestId('adhoc-closure-alerts')
    this.alert = page.getByTestId('adhoc-closure-alert')
    this.staleWarning = page.getByTestId('trading-calendar-stale-warning')
  }

  private alertsResponse(): Promise<unknown> {
    return this.page.waitForResponse((r) => r.url().includes('/api/adhoc-closure-alerts'))
  }

  /** Home page; waits until the alerts request has completed (so "no alert" assertions are meaningful). */
  async openHome(): Promise<void> {
    const alerts = this.alertsResponse()
    await this.page.goto('/')
    await expect(this.page.getByTestId('dashboard-as-of')).toBeVisible()
    await alerts
  }

  async openPositions(): Promise<void> {
    const alerts = this.alertsResponse()
    await this.page.goto('/positions')
    await expect(this.page.getByTestId('positions-as-of')).toBeVisible()
    await alerts
  }

  /** The alert lines' messages, top to bottom. */
  async alertMessages(): Promise<string[]> {
    return this.alert.getByTestId('adhoc-closure-alert-message').allTextContents()
  }

  async expectAlerts(...messages: string[]): Promise<void> {
    if (messages.length === 0) {
      await expect(this.alert).toHaveCount(0)
      await expect(this.alerts).toHaveCount(0)
      return
    }
    await expect(this.alert).toHaveCount(messages.length)
    await expect(this.alert.getByTestId('adhoc-closure-alert-message')).toHaveText(messages)
    for (let i = 0; i < messages.length; i++) {
      const line = this.alert.nth(i)
      await expect(line.getByTestId('adhoc-closure-alert-adjust')).toHaveText('去调整')
      // Q128: no close / dismiss control
      await expect(line.locator('.ant-alert-close-icon')).toHaveCount(0)
      await expect(line.getByRole('button', { name: /关闭|忽略|close/i })).toHaveCount(0)
    }
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  tag(contract: string): Locator {
    return this.row(contract).getByTestId('position-adhoc-closure-tag')
  }

  async expectTagged(contract: string, eventName: string): Promise<void> {
    const tag = this.tag(contract)
    await expect(tag).toHaveText('临时休市')
    await tag.hover()
    await expect(this.page.locator('.ant-tooltip:not(.ant-tooltip-hidden)').getByText(eventName)).toBeVisible()
    await this.page.mouse.move(0, 0)
  }

  async expectNotTagged(contract: string): Promise<void> {
    await expect(this.row(contract)).toBeVisible()
    await expect(this.tag(contract)).toHaveCount(0)
  }

  dte(contract: string): Locator {
    return this.row(contract).getByTestId('position-cell-dte')
  }

  rtd(contract: string): Locator {
    return this.row(contract).getByTestId('position-cell-remaining-trading-days')
  }

  async switchAccount(id: number, name: string): Promise<void> {
    const alerts = this.alertsResponse()
    await this.page.getByTestId('account-switcher').click()
    await this.page.getByTestId(`account-option-${id}`).click()
    await expect(this.page.getByTestId('account-current')).toHaveText(name)
    await alerts
  }

  /** FEAT-004 close modal on the positions page; closes the given number of contracts. */
  async close(contract: string, contracts: number, net: string, time: string): Promise<void> {
    await this.row(contract).getByTestId('position-close').click()
    const modal = this.page.getByTestId('close-trade-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await chooseFifoLots(modal, contracts)
    await modal.getByTestId('close-trade-net-amount').fill(net)
    await modal.getByTestId('close-trade-time').fill(time)
    await modal.getByTestId('close-trade-submit').click()
    await expect(modal.getByTestId('close-trade-confirmation')).toBeVisible()
    await expect(modal.getByTestId('close-trade-error')).toHaveCount(0)
    await modal.getByTestId('close-trade-done').click()
    await expect(modal.getByRole('dialog')).toHaveCount(0)
  }
}

test.afterEach(() => {
  setApiAccount(1)
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-042 临时休市提醒', () => {
  test('AC3 账户 A：首页和持仓页顶部都显示"2026-12-04 临时休市（National Day of Mourning），你有 2 个持仓……"；P1、P2 有"临时休市"标签（悬停 eventName），P3 没有', async ({ page }) => {
    await seed(page)
    const ui = new Ui(page)

    await ui.openHome()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
    await expect(ui.alert.first()).toHaveAttribute('data-date', '2026-12-04')

    await ui.openPositions()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
    await ui.expectTagged(P1, MOURNING_2026)
    await ui.expectTagged(P2, MOURNING_2026)
    await ui.expectNotTagged(P3)
  })

  test('AC4 切换到账户 B：首页和持仓页都没有提醒；切回账户 A 提醒回来', async ({ page }) => {
    const { b } = await seed(page)
    const ui = new Ui(page)

    await ui.openHome()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
    await ui.switchAccount(b, ACCOUNT_B)
    await ui.expectAlerts()

    await ui.openPositions()
    await ui.expectAlerts()
    await ui.expectNotTagged(P3)

    await ui.switchAccount(1, '默认账户')
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
  })

  test('AC5 持仓页 P2 STC 2 张 → "你有 1 个持仓"；P1 BTC 1 张 → 提醒消失（Q129）；期间没有关闭按钮（Q128）', async ({ page }) => {
    await seed(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))

    await ui.close(P2, 2, '80.00', '2026-12-01T09:45')
    await expect(ui.row(P2)).toHaveCount(0)
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 1))
    await ui.expectTagged(P1, MOURNING_2026)

    await ui.close(P1, 1, '40.00', '2026-12-01T09:50')
    await expect(ui.row(P1)).toHaveCount(0)
    await ui.expectAlerts()

    await ui.openHome()
    await ui.expectAlerts()
  })

  test('AC6 账户 C 首页：两行提醒，依次 2025-01-09（1 个持仓）、2026-12-04（1 个持仓）；持仓页两行都有标签', async ({ page }) => {
    const { c } = await seed(page)
    const ui = new Ui(page)
    await ui.openHome()
    await ui.switchAccount(c, ACCOUNT_C)
    await ui.expectAlerts(msg('2025-01-09', MOURNING_2025, 1), msg('2026-12-04', MOURNING_2026, 1))
    await expect(ui.alert.nth(0)).toHaveAttribute('data-date', '2025-01-09')
    await expect(ui.alert.nth(1)).toHaveAttribute('data-date', '2026-12-04')

    await ui.openPositions()
    await ui.expectAlerts(msg('2025-01-09', MOURNING_2025, 1), msg('2026-12-04', MOURNING_2026, 1))
    await ui.expectTagged(OLD, MOURNING_2025)
    await ui.expectTagged(P4, MOURNING_2026)
  })

  test('AC7 首页点"去调整"打开持仓页；P1、P2 DTE 3、剩余交易日 2、没有作废按钮；12-04 17:45 显示"0 今天到期"、剩余 0、有作废按钮', async ({ page }) => {
    await seed(page)
    const ui = new Ui(page)
    await ui.openHome()
    await expect(ui.alert).toHaveCount(1)
    await ui.alert.getByTestId('adhoc-closure-alert-adjust').click()
    await expect(page).toHaveURL(/\/positions(\?|#|$)/)
    await expect(page.getByTestId('positions-as-of')).toHaveText('截至 2026-12-01（美东）')

    for (const p of [P1, P2]) {
      await expect(ui.dte(p)).toHaveText('3')
      await expect(ui.rtd(p)).toHaveText('2')
      await expect(ui.row(p).getByTestId('position-expire')).toHaveCount(0)
    }
    await expect(ui.dte(P3)).toHaveText('17')
    await expect(ui.rtd(P3)).toHaveText('12')

    await fixNow(page, '2026-12-04T22:45:00Z') // 17:45 New York
    await ui.openPositions()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
    for (const p of [P1, P2]) {
      await expect(ui.dte(p)).toHaveText('0 今天到期')
      await expect(ui.rtd(p)).toHaveText('0')
      await expect(ui.row(p).getByTestId('position-expire')).toBeVisible()
    }
  })

  test('规则 4 持仓页"去调整"滚动到第一个受影响行（前面有 12 个较早到期的持仓）', async ({ page }) => {
    await seed(page, async () => {
      for (let i = 1; i <= 12; i++) {
        await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-03', strike: String(i), type: 'PUT', netAmount: '1.00', time: '2026-11-30T09:55' })
      }
    })
    const ui = new Ui(page)
    await page.setViewportSize({ width: 1280, height: 600 })
    await ui.openPositions()
    const firstAffected = page.locator('[data-testid="position-row"]').filter({ has: page.getByTestId('position-adhoc-closure-tag') }).first()
    await expect(firstAffected).toBeAttached()
    await expect(firstAffected).not.toBeInViewport()
    await ui.alert.getByTestId('adhoc-closure-alert-adjust').click()
    await expect(firstAffected).toBeInViewport()
    await expect(page).toHaveURL(/\/positions/)
  })

  test('AC8 12-02 拉取中 2026-12-04 被删除（已撤销）：首页、持仓页提醒和标签消失；开仓选择器 12-04 可选', async ({ page, trade }) => {
    await seed(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))

    await fetchAt(T_FETCH3, FINNHUB_2023_2027)
    await fixNow(page, T_DAY3)
    await ui.openPositions()
    await ui.expectAlerts()
    await ui.expectNotTagged(P1)
    await ui.expectNotTagged(P2)
    await ui.openHome()
    await ui.expectAlerts()

    await trade.open()
    await trade.actionSto.check()
    await trade.expiration.click()
    const dropdown = page.locator('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)')
    await expect(dropdown).toBeVisible()
    const cell = (d: string) => dropdown.getByTestId(`open-trade-expiration-day-${d}`).locator('xpath=ancestor::td[1]')
    await expect(cell('2026-12-04')).not.toHaveClass(/ant-picker-cell-disabled/)
    await expect(cell('2026-12-05')).toHaveClass(/ant-picker-cell-disabled/)
  })

  test('AC9 12-02 拉取失败：首页、持仓页提醒照常，持仓页另有"休市日数据未更新（上次成功：2026-12-01 08:00 美东）"', async ({ page }) => {
    await seed(page)
    await setBackendNow(T_FETCH3)
    const s = await fakeFetch({ httpStatus: 500, body: null })
    expect(s.lastAttemptResult).toBe('HTTP_ERROR')
    await fixNow(page, T_DAY3)
    const ui = new Ui(page)

    await ui.openHome()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
    await ui.openPositions()
    await ui.expectAlerts(msg('2026-12-04', MOURNING_2026, 2))
    await expect(ui.staleWarning).toHaveText('休市日数据未更新（上次成功：2026-12-01 08:00 美东）')
    await ui.expectTagged(P1, MOURNING_2026)
  })

  test('边界：提醒接口报错时持仓页照常可用（持仓行与标签照常显示）', async ({ page }) => {
    await seed(page)
    await page.route('**/api/adhoc-closure-alerts', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/adhoc-closure-alerts', timestamp: T_DAY2, details: [] }),
      }),
    )
    const ui = new Ui(page)
    await ui.openPositions()
    await expect(ui.row(P3)).toBeVisible()
    await expect(ui.tag(P1)).toHaveText('临时休市')
    await ui.openHome()
    await expect(page.getByTestId('dashboard-as-of')).toHaveText('截至 2026-12-01（美东）')
  })
})

