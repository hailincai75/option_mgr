import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, setBackendNow } from '../support/clock'
import { apiTransfer, CashPage, expect, test } from '../support/fixtures'
import { apiTrade } from '../support/trade'

/**
 * FEAT-043 临时休市时调整到期日 E2E (fixed backend clock + page.clock, fake Finnhub via the FEAT-009 test-support
 * fetch; FEAT-042 detects 2026-12-04 as an ad-hoc closure).
 *
 * Scenario (requirement AC): first fetch 2026-11-30 08:00 New York (2023–2027, with 2025-01-09, without 2026-12-04);
 * account A (default, id 1) trades entered 2026-11-30 10:00 while 2026-12-04 is still a trading day:
 *   P1 = QRTV 2026-12-04 50P: STO 1 2026-11-20 10:00 120.00 (lot 1), STO 1 2026-11-24 10:00 100.00 (lot 2),
 *        BTC 1 2026-11-25 10:00 40.00 (closes lot 1, +80.00) → short 1, frozen 5,000.00
 *   P2 = BTO 2 IXQZ 2026-12-04 9C 60.00;  P3 = STO 1 QRTV 2026-12-18 45P 150.00
 * second fetch 2026-12-01 08:00 adds 2026-12-04 National Day of Mourning; "today" = 2026-12-01 10:00 New York.
 * Accounts C / D (AC 8 / 9): 2025-01-09 puts seeded with the FEAT-043-extended e2e legacy endpoint (STO PUT).
 * Fictional tickers only.
 *
 * Test ids assumed (to reconcile with frontend):
 *   position-adjust-expiration        "调整到期日" button in a position row (only when expirationAdjustable)
 *   adjust-expiration-modal           modal root (antd: the visible dialog is getByRole('dialog') inside it)
 *   adjust-expiration-contract        contract label, e.g. "QRTV 2026-12-04 50 P"
 *   adjust-expiration-current         "2026-12-04（National Day of Mourning）"
 *   adjust-expiration-record-count    "3 条开仓 / 平仓记录"
 *   adjust-expiration-hint            "该合约在本账户的 3 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录"
 *   adjust-expiration-date            the date picker input (value YYYY-MM-DD); day cells located by antd td[title]
 *   adjust-expiration-note            optional note (textarea / input)
 *   adjust-expiration-submit          confirm button
 *   adjust-expiration-cancel          cancel button
 *   adjust-expiration-error           backend error message verbatim, attribute data-error-code = backend `code`
 *   audit-adjust-trade-ids            on an ADJUST_EXPIRATION audit row: the modified record ids (each as "#<id>")
 *   audit-adjust-note                 on an ADJUST_EXPIRATION audit row: the note
 * Existing ids reused: position-row[data-contract], position-cell-expiration / -dte / -remaining-trading-days /
 * -frozen, position-adhoc-closure-tag, position-expire, expire-modal, adhoc-closure-alert(-message), trade-row
 * [data-trade-id], trade-cell-contract, trade-history, closed-row[data-trade-id], closed-cell-contract,
 * closed-cell-pnl, closed-history, audit-row, audit-cell-operation, audit-cell-summary, account-switcher.
 */

const T_FETCH1 = '2026-11-30T13:00:00Z' // 08:00 New York
const T_TRADE = '2026-11-30T15:00:00Z' // 10:00 New York
const T_FETCH2 = '2026-12-01T13:00:00Z'
const T_DAY2 = '2026-12-01T15:00:00Z' // 10:00 New York

const P1 = 'QRTV 2026-12-04 50 P'
const P1_NEW = 'QRTV 2026-12-03 50 P'
const P2 = 'IXQZ 2026-12-04 9 C'
const P2_NEW = 'IXQZ 2026-12-03 9 C'
const P3 = 'QRTV 2026-12-18 45 P'

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

// ------------------------------------------------------------------------------------------------ test support / API

