import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-047 全部账户合并查看（三）E2E: 策略页（每个 账户 + 标的 一张卡片）and the 持仓页 `按标的 Greeks 汇总` (merged per
 * symbol across accounts) under 全部账户. Fake CBOE via the e2e test-support endpoints, fixed backend clock +
 * page.clock; never the real CBOE / Finnhub; fictional account names and tickers only.
 *
 * Scenario G (requirement; today 2026-11-23 Mon, the 10:00 pull succeeds; multiplier 100; ZZA 50.00, ZZB 40.00):
 *   甲账户 (id 1): deposit 50,000.00; S1 转入 ZZA 100 股 4,800.00 (10-01); H1 STO ZZA 11-20 52C 60.35 (10-20) /
 *     H2 BTC 20.65 (10-30) → +39.70; P1 STO ZZA 12-18 55C 85.35 (11-02, mark 0.60, Δ .20 Γ .04 Θ −.03 V .06);
 *     P2 STO ZZB 12-18 38P 120.00 (11-05, mark 1.10, Δ −.30 Γ .05 Θ −.04 V .045)
 *   乙账户 (id 2): deposit 20,000.00; H3 STO ZZA 11-13 47P 80.00 (10-27), 作废 → +80.00; P3 STO 2 ZZA 12-18 45P
 *     230.00 (11-05, mark 1.10, Δ −.15 Γ .03 Θ −.025 V .05); P4 STO ZZB 11-20 36P 50.00 (11-02), expired, pending
 *
 * Test ids: FEAT-019 / 021 / 023 / 035 / 046 ids from docs/architecture.md. FEAT-047 adds no documented id yet; the
 * card title prefix is asserted through the card's text (`甲账户 · ZZA`) — reconcile with docs/architecture.md
 * "FEAT-047 testid" once Frontend: Done.
 */

// ================================================================================================ test ids
const TID = {
  // FEAT-021 / 023 strategy page
  nav: 'nav-strategies',
  page: 'underlying-page',
  hint: 'underlying-hint',
  empty: 'underlying-empty',
  card: 'underlying-card',
  cardSymbol: 'underlying-card-symbol',
  cardSummary: 'underlying-card-summary',
  optionTable: 'underlying-options',
  optionRow: 'underlying-option-row',
  stockTable: 'underlying-stock',
  pnl: 'underlying-pnl',
  realized: 'underlying-pnl-realized',
  realizedOption: 'underlying-pnl-realized-option',
  realizedStock: 'underlying-pnl-realized-stock',
  unrealized: 'underlying-pnl-unrealized',
  total: 'underlying-pnl-total',
  marketValue: 'underlying-pnl-market-value',
  netPremium: 'underlying-pnl-net-premium',
  excluded: 'underlying-pnl-excluded',
  // FEAT-019 Greeks summary
  greeksToggle: 'positions-greeks-toggle',
  greeks: 'greeks-summary',
  greeksNote: 'greeks-summary-note',
  greeksRow: 'greeks-summary-row',
  greeksPortfolio: 'greeks-summary-portfolio',
  // FEAT-035
  placeholder: 'all-accounts-placeholder',
  greeksAllPlaceholder: 'greeks-summary-all-accounts',
  optionAll: 'account-option-all',
  rowAccountAttr: 'data-account-id',
} as const

// ================================================================================================ texts (requirement)
const A_NAME = '甲账户'
const B_NAME = '乙账户'
const CURRENT_ALL = '当前：全部账户（只读）'
const CURRENT_ALL_NAME = '全部账户（只读）'
const DASH = '—'
const HINT_SINGLE =
  '按标的列出当前账户正在持有的期权和股票，只读；操作请到持仓页。已实现盈亏为该标的全部历史（含手续费）；市值和未实现盈亏按 mark / 现价计算，未扣平仓手续费，仅供参考'
const HINT_ALL = HINT_SINGLE.replace('当前账户', '全部账户（每个账户的每个标的一张卡片）')
const GREEKS_NOTE_SINGLE = '按标的汇总当前账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考'
const GREEKS_NOTE_ALL = GREEKS_NOTE_SINGLE.replace('当前账户', '全部账户')
const GREEKS_TITLE = '按标的 Greeks 汇总'
const GREEKS_SINGLE_ONLY = '组合 Greeks 汇总暂只支持单个账户'
const PAGE_NOT_SUPPORTED = '此页暂不支持全部账户，请选择一个账户查看'
const EXPIRED_1 = '1 个持仓已到期待处理，未计入'
const MANUAL_PRICE = '按手动标的价'
const GREEKS_HEADERS = ['标的', '期权 Delta', '股票 Delta', '净 Delta', '标的价', 'Delta 美元敞口', 'Gamma', 'Theta', 'Vega']
const WRITE_BUTTONS = ['平仓', '作废', '被指派', '行权', '卖出', '买入', '删除', '修改', '录入开仓']

const C_P1 = 'ZZA 2026-12-18 55 C'
const C_P2 = 'ZZB 2026-12-18 38 P'
const C_P3 = 'ZZA 2026-12-18 45 P'
const C_P4 = 'ZZB 2026-11-20 36 P'
const C_H1 = 'ZZA 2026-11-20 52 C'

const ny = (local: string): string => nyInstant(local)
/** Backend-style "-18.35" → page "−18.35"; expected values below are written as the page shows them. */
const m = (s: string): string => s.replace(/-/g, MINUS)
const norm = (t: string | null | undefined): string => (t ?? '').replace(/\s+/g, ' ').trim()

// ================================================================================================ seeding (API)

type Opt = Record<string, number | string>
type SourceResponse = { httpStatus: number; body: unknown } | { unreachable: true }

function greekOpt(code: string, bid: number, ask: number, delta: number, gamma: number, theta: number, vega: number): Opt {
  return { option: code, bid, ask, iv: 0.3, delta, gamma, theta, vega, rho: 0.001 }
}

