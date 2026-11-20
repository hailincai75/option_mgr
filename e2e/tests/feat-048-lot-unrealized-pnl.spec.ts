import { chooseFifoLots } from '../support/closeLots'
import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-048 持仓批次的未实现盈亏 E2E: the positions page's expanded option / stock lot tables get 市值 and 未实现盈亏
 * (with the percent in small text). Fake CBOE via the e2e test-support endpoints, fixed backend clock + page.clock;
 * never the real CBOE / Finnhub; fictional account names and ticker ZZQ only.
 *
 * Scenario (requirement; today 2026-11-16 Mon, the 10:00 pull succeeds; multiplier 100). Data is entered with the
 * backend clock at 2026-11-13 10:30 (so the AC6 2026-11-13 put can still be opened), then the 10:00 pull:
 *   甲账户 (id 1): deposit 50,000.00; S1 转入 ZZQ 100 股 950.00 (10-01); S2 BUY 100 股 780.65 (11-03 10:00);
 *     C = STO ZZQ 2026-12-18 9C: A 1 张 18.34 (11-10 10:00), B 1 张 37.34 (11-11 10:00); bid 0.10 / ask 0.30
 *     P = BTO ZZQ 2026-12-18 8P: L1 1 张 25.65 (11-12 10:00), L2 2 张 41.30 (11-13 10:00); bid 0.15 / ask 0.19
 *     ZZQ price 8.65
 *   乙账户 (id 2): deposit 20,000.00; S3 BUY ZZQ 101 股 880.00 (11-04 10:00); S4 BUY 99 股 860.00 (11-05 10:00)
 * AC7 (price 8.655) and AC9 (8.65) are separate starting states (contract note).
 *
 * Test ids (FEAT-048, from the frontend implementation in progress — reconcile with docs/architecture.md
 * "FEAT-048 testid" once Frontend: Done): the lot cells reuse the FEAT-017 cell components with prefixes
 *   option lot rows  lot-row[data-trade-id]       → lot-cell-market-value, lot-cell-unrealized-pnl,
 *                                                   lot-cell-unrealized-pnl-percent (absent when not computable)
 *   stock lot rows   stock-lot-row[data-trade-id] → stock-lot-market-value, stock-lot-unrealized-pnl,
 *                                                   stock-lot-unrealized-pnl-percent
 * cells carry data-status (OK / NO_QUOTE / EXPIRED_PENDING). Known ids: position-row[data-contract][data-account-id],
 * position-expand, position-lots, stock-position-row[data-symbol][data-account-id], stock-position-expand,
 * stock-position-lots, FEAT-017 row cells, FEAT-016 manual-quote modal, FEAT-004 close modal, FEAT-035 switcher.
 * Display (requirement 页面与交互, Q162): 市值 with an explicit sign (+865.00 / −20.00), P&L signed green / red, percent
 * signed with %, `—` when the backend percent is null; not computable → the backend label in gray in both cells.
 */

const C9 = 'ZZQ 2026-12-18 9 C'
const P8 = 'ZZQ 2026-12-18 8 P'
const P7 = 'ZZQ 2026-12-18 7 P'
const P8X = 'ZZQ 2026-11-13 8 P'
const A_NAME = '甲账户'
const B_NAME = '乙账户'
const NO_QUOTE = '无报价'
const EXPIRED = '已到期，待处理'
const M = MINUS

/** Write controls hidden under 全部账户 (FEAT-035). */
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

// ------------------------------------------------------------------------------------------------ fake sources

type Opt = Record<string, number | string>
type SourceResponse = { httpStatus: number; body: unknown }

const option = (code: string, fields: Record<string, number>): Opt => ({ option: code, ...fields })

function cboe(symbol: string, price: number, timestamp: string, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: { timestamp, data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 8.5, options } },
  }
}

const zzq = (price = 8.65, ts = '2026-11-16 09:45:00', extra: Opt[] = []): SourceResponse =>
  cboe('ZZQ', price, ts, [
    option('ZZQ261218C00009000', { bid: 0.1, ask: 0.3 }),
    option('ZZQ261218P00008000', { bid: 0.15, ask: 0.19 }),
    ...extra,
  ])

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

