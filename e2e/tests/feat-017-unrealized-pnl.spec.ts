import { chooseFifoLots } from '../support/closeLots'
import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-017 未实现盈亏与市值 E2E (fake CBOE via the e2e test-support endpoints, fixed backend clock + page.clock).
 *
 * Scenario (requirement; today 2026-11-23 Mon, the 10:00 pull succeeds; fictional tickers):
 *   Account A (default, id 1): P1 STO 1 QRTV 2026-12-18 50P net 118.70 (1.20 / 1.30 → mark 1.25);
 *   P2 BTO 2 QRTV 2026-12-18 55C net 301.30 (1.80 / 1.90 → 1.85); P3 STO 1 IXQZ 2026-12-18 65C net 84.35
 *   (0.80 / 0.90 → 0.85); P4 STO 1 QRTV 2026-12-18 47.5P net 45.00 (never in the chain);
 *   IXQZ 100 shares total cost 5,880.00, price 61.10.
 *   Account B: P5 STO 1 QRTV 2026-12-18 50P net 120.00.
 *
 * Test ids: docs/architecture.md "FEAT-017 testid" (frontend):
 *   option row position-row[data-contract]: position-market-value, position-unrealized-pnl (amount or the backend
 *   label), position-unrealized-pnl-percent (only when computable);
 *   stock row stock-position-row[data-symbol]: stock-position-market-value, stock-position-unrealized-pnl,
 *   stock-position-unrealized-pnl-percent;
 *   summary line: unrealized-summary, unrealized-summary-{option|stock|total}-market-value,
 *   unrealized-summary-{option|stock|total}-unrealized-pnl, unrealized-summary-totals (whole sentence),
 *   unrealized-summary-{option|stock|total} (one group), unrealized-summary-excluded (backend excludedLabel),
 *   unrealized-summary-note, unrealized-summary-loading / -error / -retry; row cells carry data-status.
 * Display (requirement 页面与交互, Q162): every market value (option rows, stock rows, the three summary groups) has an
 * explicit sign (+370.00 / −125.00 with U+2212, +6,110.00, +6,270.00); P&L signed, green / red; percent signed with
 * %; thousands separators; zero is 0.00 without a sign.
 */

const P1 = 'QRTV 2026-12-18 50 P'
const P2 = 'QRTV 2026-12-18 55 C'
const P3 = 'IXQZ 2026-12-18 65 C'
const P4 = 'QRTV 2026-12-18 47.5 P'
const P5 = P1
const P6 = 'QRTV 2026-12-18 60 C'
const EXPIRED = 'QRTV 2026-11-20 45 P'
const NO_QUOTE = '无报价'
const EXPIRED_LABEL = '已到期，待处理'
const ACCOUNT_B = '账户B'
const NOTE = '未实现盈亏已含开仓手续费，未扣平仓手续费；按 mark 计算，仅供参考'

const ny = (local: string): string => nyInstant(local)

// ------------------------------------------------------------------------------------------------ fake sources

type Opt = Record<string, number | string>
type SourceResponse = { httpStatus: number; body: unknown } | { unreachable: true }

const option = (code: string, fields: Record<string, number>): Opt => ({ option: code, ...fields })

function cboe(symbol: string, price: number, timestamp: string, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: { timestamp, data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 50, options } },
  }
}

const qrtvOptions = (): Opt[] => [
  option('QRTV261218P00050000', { bid: 1.2, ask: 1.3, theo: 1.24 }),
  option('QRTV261218C00055000', { bid: 1.8, ask: 1.9, theo: 1.86 }),
]
const qrtvOk = (ts = '2026-11-23 09:45:00', extra: Opt[] = []): SourceResponse =>
  cboe('QRTV', 52.4, ts, [...qrtvOptions(), ...extra])
const ixqzOk = (ts = '2026-11-23 09:45:00'): SourceResponse =>
  cboe('IXQZ', 61.1, ts, [option('IXQZ261218C00065000', { bid: 0.8, ask: 0.9, theo: 0.84 })])

