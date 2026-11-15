import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-035 全部账户合并查看（一）E2E (fixed backend clock + page.clock, fake CBOE / Finnhub via the e2e test-support
 * endpoints; never the real CBOE / Finnhub; ports from support/backend.ts, never 8080 / 5173).
 *
 * Scenario S (requirement; today 2026-11-23 Mon 10:00 New York, the 10:00 pull succeeds; fictional names / tickers):
 *   甲账户 (id 1, default account renamed): deposit 50,000.00; 转入 IXQZ 100 shares cost 5,880.00 (price 61.10);
 *     A1 = STO 1 QRTV 2026-12-18 50P 118.70 (mark 1.25); STO QRTV 2026-12-18 48P 100.00 + BTC 11-10 40.00 (+60.00);
 *     A4 = STO 1 IXQZ 2026-11-27 65C 84.35 (mark 0.85, covered).
 *   乙账户 (id 2): deposit 20,000.00; 10-15 BTO QRTV 2026-12-18 55C 50.00 + 10-20 STC 80.00 (+30.00);
 *     B3 = STO 1 QRTV 2026-12-18 50P 120.00; B4 = STO 1 QRTV 2026-11-20 45P 60.00 (expired 3 days, unprocessed).
 *
 * AC 8: A1 / B3 are entered BEFORE the fake Finnhub fetch that adds the 2026-12-18 ad-hoc closure (otherwise the
 * opening is rejected with 422 EXPIRATION_NOT_TRADING_DAY, Q126).
 *
 * Test ids: the new FEAT-035 ids are in the TID block below (from docs/architecture.md); known ids from earlier
 * features are used directly.
 */

// ================================================================================================ test ids (FEAT-035)
/** Reconciled with docs/architecture.md "全部账户（FEAT-035）" (frontend). */
const TID = {
  /** Switcher entry "全部账户" (only with ≥ 2 accounts); account-switcher has data-all-accounts="true|false". */
  optionAll: 'account-option-all',
  /** One-line notice at the top of every page; the text is in all-accounts-read-only-hint. */
  readOnlyBanner: 'all-accounts-banner',
  readOnlyHint: 'all-accounts-read-only-hint',
  /** Placeholder of a page that does not support ALL yet (rule 7) and its per-account switch buttons. */
  unsupported: 'all-accounts-placeholder',
  unsupportedMessage: 'all-accounts-placeholder-message',
  unsupportedSwitch: (id: number) => `all-accounts-placeholder-account-${id}`,
  /** /trades/new, /stocks/new under ALL: `录入需要选择一个账户` + one button per account. */
  entryChooser: 'all-accounts-entry',
  entryMessage: 'all-accounts-entry-message',
  entrySwitch: (id: number) => `all-accounts-entry-account-${id}`,
  /** First column `账户` of the option / stock tables (only in ALL mode). */
  positionAccountCell: 'position-cell-account',
  stockAccountCell: 'stock-position-account',
  /** Attribute on position-row / stock-position-row carrying the row's account id (rows of the same contract). */
  rowAccountAttr: 'data-account-id',
  /** Cash page summary table under ALL: one row per account + sum row. */
  cashAccountsTable: 'cash-summary-accounts',
  cashAccountRow: 'cash-summary-account-row', // [data-account-id]
  cashAccountName: 'cash-summary-account-name',
  cashAccountTotal: 'cash-summary-account-totalCash',
  cashAccountFrozen: 'cash-summary-account-frozenCash',
  cashAccountAvailable: 'cash-summary-account-availableCash',
  cashTotalRow: 'cash-summary-sum-row', // cash-summary-sum-label + cash-summary-total / -frozen / -available
  cashTotalLabel: 'cash-summary-sum-label',
  /** Ledger area placeholder on the cash page (rule 7). */
  cashLedgerUnsupported: 'cash-ledger-all-accounts',
  cashLedgerUnsupportedMessage: 'cash-ledger-all-accounts-message',
  /** Ad-hoc closure alert: `切换到 <账户> 去调整` per affected account (replaces adhoc-closure-alert-adjust). */
  alertSwitch: (id: number) => `adhoc-closure-alert-switch-${id}`,
  /** Grey text in place of the FEAT-019 portfolio Greeks summary under ALL. */
  greeksUnsupported: 'greeks-summary-all-accounts',
} as const

// ================================================================================================ texts (requirement)
const A_NAME = '甲账户'
const B_NAME = '乙账户'
const CURRENT_ALL = '当前：全部账户（只读）'
const CURRENT_ALL_NAME = '全部账户（只读）'
const READ_ONLY_NOTICE = '全部账户（只读）：录入、修改、删除请先选择一个账户'
const UNSUPPORTED_TEXT = '此页暂不支持全部账户，请选择一个账户查看'
const ENTRY_TEXT = '录入需要选择一个账户'
const GREEKS_UNSUPPORTED_TEXT = '组合 Greeks 汇总暂只支持单个账户'
const ALERT_ALL =
  '2026-12-18 临时休市（Test Closure），你有 2 个持仓在该日到期（甲账户 1 个、乙账户 1 个），期权通常改为前一个交易日到期，请核对并调整到期日'

