import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-019 组合 Greeks 汇总 E2E (fake CBOE via the e2e test-support endpoints, fixed backend clock + page.clock).
 *
 * Scenario (requirement = FEAT-018 scenario; today 2026-11-23 Mon, the 10:00 pull succeeds, QRTV 52.40, IXQZ 61.10,
 * multiplier 100; fictional tickers). Row position values: P1 +30.21 / −5.12 / +4.56 / −7.11; P2 +57.00 / +9.60 /
 * −7.80 / +13.00; P3 −18.35 / −3.13 / +2.47 / −5.03; P4 无报价; P5 (account B, 3 contracts) +90.63 / −15.36 / +13.68 /
 * −21.33. Account A holds 100 IXQZ shares, account B 100 QRTV shares.
 *
 * Test ids (frontend FEAT-019, components/greeks/GreeksSummaryCard.tsx — to be confirmed with frontend-dev):
 *   block greeks-summary, greeks-summary-title, greeks-summary-note; error greeks-summary-error + greeks-summary-retry;
 *   column-header `?` greeks-summary-header-{stock-delta|net-delta|exposure}-help (tooltip);
 *   rows greeks-summary-row[data-symbol] with greeks-summary-{option-delta|stock-delta|net-delta|underlying-price|
 *   exposure|gamma|theta|vega} and the gray notes greeks-summary-{manual-price|excluded|missing}-note;
 *   portfolio row greeks-summary-portfolio with greeks-summary-portfolio-{exposure|theta|vega} and the notes below it
 *   greeks-summary-portfolio-{excluded|no-underlying-price|missing}.
 *   Reused: positions-greeks-toggle (FEAT-018), position-row[data-contract], position-underlying-price-set / -clear,
 *   stock-position-row[data-symbol] stock-position-price-set, manual-quote-modal (FEAT-016),
 *   positions-expiry-filter-today (FEAT-010), account-switcher (FEAT-033).
 */

const P1 = 'QRTV 2026-12-18 50 P'
const ACCOUNT_B = '账户B'
const DASH = '—'
const TITLE = '按标的 Greeks 汇总'
const NOTE = '按标的汇总当前账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考'
const MANUAL_PRICE = '按手动标的价'
const NO_PRICE = '无标的价'
const NO_QUOTE_1 = '1 个持仓无报价，未计入'

const ny = (local: string): string => nyInstant(local)
/** Backend-style "-18.35" → page "−18.35"; the expected values below are written as the page shows them. */
const m = (s: string): string => s.replace(/-/g, MINUS)

// ------------------------------------------------------------------------------------------------ fake sources

type Opt = Record<string, number | string | null>
type SourceResponse = { httpStatus: number; body: unknown } | { unreachable: true }

/** CBOE options[] entry; a null Greek is omitted (= missing). */
function greek(code: string, bid: number, ask: number, iv: number | null, delta: number | null, gamma: number | null,
  theta: number | null, vega: number | null): Opt {
  const o: Opt = { option: code, bid, ask, rho: -0.0123 }
  const add = (k: string, v: number | null): void => {
    if (v !== null) o[k] = v
  }
  add('iv', iv)
  add('delta', delta)
  add('gamma', gamma)
  add('theta', theta)
  add('vega', vega)
  return o
}

const p1Opt = (): Opt => greek('QRTV261218P00050000', 1.2, 1.3, 0.3512, -0.3021, 0.0512, -0.0456, 0.0711)
const p2Opt = (): Opt => greek('QRTV261218C00055000', 1.8, 1.9, 0.332, 0.285, 0.048, -0.039, 0.065)
const p3Opt = (): Opt => greek('IXQZ261218C00065000', 0.8, 0.9, 0.29, 0.18345, 0.03125, -0.0247, 0.0503)

function cboe(symbol: string, price: number, timestamp: string, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: { timestamp, data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 50, options } },
  }
}