// ------------------------------------------------------------------------------------------------ seeding (API)

async function accountsCall(method: 'POST' | 'PUT', path: string, name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== (method === 'POST' ? 201 : 200)) throw new Error(`${method} account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiStock(type: 'BUY' | 'SELL', symbol: string, shares: number, amount: string, time: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, symbol, shares, amount, tradedAtLocal: time, note: null }),
  })
  if (r.status !== 201) throw new Error(`${type} ${shares} ${symbol}: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiClose(t: { contracts: number; netAmount: string; time: string }): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol: 'ZZQ', expirationDate: '2026-12-18', strike: '9', optionType: 'CALL', action: 'BTC', contracts: t.contracts, netAmount: t.netAmount, tradedAtLocal: t.time }),
  })
  if (r.status !== 201) throw new Error(`close failed: ${r.status} ${await r.text()}`)
}

type Ids = Record<'S1' | 'S2' | 'A' | 'B' | 'L1' | 'L2' | 'S3' | 'S4', number> & Record<string, number>

/**
 * Scenario data (no pull). `extraA` runs in 甲 at the 2026-11-13 10:30 clock after 甲's trades. Backend clock left at
 * 2026-11-16 09:50, API account 1.
 */
async function scenarioData(extraA?: (ids: Record<string, number>) => Promise<void>): Promise<Ids> {
  const ids: Record<string, number> = {}
  await setBackendNow(ny('2026-11-13T10:30'))
  setApiAccount(1)
  await accountsCall('PUT', '/1', A_NAME)
  const b = await accountsCall('POST', '', B_NAME)
  if (b !== 2) throw new Error(`乙账户 expected id 2, got ${b}`)
  await apiTransfer('DEPOSIT', '50000.00', '2026-10-01T09:00')
  ids.S1 = await apiTransferInShares('ZZQ', 100, '2026-10-01', '950.00')
  ids.S2 = await apiStock('BUY', 'ZZQ', 100, '780.65', '2026-11-03T10:00')
  ids.A = (await apiTrade({ action: 'STO', symbol: 'ZZQ', expiration: '2026-12-18', strike: '9', type: 'CALL', netAmount: '18.34', time: '2026-11-10T10:00' })).id
  ids.B = (await apiTrade({ action: 'STO', symbol: 'ZZQ', expiration: '2026-12-18', strike: '9', type: 'CALL', netAmount: '37.34', time: '2026-11-11T10:00' })).id
  ids.L1 = (await apiTrade({ action: 'BTO', symbol: 'ZZQ', expiration: '2026-12-18', strike: '8', type: 'PUT', netAmount: '25.65', time: '2026-11-12T10:00' })).id
  ids.L2 = (await apiTrade({ action: 'BTO', symbol: 'ZZQ', expiration: '2026-12-18', strike: '8', type: 'PUT', contracts: 2, netAmount: '41.30', time: '2026-11-13T10:00' })).id
  if (extraA) await extraA(ids)
  setApiAccount(2)
  await apiTransfer('DEPOSIT', '20000.00', '2026-10-01T09:00')
  ids.S3 = await apiStock('BUY', 'ZZQ', 101, '880.00', '2026-11-04T10:00')
  ids.S4 = await apiStock('BUY', 'ZZQ', 99, '860.00', '2026-11-05T10:00')
  setApiAccount(1)
  await setBackendNow(ny('2026-11-16T09:50'))
  return ids as Ids
}

/** Scenario + the 10:00 pull (ZZQ 8.65); backend and browser at 2026-11-16 10:05. */
async function scenario(page: Page, extraA?: (ids: Record<string, number>) => Promise<void>): Promise<Ids> {
  const ids = await scenarioData(extraA)
  await setSources({ ZZQ: zzq() })
  await tickAt('2026-11-16T10:00')
  await fixNow(page, ny('2026-11-16T10:05'))
  return ids
}

// ------------------------------------------------------------------------------------------------ expected texts

/** "市值 | 未实现 | 百分比" of a computable lot. */
const v = (mv: string, pnl: string, pct: string): string => `${mv} | ${pnl} | ${pct}`
/** Not computable: both cells show the backend label, no percent element. */
const lbl = (label: string): string => `${label} | ${label} | `

