import { chooseFifoLots } from '../support/closeLots'
import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-021 按标的分组查看 E2E (fixed backend clock + page.clock; fictional tickers QRTV / IXQZ / ZWLK only).
 *
 * Backend N/A: the 策略 page groups GET /api/option-positions + GET /api/stock-positions (docs/api/FEAT-021.md).
 * Scenario (requirement): today 2026-11-23 10:00 New York, multiplier 100.
 *   Account A (default, id 1): deposit 50,000.00; S1 转入 QRTV 100 股 4,800.00, S2 转入 ZWLK 50 股 1,500.00
 *   (2026-10-01); T1 STO QRTV 12-18 55C 85.35; T2 STO QRTV 12-18 45P 120.35; T3 BTO QRTV 12-18 40P 40.65;
 *   T4 STO QRTV 12-18 45P 110.00; T5 STO QRTV 2027-01-15 45P 150.35; T6 STO IXQZ 11-20 58P 90.00;
 *   T7 STO IXQZ 12-18 60P 200.00.
 *   Account B: deposit 10,000.00; T8 STO QRTV 12-18 45P 115.00.
 *
 * Writes in AC 4–7 go through the app (positions page / stock entry / trade list) and the 策略 page is reached again
 * through the navigation, never page.goto / reload ("不刷新页面"); a window flag proves the document was not reloaded.
 *
 * 策略 page test ids: frontend UnderlyingGroupsPage.tsx (docs/architecture.md "按标的（FEAT-021）"), route /strategies.
 * Reused ids are from FEAT-003 / 004 / 011 / 012 / 013 / 018 / 033 / 041 specs.
 */
const S = {
  // navigation + page (Q184)
  nav: 'nav-strategies',
  page: 'underlying-page',
  title: 'underlying-title',
  note: 'underlying-hint',
  empty: 'underlying-empty',
  error: 'underlying-error',
  retry: 'underlying-retry',
  // one card per symbol: [data-testid=underlying-card][data-symbol=QRTV]
  card: 'underlying-card',
  cardSymbol: 'underlying-card-symbol', // the symbol in the card title; a link to the stock page (FEAT-011 filter)
  cardSummary: 'underlying-card-summary', // `期权 N 个持仓 · 股票 M 股`
  // 期权 small table; rows [data-testid=underlying-option-row][data-contract="QRTV 2026-12-18 45 P"]
  optionTable: 'underlying-options',
  optionRow: 'underlying-option-row',
  optionCells: {
    contract: 'underlying-option-cell-contract', // link to the trade list filtered by contract (FEAT-003)
    direction: 'underlying-option-cell-direction',
    contracts: 'underlying-option-cell-contracts',
    openingNet: 'underlying-option-cell-opening-net',
    expiration: 'underlying-option-cell-expiration',
    dte: 'underlying-option-cell-dte',
  },
  // 股票 one-row table
  stockTable: 'underlying-stock',
  stockRow: 'underlying-stock-row',
  stockCells: {
    shares: 'underlying-stock-cell-shares',
    covered: 'underlying-stock-cell-covered-shares',
    available: 'underlying-stock-cell-available-shares',
    totalCost: 'underlying-stock-cell-total-cost',
    averageCost: 'underlying-stock-cell-average-cost',
  },
} as const

/** Route of the 策略 page (only used to check the URL after clicking the nav entry). */
const STRATEGY_ROUTE = /\/strategies$/

const TEXT = {
  nav: '策略',
  title: '按标的',
  // FEAT-023 replaced the FEAT-021 hint (page wording of docs/requirements/FEAT-023-underlying-pnl.md)
  note: '按标的列出当前账户正在持有的期权和股票，只读；操作请到持仓页。已实现盈亏为该标的全部历史（含手续费）；市值和未实现盈亏按 mark / 现价计算，未扣平仓手续费，仅供参考',
  empty: '暂无持仓',
  // FEAT-023 (Q198) added 市值 / 未实现盈亏 after 开仓净额 / 每股平均成本
  optionHeaders: ['合约', '方向', '张数', '开仓净额', '市值', '未实现盈亏', '到期日', 'DTE'],
  stockHeaders: ['持股', '被覆盖', '可用', '总成本', '每股平均成本', '市值', '未实现盈亏'],
  forbiddenColumns: ['Delta', 'Gamma', 'Theta', 'Vega', 'IV'],
  forbiddenButtons: ['平仓', '作废', '被指派', '行权', '卖出', '买入', '删除', '修改'],
}