async function fetchAt(at: string, rows: Row[]): Promise<void> {
  await setBackendNow(at)
  const r = await fetch(`${BACKEND_URL}/api/test-support/trading-calendar/fetch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ httpStatus: 200, body: { data: rows, exchange: 'US', timezone: 'America/New_York' } }),
  })
  if (r.status !== 200) throw new Error(`fake fetch: ${r.status} ${await r.text()}`)
  const s = (await r.json()) as { lastAttemptResult: string }
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

async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const r = await fetch(`${BACKEND_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (r.status !== 201 && r.status !== 200) throw new Error(`POST ${path}: ${r.status} ${await r.text()}`)
  return (await r.json()) as T
}

/** FEAT-043-extended e2e legacy opening (BTO / STO, CALL / PUT): skips only the Q126 trading-day check. */
async function apiLegacyOpen(t: { symbol: string; expiration: string; strike: string; type: 'CALL' | 'PUT'; action: 'BTO' | 'STO'; netAmount: string; time: string }): Promise<number> {
  const res = await apiPost<{ id: number }>('/api/test-support/legacy-option-trades', {
    symbol: t.symbol,
    expirationDate: t.expiration,
    strike: t.strike,
    optionType: t.type,
    action: t.action,
    contracts: 1,
    netAmount: t.netAmount,
    tradedAtLocal: t.time,
    multiplier: 100,
    note: null,
  })
  return res.id
}

async function apiClose(t: { action: 'BTC' | 'STC'; symbol: string; expiration: string; strike: string; type: 'CALL' | 'PUT'; contracts?: number; netAmount: string; time: string }): Promise<number> {
  const res = await apiPost<{ id: number }>('/api/option-trades/closes', {
    symbol: t.symbol,
    expirationDate: t.expiration,
    strike: t.strike,
    optionType: t.type,
    action: t.action,
    contracts: t.contracts ?? 1,
    netAmount: t.netAmount,
    tradedAtLocal: t.time,
    note: null,
  })
  return res.id
}

interface Ids {
  lot1: number
  lot2: number
  btc: number
  p2: number
  p3: number
}

/**
 * AC scenario of account A. `extra` runs at 2026-11-30 10:00 New York, before 2026-12-04 is detected. Leaves backend
 * and browser at 2026-12-01 10:00 New York.
 */
async function seed(page: Page, extra?: () => Promise<void>): Promise<Ids> {
  await fetchAt(T_FETCH1, FINNHUB_2023_2027)
  await setBackendNow(T_TRADE)
  await apiTransfer('DEPOSIT', '30000.00', '2026-11-19T10:00')
  const lot1 = (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-04', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-20T10:00' })).id
  const lot2 = (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-04', strike: '50', type: 'PUT', netAmount: '100.00', time: '2026-11-24T10:00' })).id
  const btc = await apiClose({ action: 'BTC', symbol: 'QRTV', expiration: '2026-12-04', strike: '50', type: 'PUT', netAmount: '40.00', time: '2026-11-25T10:00' })
  const p2 = (await apiTrade({ action: 'BTO', symbol: 'IXQZ', expiration: '2026-12-04', strike: '9', type: 'CALL', contracts: 2, netAmount: '60.00', time: '2026-11-30T09:41' })).id
  const p3 = (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '150.00', time: '2026-11-30T09:42' })).id
  if (extra) await extra()
  await fetchAt(T_FETCH2, WITH_DEC4)
  await fixNow(page, T_DAY2)
  return { lot1, lot2, btc, p2, p3 }
}

/** Account with a 2025-01-09 STO put (2024-12-20 10:00), seeded before any adjustment; "today" 2026-12-01. */
async function seedOldPut(page: Page, name: string, symbol: string, strike: string, net: string): Promise<number> {
  await fetchAt(T_FETCH1, FINNHUB_2023_2027)
  await setBackendNow(T_DAY2)
  const id = await apiCreateAccount(name)
  setApiAccount(id)
  await apiTransfer('DEPOSIT', '10000.00', '2024-12-19T10:00')
  await apiLegacyOpen({ symbol, expiration: '2025-01-09', strike, type: 'PUT', action: 'STO', netAmount: net, time: '2024-12-20T10:00' })
  await fixNow(page, T_DAY2)
  return id
}