const AC1_A = v(`${M}20.00`, `${M}1.66`, `${M}9.05%`)
const AC1_B = v(`${M}20.00`, '+17.34', '+46.44%')
const AC2_L1 = v('+17.00', `${M}8.65`, `${M}33.72%`)
const AC2_L2 = v('+34.00', `${M}7.30`, `${M}17.68%`)
const AC3_S1 = v('+865.00', `${M}85.00`, `${M}8.95%`)
const AC3_S2 = v('+865.00', '+84.35', '+10.81%')

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

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  /** Positions page; waits until both position lists have loaded. */
  async open(): Promise<void> {
    const opts = this.page.waitForResponse((r) => r.url().includes('/api/option-positions'))
    const stocks = this.page.waitForResponse((r) => r.url().includes('/api/stock-positions'))
    await this.page.goto('/positions')
    await opts
    await stocks
    await expect(this.id('positions-as-of')).toBeVisible()
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await expect(this.id(`account-option-${id}`)).toBeVisible()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
  }

  async switchToAll(): Promise<void> {
    await this.id('account-switcher').click()
    await expect(this.id('account-option-all')).toBeVisible()
    await this.id('account-option-all').click()
    await expect(this.id('account-switcher')).toHaveAttribute('data-all-accounts', 'true')
  }

  row(contract: string, accountId?: number): Locator {
    const acc = accountId === undefined ? '' : `[data-account-id="${accountId}"]`
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]${acc}`)
  }

  stockRow(symbol: string, accountId?: number): Locator {
    const acc = accountId === undefined ? '' : `[data-account-id="${accountId}"]`
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]${acc}`)
  }

  /** Expands an option row (if not yet) and returns its lot table. */
  async expandOption(row: Locator): Promise<Locator> {
    const btn = row.getByTestId('position-expand')
    if ((await btn.getAttribute('aria-expanded')) !== 'true') await btn.click()
    await expect(btn).toHaveAttribute('aria-expanded', 'true')
    const table = row.locator('xpath=following-sibling::tr[1]').getByTestId('position-lots')
    await expect(table).toBeVisible()
    return table
  }

  async expandStock(row: Locator): Promise<Locator> {
    const btn = row.getByTestId('stock-position-expand')
    if ((await btn.getAttribute('aria-expanded')) !== 'true') await btn.click()
    await expect(btn).toHaveAttribute('aria-expanded', 'true')
    const table = row.locator('xpath=following-sibling::tr[1]').getByTestId('stock-position-lots')
    await expect(table).toBeVisible()
    return table
  }

  /** "市值 | 未实现 | 百分比" of every lot row, top to bottom (FIFO). */
  lotLines(table: Locator, kind: 'option' | 'stock'): Promise<string[]> {
    const rowId = kind === 'option' ? 'lot-row' : 'stock-lot-row'
    const p = kind === 'option' ? 'lot-cell' : 'stock-lot'
    return table.getByTestId(rowId).evaluateAll(
      (rs, prefix) =>
        rs.map((r) => {
          const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
          return `${t(`${prefix}-market-value`)} | ${t(`${prefix}-unrealized-pnl`)} | ${t(`${prefix}-unrealized-pnl-percent`)}`
        }),
      p,
    )
  }

  async expectLots(table: Locator, kind: 'option' | 'stock', lines: string[]): Promise<void> {
    await expect.poll(() => this.lotLines(table, kind)).toEqual(lines)
  }

  /** Trade ids of the lot rows, top to bottom. */
  lotIds(table: Locator, kind: 'option' | 'stock'): Promise<number[]> {
    return table
      .getByTestId(kind === 'option' ? 'lot-row' : 'stock-lot-row')
      .evaluateAll((rs) => rs.map((r) => Number(r.getAttribute('data-trade-id'))))
  }

  lot(table: Locator, kind: 'option' | 'stock', tradeId: number): Locator {
    const rowId = kind === 'option' ? 'lot-row' : 'stock-lot-row'
    return table.locator(`[data-testid="${rowId}"][data-trade-id="${tradeId}"]`)
  }

  /** Header texts of a lot table. */
  headers(table: Locator): Promise<string[]> {
    return table.locator('thead th').evaluateAll((ths) => ths.map((th) => th.textContent?.trim() ?? ''))
  }

  async expectOptionRow(contract: string, mv: string, pnl: string, pct: string, accountId?: number): Promise<void> {
    const r = this.row(contract, accountId)
    await expect(r.getByTestId('position-market-value')).toHaveText(mv)
    await expect(r.getByTestId('position-unrealized-pnl')).toHaveText(pnl)
    await expect(r.getByTestId('position-unrealized-pnl-percent')).toHaveText(pct)
  }

  async expectStockRow(symbol: string, mv: string, pnl: string, pct: string, accountId?: number): Promise<void> {
    const r = this.stockRow(symbol, accountId)
    await expect(r.getByTestId('stock-position-market-value')).toHaveText(mv)
    await expect(r.getByTestId('stock-position-unrealized-pnl')).toHaveText(pnl)
    await expect(r.getByTestId('stock-position-unrealized-pnl-percent')).toHaveText(pct)
  }

  /** Opens the manual price / mark modal from `edit`, enters the value and saves; waits for the modal to close. */
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

