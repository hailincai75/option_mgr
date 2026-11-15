import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-018 单合约 Greeks E2E (fake CBOE via the e2e test-support endpoints, fixed backend clock + page.clock).
 *
 * Scenario (requirement; today 2026-11-23 Mon, the 10:00 pull succeeds with CBOE snapshot 09:45; multiplier 100;
 * fictional tickers):
 *   QRTV 2026-12-18 50P 1.20 / 1.30 iv 0.3512 delta −0.3021 gamma 0.0512 theta −0.0456 vega 0.0711;
 *   QRTV 2026-12-18 55C 1.80 / 1.90 iv 0.3320 delta 0.2850 gamma 0.0480 theta −0.0390 vega 0.0650;
 *   IXQZ 2026-12-18 65C 0.80 / 0.90 iv 0.2900 delta 0.18345 gamma 0.03125 theta −0.0247 vega 0.0503.
 *   Account A (default, id 1): P1 STO 1 QRTV 50P; P2 BTO 2 QRTV 55C; P3 STO 1 IXQZ 65C (covered by 100 IXQZ shares);
 *   P4 STO 1 QRTV 2026-12-18 47.5P (never in the chain). Account B: P5 STO 3 QRTV 50P.
 *
 * Test ids (frontend FEAT-018, components/greeks/GreeksCells.tsx — to be confirmed against docs/architecture.md):
 *   positions-greeks-toggle (antd Switch, aria-checked), positions-greeks-note;
 *   column header greeks-header-{iv|delta|gamma|theta|vega} with the `?` icon greeks-header-{key}-help (tooltip);
 *   row cells position-greeks-iv, position-greeks-{delta|gamma|theta|vega} (data-status = greeks.status) with
 *   position-greeks-{key}-position (explicit sign, U+2212) and position-greeks-{key}-per-contract (U+2212, no +);
 *   position-greeks-manual-note.
 */

const P1 = 'QRTV 2026-12-18 50 P'
const P2 = 'QRTV 2026-12-18 55 C'
const P3 = 'IXQZ 2026-12-18 65 C'
const P4 = 'QRTV 2026-12-18 47.5 P'
const P5 = P1
const P6 = 'QRTV 2026-11-20 45 P'
const P7 = 'QRTV 2026-11-23 52 P'
const NO_QUOTE = '无报价'
const EXPIRED_LABEL = '已到期，待处理'
const ACCOUNT_B = '账户B'
const DASH = '—'
const NOTE = 'IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考'
const KEYS = ['delta', 'gamma', 'theta', 'vega'] as const
type Key = (typeof KEYS)[number]

const ny = (local: string): string => nyInstant(local)
const m = (s: string): string => s.replace(/^-/, MINUS)

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

/** Expected Greeks of one row, as the backend strings ("-0.3021"); the page shows U+2212 and an explicit + on positions. */
interface G {
  iv: string
  delta: [string, string]
  gamma: [string, string]
  theta: [string, string]
  vega: [string, string]
}