// ------------------------------------------------------------------------------------------------ page objects

class Ui {
  readonly alert: Locator
  readonly modal: Locator

  constructor(readonly page: Page) {
    this.alert = page.getByTestId('adhoc-closure-alert')
    this.modal = page.getByTestId('adjust-expiration-modal').getByRole('dialog')
  }

  id(testId: string): Locator {
    return this.page.getByTestId(testId)
  }

  async openPositions(): Promise<void> {
    const alerts = this.page.waitForResponse((r) => r.url().includes('/api/adhoc-closure-alerts'))
    await this.page.goto('/positions')
    await expect(this.id('positions-as-of')).toBeVisible()
    await alerts
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  adjustButton(contract: string): Locator {
    return this.row(contract).getByTestId('position-adjust-expiration')
  }

  async openAdjust(contract: string): Promise<Locator> {
    await this.adjustButton(contract).click()
    await expect(this.modal).toBeVisible()
    return this.modal
  }

  /** Open date-picker dropdown of the dialog. */
  async openPicker(): Promise<Locator> {
    await this.modal.getByTestId('adjust-expiration-date').click()
    const dropdown = this.page.locator('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)')
    await expect(dropdown).toBeVisible()
    return dropdown
  }

  dayCell(dropdown: Locator, date: string): Locator {
    return dropdown.locator(`td[title="${date}"]`)
  }

  /** Submits the dialog and expects success (dialog closes, no error). */
  async submitOk(): Promise<void> {
    await this.modal.getByTestId('adjust-expiration-submit').click()
    await expect(this.modal).toHaveCount(0)
  }

  async alertMessages(): Promise<string[]> {
    return this.alert.getByTestId('adhoc-closure-alert-message').allTextContents()
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.page.getByTestId('account-switcher').click()
    await this.page.getByTestId(`account-option-${id}`).click()
    await expect(this.page.getByTestId('account-current')).toHaveText(name)
  }

  tradeRow(id: number): Locator {
    return this.page.locator(`[data-testid="trade-row"][data-trade-id="${id}"]`)
  }

  closedRow(id: number): Locator {
    return this.page.locator(`[data-testid="closed-row"][data-trade-id="${id}"]`)
  }
}

test.afterEach(() => {
  setApiAccount(1)
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-043 调整到期日', () => {
  test('AC1+AC2 P1、P2 有"调整到期日"，P3 没有；P1 对话框：2026-12-04（National Day of Mourning）、3 条记录、默认 2026-12-03，12-04/05/06 不可选', async ({ page }) => {
    await seed(page)
    const ui = new Ui(page)
    await ui.openPositions()

    await expect(ui.adjustButton(P1)).toHaveText(/调整到期日/)
    await expect(ui.adjustButton(P2)).toHaveText(/调整到期日/)
    await expect(ui.row(P3)).toBeVisible()
    await expect(ui.adjustButton(P3)).toHaveCount(0)
    await expect(ui.id('position-adjust-expiration')).toHaveCount(2)

    const d = await ui.openAdjust(P1)
    await expect(d.getByTestId('adjust-expiration-contract')).toHaveText(P1)
    await expect(d.getByTestId('adjust-expiration-current')).toHaveText('2026-12-04（National Day of Mourning）')
    await expect(d.getByTestId('adjust-expiration-record-count')).toContainText('3 条开仓 / 平仓记录')
    await expect(d.getByTestId('adjust-expiration-hint')).toHaveText('该合约在本账户的 3 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录')
    await expect(d.getByTestId('adjust-expiration-date')).toHaveValue('2026-12-03')
    await expect(d.getByTestId('adjust-expiration-note')).toHaveValue('')

    const dropdown = await ui.openPicker()
    for (const day of ['2026-12-04', '2026-12-05', '2026-12-06']) {
      await expect(ui.dayCell(dropdown, day), day).toHaveClass(/ant-picker-cell-disabled/)
    }
    // range 2026-11-25 (last trade date) .. 2026-12-03; Thanksgiving 11-26 is not a trading day
    await expect(ui.dayCell(dropdown, '2026-12-03')).not.toHaveClass(/ant-picker-cell-disabled/)
    // November is not shown in the December view: go to the previous month (must not jump back while a date is filled)
    // (under load the first click can land while the dropdown is still animating open and be dropped: click again only
    // while the view still shows December, so it can never overshoot to October)
    const view = dropdown.locator('.ant-picker-header-view')
    await expect(async () => {
      if (/Dec\s*2026|2026.*12/.test(await view.innerText())) {
        await dropdown.locator('.ant-picker-header-prev-btn').click()
      }
      await expect(view).toHaveText(/Nov\s*2026|2026.*11/, { timeout: 2_000 })
    }).toPass({ timeout: 15_000 })
    await expect(ui.dayCell(dropdown, '2026-11-25')).not.toHaveClass(/ant-picker-cell-disabled/)
    await expect(ui.dayCell(dropdown, '2026-11-24')).toHaveClass(/ant-picker-cell-disabled/)
    await expect(ui.dayCell(dropdown, '2026-11-26')).toHaveClass(/ant-picker-cell-disabled/)
    await page.keyboard.press('Escape')

    // cancel changes nothing
    await d.getByTestId('adjust-expiration-cancel').click()
    await expect(ui.modal).toHaveCount(0)
    await expect(ui.row(P1)).toBeVisible()
    await expect(ui.adjustButton(P1)).toBeVisible()
  })

  test('AC3+AC4 确认 2026-12-03：不刷新页面，P1 到期日 12-03、DTE 2、剩余 2、冻结 5,000.00、无标签无按钮，提醒变 1 个；资金不变；交易列表 / 已平仓 / 审计都显示新合约', async ({ page }) => {
    const ids = await seed(page)
    const ui = new Ui(page)
    const cash = new CashPage(page)
    await cash.open()
    await cash.expectSummary('30,270.00', '9,500.00', '20,770.00')

    await ui.openPositions()
    await expect.poll(() => ui.alertMessages()).toEqual([msg('2026-12-04', MOURNING_2026, 2)])
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })

    const d = await ui.openAdjust(P1)
    await d.getByTestId('adjust-expiration-note').fill('临时休市，交易所改为 12-03 到期')
    await ui.submitOk()

    // no reload: positions and alert refreshed in place
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
    await expect(ui.row(P1)).toHaveCount(0)
    const row = ui.row(P1_NEW)
    await expect(row.getByTestId('position-cell-expiration')).toHaveText('2026-12-03')
    await expect(row.getByTestId('position-cell-dte')).toHaveText('2')
    await expect(row.getByTestId('position-cell-remaining-trading-days')).toHaveText('2')
    await expect(row.getByTestId('position-cell-frozen')).toHaveText('5,000.00')
    await expect(row.getByTestId('position-adhoc-closure-tag')).toHaveCount(0)
    await expect(row.getByTestId('position-adjust-expiration')).toHaveCount(0)
    await expect.poll(() => ui.alertMessages()).toEqual([msg('2026-12-04', MOURNING_2026, 1)])
    await expect(ui.adjustButton(P2)).toBeVisible()

    // Q130: cash unchanged
    await cash.open()
    await cash.expectSummary('30,270.00', '9,500.00', '20,770.00')

    // AC4 trade list: 3 records on the new contract, each with "历史"
    await page.goto('/trades')
    for (const id of [ids.lot1, ids.lot2, ids.btc]) {
      await expect(ui.tradeRow(id).getByTestId('trade-cell-contract')).toHaveText(P1_NEW)
      await expect(ui.tradeRow(id).getByTestId('trade-history')).toBeVisible()
    }
    await expect(ui.tradeRow(ids.p2).getByTestId('trade-cell-contract')).toHaveText(P2)
    await expect(ui.tradeRow(ids.p2).getByTestId('trade-history')).toHaveCount(0)
    await expect(ui.tradeRow(ids.p3).getByTestId('trade-history')).toHaveCount(0)

    // closed page: BTC still +80.00
    await page.goto('/closed')
    await expect(ui.closedRow(ids.btc).getByTestId('closed-cell-contract')).toHaveText(P1_NEW)
    await expect(ui.closedRow(ids.btc).getByTestId('closed-cell-pnl')).toHaveText('+80.00')
    await expect(ui.closedRow(ids.btc).getByTestId('closed-history')).toBeVisible()

    // audit page: one 调整到期日 record
    await page.goto('/audit')
    await expect(ui.id('audit-row')).toHaveCount(1)
    const a = ui.id('audit-row').first()
    await expect(a.getByTestId('audit-cell-operation')).toHaveText('调整到期日')
    await expect(a.getByTestId('audit-cell-summary')).toHaveText('调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）')
    await expect(a).toContainText('2026-12-04 → 2026-12-03')
    const tradeIds = a.getByTestId('audit-adjust-trade-ids')
    for (const id of [ids.lot1, ids.lot2, ids.btc]) await expect(tradeIds).toContainText(`#${id}`)
    await expect(a.getByTestId('audit-adjust-note')).toHaveText('临时休市，交易所改为 12-03 到期')

    // "历史" of the second lot (not the audit objectId) shows the adjustment
    await page.goto('/trades')
    await ui.tradeRow(ids.lot2).getByTestId('trade-history').click()
    await expect(page).toHaveURL(/\/audit\?.*objectType=OPTION_TRADE/)
    expect(new URL(page.url()).searchParams.get('objectId')).toBe(String(ids.lot2))
    await expect(ui.id('audit-row')).toHaveCount(1)
    await expect(ui.id('audit-row').first().getByTestId('audit-cell-operation')).toHaveText('调整到期日')
  })