const qrtv = (ts: string, options: Opt[]): SourceResponse => cboe('QRTV', 52.4, ts, options)
const ixqz = (ts = '2026-11-23 09:45:00'): SourceResponse => cboe('IXQZ', 61.1, ts, [p3Opt()])

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

/** Closing trade (BTC / STC, whole oldest lots) in the current API account. */
async function apiClose(action: 'BTC' | 'STC', symbol: string, strike: string, type: 'CALL' | 'PUT', contracts: number,
  netAmount: string, time: string): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol, expirationDate: '2026-12-18', strike, optionType: type, action, contracts, netAmount,
      tradedAtLocal: time, note: null }),
  })
  if (r.status !== 201) throw new Error(`close ${action} ${symbol}: ${r.status} ${await r.text()}`)
}

async function apiSellShares(symbol: string, shares: number, amount: string, time: string): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'SELL', symbol, shares, amount, tradedAtLocal: time, note: null }),
  })
  if (r.status !== 201) throw new Error(`SELL ${symbol}: ${r.status} ${await r.text()}`)
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
  await apiTransfer('DEPOSIT', '20000.00', '2026-11-16T10:00')
  await apiTransferInShares('QRTV', 100, '2026-11-19', '5200.00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', contracts: 3, netAmount: '360.00', time: '2026-11-20T10:20' })
  setApiAccount(1)
  return b
}

/** Scenario + the 10:00 pull (extra QRTV entries appended to P1 / P2). */
async function scenarioPulled(extraQrtv: Opt[] = []): Promise<number> {
  const b = await scenario()
  await setSources({ QRTV: qrtv('2026-11-23 09:45:00', [p1Opt(), p2Opt(), ...extraQrtv]), IXQZ: ixqz() })
  await tickAt('2026-11-23T10:00')
  return b
}

// ------------------------------------------------------------------------------------------------ page object

/** One summary row as the page shows it (explicit sign, U+2212, thousands separators on the exposure). */
interface Row {
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
  missing?: string
}

interface Portfolio {
  exposure: string
  theta: string
  vega: string
  excluded?: string
  noPrice?: string
  missing?: string
}