test.describe('FEAT-048 持仓批次的未实现盈亏', () => {
  test('AC1 / AC2 / AC3 展开 C、P、ZZQ 股票：行不变；批次 市值 / 未实现 / 百分比；列在 剩余开仓净额 / 剩余成本 之后；正绿负红', async ({ page }) => {
    const ids = await scenario(page)
    const ui = new Ui(page)
    await ui.open()

    // AC1 short lots
    await ui.expectOptionRow(C9, `${M}40.00`, '+15.68', '+28.16%')
    const c = await ui.expandOption(ui.row(C9))
    expect(await ui.lotIds(c, 'option')).toEqual([ids.A, ids.B])
    await ui.expectLots(c, 'option', [AC1_A, AC1_B])
    expect(await colorOf(ui.lot(c, 'option', ids.A).getByTestId('lot-cell-unrealized-pnl'))).toBe('red')
    expect(await colorOf(ui.lot(c, 'option', ids.B).getByTestId('lot-cell-unrealized-pnl'))).toBe('green')
    await expect(ui.lot(c, 'option', ids.A).getByTestId('lot-cell-market-value')).toHaveAttribute('data-status', 'OK')
    const oh = await ui.headers(c)
    const oi = oh.indexOf('剩余开仓净额')
    expect(oi, `lot headers ${oh.join(',')}`).toBeGreaterThanOrEqual(0)
    expect(oh.slice(oi, oi + 3)).toEqual(['剩余开仓净额', '市值', '未实现盈亏'])
    // the row itself is unchanged after expanding
    await ui.expectOptionRow(C9, `${M}40.00`, '+15.68', '+28.16%')

    // AC2 long lots of different sizes
    await ui.expectOptionRow(P8, '+51.00', `${M}15.95`, `${M}23.82%`)
    const p = await ui.expandOption(ui.row(P8))
    expect(await ui.lotIds(p, 'option')).toEqual([ids.L1, ids.L2])
    await ui.expectLots(p, 'option', [AC2_L1, AC2_L2])
    expect(await colorOf(ui.lot(p, 'option', ids.L2).getByTestId('lot-cell-unrealized-pnl'))).toBe('red')

    // AC3 stock lots
    await ui.expectStockRow('ZZQ', '+1,730.00', `${M}0.65`, `${M}0.04%`)
    const s = await ui.expandStock(ui.stockRow('ZZQ'))
    expect(await ui.lotIds(s, 'stock')).toEqual([ids.S1, ids.S2])
    await ui.expectLots(s, 'stock', [AC3_S1, AC3_S2])
    expect(await colorOf(ui.lot(s, 'stock', ids.S1).getByTestId('stock-lot-unrealized-pnl'))).toBe('red')
    expect(await colorOf(ui.lot(s, 'stock', ids.S2).getByTestId('stock-lot-unrealized-pnl'))).toBe('green')
    const sh = await ui.headers(s)
    const si = sh.indexOf('剩余成本')
    expect(si, `stock lot headers ${sh.join(',')}`).toBeGreaterThanOrEqual(0)
    expect(sh.slice(si, si + 3)).toEqual(['剩余成本', '市值', '未实现盈亏'])
  })

  test('AC4 页面上 BTC 1 张 C 净额 20.65：确认页已实现 −2.31；展开中的批次表只剩 B −20.00 / +17.34（+46.44%），与行相同', async ({ page }) => {
    const ids = await scenario(page)
    const ui = new Ui(page)
    await ui.open()
    const c = await ui.expandOption(ui.row(C9))
    await ui.expectLots(c, 'option', [AC1_A, AC1_B])

    await ui.row(C9).getByTestId('position-close').click()
    const modal = ui.id('close-trade-modal')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await chooseFifoLots(modal, 1)
    await modal.getByTestId('close-trade-net-amount').fill('20.65')
    await modal.getByTestId('close-trade-time').fill('2026-11-16T10:04')
    await modal.getByTestId('close-trade-submit').click()
    await expect(modal.getByTestId('close-trade-confirmation')).toBeVisible()
    await expect(modal.getByTestId('close-trade-confirmation-realized-pnl')).toHaveText(`${M}2.31`)
    await modal.getByTestId('close-trade-done').click()

    await ui.expectOptionRow(C9, `${M}20.00`, '+17.34', '+46.44%')
    const after = await ui.expandOption(ui.row(C9))
    await ui.expectLots(after, 'option', [AC1_B])
    expect(await ui.lotIds(after, 'option')).toEqual([ids.B])
  })

  test('AC5 页面上把 C 的 Mark 设为 0.35：行 −70.00 / −14.32（−25.72%）；A −35.00 / −16.66（−90.84%）；B −35.00 / +2.34（+6.27%）；清除后恢复 AC1', async ({ page }) => {
    await scenario(page)
    const ui = new Ui(page)
    await ui.open()
    const c = await ui.expandOption(ui.row(C9))
    await ui.expectLots(c, 'option', [AC1_A, AC1_B])

    await ui.override(ui.row(C9).getByTestId('position-mark-set'), '0.35')
    await expect(ui.row(C9).getByTestId('position-mark')).toHaveText('0.35')
    await ui.expectOptionRow(C9, `${M}70.00`, `${M}14.32`, `${M}25.72%`)
    const c2 = await ui.expandOption(ui.row(C9))
    await ui.expectLots(c2, 'option', [
      v(`${M}35.00`, `${M}16.66`, `${M}90.84%`),
      v(`${M}35.00`, '+2.34', '+6.27%'),
    ])

    await ui.row(C9).getByTestId('position-mark-clear').click()
    await expect(ui.row(C9).getByTestId('position-mark')).toHaveText('0.20')
    await ui.expectOptionRow(C9, `${M}40.00`, '+15.68', '+28.16%')
    const c3 = await ui.expandOption(ui.row(C9))
    await ui.expectLots(c3, 'option', [AC1_A, AC1_B])
  })

  test('AC6 7P 批次两列 无报价、已过期 8P 批次两列 已到期，待处理（灰色）；10:15 起拉取失败（行情未更新）→ A、B 仍 −1.66 / +17.34', async ({ page }) => {
    const ids = await scenario(page, async (x) => {
      x.P7 = (await apiTrade({ action: 'STO', symbol: 'ZZQ', expiration: '2026-12-18', strike: '7', type: 'PUT', netAmount: '12.00', time: '2026-11-13T10:20' })).id
      x.P8X = (await apiTrade({ action: 'STO', symbol: 'ZZQ', expiration: '2026-11-13', strike: '8', type: 'PUT', netAmount: '15.00', time: '2026-11-12T10:30' })).id
    })
    const ui = new Ui(page)
    await ui.open()

    const p7 = await ui.expandOption(ui.row(P7))
    await ui.expectLots(p7, 'option', [lbl(NO_QUOTE)])
    const p7lot = ui.lot(p7, 'option', ids.P7!)
    await expect(p7lot.getByTestId('lot-cell-market-value')).toHaveAttribute('data-status', 'NO_QUOTE')
    await expect(p7lot.getByTestId('lot-cell-unrealized-pnl')).toHaveAttribute('data-status', 'NO_QUOTE')
    await expect(p7lot.getByTestId('lot-cell-unrealized-pnl-percent')).toHaveCount(0)
    expect(await colorOf(p7lot.getByTestId('lot-cell-unrealized-pnl'))).toBe('other')

    const x = await ui.expandOption(ui.row(P8X))
    await ui.expectLots(x, 'option', [lbl(EXPIRED)])
    await expect(ui.lot(x, 'option', ids.P8X!).getByTestId('lot-cell-market-value')).toHaveAttribute('data-status', 'EXPIRED_PENDING')

    // 10:15 on: the ZZQ pull fails; rows show 行情未更新 and keep the 10:00 mark
    await setSources({ ZZQ: { httpStatus: 500, body: null } })
    await tickAt('2026-11-16T10:15')
    await fixNow(page, ny('2026-11-16T10:20'))
    await ui.open()
    await expect(ui.row(C9).getByTestId('position-mark-stale')).toHaveText('行情未更新（上次：11-16 10:00）')
    await ui.expectOptionRow(C9, `${M}40.00`, '+15.68', '+28.16%')
    const c = await ui.expandOption(ui.row(C9))
    await ui.expectLots(c, 'option', [AC1_A, AC1_B])
  })

  test('AC7 现价变为 8.655 后切到乙账户：股票行 +1,731.00 / −9.00（−0.52%）；S3 +874.16 / −5.84（−0.66%）；S4 取余数 +856.84 / −3.16（−0.37%）', async ({ page }) => {
    const ids = await scenario(page)
    await setSources({ ZZQ: zzq(8.655, '2026-11-16 10:10:00') })
    await tickAt('2026-11-16T10:15')
    await fixNow(page, ny('2026-11-16T10:20'))
    const ui = new Ui(page)
    await ui.open()
    await ui.switchAccount(2, B_NAME)

    await ui.expectStockRow('ZZQ', '+1,731.00', `${M}9.00`, `${M}0.52%`)
    const s = await ui.expandStock(ui.stockRow('ZZQ'))
    expect(await ui.lotIds(s, 'stock')).toEqual([ids.S3, ids.S4])
    await ui.expectLots(s, 'stock', [
      v('+874.16', `${M}5.84`, `${M}0.66%`),
      v('+856.84', `${M}3.16`, `${M}0.37%`),
    ])
  })

  test('AC8 AC4 之后卖出 50 股实收 440.00：股票行 150 股 +1,297.50 / +41.85（+3.33%）；S1 剩 50 股 475.00、+432.50 / −42.50（−8.95%）；S2 不变 +84.35', async ({ page }) => {
    const ids = await scenario(page)
    await setBackendNow(ny('2026-11-16T10:05'))
    await apiClose({ contracts: 1, netAmount: '20.65', time: '2026-11-16T10:05' })
    await setBackendNow(ny('2026-11-16T10:10'))
    await apiStock('SELL', 'ZZQ', 50, '440.00', '2026-11-16T10:10')
    await fixNow(page, ny('2026-11-16T10:12'))
    const ui = new Ui(page)
    await ui.open()

    await expect(ui.stockRow('ZZQ').getByTestId('stock-position-shares')).toContainText('150')
    await ui.expectStockRow('ZZQ', '+1,297.50', '+41.85', '+3.33%')
    const s = await ui.expandStock(ui.stockRow('ZZQ'))
    expect(await ui.lotIds(s, 'stock')).toEqual([ids.S1, ids.S2])
    const s1 = ui.lot(s, 'stock', ids.S1)
    await expect(s1.getByTestId('stock-lot-remaining-shares')).toContainText('50')
    await expect(s1.getByTestId('stock-lot-remaining-cost')).toHaveText('475.00')
    await ui.expectLots(s, 'stock', [v('+432.50', `${M}42.50`, `${M}8.95%`), AC3_S2])
  })

  test('AC9 全部账户：展开 甲 C → A、B 同 AC1；展开 乙 ZZQ 股票 → S3 +873.65 / −6.35（−0.72%），S4 +856.35 / −3.65（−0.42%），乙行 −10.00（−0.57%）；无写操作按钮', async ({ page }) => {
    const ids = await scenario(page)
    const ui = new Ui(page)
    await ui.open()
    await ui.switchToAll()
    await expect(ui.row(C9, 1)).toBeVisible()

    const c = await ui.expandOption(ui.row(C9, 1))
    expect(await ui.lotIds(c, 'option')).toEqual([ids.A, ids.B])
    await ui.expectLots(c, 'option', [AC1_A, AC1_B])

    await ui.expectStockRow('ZZQ', '+1,730.00', `${M}10.00`, `${M}0.57%`, 2)
    const s = await ui.expandStock(ui.stockRow('ZZQ', 2))
    expect(await ui.lotIds(s, 'stock')).toEqual([ids.S3, ids.S4])
    await ui.expectLots(s, 'stock', [
      v('+873.65', `${M}6.35`, `${M}0.72%`),
      v('+856.35', `${M}3.65`, `${M}0.42%`),
    ])
    // 甲's stock lots in the same view are still AC3's
    const sa = await ui.expandStock(ui.stockRow('ZZQ', 1))
    await ui.expectLots(sa, 'stock', [AC3_S1, AC3_S2])

    for (const w of POSITION_WRITE_IDS) {
      await expect(ui.id(w), `${w} hidden under ALL`).toHaveCount(0)
    }
  })

  test('边界 Q238：三个批次 + 页面上设手动价 12.3456（33 / 33 / 34 股，后两笔同一时间）→ +407.40、+407.40、最后取余数 +419.76；设之前三个批次都显示 无报价', async ({ page }) => {
    const ids = await scenario(page, async (x) => {
      x.Z1 = await apiStock('BUY', 'ZZS', 33, '400.00', '2026-11-02T10:00')
      x.Z2 = await apiStock('BUY', 'ZZS', 33, '400.00', '2026-11-03T10:00')
      x.Z3 = await apiStock('BUY', 'ZZS', 34, '420.00', '2026-11-03T10:00')
    })
    const ui = new Ui(page)
    await ui.open()
    const s = await ui.expandStock(ui.stockRow('ZZS'))
    await ui.expectLots(s, 'stock', [lbl(NO_QUOTE), lbl(NO_QUOTE), lbl(NO_QUOTE)])

    await ui.override(ui.stockRow('ZZS').getByTestId('stock-position-price-set'), '12.3456')
    await ui.expectStockRow('ZZS', '+1,234.56', '+14.56', '+1.19%')
    const s2 = await ui.expandStock(ui.stockRow('ZZS'))
    expect(await ui.lotIds(s2, 'stock')).toEqual([ids.Z1, ids.Z2, ids.Z3])
    await ui.expectLots(s2, 'stock', [
      v('+407.40', '+7.40', '+1.85%'),
      v('+407.40', '+7.40', '+1.85%'),
      v('+419.76', `${M}0.24`, `${M}0.06%`),
    ])
  })

  test('边界 Q155：剩余成本 0.00 的批次（转入 3 股 0.01、卖出 2 股）百分比显示 —；另一批次 +6.50（+8.13%）', async ({ page }) => {
    const ids = await scenario(page, async (x) => {
      x.R1 = await apiTransferInShares('ZZR', 3, '2026-10-01', '0.01')
      await apiStock('SELL', 'ZZR', 2, '17.00', '2026-11-04T10:00')
      x.R2 = await apiStock('BUY', 'ZZR', 10, '80.00', '2026-11-05T10:00')
    })
    const ui = new Ui(page)
    await ui.open()
    await ui.override(ui.stockRow('ZZR').getByTestId('stock-position-price-set'), '8.65')
    await ui.expectStockRow('ZZR', '+95.15', '+15.15', '+18.94%')
    const s = await ui.expandStock(ui.stockRow('ZZR'))
    expect(await ui.lotIds(s, 'stock')).toEqual([ids.R1, ids.R2])
    await expect(ui.lot(s, 'stock', ids.R1!).getByTestId('stock-lot-remaining-cost')).toHaveText('0.00')
    await ui.expectLots(s, 'stock', [v('+8.65', '+8.65', '—'), v('+86.50', '+6.50', '+8.13%')])
  })
})
