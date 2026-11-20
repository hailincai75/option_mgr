import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-016 自动行情 E2E (fake CBOE / Finnhub via the e2e test-support endpoints, fixed backend clock + page.clock).
 *
 * Scenario (requirement): today 2026-11-23 (Mon). Account A (default, id 1): STO 1 QRTV 2026-12-18 50P (P1).
 * Account B: 100 IXQZ shares, STO 1 IXQZ 2026-12-18 60C (P2), STO 1 QRTV 2026-12-18 50P (P3). Fake CBOE: QRTV 52.40
 * at 09:45 with QRTV261218P00050000 1.20 / 1.30 (theo 1.24); IXQZ 61.10 at 09:45 with IXQZ261218C00060000 2.10 / 2.30.
 * Pulls are triggered with POST /api/test-support/market-data/tick at the backend clock (= the scheduled run).
 * Fictional tickers only. The browser never talks to CBOE / Finnhub (backend in-memory fake).
 *
 * Test ids: docs/architecture.md "FEAT-016 testid" (frontend):
 *   market-data-stale-warning (backend `warning`), market-data-refresh (+ market-data-refresh-wrapper for the tooltip
 *   "1 分钟内只能刷新一次"), market-data-refresh-result[data-kind];
 *   in position-row[data-contract]: position-underlying-price[data-source][data-manual], position-underlying-price-manual
 *   / -set / -clear / -stale, position-bid-ask, position-mark[data-contract-status][data-manual], position-mark-manual /
 *   -set / -clear / -status (contractStatusLabel) / -stale (chain staleLabel), position-quote-time;
 *   in stock-position-row[data-symbol]: stock-position-price, -price-stale, stock-position-quote-time;
 *   dialog manual-quote-modal / manual-quote-price / manual-quote-submit / manual-quote-error.
 * The refresh button is re-enabled by the 60-second status poll or a reload: after moving the test clock, reload.
 */

const CELL = {
  'underlying-price': 'position-underlying-price',
  'bid-ask': 'position-bid-ask',
  mark: 'position-mark',
  'quote-time': 'position-quote-time',
} as const

const P1 = 'QRTV 2026-12-18 50 P'
const P2 = 'IXQZ 2026-12-18 60 C'
const P3 = P1
const P4 = 'QRTV 2026-12-18 47.5 P'
const OCC_P1 = 'QRTV261218P00050000'
const OCC_P2 = 'IXQZ261218C00060000'
const NO_QUOTE = '无报价'
const ACCOUNT_B = '账户B'

/** UTC instant of a New York local time "YYYY-MM-DDTHH:mm[:ss]". */
function ny(local: string): string {
  const base = nyInstant(local.slice(0, 16))
  const sec = local.length > 16 ? Number(local.slice(17, 19)) : 0
  return new Date(Date.parse(base) + sec * 1000).toISOString()
}

// ------------------------------------------------------------------------------------------------ fake sources

type Opt = Record<string, number | string>
type SourceResponse = { httpStatus: number; body: unknown } | { httpStatus: number; rawBody: string } | { unreachable: true }

const option = (code: string, fields: Record<string, number>): Opt => ({ option: code, ...fields })
const p1Option = (bid = 1.2, ask = 1.3): Opt =>
  option(OCC_P1, { bid, ask, theo: 1.24, iv: 0.3512, delta: -0.3021, theta: -0.0456, volume: 120, open_interest: 3400 })
const p2Option = (): Opt => option(OCC_P2, { bid: 2.1, ask: 2.3 })

function cboe(symbol: string, price: number | null, timestamp: string, options: Opt[]): SourceResponse {
  return {
    httpStatus: 200,
    body: { timestamp, data: { symbol, current_price: price, bid: null, ask: null, prev_day_close: 50, options } },
  }
}
const qrtvOk = (): SourceResponse => cboe('QRTV', 52.4, '2026-11-23 09:45:00', [p1Option()])
const ixqzOk = (): SourceResponse => cboe('IXQZ', 61.1, '2026-11-23 09:45:00', [p2Option()])