  test('AC5 再把 P2 调整为 2026-12-03 → 成功，临时休市提醒消失（Q129）', async ({ page }) => {
    await seed(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openAdjust(P1)
    await ui.submitOk()
    await expect.poll(() => ui.alertMessages()).toEqual([msg('2026-12-04', MOURNING_2026, 1)])

    const d = await ui.openAdjust(P2)
    await expect(d.getByTestId('adjust-expiration-current')).toHaveText('2026-12-04（National Day of Mourning）')
    await expect(d.getByTestId('adjust-expiration-hint')).toHaveText('该合约在本账户的 1 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录')
    await expect(d.getByTestId('adjust-expiration-date')).toHaveValue('2026-12-03')
    await ui.submitOk()
    await expect(ui.row(P2_NEW)).toBeVisible()
    await expect(ui.alert).toHaveCount(0)
    await expect(ui.id('adhoc-closure-alerts')).toHaveCount(0)
    await expect(ui.id('position-adjust-expiration')).toHaveCount(0)

    await page.goto('/')
    await expect(page.getByTestId('dashboard-as-of')).toBeVisible()
    await expect(ui.alert).toHaveCount(0)
  })

  test('后端拒绝（Q132 目标合约已有记录）：错误原样显示在对话框里、不关闭，什么都不改', async ({ page }) => {
    await seed(page, async () => {
      await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-03', strike: '50', type: 'PUT', netAmount: '30.00', time: '2026-11-30T09:50' })
      await apiClose({ action: 'BTC', symbol: 'QRTV', expiration: '2026-12-03', strike: '50', type: 'PUT', netAmount: '10.00', time: '2026-11-30T09:51' })
    })
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openAdjust(P1)
    await d.getByTestId('adjust-expiration-submit').click()
    const err = d.getByTestId('adjust-expiration-error')
    await expect(err).toHaveText('该账户已有合约 QRTV 2026-12-03 50 P 的记录，不能合并')
    await expect(err).toHaveAttribute('data-error-code', 'TARGET_CONTRACT_EXISTS')
    await expect(ui.modal).toBeVisible()
    await expect(d.getByTestId('adjust-expiration-date')).toHaveValue('2026-12-03')

    await d.getByTestId('adjust-expiration-cancel').click()
    await expect(ui.row(P1)).toBeVisible()
    await expect(ui.adjustButton(P1)).toBeVisible()
    await expect.poll(() => ui.alertMessages()).toEqual([msg('2026-12-04', MOURNING_2026, 2)])
  })

  test('边界：提交时后端 500 → 对话框显示错误、不关闭；持仓不变', async ({ page }) => {
    await seed(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await page.route('**/api/option-trades/expiration-adjustments', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/option-trades/expiration-adjustments', timestamp: T_DAY2, details: [] }),
      }),
    )
    const d = await ui.openAdjust(P1)
    await d.getByTestId('adjust-expiration-submit').click()
    await expect(d.getByTestId('adjust-expiration-error')).toBeVisible()
    await expect(d.getByTestId('adjust-expiration-error')).not.toHaveText('')
    await expect(ui.modal).toBeVisible()
    await page.unroute('**/api/option-trades/expiration-adjustments')
    await d.getByTestId('adjust-expiration-cancel').click()
    await expect(ui.row(P1)).toBeVisible()
  })