function cboe(symbol: string, price: number, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: {
      timestamp: '2026-11-23 09:45:00',
      data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 50, options },
    },
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

async function accountsCall(method: 'POST' | 'PUT', path: string, name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== (method === 'POST' ? 201 : 200)) throw new Error(`${method} account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiClose(symbol: string, exp: string, strike: string, type: 'CALL' | 'PUT', net: string, time: string): Promise<void> {
  await post('/api/option-trades/closes', {
    symbol, expirationDate: exp, strike, optionType: type, action: 'BTC', contracts: 1, netAmount: net, tradedAtLocal: time, note: null,
  })
}

async function apiExpire(symbol: string, exp: string, strike: string, type: 'CALL' | 'PUT'): Promise<void> {
  await post('/api/option-trades/expirations', { symbol, expirationDate: exp, strike, optionType: type, contracts: 1 })
}

/** Accounts 甲 (1, renamed default) and 乙 (2). */
async function twoAccounts(): Promise<void> {
  setApiAccount(1)
  await accountsCall('PUT', '/1', A_NAME)
  const b = await accountsCall('POST', '', B_NAME)
  if (b !== 2) throw new Error(`乙账户 expected id 2, got ${b}`)
}

/** Scenario G data only (no pull). Backend clock 2026-11-23 09:50 New York; API account 1. */
async function scenarioGData(): Promise<void> {
  await setBackendNow(ny('2026-11-23T09:50'))
  await twoAccounts()
  setApiAccount(1)
  await apiTransfer('DEPOSIT', '50000.00', '2026-10-01T09:00')
  await apiTransferInShares('ZZA', 100, '2026-10-01', '4800.00') // S1
  await apiTrade({ action: 'STO', symbol: 'ZZA', expiration: '2026-11-20', strike: '52', type: 'CALL', netAmount: '60.35', time: '2026-10-20T10:00' }) // H1
  await apiClose('ZZA', '2026-11-20', '52', 'CALL', '20.65', '2026-10-30T10:00') // H2
  await apiTrade({ action: 'STO', symbol: 'ZZA', expiration: '2026-12-18', strike: '55', type: 'CALL', netAmount: '85.35', time: '2026-11-02T10:00' }) // P1
  await apiTrade({ action: 'STO', symbol: 'ZZB', expiration: '2026-12-18', strike: '38', type: 'PUT', netAmount: '120.00', time: '2026-11-05T10:00' }) // P2
  setApiAccount(2)
  await apiTransfer('DEPOSIT', '20000.00', '2026-10-01T09:00')
  await apiTrade({ action: 'STO', symbol: 'ZZA', expiration: '2026-11-13', strike: '47', type: 'PUT', netAmount: '80.00', time: '2026-10-27T10:00' }) // H3
  await apiExpire('ZZA', '2026-11-13', '47', 'PUT') // H3 作废
  await apiTrade({ action: 'STO', symbol: 'ZZA', expiration: '2026-12-18', strike: '45', type: 'PUT', contracts: 2, netAmount: '230.00', time: '2026-11-05T10:00' }) // P3
  await apiTrade({ action: 'STO', symbol: 'ZZB', expiration: '2026-11-20', strike: '36', type: 'PUT', netAmount: '50.00', time: '2026-11-02T10:00' }) // P4
  setApiAccount(1)
}

/** Scenario G + the 10:00 pull; browser clock 10:05 (or `browserNow`). */
async function scenarioG(page: Page, browserNow = '2026-11-23T10:05'): Promise<void> {
  await scenarioGData()
  await setSources({
    ZZA: cboe('ZZA', 50, [
      greekOpt('ZZA261218C00055000', 0.55, 0.65, 0.2, 0.04, -0.03, 0.06),
      greekOpt('ZZA261218P00045000', 1.05, 1.15, -0.15, 0.03, -0.025, 0.05),
    ]),
    ZZB: cboe('ZZB', 40, [greekOpt('ZZB261218P00038000', 1.05, 1.15, -0.3, 0.05, -0.04, 0.045)]),
  })
  await tickAt('2026-11-23T10:00')
  await fixNow(page, ny(browserNow))
}

// ================================================================================================ page helpers

const id = (page: Page, testId: string): Locator => page.getByTestId(testId)

async function expectAllMode(page: Page): Promise<void> {
  await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'true')
  await expect(id(page, 'account-switcher')).toContainText(CURRENT_ALL)
  await expect(id(page, 'account-current')).toHaveText(CURRENT_ALL_NAME)
}

async function switchToAll(page: Page): Promise<void> {
  await id(page, 'account-switcher').click()
  await id(page, TID.optionAll).click()
  await expectAllMode(page)
}

async function switchTo(page: Page, accountId: number, name: string): Promise<void> {
  await id(page, 'account-switcher').click()
  await id(page, `account-option-${accountId}`).click()
  await expect(id(page, 'account-current')).toHaveText(name)
  await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
}

async function markNoReload(page: Page): Promise<void> {
  await page.evaluate(() => {
    ;(window as unknown as { __noReload: boolean }).__noReload = true
  })
}

async function expectNoReload(page: Page): Promise<void> {
  expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
}

// --- strategy page

async function gotoStrategyByNav(page: Page): Promise<void> {
  await id(page, TID.nav).click()
  await expect(page).toHaveURL(/\/strategies$/)
  await expect(id(page, TID.page)).toBeVisible()
}

const cards = (page: Page): Locator => id(page, TID.card)

const ACCOUNT_IDS: Record<string, number> = { [A_NAME]: 1, [B_NAME]: 2 }

/**
 * The card of `symbol`; with an account name (全部账户) the card of that account (FEAT-047: underlying-card carries
 * data-account-id), which must also be titled `<accountName> · <symbol>` (underlying-card-heading).
 */
function card(page: Page, symbol: string, accountName?: string): Locator {
  const acc = accountName === undefined ? '' : `[data-account-id="${ACCOUNT_IDS[accountName]}"]`
  return page.locator(`[data-testid="${TID.card}"][data-symbol="${symbol}"]${acc}`)
}

/** Every card in page order: `<data-account-id> <heading> | <summary>` (FEAT-047 underlying-card-heading). */
async function cardHeads(page: Page): Promise<string[]> {
  return cards(page).evaluateAll((cs) =>
    cs.map((c) => {
      const heading = c.querySelector('[data-testid="underlying-card-heading"]')?.textContent?.replace(/s+/g, ' ').trim() ?? ''
      const summary = c.querySelector('[data-testid="underlying-card-summary"]')?.textContent?.replace(/s+/g, ' ').trim() ?? ''
      return `${c.getAttribute('data-account-id') ?? ''} ${heading} | ${summary}`
    }),
  )
}

/** 已实现, 期权, 股票, 未实现, 合计, 市值, 净权利金, 提示 (null = no hint). */
type CardValues = [string, string, string, string, string, string, string, string | null]

const V_A_ZZA: CardValues = ['+39.70', '+39.70', DASH, '+225.35', '+265.05', '+4,940.00', '+85.35', null]
const V_B_ZZA: CardValues = ['+80.00', '+80.00', DASH, '+10.00', '+90.00', m('-220.00'), '+230.00', null]
const V_A_ZZB: CardValues = ['0.00', DASH, DASH, '+10.00', '+10.00', m('-110.00'), '+120.00', null]
const V_B_ZZB: CardValues = ['0.00', DASH, DASH, DASH, '0.00', DASH, '+50.00', EXPIRED_1]

async function expectCard(c: Locator, what: string, [realized, opt, stock, unrealized, total, mv, prem, hint]: CardValues): Promise<void> {
  await expect(c, what).toHaveCount(1)
  await expect(c.getByTestId(TID.realized), `${what} 已实现`).toHaveText(realized)
  await expect(c.getByTestId(TID.realizedOption), `${what} 已实现 期权`).toHaveText(opt)
  await expect(c.getByTestId(TID.realizedStock), `${what} 已实现 股票`).toHaveText(stock)
  await expect(c.getByTestId(TID.unrealized), `${what} 未实现`).toHaveText(unrealized)
  await expect(c.getByTestId(TID.total), `${what} 合计`).toHaveText(total)
  await expect(c.getByTestId(TID.marketValue), `${what} 市值`).toHaveText(mv)
  await expect(c.getByTestId(TID.netPremium), `${what} 净权利金`).toHaveText(prem)
  if (hint === null) await expect(c.getByTestId(TID.excluded), `${what} 无提示`).toHaveCount(0)
  else await expect(c.getByTestId(TID.excluded), `${what} 提示`).toHaveText(hint)
}

async function expectScenarioGCards(page: Page): Promise<void> {
  await expect.poll(() => cardHeads(page)).toEqual([
    `1 ${A_NAME} · ZZA | 期权 1 个持仓 · 股票 100 股`,
    `2 ${B_NAME} · ZZA | 期权 1 个持仓`,
    `1 ${A_NAME} · ZZB | 期权 1 个持仓`,
    `2 ${B_NAME} · ZZB | 期权 1 个持仓`,
  ])
  await expectCard(card(page, 'ZZA', A_NAME), '甲 ZZA', V_A_ZZA)
  await expectCard(card(page, 'ZZA', B_NAME), '乙 ZZA', V_B_ZZA)
  await expectCard(card(page, 'ZZB', A_NAME), '甲 ZZB', V_A_ZZB)
  await expectCard(card(page, 'ZZB', B_NAME), '乙 ZZB', V_B_ZZB)
}

function cardOptionRow(c: Locator, contract: string): Locator {
  return c.locator(`[data-testid="${TID.optionRow}"][data-contract="${contract}"]`)
}

// --- positions page / Greeks summary

async function openPositions(page: Page): Promise<void> {
  const loaded = page.waitForResponse((r) => r.url().includes('/api/option-positions'))
  await page.goto('/positions')
  await loaded
  await expect(id(page, 'positions-as-of')).toBeVisible()
}

async function gotoPositionsByNav(page: Page): Promise<void> {
  await id(page, 'nav-positions').click()
  await expect(id(page, 'positions-as-of')).toBeVisible()
}

async function greeksOn(page: Page): Promise<void> {
  const t = id(page, TID.greeksToggle)
  if ((await t.getAttribute('aria-checked')) !== 'true') await t.click()
  await expect(t).toHaveAttribute('aria-checked', 'true')
  await expect(id(page, TID.greeks)).toBeVisible()
}

async function greeksOff(page: Page): Promise<void> {
  const t = id(page, TID.greeksToggle)
  if ((await t.getAttribute('aria-checked')) === 'true') await t.click()
  await expect(t).toHaveAttribute('aria-checked', 'false')
}

function optRow(page: Page, contract: string, accountId?: number): Locator {
  const acc = accountId === undefined ? '' : `[${TID.rowAccountAttr}="${accountId}"]`
  return page.locator(`[data-testid="position-row"][data-contract="${contract}"]${acc}`)
}

function stockPosRow(page: Page, symbol: string, accountId?: number): Locator {
  const acc = accountId === undefined ? '' : `[${TID.rowAccountAttr}="${accountId}"]`
  return page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]${acc}`)
}