async function setSources(cboeMap: Record<string, SourceResponse>): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/sources`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ finnhubKeyPresent: true, cboe: cboeMap, finnhub: {} }),
  })
  if (r.status !== 204) throw new Error(`PUT sources: ${r.status} ${await r.text()}`)
}

/** Backend clock to the New York time `local`, then the scheduled-timer simulation; must run. */
async function tickAt(local: string): Promise<void> {
  await setBackendNow(ny(local))
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/tick`, { method: 'POST' })
  if (r.status !== 200) throw new Error(`tick: ${r.status} ${await r.text()}`)
  const body = (await r.json()) as { ran: boolean }
  if (!body.ran) throw new Error(`tick at ${local} did not run: ${JSON.stringify(body)}`)
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

/** Requirement scenario (data only). Backend clock left at 2026-11-23 09:50 New York; returns account B's id. */
async function scenario(): Promise<number> {
  await setBackendNow(ny('2026-11-23T09:50'))
  setApiAccount(1)
  await apiTransfer('DEPOSIT', '30000.00', '2026-11-16T10:00')
  await apiTransferInShares('IXQZ', 100, '2026-11-19', '5880.00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '118.70', time: '2026-11-20T10:00' })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '55', type: 'CALL', contracts: 2, netAmount: '301.30', time: '2026-11-20T10:05' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-18', strike: '65', type: 'CALL', netAmount: '84.35', time: '2026-11-20T10:10' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '47.5', type: 'PUT', netAmount: '45.00', time: '2026-11-20T10:15' })
  const b = await apiCreateAccount(ACCOUNT_B)
  setApiAccount(b)
  await apiTransfer('DEPOSIT', '10000.00', '2026-11-16T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-20T10:20' })
  setApiAccount(1)
  return b
}

/** Scenario + the 10:00 pull. */
async function scenarioPulled(): Promise<number> {
  const b = await scenario()
  await setSources({ QRTV: qrtvOk(), IXQZ: ixqzOk() })
  await tickAt('2026-11-23T10:00')
  return b
}

async function colorOf(l: Locator): Promise<'green' | 'red' | 'other'> {
  const colors = await l.evaluate((el) =>
    [el, ...Array.from(el.querySelectorAll('*'))].map((e) => getComputedStyle(e).color),
  )
  for (const c of colors) {
    const [r, g] = (c.match(/\d+/g) ?? []).map(Number)
    if (r === undefined || g === undefined) continue
    if (g > r + 40) return 'green'
    if (r > g + 40) return 'red'
  }
  return 'other'
}

// ------------------------------------------------------------------------------------------------ page object