const QRTV_A: Row = {
  optionDelta: '+87.21', stockDelta: '0.00', netDelta: '+87.21', price: '52.40', exposure: '+4,569.80',
  gamma: '+4.48', theta: m('-3.24'), vega: '+5.89', excluded: NO_QUOTE_1,
}
const IXQZ_A: Row = {
  optionDelta: m('-18.35'), stockDelta: '+100.00', netDelta: '+81.65', price: '61.10', exposure: '+4,988.82',
  gamma: m('-3.13'), theta: '+2.47', vega: m('-5.03'),
}
const PF_A: Portfolio = { exposure: '+9,558.62', theta: m('-0.77'), vega: '+0.86', excluded: NO_QUOTE_1 }
const QRTV_B: Row = {
  optionDelta: '+90.63', stockDelta: '+100.00', netDelta: '+190.63', price: '52.40', exposure: '+9,989.01',
  gamma: m('-15.36'), theta: '+13.68', vega: m('-21.33'),
}
const PF_B: Portfolio = { exposure: '+9,989.01', theta: '+13.68', vega: m('-21.33') }

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  get toggle(): Locator {
    return this.id('positions-greeks-toggle')
  }

  get block(): Locator {
    return this.id('greeks-summary')
  }

  /** Positions page; waits until the positions have loaded. */
  async open(): Promise<void> {
    const positions = this.page.waitForResponse((r) => r.url().includes('/api/option-positions'))
    await this.page.goto('/positions')
    await expect(this.id('positions-as-of')).toBeVisible()
    await positions
  }

  async showGreeks(): Promise<void> {
    if ((await this.toggle.getAttribute('aria-checked')) !== 'true') await this.toggle.click()
    await expect(this.toggle).toHaveAttribute('aria-checked', 'true')
    await expect(this.block).toBeVisible()
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
  }

  posRow(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  stockRow(symbol: string): Locator {
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
  }

  row(symbol: string): Locator {
    return this.block.locator(`[data-testid="greeks-summary-row"][data-symbol="${symbol}"]`)
  }

  get rows(): Locator {
    return this.block.getByTestId('greeks-summary-row')
  }

  get portfolio(): Locator {
    return this.block.getByTestId('greeks-summary-portfolio')
  }

  async expectRow(symbol: string, r: Row): Promise<void> {
    const row = this.row(symbol)
    await expect(row).toHaveCount(1)
    const cells: [string, string][] = [
      ['option-delta', r.optionDelta], ['stock-delta', r.stockDelta], ['net-delta', r.netDelta],
      ['underlying-price', r.price], ['exposure', r.exposure], ['gamma', r.gamma], ['theta', r.theta],
      ['vega', r.vega],
    ]
    for (const [k, v] of cells) await expect(row.getByTestId(`greeks-summary-${k}`), `${symbol} ${k}`).toHaveText(v)
    const notes: [string, string | undefined][] = [['manual-price', r.manual], ['excluded', r.excluded], ['missing', r.missing]]
    for (const [k, v] of notes) {
      const n = row.getByTestId(`greeks-summary-${k}-note`)
      if (v === undefined) await expect(n, `${symbol} no ${k} note`).toHaveCount(0)
      else await expect(n, `${symbol} ${k} note`).toHaveText(v)
    }
  }

  async expectPortfolio(p: Portfolio): Promise<void> {
    await expect(this.portfolio).toContainText('全组合')
    await expect(this.block.getByTestId('greeks-summary-portfolio-exposure')).toHaveText(p.exposure)
    await expect(this.block.getByTestId('greeks-summary-portfolio-theta')).toHaveText(p.theta)
    await expect(this.block.getByTestId('greeks-summary-portfolio-vega')).toHaveText(p.vega)
    const notes: [string, string | undefined][] = [['excluded', p.excluded], ['no-underlying-price', p.noPrice], ['missing', p.missing]]
    for (const [k, v] of notes) {
      const n = this.block.getByTestId(`greeks-summary-portfolio-${k}`)
      if (v === undefined) await expect(n, `portfolio no ${k} note`).toHaveCount(0)
      else await expect(n, `portfolio ${k} note`).toHaveText(v)
    }
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

test.describe('FEAT-019 组合 Greeks 汇总', () => {
  test('AC10 显示 Greeks 关闭时没有 按标的 Greeks 汇总 区块；打开后区块在期权持仓表上方，列与说明；悬停 Delta 美元敞口 / 净 Delta / 股票 Delta 的 ? 显示白话解释；关闭后区块消失', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await expect(ui.toggle).toHaveAttribute('aria-checked', 'false')
    await expect(ui.block).toHaveCount(0)
    await expect(page.getByText(TITLE)).toHaveCount(0)

    await ui.showGreeks()
    await expect(ui.id('greeks-summary-title')).toContainText(TITLE)
    await expect(ui.id('greeks-summary-note')).toHaveText(NOTE)
    const headers = (await ui.block.locator('thead th').allInnerTexts()).map((t) => t.replace(/\s*\?\s*$/, '').replace(/\s+/g, ' ').trim())
    expect(headers).toEqual(['标的', '期权 Delta', '股票 Delta', '净 Delta', '标的价', 'Delta 美元敞口', 'Gamma', 'Theta', 'Vega'])
    // above the option positions table (Q175)
    const blockBox = await ui.block.boundingBox()
    const firstPos = await ui.posRow(P1).boundingBox()
    expect(blockBox && firstPos && blockBox.y + blockBox.height <= firstPos.y, 'summary above the positions').toBe(true)

    await ui.id('greeks-summary-header-exposure-help').first().hover()
    const tip = page.getByRole('tooltip').filter({ hasText: '相当于持有多少美元的这只股票' })
    await expect(tip).toBeVisible()
    await expect(tip).toContainText('Delta 美元敞口 = 净 Delta 股数 × 标的价')
    await expect(tip).toContainText('标的涨 1%，大约赚 45.70')

    await page.mouse.move(0, 0)
    await ui.id('greeks-summary-header-net-delta-help').first().hover()
    await expect(page.getByRole('tooltip').filter({ hasText: '这个标的整体相当于持有多少股' })).toBeVisible()
    await page.mouse.move(0, 0)
    await ui.id('greeks-summary-header-stock-delta-help').first().hover()
    await expect(page.getByRole('tooltip').filter({ hasText: '每股股票的 Delta 固定为 1' })).toBeVisible()

    await ui.toggle.click()
    await expect(ui.toggle).toHaveAttribute('aria-checked', 'false')
    await expect(ui.block).toHaveCount(0)
  })

  test('AC1 / AC2 / AC3 账户 A：QRTV +87.21 / 0.00 / +87.21 / 52.40 / +4,569.80 / +4.48 / −3.24 / +5.89 + 1 个持仓无报价，未计入；IXQZ −18.35 / +100.00 / +81.65 / 61.10 / +4,988.82；全组合 +9,558.62 / −0.77 / +0.86，其它列空', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await expect(ui.rows).toHaveCount(2)
    await expect(ui.rows.nth(0)).toHaveAttribute('data-symbol', 'IXQZ')
    await expect(ui.rows.nth(1)).toHaveAttribute('data-symbol', 'QRTV')
    await ui.expectRow('QRTV', QRTV_A)
    await ui.expectRow('IXQZ', IXQZ_A)
    await ui.expectPortfolio(PF_A)
    const pfCells = (await ui.portfolio.locator('td').allInnerTexts()).map((t) => t.trim())
    expect(pfCells).toEqual(['全组合', '', '', '', '', '+9,558.62', '', `${MINUS}0.77`, '+0.86'])
  })

  test('AC4 切换到账户 B：只有 QRTV 一行 +90.63 / +100.00 / +190.63 / +9,989.01 / −15.36 / +13.68 / −21.33；全组合 +9,989.01 / +13.68 / −21.33，无提示', async ({ page }) => {
    const b = await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await ui.expectRow('QRTV', QRTV_A)

    const summary = page.waitForResponse((r) => r.url().includes('/api/positions/greeks-summary'))
    await ui.switchAccount(b, ACCOUNT_B)
    await summary
    await ui.showGreeks()
    await expect(ui.rows).toHaveCount(1)
    await ui.expectRow('QRTV', QRTV_B)
    await ui.expectPortfolio(PF_B)
  })

  test('AC5 页面上把 QRTV 标的价手动设为 52.00 → A 的 QRTV +4,534.92（按手动标的价），全组合 +9,523.74；B +9,912.76；10:15 拉取失败仍不变；清除后恢复', async ({ page }) => {
    const b = await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await ui.override(ui.posRow(P1).getByTestId('position-underlying-price-set'), '52.00')
    const qA: Row = { ...QRTV_A, price: '52.00', exposure: '+4,534.92', manual: MANUAL_PRICE }
    await ui.expectRow('QRTV', qA)
    await ui.expectRow('IXQZ', IXQZ_A)
    await ui.expectPortfolio({ ...PF_A, exposure: '+9,523.74' })

    await ui.switchAccount(b, ACCOUNT_B)
    await ui.showGreeks()
    const qB: Row = { ...QRTV_B, price: '52.00', exposure: '+9,912.76', manual: MANUAL_PRICE }
    await ui.expectRow('QRTV', qB)
    await ui.expectPortfolio({ ...PF_B, exposure: '+9,912.76' })

    // 10:15 QRTV fetch fails → still the manual values
    await setSources({ QRTV: { httpStatus: 500, body: null }, IXQZ: ixqz('2026-11-23 10:15:00') })
    await tickAt('2026-11-23T10:15')
    await fixNow(page, ny('2026-11-23T10:16'))
    await ui.open()
    await ui.showGreeks()
    await ui.expectRow('QRTV', qB)

    // clear (on account B's row) → back to 52.40, note gone
    await ui.posRow(P1).getByTestId('position-underlying-price-clear').click()
    await ui.expectRow('QRTV', QRTV_B)
    await ui.expectPortfolio(PF_B)
  })

  test('AC6 10:15 P2 的 gamma、vega 为 null → QRTV Gamma −5.12、Vega −7.11，行下另注 Gamma、Vega：1 个持仓缺值，未计入；全组合 Vega −12.14，下方 1 个持仓无报价，未计入 + Vega：1 个持仓缺值，未计入', async ({ page }) => {
    await scenarioPulled()
    await setSources({
      QRTV: qrtv('2026-11-23 10:00:00', [p1Opt(), greek('QRTV261218C00055000', 1.8, 1.9, 0.332, 0.285, null, -0.039, null)]),
      IXQZ: ixqz('2026-11-23 10:00:00'),
    })
    await tickAt('2026-11-23T10:15')
    await fixNow(page, ny('2026-11-23T10:16'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await ui.expectRow('QRTV', { ...QRTV_A, gamma: m('-5.12'), vega: m('-7.11'), missing: 'Gamma、Vega：1 个持仓缺值，未计入' })
    await ui.expectRow('IXQZ', IXQZ_A)
    await ui.expectPortfolio({ ...PF_A, vega: m('-12.14'), missing: 'Vega：1 个持仓缺值，未计入' })
  })

  test('AC7 另有 P6 已过期未处理、P7 今天到期 → QRTV +132.21 / +6,927.80 / −7.52 / +4.76 / +4.39，1 个持仓无报价、1 个持仓已到期待处理，未计入；全组合 +11,916.62 / +7.23 / −0.64', async ({ page }) => {
    await setBackendNow(ny('2026-11-17T09:50'))
    await apiTransfer('DEPOSIT', '15000.00', '2026-11-16T09:00')
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-20', strike: '45', type: 'PUT', netAmount: '60.00', time: '2026-11-17T09:40' })
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-23', strike: '52', type: 'PUT', netAmount: '150.00', time: '2026-11-17T09:45' })
    await setSources({
      QRTV: qrtv('2026-11-20 09:45:00', [
        greek('QRTV261120P00045000', 0.04, 0.06, 0.41, -0.01, 0.004, -0.008, 0.001),
        greek('QRTV261123P00052000', 0.6, 0.7, 0.3, -0.2, 0.1, -0.05, 0.02),
      ]),
    })
    await tickAt('2026-11-20T10:00')
    await scenarioPulled([greek('QRTV261123P00052000', 0.55, 0.65, 0.38, -0.45, 0.12, -0.08, 0.015)])
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    const excluded = '1 个持仓无报价、1 个持仓已到期待处理，未计入'
    await ui.expectRow('QRTV', {
      optionDelta: '+132.21', stockDelta: '0.00', netDelta: '+132.21', price: '52.40', exposure: '+6,927.80',
      gamma: m('-7.52'), theta: '+4.76', vega: '+4.39', excluded,
    })
    await ui.expectPortfolio({ exposure: '+11,916.62', theta: '+7.23', vega: m('-0.64'), excluded })
  })

  test('AC8 行情库为空、账户 C 只持 IXQZ 100 股 → 期权列 —、股票 / 净 Delta +100.00、标的价和敞口 无标的价；全组合 0.00 ×3 + 1 个标的无标的价，美元敞口未计入；手动 60.00 → +6,000.00（按手动标的价），提示消失', async ({ page }) => {
    await setBackendNow(ny('2026-11-23T10:00'))
    const c = await apiCreateAccount('账户C')
    setApiAccount(c)
    await apiTransferInShares('IXQZ', 100, '2026-11-19', '6000.00')
    setApiAccount(1)
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.switchAccount(c, '账户C')
    await ui.showGreeks()

    await expect(ui.rows).toHaveCount(1)
    await ui.expectRow('IXQZ', {
      optionDelta: DASH, stockDelta: '+100.00', netDelta: '+100.00', price: NO_PRICE, exposure: NO_PRICE,
      gamma: DASH, theta: DASH, vega: DASH,
    })
    await ui.expectPortfolio({ exposure: '0.00', theta: '0.00', vega: '0.00', noPrice: '1 个标的无标的价，美元敞口未计入' })

    await ui.override(ui.stockRow('IXQZ').getByTestId('stock-position-price-set'), '60.00')
    await ui.expectRow('IXQZ', {
      optionDelta: DASH, stockDelta: '+100.00', netDelta: '+100.00', price: '60.00', exposure: '+6,000.00',
      gamma: DASH, theta: DASH, vega: DASH, manual: MANUAL_PRICE,
    })
    await ui.expectPortfolio({ exposure: '+6,000.00', theta: '0.00', vega: '0.00' })
  })

  test('AC9 STC 2 张 P2 → QRTV +30.21 / +1,583.00 / −5.12 / +4.56 / −7.11，全组合 +6,571.82 / +7.03 / −12.14；再 BTC P3 并卖出全部 IXQZ → IXQZ 行消失', async ({ page }) => {
    await scenarioPulled()
    await setBackendNow(ny('2026-11-23T10:05'))
    await apiClose('STC', 'QRTV', '55', 'CALL', 2, '368.70', '2026-11-23T10:03')
    await fixNow(page, ny('2026-11-23T10:06'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await ui.expectRow('QRTV', {
      optionDelta: '+30.21', stockDelta: '0.00', netDelta: '+30.21', price: '52.40', exposure: '+1,583.00',
      gamma: m('-5.12'), theta: '+4.56', vega: m('-7.11'), excluded: NO_QUOTE_1,
    })
    await ui.expectPortfolio({ exposure: '+6,571.82', theta: '+7.03', vega: m('-12.14'), excluded: NO_QUOTE_1 })

    await apiClose('BTC', 'IXQZ', '65', 'CALL', 1, '85.00', '2026-11-23T10:04')
    await apiSellShares('IXQZ', 100, '6110.00', '2026-11-23T10:04')
    await ui.open()
    await ui.showGreeks()
    await expect(ui.rows).toHaveCount(1)
    await expect(ui.row('IXQZ')).toHaveCount(0)
    await ui.expectPortfolio({ exposure: '+1,583.00', theta: '+4.56', vega: m('-7.11'), excluded: NO_QUOTE_1 })
  })

  test('AC10 到期过滤选 今天到期（没有符合的期权行）→ 汇总与 AC1–3 相同（不随过滤变化）', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await ui.expectPortfolio(PF_A)

    await ui.id('positions-expiry-filter-today').click()
    await expect(ui.id('positions-expiry-filter')).toHaveAttribute('data-value', 'today')
    await expect(ui.posRow(P1)).toHaveCount(0)
    await ui.expectRow('QRTV', QRTV_A)
    await ui.expectRow('IXQZ', IXQZ_A)
    await ui.expectPortfolio(PF_A)
  })

  test('后端汇总接口报错：区块显示错误 + 重新加载汇总，持仓表照常；恢复后重试显示汇总', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    await page.route('**/api/positions/greeks-summary', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/positions/greeks-summary', timestamp: new Date().toISOString(), details: [] }),
      }),
    )
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await expect(ui.id('greeks-summary-error')).toBeVisible({ timeout: 30_000 })
    await expect(ui.id('greeks-summary-error')).toContainText('INTERNAL_ERROR')
    await expect(ui.rows).toHaveCount(0)
    await expect(ui.posRow(P1).getByTestId('position-greeks-delta-position')).toHaveText('+30.21')

    await page.unroute('**/api/positions/greeks-summary')
    await ui.id('greeks-summary-retry').click()
    await expect(ui.id('greeks-summary-error')).toHaveCount(0)
    await ui.expectPortfolio(PF_A)
  })
})
