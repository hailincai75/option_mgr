import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-023 按标的盈亏 E2E (fake CBOE via the e2e test-support endpoints, fixed backend clock + page.clock; fictional
 * tickers QRTV / IXQZ / ZWLK / QFZ only).
 *
 * Contract docs/api/FEAT-023.md: the card summary line comes from GET /api/positions/underlying-summary; the row
 * 市值 / 未实现盈亏 columns from the FEAT-017 fields of GET /api/option-positions / /api/stock-positions.
 * Scenario (requirement; today 2026-11-23 Mon, the 10:00 pull succeeds, multiplier 100):
 *   Account A (default, id 1): deposit 50,000.00; S1 转入 QRTV 100 股 4,800.00; S2 转入 ZWLK 50 股 1,500.00 (10-01).
 *   History: H1 STO QRTV 11-20 50C 60.35 / H2 BTC 20.65; H3 STO IXQZ 11-13 55P 80.00 / H4 作废; H5 STO QFZ 11-20 20P
 *   50.00 / H6 BTC 10.65; H7 卖出 ZWLK 20 股 700.00.
 *   Positions: T1 STO QRTV 12-18 55C 85.35; T2 STO 45P 120.35; T3 BTO 40P 40.65; T6 STO IXQZ 11-20 58P 90.00
 *   (expired, pending); T7 STO IXQZ 12-18 60P 200.00.
 *   Quotes: QRTV 50.00; marks 55C 0.60, 45P 1.10, 40P 0.30, IXQZ 60P 2.50; ZWLK never quoted (fake source absent).
 *   Account B: deposit 10,000.00; T8 STO QRTV 12-18 45P 115.00.
 *
 * Writes in AC 5–7 go through the app (positions page / trade list / stock page) and the 策略 page is reached again
 * through the navigation, never page.goto / reload ("不刷新页面"); a window flag proves the document was not reloaded.
 */

// ------------------------------------------------------------------------------------------------ test ids
// FEAT-021 ids (exist): frontend UnderlyingGroupsPage.tsx.
const S = {
  nav: 'nav-strategies',
  page: 'underlying-page',
  note: 'underlying-hint',
  empty: 'underlying-empty',
  card: 'underlying-card',
  cardSymbol: 'underlying-card-symbol',
  optionTable: 'underlying-options',
  optionRow: 'underlying-option-row',
  stockTable: 'underlying-stock',
  stockRow: 'underlying-stock-row',
} as const

// FEAT-023 ids — GUESSED while the frontend is being built (taken from the in-progress UnderlyingPnlSummary.tsx /
// UnrealizedCells.tsx); reconcile with docs/architecture.md once Frontend: Done.
const N = {
  // card summary line
  pnl: 'underlying-pnl', // one per card (no 全部标的 total, Q201)
  realized: 'underlying-pnl-realized', // link → /closed?symbol=…
  realizedSplit: 'underlying-pnl-realized-split', // `期权 +39.70 · 股票 —`
  realizedOption: 'underlying-pnl-realized-option',
  realizedStock: 'underlying-pnl-realized-stock',
  unrealized: 'underlying-pnl-unrealized',
  total: 'underlying-pnl-total',
  marketValue: 'underlying-pnl-market-value',
  netPremium: 'underlying-pnl-net-premium',
  excluded: 'underlying-pnl-excluded', // backend excludedLabel, only when not null
  unavailable: 'underlying-pnl-unavailable', // summary request failed (frontend design, not in the requirement)
  // row columns (FEAT-017 cells with the card prefix)
  optMarketValue: 'underlying-option-cell-market-value',
  optUnrealized: 'underlying-option-cell-unrealized-pnl',
  optUnrealizedPct: 'underlying-option-cell-unrealized-pnl-percent',
  stockMarketValue: 'underlying-stock-cell-market-value',
  stockUnrealized: 'underlying-stock-cell-unrealized-pnl',
  stockUnrealizedPct: 'underlying-stock-cell-unrealized-pnl-percent',
} as const

const STRATEGY_ROUTE = /\/strategies$/