const ACCOUNT_A = '默认账户'
const ACCOUNT_B = '账户B'
const ACCOUNT_C = '账户C'

const IXQZ_58P = 'IXQZ 2026-11-20 58 P'
const IXQZ_60P = 'IXQZ 2026-12-18 60 P'
const QRTV_40P = 'QRTV 2026-12-18 40 P'
const QRTV_45P = 'QRTV 2026-12-18 45 P'
const QRTV_55C = 'QRTV 2026-12-18 55 C'
const QRTV_45P_JAN = 'QRTV 2027-01-15 45 P'

const ny = (local: string): string => nyInstant(local)

// ------------------------------------------------------------------------------------------------ expected rows

/** contract, 方向, 张数, 开仓净额, 到期日, DTE — as the page shows them. */
type OptRow = [string, string, string, string, string, string]
/** 持股, 被覆盖, 可用, 总成本, 每股平均成本. */
type StockRow = [string, string, string, string, string]

const R_IXQZ_58P: OptRow = [IXQZ_58P, '空头', '1', '+90.00', '2026-11-20', '已过期 3 天']
const R_IXQZ_60P: OptRow = [IXQZ_60P, '空头', '1', '+200.00', '2026-12-18', '25']
const R_QRTV_40P: OptRow = [QRTV_40P, '多头', '1', `${MINUS}40.65`, '2026-12-18', '25']
const R_QRTV_45P: OptRow = [QRTV_45P, '空头', '2', '+230.35', '2026-12-18', '25']
const R_QRTV_55C: OptRow = [QRTV_55C, '空头', '1', '+85.35', '2026-12-18', '25']
const R_QRTV_45P_JAN: OptRow = [QRTV_45P_JAN, '空头', '1', '+150.35', '2027-01-15', '53']

// Q88: average cost per share with 4 decimals, same as the stock / positions pages
const ST_QRTV: StockRow = ['100', '100', '0', '4,800.00', '48.0000']
const ST_ZWLK: StockRow = ['50', '0', '50', '1,500.00', '30.0000']

// ------------------------------------------------------------------------------------------------ seeding

async function apiCreateAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiSellShares(symbol: string, shares: number, amount: string, time: string): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'SELL', symbol, shares, amount, tradedAtLocal: time, note: null }),
  })
  if (r.status !== 201) throw new Error(`SELL ${symbol}: ${r.status} ${await r.text()}`)
}

/** Requirement scenario (data only); backend clock left at 2026-11-23 10:00 New York. Returns account B's id. */
async function scenario(): Promise<number> {
  await setBackendNow(ny('2026-11-23T10:00'))
  setApiAccount(1)
  await apiTransfer('DEPOSIT', '50000.00', '2026-10-01T09:00')
  await apiTransferInShares('QRTV', 100, '2026-10-01', '4800.00')
  await apiTransferInShares('ZWLK', 50, '2026-10-01', '1500.00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '55', type: 'CALL', netAmount: '85.35', time: '2026-11-02T10:00' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '120.35', time: '2026-11-03T10:00' })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '40', type: 'PUT', netAmount: '40.65', time: '2026-11-03T10:05' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '110.00', time: '2026-11-05T10:00' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2027-01-15', strike: '45', type: 'PUT', netAmount: '150.35', time: '2026-11-05T10:10' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-11-20', strike: '58', type: 'PUT', netAmount: '90.00', time: '2026-11-02T10:30' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-18', strike: '60', type: 'PUT', netAmount: '200.00', time: '2026-11-05T10:30' })
  const b = await apiCreateAccount(ACCOUNT_B)
  setApiAccount(b)
  await apiTransfer('DEPOSIT', '10000.00', '2026-10-01T09:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '115.00', time: '2026-11-05T11:00' })
  setApiAccount(1)
  return b
}