type Group = 'option' | 'stock' | 'total'

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  /** Positions page; waits until positions, the market-data status and the unrealized summary have loaded. */
  async open(search = ''): Promise<void> {
    const summary = this.page.waitForResponse((r) => r.url().includes('/api/positions/unrealized-summary'))
    await this.page.goto(`/positions${search}`)
    await expect(this.id('positions-as-of')).toBeVisible()
    await summary
    await expect(this.id('unrealized-summary')).toBeVisible()
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  stockRow(symbol: string): Locator {
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
  }

  /** 市值 / 未实现盈亏 / 百分比 of an option row whose values are computable. */
  async expectOption(contract: string, mv: string, pnl: string, pct: string): Promise<void> {
    const r = this.row(contract)
    await expect(r.getByTestId('position-market-value')).toHaveText(mv)
    await expect(r.getByTestId('position-unrealized-pnl')).toHaveText(pnl)
    await expect(r.getByTestId('position-unrealized-pnl-percent')).toHaveText(pct)
  }

  /** Both columns show the backend label (无报价 / 已到期，待处理), no percent. */
  async expectOptionLabel(contract: string, label: string): Promise<void> {
    const r = this.row(contract)
    await expect(r.getByTestId('position-market-value')).toHaveText(label)
    await expect(r.getByTestId('position-unrealized-pnl')).toHaveText(label)
    await expect(r.getByTestId('position-unrealized-pnl-percent')).toHaveCount(0)
  }

  async expectStock(symbol: string, mv: string, pnl: string, pct: string): Promise<void> {
    const r = this.stockRow(symbol)
    await expect(r.getByTestId('stock-position-market-value')).toHaveText(mv)
    await expect(r.getByTestId('stock-position-unrealized-pnl')).toHaveText(pnl)
    await expect(r.getByTestId('stock-position-unrealized-pnl-percent')).toHaveText(pct)
  }

  async expectSummary(g: Group, mv: string, pnl: string): Promise<void> {
    await expect(this.id(`unrealized-summary-${g}-market-value`)).toHaveText(mv)
    await expect(this.id(`unrealized-summary-${g}-unrealized-pnl`)).toHaveText(pnl)
  }

  get excluded(): Locator {
    return this.id('unrealized-summary-excluded')
  }

  /** Opens the override modal from `edit`, enters the price and saves; waits for the modal to close. */
  async override(edit: Locator, price: string): Promise<void> {
    await edit.click()
    const modal = this.id('manual-quote-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await modal.getByTestId('manual-quote-price').fill(price)
    await modal.getByTestId('manual-quote-submit').click()
    await expect(modal.getByRole('dialog')).toHaveCount(0)
  }
}

test.afterEach(() => {
  setApiAccount(1)
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-017 未实现盈亏与市值', () => {
  test('AC1 / AC2 / AC3 账户 A：期权行、股票行、无报价、顶部合计与未计入提示、表头说明', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    // AC1
    await ui.expectOption(P1, `${MINUS}125.00`, `${MINUS}6.30`, `${MINUS}5.31%`)
    await ui.expectOption(P2, '+370.00', '+68.70', '+22.80%')
    await ui.expectOption(P3, `${MINUS}85.00`, `${MINUS}0.65`, `${MINUS}0.77%`)
    expect(await colorOf(ui.row(P2).getByTestId('position-unrealized-pnl'))).toBe('green')
    expect(await colorOf(ui.row(P1).getByTestId('position-unrealized-pnl'))).toBe('red')
    // the 市值 / 未实现盈亏 columns come after Mark
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('1.25')

    // AC2
    await ui.expectStock('IXQZ', '+6,110.00', '+230.00', '+3.91%')
    expect(await colorOf(ui.stockRow('IXQZ').getByTestId('stock-position-unrealized-pnl'))).toBe('green')

    // AC3
    await ui.expectOptionLabel(P4, NO_QUOTE)
    await ui.expectSummary('option', '+160.00', '+61.75')
    await ui.expectSummary('stock', '+6,110.00', '+230.00')
    await ui.expectSummary('total', '+6,270.00', '+291.75')
    await expect(ui.id('unrealized-summary-totals')).toHaveText(
      '期权 市值 +160.00 未实现 +61.75 ｜ 股票 市值 +6,110.00 未实现 +230.00 ｜ 合计 市值 +6,270.00 未实现 +291.75',
    )
    await expect(ui.id('unrealized-summary-option')).toHaveText('期权 市值 +160.00 未实现 +61.75')
    expect(await colorOf(ui.id('unrealized-summary-total-unrealized-pnl'))).toBe('green')
    await expect(ui.row(P1).getByTestId('position-market-value')).toHaveAttribute('data-status', 'OK')
    await expect(ui.row(P4).getByTestId('position-market-value')).toHaveAttribute('data-status', 'NO_QUOTE')
    await expect(ui.row(P4).getByTestId('position-unrealized-pnl')).toHaveAttribute('data-status', 'NO_QUOTE')
    await expect(ui.excluded).toHaveText('1 个持仓无报价，未计入')
    await expect(ui.id('unrealized-summary-note')).toHaveText(NOTE)
  })

  test('AC4 切换到账户 B：P5 −125.00 / −5.00（−4.17%）；合计 期权 −125.00 / −5.00，股票 0.00 / 0.00，合计 −125.00 / −5.00，无未计入', async ({ page }) => {
    const b = await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectSummary('total', '+6,270.00', '+291.75')

    const summary = page.waitForResponse((r) => r.url().includes('/api/positions/unrealized-summary'))
    await ui.switchAccount(b, ACCOUNT_B)
    await summary
    await ui.expectOption(P5, `${MINUS}125.00`, `${MINUS}5.00`, `${MINUS}4.17%`)
    await ui.expectSummary('option', `${MINUS}125.00`, `${MINUS}5.00`)
    await ui.expectSummary('stock', '0.00', '0.00')
    await ui.expectSummary('total', `${MINUS}125.00`, `${MINUS}5.00`)
    await expect(ui.excluded).toHaveCount(0)
  })

  test('AC5 页面上手动设 P1 Mark 1.40 → P1 −140.00 / −21.30，期权合计 +46.75；B 的 P5 −20.00；P4 设 0.30 → −30.00 / +15.00，未计入提示消失', async ({ page }) => {
    const b = await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await ui.override(ui.row(P1).getByTestId('position-mark-set'), '1.40')
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('1.40')
    await ui.expectOption(P1, `${MINUS}140.00`, `${MINUS}21.30`, `${MINUS}17.94%`)
    await ui.expectSummary('option', '+145.00', '+46.75')
    await expect(ui.excluded).toHaveText('1 个持仓无报价，未计入')

    await ui.override(ui.row(P4).getByTestId('position-mark-set'), '0.30')
    await ui.expectOption(P4, `${MINUS}30.00`, '+15.00', '+33.33%')
    await ui.expectSummary('option', '+115.00', '+61.75')
    await ui.expectSummary('total', '+6,225.00', '+291.75')
    await expect(ui.excluded).toHaveCount(0)

    await ui.switchAccount(b, ACCOUNT_B)
    await ui.expectOption(P5, `${MINUS}140.00`, `${MINUS}20.00`, `${MINUS}16.67%`)
    await ui.expectSummary('total', `${MINUS}140.00`, `${MINUS}20.00`)

    // clearing the manual mark brings back the automatic 1.25
    await ui.row(P5).getByTestId('position-mark-clear').click()
    await ui.expectOption(P5, `${MINUS}125.00`, `${MINUS}5.00`, `${MINUS}4.17%`)
    await ui.expectSummary('total', `${MINUS}125.00`, `${MINUS}5.00`)
  })

  test('AC6 P6 mark 0.125 → +12.50 / −3.15（−20.13%），合计 172.50 / +58.60、6,282.50 / +288.60；10:15 theo 0.03 → +3.00 / −12.65（−80.83%），163.00 / +49.10、6,273.00 / +279.10', async ({ page }) => {
    await scenario()
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '60', type: 'CALL', netAmount: '15.65', time: '2026-11-20T10:25' })
    await setSources({ QRTV: qrtvOk('2026-11-23 09:45:00', [option('QRTV261218C00060000', { bid: 0.1, ask: 0.15, theo: 0.12 })]), IXQZ: ixqzOk() })
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.row(P6).getByTestId('position-mark')).toHaveText('0.125')
    await ui.expectOption(P6, '+12.50', `${MINUS}3.15`, `${MINUS}20.13%`)
    await ui.expectSummary('option', '+172.50', '+58.60')
    await ui.expectSummary('total', '+6,282.50', '+288.60')

    await setSources({
      QRTV: qrtvOk('2026-11-23 10:00:00', [option('QRTV261218C00060000', { bid: 0, ask: 0.05, theo: 0.03 })]),
      IXQZ: ixqzOk('2026-11-23 10:00:00'),
    })
    await tickAt('2026-11-23T10:15')
    await fixNow(page, ny('2026-11-23T10:16'))
    await ui.open()
    await expect(ui.row(P6).getByTestId('position-mark')).toHaveText('0.03')
    await ui.expectOption(P6, '+3.00', `${MINUS}12.65`, `${MINUS}80.83%`)
    await ui.expectSummary('option', '+163.00', '+49.10')
    await ui.expectSummary('total', '+6,273.00', '+279.10')
  })

  test('AC7 10:15 起 QRTV 拉取失败 → 行上 行情未更新（上次：11-23 10:00），P1 / P2 仍 −6.30 / +68.70，计入合计', async ({ page }) => {
    await scenarioPulled()
    await setSources({ QRTV: { httpStatus: 500, body: null }, IXQZ: ixqzOk('2026-11-23 10:00:00') })
    await tickAt('2026-11-23T10:15')
    await fixNow(page, ny('2026-11-23T10:20'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveText('行情未更新（上次：11-23 10:00）')
    await ui.expectOption(P1, `${MINUS}125.00`, `${MINUS}6.30`, `${MINUS}5.31%`)
    await ui.expectOption(P2, '+370.00', '+68.70', '+22.80%')
    await ui.expectSummary('option', '+160.00', '+61.75')
    await ui.expectSummary('total', '+6,270.00', '+291.75')
    await expect(ui.excluded).toHaveText('1 个持仓无报价，未计入')
  })

  test('AC8 已过期未处理（最后 mark 0.05）→ 已到期，待处理，合计不变，提示两种原因；页面上 到期作废 后行消失、提示恢复', async ({ page }) => {
    // expired contract quoted before its expiration
    await setBackendNow(ny('2026-11-17T09:50'))
    await apiTransfer('DEPOSIT', '5000.00', '2026-11-16T09:00')
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-20', strike: '45', type: 'PUT', netAmount: '60.00', time: '2026-11-17T09:40' })
    await setSources({ QRTV: cboe('QRTV', 52.4, '2026-11-20 09:45:00', [option('QRTV261120P00045000', { bid: 0.04, ask: 0.06 })]) })
    await tickAt('2026-11-20T10:00')
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await expect(ui.row(EXPIRED).getByTestId('position-mark')).toHaveText('0.05')
    await ui.expectOptionLabel(EXPIRED, EXPIRED_LABEL)
    await expect(ui.row(EXPIRED).getByTestId('position-market-value')).toHaveAttribute('data-status', 'EXPIRED_PENDING')
    await ui.expectSummary('option', '+160.00', '+61.75')
    await ui.expectSummary('total', '+6,270.00', '+291.75')
    await expect(ui.excluded).toHaveText('1 个持仓无报价、1 个持仓已到期待处理，未计入')

    await ui.row(EXPIRED).getByTestId('position-expire').click()
    const d = ui.dialog('expire-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('expire-submit').click()
    await expect(d.getByTestId('expire-result')).toBeVisible()
    await d.getByTestId('expire-done').click()
    await expect(ui.row(EXPIRED)).toHaveCount(0)
    await expect(ui.excluded).toHaveText('1 个持仓无报价，未计入')
    await ui.expectSummary('total', '+6,270.00', '+291.75')
  })

  test('AC9 页面上 STC 2 张 P2 净额 368.70 → P2 消失，期权合计 −210.00 / −6.95，合计 5,900.00 / +223.05', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await ui.row(P2).getByTestId('position-close').click()
    const modal = ui.id('close-trade-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await chooseFifoLots(modal, 2)
    await modal.getByTestId('close-trade-net-amount').fill('368.70')
    await modal.getByTestId('close-trade-time').fill('2026-11-23T10:03')
    await modal.getByTestId('close-trade-submit').click()
    await expect(modal.getByTestId('close-trade-confirmation')).toBeVisible()
    await modal.getByTestId('close-trade-done').click()

    await expect(ui.row(P2)).toHaveCount(0)
    await ui.expectSummary('option', `${MINUS}210.00`, `${MINUS}6.95`)
    await ui.expectSummary('total', '+5,900.00', '+223.05')
  })

  test('AC10 没有任何持仓的账户：合计行全部 0.00，无未计入提示', async ({ page }) => {
    await scenarioPulled()
    const empty = await apiCreateAccount('空账户')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.switchAccount(empty, '空账户')
    for (const g of ['option', 'stock', 'total'] as const) await ui.expectSummary(g, '0.00', '0.00')
    await expect(ui.excluded).toHaveCount(0)
  })

  test('Q158 选择到期过滤（已过期未处理 / 今天到期 / 7 天内）时合计仍为全账户', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectSummary('total', '+6,270.00', '+291.75')
    for (const v of ['expired', 'today', '7', '30']) {
      await ui.id(`positions-expiry-filter-${v}`).click()
      await expect(ui.id('positions-expiry-filter')).toHaveAttribute('data-value', v)
      await ui.expectSummary('option', '+160.00', '+61.75')
      await ui.expectSummary('total', '+6,270.00', '+291.75')
      await expect(ui.excluded).toHaveText('1 个持仓无报价，未计入')
    }
  })

  test('边界：股票手动价 61.105 × 33 股 → 市值 2,016.47（Q154 HALF_UP）；无报价股票显示 无报价 不计入', async ({ page }) => {
    await setBackendNow(ny('2026-11-23T09:50'))
    await apiTransferInShares('ZQMB', 33, '2026-11-19', '2000.00')
    await apiTransferInShares('ZQMC', 7, '2026-11-19', '100.00')
    await setSources({})
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.stockRow('ZQMB').getByTestId('stock-position-market-value')).toHaveText(NO_QUOTE)
    await expect(ui.stockRow('ZQMB').getByTestId('stock-position-unrealized-pnl')).toHaveText(NO_QUOTE)
    await expect(ui.excluded).toHaveText('2 个持仓无报价，未计入')
    await ui.expectSummary('stock', '0.00', '0.00')

    await ui.override(ui.stockRow('ZQMB').getByTestId('stock-position-price-set'), '61.105')
    await ui.expectStock('ZQMB', '+2,016.47', '+16.47', '+0.82%')
    await ui.override(ui.stockRow('ZQMC').getByTestId('stock-position-price-set'), '12.3456')
    await ui.expectStock('ZQMC', '+86.42', `${MINUS}13.58`, `${MINUS}13.58%`)
    expect(await colorOf(ui.stockRow('ZQMC').getByTestId('stock-position-unrealized-pnl'))).toBe('red')
    await ui.expectSummary('stock', '+2,102.89', '+2.89')
    await expect(ui.excluded).toHaveCount(0)
  })

  test('后端合计接口报错时：合计行显示错误 + 重新加载合计，持仓行照常显示；恢复后点重试显示合计', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    await page.route('**/api/positions/unrealized-summary', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/positions/unrealized-summary', timestamp: new Date().toISOString(), details: [] }),
      }),
    )
    await page.goto('/positions')
    await expect(page.getByTestId('positions-as-of')).toBeVisible()
    const ui = new Ui(page)
    await expect(ui.id('unrealized-summary-error')).toBeVisible({ timeout: 30_000 })
    await expect(ui.id('unrealized-summary-error')).toContainText('INTERNAL_ERROR')
    await expect(ui.id('unrealized-summary-totals')).toHaveCount(0)
    await expect(ui.id('unrealized-summary-total-market-value')).toHaveCount(0)
    await ui.expectOption(P1, `${MINUS}125.00`, `${MINUS}6.30`, `${MINUS}5.31%`)

    await page.unroute('**/api/positions/unrealized-summary')
    await ui.id('unrealized-summary-retry').click()
    await expect(ui.id('unrealized-summary-error')).toHaveCount(0)
    await ui.expectSummary('total', '+6,270.00', '+291.75')
  })
})