interface GRow {
  optionDelta: string
  stockDelta: string
  netDelta: string
  price: string
  exposure: string
  gamma: string
  theta: string
  vega: string
  manual?: string
  excluded?: string
}

const G_ZZA: GRow = { optionDelta: '+10.00', stockDelta: '+100.00', netDelta: '+110.00', price: '50.00', exposure: '+5,500.00', gamma: m('-10.00'), theta: '+8.00', vega: m('-16.00') }
const G_ZZB: GRow = { optionDelta: '+30.00', stockDelta: '0.00', netDelta: '+30.00', price: '40.00', exposure: '+1,200.00', gamma: m('-5.00'), theta: '+4.00', vega: m('-4.50'), excluded: EXPIRED_1 }

const greeksRow = (page: Page, symbol: string): Locator =>
  id(page, TID.greeks).locator(`[data-testid="${TID.greeksRow}"][data-symbol="${symbol}"]`)

async function greeksSymbols(page: Page): Promise<string[]> {
  return id(page, TID.greeks).getByTestId(TID.greeksRow).evaluateAll((rs) => rs.map((r) => r.getAttribute('data-symbol') ?? ''))
}

async function expectGreeksRow(page: Page, symbol: string, r: GRow): Promise<void> {
  const row = greeksRow(page, symbol)
  await expect(row, `${symbol} one merged row`).toHaveCount(1)
  const cells: [string, string][] = [
    ['option-delta', r.optionDelta], ['stock-delta', r.stockDelta], ['net-delta', r.netDelta], ['underlying-price', r.price],
    ['exposure', r.exposure], ['gamma', r.gamma], ['theta', r.theta], ['vega', r.vega],
  ]
  for (const [k, v] of cells) await expect(row.getByTestId(`greeks-summary-${k}`), `${symbol} ${k}`).toHaveText(v)
  const notes: [string, string | undefined][] = [['manual-price', r.manual], ['excluded', r.excluded]]
  for (const [k, v] of notes) {
    const n = row.getByTestId(`greeks-summary-${k}-note`)
    if (v === undefined) await expect(n, `${symbol} no ${k} note`).toHaveCount(0)
    else await expect(n, `${symbol} ${k} note`).toHaveText(v)
  }
}

