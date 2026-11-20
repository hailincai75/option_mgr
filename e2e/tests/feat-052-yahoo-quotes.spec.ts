import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-052 行情数据源改用 Yahoo Finance E2E (fake Yahoo / Finnhub / CBOE via the e2e test-support endpoints, fixed
 * backend clock + page.clock). The e2e profile defaults to option-source CBOE and r = 0.00: every test switches to
 * Yahoo explicitly with PUT /api/test-support/market-data/settings. The browser never talks to Yahoo / CBOE / Finnhub.
 *
 * Scenario (requirement; today 2026-11-20 Fri; r = 0.00 %; multiplier 100; fictional tickers):
 *   Account A (default, id 1): P1 STO 1 QRTV 2027-02-19 50P (net 380.00); P2 STO 1 QRTV 2026-12-18 45P (net 70.00).
 *   Account B: P3 BTO 2 QRTV 2027-02-19 50C (net 800.00); 100 IXQZ shares (cost 6000.00), no IXQZ options.
 *   Fake Yahoo (shape of the real /v7/finance/options JSON): QRTV 50.00 at 11-20 10:00; 2027-02-19 chain 50P 3.90 /
 *   4.10 / last 3.95 / IV 0.30, 50C 4.00 / 4.20 / IV 0.30; 2026-12-18 chain 45P 0.60 / 0.70 / IV 0.35; IXQZ 61.10.
 *
 * Test ids: no new ids (docs/architecture.md "Yahoo 行情（FEAT-052）"): FEAT-016 position-row[data-contract] cells
 * position-underlying-price[data-source], position-bid-ask, position-mark, position-quote-time, position-mark-stale,
 * position-mark-status, stock-position-row / stock-position-price, market-data-stale-warning; FEAT-017
 * position-market-value / -unrealized-pnl / -unrealized-pnl-percent; FEAT-018 positions-greeks-toggle,
 * positions-greeks-note (text by data source), position-greeks-iv / -{key} / -{key}-position / -{key}-per-contract,
 * position-greeks-manual-note; FEAT-019 greeks-summary-row[data-symbol] cells.
 */

const P1 = 'QRTV 2027-02-19 50 P'
const P2 = 'QRTV 2026-12-18 45 P'
const P3 = 'QRTV 2027-02-19 50 C'
const P4 = 'QRTV 2027-02-19 45 P'
const FEB = '2027-02-19'
const DEC = '2026-12-18'
const NO_QUOTE = '无报价'
const ACCOUNT_B = '账户B'
const DASH = '—'
const KEYS = ['delta', 'gamma', 'theta', 'vega'] as const
type Key = (typeof KEYS)[number]
const yahooNote = (r: string): string =>
  `IV 来自 Yahoo Finance 延迟报价，Greeks 由系统按 Black-Scholes 计算（无风险利率 ${r}%），与券商数值可能略有差异；持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考`
const CBOE_NOTE = 'IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考'
const STALE_1000 = '行情未更新（上次：11-20 10:00）'

const ny = (local: string): string => nyInstant(local)
const m = (s: string): string => s.replace(/^-/, MINUS)
const epoch = (local: string): number => Date.parse(ny(local)) / 1000
/** The Yahoo `date` / `expirationDates` value: 00:00 UTC of the day. */
const dateSec = (iso: string): number => Date.parse(`${iso}T00:00:00Z`) / 1000

// ------------------------------------------------------------------------------------------------ fake sources

type Json = Record<string, unknown>
type Reply = { httpStatus: number; body: unknown } | { httpStatus: number; rawBody: string } | { httpStatus: number } | { unreachable: true }