const signed = (s: string): string => (s.startsWith('-') ? m(s) : s === '0.00' ? s : `+${s}`)

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  get toggle(): Locator {
    return this.id('positions-greeks-toggle')
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
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  /** Header texts of the option positions table, in order. */
  async headers(): Promise<string[]> {
    const ths = this.id('positions-page').locator('thead th')
    return (await ths.allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim())
  }

  async expectGreeks(contract: string, g: G): Promise<void> {
    const r = this.row(contract)
    await expect(r.getByTestId('position-greeks-iv')).toHaveText(g.iv === DASH ? DASH : `${g.iv}%`)
    await expect(r.getByTestId('position-greeks-iv')).toHaveAttribute('data-status', 'OK')
    for (const k of KEYS) {
      const [pos, per] = g[k]
      const cell = r.getByTestId(`position-greeks-${k}`)
      await expect(cell).toHaveAttribute('data-status', 'OK')
      if (pos === DASH) {
        await expect(cell).toContainText(DASH)
        await expect(cell).not.toContainText(/\d/)
      } else {
        await expect(cell.getByTestId(`position-greeks-${k}-position`)).toHaveText(signed(pos))
        await expect(cell.getByTestId(`position-greeks-${k}-per-contract`)).toHaveText(m(per))
      }
    }
  }

  /** All five Greeks cells show `text` (backend label, or — for a response without greeks), no numbers. */
  async expectAllCells(contract: string, text: string, status?: string): Promise<void> {
    const r = this.row(contract)
    for (const k of ['iv', ...KEYS]) {
      const cell = r.getByTestId(`position-greeks-${k}`)
      await expect(cell).toHaveText(text)
      if (status !== undefined) await expect(cell).toHaveAttribute('data-status', status)
    }
    await expect(r.getByTestId('position-greeks-delta-position')).toHaveCount(0)
  }

  /** FEAT-017 summary of account A exactly as before FEAT-018 (AC4). */
  async expectSummaryUnchanged(): Promise<void> {
    await expect(this.id('unrealized-summary-option-market-value')).toHaveText('+160.00')
    await expect(this.id('unrealized-summary-option-unrealized-pnl')).toHaveText('+61.75')
    await expect(this.id('unrealized-summary-total-market-value')).toHaveText('+6,270.00')
    await expect(this.id('unrealized-summary-total-unrealized-pnl')).toHaveText('+291.75')
    await expect(this.id('unrealized-summary-excluded')).toHaveText('1 个持仓无报价，未计入')
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

const G_P1: G = { iv: '35.12', delta: ['30.21', '-0.3021'], gamma: ['-5.12', '0.0512'], theta: ['4.56', '-0.0456'], vega: ['-7.11', '0.0711'] }
const G_P2: G = { iv: '33.20', delta: ['57.00', '0.2850'], gamma: ['9.60', '0.0480'], theta: ['-7.80', '-0.0390'], vega: ['13.00', '0.0650'] }
const G_P3: G = { iv: '29.00', delta: ['-18.35', '0.1835'], gamma: ['-3.13', '0.0313'], theta: ['2.47', '-0.0247'], vega: ['-5.03', '0.0503'] }
const G_P5: G = { iv: '35.12', delta: ['90.63', '-0.3021'], gamma: ['-15.36', '0.0512'], theta: ['13.68', '-0.0456'], vega: ['-21.33', '0.0711'] }

test.afterEach(() => {
  setApiAccount(1)
})

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-018 单合约 Greeks', () => {
  test('AC10 显示 Greeks 默认关闭（无 Greeks 列、无说明）；打开后 未实现盈亏 之后五列 IV / Delta / Gamma / Theta / Vega、口径说明；无 Rho；关闭后恢复', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await expect(ui.toggle).toBeVisible()
    await expect(ui.toggle).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByText('显示 Greeks', { exact: true })).toBeVisible()
    const off = await ui.headers()
    for (const h of ['IV', 'Delta', 'Gamma', 'Theta', 'Vega', 'Rho']) expect(off).not.toContain(h)
    await expect(page.getByTestId('position-greeks-iv')).toHaveCount(0)
    await expect(page.getByTestId('positions-greeks-note')).toHaveCount(0)

    await ui.toggle.click()
    await expect(ui.toggle).toHaveAttribute('aria-checked', 'true')
    const on = await ui.headers()
    const i = on.findIndex((h) => h.startsWith('未实现盈亏'))
    expect(i, `headers: ${JSON.stringify(on)}`).toBeGreaterThanOrEqual(0)
    expect(on.slice(i + 1, i + 6).map((h) => h.replace(/\s*\?$/, ''))).toEqual(['IV', 'Delta', 'Gamma', 'Theta', 'Vega'])
    expect(on.some((h) => /rho/i.test(h))).toBe(false)
    for (const k of ['iv', ...KEYS]) await expect(ui.id(`greeks-header-${k}-help`).first()).toBeVisible()
    await expect(ui.id('positions-greeks-note')).toHaveText(NOTE)
    await expect(ui.row(P1).getByTestId('position-greeks-iv')).toHaveText('35.12%')

    await ui.toggle.click()
    await expect(ui.toggle).toHaveAttribute('aria-checked', 'false')
    await expect(page.getByTestId('position-greeks-iv')).toHaveCount(0)
    await expect(page.getByTestId('positions-greeks-note')).toHaveCount(0)
    expect(await ui.headers()).toEqual(off)
  })

  test('AC10 悬停 Theta 列头的 ? → 显示 Theta 的通俗解释（含 持仓值 = 每天大约赚 / 亏多少美元）；Delta 的 ? → 等价股数说明', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await ui.id('greeks-header-theta-help').first().hover()
    const tip = page.getByRole('tooltip').filter({ hasText: '持仓值 = 每天大约赚 / 亏多少美元' })
    await expect(tip).toBeVisible()
    await expect(tip).toContainText('过一天期权每股价格大约变多少')

    await page.mouse.move(0, 0)
    await ui.id('greeks-header-delta-help').first().hover()
    await expect(page.getByRole('tooltip').filter({ hasText: '相当于多少股标的' })).toBeVisible()
  })

  test('AC1 / AC2 / AC3 / AC4 账户 A：P1、P2、P3 的 IV / 持仓值（带符号）/ 每张原值；P4 五列 无报价；FEAT-017 合计不变，无 Greeks 合计', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.id('unrealized-summary-total-market-value')).toHaveText('+6,270.00')
    await ui.showGreeks()

    // AC1: IV 35.12%; Delta +30.21 (−0.3021); Gamma −5.12 (0.0512); Theta +4.56 (−0.0456); Vega −7.11 (0.0711)
    await ui.expectGreeks(P1, G_P1)
    await expect(ui.row(P1).getByTestId('position-greeks-delta-position')).toHaveText('+30.21')
    await expect(ui.row(P1).getByTestId('position-greeks-delta-per-contract')).toHaveText(`${MINUS}0.3021`)
    await expect(ui.row(P1).getByTestId('position-greeks-manual-note')).toHaveCount(0)
    // AC2
    await ui.expectGreeks(P2, G_P2)
    // AC3
    await ui.expectGreeks(P3, G_P3)
    // AC4
    await ui.expectAllCells(P4, NO_QUOTE, 'NO_QUOTE')
    await ui.expectSummaryUnchanged()
    // no Greeks totals inside the positions table / FEAT-017 summary (Q165; the per-symbol summary block is FEAT-019)
    await expect(page.getByTestId(/greeks-total/)).toHaveCount(0)
    await expect(ui.id('unrealized-summary-totals').getByTestId(/greeks/)).toHaveCount(0)
  })

  test('AC5 切换到账户 B：P5 35.12% / +90.63 / −15.36 / +13.68 / −21.33（每张同 P1）', async ({ page }) => {
    const b = await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await ui.expectGreeks(P1, G_P1)

    const positions = page.waitForResponse((r) => r.url().includes('/api/option-positions'))
    await ui.switchAccount(b, ACCOUNT_B)
    await positions
    await ui.showGreeks()
    await expect(page.locator('[data-testid="position-row"]')).toHaveCount(1)
    await ui.expectGreeks(P5, G_P5)
  })

  test('AC6 10:15 拉到新值 → P1 36.00% / +33.00 / −5.30 / +4.70 / −7.20；10:30 起 QRTV 失败（行情未更新（上次：11-23 10:15））→ 仍显示 10:15 的值', async ({ page }) => {
    await scenarioPulled()
    await setSources({
      QRTV: qrtv('2026-11-23 10:00:00', [greek('QRTV261218P00050000', 1.25, 1.35, 0.36, -0.33, 0.053, -0.047, 0.072), p2Opt()]),
      IXQZ: ixqz('2026-11-23 10:00:00'),
    })
    await tickAt('2026-11-23T10:15')
    await fixNow(page, ny('2026-11-23T10:16'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    const p1New: G = { iv: '36.00', delta: ['33.00', '-0.3300'], gamma: ['-5.30', '0.0530'], theta: ['4.70', '-0.0470'], vega: ['-7.20', '0.0720'] }
    await ui.expectGreeks(P1, p1New)

    await setSources({ QRTV: { httpStatus: 500, body: null }, IXQZ: ixqz('2026-11-23 10:15:00') })
    await tickAt('2026-11-23T10:30')
    await fixNow(page, ny('2026-11-23T10:31'))
    await ui.open()
    await ui.showGreeks()
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveText('行情未更新（上次：11-23 10:15）')
    await ui.expectGreeks(P1, p1New)
  })

  test('AC7 页面上手动设 P1 Mark 1.40 → Greeks 不变 + 标注 Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整；清除后标注消失；P4 手动 0.30 仍 无报价', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await ui.override(ui.row(P1).getByTestId('position-mark-set'), '1.40')
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('1.40')
    await expect(ui.row(P1).getByTestId('position-greeks-manual-note')).toHaveText(
      'Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整',
    )
    await ui.expectGreeks(P1, G_P1)

    await ui.row(P1).getByTestId('position-mark-clear').click()
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('1.25')
    await expect(ui.row(P1).getByTestId('position-greeks-manual-note')).toHaveCount(0)
    await ui.expectGreeks(P1, G_P1)

    await ui.override(ui.row(P4).getByTestId('position-mark-set'), '0.30')
    await expect(ui.row(P4).getByTestId('position-mark')).toHaveText('0.30')
    await ui.expectAllCells(P4, NO_QUOTE, 'NO_QUOTE')
    await expect(ui.row(P4).getByTestId('position-greeks-manual-note')).toHaveCount(0)
  })

  test('AC8 10:15 P2 的 gamma、vega 为 null → Gamma、Vega 两列显示 —，IV / Delta / Theta 正常', async ({ page }) => {
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
    await ui.expectGreeks(P2, { iv: '33.20', delta: ['57.00', '0.2850'], gamma: [DASH, DASH], theta: ['-7.80', '-0.0390'], vega: [DASH, DASH] })
    await ui.expectGreeks(P1, G_P1)
  })

  test('AC9 P6 已过期未处理 → Greeks 五列 已到期，待处理；P7 今天到期 → Delta +45.00（−0.4500）', async ({ page }) => {
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
    await scenarioPulled([greek('QRTV261123P00052000', 0.55, 0.65, 0.38, -0.45, 0.21, -0.15, 0.01)])
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()

    await expect(ui.row(P6).getByTestId('position-mark')).toHaveText('0.05')
    await ui.expectAllCells(P6, EXPIRED_LABEL, 'EXPIRED_PENDING')
    await expect(ui.row(P7).getByTestId('position-greeks-delta-position')).toHaveText('+45.00')
    await expect(ui.row(P7).getByTestId('position-greeks-delta-per-contract')).toHaveText(`${MINUS}0.4500`)
    await ui.expectGreeks(P7, { iv: '38.00', delta: ['45.00', '-0.4500'], gamma: ['-21.00', '0.2100'], theta: ['15.00', '-0.1500'], vega: ['-1.00', '0.0100'] })
  })

  test('边界：持仓值 0.00 无符号、每张 0.0000；IV 缺失显示 —（BTO 2 张 QRTV 30C：delta 1 / gamma 0 / theta −0.00005 / vega 0.00004）', async ({ page }) => {
    await scenario()
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '30', type: 'CALL', contracts: 2, netAmount: '4400.00', time: '2026-11-20T11:00' })
    await setSources({
      QRTV: qrtv('2026-11-23 09:45:00', [p1Opt(), p2Opt(), greek('QRTV261218C00030000', 22.3, 22.5, null, 1, 0, -0.00005, 0.00004)]),
      IXQZ: ixqz(),
    })
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await ui.expectGreeks('QRTV 2026-12-18 30 C', {
      iv: DASH, delta: ['200.00', '1.0000'], gamma: ['0.00', '0.0000'], theta: ['-0.01', '-0.0001'], vega: ['0.01', '0.0000'],
    })
  })

  test('边界：后端响应没有 greeks 字段（旧响应）→ 该行五列显示 —，其它行照常', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-23T10:05'))
    await page.route('**/api/option-positions**', async (route) => {
      const res = await route.fetch()
      const body = (await res.json()) as { positions: { contractLabel: string; greeks?: unknown }[] }
      for (const p of body.positions) if (p.contractLabel === P2) delete p.greeks
      await route.fulfill({ response: res, json: body })
    })
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await ui.expectAllCells(P2, DASH)
    await ui.expectGreeks(P1, G_P1)
  })
})
