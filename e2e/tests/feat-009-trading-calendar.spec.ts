import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL } from '../support/backend'
import { fixNow } from '../support/clock'
import { apiTransfer, expect, test } from '../support/fixtures'
import { apiLegacyTrade, apiTrade, TradeForm } from '../support/trade'

/**
 * FEAT-009 美股交易日历 E2E. First spec with a FIXED backend clock:
 * - backend "now": PUT /api/test-support/clock (e2e profile; POST /api/test-support/reset in the fixture restores it,
 *   and afterEach DELETEs it as well);
 * - Finnhub: the e2e backend never calls Finnhub; POST /api/test-support/trading-calendar/fetch processes a fake
 *   response exactly like a real fetch;
 * - browser "now": page.clock installed at the same instant (support/clock.ts fixNow), so "today" in the page
 *   (default trade time, picker's initial month) matches the backend.
 * Scenario (requirement): today 2026-11-23 (Mon) 10:00 New York; fake Finnhub 2023–2027; account A: P1 = STO 1 QRTV
 * 2026-11-27 50P 120.00, P2 = STO 1 QRTV 2026-12-18 45P 150.00 (2026-11-20 10:00); available 10,770.00.
 * Fictional tickers only.
 */

const NOW = '2026-11-23T15:00:00Z' // 10:00 New York
const P1 = 'QRTV 2026-11-27 50 P'
const P2 = 'QRTV 2026-12-18 45 P'
const NEVER_SUCCEEDED = '休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）'
const RTD_HINT = '今天之后到到期日（含）的美股交易日数，不含周末和休市日'

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
  closed("New Year's Day", '2025-01-01'), closed('National Mourning Day', '2025-01-09'),
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

// ------------------------------------------------------------------------------------------------ test support

async function backendNow(instant: string): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/clock`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ now: instant }),
  })
  if (r.status !== 204) throw new Error(`PUT clock ${instant}: ${r.status} ${await r.text()}`)
}

async function backendRealClock(): Promise<void> {
  await fetch(`${BACKEND_URL}/api/test-support/clock`, { method: 'DELETE' })
}

/** Fake Finnhub fetch at the backend's current (fixed) time; returns the resulting status. */
async function fakeFetch(body: unknown): Promise<{ lastAttemptResult: string; stale: boolean; warning: string | null }> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/trading-calendar/fetch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (r.status !== 200) throw new Error(`fake fetch: ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

async function fetchFinnhub(rows: Row[]): Promise<void> {
  const s = await fakeFetch({ httpStatus: 200, body: { data: rows, exchange: 'US', timezone: 'America/New_York' } })
  if (s.lastAttemptResult !== 'SUCCESS') throw new Error(`fake fetch not SUCCESS: ${JSON.stringify(s)}`)
}

/** Backend and browser at the same instant. */
async function nowEverywhere(page: Page, instant: string): Promise<void> {
  await fixNow(page, instant)
}

/** Requirement scenario; leaves backend and browser at 2026-11-23 10:00 New York. */
async function seedScenario(page: Page): Promise<void> {
  await backendNow('2026-11-23T13:00:00Z') // fetch at 08:00 New York
  await fetchFinnhub(FINNHUB_2023_2027)
  await nowEverywhere(page, NOW)
  await seedScenarioTrades()
}