const A1 = 'QRTV 2026-12-18 50 P'
const B3 = A1
const A4 = 'IXQZ 2026-11-27 65 C'
const B4 = 'QRTV 2026-11-20 45 P'

/** Write controls that must be hidden (not disabled) under ALL (rule 5). Known ids from FEAT-004…043. */
const POSITION_WRITE_IDS = [
  'position-close',
  'position-expire',
  'position-assign',
  'position-exercise',
  'position-adjust-expiration',
  'positions-batch-expire',
  'stock-position-sell',
]

const ny = (local: string): string => nyInstant(local)

// ================================================================================================ seeding (API)

type Opt = Record<string, number | string>
type SourceResponse = { httpStatus: number; body: unknown }

const option = (code: string, fields: Record<string, number>): Opt => ({ option: code, ...fields })

function cboe(symbol: string, price: number, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: { timestamp: '2026-11-23 09:45:00', data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 50, options } },
  }
}

const QRTV_CHAIN = cboe('QRTV', 52.4, [
  option('QRTV261218P00050000', { bid: 1.2, ask: 1.3, theo: 1.24, iv: 0.3512, delta: -0.3021, gamma: 0.0512, theta: -0.0456, vega: 0.0711, rho: -0.0123 }),
])
const IXQZ_CHAIN = cboe('IXQZ', 61.1, [
  option('IXQZ261127C00065000', { bid: 0.8, ask: 0.9, theo: 0.84, iv: 0.281, delta: 0.22, gamma: 0.061, theta: -0.039, vega: 0.031, rho: 0.004 }),
])

async function setSources(cboeMap: Record<string, SourceResponse>): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/sources`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ finnhubKeyPresent: true, cboe: cboeMap, finnhub: {} }),
  })
  if (r.status !== 204) throw new Error(`PUT sources: ${r.status} ${await r.text()}`)
}

async function tickAt(local: string): Promise<void> {
  await setBackendNow(ny(local))
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/tick`, { method: 'POST' })
  if (r.status !== 200) throw new Error(`tick: ${r.status} ${await r.text()}`)
  const body = (await r.json()) as { ran: boolean }
  if (!body.ran) throw new Error(`tick at ${local} did not run: ${JSON.stringify(body)}`)
}

async function accountsCall(method: 'POST' | 'PUT', path: string, name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== (method === 'POST' ? 201 : 200)) throw new Error(`${method} account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiClose(t: { action: 'BTC' | 'STC'; symbol: string; expiration: string; strike: string; type: 'CALL' | 'PUT'; netAmount: string; time: string }): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol: t.symbol, expirationDate: t.expiration, strike: t.strike, optionType: t.type, action: t.action, contracts: 1, netAmount: t.netAmount, tradedAtLocal: t.time }),
  })
  if (r.status !== 201) throw new Error(`close failed: ${r.status} ${await r.text()}`)
}

/** Scenario S data only. Backend clock left at 2026-11-23 09:50 New York; API account 1. */
async function scenarioData(): Promise<void> {
  await setBackendNow(ny('2026-11-23T09:50'))
  setApiAccount(1)
  await accountsCall('PUT', '/1', A_NAME)
  await apiTransfer('DEPOSIT', '50000.00', '2026-10-01T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '48', type: 'PUT', netAmount: '100.00', time: '2026-11-05T10:00' })
  await apiClose({ action: 'BTC', symbol: 'QRTV', expiration: '2026-12-18', strike: '48', type: 'PUT', netAmount: '40.00', time: '2026-11-10T10:00' })
  await apiTransferInShares('IXQZ', 100, '2026-11-19', '5880.00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '118.70', time: '2026-11-20T10:00' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-11-27', strike: '65', type: 'CALL', netAmount: '84.35', time: '2026-11-20T10:10' })

  const b = await accountsCall('POST', '', B_NAME)
  if (b !== 2) throw new Error(`乙账户 expected id 2, got ${b}`)
  setApiAccount(2)
  await apiTransfer('DEPOSIT', '20000.00', '2026-10-01T10:00')
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '55', type: 'CALL', netAmount: '50.00', time: '2026-10-15T10:00' })
  await apiClose({ action: 'STC', symbol: 'QRTV', expiration: '2026-12-18', strike: '55', type: 'CALL', netAmount: '80.00', time: '2026-10-20T10:00' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-20', strike: '45', type: 'PUT', netAmount: '60.00', time: '2026-11-17T09:40' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-20T10:20' })
  setApiAccount(1)
}

async function pull(): Promise<void> {
  await setSources({ QRTV: QRTV_CHAIN, IXQZ: IXQZ_CHAIN })
  await tickAt('2026-11-23T10:00')
}

/** NYSE 2026–2027 as published plus 2026-12-18 Test Closure (full day); fetched at the current backend clock. */
async function injectDec18Closure(): Promise<void> {
  const closed = (eventName: string, atDate: string) => ({ eventName, atDate, tradingHour: '', postMarket: '' })
  const early = (eventName: string, atDate: string) => ({ eventName, atDate, tradingHour: '09:30-13:00', postMarket: '' })
  const data = [
    closed("New Year's Day", '2026-01-01'), closed('Martin Luther King Jr. Day', '2026-01-19'),
    closed("Washington's Birthday", '2026-02-16'), closed('Good Friday', '2026-04-03'), closed('Memorial Day', '2026-05-25'),
    closed('Juneteenth', '2026-06-19'), closed('Independence Day', '2026-07-03'), closed('Labor Day', '2026-09-07'),
    closed('Thanksgiving Day', '2026-11-26'), early('Thanksgiving Day', '2026-11-27'), early('Christmas', '2026-12-24'),
    closed('Christmas', '2026-12-25'),
    closed("New Year's Day", '2027-01-01'), closed('Martin Luther King Jr. Day', '2027-01-18'),
    closed("Washington's Birthday", '2027-02-15'), closed('Good Friday', '2027-03-26'), closed('Memorial Day', '2027-05-31'),
    closed('Juneteenth', '2027-06-18'), closed('Independence Day', '2027-07-05'), closed('Labor Day', '2027-09-06'),
    closed('Thanksgiving Day', '2027-11-25'), early('Thanksgiving Day', '2027-11-26'), closed('Christmas', '2027-12-24'),
    closed('Test Closure', '2026-12-18'),
  ]
  const r = await fetch(`${BACKEND_URL}/api/test-support/trading-calendar/fetch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ httpStatus: 200, body: { data, exchange: 'US', timezone: 'America/New_York' } }),
  })
  if (r.status !== 200) throw new Error(`calendar fetch: ${r.status} ${await r.text()}`)
  const s = (await r.json()) as { lastAttemptResult: string }
  if (s.lastAttemptResult !== 'SUCCESS') throw new Error(`calendar fetch not SUCCESS: ${JSON.stringify(s)}`)
}