const TEXT = {
  note: '按标的列出当前账户正在持有的期权和股票，只读；操作请到持仓页。已实现盈亏为该标的全部历史（含手续费）；市值和未实现盈亏按 mark / 现价计算，未扣平仓手续费，仅供参考',
  empty: '暂无持仓',
  optionHeaders: ['合约', '方向', '张数', '开仓净额', '市值', '未实现盈亏', '到期日', 'DTE'],
  stockHeaders: ['持股', '被覆盖', '可用', '总成本', '每股平均成本', '市值', '未实现盈亏'],
  forbidden: ['Delta', 'Gamma', 'Theta', 'Vega', 'IV', '最大盈利', '最大亏损', '盈亏平衡'],
  forbiddenButtons: ['平仓', '作废', '被指派', '行权', '卖出', '买入', '删除', '修改'],
  noQuote: '无报价',
  expired: '已到期，待处理',
  excludedExpired: '1 个持仓已到期待处理，未计入',
  excludedNoQuote: '1 个持仓无报价，未计入',
}

const DASH = '—'
const ACCOUNT_A = '默认账户'
const ACCOUNT_B = '账户B'
const ACCOUNT_C = '账户C'

const IXQZ_58P = 'IXQZ 2026-11-20 58 P'
const IXQZ_60P = 'IXQZ 2026-12-18 60 P'
const QRTV_40P = 'QRTV 2026-12-18 40 P'
const QRTV_45P = 'QRTV 2026-12-18 45 P'
const QRTV_55C = 'QRTV 2026-12-18 55 C'

const ny = (local: string): string => nyInstant(local)
const neg = (s: string): string => `${MINUS}${s}`

// ------------------------------------------------------------------------------------------------ expected cards

/** 已实现, 期权, 股票, 未实现, 合计, 市值, 净权利金, 提示 (null = no hint). */
type Card = [string, string, string, string, string, string, string, string | null]

const C_IXQZ: Card = ['+80.00', '+80.00', DASH, neg('50.00'), '+30.00', neg('250.00'), '+290.00', TEXT.excludedExpired]
const C_QRTV: Card = ['+39.70', '+39.70', DASH, '+225.05', '+264.75', '+4,860.00', '+165.05', null]
const C_ZWLK: Card = ['+100.00', DASH, '+100.00', DASH, '+100.00', DASH, DASH, TEXT.excludedNoQuote]

// ------------------------------------------------------------------------------------------------ seeding

type Opt = Record<string, number | string>
type SourceResponse = { httpStatus: number; body: unknown } | { unreachable: true }

const option = (code: string, fields: Record<string, number>): Opt => ({ option: code, ...fields })

function cboe(symbol: string, price: number, timestamp: string, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: { timestamp, data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 50, options } },
  }
}

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