async function seedScenarioTrades(): Promise<void> {
  await apiTransfer('DEPOSIT', '20000.00', '2026-11-19T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-27', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-20T10:00' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '150.00', time: '2026-11-20T10:00' })
}

// ------------------------------------------------------------------------------------------------ page objects

class Positions {
  readonly warning: Locator
  constructor(readonly page: Page) {
    this.warning = page.getByTestId('trading-calendar-stale-warning')
  }

  /** Opens /positions and waits until the calendar status has been loaded. */
  async open(): Promise<void> {
    const status = this.page.waitForResponse((r) => r.url().includes('/api/trading-calendar/status'))
    await this.page.goto('/positions')
    await expect(this.page.getByTestId('positions-as-of')).toBeVisible()
    await status
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  dte(contract: string): Locator {
    return this.row(contract).getByTestId('position-cell-dte')
  }

  rtd(contract: string): Locator {
    return this.row(contract).getByTestId('position-cell-remaining-trading-days')
  }

  async expectDays(contract: string, dte: string, rtd: string): Promise<void> {
    await expect(this.dte(contract)).toHaveText(dte)
    await expect(this.rtd(contract)).toHaveText(rtd)
  }

  /** Close modal of FEAT-004: BTC with the given net / time; waits for the confirmation. */
  async close(contract: string, net: string, time: string): Promise<void> {
    await this.row(contract).getByTestId('position-close').click()
    const modal = this.page.getByTestId('close-trade-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await modal.getByTestId('close-trade-net-amount').fill(net)
    await modal.getByTestId('close-trade-time').fill(time)
    await modal.getByTestId('close-trade-submit').click()
    await expect(modal.getByTestId('close-trade-confirmation')).toBeVisible()
    await expect(modal.getByTestId('close-trade-error')).toHaveCount(0)
  }
}

/** The 到期日 date picker of the trade entry form (AntD DatePicker). */
class ExpiryPicker {
  constructor(readonly page: Page, readonly trade: TradeForm) {}

  dropdown(): Locator {
    return this.page.locator('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)')
  }

  async openPanel(): Promise<void> {
    await this.trade.expiration.click()
    await expect(this.dropdown()).toBeVisible()
  }

  /** The <td> of a day in the open panel. */
  cell(date: string): Locator {
    return this.dropdown().getByTestId(`open-trade-expiration-day-${date}`).locator('xpath=ancestor::td[1]')
  }

  async expectDisabled(...dates: string[]): Promise<void> {
    for (const d of dates) await expect(this.cell(d), `${d} disabled`).toHaveClass(/ant-picker-cell-disabled/)
  }

  async expectEnabled(...dates: string[]): Promise<void> {
    for (const d of dates) await expect(this.cell(d), `${d} selectable`).not.toHaveClass(/ant-picker-cell-disabled/)
  }

  async nextMonth(times: number): Promise<void> {
    for (let i = 0; i < times; i++) await this.dropdown().locator('.ant-picker-header-next-btn').click()
  }
}

test.afterEach(async () => {
  await backendRealClock()
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-009 美股交易日历', () => {
  test('AC1 持仓页：P1 DTE 4 / 剩余交易日 3，P2 DTE 25 / 剩余交易日 18；无"休市日数据未更新"；列标题悬停说明', async ({ page }) => {
    await seedScenario(page)
    const pos = new Positions(page)
    await pos.open()
    await expect(page.getByTestId('positions-as-of')).toHaveText('截至 2026-11-23（美东）')
    await pos.expectDays(P1, '4', '3')
    await pos.expectDays(P2, '25', '18')
    await expect(pos.warning).toHaveCount(0)

    const header = page.getByRole('columnheader', { name: '剩余交易日' })
    await expect(header).toBeVisible()
    await header.getByText('剩余交易日').hover()
    await expect(page.locator('.ant-tooltip').getByText(RTD_HINT)).toBeVisible()
  })

  test('AC2 今天 11-27：P1 "0 今天到期" / 0、P2 15；周六 11-28：P2 15；11-30 P1 未处理：已过期 3 天 / —、P2 14', async ({ page }) => {
    await seedScenario(page)
    const pos = new Positions(page)

    await nowEverywhere(page, '2026-11-27T15:00:00Z')
    await pos.open()
    await expect(pos.dte(P1)).toHaveText('0 今天到期')
    await expect(pos.rtd(P1)).toHaveText('0')
    await pos.expectDays(P2, '21', '15')

    await nowEverywhere(page, '2026-11-28T15:00:00Z') // Saturday: today is not counted either way
    await pos.open()
    await pos.expectDays(P1, '已过期 1 天', '—')
    await pos.expectDays(P2, '20', '15')

    await nowEverywhere(page, '2026-11-30T15:00:00Z')
    await pos.open()
    await pos.expectDays(P1, '已过期 3 天', '—')
    await pos.expectDays(P2, '18', '14')
  })

  test('AC3 开仓到期日选择器：2026-11 的 11-26 与周末不可选、11-27（提前收盘）可选；2027-03 的 03-26 与周末不可选、03-25 可选；平仓不限制', async ({ page, trade }) => {
    await seedScenario(page)
    await trade.open()
    await trade.actionBto.check()
    const picker = new ExpiryPicker(page, trade)
    await picker.openPanel()
    await picker.expectDisabled('2026-11-26', '2026-11-01', '2026-11-07', '2026-11-08', '2026-11-14', '2026-11-15',
      '2026-11-21', '2026-11-22', '2026-11-28', '2026-11-29')
    await picker.expectEnabled('2026-11-27', '2026-11-25', '2026-11-30', '2026-11-23')
    await expect(page.getByTestId('open-trade-expiration-day-2026-11-26')).toHaveAttribute('title', 'Thanksgiving Day')

    await picker.nextMonth(4)
    await picker.expectDisabled('2027-03-26', '2027-03-06', '2027-03-07', '2027-03-13', '2027-03-14', '2027-03-20',
      '2027-03-21', '2027-03-27', '2027-03-28')
    await picker.expectEnabled('2027-03-25', '2027-03-29', '2027-03-01')
    await picker.cell('2027-03-25').click()
    await expect(trade.expiration).toHaveValue('2027-03-25')
    await expect(trade.fieldError(trade.expiration)).toHaveCount(0)

    // closes are not restricted (Q126: closing never checks the trading calendar)
    await page.getByTestId('open-trade-action-btc').check()
    await picker.openPanel()
    await picker.expectEnabled('2027-03-26', '2027-03-27')
  })

  test('AC3 手动输入非交易日（11-26 感恩节、11-28 周六）提示"所选日期不是美股交易日"；改为 11-27 后提示消失', async ({ page, trade }) => {
    await seedScenario(page)
    await trade.open()
    await trade.actionSto.check()
    // ignore error texts that are animating out (fill clears the field first)
    const expiryError = trade.fieldError(trade.expiration).and(page.locator(':not(.ant-form-show-help-item-leave)'))
    // the month on screen (2026-11) is loaded when the form opens; the rule re-checks once data arrives
    await trade.expiration.fill('2026-11-26')
    await trade.strike.click()
    await expect(expiryError).toHaveText('所选日期不是美股交易日')
    await trade.expiration.fill('2026-11-28')
    await trade.strike.click()
    await expect(expiryError).toHaveText('所选日期不是美股交易日')
    await trade.expiration.fill('2026-11-27')
    await trade.strike.click()
    await expect(expiryError).toHaveCount(0)
  })

  test('AC6 / 规则 8 非交易日查询失败：不禁用、显示查询失败提示；保存 IXQZ 2027-03-26 30C 时后端错误原样显示，可用现金仍 10,770.00', async ({ page, trade }) => {
    await seedScenario(page)
    await page.route('**/api/trading-calendar/non-trading-days**', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/trading-calendar/non-trading-days', timestamp: '2026-11-23T15:00:00Z', details: [] }),
      }),
    )
    await trade.open()
    await trade.actionBto.check()
    const picker = new ExpiryPicker(page, trade)
    await picker.openPanel()
    await expect(page.getByTestId('open-trade-expiration-calendar-error')).toHaveText('休市日查询失败，暂不禁用非交易日，保存时由后端校验')
    await picker.expectEnabled('2026-11-26', '2026-11-28')
    await page.keyboard.press('Escape')

    await trade.submitAndExpectError(
      { symbol: 'IXQZ', expiration: '2027-03-26', strike: '30', type: 'CALL', contracts: '1', netAmount: '80.00', time: '2026-11-23T09:50', multiplier: '100' },
      'EXPIRATION_NOT_TRADING_DAY',
      '到期日 2027-03-26 不是美股交易日（Good Friday）',
    )
    await expect(page.getByTestId('cash-summary-available')).toHaveText('10,770.00')
    await expect(trade.expiration).toHaveValue('2027-03-26')
  })

  test('AC7 无 key、从未成功：持仓页与开仓表单显示"从未成功获取"提示；选择器仍禁用 11-26（规则）；页面录入开仓与平仓照常', async ({ page, trade }) => {
    await nowEverywhere(page, NOW)
    const pos = new Positions(page)
    // brand-new database, never attempted: no warning yet
    await pos.open()
    await expect(pos.warning).toHaveCount(0)

    await fakeFetch({ noKey: true })
    await apiTransfer('DEPOSIT', '20000.00', '2026-11-19T10:00')
    await pos.open()
    await expect(pos.warning).toHaveText(NEVER_SUCCEEDED)

    await trade.open()
    await expect(page.getByTestId('trading-calendar-stale-warning')).toHaveText(NEVER_SUCCEEDED)
    await trade.actionSto.check()
    const picker = new ExpiryPicker(page, trade)
    await picker.openPanel()
    await picker.expectDisabled('2026-11-26', '2026-11-28')
    await picker.expectEnabled('2026-11-27')
    await page.keyboard.press('Escape')
    await trade.submitAndExpectSaved({ symbol: 'QRTV', expiration: '2026-11-27', strike: '50', type: 'PUT', contracts: '1', netAmount: '120.00', time: '2026-11-23T09:30', multiplier: '100' })

    await pos.open()
    await pos.expectDays(P1, '4', '3')
    await pos.close(P1, '30.00', '2026-11-23T09:55')
  })

  test('AC8 11-20 08:00 成功、11-21 返回 500：开仓表单显示"上次成功：2026-11-20 08:00 美东"，2025-01-09 仍不可选；下一次成功后提示消失', async ({ page, trade }) => {
    await backendNow('2026-11-20T13:00:00Z')
    await fetchFinnhub(FINNHUB_2023_2027)
    await backendNow('2026-11-21T13:00:00Z')
    const failed = await fakeFetch({ httpStatus: 500, body: null })
    expect(failed.lastAttemptResult).toBe('HTTP_ERROR')
    await nowEverywhere(page, '2026-11-21T15:00:00Z')

    const warning = page.getByTestId('trading-calendar-stale-warning')
    await trade.open()
    await expect(warning).toHaveText('休市日数据未更新（上次成功：2026-11-20 08:00 美东）')
    await trade.actionBto.check()
    // typing a date moves the panel to its month
    await trade.expiration.fill('2025-01-08')
    const picker = new ExpiryPicker(page, trade)
    await picker.openPanel()
    await picker.expectDisabled('2025-01-09', '2025-01-11', '2025-01-12')
    await picker.expectEnabled('2025-01-08', '2025-01-10')
    await page.keyboard.press('Escape')

    const pos = new Positions(page)
    await pos.open()
    await expect(pos.warning).toHaveText('休市日数据未更新（上次成功：2026-11-20 08:00 美东）')

    await backendNow('2026-11-22T13:00:00Z')
    await fetchFinnhub(FINNHUB_2023_2027)
    await nowEverywhere(page, '2026-11-22T15:00:00Z')
    await pos.open()
    await expect(pos.warning).toHaveCount(0)
    const status = page.waitForResponse((r) => r.url().includes('/api/trading-calendar/status'))
    await trade.open()
    await status
    await expect(warning).toHaveCount(0)
  })

  test('AC10 FEAT-009 之前已存的周六到期持仓（IXQZ 2026-11-21，今天 11-16）：DTE 5、剩余交易日 4；平仓不被拒绝', async ({ page }) => {
    // The requirement's 9P cannot be created any more (Q126) and the e2e backend has no "legacy put" endpoint; the
    // e2e-only legacy STO call endpoint is not checked (contract), so the same weekend expiration is a 9C here.
    // The backend system test covers the exact 9P.
    await backendNow('2026-11-16T13:00:00Z')
    await fetchFinnhub(FINNHUB_2023_2027)
    await nowEverywhere(page, '2026-11-16T15:00:00Z')
    await apiTransfer('DEPOSIT', '5000.00', '2026-11-12T10:00')
    await apiLegacyTrade({ symbol: 'IXQZ', expiration: '2026-11-21', strike: '9', netAmount: '30.00', time: '2026-11-13T10:00' })

    const pos = new Positions(page)
    await pos.open()
    await pos.expectDays('IXQZ 2026-11-21 9 C', '5', '4')
    await pos.close('IXQZ 2026-11-21 9 C', '10.00', '2026-11-16T09:50')
    await page.getByTestId('close-trade-modal').getByTestId('close-trade-done').click()
    await expect(pos.row('IXQZ 2026-11-21 9 C')).toHaveCount(0)
  })
})