/** Scenario S + pull; backend and browser at 2026-11-23 10:05 New York. */
async function scenarioS(page: Page): Promise<void> {
  await scenarioData()
  await pull()
  await fixNow(page, ny('2026-11-23T10:05'))
}

// ================================================================================================ page helpers

const id = (page: Page, testId: string): Locator => page.getByTestId(testId)

async function switchToAll(page: Page): Promise<void> {
  await id(page, 'account-switcher').click()
  await id(page, TID.optionAll).click()
  await expectAllMode(page)
}

async function expectAllMode(page: Page): Promise<void> {
  await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'true')
  await expect(id(page, 'account-switcher')).toContainText(CURRENT_ALL)
  await expect(id(page, 'account-current')).toHaveText(CURRENT_ALL_NAME)
}

async function switchTo(page: Page, accountId: number, name: string): Promise<void> {
  await id(page, 'account-switcher').click()
  // wait for the dropdown to finish opening before clicking (a click during the open animation was lost once in
  // the M5 full run under 3 workers)
  await expect(id(page, `account-option-${accountId}`)).toBeVisible()
  await id(page, `account-option-${accountId}`).click()
  await expect(id(page, 'account-current')).toHaveText(name)
}

function optRow(page: Page, contract: string, accountId?: number): Locator {
  const acc = accountId === undefined ? '' : `[${TID.rowAccountAttr}="${accountId}"]`
  return page.locator(`[data-testid="position-row"][data-contract="${contract}"]${acc}`)
}