/** One Yahoo contract (real field set); a null bid / ask / last / iv is omitted. */
function yc(occ: string, bid: number | null, ask: number | null, last: number | null, iv: number | null): Json {
  const tail = occ.slice(-15)
  const c: Json = {
    contractSymbol: occ,
    strike: Number(tail.slice(7)) / 1000,
    currency: 'USD',
    change: 0,
    percentChange: 0,
    volume: 12,
    openInterest: 340,
    contractSize: 'REGULAR',
    expiration: dateSec(`20${tail.slice(0, 2)}-${tail.slice(2, 4)}-${tail.slice(4, 6)}`),
    lastTradeDate: epoch('2026-11-19T15:58'),
    inTheMoney: false,
  }
  if (bid !== null) c.bid = bid
  if (ask !== null) c.ask = ask
  if (last !== null) c.lastPrice = last
  if (iv !== null) c.impliedVolatility = iv
  return c
}

const QRTV_EXPS = ['2026-11-27', DEC, '2027-01-15', FEB]

/** Whole body {optionChain:{result:[…], error:null}} for one expiration. */
function yBody(symbol: string, price: number, local: string, chainDate: string | null, contracts: Json[], exps = QRTV_EXPS): Reply {
  const calls = contracts.filter((c) => String(c.contractSymbol).slice(-9, -8) === 'C')
  const puts = contracts.filter((c) => String(c.contractSymbol).slice(-9, -8) === 'P')
  return {
    httpStatus: 200,
    body: {
      optionChain: {
        result: [{
          underlyingSymbol: symbol,
          expirationDates: exps.map(dateSec),
          strikes: contracts.map((c) => c.strike),
          hasMiniOptions: false,
          quote: {
            language: 'en-US', region: 'US', quoteType: 'EQUITY', currency: 'USD', marketState: 'REGULAR', symbol,
            regularMarketPrice: price, regularMarketTime: epoch(local), regularMarketPreviousClose: 49.6,
            bid: Math.round((price - 0.02) * 100) / 100, ask: Math.round((price + 0.02) * 100) / 100,
          },
          options: chainDate === null ? [] : [{ expirationDate: dateSec(chainDate), hasMiniOptions: false, calls, puts }],
        }],
        error: null,
      },
    },
  }
}

const p1Y = (): Json => yc('QRTV270219P00050000', 3.9, 4.1, 3.95, 0.3)
const p3Y = (): Json => yc('QRTV270219C00050000', 4.0, 4.2, 4.1, 0.3)
const p2Y = (): Json => yc('QRTV261218P00045000', 0.6, 0.7, 0.62, 0.35)
const feb = (local = '2026-11-20T10:00', contracts: Json[] = [p1Y(), p3Y()], price = 50): Reply => yBody('QRTV', price, local, FEB, contracts)
const dec = (local = '2026-11-20T10:00', contracts: Json[] = [p2Y()], price = 50): Reply => yBody('QRTV', price, local, DEC, contracts)
const ixqz = (local = '2026-11-20T10:00', price = 61.1): Reply =>
  yBody('IXQZ', price, local, '2026-11-27', [yc('IXQZ261127C00060000', 1.5, 1.6, 1.55, 0.28)], ['2026-11-27', DEC])
const finnhub = (c: number, local: string): Reply => ({ httpStatus: 200, body: { c, pc: 49.6, t: epoch(local) } })

interface YSymbol {
  default?: Reply | Reply[]
  byDate?: Record<string, Reply | Reply[]>
}