async function setSources(s: {
  cboe?: Record<string, SourceResponse>
  finnhub?: Record<string, SourceResponse>
  finnhubKeyPresent?: boolean
}): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/sources`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ finnhubKeyPresent: s.finnhubKeyPresent ?? true, cboe: s.cboe ?? {}, finnhub: s.finnhub ?? {} }),
  })
  if (r.status !== 204) throw new Error(`PUT sources: ${r.status} ${await r.text()}`)
}

const scenarioSources = (): Promise<void> => setSources({ cboe: { QRTV: qrtvOk(), IXQZ: ixqzOk() } })

/** Backend clock to the New York time `local`, then the scheduled-timer simulation; must run. */
async function tickAt(local: string): Promise<void> {
  await setBackendNow(ny(local))
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/tick`, { method: 'POST' })
  if (r.status !== 200) throw new Error(`tick: ${r.status} ${await r.text()}`)
  const body = (await r.json()) as { ran: boolean }
  if (!body.ran) throw new Error(`tick at ${local} did not run: ${JSON.stringify(body)}`)
}

async function requests(): Promise<string[]> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/market-data/requests`)
  const body = (await r.json()) as { requests: { source: string; symbol: string }[] }
  return body.requests.map((q) => `${q.source} ${q.symbol}`)
}

async function clearRequests(): Promise<void> {
  await fetch(`${BACKEND_URL}/api/test-support/market-data/requests`, { method: 'DELETE' })
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
  await apiTransfer('DEPOSIT', '20000.00', '2026-11-16T10:00')
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-20T10:00' })
  const b = await apiCreateAccount(ACCOUNT_B)
  setApiAccount(b)
  await apiTransfer('DEPOSIT', '10000.00', '2026-11-16T10:00')
  await apiTransferInShares('IXQZ', 100, '2026-11-19', '6000.00')
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-18', strike: '60', type: 'CALL', netAmount: '150.00', time: '2026-11-20T10:05' })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-20T10:10' })
  setApiAccount(1)
  return b
}

// ------------------------------------------------------------------------------------------------ page object

class Ui {
  readonly warning: Locator
  readonly refresh: Locator

  constructor(readonly page: Page) {
    this.warning = page.getByTestId('market-data-stale-warning')
    this.refresh = page.getByTestId('market-data-refresh')
  }

  private statusResponse(): Promise<unknown> {
    return this.page.waitForResponse((r) => r.url().includes('/api/market-data/status'))
  }

  /** Positions page; waits until positions and the market-data status have loaded. */
  async open(): Promise<void> {
    const status = this.statusResponse()
    await this.page.goto('/positions')
    await expect(this.page.getByTestId('positions-as-of')).toBeVisible()
    await status
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.page.getByTestId('account-switcher').click()
    await this.page.getByTestId(`account-option-${id}`).click()
    await expect(this.page.getByTestId('account-current')).toHaveText(name)
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  cell(contract: string, name: 'underlying-price' | 'bid-ask' | 'mark' | 'quote-time'): Locator {
    return this.row(contract).getByTestId(CELL[name])
  }

  stockRow(symbol: string): Locator {
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
  }

  /** 标的价, Bid / Ask, Mark, 报价时间 of a position row. */
  async expectQuote(contract: string, price: string, bidAsk: string, mark: string, time: string): Promise<void> {
    await expect(this.cell(contract, 'underlying-price')).toHaveText(price)
    await expect(this.cell(contract, 'bid-ask')).toHaveText(bidAsk)
    await expect(this.cell(contract, 'mark')).toHaveText(mark)
    await expect(this.cell(contract, 'quote-time')).toHaveText(time)
  }

  async expectStock(symbol: string, price: string, time: string): Promise<void> {
    await expect(this.stockRow(symbol).getByTestId('stock-position-price')).toHaveText(price)
    await expect(this.stockRow(symbol).getByTestId('stock-position-quote-time')).toHaveText(time)
  }

  stale(contract: string): Locator {
    return this.row(contract).getByTestId('position-mark-stale')
  }

  /** Opens the override modal from `edit`, enters the price and saves; waits for the modal to close. */
  async override(edit: Locator, price: string): Promise<void> {
    await edit.click()
    const modal = this.page.getByTestId('manual-quote-modal')
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

test.describe('FEAT-016 自动行情', () => {
  test('AC1 10:00 拉取后：A 的 P1、B 的 P3 显示 52.40、1.20 / 1.30、1.25、11-23 09:45；P2 61.10、2.10 / 2.30、2.20；IXQZ 现价 61.10；无顶部提示', async ({ page }) => {
    const b = await scenario()
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)

    await ui.open()
    await ui.expectQuote(P1, '52.40', '1.20 / 1.30', '1.25', '11-23 09:45')
    await expect(ui.warning).toHaveCount(0)
    await expect(ui.stale(P1)).toHaveCount(0)
    // IV / Greeks not shown (FEAT-018)
    await expect(ui.row(P1)).not.toContainText('0.3512')
    await expect(ui.row(P1)).not.toContainText('-0.3021')

    await ui.switchAccount(b, ACCOUNT_B)
    await ui.expectQuote(P3, '52.40', '1.20 / 1.30', '1.25', '11-23 09:45')
    await ui.expectQuote(P2, '61.10', '2.10 / 2.30', '2.20', '11-23 09:45')
    await ui.expectStock('IXQZ', '61.10', '11-23 09:45')
  })

  test('AC3 / AC5 P2 bid 0 / ask 0.05 无 theo → Mark 无报价；P4 链中从无 → 无报价；P1 从链中消失 → 1.25 + 数据源已无该合约（最后报价：11-23 09:45）', async ({ page }) => {
    const b = await scenario()
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '47.5', type: 'PUT', netAmount: '100.00', time: '2026-11-20T10:20' })
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await setSources({
      cboe: {
        QRTV: cboe('QRTV', 52.5, '2026-11-23 10:00:00', []),
        IXQZ: cboe('IXQZ', 61.1, '2026-11-23 10:00:00', [option(OCC_P2, { bid: 0, ask: 0.05 })]),
      },
    })
    await tickAt('2026-11-23T11:00')
    await fixNow(page, ny('2026-11-23T11:05'))
    const ui = new Ui(page)

    await ui.open()
    await expect(ui.cell(P4, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.stale(P4)).toHaveCount(0)
    await expect(ui.cell(P1, 'mark')).toHaveText('1.25')
    await expect(ui.row(P1).getByTestId('position-mark-status')).toHaveText('数据源已无该合约（最后报价：11-23 09:45）')
    await expect(ui.cell(P1, 'quote-time')).toHaveText('11-23 09:45')
    await expect(ui.cell(P1, 'underlying-price')).toHaveText('52.50')

    await ui.switchAccount(b, ACCOUNT_B)
    await expect(ui.cell(P2, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.cell(P2, 'bid-ask')).toHaveText('0.00 / 0.05')
  })

  test('AC4 IXQZ CBOE 500、Finnhub 61.05：现价 61.05（悬停 来源 Finnhub）；P2 无报价 + 行情未更新（从未成功）；P3 1.25', async ({ page }) => {
    const b = await scenario()
    await setSources({
      cboe: { QRTV: qrtvOk(), IXQZ: { httpStatus: 500, body: null } },
      finnhub: { IXQZ: { httpStatus: 200, body: { c: 61.05, pc: 60.8, t: Date.parse(ny('2026-11-23T09:59')) / 1000 } } },
    })
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.switchAccount(b, ACCOUNT_B)

    await expect(ui.stockRow('IXQZ').getByTestId('stock-position-price')).toHaveText('61.05')
    await expect(ui.cell(P2, 'underlying-price')).toHaveText('61.05')
    await expect(ui.cell(P2, 'underlying-price')).toHaveAttribute('data-source', 'FINNHUB')
    await expect(ui.row(P2).getByTestId('position-underlying-price-stale')).toHaveCount(0)
    await ui.cell(P2, 'underlying-price').hover()
    await expect(page.locator('.ant-tooltip:not(.ant-tooltip-hidden)').getByText('来源 Finnhub')).toBeVisible()
    await page.mouse.move(0, 0)
    await expect(ui.cell(P2, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.stale(P2)).toHaveText('行情未更新（从未成功）')
    await expect(ui.cell(P3, 'mark')).toHaveText('1.25')
    await expect(ui.stale(P3)).toHaveCount(0)
    await expect(ui.warning).toHaveCount(0)
  })

  test('AC6 IXQZ 失败：IXQZ 行 行情未更新（上次：11-23 10:00）、P2 仍 2.20、现价仍 61.10、无顶部提示；之后全部失败超过 90 分钟（Q270）顶部提示，成功后消失', async ({ page }) => {
    const b = await scenario()
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await setSources({ cboe: { QRTV: qrtvOk() } })
    await tickAt('2026-11-23T11:00')
    await fixNow(page, ny('2026-11-23T11:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.switchAccount(b, ACCOUNT_B)
    await expect(ui.stale(P2)).toHaveText('行情未更新（上次：11-23 10:00）')
    await expect(ui.cell(P2, 'mark')).toHaveText('2.20')
    await expect(ui.stockRow('IXQZ').getByTestId('stock-position-price')).toHaveText('61.10')
    await expect(ui.stockRow('IXQZ').getByTestId('stock-position-price-stale')).toHaveText('行情未更新（上次：11-23 10:00）')
    await expect(ui.row(P2).getByTestId('position-underlying-price-stale')).toHaveText('行情未更新（上次：11-23 10:00）')
    await expect(ui.cell(P2, 'underlying-price')).toHaveText('61.10')
    await expect(ui.stale(P3)).toHaveCount(0)
    await expect(ui.warning).toHaveCount(0)

    // the last successful run was 11:00 (QRTV); now everything fails at 12:00 (Q270 hourly slots; the exact 10:00 / 11:31
    // timeline is covered by the backend system test ac6AllFailTopWarning); 90-minute threshold
    await setSources({})
    await tickAt('2026-11-23T12:00')
    await fixNow(page, ny('2026-11-23T12:30'))
    await ui.open()
    await expect(ui.cell(P2, 'mark')).toHaveText('2.20')
    await expect(ui.warning).toHaveCount(0)
    await fixNow(page, ny('2026-11-23T12:31'))
    await ui.open()
    await expect(ui.warning).toHaveText('行情未更新（上次：2026-11-23 11:00 美东）')
    await expect(ui.cell(P2, 'mark')).toHaveText('2.20')
    await expect(ui.cell(P3, 'mark')).toHaveText('1.25')

    await scenarioSources()
    await tickAt('2026-11-23T13:00')
    await fixNow(page, ny('2026-11-23T13:01'))
    await ui.open()
    await expect(ui.warning).toHaveCount(0)
    await expect(ui.stale(P2)).toHaveCount(0)
  })

  test('AC8 手动设置 P1 Mark 1.40、QRTV 标的价 52.00 → 各带 手动 11-23 10:05，B 的 P3 也是 1.40；11:00 QRTV 失败仍 1.40；12:00 成功恢复 1.25、52.40；可清除', async ({ page }) => {
    const b = await scenario()
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()

    await ui.override(ui.row(P1).getByTestId('position-mark-set'), '1.40')
    await expect(ui.cell(P1, 'mark')).toHaveText('1.40')
    await expect(ui.row(P1).getByTestId('position-mark-manual')).toHaveText('手动 11-23 10:05')
    await ui.override(ui.row(P1).getByTestId('position-underlying-price-set'), '52.00')
    await expect(ui.cell(P1, 'underlying-price')).toHaveText('52.00')
    await expect(ui.row(P1).getByTestId('position-underlying-price-manual')).toHaveText('手动 11-23 10:05')

    await ui.switchAccount(b, ACCOUNT_B)
    await expect(ui.cell(P3, 'mark')).toHaveText('1.40')
    await expect(ui.row(P3).getByTestId('position-mark-manual')).toHaveText('手动 11-23 10:05')
    await expect(ui.cell(P3, 'underlying-price')).toHaveText('52.00')

    await setSources({ cboe: { IXQZ: ixqzOk() } })
    await tickAt('2026-11-23T11:00')
    await fixNow(page, ny('2026-11-23T11:01'))
    await ui.open()
    await expect(ui.cell(P3, 'mark')).toHaveText('1.40')
    await expect(ui.row(P3).getByTestId('position-mark-manual')).toBeVisible()

    await scenarioSources()
    await tickAt('2026-11-23T12:00')
    await fixNow(page, ny('2026-11-23T12:01'))
    await ui.open()
    await expect(ui.cell(P3, 'mark')).toHaveText('1.25')
    await expect(ui.cell(P3, 'underlying-price')).toHaveText('52.40')
    await expect(ui.row(P3).getByTestId('position-mark-manual')).toHaveCount(0)
    await expect(ui.row(P3).getByTestId('position-underlying-price-manual')).toHaveCount(0)

    // clear: set again, then 清除
    await ui.override(ui.row(P3).getByTestId('position-mark-set'), '1.33')
    await expect(ui.cell(P3, 'mark')).toHaveText('1.33')
    await ui.row(P3).getByTestId('position-mark-clear').click()
    await expect(ui.cell(P3, 'mark')).toHaveText('1.25')
    await expect(ui.row(P3).getByTestId('position-mark-manual')).toHaveCount(0)
  })

  test('AC8 手动设置的非法价格（0）显示 价格必须大于 0，不保存', async ({ page }) => {
    await scenario()
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await ui.row(P1).getByTestId('position-mark-set').click()
    const modal = page.getByTestId('manual-quote-modal')
    await modal.getByTestId('manual-quote-price').fill('0')
    await modal.getByTestId('manual-quote-submit').click()
    await expect(modal).toContainText('价格必须大于 0')
    await expect(modal.getByRole('dialog')).toBeVisible()
    await modal.getByTestId('manual-quote-cancel').click()
    await expect(modal.getByRole('dialog')).toHaveCount(0)
    await expect(ui.cell(P1, 'mark')).toHaveText('1.25')
    await expect(ui.row(P1).getByTestId('position-mark-manual')).toHaveCount(0)
  })

  test('AC9 10:02 点 立即刷新 → P1 Mark 1.35，按钮禁用（悬停 1 分钟内只能刷新一次）至 10:03；10:02:30 绕过页面请求 → 不发 CBOE 请求', async ({ page }) => {
    await scenario()
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:02'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.cell(P1, 'mark')).toHaveText('1.25')
    await expect(ui.refresh).toBeEnabled()

    await setSources({ cboe: { QRTV: cboe('QRTV', 52.4, '2026-11-23 09:47:00', [p1Option(1.3, 1.4)]), IXQZ: ixqzOk() } })
    await clearRequests()
    const done = page.waitForResponse((r) => r.url().includes('/api/market-data/refresh'))
    await ui.refresh.click()
    await done
    await expect(page.getByTestId('market-data-refresh-result')).toHaveAttribute('data-kind', 'success')
    await expect(ui.cell(P1, 'mark')).toHaveText('1.35')
    await expect(ui.refresh).toBeDisabled()
    await page.getByTestId('market-data-refresh-wrapper').hover()
    await expect(page.locator('.ant-tooltip:not(.ant-tooltip-hidden)').getByText('1 分钟内只能刷新一次')).toBeVisible()
    await page.mouse.move(0, 0)
    expect(await requests()).toHaveLength(2)

    // bypassing the page
    await setBackendNow(ny('2026-11-23T10:02:30'))
    await clearRequests()
    const r = await fetch(`${BACKEND_URL}/api/market-data/refresh`, { method: 'POST' })
    expect(r.status).toBe(200)
    expect(((await r.json()) as { performed: boolean; reason: string }).reason).toBe('COOLDOWN')
    expect(await requests()).toEqual([])
    await page.clock.setSystemTime(new Date(ny('2026-11-23T10:02:30')))
    await ui.open()
    await expect(ui.refresh).toBeDisabled()
    await expect(ui.cell(P1, 'mark')).toHaveText('1.35')

    // 10:03: enabled again
    await setBackendNow(ny('2026-11-23T10:03:01'))
    await page.clock.setSystemTime(new Date(ny('2026-11-23T10:03:01')))
    await ui.open()
    await expect(ui.refresh).toBeEnabled()
  })

  test('AC9 立即刷新时后端报错 → 页面提示错误，列表保留原值', async ({ page }) => {
    await scenario()
    await scenarioSources()
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await page.route('**/api/market-data/refresh', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/market-data/refresh', timestamp: new Date().toISOString(), details: [] }),
      }),
    )
    await ui.refresh.click()
    await expect(page.getByTestId('market-data-refresh-result')).toHaveAttribute('data-kind', 'error')
    await expect(ui.cell(P1, 'mark')).toHaveText('1.25')
    await page.unroute('**/api/market-data/refresh')
  })

  test('AC10 无 key、CBOE 不可达、从未成功：顶部 行情未更新（从未成功获取）（晚上也显示），所有持仓无报价；录入 STO QRTV 2027-01-15 45P 照常保存', async ({ page, trade }) => {
    const b = await scenario()
    await setSources({ finnhubKeyPresent: false })
    await tickAt('2026-11-23T10:00')
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    await expect(ui.warning).toHaveText('行情未更新（从未成功获取）')
    await expect(ui.cell(P1, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.stale(P1)).toHaveText('行情未更新（从未成功）')
    await ui.switchAccount(b, ACCOUNT_B)
    await expect(ui.cell(P2, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.cell(P3, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.stockRow('IXQZ').getByTestId('stock-position-price')).toHaveText(NO_QUOTE)
    await ui.switchAccount(1, '默认账户')

    await fixNow(page, ny('2026-11-23T20:00'))
    await ui.open()
    await expect(ui.warning).toHaveText('行情未更新（从未成功获取）')

    await fixNow(page, ny('2026-11-23T10:10'))
    await trade.open()
    await trade.submitAndExpectSaved({
      action: 'STO', symbol: 'QRTV', expiration: '2027-01-15', strike: '45', type: 'PUT', contracts: '1', netAmount: '90.00', time: '2026-11-23T10:06', multiplier: '100',
    })
    await ui.open()
    await expect(ui.cell('QRTV 2027-01-15 45 P', 'mark')).toHaveText(NO_QUOTE)
  })

  test('Q149 指数期权 SPX 不拉取：显示 无报价，无"行情未更新"标签，不请求 SPX', async ({ page }) => {
    await scenario()
    await apiTrade({ action: 'BTO', symbol: 'SPX', expiration: '2026-12-18', strike: '7000', type: 'CALL', netAmount: '500.00', time: '2026-11-20T11:00' })
    await setSources({
      cboe: { QRTV: qrtvOk(), IXQZ: ixqzOk(), SPX: cboe('SPX', 6800, '2026-11-23 09:45:00', [option('SPX261218C07000000', { bid: 20, ask: 21 })]) },
    })
    await tickAt('2026-11-23T10:00')
    expect((await requests()).filter((r) => r.includes('SPX'))).toEqual([])
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    const spx = 'SPX 2026-12-18 7000 C'
    await expect(ui.cell(spx, 'mark')).toHaveText(NO_QUOTE)
    await expect(ui.cell(spx, 'underlying-price')).toHaveText(NO_QUOTE)
    await expect(ui.stale(spx)).toHaveCount(0)
    await expect(ui.cell(P1, 'mark')).toHaveText('1.25')
  })

  test('Q150 到期日次日：过期未处理的合约显示最后报价，不请求该标的、无失败标签', async ({ page }) => {
    await setBackendNow(ny('2026-11-16T09:50'))
    await apiTransfer('DEPOSIT', '20000.00', '2026-11-16T09:00')
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-20', strike: '45', type: 'PUT', netAmount: '60.00', time: '2026-11-16T09:40' })
    await setSources({ cboe: { QRTV: cboe('QRTV', 52.4, '2026-11-20 09:45:00', [option('QRTV261120P00045000', { bid: 0.01, ask: 0.03 })]) } })
    await tickAt('2026-11-20T10:00')
    await clearRequests()
    await tickAt('2026-11-23T10:00')
    expect(await requests()).toEqual([])
    await fixNow(page, ny('2026-11-23T10:05'))
    const ui = new Ui(page)
    await ui.open()
    const c = 'QRTV 2026-11-20 45 P'
    await expect(ui.cell(c, 'mark')).toHaveText('0.02')
    await expect(ui.cell(c, 'quote-time')).toHaveText('11-20 09:45')
    await expect(ui.row(c).getByTestId('position-mark-status')).toHaveText('已到期（最后报价：11-20 09:45）')
    await expect(ui.stale(c)).toHaveCount(0)
    await expect(ui.cell(c, 'underlying-price')).toHaveText('52.40')
    await expect(ui.row(c).getByTestId('position-underlying-price-stale')).toHaveCount(0)
    await expect(ui.warning).toHaveCount(0)
  })
})