function stockRow(page: Page, symbol: string, accountId?: number): Locator {
  const acc = accountId === undefined ? '' : `[${TID.rowAccountAttr}="${accountId}"]`
  return page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]${acc}`)
}

/** "账户 合约" of each option row, top to bottom. */
async function optionOrder(page: Page): Promise<string[]> {
  return page.getByTestId('position-row').evaluateAll(
    (rs, cell) => rs.map((r) => `${r.querySelector(`[data-testid="${cell}"]`)?.textContent?.trim() ?? ''} ${r.getAttribute('data-contract') ?? ''}`.trim()),
    TID.positionAccountCell,
  )
}

/** Positions page; waits until positions loaded. */
async function openPositions(page: Page, search = ''): Promise<void> {
  const loaded = page.waitForResponse((r) => r.url().includes('/api/option-positions'))
  await page.goto(`/positions${search}`)
  await loaded
  await expect(id(page, 'positions-as-of')).toBeVisible()
}

async function expectNoWriteControls(page: Page): Promise<void> {
  for (const w of POSITION_WRITE_IDS) {
    await expect(id(page, w), `${w} hidden under ALL`).toHaveCount(0)
  }
}

/** Summary rows "KEY count option stock total" (FEAT-040 ids). */
async function pnlRows(page: Page, kind: 'symbol' | 'month'): Promise<string[]> {
  return page.getByTestId(`pnl-${kind}-row`).evaluateAll(
    (rs, p) => rs.map((r) => [`${p}-link`, `${p}-count`, `${p}-option`, `${p}-stock`, `${p}-pnl`].map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '').join(' ')),
    `pnl-${kind}`,
  )
}

// ================================================================================================ tests

test.describe('FEAT-035 全部账户合并查看（一）', () => {
  test('AC1 切换器：选全部账户 → 当前：全部账户（只读）+ 只读提示；刷新后仍是全部账户；切到甲账户 → 只有 A4、A1，无账户列，平仓等按钮恢复', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await id(page, 'account-switcher').click()
    await expect(id(page, TID.optionAll)).toContainText('全部账户')
    await page.keyboard.press('Escape')
    await switchToAll(page)
    await expect(id(page, TID.readOnlyBanner).getByTestId(TID.readOnlyHint)).toHaveText(READ_ONLY_NOTICE)

    await page.reload()
    await expectAllMode(page)
    await expect(id(page, TID.readOnlyBanner).getByTestId(TID.readOnlyHint)).toHaveText(READ_ONLY_NOTICE)

    await openPositions(page)
    await expect(id(page, 'position-row')).toHaveCount(4)
    await expect(id(page, TID.positionAccountCell)).toHaveCount(4)
    await expect(page.locator('thead th').filter({ hasText: /^账户$/ }).first()).toBeVisible()

    // leaving ALL: the page re-renders as single account (no reload)
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })
    await switchTo(page, 1, A_NAME)
    await expect(id(page, 'position-row')).toHaveCount(2)
    expect(await page.getByTestId('position-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract')))).toEqual([A4, A1])
    await expect(id(page, TID.positionAccountCell)).toHaveCount(0)
    await expect(page.locator('thead th').filter({ hasText: /^账户$/ })).toHaveCount(0)
    await expect(id(page, TID.readOnlyBanner)).toHaveCount(0)
    await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
    await expect(optRow(page, A1).getByTestId('position-close')).toBeVisible()
    await expect(optRow(page, A4).getByTestId('position-close')).toBeVisible()
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
  })

  test('AC2 首页：现金 70,473.05 / 14,500.00 / 55,973.05；已实现 +90.00（2 笔）、本月 +60.00（1 笔）；期权 4、冻结 14,500.00、7 天内 1、已过期 1、股票 1；无录入交易入口', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await switchToAll(page)
    await expect(id(page, 'cash-summary-total')).toHaveText('70,473.05')
    await expect(id(page, 'cash-summary-frozen')).toHaveText('14,500.00')
    await expect(id(page, 'cash-summary-available')).toHaveText('55,973.05')
    await expect(id(page, 'dashboard-realized-total')).toHaveText('+90.00')
    await expect(id(page, 'dashboard-realized-count')).toHaveText('2')
    await expect(id(page, 'dashboard-month-total')).toHaveText('+60.00')
    await expect(id(page, 'dashboard-month-count')).toHaveText('1')
    await expect(id(page, 'dashboard-position-count')).toHaveText('4')
    await expect(id(page, 'dashboard-frozen-total')).toHaveText('14,500.00')
    await expect(id(page, 'dashboard-expiring-count')).toHaveText('1')
    await expect(id(page, 'dashboard-expired-count')).toHaveText('1')
    await expect(id(page, 'dashboard-stock-position-count')).toHaveText('1')
    await expect(id(page, 'dashboard-new-trade')).toHaveCount(0)
    await expect(page.getByRole('link', { name: '录入交易' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '录入交易' })).toHaveCount(0)
  })

  test('AC3 资金页：甲 / 乙 / 合计三行；没有入金出金表单；流水区显示合并流水（FEAT-046 起不再是占位）', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/cash')
    await switchToAll(page)
    const rows = id(page, TID.cashAccountRow)
    await expect(rows).toHaveCount(2)
    const cells = (r: Locator) =>
      r.evaluate((el, ids) => ids.map((x) => el.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '').join(' '), [
        TID.cashAccountName, TID.cashAccountTotal, TID.cashAccountFrozen, TID.cashAccountAvailable,
      ])
    expect(await cells(rows.nth(0))).toBe('甲账户 50,263.05 5,000.00 45,263.05')
    expect(await cells(rows.nth(1))).toBe('乙账户 20,210.00 9,500.00 10,710.00')
    await expect(rows.nth(0)).toHaveAttribute('data-account-id', '1')
    const total = id(page, TID.cashTotalRow)
    await expect(total.getByTestId(TID.cashTotalLabel)).toHaveText('合计')
    await expect(total.getByTestId('cash-summary-total')).toHaveText('70,473.05')
    await expect(total.getByTestId('cash-summary-frozen')).toHaveText('14,500.00')
    await expect(total.getByTestId('cash-summary-available')).toHaveText('55,973.05')

    for (const f of ['cash-transfer-type', 'cash-transfer-amount', 'cash-transfer-time', 'cash-transfer-submit']) {
      await expect(id(page, f), `${f} hidden`).toHaveCount(0)
    }
    // FEAT-046 (requirement change): the ledger is merged under ALL — 甲 5 + 乙 5 entries, no placeholder
    await expect(id(page, 'cash-ledger-row')).toHaveCount(10)
    await expect(id(page, TID.cashLedgerUnsupported)).toHaveCount(0)
    await expect(id(page, TID.cashLedgerUnsupportedMessage)).toHaveCount(0)
  })

  test('AC4 持仓页期权行：乙 B4（已过期 3 天，4,500.00）、甲 A4（DTE 4，已覆盖 100 股）、甲 A1（+118.70，5,000.00）、乙 B3（+120.00，5,000.00）；共 4 个持仓、冻结 14,500.00', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    await expect.poll(() => optionOrder(page)).toEqual([`乙账户 ${B4}`, `甲账户 ${A4}`, `甲账户 ${A1}`, `乙账户 ${B3}`])
    await expect(id(page, 'positions-count')).toHaveText('共 4 个持仓')
    await expect(id(page, 'positions-frozen-total')).toHaveText('冻结现金合计 14,500.00')

    const b4 = optRow(page, B4, 2)
    await expect(b4.getByTestId('position-cell-dte')).toHaveText('已过期 3 天')
    await expect(b4.getByTestId('position-cell-frozen')).toHaveText('4,500.00')
    const a4 = optRow(page, A4, 1)
    await expect(a4.getByTestId('position-cell-dte')).toContainText('4')
    await expect(a4.getByTestId('position-cell-coverage-checked')).toHaveText('已覆盖 100 股')
    const a1 = optRow(page, A1, 1)
    await expect(a1.getByTestId('position-cell-opening-net')).toHaveText('+118.70')
    await expect(a1.getByTestId('position-cell-frozen')).toHaveText('5,000.00')
    await expect(a1.getByTestId(TID.positionAccountCell)).toHaveText(A_NAME)
    const b3 = optRow(page, B3, 2)
    await expect(b3.getByTestId('position-cell-opening-net')).toHaveText('+120.00')
    await expect(b3.getByTestId('position-cell-frozen')).toHaveText('5,000.00')
    await expect(b3.getByTestId(TID.positionAccountCell)).toHaveText(B_NAME)
    // same contract = two separate rows (Q215)
    await expect(optRow(page, A1)).toHaveCount(2)
  })

  test('AC5 股票与未实现合计：甲 IXQZ 100 股 +6,110.00 / +230.00；A1 −6.30、B3 −5.00、A4 −0.65、B4 已到期，待处理；合计行与未计入提示', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    const s = stockRow(page, 'IXQZ', 1)
    await expect(id(page, 'stock-position-row')).toHaveCount(1)
    await expect(s.getByTestId(TID.stockAccountCell)).toHaveText(A_NAME)
    await expect(s.getByTestId('stock-position-shares')).toContainText('100')
    await expect(s.getByTestId('stock-position-market-value')).toHaveText('+6,110.00')
    await expect(s.getByTestId('stock-position-unrealized-pnl')).toHaveText('+230.00')

    await expect(optRow(page, A1, 1).getByTestId('position-unrealized-pnl')).toHaveText(`${MINUS}6.30`)
    await expect(optRow(page, B3, 2).getByTestId('position-unrealized-pnl')).toHaveText(`${MINUS}5.00`)
    await expect(optRow(page, A4, 1).getByTestId('position-unrealized-pnl')).toHaveText(`${MINUS}0.65`)
    await expect(optRow(page, B4, 2).getByTestId('position-unrealized-pnl')).toHaveText('已到期，待处理')
    // same mark on both rows of the contract
    await expect(optRow(page, A1, 1).getByTestId('position-mark')).toHaveText('1.25')
    await expect(optRow(page, B3, 2).getByTestId('position-mark')).toHaveText('1.25')

    await expect(id(page, 'unrealized-summary-totals')).toHaveText(
      `期权 市值 ${MINUS}335.00 未实现 ${MINUS}11.95 ｜ 股票 市值 +6,110.00 未实现 +230.00 ｜ 合计 市值 +5,775.00 未实现 +218.05`,
    )
    await expect(id(page, 'unrealized-summary-excluded')).toHaveText('1 个持仓已到期待处理，未计入')
  })

  test('AC5（Q227 补充）乙再转入 IXQZ 50 股：股票表两行（甲 IXQZ、乙 IXQZ），共 1 个标的；首页 股票持仓 1 个标的；切到乙账户 共 1 个标的', async ({ page }) => {
    await scenarioS(page)
    setApiAccount(2)
    await apiTransferInShares('IXQZ', 50, '2026-11-19', '3000.00')
    setApiAccount(1)
    await page.goto('/')
    await switchToAll(page)
    await expect(id(page, 'dashboard-stock-position-count')).toHaveText('1')
    await expect(id(page, 'dashboard-stock-positions-link')).toHaveText('股票持仓 1 个标的')

    await openPositions(page)
    await expect(id(page, 'stock-position-row')).toHaveCount(2)
    expect(
      await page.getByTestId('stock-position-row').evaluateAll(
        (rs, cell) => rs.map((r) => `${r.querySelector(`[data-testid="${cell}"]`)?.textContent?.trim() ?? ''} ${r.getAttribute('data-symbol') ?? ''}`),
        TID.stockAccountCell,
      ),
    ).toEqual([`${A_NAME} IXQZ`, `${B_NAME} IXQZ`])
    await expect(id(page, 'stock-positions-count')).toHaveText('共 1 个标的')
    await expect(stockRow(page, 'IXQZ', 2).getByTestId('stock-position-market-value')).toHaveText('+3,055.00')
    await expect(stockRow(page, 'IXQZ', 2).getByTestId('stock-position-unrealized-pnl')).toHaveText('+55.00')

    await switchTo(page, 2, B_NAME)
    await expect(id(page, 'stock-position-row')).toHaveCount(1)
    await expect(id(page, 'stock-positions-count')).toHaveText('共 1 个标的')
  })

  test('AC6 首页点 7 天内到期 1 个 → 持仓页 ?expiry=7 只有甲 A4；已过期未处理 → 只有乙 B4；冻结合计与未实现合计不变', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await switchToAll(page)
    await id(page, 'dashboard-expiring-link').click()
    await expect(page).toHaveURL(/\/positions\?expiry=7$/)
    await expectAllMode(page)
    await expect.poll(() => optionOrder(page)).toEqual([`甲账户 ${A4}`])
    await expect(id(page, 'positions-frozen-total')).toHaveText('冻结现金合计 14,500.00')
    const totals = `期权 市值 ${MINUS}335.00 未实现 ${MINUS}11.95 ｜ 股票 市值 +6,110.00 未实现 +230.00 ｜ 合计 市值 +5,775.00 未实现 +218.05`
    await expect(id(page, 'unrealized-summary-totals')).toHaveText(totals)

    await id(page, 'positions-expiry-filter-expired').click()
    await expect(page).toHaveURL(/expiry=expired/)
    await expect.poll(() => optionOrder(page)).toEqual([`乙账户 ${B4}`])
    await expect(id(page, 'positions-frozen-total')).toHaveText('冻结现金合计 14,500.00')
    await expect(id(page, 'unrealized-summary-totals')).toHaveText(totals)
  })

  test('AC7 盈亏汇总：QRTV 2 笔 +90.00；2026-11 1 笔 +60.00、2026-10 1 笔 +30.00；合计 +90.00；11 月范围 QRTV 1 笔 +60.00；乙账户 QRTV 1 笔 +30.00', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/pnl-summary')
    await switchToAll(page)
    await expect(id(page, 'pnl-total-count')).toHaveText('2')
    await expect(id(page, 'pnl-total')).toHaveText('+90.00')
    // zero split shows "—" (FEAT-040 display of an empty option / stock part)
    await expect.poll(() => pnlRows(page, 'symbol')).toEqual(['QRTV 2 +90.00 — +90.00'])
    await expect.poll(() => pnlRows(page, 'month')).toEqual(['2026-11 1 +60.00 — +60.00', '2026-10 1 +30.00 — +30.00'])
    for (const card of ['pnl-by-symbol', 'pnl-by-month']) {
      const headers = (await id(page, card).locator('thead th').allTextContents()).map((h) => h.trim())
      expect(headers, `${card}: no 账户 column (Q222)`).not.toContain('账户')
    }

    await id(page, 'pnl-filter-start-date').fill('2026-11-01')
    await id(page, 'pnl-filter-end-date').fill('2026-11-30')
    await id(page, 'pnl-filter-submit').click()
    await expect.poll(() => pnlRows(page, 'symbol')).toEqual(['QRTV 1 +60.00 — +60.00'])
    await expect(id(page, 'pnl-total')).toHaveText('+60.00')

    await id(page, 'pnl-filter-clear').click()
    // let the clear-driven refetch settle (back to the full range) before opening the account switcher
    await expect(id(page, 'pnl-total')).toHaveText('+90.00')
    await switchTo(page, 2, B_NAME)
    await expect.poll(() => pnlRows(page, 'symbol')).toEqual(['QRTV 1 +30.00 — +30.00'])
    await expect(id(page, 'pnl-total')).toHaveText('+30.00')
  })

  test('AC8 临时休市 2026-12-18：首页与持仓页一行合计并按账户分列 + 切换到 甲账户 / 乙账户 去调整；点乙 → 当前乙账户、持仓页定位到 B3', async ({ page }) => {
    await scenarioData() // A1, B3 before the closure exists (Q126)
    await setBackendNow(ny('2026-11-23T09:55'))
    await injectDec18Closure()
    await pull()
    await fixNow(page, ny('2026-11-23T10:05'))

    await page.goto('/positions')
    await switchToAll(page)
    for (const path of ['/', '/positions']) {
      await page.goto(path)
      const alert = page.locator('[data-testid="adhoc-closure-alert"][data-date="2026-12-18"]')
      await expect(alert.getByTestId('adhoc-closure-alert-message')).toHaveText(ALERT_ALL)
      await expect(alert.getByTestId(TID.alertSwitch(1))).toHaveText(`切换到 ${A_NAME} 去调整`)
      await expect(alert.getByTestId(TID.alertSwitch(2))).toHaveText(`切换到 ${B_NAME} 去调整`)
      await expect(alert.getByTestId('adhoc-closure-alert-adjust')).toHaveCount(0)
    }
    // both affected rows carry the 临时休市 tag in ALL
    await expect(optRow(page, A1, 1).getByTestId('position-adhoc-closure-tag')).toBeVisible()
    await expect(optRow(page, B3, 2).getByTestId('position-adhoc-closure-tag')).toBeVisible()

    // from the home page: switch to 乙 → positions page, B3 located
    await page.goto('/')
    await page.locator('[data-testid="adhoc-closure-alert"][data-date="2026-12-18"]').getByTestId(TID.alertSwitch(2)).click()
    await expect(id(page, 'account-current')).toHaveText(B_NAME)
    await expect(page).toHaveURL(/\/positions/)
    await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
    await expect(id(page, 'position-row')).toHaveCount(2)
    await expect(optRow(page, B3)).toHaveAttribute('data-highlighted', 'true')
    await expect(optRow(page, B3)).toBeInViewport()
    // single account again: the FEAT-042 message and its 去调整
    await expect(page.getByTestId('adhoc-closure-alert-message')).toHaveText(
      '2026-12-18 临时休市（Test Closure），你有 1 个持仓在该日到期，期权通常改为前一个交易日到期，请核对并调整到期日',
    )
  })

  test('AC9 只读：持仓页无平仓/作废/指派/行权/调整到期日/批量作废/卖出；资金页、首页无写入口；/trades/new 显示选择账户，点乙账户 → 表单；后端拒绝 ALL 入金且现金不变', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await switchToAll(page)
    await expect(id(page, 'dashboard-new-trade')).toHaveCount(0)

    await openPositions(page)
    await expect(id(page, 'position-row')).toHaveCount(4)
    await expectNoWriteControls(page)
    await expect(page.getByRole('button', { name: /批量作废/ })).toHaveCount(0)
    // market data actions stay available (Q218)
    await expect(optRow(page, A1, 1).getByTestId('position-mark-set')).toBeVisible()

    await page.goto('/cash')
    await expect(id(page, 'cash-transfer-submit')).toHaveCount(0)
    await expect(id(page, 'cash-ledger-edit')).toHaveCount(0)
    await expect(id(page, 'cash-ledger-delete')).toHaveCount(0)

    // deep link to the entry page
    await page.goto('/trades/new')
    await expect(id(page, TID.entryMessage)).toHaveText(ENTRY_TEXT)
    await expect(id(page, TID.entrySwitch(1))).toHaveText(A_NAME)
    await expect(id(page, TID.entrySwitch(2))).toHaveText(B_NAME)
    await expect(id(page, 'open-trade-submit')).toHaveCount(0)
    await id(page, TID.entrySwitch(2)).click()
    await expect(id(page, 'account-current')).toHaveText(B_NAME)
    await expect(page).toHaveURL(/\/trades\/new$/)
    await expect(id(page, 'open-trade-submit')).toBeVisible()
    await expect(id(page, TID.entryChooser)).toHaveCount(0)

    // /stocks/new the same
    await switchToAll(page)
    await page.goto('/stocks/new')
    await expect(id(page, TID.entryMessage)).toHaveText(ENTRY_TEXT)
    await expect(id(page, 'stock-entry-submit')).toHaveCount(0)

    // backend: a deposit without a single account is rejected, cash unchanged
    const r = await fetch(`${BACKEND_URL}/api/cash/transfers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Account-Id': 'ALL' },
      body: JSON.stringify({ type: 'DEPOSIT', amount: '100.00', occurredAtLocal: '2026-11-23T09:30', remark: null }),
    })
    expect(r.status).toBe(400)
    expect(((await r.json()) as { code: string }).code).toBe('ALL_ACCOUNTS_READ_ONLY')
    const summary = (await (await fetch(`${BACKEND_URL}/api/cash/summary`, { headers: { 'X-Account-Id': 'ALL' } })).json()) as {
      totalCash: string
      accounts: { totalCash: string }[]
    }
    expect(summary.totalCash).toBe('70473.05')
    expect(summary.accounts.map((a) => a.totalCash)).toEqual(['50263.05', '20210.00'])
  })

  test('AC10 未支持页面：审计显示占位 + 两个账户按钮且不发请求（FEAT-046 起交易列表、股票交易、已平仓，FEAT-047 起策略、组合 Greeks 不再是占位）；持仓页每行 Greeks 照常', async ({ page }) => {
    await scenarioS(page)
    const allRequests: string[] = []
    page.on('request', (req) => {
      if (req.headers()['x-account-id']?.trim().toUpperCase() === 'ALL') allRequests.push(new URL(req.url()).pathname)
    })
    await page.goto('/')
    await switchToAll(page)
    await page.goto('/audit')
    const ph = id(page, TID.unsupported)
    await expect(ph.getByTestId(TID.unsupportedMessage)).toHaveText(UNSUPPORTED_TEXT)
    await expect(ph.getByTestId(TID.unsupportedSwitch(1))).toHaveText(A_NAME)
    await expect(ph.getByTestId(TID.unsupportedSwitch(2))).toHaveText(B_NAME)
    // FEAT-046 / FEAT-047 (requirement changes): these pages show merged data, no placeholder (details in the
    // feat-046 / feat-047 specs)
    for (const path of ['/trades', '/stocks', '/closed', '/strategies']) {
      await page.goto(path)
      await expectAllMode(page)
      await expect(id(page, TID.unsupported), `${path} no placeholder under ALL`).toHaveCount(0)
    }
    // the placeholder page does not query its (unsupported) endpoint with ALL
    expect(allRequests.filter((x) => x.startsWith('/api/audit-log')), 'no ALL request to /api/audit-log').toEqual([])

    // the switch button on a placeholder switches and shows the page
    await page.goto('/audit')
    await id(page, TID.unsupportedSwitch(2)).click()
    await expect(id(page, 'account-current')).toHaveText(B_NAME)
    await expect(id(page, TID.unsupported)).toHaveCount(0)
    await page.goto('/closed')
    await expect(id(page, 'closed-count')).toHaveText('共 1 笔平仓')

    // positions page with Greeks on: FEAT-047 replaces the gray text by the merged summary
    await switchToAll(page)
    await openPositions(page)
    const toggle = id(page, 'positions-greeks-toggle')
    if ((await toggle.getAttribute('aria-checked')) !== 'true') await toggle.click()
    await expect(toggle).toHaveAttribute('aria-checked', 'true')
    await expect(id(page, 'greeks-summary')).toBeVisible()
    await expect(id(page, TID.greeksUnsupported)).toHaveCount(0)
    await expect(page.getByText(GREEKS_UNSUPPORTED_TEXT)).toHaveCount(0)
    await expect(optRow(page, A1, 1).getByTestId('position-greeks-iv')).toHaveText('35.12%')
    await expect(optRow(page, B3, 2).getByTestId('position-greeks-iv')).toHaveText('35.12%')
    await expect(optRow(page, A4, 1).getByTestId('position-greeks-iv')).toHaveText('28.10%')
  })

  test('边界 只有一个账户：切换器没有全部账户；记住了全部账户但只剩一个账户 → 选 ID 最小的账户', async ({ page }) => {
    setApiAccount(1)
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await page.goto('/')
    await id(page, 'account-switcher').click()
    await expect(page.locator('[data-testid^="account-option-"]')).toHaveCount(1)
    await expect(id(page, TID.optionAll)).toHaveCount(0)
    await page.keyboard.press('Escape')
    // a remembered ALL selection (whatever the frontend stores) falls back to the default account
    await page.evaluate(() => {
      for (const k of Object.keys(localStorage)) if (k.startsWith('option-mgr.')) localStorage.setItem(k, 'ALL')
      localStorage.setItem('option-mgr.accountId', 'ALL')
    })
    await page.reload()
    await expect(id(page, 'account-current')).toHaveText('默认账户')
    await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
  })

  test('边界 全部账户下新增账户：账户页照常可用；资金汇总立即多一行 0.00，合计不变', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/cash')
    await switchToAll(page)
    await expect(id(page, TID.cashAccountRow)).toHaveCount(2)
    await page.goto('/accounts')
    await id(page, 'account-create').click()
    const modal = page.getByTestId('account-form-modal').getByRole('dialog')
    await modal.getByTestId('account-form-name').fill('丙账户')
    await modal.getByTestId('account-form-submit').click()
    await expect(modal).toBeHidden()
    await expectAllMode(page)
    await page.goto('/cash')
    await expect(id(page, TID.cashAccountRow)).toHaveCount(3)
    await expect(id(page, TID.cashAccountRow).nth(2)).toContainText('丙账户')
    await expect(id(page, TID.cashAccountRow).nth(2).getByTestId(TID.cashAccountTotal)).toHaveText('0.00')
    await expect(id(page, TID.cashTotalRow).getByTestId('cash-summary-total')).toHaveText('70,473.05')
  })

  test('边界 全部账户下手动覆盖 mark：两个账户同一合约的行一起变（A1 −21.30、B3 −20.00）', async ({ page }) => {
    await scenarioS(page)
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    await optRow(page, A1, 1).getByTestId('position-mark-set').click()
    const modal = page.getByTestId('manual-quote-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await modal.getByTestId('manual-quote-price').fill('1.40')
    await modal.getByTestId('manual-quote-submit').click()
    await expect(modal.getByRole('dialog')).toHaveCount(0)
    await expect(optRow(page, A1, 1).getByTestId('position-unrealized-pnl')).toHaveText(`${MINUS}21.30`)
    await expect(optRow(page, B3, 2).getByTestId('position-unrealized-pnl')).toHaveText(`${MINUS}20.00`)
    await expect(optRow(page, B3, 2).getByTestId('position-mark')).toHaveText('1.40')
    // nothing was written to either account (manual quotes are not account data)
    await expect(id(page, 'positions-frozen-total')).toHaveText('冻结现金合计 14,500.00')
  })
})