async function setSources(s: {
  yahoo?: { handshake?: Reply | Reply[]; symbols?: Record<string, YSymbol> }
  cboe?: Record<string, Reply>
  finnhub?: Record<string, Reply>
  finnhubKeyPresent?: boolean
}): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/sources`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      finnhubKeyPresent: s.finnhubKeyPresent ?? true,
      cboe: s.cboe ?? {},
      finnhub: s.finnhub ?? {},
      yahoo: s.yahoo ?? {},
    }),
  })
  if (r.status !== 204) throw new Error(`PUT sources: ${r.status} ${await r.text()}`)
}

const crumb: Reply = { httpStatus: 200, rawBody: 'AbCdEfGhIjK' }
const scenarioYahoo = (): Promise<void> =>
  setSources({ yahoo: { handshake: crumb, symbols: { QRTV: { byDate: { [FEB]: feb(), [DEC]: dec() } }, IXQZ: { default: ixqz() } } } })

async function settings(optionSource: string | null, riskFreeRatePercent: string | null): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ optionSource, riskFreeRatePercent }),
  })
  if (r.status !== 200) throw new Error(`PUT settings: ${r.status} ${await r.text()}`)
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

/** Requirement scenario (data only), Yahoo selected (r 0.00 unless given). Backend clock left at 11-20 09:50. */
async function scenario(rate = '0.00'): Promise<number> {
  await settings('YAHOO', rate)
  await setBackendNow(ny('2026-11-20T09:50'))
  setApiAccount(1)
  await apiTransfer('DEPOSIT', '20000.00', '2026-11-16T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: FEB, strike: '50', type: 'PUT', netAmount: '380.00', time: '2026-11-19T10:00' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: DEC, strike: '45', type: 'PUT', netAmount: '70.00', time: '2026-11-19T10:05' })
  const b = await apiCreateAccount(ACCOUNT_B)
  setApiAccount(b)
  await apiTransfer('DEPOSIT', '20000.00', '2026-11-16T10:00')
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: FEB, strike: '50', type: 'CALL', contracts: 2, netAmount: '800.00', time: '2026-11-19T10:10' })
  await apiTransferInShares('IXQZ', 100, '2026-11-18', '6000.00')
  setApiAccount(1)
  return b
}

/** Scenario + fake Yahoo + the 10:00 pull. */
async function scenarioPulled(rate = '0.00'): Promise<number> {
  const b = await scenario(rate)
  await scenarioYahoo()
  await tickAt('2026-11-20T10:00')
  return b
}

// ------------------------------------------------------------------------------------------------ page object

/** Expected Greeks of one row as backend strings ("-0.4701"); the page shows U+2212 and an explicit + on positions. */
interface G {
  iv: string
  delta: [string, string]
  gamma: [string, string]
  theta: [string, string]
  vega: [string, string]
}

const signed = (s: string): string => (s.startsWith('-') ? m(s) : s === '0.00' ? s : `+${s}`)

const G_P1: G = { iv: '30.00', delta: ['47.01', '-0.4701'], gamma: ['-5.30', '0.0530'], theta: ['1.63', '-0.0163'], vega: ['-9.95', '0.0995'] }
const G_P2: G = { iv: '35.00', delta: ['12.91', '-0.1291'], gamma: ['-4.32', '0.0432'], theta: ['1.81', '-0.0181'], vega: ['-2.93', '0.0293'] }
const G_P3: G = { iv: '30.00', delta: ['105.98', '0.5299'], gamma: ['10.61', '0.0530'], theta: ['-3.27', '-0.0163'], vega: ['19.89', '0.0995'] }

class Ui {
  readonly warning: Locator

  constructor(readonly page: Page) {
    this.warning = page.getByTestId('market-data-stale-warning')
  }

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  get toggle(): Locator {
    return this.id('positions-greeks-toggle')
  }

  get note(): Locator {
    return this.id('positions-greeks-note')
  }

  /** Positions page; waits until positions and the market-data status have loaded. */
  async open(): Promise<void> {
    const positions = this.page.waitForResponse((r) => r.url().includes('/api/option-positions'))
    const status = this.page.waitForResponse((r) => r.url().includes('/api/market-data/status'))
    await this.page.goto('/positions')
    await expect(this.id('positions-as-of')).toBeVisible()
    await positions
    await status
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

  stockRow(symbol: string): Locator {
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
  }

  /** 标的价, Bid / Ask, Mark, 报价时间 of a position row. */
  async expectQuote(contract: string, price: string, bidAsk: string, mark: string, time: string): Promise<void> {
    const r = this.row(contract)
    await expect(r.getByTestId('position-underlying-price')).toHaveText(price)
    await expect(r.getByTestId('position-bid-ask')).toHaveText(bidAsk)
    await expect(r.getByTestId('position-mark')).toHaveText(mark)
    await expect(r.getByTestId('position-quote-time')).toHaveText(time)
  }

  async expectPnl(contract: string, mv: string, pnl: string, pct: string): Promise<void> {
    const r = this.row(contract)
    await expect(r.getByTestId('position-market-value')).toHaveText(mv)
    await expect(r.getByTestId('position-unrealized-pnl')).toHaveText(pnl)
    await expect(r.getByTestId('position-unrealized-pnl-percent')).toHaveText(pct)
  }

  async expectGreeks(contract: string, g: G): Promise<void> {
    const r = this.row(contract)
    await expect(r.getByTestId('position-greeks-iv')).toHaveText(g.iv === DASH ? DASH : `${g.iv}%`)
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

  async expectGreeksDash(contract: string, iv: string): Promise<void> {
    await this.expectGreeks(contract, { iv, delta: [DASH, DASH], gamma: [DASH, DASH], theta: [DASH, DASH], vega: [DASH, DASH] })
  }

  greek(contract: string, k: Key, part: 'position' | 'per-contract'): Locator {
    return this.row(contract).getByTestId(`position-greeks-${k}-${part}`)
  }

  summaryRow(symbol: string): Locator {
    return this.id('greeks-summary').locator(`[data-testid="greeks-summary-row"][data-symbol="${symbol}"]`)
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

test.describe('FEAT-052 Yahoo 行情 + 后端 Greeks', () => {
  test('AC1 / AC3 账户 A：P1 50.00、3.90 / 4.10、4.00、11-20 10:00（标的价来源 YAHOO 无悬停）；P2 0.65；未实现盈亏；显示 Greeks → 说明含 Black-Scholes 与 无风险利率 0.00%，P1 IV 30.00% / +47.01 / −5.30 / +1.63 / −9.95', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-20T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await ui.expectQuote(P1, '50.00', '3.90 / 4.10', '4.00', '11-20 10:00')
    await ui.expectQuote(P2, '50.00', '0.60 / 0.70', '0.65', '11-20 10:00')
    await expect(ui.row(P1).getByTestId('position-underlying-price')).toHaveAttribute('data-source', 'YAHOO')
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveCount(0)
    await expect(ui.warning).toHaveCount(0)
    await ui.row(P1).getByTestId('position-underlying-price').hover()
    await page.waitForTimeout(500)
    await expect(page.locator('.ant-tooltip:not(.ant-tooltip-hidden)').getByText(/来源/)).toHaveCount(0)
    await page.mouse.move(0, 0)

    await ui.expectPnl(P1, `${MINUS}400.00`, `${MINUS}20.00`, `${MINUS}5.26%`)
    await ui.expectPnl(P2, `${MINUS}65.00`, '+5.00', '+7.14%')

    await ui.showGreeks()
    await expect(ui.note).toHaveText(yahooNote('0.00'))
    await ui.expectGreeks(P1, G_P1)
    await ui.expectGreeks(P2, G_P2)
    await expect(ui.row(P1).getByTestId('position-greeks-manual-note')).toHaveCount(0)
  })

  test('AC4 账户 B：P3 Mark 4.10、Delta +105.98（0.5299）/ +10.61 / −3.27 / +19.89；IXQZ 现价 61.10；按标的 Greeks 汇总 QRTV 期权 Delta +105.98、标的价 50.00、敞口 +5,299.00', async ({ page }) => {
    const b = await scenarioPulled()
    await fixNow(page, ny('2026-11-20T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.switchAccount(b, ACCOUNT_B)

    await ui.expectQuote(P3, '50.00', '4.00 / 4.20', '4.10', '11-20 10:00')
    await ui.expectPnl(P3, '+820.00', '+20.00', '+2.50%')
    await expect(ui.stockRow('IXQZ').getByTestId('stock-position-price')).toHaveText('61.10')
    await expect(ui.stockRow('IXQZ').getByTestId('stock-position-quote-time')).toHaveText('11-20 10:00')

    await ui.showGreeks()
    await ui.expectGreeks(P3, G_P3)
    const q = ui.summaryRow('QRTV')
    await expect(q.getByTestId('greeks-summary-option-delta')).toHaveText('+105.98')
    await expect(q.getByTestId('greeks-summary-stock-delta')).toHaveText('0.00')
    await expect(q.getByTestId('greeks-summary-net-delta')).toHaveText('+105.98')
    await expect(q.getByTestId('greeks-summary-underlying-price')).toHaveText('50.00')
    await expect(q.getByTestId('greeks-summary-exposure')).toHaveText('+5,299.00')
    await expect(q.getByTestId('greeks-summary-gamma')).toHaveText('+10.61')
    await expect(q.getByTestId('greeks-summary-theta')).toHaveText(m('-3.27'))
    await expect(q.getByTestId('greeks-summary-vega')).toHaveText('+19.89')
  })

  test('AC5 无风险利率 5.00%：说明显示 无风险利率 5.00%，P1 每张 Delta −0.4371、持仓值 +43.71', async ({ page }) => {
    await scenarioPulled('5.00')
    await fixNow(page, ny('2026-11-20T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.showGreeks()
    await expect(ui.note).toHaveText(yahooNote('5.00'))
    await expect(ui.greek(P1, 'delta', 'per-contract')).toHaveText(m('-0.4371'))
    await expect(ui.greek(P1, 'delta', 'position')).toHaveText('+43.71')
  })

  test('AC6 16:20 收盘后 bid / ask 为 0、IV 0.00001、标的价 50.30：P1 仍 4.00、IV 30.00%、Delta +47.01、11-20 10:00，无失败标签；标的价 50.30；15:00 新开 P4，16:20 只有 last 1.10 / IV 0 → Mark 1.10、Greeks 各列 —', async ({ page }) => {
    await scenarioPulled()
    await setBackendNow(ny('2026-11-20T15:00'))
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: FEB, strike: '45', type: 'PUT', netAmount: '105.00', time: '2026-11-20T15:00' })
    await setSources({
      yahoo: {
        handshake: crumb,
        symbols: {
          QRTV: {
            byDate: {
              [FEB]: feb('2026-11-20T16:00', [yc('QRTV270219P00050000', 0, 0, 3.95, 0.00001), yc('QRTV270219C00050000', 0, 0, 4.1, 0.00001), yc('QRTV270219P00045000', 0, 0, 1.1, 0)], 50.3),
              [DEC]: dec('2026-11-20T16:00', [yc('QRTV261218P00045000', 0, 0, 0.62, 0)], 50.3),
            },
          },
          IXQZ: { default: ixqz('2026-11-20T16:00', 61.4) },
        },
      },
    })
    await tickAt('2026-11-20T16:20')
    await fixNow(page, ny('2026-11-20T16:25'))
    const ui = new Ui(page)
    await ui.open()

    await ui.expectQuote(P1, '50.30', '3.90 / 4.10', '4.00', '11-20 10:00')
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveCount(0)
    await expect(ui.row(P1).getByTestId('position-mark-status')).toHaveCount(0)
    await expect(ui.warning).toHaveCount(0)
    await ui.expectQuote(P4, '50.30', '0.00 / 0.00', '1.10', '11-20 16:00')
    await ui.expectPnl(P4, `${MINUS}110.00`, `${MINUS}5.00`, `${MINUS}4.76%`)

    await ui.showGreeks()
    await ui.expectGreeks(P1, G_P1)
    await ui.expectGreeksDash(P4, '0.00')
  })

  test('AC6（Q271）P4 last 也为 0 → Mark 无报价，Greeks 五列 无报价', async ({ page }) => {
    await scenarioPulled()
    await setBackendNow(ny('2026-11-20T15:00'))
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: FEB, strike: '45', type: 'PUT', netAmount: '105.00', time: '2026-11-20T15:00' })
    await setSources({
      yahoo: {
        handshake: crumb,
        symbols: {
          QRTV: { byDate: { [FEB]: feb('2026-11-20T16:00', [p1Y(), p3Y(), yc('QRTV270219P00045000', 0, 0, 0, 0)], 50.3), [DEC]: dec('2026-11-20T16:00') } },
          IXQZ: { default: ixqz('2026-11-20T16:00') },
        },
      },
    })
    await tickAt('2026-11-20T16:20')
    await fixNow(page, ny('2026-11-20T16:25'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.row(P4).getByTestId('position-mark')).toHaveText(NO_QUOTE)
    await expect(ui.row(P4).getByTestId('position-mark-stale')).toHaveCount(0)
    await ui.showGreeks()
    for (const k of ['iv', ...KEYS]) {
      await expect(ui.row(P4).getByTestId(`position-greeks-${k}`)).toHaveText(NO_QUOTE)
    }
  })

  test('AC7 11:00 QRTV 返回 403、Finnhub 50.20：标的价 50.20（悬停 来源 Finnhub），P1、P2 保留 10:00 的值并标 行情未更新（上次：11-20 10:00）；B 的 P3 同样', async ({ page }) => {
    const b = await scenarioPulled()
    await setSources({
      yahoo: { handshake: crumb, symbols: { QRTV: { default: { httpStatus: 403 } }, IXQZ: { default: ixqz() } } },
      finnhub: { QRTV: finnhub(50.2, '2026-11-20T10:59') },
    })
    await tickAt('2026-11-20T11:00')
    await fixNow(page, ny('2026-11-20T11:05'))
    const ui = new Ui(page)
    await ui.open()

    const price = ui.row(P1).getByTestId('position-underlying-price')
    await expect(price).toHaveText('50.20')
    await expect(price).toHaveAttribute('data-source', 'FINNHUB')
    await price.hover()
    await expect(page.locator('.ant-tooltip:not(.ant-tooltip-hidden)').getByText('来源 Finnhub')).toBeVisible()
    await page.mouse.move(0, 0)
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('4.00')
    await expect(ui.row(P1).getByTestId('position-quote-time')).toHaveText('11-20 10:00')
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveText(STALE_1000)
    await expect(ui.row(P2).getByTestId('position-mark')).toHaveText('0.65')
    await expect(ui.row(P2).getByTestId('position-mark-stale')).toHaveText(STALE_1000)
    await ui.showGreeks()
    await ui.expectGreeks(P1, G_P1)

    await ui.switchAccount(b, ACCOUNT_B)
    await expect(ui.row(P3).getByTestId('position-mark')).toHaveText('4.10')
    await expect(ui.row(P3).getByTestId('position-mark-stale')).toHaveText(STALE_1000)
  })

  test('AC7 11:00 起会话握手持续失败：11:30 无顶部提示，11:31（90 分钟）顶部 行情未更新（上次：2026-11-20 10:00 美东）', async ({ page }) => {
    await scenarioPulled()
    await setSources({
      yahoo: { handshake: { httpStatus: 401 }, symbols: { QRTV: { byDate: { [FEB]: feb(), [DEC]: dec() } }, IXQZ: { default: ixqz() } } },
      finnhub: { QRTV: finnhub(50.2, '2026-11-20T10:59'), IXQZ: finnhub(61.0, '2026-11-20T10:59') },
    })
    await tickAt('2026-11-20T11:00')
    await fixNow(page, ny('2026-11-20T11:30'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveText(STALE_1000)
    await expect(ui.row(P1).getByTestId('position-underlying-price')).toHaveText('50.20')
    await expect(ui.warning).toHaveCount(0)

    await fixNow(page, ny('2026-11-20T11:31'))
    await ui.open()
    await expect(ui.warning).toHaveText('行情未更新（上次：2026-11-20 10:00 美东）')
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('4.00')
  })

  test('AC8 11:00 拉取成功但 2026-12-18 响应中没有 P2 → P2 Mark 0.65，标 数据源已无该合约（最后报价：11-20 10:00）', async ({ page }) => {
    await scenarioPulled()
    await setSources({
      yahoo: {
        handshake: crumb,
        symbols: {
          QRTV: { byDate: { [FEB]: feb(), [DEC]: dec('2026-11-20T11:00', [yc('QRTV261218P00040000', 0.1, 0.15, 0.12, 0.4)], 50.1) } },
          IXQZ: { default: ixqz() },
        },
      },
    })
    await tickAt('2026-11-20T11:00')
    await fixNow(page, ny('2026-11-20T11:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.row(P2).getByTestId('position-mark')).toHaveText('0.65')
    await expect(ui.row(P2).getByTestId('position-mark-status')).toHaveText('数据源已无该合约（最后报价：11-20 10:00）')
    await expect(ui.row(P2).getByTestId('position-mark-stale')).toHaveCount(0)
  })

  test('AC9 页面上把 P1 Mark 手动设为 4.20：Greeks 不变，标注 Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整；说明含 Black-Scholes 和 无风险利率 0.00%', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-20T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.override(ui.row(P1).getByTestId('position-mark-set'), '4.20')
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('4.20')
    await expect(ui.row(P1).getByTestId('position-mark-manual')).toHaveText('手动 11-20 10:05')
    await ui.expectPnl(P1, `${MINUS}420.00`, `${MINUS}40.00`, `${MINUS}10.53%`)

    await ui.showGreeks()
    await ui.expectGreeks(P1, G_P1)
    await expect(ui.row(P1).getByTestId('position-greeks-manual-note')).toHaveText('Greeks 按 Yahoo 报价计算（11-20 10:00），未随手动 Mark 调整')
    await expect(ui.note).toContainText('Black-Scholes')
    await expect(ui.note).toContainText('无风险利率 0.00%')
  })

  test('AC10 升级前 P1 有 CBOE 报价 3.50，之后 Yahoo 不可达：P1 仍 3.50（CBOE 的 Greeks、CBOE 手动标注原文）并标 行情未更新；录入交易照常保存', async ({ page, trade }) => {
    await settings('CBOE', null)
    await setBackendNow(ny('2026-11-20T09:50'))
    setApiAccount(1)
    await apiTransfer('DEPOSIT', '20000.00', '2026-11-16T10:00')
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: FEB, strike: '50', type: 'PUT', netAmount: '380.00', time: '2026-11-19T10:00' })
    await setSources({
      cboe: {
        QRTV: {
          httpStatus: 200,
          body: {
            timestamp: '2026-11-20 09:45:00',
            data: {
              symbol: 'QRTV', current_price: 49.8, bid: null, ask: null, prev_day_close: 49.6,
              options: [{ option: 'QRTV270219P00050000', bid: 3.4, ask: 3.6, theo: 3.48, iv: 0.295, delta: -0.46, gamma: 0.052, theta: -0.017, vega: 0.098, rho: -0.05 }],
            },
          },
        },
      },
    })
    await tickAt('2026-11-20T10:00')

    // "upgrade": Yahoo is the source now, unreachable (handshake fails), no Finnhub key
    await settings('YAHOO', null)
    await setSources({ finnhubKeyPresent: false, yahoo: { handshake: { unreachable: true } } })
    await tickAt('2026-11-20T11:00')
    await fixNow(page, ny('2026-11-20T11:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.row(P1).getByTestId('position-mark')).toHaveText('3.50')
    await expect(ui.row(P1).getByTestId('position-bid-ask')).toHaveText('3.40 / 3.60')
    await expect(ui.row(P1).getByTestId('position-quote-time')).toHaveText('11-20 09:45')
    await expect(ui.row(P1).getByTestId('position-mark-stale')).toHaveText(STALE_1000)
    await ui.showGreeks()
    await expect(ui.note).toHaveText(yahooNote('0.00'))
    await ui.expectGreeks(P1, { iv: '29.50', delta: ['46.00', '-0.4600'], gamma: ['-5.20', '0.0520'], theta: ['1.70', '-0.0170'], vega: ['-9.80', '0.0980'] })
    await ui.override(ui.row(P1).getByTestId('position-mark-set'), '3.70')
    await expect(ui.row(P1).getByTestId('position-greeks-manual-note')).toHaveText('Greeks 按 CBOE 报价（11-20 09:45），未随手动 Mark 调整')

    await fixNow(page, ny('2026-11-20T11:10'))
    await trade.open()
    await trade.submitAndExpectSaved({
      action: 'STO', symbol: 'QRTV', expiration: '2027-01-15', strike: '45', type: 'PUT', contracts: '1', netAmount: '90.00', time: '2026-11-20T11:08', multiplier: '100',
    })
    await ui.open()
    await expect(ui.row('QRTV 2027-01-15 45 P').getByTestId('position-mark')).toHaveText(NO_QUOTE)
  })

  test('AC10 数据源改为 CBOE：行为同 FEAT-016 / 018（CBOE 原值 Greeks，说明为 CBOE 原文，标的价来源 CBOE）', async ({ page }) => {
    await scenario()
    await settings('CBOE', null)
    await setSources({
      cboe: {
        QRTV: {
          httpStatus: 200,
          body: {
            timestamp: '2026-11-20 09:45:00',
            data: { symbol: 'QRTV', current_price: 49.8, bid: null, ask: null, prev_day_close: 49.6, options: [{ option: 'QRTV270219P00050000', bid: 3.4, ask: 3.6, theo: 3.48, iv: 0.295, delta: -0.46, gamma: 0.052, theta: -0.017, vega: 0.098 }] },
          },
        },
      },
      yahoo: { handshake: crumb, symbols: { QRTV: { byDate: { [FEB]: feb(), [DEC]: dec() } } } },
    })
    await tickAt('2026-11-20T10:00')
    await fixNow(page, ny('2026-11-20T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.expectQuote(P1, '49.80', '3.40 / 3.60', '3.50', '11-20 09:45')
    await expect(ui.row(P1).getByTestId('position-underlying-price')).toHaveAttribute('data-source', 'CBOE')
    await expect(ui.row(P2).getByTestId('position-mark')).toHaveText(NO_QUOTE)
    await ui.showGreeks()
    await expect(ui.note).toHaveText(CBOE_NOTE)
    await ui.expectGreeks(P1, { iv: '29.50', delta: ['46.00', '-0.4600'], gamma: ['-5.20', '0.0520'], theta: ['1.70', '-0.0170'], vega: ['-9.80', '0.0980'] })
  })

  test('行情状态接口报错 → Greeks 说明仍按 Yahoo 文字、利率显示 —；Greeks 数值照常', async ({ page }) => {
    await scenarioPulled()
    await fixNow(page, ny('2026-11-20T10:05'))
    await page.route('**/api/market-data/status', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/market-data/status', timestamp: new Date().toISOString(), details: [] }),
      }),
    )
    const ui = new Ui(page)
    const positions = page.waitForResponse((r) => r.url().includes('/api/option-positions'))
    await page.goto('/positions')
    await positions
    await ui.showGreeks()
    await expect(ui.note).toContainText('Black-Scholes')
    await expect(ui.note).toContainText('无风险利率 —')
    await ui.expectGreeks(P1, G_P1)
    await page.unroute('**/api/market-data/status')
  })
})