// ------------------------------------------------------------------------------------------------ page object

async function colorOf(l: Locator): Promise<'green' | 'red' | 'other'> {
  const colors = await l.evaluate((el) => [el, ...Array.from(el.querySelectorAll('*'))].map((e) => getComputedStyle(e).color))
  for (const c of colors) {
    const [r, g] = (c.match(/\d+/g) ?? []).map(Number)
    if (r === undefined || g === undefined) continue
    if (g > r + 40) return 'green'
    if (r > g + 40) return 'red'
  }
  return 'other'
}

const norm = (t: string | null | undefined): string => (t ?? '').replace(/\s+/g, ' ').trim()

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  // ---- 策略 page

  get strategyPage(): Locator {
    return this.id(S.page)
  }

  get cards(): Locator {
    return this.page.getByTestId(S.card)
  }

  card(symbol: string): Locator {
    return this.page.locator(`[data-testid="${S.card}"][data-symbol="${symbol}"]`)
  }

  optionRow(symbol: string, contract: string): Locator {
    return this.card(symbol).locator(`[data-testid="${S.optionRow}"][data-contract="${contract}"]`)
  }

  /** First load: home page, then the 策略 nav entry (so the browser document is created once per test). */
  async open(): Promise<void> {
    await this.page.goto('/')
    await this.gotoStrategyByNav()
  }

  /** Client-side navigation to the 策略 page (no reload). */
  async gotoStrategyByNav(): Promise<void> {
    await this.id(S.nav).click()
    await expect(this.page).toHaveURL(STRATEGY_ROUTE)
    await expect(this.strategyPage).toBeVisible()
  }

  async gotoPositionsByNav(): Promise<void> {
    await this.id('nav-positions').click()
    await expect(this.id('positions-as-of')).toBeVisible()
  }

  async cardSymbols(): Promise<string[]> {
    return this.cards.evaluateAll((cs) => cs.map((c) => c.getAttribute('data-symbol') ?? ''))
  }

  async expectCards(expected: [symbol: string, summary: string][]): Promise<void> {
    await expect.poll(() => this.cardSymbols()).toEqual(expected.map(([s]) => s))
    for (const [symbol, summary] of expected) {
      const c = this.card(symbol)
      await expect(c.getByTestId(S.cardSymbol), `${symbol} title`).toHaveText(symbol)
      await expect(c.getByTestId(S.cardSummary), `${symbol} summary`).toHaveText(summary)
    }
  }

  async optionRows(symbol: string): Promise<string[][]> {
    const ids = Object.values(S.optionCells)
    return this.card(symbol)
      .getByTestId(S.optionRow)
      .evaluateAll(
        (rs, cellIds) =>
          rs.map((r) =>
            cellIds.map((x) => (r.querySelector(`[data-testid="${x}"]`)?.textContent ?? '').replace(/\s+/g, ' ').trim()),
          ),
        ids,
      )
  }

  async stockRows(symbol: string): Promise<string[][]> {
    const ids = Object.values(S.stockCells)
    return this.card(symbol)
      .getByTestId(S.stockRow)
      .evaluateAll(
        (rs, cellIds) =>
          rs.map((r) =>
            cellIds.map((x) => (r.querySelector(`[data-testid="${x}"]`)?.textContent ?? '').replace(/\s+/g, ' ').trim()),
          ),
        ids,
      )
  }

  async expectOptions(symbol: string, rows: OptRow[]): Promise<void> {
    if (rows.length === 0) {
      await expect(this.card(symbol).getByTestId(S.optionTable), `${symbol} has no 期权 table`).toHaveCount(0)
      await expect(this.card(symbol).getByTestId(S.optionRow)).toHaveCount(0)
      return
    }
    await expect(this.card(symbol).getByTestId(S.optionTable)).toBeVisible()
    await expect.poll(() => this.optionRows(symbol), { message: `${symbol} option rows` }).toEqual(rows)
    // the row key used by the links / tests is the contract label
    const keys = await this.card(symbol).getByTestId(S.optionRow).evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract')))
    expect(keys).toEqual(rows.map((r) => r[0]))
  }

  async expectStock(symbol: string, row: StockRow | null): Promise<void> {
    if (row === null) {
      await expect(this.card(symbol).getByTestId(S.stockTable), `${symbol} has no 股票 table`).toHaveCount(0)
      await expect(this.card(symbol).getByTestId(S.stockRow)).toHaveCount(0)
      return
    }
    await expect(this.card(symbol).getByTestId(S.stockTable)).toBeVisible()
    await expect.poll(() => this.stockRows(symbol), { message: `${symbol} stock row` }).toEqual([row])
  }

  /** Header texts of a card's table (antd thead). */
  async headers(table: Locator): Promise<string[]> {
    return (await table.locator('thead th').allInnerTexts()).map((t) => norm(t)).filter((t) => t !== '')
  }

  /** Account A as in AC 1–3. */
  async expectScenarioA(): Promise<void> {
    await this.expectCards([
      ['IXQZ', '期权 2 个持仓'],
      ['QRTV', '期权 4 个持仓 · 股票 100 股'],
      ['ZWLK', '股票 50 股'],
    ])
    await this.expectOptions('IXQZ', [R_IXQZ_58P, R_IXQZ_60P])
    await this.expectStock('IXQZ', null)
    await this.expectOptions('QRTV', [R_QRTV_40P, R_QRTV_45P, R_QRTV_55C, R_QRTV_45P_JAN])
    await this.expectStock('QRTV', ST_QRTV)
    await this.expectOptions('ZWLK', [])
    await this.expectStock('ZWLK', ST_ZWLK)
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
  }

  // ---- "no reload" proof

  async markNoReload(): Promise<void> {
    await this.page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })
  }

  async expectNoReload(): Promise<void> {
    expect(await this.page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
  }

  // ---- writes through the app (positions page, stock entry, trade list)

  posRow(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  stockPosRow(symbol: string): Locator {
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
  }

  /** FEAT-004 close dialog from the positions page. */
  async closeOnPositions(contract: string, contracts: number, net: string, time: string): Promise<void> {
    await this.posRow(contract).getByTestId('position-close').click()
    const modal = this.id('close-trade-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await chooseFifoLots(modal, contracts)
    await modal.getByTestId('close-trade-net-amount').fill(net)
    await modal.getByTestId('close-trade-time').fill(time)
    await modal.getByTestId('close-trade-submit').click()
    await expect(modal.getByTestId('close-trade-confirmation')).toBeVisible()
    await modal.getByTestId('close-trade-done').click()
    await expect(modal.getByRole('dialog')).toBeHidden()
  }

  /** FEAT-012 作废 from the positions page. */
  async expireOnPositions(contract: string): Promise<void> {
    await this.posRow(contract).getByTestId('position-expire').click()
    const d = this.dialog('expire-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('expire-submit').click()
    await expect(d.getByTestId('expire-result')).toBeVisible()
    await d.getByTestId('expire-done').click()
    await expect(this.dialog('expire-modal')).toBeHidden()
  }

  /** FEAT-013 / 041 被指派 from the positions page (1 contract, given assignment date, fee 0). */
  async assignOnPositions(contract: string, date: string): Promise<void> {
    await this.posRow(contract).getByTestId('position-assign').click()
    const d = this.dialog('assign-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('assign-date').fill(date)
    await d.getByTestId('assign-submit').click()
    await expect(d.getByTestId('assign-result')).toBeVisible()
    await d.getByTestId('assign-done').click()
    await expect(this.dialog('assign-modal')).toBeHidden()
  }

  /** FEAT-011 卖出 from the stock row of the positions page (prefilled SELL + symbol). */
  async sellOnPositions(symbol: string, shares: string, amount: string, time: string): Promise<void> {
    await this.stockPosRow(symbol).getByTestId('stock-position-sell').click()
    await expect(this.page).toHaveURL(/\/stocks\/new\?.*type=SELL/)
    await expect(this.id('stock-entry-type-sell')).toBeChecked()
    await expect(this.id('stock-entry-symbol')).toHaveValue(symbol)
    await this.id('stock-entry-shares').fill(shares)
    await this.id('stock-entry-amount').fill(amount)
    await this.id('stock-entry-time').fill(time)
    await this.id('stock-entry-submit').click()
    await expect(this.id('stock-entry-confirmation')).toBeVisible()
  }

  /** FEAT-005 / 012 / 013: delete the only trade-list row whose action is `action` (到期作废 / 被指派). */
  async deleteTradeByAction(action: string): Promise<void> {
    await this.id('nav-trades').click()
    const row = this.page.locator(`[data-testid="trade-row"]:has([data-testid="trade-cell-action"]:text-is("${action}"))`)
    await expect(row).toHaveCount(1)
    await row.getByTestId('trade-delete').click()
    const d = this.dialog('trade-delete-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('trade-delete-confirm').click()
    await expect(this.dialog('trade-delete-modal')).toBeHidden()
    await expect(row).toHaveCount(0)
  }
}

test.afterEach(() => {
  setApiAccount(1)
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-021 按标的分组查看', () => {
  test('AC1 账户 A 策略页：导航 策略、标题 按标的 + 灰字说明；依次 3 张卡片 IXQZ 期权 2 个持仓 / QRTV 期权 4 个持仓 · 股票 100 股 / ZWLK 股票 50 股', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await page.goto('/')
    await expect(ui.id(S.nav)).toHaveText(TEXT.nav)
    await ui.gotoStrategyByNav()

    await expect(ui.id(S.title)).toHaveText(TEXT.title)
    await expect(ui.id(S.note)).toHaveText(TEXT.note)
    await ui.expectCards([
      ['IXQZ', '期权 2 个持仓'],
      ['QRTV', '期权 4 个持仓 · 股票 100 股'],
      ['ZWLK', '股票 50 股'],
    ])
    await expect(ui.id(S.empty)).toHaveCount(0)
  })

  test('AC2 QRTV 卡片：期权 40P 多头 1 −40.65 / 45P 空头 2 +230.35（两批合并）/ 55C 空头 1 +85.35 / 2027-01-15 45P +150.35 DTE 53；股票 100 / 100 / 0 / 4,800.00 / 48.0000', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await ui.open()

    await ui.expectOptions('QRTV', [R_QRTV_40P, R_QRTV_45P, R_QRTV_55C, R_QRTV_45P_JAN])
    await ui.expectStock('QRTV', ST_QRTV)
    const q = ui.card('QRTV')
    expect(await ui.headers(q.getByTestId(S.optionTable))).toEqual(TEXT.optionHeaders)
    expect(await ui.headers(q.getByTestId(S.stockTable))).toEqual(TEXT.stockHeaders)
    // the long put's negative opening net uses U+2212, not ASCII '-'
    await expect(ui.optionRow('QRTV', QRTV_40P).getByTestId(S.optionCells.openingNet)).not.toContainText('-')
  })

  test('AC3 IXQZ 卡片：58P 在上、DTE 红色 已过期 3 天；60P +200.00 DTE 25；没有股票行。ZWLK 卡片没有期权表，股票 50 / 0 / 50 / 1,500.00 / 30.0000', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await ui.open()

    await ui.expectOptions('IXQZ', [R_IXQZ_58P, R_IXQZ_60P])
    const dte58 = ui.optionRow('IXQZ', IXQZ_58P).getByTestId(S.optionCells.dte)
    await expect(dte58).toHaveText('已过期 3 天')
    expect(await colorOf(dte58), '已过期 DTE is red').toBe('red')
    expect(await colorOf(ui.optionRow('IXQZ', IXQZ_60P).getByTestId(S.optionCells.dte)), 'active DTE not red').not.toBe('red')
    await ui.expectStock('IXQZ', null)

    await ui.expectOptions('ZWLK', [])
    await ui.expectStock('ZWLK', ST_ZWLK)
  })

  test('AC4 不刷新页面：11-24 BTC 1 张 45P 60.65 → 45P 1 张 +110.00；STC 40P 20.65 → 40P 消失、标题 期权 3 个持仓 · 股票 100 股；卖出 ZWLK 50 股 → ZWLK 卡片消失', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-24T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.cards).toHaveCount(3)
    await ui.markNoReload()

    // BTC 1 张 45P: FIFO closes T2 (120.35), T4 (110.00) remains
    await ui.gotoPositionsByNav()
    await ui.closeOnPositions(QRTV_45P, 1, '60.65', '2026-11-24T10:00')
    await ui.gotoStrategyByNav()
    await expect.poll(async () => (await ui.optionRows('QRTV')).find((r) => r[0] === QRTV_45P)?.slice(1, 4))
      .toEqual(['空头', '1', '+110.00'])
    await ui.expectCards([
      ['IXQZ', '期权 2 个持仓'],
      ['QRTV', '期权 4 个持仓 · 股票 100 股'],
      ['ZWLK', '股票 50 股'],
    ])

    // STC 40P → row gone
    await ui.gotoPositionsByNav()
    await ui.closeOnPositions(QRTV_40P, 1, '20.65', '2026-11-24T10:00')
    await ui.gotoStrategyByNav()
    await expect(ui.optionRow('QRTV', QRTV_40P)).toHaveCount(0)
    await expect.poll(async () => (await ui.optionRows('QRTV')).map((r) => r[0])).toEqual([QRTV_45P, QRTV_55C, QRTV_45P_JAN])
    await expect(ui.card('QRTV').getByTestId(S.cardSummary)).toHaveText('期权 3 个持仓 · 股票 100 股')

    // sell all ZWLK → card gone
    await ui.gotoPositionsByNav()
    await ui.sellOnPositions('ZWLK', '50', '1600.00', '2026-11-24T10:00')
    await ui.gotoStrategyByNav()
    await ui.expectCards([
      ['IXQZ', '期权 2 个持仓'],
      ['QRTV', '期权 3 个持仓 · 股票 100 股'],
    ])
    await expect(ui.card('ZWLK')).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC5 作废 IXQZ 58P → IXQZ 只剩 60P、标题 期权 1 个持仓；删除该作废记录 → 恢复 AC3', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectOptions('IXQZ', [R_IXQZ_58P, R_IXQZ_60P])
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.expireOnPositions(IXQZ_58P)
    await ui.gotoStrategyByNav()
    await expect(ui.card('IXQZ').getByTestId(S.cardSummary)).toHaveText('期权 1 个持仓')
    await ui.expectOptions('IXQZ', [R_IXQZ_60P])
    await ui.expectStock('IXQZ', null)

    await ui.deleteTradeByAction('到期作废')
    await ui.gotoStrategyByNav()
    await ui.expectScenarioA()
    expect(await colorOf(ui.optionRow('IXQZ', IXQZ_58P).getByTestId(S.optionCells.dte))).toBe('red')
    await ui.expectNoReload()
  })

  test('AC6 12-18 18:00 IXQZ 60P 被指派（指派日期 12-18）→ IXQZ 期权只剩 58P（已过期 28 天），股票 100 / 0 / 100 / 5,800.00 / 58.0000，标题 期权 1 个持仓 · 股票 100 股；删除被指派记录 → 股票行消失、60P 恢复', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-12-18T18:00'))
    const ui = new Ui(page)
    await ui.open()
    // 到期日当天: DTE shows 今天到期 (same as FEAT-003)
    await expect(ui.optionRow('IXQZ', IXQZ_60P).getByTestId(S.optionCells.dte)).toContainText('今天到期')
    await expect(ui.optionRow('QRTV', QRTV_45P).getByTestId(S.optionCells.dte)).toContainText('今天到期')
    await expect(ui.optionRow('IXQZ', IXQZ_58P).getByTestId(S.optionCells.dte)).toHaveText('已过期 28 天')
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.assignOnPositions(IXQZ_60P, '2026-12-18')
    await ui.gotoStrategyByNav()
    await expect(ui.card('IXQZ').getByTestId(S.cardSummary)).toHaveText('期权 1 个持仓 · 股票 100 股')
    await ui.expectOptions('IXQZ', [[IXQZ_58P, '空头', '1', '+90.00', '2026-11-20', '已过期 28 天']])
    await ui.expectStock('IXQZ', ['100', '0', '100', '5,800.00', '58.0000'])
    await expect.poll(() => ui.cardSymbols()).toEqual(['IXQZ', 'QRTV', 'ZWLK'])

    await ui.deleteTradeByAction('被指派')
    await ui.gotoStrategyByNav()
    await expect(ui.card('IXQZ').getByTestId(S.cardSummary)).toHaveText('期权 2 个持仓')
    await ui.expectStock('IXQZ', null)
    await expect.poll(async () => (await ui.optionRows('IXQZ')).map((r) => r[0])).toEqual([IXQZ_58P, IXQZ_60P])
    await expect(ui.optionRow('IXQZ', IXQZ_60P).getByTestId(S.optionCells.dte)).toContainText('今天到期')
    await ui.expectNoReload()
  })

  test('AC7 12-18 18:00 QRTV 55C 被指派 → QRTV 卡片 55C 行消失、股票行消失，标题 期权 3 个持仓', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-12-18T18:00'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectStock('QRTV', ST_QRTV)
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.assignOnPositions(QRTV_55C, '2026-12-18')
    await ui.gotoStrategyByNav()
    await expect(ui.card('QRTV').getByTestId(S.cardSummary)).toHaveText('期权 3 个持仓')
    await expect(ui.optionRow('QRTV', QRTV_55C)).toHaveCount(0)
    await expect.poll(async () => (await ui.optionRows('QRTV')).map((r) => r[0])).toEqual([QRTV_40P, QRTV_45P, QRTV_45P_JAN])
    await ui.expectStock('QRTV', null)
    await expect.poll(() => ui.cardSymbols()).toEqual(['IXQZ', 'QRTV', 'ZWLK'])
    await ui.expectNoReload()
  })

  test('AC8 切换到账户 B：只有 QRTV 期权 1 个持仓 45P 空头 1 +115.00、无股票行；切换到没有持仓的账户 C：暂无持仓', async ({ page }) => {
    const b = await scenario()
    const c = await apiCreateAccount(ACCOUNT_C)
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.id('account-current')).toHaveText(ACCOUNT_A)
    await ui.expectScenarioA()

    await ui.switchAccount(b, ACCOUNT_B)
    await ui.expectCards([['QRTV', '期权 1 个持仓']])
    await ui.expectOptions('QRTV', [[QRTV_45P, '空头', '1', '+115.00', '2026-12-18', '25']])
    await ui.expectStock('QRTV', null)
    await expect(ui.id(S.empty)).toHaveCount(0)

    await ui.switchAccount(c, ACCOUNT_C)
    await expect(ui.id(S.empty)).toBeVisible()
    await expect(ui.id(S.empty)).toContainText(TEXT.empty)
    await expect(ui.cards).toHaveCount(0)

    // back to A: everything returns without a reload
    await ui.switchAccount(1, ACCOUNT_A)
    await ui.expectScenarioA()
  })

  test('AC9 显示 Greeks 已打开时策略页仍无 Greeks 列（市值 / 未实现盈亏 自 FEAT-023 起存在）、无任何操作按钮；点合约进入交易列表按合约筛选；点标题 ZWLK 进入股票页按 ZWLK 筛选', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await page.goto('/positions')
    await expect(ui.id('positions-as-of')).toBeVisible()
    const toggle = ui.id('positions-greeks-toggle')
    if ((await toggle.getAttribute('aria-checked')) !== 'true') await toggle.click()
    await expect(toggle).toHaveAttribute('aria-checked', 'true')

    await ui.gotoStrategyByNav()
    await ui.expectScenarioA()
    for (const symbol of ['IXQZ', 'QRTV', 'ZWLK']) {
      const card = ui.card(symbol)
      const cardText = norm(await card.innerText())
      for (const word of TEXT.forbiddenColumns) expect(cardText, `${symbol} card has no ${word}`).not.toContain(word)
      await expect(card.getByRole('button'), `${symbol} card has no buttons`).toHaveCount(0)
      for (const t of await card.locator('table').all()) {
        const hs = await ui.headers(t)
        expect(hs.every((h) => [...TEXT.optionHeaders, ...TEXT.stockHeaders].includes(h)), `${symbol} headers ${hs.join(',')}`).toBe(true)
      }
    }
    const pageText = norm(await ui.strategyPage.innerText())
    for (const word of TEXT.forbiddenButtons) {
      await expect(ui.strategyPage.getByRole('button', { name: word }), `no ${word} button`).toHaveCount(0)
    }
    expect(pageText).not.toContain('Greeks 汇总')
    await expect(ui.strategyPage.getByTestId('greeks-summary')).toHaveCount(0)

    // contract link → trade list filtered by that contract (FEAT-003 behaviour): only T1
    await ui.optionRow('QRTV', QRTV_55C).getByTestId(S.optionCells.contract).click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect(ui.id('trade-filter-symbol')).toHaveValue('QRTV')
    await expect(ui.id('trade-filter-expiration')).toHaveValue('2026-12-18')
    await expect(ui.id('trade-filter-strike')).toHaveValue('55')
    await expect(ui.id('trade-filter-type-call')).toBeChecked()
    await expect(ui.id('trade-list-total')).toHaveText('共 1 条')
    await expect(ui.id('trade-row').getByTestId('trade-cell-contract')).toHaveText(QRTV_55C)

    // card title symbol → stock page filtered by ZWLK (FEAT-011 behaviour)
    await ui.gotoStrategyByNav()
    await ui.card('ZWLK').getByTestId(S.cardSymbol).click()
    await expect(page).toHaveURL(/\/stocks\?.*symbol=ZWLK/)
    await expect(ui.id('stock-filter-symbol')).toHaveValue('ZWLK')
    await expect.poll(() => page.getByTestId('stock-row').evaluateAll((rs) => rs.length)).toBe(1)
    await expect(ui.id('stock-row').getByTestId('stock-cell-symbol')).toHaveText('ZWLK')
  })

  // ---------------------------------------------------------------------------------------------- boundaries

  test('边界：股票部分卖出 → 卡片仍在，持股 / 成本减少（ZWLK 卖出 20 股 → 30 / 0 / 30 / 900.00 / 30.0000，标题 股票 30 股）', async ({ page }) => {
    await scenario()
    await setBackendNow(ny('2026-11-23T10:00'))
    await apiSellShares('ZWLK', 20, '640.00', '2026-11-23T09:45')
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectCards([
      ['IXQZ', '期权 2 个持仓'],
      ['QRTV', '期权 4 个持仓 · 股票 100 股'],
      ['ZWLK', '股票 30 股'],
    ])
    await ui.expectStock('ZWLK', ['30', '0', '30', '900.00', '30.0000'])
  })

  test('边界：只有股票的标的与只有期权的标的都成组；千分位持股（ZWLK 再转入 1,000 股 → 股票 1,050 股）', async ({ page }) => {
    await scenario()
    await apiTransferInShares('ZWLK', 1000, '2026-10-02', '30000.00')
    await fixNow(page, ny('2026-11-23T10:00'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.card('ZWLK').getByTestId(S.cardSummary)).toHaveText('股票 1,050 股')
    await ui.expectStock('ZWLK', ['1,050', '0', '1,050', '31,500.00', '30.0000'])
    await ui.expectOptions('ZWLK', [])
  })

  test('边界：后端报错时策略页提示错误，不显示 暂无持仓 也不显示卡片', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:00'))
    await page.route('**/api/option-positions', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/option-positions', timestamp: new Date().toISOString(), details: [] }),
      }),
    )
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.id(S.error)).toBeVisible({ timeout: 30_000 })
    await expect(ui.id(S.error)).toContainText('INTERNAL_ERROR')
    await expect(ui.id(S.empty)).toHaveCount(0)
    await expect(ui.cards).toHaveCount(0)

    await page.unroute('**/api/option-positions')
    await ui.id(S.retry).click()
    await expect(ui.id(S.error)).toHaveCount(0)
    await ui.expectScenarioA()
  })
})