async function expectPortfolio(page: Page, exposure: string, theta: string, vega: string, excluded?: string): Promise<void> {
  const b = id(page, TID.greeks)
  await expect(b.getByTestId(TID.greeksPortfolio)).toContainText('全组合')
  await expect(b.getByTestId('greeks-summary-portfolio-exposure')).toHaveText(exposure)
  await expect(b.getByTestId('greeks-summary-portfolio-theta')).toHaveText(theta)
  await expect(b.getByTestId('greeks-summary-portfolio-vega')).toHaveText(vega)
  const n = b.getByTestId('greeks-summary-portfolio-excluded')
  if (excluded === undefined) await expect(n).toHaveCount(0)
  else await expect(n).toHaveText(excluded)
}

async function greeksHeaders(page: Page): Promise<string[]> {
  return (await id(page, TID.greeks).locator('thead th').allInnerTexts())
    .map((t) => t.replace(/\s*\?\s*$/, '').replace(/\s+/g, ' ').trim())
}

/** FEAT-016 manual price dialog. */
async function setManualPrice(page: Page, edit: Locator, price: string): Promise<void> {
  await edit.click()
  const modal = id(page, 'manual-quote-modal')
  await expect(modal.getByRole('dialog')).toBeVisible()
  await modal.getByTestId('manual-quote-price').fill(price)
  await modal.getByTestId('manual-quote-submit').click()
  await expect(modal.getByRole('dialog')).toHaveCount(0)
}

/** FEAT-004 close dialog from the positions page (single account). */
async function closeOnPositions(page: Page, contract: string, net: string, time: string): Promise<void> {
  await optRow(page, contract).getByTestId('position-close').click()
  const modal = id(page, 'close-trade-modal')
  await expect(modal.getByRole('dialog')).toBeVisible()
  await modal.getByTestId('close-trade-contracts-1').check()
  await modal.getByTestId('close-trade-net-amount').fill(net)
  await modal.getByTestId('close-trade-time').fill(time)
  await modal.getByTestId('close-trade-submit').click()
  await expect(modal.getByTestId('close-trade-confirmation')).toBeVisible()
  await modal.getByTestId('close-trade-done').click()
  await expect(modal.getByRole('dialog')).toBeHidden()
}

test.afterEach(() => {
  setApiAccount(1)
})

// ================================================================================================ tests