async function post(path: string, body: unknown): Promise<{ id: number }> {
  const r = await fetch(`${BACKEND_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (r.status !== 201) throw new Error(`POST ${path} ${JSON.stringify(body)}: ${r.status} ${await r.text()}`)
  return (await r.json()) as { id: number }
}

async function apiCreateAccount(name: string): Promise<number> {
  setApiAccount(null)
  try {
    return (await post('/api/accounts', { name, brokerNote: null })).id
  } finally {
    setApiAccount(1)
  }
}

async function apiClose(action: 'BTC' | 'STC', symbol: string, exp: string, strike: string, type: 'CALL' | 'PUT', net: string, time: string): Promise<void> {
  await post('/api/option-trades/closes', {
    symbol, expirationDate: exp, strike, optionType: type, action, contracts: 1, netAmount: net, tradedAtLocal: time, note: null,
  })
}

async function apiExpire(symbol: string, exp: string, strike: string, type: 'CALL' | 'PUT'): Promise<void> {
  await post('/api/option-trades/expirations', { symbol, expirationDate: exp, strike, optionType: type, contracts: 1 })
}

async function apiStock(type: 'SELL' | 'BUY', symbol: string, shares: number, amount: string, time: string): Promise<number> {
  return (await post('/api/stock-trades', { type, symbol, shares, amount, tradedAtLocal: time, note: null })).id
}

interface Seeded {
  accountB: number
  h7: number
}

/** Requirement scenario + the 10:00 pull. Backend clock left at 2026-11-23 10:00 New York. */
async function scenario(): Promise<Seeded> {
  await setBackendNow(ny('2026-11-23T09:50'))
  setApiAccount(1)
  await apiTransfer('DEPOSIT', '50000.00', '2026-10-01T09:00')
  await apiTransferInShares('QRTV', 100, '2026-10-01', '4800.00')
  await apiTransferInShares('ZWLK', 50, '2026-10-01', '1500.00')
  // history
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-20', strike: '50', type: 'CALL', netAmount: '60.35', time: '2026-10-20T10:00' })
  await apiClose('BTC', 'QRTV', '2026-11-20', '50', 'CALL', '20.65', '2026-10-30T10:00')
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-11-13', strike: '55', type: 'PUT', netAmount: '80.00', time: '2026-10-27T10:00' })
  await apiExpire('IXQZ', '2026-11-13', '55', 'PUT')
  await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: '2026-11-20', strike: '20', type: 'PUT', netAmount: '50.00', time: '2026-10-28T10:00' })
  await apiClose('BTC', 'QFZ', '2026-11-20', '20', 'PUT', '10.65', '2026-11-06T10:00')
  const h7 = await apiStock('SELL', 'ZWLK', 20, '700.00', '2026-11-12T10:00')
  // positions
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '55', type: 'CALL', netAmount: '85.35', time: '2026-11-02T10:00' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '120.35', time: '2026-11-03T10:00' })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '40', type: 'PUT', netAmount: '40.65', time: '2026-11-03T10:05' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-11-20', strike: '58', type: 'PUT', netAmount: '90.00', time: '2026-11-02T10:30' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-18', strike: '60', type: 'PUT', netAmount: '200.00', time: '2026-11-05T10:30' })
  const b = await apiCreateAccount(ACCOUNT_B)
  setApiAccount(b)
  await apiTransfer('DEPOSIT', '10000.00', '2026-10-01T09:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '45', type: 'PUT', netAmount: '115.00', time: '2026-11-05T11:00' })
  setApiAccount(1)
  await setSources({
    QRTV: cboe('QRTV', 50, '2026-11-23 09:45:00', [
      option('QRTV261218C00055000', { bid: 0.55, ask: 0.65 }),
      option('QRTV261218P00045000', { bid: 1.05, ask: 1.15 }),
      option('QRTV261218P00040000', { bid: 0.25, ask: 0.35 }),
    ]),
    IXQZ: cboe('IXQZ', 61, '2026-11-23 09:45:00', [option('IXQZ261218P00060000', { bid: 2.45, ask: 2.55 })]),
  })
  await tickAt('2026-11-23T10:00')
  return { accountB: b, h7 }
}

// ------------------------------------------------------------------------------------------------ page object

/**
 * P&L color of an element: green / red, else 'other' (gray, black, or the antd link blue rgb(22,119,255) — a link
 * whose value is 0.00 keeps the plain link color, which is "不着色"; blue must not be mistaken for green).
 */
async function colorOf(l: Locator): Promise<'green' | 'red' | 'other'> {
  const colors = await l.evaluate((el) => [el, ...Array.from(el.querySelectorAll('*'))].map((e) => getComputedStyle(e).color))
  for (const c of colors) {
    const [r, g, b] = (c.match(/\d+/g) ?? []).map(Number)
    if (r === undefined || g === undefined || b === undefined) continue
    if (g > r + 40 && g > b) return 'green'
    if (r > g + 40 && r > b) return 'red'
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

  stockRow(symbol: string): Locator {
    return this.card(symbol).getByTestId(S.stockRow)
  }

  async open(): Promise<void> {
    await this.page.goto('/')
    await this.gotoStrategyByNav()
  }

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

  /** The 8 summary values of a card as the page shows them. */
  async expectCard(symbol: string, [realized, opt, stock, unrealized, total, mv, prem, hint]: Card): Promise<void> {
    const c = this.card(symbol)
    await expect(c.getByTestId(N.realized), `${symbol} 已实现`).toHaveText(realized)
    await expect(c.getByTestId(N.realizedOption), `${symbol} 已实现 期权`).toHaveText(opt)
    await expect(c.getByTestId(N.realizedStock), `${symbol} 已实现 股票`).toHaveText(stock)
    await expect(c.getByTestId(N.unrealized), `${symbol} 未实现`).toHaveText(unrealized)
    await expect(c.getByTestId(N.total), `${symbol} 合计`).toHaveText(total)
    await expect(c.getByTestId(N.marketValue), `${symbol} 市值`).toHaveText(mv)
    await expect(c.getByTestId(N.netPremium), `${symbol} 净权利金`).toHaveText(prem)
    if (hint === null) {
      await expect(c.getByTestId(N.excluded), `${symbol} 无提示`).toHaveCount(0)
    } else {
      await expect(c.getByTestId(N.excluded), `${symbol} 提示`).toHaveText(hint)
    }
    // labels of the summary line (requirement wording)
    const text = norm(await c.getByTestId(N.pnl).innerText())
    for (const label of ['已实现', '未实现', '合计', '市值', '净权利金']) expect(text, `${symbol} summary has ${label}`).toContain(label)
  }

  async expectScenarioA(): Promise<void> {
    await expect.poll(() => this.cardSymbols()).toEqual(['IXQZ', 'QRTV', 'ZWLK'])
    await this.expectCard('IXQZ', C_IXQZ)
    await this.expectCard('QRTV', C_QRTV)
    await this.expectCard('ZWLK', C_ZWLK)
  }

  async expectOptionValues(symbol: string, contract: string, mv: string, pnl: string, pct: string): Promise<void> {
    const r = this.optionRow(symbol, contract)
    await expect(r.getByTestId(N.optMarketValue), `${contract} 市值`).toHaveText(mv)
    await expect(r.getByTestId(N.optUnrealized), `${contract} 未实现`).toHaveText(pnl)
    await expect(r.getByTestId(N.optUnrealizedPct), `${contract} 百分比`).toHaveText(pct)
  }

  async expectOptionLabel(symbol: string, contract: string, label: string): Promise<void> {
    const r = this.optionRow(symbol, contract)
    await expect(r.getByTestId(N.optMarketValue)).toHaveText(label)
    await expect(r.getByTestId(N.optUnrealized)).toHaveText(label)
    await expect(r.getByTestId(N.optUnrealizedPct)).toHaveCount(0)
  }

  async expectStockValues(symbol: string, mv: string, pnl: string, pct: string): Promise<void> {
    const r = this.stockRow(symbol)
    await expect(r.getByTestId(N.stockMarketValue), `${symbol} 股票市值`).toHaveText(mv)
    await expect(r.getByTestId(N.stockUnrealized), `${symbol} 股票未实现`).toHaveText(pnl)
    await expect(r.getByTestId(N.stockUnrealizedPct), `${symbol} 股票百分比`).toHaveText(pct)
  }

  async expectStockLabel(symbol: string, label: string): Promise<void> {
    const r = this.stockRow(symbol)
    await expect(r.getByTestId(N.stockMarketValue)).toHaveText(label)
    await expect(r.getByTestId(N.stockUnrealized)).toHaveText(label)
    await expect(r.getByTestId(N.stockUnrealizedPct)).toHaveCount(0)
  }

  async headers(table: Locator): Promise<string[]> {
    return (await table.locator('thead th').allInnerTexts()).map((t) => norm(t)).filter((t) => t !== '')
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
  }

  async markNoReload(): Promise<void> {
    await this.page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })
  }

  async expectNoReload(): Promise<void> {
    expect(await this.page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
  }

  // ---- writes through the app

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
    await modal.getByTestId(`close-trade-contracts-${contracts}`).check()
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

  /** FEAT-005 / 012: delete the trade-list row with this action and contract. */
  async deleteTrade(action: string, contract: string): Promise<void> {
    await this.id('nav-trades').click()
    // the contract cell is a link button (FEAT-003), so match its visible text with filter() rather than :text-is
    const row = this.page
      .getByTestId('trade-row')
      .filter({ has: this.page.getByTestId('trade-cell-action').getByText(action, { exact: true }) })
      .filter({ has: this.page.getByTestId('trade-cell-contract').getByText(contract, { exact: true }) })
    await expect(row).toHaveCount(1)
    await row.getByTestId('trade-delete').click()
    const d = this.dialog('trade-delete-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('trade-delete-confirm').click()
    await expect(this.dialog('trade-delete-modal')).toBeHidden()
    await expect(row).toHaveCount(0)
  }

  /** FEAT-016 manual price from the positions page stock row. */
  async setStockPrice(symbol: string, price: string): Promise<void> {
    await this.stockPosRow(symbol).getByTestId('stock-position-price-set').click()
    const modal = this.id('manual-quote-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await modal.getByTestId('manual-quote-price').fill(price)
    await modal.getByTestId('manual-quote-submit').click()
    await expect(modal.getByRole('dialog')).toHaveCount(0)
  }

  async clearStockPrice(symbol: string): Promise<void> {
    await this.stockPosRow(symbol).getByTestId('stock-position-price-clear').click()
    await expect(this.stockPosRow(symbol).getByTestId('stock-position-price-clear')).toHaveCount(0)
  }

  /** FEAT-011 edit of a stock record's amount on the 股票 page. */
  async editStockAmount(id: number, amount: string): Promise<void> {
    await this.id('nav-stocks').click()
    const row = this.page.locator(`[data-testid="stock-row"][data-stock-trade-id="${id}"]`)
    await expect(row).toBeVisible()
    await row.getByTestId('stock-edit').click()
    const d = this.dialog('stock-edit-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('stock-edit-amount').fill(amount)
    await d.getByTestId('stock-edit-submit').click()
    await expect(d).toHaveCount(0)
  }
}

test.afterEach(() => {
  setApiAccount(1)
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-023 按标的盈亏', () => {
  test('AC1 账户 A：依次 IXQZ、QRTV、ZWLK（无 QFZ）；摘要 已实现（期权 · 股票）/ 未实现 / 合计 / 市值 / 净权利金 / 未计入提示；颜色', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await ui.expectScenarioA()
    await expect(ui.card('QFZ')).toHaveCount(0)
    await expect(ui.card('QRTV').getByTestId(N.realizedSplit)).toHaveText(`期权 +39.70 · 股票 ${DASH}`)
    await expect(ui.card('ZWLK').getByTestId(N.realizedSplit)).toHaveText(`期权 ${DASH} · 股票 +100.00`)

    // 盈亏三项正绿负红；市值 / 净权利金 not P&L colored; — gray (not green / red)
    expect(await colorOf(ui.card('QRTV').getByTestId(N.realized))).toBe('green')
    expect(await colorOf(ui.card('QRTV').getByTestId(N.unrealized))).toBe('green')
    expect(await colorOf(ui.card('QRTV').getByTestId(N.total))).toBe('green')
    expect(await colorOf(ui.card('IXQZ').getByTestId(N.unrealized))).toBe('red')
    expect(await colorOf(ui.card('IXQZ').getByTestId(N.total))).toBe('green')
    expect(await colorOf(ui.card('ZWLK').getByTestId(N.unrealized))).toBe('other')
    expect(await colorOf(ui.card('ZWLK').getByTestId(N.marketValue))).toBe('other')
    // explicit sign on 市值 (Q162) uses U+2212, never ASCII '-'
    await expect(ui.card('IXQZ').getByTestId(N.marketValue)).not.toContainText('-')
  })

  test('AC2 QRTV 卡片行：40P +30.00 / −10.65（−26.20%）、45P −110.00 / +10.35（+8.60%）、55C −60.00 / +25.35（+29.70%）、股票 +5,000.00 / +200.00（+4.17%）；IXQZ 58P 已到期，待处理；ZWLK 无报价', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await ui.expectOptionValues('QRTV', QRTV_40P, '+30.00', neg('10.65'), `${MINUS}26.20%`)
    await ui.expectOptionValues('QRTV', QRTV_45P, neg('110.00'), '+10.35', '+8.60%')
    await ui.expectOptionValues('QRTV', QRTV_55C, neg('60.00'), '+25.35', '+29.70%')
    await ui.expectStockValues('QRTV', '+5,000.00', '+200.00', '+4.17%')
    await ui.expectOptionLabel('IXQZ', IXQZ_58P, TEXT.expired)
    await ui.expectOptionValues('IXQZ', IXQZ_60P, neg('250.00'), neg('50.00'), `${MINUS}25.00%`)
    await ui.expectStockLabel('ZWLK', TEXT.noQuote)
    expect(await colorOf(ui.optionRow('QRTV', QRTV_40P).getByTestId(N.optUnrealized))).toBe('red')
    expect(await colorOf(ui.optionRow('QRTV', QRTV_55C).getByTestId(N.optUnrealized))).toBe('green')
  })

  test('AC3 持仓页合计 +4,610.00 / +175.05、1 无报价 + 1 已到期 = 三张卡片之和；策略页没有全部标的合计；盈亏汇总 IXQZ / QFZ / QRTV / ZWLK', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectScenarioA()
    // one summary line per card, nothing else (Q201)
    await expect(ui.strategyPage.getByTestId(N.pnl)).toHaveCount(3)
    const pageText = norm(await ui.strategyPage.innerText())
    expect(pageText).not.toContain('+4,610.00')
    expect(pageText).not.toContain('+175.05')

    await ui.gotoPositionsByNav()
    await expect(ui.id('unrealized-summary-total-market-value')).toHaveText('+4,610.00')
    await expect(ui.id('unrealized-summary-total-unrealized-pnl')).toHaveText('+175.05')
    await expect(ui.id('unrealized-summary-excluded')).toHaveText('1 个持仓无报价、1 个持仓已到期待处理，未计入')

    await page.getByTestId('nav-pnl-summary').click()
    await expect.poll(() =>
      page.getByTestId('pnl-symbol-row').evaluateAll((rs) =>
        rs.map((r) => ['pnl-symbol-link', 'pnl-symbol-count', 'pnl-symbol-option', 'pnl-symbol-stock', 'pnl-symbol-pnl']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '').join(' '))),
    ).toEqual([
      'IXQZ 1 +80.00 — +80.00',
      'QFZ 1 +39.35 — +39.35',
      'QRTV 1 +39.70 — +39.70',
      'ZWLK 1 — +100.00 +100.00',
    ])
  })

  test('AC4 点 QRTV 卡片的 +39.70 → 已平仓页按 QRTV 筛选、类型不限、无日期，只有 H2 一行 +39.70', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.card('QRTV').getByTestId(N.realized).click()
    await expect(page).toHaveURL(/\/closed\?/)
    const p = new URL(page.url()).searchParams
    expect(p.get('symbol')).toBe('QRTV')
    expect(p.has('type')).toBe(false)
    expect(p.has('startDate')).toBe(false)
    expect(p.has('endDate')).toBe(false)
    await expect(page.getByTestId('closed-filter-record-type-any')).toBeChecked()
    await expect(page.getByTestId('closed-count')).toHaveText('共 1 笔平仓')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+39.70')
    await expect(page.getByTestId('closed-row')).toHaveCount(1)
    await expect(page.getByTestId('closed-row').getByTestId('closed-cell-contract')).toHaveText('QRTV 2026-11-20 50 C')
    await expect(page.getByTestId('closed-row').getByTestId('closed-cell-pnl')).toHaveText('+39.70')
  })

  test('AC5 不刷新页面：10:30 BTC 1 张 QRTV 45P 60.65 → QRTV 已实现 +99.40（+99.40 · —）、未实现 +214.70、合计 +314.10、市值 +4,970.00、净权利金 +44.70，45P 行消失', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:35'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectCard('QRTV', C_QRTV)
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.closeOnPositions(QRTV_45P, 1, '60.65', '2026-11-23T10:30')
    await ui.gotoStrategyByNav()
    await ui.expectCard('QRTV', ['+99.40', '+99.40', DASH, '+214.70', '+314.10', '+4,970.00', '+44.70', null])
    await expect(ui.optionRow('QRTV', QRTV_45P)).toHaveCount(0)
    await ui.expectCard('IXQZ', C_IXQZ)
    await ui.expectCard('ZWLK', C_ZWLK)
    await ui.expectNoReload()
  })

  test('AC6 作废 IXQZ 58P → 已实现 +170.00、未实现 −50.00、合计 +120.00、市值 −250.00、净权利金 +200.00、提示消失；删除作废记录 → 恢复 AC1', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectCard('IXQZ', C_IXQZ)
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.expireOnPositions(IXQZ_58P)
    await ui.gotoStrategyByNav()
    await ui.expectCard('IXQZ', ['+170.00', '+170.00', DASH, neg('50.00'), '+120.00', neg('250.00'), '+200.00', null])

    await ui.deleteTrade('到期作废', IXQZ_58P)
    await ui.gotoStrategyByNav()
    await ui.expectScenarioA()
    await ui.expectOptionLabel('IXQZ', IXQZ_58P, TEXT.expired)
    await ui.expectNoReload()
  })

  test('AC7 ZWLK 现价手动 32.00 → 未实现 +60.00、合计 +160.00、市值 +960.00、提示消失、行 +960.00 / +60.00（+6.67%）；H7 实收改 650.00 → 已实现 +50.00（— · +50.00）、合计 +110.00；清除现价 → —、合计 +50.00', async ({ page }) => {
    const { h7 } = await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectCard('ZWLK', C_ZWLK)
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.setStockPrice('ZWLK', '32.00')
    await ui.gotoStrategyByNav()
    await ui.expectCard('ZWLK', ['+100.00', DASH, '+100.00', '+60.00', '+160.00', '+960.00', DASH, null])
    await ui.expectStockValues('ZWLK', '+960.00', '+60.00', '+6.67%')

    await ui.editStockAmount(h7, '650.00')
    await ui.gotoStrategyByNav()
    await ui.expectCard('ZWLK', ['+50.00', DASH, '+50.00', '+60.00', '+110.00', '+960.00', DASH, null])
    await expect(ui.card('ZWLK').getByTestId(N.realizedSplit)).toHaveText(`期权 ${DASH} · 股票 +50.00`)

    await ui.gotoPositionsByNav()
    await ui.clearStockPrice('ZWLK')
    await ui.gotoStrategyByNav()
    await ui.expectCard('ZWLK', ['+50.00', DASH, '+50.00', DASH, '+50.00', DASH, DASH, TEXT.excludedNoQuote])
    await ui.expectStockLabel('ZWLK', TEXT.noQuote)
    await ui.expectCard('QRTV', C_QRTV)
    await ui.expectNoReload()
  })

  test('AC8 切换到账户 B：只有 QRTV，已实现 0.00（— · —）、未实现 +5.00、合计 +5.00、市值 −110.00、净权利金 +115.00，A 的数字不出现；无持仓账户 → 暂无持仓', async ({ page }) => {
    const { accountB } = await scenario()
    const c = await apiCreateAccount(ACCOUNT_C)
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.id('account-current')).toHaveText(ACCOUNT_A)
    await ui.expectScenarioA()

    await ui.switchAccount(accountB, ACCOUNT_B)
    await expect.poll(() => ui.cardSymbols()).toEqual(['QRTV'])
    await ui.expectCard('QRTV', ['0.00', DASH, DASH, '+5.00', '+5.00', neg('110.00'), '+115.00', null])
    expect(await colorOf(ui.card('QRTV').getByTestId(N.realized)), '0.00 不着色').toBe('other')
    await ui.expectOptionValues('QRTV', QRTV_45P, neg('110.00'), '+5.00', '+4.35%')
    const text = norm(await ui.strategyPage.innerText())
    for (const a of ['+39.70', '+225.05', '+264.75', '+4,860.00', '+165.05', 'IXQZ', 'ZWLK']) expect(text, `no ${a} of account A`).not.toContain(a)

    await ui.switchAccount(c, ACCOUNT_C)
    await expect(ui.id(S.empty)).toBeVisible()
    await expect(ui.id(S.empty)).toContainText(TEXT.empty)
    await expect(ui.cards).toHaveCount(0)
    await expect(ui.strategyPage.getByTestId(N.pnl)).toHaveCount(0)

    await ui.switchAccount(1, ACCOUNT_A)
    await ui.expectScenarioA()
  })

  test('AC9 显示 Greeks 已打开：卡片无 Greeks 列、无最大盈利 / 最大亏损 / 盈亏平衡点、无操作按钮；表头加 市值 / 未实现盈亏；顶部灰字为新文字', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await page.goto('/positions')
    await expect(ui.id('positions-as-of')).toBeVisible()
    const toggle = ui.id('positions-greeks-toggle')
    if ((await toggle.getAttribute('aria-checked')) !== 'true') await toggle.click()
    await expect(toggle).toHaveAttribute('aria-checked', 'true')

    await ui.gotoStrategyByNav()
    await ui.expectScenarioA()
    await expect(ui.id(S.note)).toHaveText(TEXT.note)
    expect(await ui.headers(ui.card('QRTV').getByTestId(S.optionTable))).toEqual(TEXT.optionHeaders)
    expect(await ui.headers(ui.card('QRTV').getByTestId(S.stockTable))).toEqual(TEXT.stockHeaders)
    for (const symbol of ['IXQZ', 'QRTV', 'ZWLK']) {
      const card = ui.card(symbol)
      const cardText = norm(await card.innerText())
      for (const word of TEXT.forbidden) expect(cardText, `${symbol} card has no ${word}`).not.toContain(word)
      await expect(card.getByRole('button'), `${symbol} card has no buttons`).toHaveCount(0)
    }
    for (const word of TEXT.forbiddenButtons) {
      await expect(ui.strategyPage.getByRole('button', { name: word }), `no ${word} button`).toHaveCount(0)
    }
    await expect(ui.strategyPage.getByTestId('greeks-summary')).toHaveCount(0)
  })

  // ---------------------------------------------------------------------------------------------- boundaries

  test('边界：按标的摘要接口报错 → 卡片和行仍显示，摘要行不显示任何数字（不显示 0.00 冒充）；其它卡片不受影响', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    await page.route('**/api/positions/underlying-summary', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/positions/underlying-summary', timestamp: new Date().toISOString(), details: [] }),
      }),
    )
    const ui = new Ui(page)
    await ui.open()
    await expect.poll(() => ui.cardSymbols()).toEqual(['IXQZ', 'QRTV', 'ZWLK'])
    // rows still have their FEAT-017 values
    await ui.expectOptionValues('QRTV', QRTV_55C, neg('60.00'), '+25.35', '+29.70%')
    for (const symbol of ['IXQZ', 'QRTV', 'ZWLK']) {
      const c = ui.card(symbol)
      await expect(c.getByTestId(N.unavailable), `${symbol} shows the summary is unavailable`).toBeVisible({ timeout: 30_000 })
      await expect(c.getByTestId(N.realized)).toHaveCount(0)
      await expect(c.getByTestId(N.total)).toHaveCount(0)
    }
  })

  test('边界：到期日当天（12-18 周五 10:00）照常计算；IXQZ 60P 被指派后 IXQZ 卡片 已实现 +80.00（期权 2 笔）、市值 +6,100.00、未实现 +300.00、合计 +380.00、净权利金 +90.00', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-12-18T18:00'))
    const ui = new Ui(page)
    await ui.open()
    // expiration day: still computed with the last (11-23) quotes
    await ui.expectScenarioA()
    await ui.markNoReload()

    await ui.gotoPositionsByNav()
    await ui.posRow(IXQZ_60P).getByTestId('position-assign').click()
    const d = ui.dialog('assign-modal')
    await expect(d).toBeVisible()
    await d.getByTestId('assign-date').fill('2026-12-18')
    await d.getByTestId('assign-submit').click()
    await expect(d.getByTestId('assign-result')).toBeVisible()
    await d.getByTestId('assign-done').click()
    await expect(ui.dialog('assign-modal')).toBeHidden()

    await ui.gotoStrategyByNav()
    await ui.expectCard('IXQZ', ['+80.00', '+80.00', DASH, '+300.00', '+380.00', '+6,100.00', '+90.00', TEXT.excludedExpired])
    await ui.expectStockValues('IXQZ', '+6,100.00', '+300.00', '+5.17%')
    await ui.expectNoReload()
  })

  test('刷新：持仓页 立即刷新 拉到新行情（QRTV 52.00、55C 0.80）→ 不刷新页面，QRTV 卡片 未实现 +405.05、合计 +444.75、市值 +5,040.00，行 55C −80.00 / +5.35', async ({ page }) => {
    await scenario()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectCard('QRTV', C_QRTV)
    await ui.markNoReload()

    await setSources({
      QRTV: cboe('QRTV', 52, '2026-11-23 10:04:00', [
        option('QRTV261218C00055000', { bid: 0.75, ask: 0.85 }),
        option('QRTV261218P00045000', { bid: 1.05, ask: 1.15 }),
        option('QRTV261218P00040000', { bid: 0.25, ask: 0.35 }),
      ]),
      IXQZ: cboe('IXQZ', 61, '2026-11-23 10:04:00', [option('IXQZ261218P00060000', { bid: 2.45, ask: 2.55 })]),
    })
    await ui.gotoPositionsByNav()
    const refresh = ui.id('market-data-refresh')
    await expect(refresh).toBeEnabled()
    const done = page.waitForResponse((r) => r.url().includes('/api/market-data/refresh'))
    await refresh.click()
    expect((await done).status()).toBe(200)
    await expect(ui.posRow(QRTV_55C).getByTestId('position-mark')).toHaveText('0.80')

    await ui.gotoStrategyByNav()
    // 55C −80.00 / +5.35; stock 5,200.00 / +400.00 → mv −80 − 110 + 30 + 5,200 = 5,040.00; upnl 5.35 + 10.35 − 10.65 + 400 = 405.05
    await ui.expectCard('QRTV', ['+39.70', '+39.70', DASH, '+405.05', '+444.75', '+5,040.00', '+165.05', null])
    await ui.expectOptionValues('QRTV', QRTV_55C, neg('80.00'), '+5.35', '+6.27%')
    await ui.expectStockValues('QRTV', '+5,200.00', '+400.00', '+8.33%')
    await ui.expectCard('IXQZ', C_IXQZ)
    await ui.expectNoReload()
  })

  test('边界：股票卖完 → 卡片消失；再买入 → 卡片重新出现，已实现包含之前全部历史', async ({ page }) => {
    await scenario()
    await setBackendNow(ny('2026-11-23T10:20'))
    await apiStock('SELL', 'ZWLK', 30, '960.00', '2026-11-23T10:10')
    await fixNow(page, ny('2026-11-23T10:20'))
    const ui = new Ui(page)
    await ui.open()
    await expect.poll(() => ui.cardSymbols()).toEqual(['IXQZ', 'QRTV'])

    await apiStock('BUY', 'ZWLK', 10, '320.00', '2026-11-23T10:15')
    await page.reload()
    await expect(ui.strategyPage).toBeVisible()
    await expect.poll(() => ui.cardSymbols()).toEqual(['IXQZ', 'QRTV', 'ZWLK'])
    await ui.expectCard('ZWLK', ['+160.00', DASH, '+160.00', DASH, '+160.00', DASH, DASH, TEXT.excludedNoQuote])
  })
})