  test('AC8 账户 C：QRTV 2025-01-09 40P 已作废 → 持仓页没有该行，也没有"调整到期日"按钮', async ({ page }) => {
    const c = await seedOldPut(page, '账户C', 'QRTV', '40', '80.00')
    await apiPost('/api/option-trades/expirations', { symbol: 'QRTV', expirationDate: '2025-01-09', strike: '40', optionType: 'PUT', contracts: 1 })
    setApiAccount(1)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(c, '账户C')
    await expect(ui.id('positions-as-of')).toBeVisible()
    await expect(ui.row('QRTV 2025-01-09 40 P')).toHaveCount(0)
    await expect(ui.id('position-adjust-expiration')).toHaveCount(0)
    await expect(ui.alert).toHaveCount(0)
  })

  test('AC9 账户 D：IXQZ 2025-01-09 8P 调整为 2025-01-08 → 显示"已过期 692 天"、"作废"；作废记在 2025-01-08 16:00（Q63）', async ({ page }) => {
    const dAcc = await seedOldPut(page, '账户D', 'IXQZ', '8', '30.00')
    setApiAccount(1)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(dAcc, '账户D')
    await expect.poll(() => ui.alertMessages()).toEqual([msg('2025-01-09', MOURNING_2025, 1)])

    const d = await ui.openAdjust('IXQZ 2025-01-09 8 P')
    await expect(d.getByTestId('adjust-expiration-current')).toHaveText('2025-01-09（National Mourning Day）')
    await expect(d.getByTestId('adjust-expiration-date')).toHaveValue('2025-01-08')
    await ui.submitOk()

    const row = ui.row('IXQZ 2025-01-08 8 P')
    await expect(row.getByTestId('position-cell-dte')).toHaveText('已过期 692 天')
    await expect(row.getByTestId('position-expire')).toBeVisible()
    await expect(row.getByTestId('position-adjust-expiration')).toHaveCount(0)
    await expect(ui.alert).toHaveCount(0)

    await row.getByTestId('position-expire').click()
    const e = page.getByTestId('expire-modal').getByRole('dialog')
    await expect(e).toBeVisible()
    await expect(e.getByTestId('expire-time')).toHaveText('2025-01-08 16:00（美东）')
    await e.getByTestId('expire-submit').click()
    await expect(e.getByTestId('expire-result')).toBeVisible()
    await expect(e.getByTestId('expire-result-time')).toHaveText('2025-01-08 16:00（美东）')
    await e.getByTestId('expire-done').click()
    await expect(row).toHaveCount(0)
  })
})