test.describe('FEAT-047 全部账户合并查看（三）：策略页、组合 Greeks 汇总', () => {
  test('AC1 全部账户策略页：依次 甲账户 · ZZA（期权 1 个持仓 · 股票 100 股）、乙账户 · ZZA、甲账户 · ZZB、乙账户 · ZZB（各 期权 1 个持仓）；无占位、无合计行', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expect(page.getByText(PAGE_NOT_SUPPORTED)).toHaveCount(0)
    await expect(cards(page)).toHaveCount(4)
    await expect.poll(() => cardHeads(page)).toEqual([
      `1 ${A_NAME} · ZZA | 期权 1 个持仓 · 股票 100 股`,
      `2 ${B_NAME} · ZZA | 期权 1 个持仓`,
      `1 ${A_NAME} · ZZB | 期权 1 个持仓`,
      `2 ${B_NAME} · ZZB | 期权 1 个持仓`,
    ])
    // each card's title starts with its account name
    for (const [sym, name] of [['ZZA', A_NAME], ['ZZA', B_NAME], ['ZZB', A_NAME], ['ZZB', B_NAME]] as const) {
      const c = card(page, sym, name)
      await expect(c, `${name} · ${sym}`).toHaveCount(1)
      await expect(c.getByTestId('underlying-card-heading')).toHaveText(`${name} · ${sym}`)
      await expect(c.getByTestId('underlying-card-account')).toHaveText(name)
      await expect(c.getByTestId('underlying-card-account')).toHaveAttribute('data-account-id', String(ACCOUNT_IDS[name]))
      await expect(c.getByTestId(TID.realized)).toHaveAttribute('data-account-id', String(ACCOUNT_IDS[name]))
    }
    // card rows: each card holds only its own account's rows
    await expect(card(page, 'ZZA', A_NAME).getByTestId(TID.optionRow)).toHaveCount(1)
    await expect(cardOptionRow(card(page, 'ZZA', A_NAME), C_P1)).toHaveCount(1)
    await expect(card(page, 'ZZA', A_NAME).getByTestId(TID.stockTable)).toHaveCount(1)
    await expect(cardOptionRow(card(page, 'ZZA', B_NAME), C_P3)).toHaveCount(1)
    await expect(card(page, 'ZZA', B_NAME).getByTestId(TID.stockTable)).toHaveCount(0)
    await expect(cardOptionRow(card(page, 'ZZB', A_NAME), C_P2)).toHaveCount(1)
    await expect(cardOptionRow(card(page, 'ZZB', B_NAME), C_P4)).toHaveCount(1)
    // one summary line per card, no 全部标的 total (Q201)
    await expect(id(page, TID.page).getByTestId(TID.pnl)).toHaveCount(4)
    const text = norm(await id(page, TID.page).innerText())
    expect(text).not.toContain('+4,610.00')
    expect(text).not.toContain('+245.35')
  })

  test('AC2 卡片摘要：甲 ZZA +39.70 / +225.35 / +265.05 / +4,940.00 / +85.35；乙 ZZA +80.00 / +10.00 / +90.00 / −220.00 / +230.00；甲 ZZB 0.00 / +10.00；乙 ZZB — / 0.00 / — / +50.00 + 提示；乙 ZZA 45P 行 空头 2 +230.00 −220.00 +10.00（+4.35%）；切到甲只有甲的两张卡片、标题无账户名', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expectScenarioGCards(page)

    const p3 = cardOptionRow(card(page, 'ZZA', B_NAME), C_P3)
    await expect(p3.getByTestId('underlying-option-cell-direction')).toHaveText('空头')
    await expect(p3.getByTestId('underlying-option-cell-contracts')).toHaveText('2')
    await expect(p3.getByTestId('underlying-option-cell-opening-net')).toHaveText('+230.00')
    await expect(p3.getByTestId('underlying-option-cell-market-value')).toHaveText(m('-220.00'))
    await expect(p3.getByTestId('underlying-option-cell-unrealized-pnl')).toHaveText('+10.00')
    await expect(p3.getByTestId('underlying-option-cell-unrealized-pnl-percent')).toHaveText('+4.35%')

    await switchTo(page, 1, A_NAME)
    await expect(cards(page)).toHaveCount(2)
    await expect.poll(() => cards(page).evaluateAll((cs) => cs.map((c) => c.getAttribute('data-symbol')))).toEqual(['ZZA', 'ZZB'])
    for (const sym of ['ZZA', 'ZZB']) {
      await expect(card(page, sym).getByTestId('underlying-card-heading'), `${sym} title has no account name in a single account`).toHaveText(sym)
      await expect(card(page, sym).getByTestId('underlying-card-account')).toHaveCount(0)
    }
    await expect(card(page, 'ZZA').getByTestId(TID.cardSummary)).toHaveText('期权 1 个持仓 · 股票 100 股')
    await expectCard(card(page, 'ZZA'), '甲 ZZA (single)', V_A_ZZA)
    await expectCard(card(page, 'ZZB'), '甲 ZZB (single)', V_A_ZZB)
  })

  test('AC3 一致性：全部账户持仓页合计 +4,610.00 / +245.35 / 1 个持仓已到期待处理 = 4 张卡片之和；盈亏汇总 ZZA 2 笔 +119.70 = +39.70 + +80.00', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expectScenarioGCards(page)

    await gotoPositionsByNav(page)
    await expectAllMode(page)
    await expect(id(page, 'unrealized-summary-total-market-value')).toHaveText('+4,610.00')
    await expect(id(page, 'unrealized-summary-total-unrealized-pnl')).toHaveText('+245.35')
    await expect(id(page, 'unrealized-summary-excluded')).toHaveText(EXPIRED_1)

    await id(page, 'nav-pnl-summary').click()
    await expectAllMode(page)
    const zza = page.getByTestId('pnl-symbol-row').filter({ has: page.getByTestId('pnl-symbol-link').getByText('ZZA', { exact: true }) })
    await expect(zza).toHaveCount(1)
    await expect(zza.getByTestId('pnl-symbol-count')).toHaveText('2')
    await expect(zza.getByTestId('pnl-symbol-pnl')).toHaveText('+119.70')
  })

  test('AC4 链接：乙 ZZA 卡片 ZZA 2026-12-18 45 P → 交易列表仍是全部账户、只有 P3；甲 ZZA 卡片 +39.70 → 当前账户变为甲账户，已平仓按 ZZA 筛选只有 H2 +39.70（Q235）', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expectScenarioGCards(page)

    await cardOptionRow(card(page, 'ZZA', B_NAME), C_P3).getByTestId('underlying-option-cell-contract').click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expectAllMode(page)
    const p = new URL(page.url()).searchParams
    expect(p.get('symbol')).toBe('ZZA')
    expect(p.get('expirationDate')).toBe('2026-12-18')
    expect(p.get('optionType')).toBe('PUT')
    await expect(id(page, 'trade-list-total')).toHaveText('共 1 条')
    const row = id(page, 'trade-row')
    await expect(row).toHaveCount(1)
    await expect(row.getByTestId('trade-cell-contract')).toHaveText(C_P3)
    await expect(row.getByTestId('trade-cell-account')).toHaveText(B_NAME)
    await expect(row).toHaveAttribute(TID.rowAccountAttr, '2')

    await gotoStrategyByNav(page)
    await expectAllMode(page)
    await card(page, 'ZZA', A_NAME).getByTestId(TID.realized).click()
    await expect(page).toHaveURL(/\/closed\?/)
    await expect(id(page, 'account-current')).toHaveText(A_NAME)
    await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
    const q = new URL(page.url()).searchParams
    expect(q.get('symbol')).toBe('ZZA')
    expect(q.has('startDate')).toBe(false)
    await expect(id(page, 'closed-count')).toHaveText('共 1 笔平仓')
    await expect(id(page, 'closed-pnl-total')).toHaveText('+39.70')
    await expect(id(page, 'closed-row')).toHaveCount(1)
    await expect(id(page, 'closed-row').getByTestId('closed-cell-contract')).toHaveText(C_H1)
    await expect(id(page, 'closed-row').getByTestId('closed-cell-pnl')).toHaveText('+39.70')
  })

  test('AC5 Greeks 合并行：全部账户打开 显示 Greeks → 按标的 Greeks 汇总 两行、无账户列：ZZA +10.00 / +100.00 / +110.00 / 50.00 / +5,500.00 / −10.00 / +8.00 / −16.00；ZZB +30.00 / 0.00 / +30.00 / 40.00 / +1,200.00 / −5.00 / +4.00 / −4.50 + 1 个持仓已到期待处理', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    await expectAllMode(page)
    await greeksOn(page)
    await expect(id(page, 'greeks-summary-title')).toContainText(GREEKS_TITLE)
    await expect(id(page, TID.greeksNote)).toHaveText(GREEKS_NOTE_ALL)
    await expect(id(page, TID.greeksAllPlaceholder)).toHaveCount(0)
    await expect(page.getByText(GREEKS_SINGLE_ONLY)).toHaveCount(0)
    expect(await greeksHeaders(page), 'same columns as a single account, no 账户 column').toEqual(GREEKS_HEADERS)
    await expect.poll(() => greeksSymbols(page)).toEqual(['ZZA', 'ZZB'])
    await expectGreeksRow(page, 'ZZA', G_ZZA)
    await expectGreeksRow(page, 'ZZB', G_ZZB)
    // per-row Greeks still per account (FEAT-035)
    await expect(optRow(page, C_P1, 1)).toHaveCount(1)
    await expect(optRow(page, C_P3, 2)).toHaveCount(1)
  })

  test('AC6 全组合：全部账户 +6,700.00 / +12.00 / −20.50 + 1 个持仓已到期待处理；切到甲 +5,200.00 / +7.00 / −10.50、ZZA +4,000.00；切到乙 +1,500.00 / +5.00 / −10.00（乙 ZZB 单独为 —）', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    await greeksOn(page)
    await expectPortfolio(page, '+6,700.00', '+12.00', m('-20.50'), EXPIRED_1)

    await switchTo(page, 1, A_NAME)
    await greeksOn(page)
    await expect(id(page, TID.greeksNote)).toHaveText(GREEKS_NOTE_SINGLE)
    await expectPortfolio(page, '+5,200.00', '+7.00', m('-10.50'))
    await expect(greeksRow(page, 'ZZA').getByTestId('greeks-summary-exposure')).toHaveText('+4,000.00')
    await expect(greeksRow(page, 'ZZA').getByTestId('greeks-summary-option-delta')).toHaveText(m('-20.00'))
    await expect(greeksRow(page, 'ZZA').getByTestId('greeks-summary-net-delta')).toHaveText('+80.00')

    await switchTo(page, 2, B_NAME)
    await greeksOn(page)
    await expectPortfolio(page, '+1,500.00', '+5.00', m('-10.00'), EXPIRED_1)
    await expect(greeksRow(page, 'ZZA').getByTestId('greeks-summary-exposure')).toHaveText('+1,500.00')
    // contract note: 乙 alone has only the expired P4 in ZZB → NO_DELTA (—); merged under ALL it is OK
    await expect(greeksRow(page, 'ZZB').getByTestId('greeks-summary-option-delta')).toHaveText(DASH)
    await expect(greeksRow(page, 'ZZB').getByTestId('greeks-summary-exposure')).toHaveText(DASH)
    await expect(greeksRow(page, 'ZZB').locator('[data-status]:has([data-testid="greeks-summary-exposure"])')).toHaveAttribute('data-status', 'NO_DELTA')

    await switchToAll(page)
    await greeksOn(page)
    await expectGreeksRow(page, 'ZZB', G_ZZB)
    await expect(greeksRow(page, 'ZZB').locator('[data-status]:has([data-testid="greeks-summary-exposure"])')).toHaveAttribute('data-status', 'OK')
  })

  test('AC7 手动标的价：全部账户把 ZZA 标的价设为 52.00 → ZZA +5,720.00（按手动标的价）、全组合 +6,920.00，Greeks 与卡片期权行市值 / 未实现不变；清除后恢复', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    await greeksOn(page)
    await expectGreeksRow(page, 'ZZA', G_ZZA)

    // Q218: market-data actions stay available under 全部账户
    await setManualPrice(page, stockPosRow(page, 'ZZA', 1).getByTestId('stock-position-price-set'), '52.00')
    await expectAllMode(page)
    await expectGreeksRow(page, 'ZZA', { ...G_ZZA, price: '52.00', exposure: '+5,720.00', manual: MANUAL_PRICE })
    await expectGreeksRow(page, 'ZZB', G_ZZB)
    await expectPortfolio(page, '+6,920.00', '+12.00', m('-20.50'), EXPIRED_1)

    await gotoStrategyByNav(page)
    const p1 = cardOptionRow(card(page, 'ZZA', A_NAME), C_P1)
    await expect(p1.getByTestId('underlying-option-cell-market-value')).toHaveText(m('-60.00'))
    await expect(p1.getByTestId('underlying-option-cell-unrealized-pnl')).toHaveText('+25.35')
    const p3 = cardOptionRow(card(page, 'ZZA', B_NAME), C_P3)
    await expect(p3.getByTestId('underlying-option-cell-market-value')).toHaveText(m('-220.00'))
    await expect(p3.getByTestId('underlying-option-cell-unrealized-pnl')).toHaveText('+10.00')
    await expectCard(card(page, 'ZZA', B_NAME), '乙 ZZA unchanged', V_B_ZZA)

    await gotoPositionsByNav(page)
    await greeksOn(page)
    await stockPosRow(page, 'ZZA', 1).getByTestId('stock-position-price-clear').click()
    await expect(stockPosRow(page, 'ZZA', 1).getByTestId('stock-position-price-clear')).toHaveCount(0)
    await expectGreeksRow(page, 'ZZA', G_ZZA)
    await expectPortfolio(page, '+6,700.00', '+12.00', m('-20.50'), EXPIRED_1)
    await gotoStrategyByNav(page)
    await expectScenarioGCards(page)
  })

  test('AC8 写操作后更新（不刷新页面）：甲账户 BTC 1 张 P1 净额 60.65 → 切回全部账户：甲 ZZA 卡片期权表消失、股票 100 股，已实现 +64.40 / 未实现 +200.00 / 合计 +264.40 / 市值 +5,000.00 / 净权利金 —；Greeks ZZA +30.00 / +130.00 / +6,500.00 / +5.00 / −10.00；全组合 +7,700.00 / +9.00 / −14.50', async ({ page }) => {
    await scenarioG(page, '2026-11-23T10:35')
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expectScenarioGCards(page)
    await gotoPositionsByNav(page)
    await greeksOn(page)
    await expectPortfolio(page, '+6,700.00', '+12.00', m('-20.50'), EXPIRED_1)
    await markNoReload(page)

    await switchTo(page, 1, A_NAME)
    await closeOnPositions(page, C_P1, '60.65', '2026-11-23T10:30')
    await switchToAll(page)

    await greeksOn(page)
    await expectGreeksRow(page, 'ZZA', { ...G_ZZA, optionDelta: '+30.00', netDelta: '+130.00', exposure: '+6,500.00', gamma: m('-6.00'), theta: '+5.00', vega: m('-10.00') })
    await expectGreeksRow(page, 'ZZB', G_ZZB)
    await expectPortfolio(page, '+7,700.00', '+9.00', m('-14.50'), EXPIRED_1)

    await gotoStrategyByNav(page)
    const a = card(page, 'ZZA', A_NAME)
    await expect(a.getByTestId(TID.optionTable)).toHaveCount(0)
    await expect(a.getByTestId(TID.cardSummary)).toHaveText('股票 100 股')
    await expectCard(a, '甲 ZZA after BTC', ['+64.40', '+64.40', DASH, '+200.00', '+264.40', '+5,000.00', DASH, null])
    await expectCard(card(page, 'ZZA', B_NAME), '乙 ZZA', V_B_ZZA)
    await expectCard(card(page, 'ZZB', A_NAME), '甲 ZZB', V_A_ZZB)
    await expectCard(card(page, 'ZZB', B_NAME), '乙 ZZB', V_B_ZZB)
    await expectNoReload(page)
  })

  test('AC9 开关与切回：全部账户 显示 Greeks 关闭 → 没有 按标的 Greeks 汇总 区块、没有 暂只支持单个账户 灰字；策略页顶部灰字为全部账户新文字；切回单账户恢复原文字', async ({ page }) => {
    await scenarioG(page)
    const greeksRequests: string[] = []
    page.on('request', (req) => {
      if (new URL(req.url()).pathname.startsWith('/api/positions/greeks-summary')) greeksRequests.push(req.headers()['x-account-id'] ?? '')
    })
    await page.goto('/')
    await switchToAll(page)
    await openPositions(page)
    await greeksOff(page)
    await expect(id(page, TID.greeks)).toHaveCount(0)
    await expect(page.getByText(GREEKS_TITLE)).toHaveCount(0)
    await expect(id(page, TID.greeksAllPlaceholder)).toHaveCount(0)
    await expect(page.getByText(GREEKS_SINGLE_ONLY)).toHaveCount(0)
    expect(greeksRequests, 'no summary request while the switch is off').toEqual([])

    await greeksOn(page)
    await expect(id(page, TID.greeksNote)).toHaveText(GREEKS_NOTE_ALL)
    expect(greeksRequests.some((h) => h.trim().toUpperCase() === 'ALL'), 'summary requested with ALL').toBe(true)
    await greeksOff(page)
    await expect(id(page, TID.greeks)).toHaveCount(0)

    await gotoStrategyByNav(page)
    await expect(id(page, TID.hint)).toHaveText(HINT_ALL)

    await switchTo(page, 2, B_NAME)
    await expect(id(page, TID.hint)).toHaveText(HINT_SINGLE)
    await expect(cards(page)).toHaveCount(2)
    await expectCard(card(page, 'ZZA'), '乙 ZZA (single)', V_B_ZZA)
    await expectCard(card(page, 'ZZB'), '乙 ZZB (single)', V_B_ZZB)
  })

  test('AC9 所有账户都没有持仓 → 全部账户策略页 暂无持仓；Greeks 区块为空态、全组合 0.00', async ({ page }) => {
    await setBackendNow(ny('2026-11-23T10:00'))
    await twoAccounts()
    setApiAccount(1)
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expect(id(page, TID.empty)).toHaveText('暂无持仓')
    await expect(cards(page)).toHaveCount(0)
    await expect(id(page, TID.placeholder)).toHaveCount(0)

    await openPositions(page)
    await greeksOn(page)
    await expect(id(page, 'greeks-summary-empty')).toBeVisible()
    await expectPortfolio(page, '0.00', '0.00', '0.00')
  })

  test('边界 只读：全部账户策略页与 Greeks 区块没有任何写按钮；持仓页没有平仓；后端拒绝全部账户的平仓且数字不变', async ({ page }) => {
    await scenarioG(page)
    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expectScenarioGCards(page)
    for (const name of WRITE_BUTTONS) {
      await expect(id(page, TID.page).getByRole('button', { name, exact: true }), `strategy page: no ${name}`).toHaveCount(0)
    }
    await gotoPositionsByNav(page)
    await greeksOn(page)
    for (const name of WRITE_BUTTONS) {
      await expect(id(page, TID.greeks).getByRole('button', { name, exact: true }), `Greeks block: no ${name}`).toHaveCount(0)
    }
    await expect(optRow(page, C_P1, 1).getByTestId('position-close')).toHaveCount(0)
    await expect(optRow(page, C_P3, 2).getByTestId('position-close')).toHaveCount(0)

    const r = await fetch(`${BACKEND_URL}/api/option-trades/closes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Account-Id': 'ALL' },
      body: JSON.stringify({ symbol: 'ZZA', expirationDate: '2026-12-18', strike: '55', optionType: 'CALL', action: 'BTC', contracts: 1, netAmount: '60.65', tradedAtLocal: '2026-11-23T10:00', note: null }),
    })
    expect(r.status).toBe(400)
    expect(((await r.json()) as { code: string }).code).toBe('ALL_ACCOUNTS_READ_ONLY')
    await page.reload()
    await expectAllMode(page)
    await greeksOn(page)
    await expectPortfolio(page, '+6,700.00', '+12.00', m('-20.50'), EXPIRED_1)
  })

  test('边界 只有一个账户有该标的 + 只有已平仓历史的账户不出卡片 + 无标的价只计 1 个：乙在 ZZD 只有已平仓；甲、乙都持有无行情的 ZZC → 卡片 甲 ZZC、乙 ZZC、甲 ZZD；Greeks ZZC 一行 +200.00 无标的价，全组合 2 个标的无标的价（ZZC、ZZD 各 1）', async ({ page }) => {
    await scenarioGData()
    setApiAccount(2)
    await apiTrade({ action: 'STO', symbol: 'ZZD', expiration: '2026-12-18', strike: '30', type: 'PUT', netAmount: '60.00', time: '2026-11-03T10:00' })
    await apiClose('ZZD', '2026-12-18', '30', 'PUT', '20.00', '2026-11-10T10:00')
    await apiTransferInShares('ZZC', 100, '2026-10-01', '3100.00')
    setApiAccount(1)
    await apiTransferInShares('ZZC', 100, '2026-10-01', '3000.00')
    await apiTransferInShares('ZZD', 100, '2026-10-01', '3000.00')
    await setSources({
      ZZA: cboe('ZZA', 50, [
        greekOpt('ZZA261218C00055000', 0.55, 0.65, 0.2, 0.04, -0.03, 0.06),
        greekOpt('ZZA261218P00045000', 1.05, 1.15, -0.15, 0.03, -0.025, 0.05),
      ]),
      ZZB: cboe('ZZB', 40, [greekOpt('ZZB261218P00038000', 1.05, 1.15, -0.3, 0.05, -0.04, 0.045)]),
      ZZC: { unreachable: true },
      ZZD: { unreachable: true },
    })
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))

    await page.goto('/')
    await switchToAll(page)
    await gotoStrategyByNav(page)
    await expect.poll(() => cardHeads(page)).toEqual([
      `1 ${A_NAME} · ZZA | 期权 1 个持仓 · 股票 100 股`,
      `2 ${B_NAME} · ZZA | 期权 1 个持仓`,
      `1 ${A_NAME} · ZZB | 期权 1 个持仓`,
      `2 ${B_NAME} · ZZB | 期权 1 个持仓`,
      `1 ${A_NAME} · ZZC | 股票 100 股`,
      `2 ${B_NAME} · ZZC | 股票 100 股`,
      `1 ${A_NAME} · ZZD | 股票 100 股`,
    ])
    await expect(card(page, 'ZZD', B_NAME), '乙 has only closed history in ZZD → no card').toHaveCount(0)
    // 甲 ZZD realized is 甲's own (none), not 乙's +40.00
    await expect(card(page, 'ZZD', A_NAME).getByTestId(TID.realized)).toHaveText('0.00')
    await expect(card(page, 'ZZC', A_NAME).getByTestId(TID.excluded)).toHaveText('1 个持仓无报价，未计入')

    await openPositions(page)
    await greeksOn(page)
    await expect.poll(() => greeksSymbols(page)).toEqual(['ZZA', 'ZZB', 'ZZC', 'ZZD'])
    const zzc = greeksRow(page, 'ZZC')
    await expect(zzc).toHaveCount(1)
    await expect(zzc.getByTestId('greeks-summary-stock-delta')).toHaveText('+200.00')
    await expect(zzc.getByTestId('greeks-summary-underlying-price')).toHaveText('无标的价')
    await expect(zzc.getByTestId('greeks-summary-exposure')).toHaveText('无标的价')
    await expect(greeksRow(page, 'ZZD').getByTestId('greeks-summary-stock-delta')).toHaveText('+100.00')
    await expectPortfolio(page, '+6,700.00', '+12.00', m('-20.50'), EXPIRED_1)
    await expect(id(page, TID.greeks).getByTestId('greeks-summary-portfolio-no-underlying-price')).toHaveText('2 个标的无标的价，美元敞口未计入')

    await id(page, 'nav-pnl-summary').click()
    const zzd = page.getByTestId('pnl-symbol-row').filter({ has: page.getByTestId('pnl-symbol-link').getByText('ZZD', { exact: true }) })
    await expect(zzd.getByTestId('pnl-symbol-pnl')).toHaveText('+40.00')
  })
})
