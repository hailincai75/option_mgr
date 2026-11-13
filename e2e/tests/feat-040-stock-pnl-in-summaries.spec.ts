import type { Locator, Page } from '@playwright/test'
import { apiTransfer, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { apiTrade } from '../support/trade'

/**
 * FEAT-040 股票已实现盈亏并入已平仓、盈亏汇总、首页 E2E (real clock; all scenario dates are before 2026-11-13).
 * The DB is reset before every test (ids restart at 1), so scenario M gives option trades O1–O4 = #1–#4 and stock
 * records S1–S7 = #1–#7: the two id sequences collide, rows are told apart by data-record-type + data-trade-id.
 * "本月" figures are those of the requirement when run in 2026-11, otherwise 0.00 / 0 (same as FEAT-008).
 * Tickers are fictional.
 */

const BACKEND = BACKEND_URL
const today = () => nyLocal(new Date()).slice(0, 10)
const inNov2026 = () => today().startsWith('2026-11')

type StockType = 'BUY' | 'SELL' | 'TRANSFER_IN'

async function apiStock(type: StockType, symbol: string, shares: number, amount: string, time: string, note: string | null = null): Promise<number> {
  const r = await fetch(`${BACKEND}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, symbol, shares, amount, ...(type === 'TRANSFER_IN' ? { tradeDate: time } : { tradedAtLocal: time }), note }),
  })
  if (r.status !== 201) throw new Error(`seed stock ${type} ${symbol} failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiClose(body: Record<string, unknown>): Promise<number> {
  const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contracts: 1, ...body }),
  })
  if (r.status !== 201) throw new Error(`close failed ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiCreateAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

/** Scenario M (requirement). O1–O4 = option #1–#4, S1–S7 = stock #1–#7. Cash 19,499.35. */
async function scenarioM(): Promise<void> {
  await apiTransfer('DEPOSIT', '20000.00', '2026-10-26T10:00')
  expect(await apiStock('TRANSFER_IN', 'IXQZ', 100, '750.00', '2026-10-01')).toBe(1)
  await apiStock('TRANSFER_IN', 'QRTV', 50, '400.00', '2026-10-01')
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-11-20', strike: '7', type: 'PUT', netAmount: '45.00', time: '2026-10-27T10:00' })
  await apiClose({ symbol: 'IXQZ', expirationDate: '2026-11-20', strike: '7', optionType: 'PUT', action: 'BTC', netAmount: '10.00', tradedAtLocal: '2026-10-30T10:00' })
  await apiStock('SELL', 'QRTV', 50, '380.00', '2026-10-31T23:30', '盘后')
  await apiStock('BUY', 'IXQZ', 200, '1500.65', '2026-11-03T10:00')
  await apiStock('BUY', 'XYZ', 300, '1000.00', '2026-11-04T10:00')
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: '2026-12-18', strike: '3', type: 'CALL', netAmount: '25.00', time: '2026-11-04T11:00' })
  await apiStock('SELL', 'IXQZ', 150, '1200.00', '2026-11-05T10:00')
  expect(await apiStock('SELL', 'XYZ', 100, '400.00', '2026-11-06T10:00')).toBe(7)
  expect(await apiClose({ symbol: 'XYZ', expirationDate: '2026-12-18', strike: '3', optionType: 'CALL', action: 'BTC', netAmount: '40.00', tradedAtLocal: '2026-11-09T10:00' })).toBe(4)
}

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

async function switchTo(page: Page, id: number, name: string): Promise<void> {
  await page.getByTestId('account-switcher').click()
  await page.getByTestId(`account-option-${id}`).click()
  await expect(page.getByTestId('account-current')).toHaveText(name)
}

/** Marks the window; {@link expectNoReload} fails if the page was reloaded since. */
async function markNoReload(page: Page): Promise<void> {
  await page.evaluate(() => {
    ;(window as unknown as { __noReload: boolean }).__noReload = true
  })
}

async function expectNoReload(page: Page): Promise<void> {
  expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
}

const LABELS: Record<string, string> = {
  'OPTION-1': 'O1', 'OPTION-2': 'O2', 'OPTION-3': 'O3', 'OPTION-4': 'O4', 'OPTION-5': 'O5', 'OPTION-6': 'O6',
  'STOCK-1': 'S1', 'STOCK-2': 'S2', 'STOCK-3': 'S3', 'STOCK-4': 'S4', 'STOCK-5': 'S5', 'STOCK-6': 'S6',
  'STOCK-7': 'S7', 'STOCK-8': 'S8', 'STOCK-9': 'S9',
}

interface ClosedCells {
  time: string
  type: string
  contract: string
  action: string
  direction: string
  contracts: string
  openingNet: string
  openingPer: string
  closeNet: string
  closePer: string
  pnl: string
}

class ClosedUi {
  readonly rows: Locator
  readonly count: Locator
  readonly total: Locator
  constructor(readonly page: Page) {
    this.rows = page.getByTestId('closed-row')
    this.count = page.getByTestId('closed-count')
    this.total = page.getByTestId('closed-pnl-total')
  }

  f(name: string): Locator {
    return this.page.getByTestId(`closed-filter-${name}`)
  }

  async open(query = ''): Promise<void> {
    await this.page.goto(`/closed${query}`)
    await expect(this.page.getByTestId('closed-page')).toBeVisible()
  }

  row(label: string): Locator {
    const type = label.startsWith('O') ? 'OPTION' : 'STOCK'
    return this.page.locator(`[data-testid="closed-row"][data-record-type="${type}"][data-trade-id="${label.slice(1)}"]`)
  }

  async labels(): Promise<string[]> {
    const keys = await this.rows.evaluateAll((rs) => rs.map((r) => `${r.getAttribute('data-record-type')}-${r.getAttribute('data-trade-id')}`))
    return keys.map((k) => LABELS[k] ?? k)
  }

  async expect(labels: string[], count: number, total: string): Promise<void> {
    await expect(this.count).toHaveText(`共 ${count} 笔平仓`)
    await expect(this.total).toHaveText(total)
    await expect.poll(() => this.labels()).toEqual(labels)
  }

  async cells(): Promise<ClosedCells[]> {
    return this.rows.evaluateAll((rs) =>
      rs.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
        return {
          time: t('closed-cell-time'),
          type: t('closed-cell-type'),
          contract: t('closed-cell-contract'),
          action: t('closed-cell-action'),
          direction: t('closed-cell-direction'),
          contracts: t('closed-cell-contracts'),
          openingNet: t('closed-cell-opening-net'),
          openingPer: t('closed-cell-opening-net-per'),
          closeNet: t('closed-cell-close-net'),
          closePer: t('closed-cell-close-net-per'),
          pnl: t('closed-cell-pnl'),
        }
      }),
    )
  }

  async soldLots(label: string): Promise<string[]> {
    await this.row(label).getByTestId('closed-expand').click()
    const lots = this.row(label).locator('xpath=following-sibling::tr[1]').getByTestId('closed-sold-lot-row')
    await expect(lots.first()).toBeVisible()
    return lots.evaluateAll((rs) =>
      rs.map((r) =>
        [r.getAttribute('data-trade-id') ?? '', ...['closed-sold-lot-time', 'closed-sold-lot-source', 'closed-sold-lot-shares', 'closed-sold-lot-cost'].map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '')].join(' | '),
      ),
    )
  }

  fieldError(testId: string): Locator {
    return this.page
      .locator('.ant-form-item')
      .filter({ has: this.page.getByTestId(testId) })
      .locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
  }

  async query(): Promise<void> {
    await this.f('submit').click()
  }
}

/** Scenario M rows on the 已平仓 page (AC1). */
const M_CELLS: ClosedCells[] = [
  { time: '2026-11-09 10:00', type: '期权', contract: 'XYZ 2026-12-18 3 C', action: 'BTC（买入平仓）', direction: '空头', contracts: '1', openingNet: '+25.00', openingPer: '每张 +25.00', closeNet: `${MINUS}40.00`, closePer: `每张 ${MINUS}40.00`, pnl: `${MINUS}15.00` },
  { time: '2026-11-06 10:00', type: '股票', contract: 'XYZ', action: '卖出', direction: '', contracts: '100 股', openingNet: `${MINUS}333.33`, openingPer: '每股 3.3333', closeNet: '+400.00', closePer: '每股 4.0000', pnl: '+66.67' },
  { time: '2026-11-05 10:00', type: '股票', contract: 'IXQZ', action: '卖出', direction: '', contracts: '150 股', openingNet: `${MINUS}1,125.16`, openingPer: '每股 7.5011', closeNet: '+1,200.00', closePer: '每股 8.0000', pnl: '+74.84' },
  { time: '2026-10-31 23:30', type: '股票', contract: 'QRTV', action: '卖出', direction: '', contracts: '50 股', openingNet: `${MINUS}400.00`, openingPer: '每股 8.0000', closeNet: '+380.00', closePer: '每股 7.6000', pnl: `${MINUS}20.00` },
  { time: '2026-10-30 10:00', type: '期权', contract: 'IXQZ 2026-11-20 7 P', action: 'BTC（买入平仓）', direction: '空头', contracts: '1', openingNet: '+45.00', openingPer: '每张 +45.00', closeNet: `${MINUS}10.00`, closePer: `每张 ${MINUS}10.00`, pnl: '+35.00' },
]

/** Summary rows: "KEY count option stock total". */
async function sumRows(page: Page, kind: 'symbol' | 'month'): Promise<string[]> {
  return page.getByTestId(`pnl-${kind}-row`).evaluateAll(
    (rs, p) => rs.map((r) => [`${p}-link`, `${p}-count`, `${p}-option`, `${p}-stock`, `${p}-pnl`].map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '').join(' ')),
    `pnl-${kind}`,
  )
}

async function sumTotalRow(page: Page, kind: 'symbol' | 'month'): Promise<string> {
  const r = page.getByTestId(`pnl-${kind}-total-row`)
  const t = async (s: string) => ((await r.getByTestId(`pnl-${kind}-total-${s}`).textContent()) ?? '').trim()
  return [await t('count'), await t('option'), await t('stock'), await t('pnl')].join(' ')
}

async function expectSumTotals(page: Page, count: string, total: string, breakdown: string): Promise<void> {
  await expect(page.getByTestId('pnl-total-count')).toHaveText(count)
  await expect(page.getByTestId('pnl-total')).toHaveText(total)
  await expect(page.getByTestId('pnl-total-breakdown')).toHaveText(breakdown)
}

async function expectHome(page: Page, total: string, count: string, monthTotal: string, monthCount: string, optionPositions: string, stockSymbols: string): Promise<void> {
  await expect(page.getByTestId('dashboard-realized-total')).toHaveText(total)
  await expect(page.getByTestId('dashboard-realized-count')).toHaveText(count)
  const nov = inNov2026()
  await expect(page.getByTestId('dashboard-month-total')).toHaveText(nov ? monthTotal : '0.00')
  await expect(page.getByTestId('dashboard-month-count')).toHaveText(nov ? monthCount : '0')
  await expect(page.getByTestId('dashboard-positions-card')).toContainText(`期权持仓 ${optionPositions} 个`)
  await expect(page.getByTestId('dashboard-position-count')).toHaveText(optionPositions)
  await expect(page.getByTestId('dashboard-stock-positions-link')).toHaveText(`股票持仓 ${stockSymbols} 个标的`)
  await expect(page.getByTestId('dashboard-stock-position-count')).toHaveText(stockSymbols)
}

class StockPage {
  constructor(readonly page: Page) {}

  row(id: number): Locator {
    return this.page.locator(`[data-testid="stock-row"][data-stock-trade-id="${id}"]`)
  }

  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  /** Edit via the 股票 page dialog; only the given fields are changed. */
  async edit(id: number, change: { amount?: string; note?: string }): Promise<void> {
    await this.page.getByTestId('nav-stocks').click()
    await expect(this.row(id)).toBeVisible()
    await this.row(id).getByTestId('stock-edit').click()
    const d = this.dialog('stock-edit-modal')
    await expect(d).toBeVisible()
    if (change.amount !== undefined) await d.getByTestId('stock-edit-amount').fill(change.amount)
    if (change.note !== undefined) await d.getByTestId('stock-edit-note').fill(change.note)
    await d.getByTestId('stock-edit-submit').click()
    await expect(d).toHaveCount(0)
  }

  async remove(id: number): Promise<void> {
    await this.page.getByTestId('nav-stocks').click()
    await expect(this.row(id)).toBeVisible()
    await this.row(id).getByTestId('stock-delete').click()
    const d = this.dialog('stock-delete-modal')
    await d.getByTestId('stock-delete-confirm').click()
    await expect(d).toHaveCount(0)
    await expect(this.row(id)).toHaveCount(0)
  }

  /** New record through the entry form (SPA: nav → 股票 → 录入). */
  async enter(e: { type: StockType; symbol: string; shares: string; amount: string; time?: string; date?: string }): Promise<void> {
    await this.page.getByTestId('nav-stocks').click()
    await this.page.getByTestId('stock-list-new').click()
    await expect(this.page.getByTestId('stock-entry-form')).toBeVisible()
    const suffix = e.type === 'TRANSFER_IN' ? 'transfer-in' : e.type.toLowerCase()
    await this.page.getByTestId(`stock-entry-type-${suffix}`).check()
    await this.page.getByTestId('stock-entry-symbol').fill(e.symbol)
    await this.page.getByTestId('stock-entry-shares').fill(e.shares)
    await this.page.getByTestId('stock-entry-amount').fill(e.amount)
    if (e.time !== undefined) await this.page.getByTestId('stock-entry-time').fill(e.time)
    if (e.date !== undefined) await this.page.getByTestId('stock-entry-date').fill(e.date)
    await this.page.getByTestId('stock-entry-submit').click()
    await expect(this.page.getByTestId('stock-entry-confirmation')).toBeVisible()
  }
}

test.describe('FEAT-040 股票已实现盈亏并入已平仓、盈亏汇总、首页', () => {
  test('AC1 已平仓：共 5 笔 +141.51；类型列（期权/股票颜色不同）、合约/标的、方向、股数、每股 4 位小数；表头与说明；无历史；买入/转入不出现', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/')
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    expect(await colorOf(c.total)).toBe('green')
    expect(await c.cells()).toEqual(M_CELLS)
    // colors
    expect(await colorOf(c.row('O4').getByTestId('closed-cell-pnl'))).toBe('red')
    expect(await colorOf(c.row('S7').getByTestId('closed-cell-pnl'))).toBe('green')
    expect(await colorOf(c.row('S6').getByTestId('closed-cell-pnl'))).toBe('green')
    expect(await colorOf(c.row('S3').getByTestId('closed-cell-pnl'))).toBe('red')
    expect(await colorOf(c.row('O2').getByTestId('closed-cell-pnl'))).toBe('green')
    const tagColor = (l: Locator) => l.evaluate((e) => `${getComputedStyle(e).color}|${getComputedStyle(e).backgroundColor}`)
    expect(await tagColor(c.row('S7').getByTestId('closed-cell-type'))).not.toBe(await tagColor(c.row('O4').getByTestId('closed-cell-type')))
    // stock action tag looks like the 卖出 tag of the 股票 page
    const sellTag = await tagColor(c.row('S7').getByTestId('closed-cell-action'))
    // note, no multiplier / 空头·多头 on stock rows
    await expect(c.row('S3').getByTestId('closed-cell-note')).toHaveText('盘后')
    await expect(c.row('S6').getByTestId('closed-cell-note')).toHaveText('—')
    for (const s of ['S7', 'S6', 'S3']) {
      await expect(c.row(s).getByTestId('closed-cell-direction')).toHaveCount(0)
      await expect(c.row(s).getByTestId('closed-cell-contracts')).not.toContainText('multiplier')
    }
    await expect(page.getByTestId('closed-history')).toHaveCount(0)
    // header names and type column right after the time column
    const headers = (await page.getByTestId('closed-list').locator('thead th').allTextContents()).map((h) => h.trim()).filter((h) => h !== '')
    expect(headers.slice(0, 3)).toEqual(['平仓时间（美东时间）', '类型', '合约 / 标的'])
    for (const h of ['张数 / 股数', '开仓净额合计 / 成本', '平仓净额 / 卖出实收', '已实现盈亏']) expect(headers).toContain(h)
    await expect(page.getByTestId('closed-stock-note')).toHaveText(
      '股票卖出：成本为按 FIFO 被卖出批次的成本（部分卖出按比例四舍五入到分），每股数字保留 4 位小数，仅供参考；股票转入和买入没有已实现盈亏。',
    )
    // buys / transfers are not rows (S1, S2, S4, S5)
    for (const s of ['S1', 'S2', 'S4', 'S5']) await expect(c.row(s)).toHaveCount(0)

    await page.goto('/stocks')
    const stockSellTag = await tagColor(page.locator('[data-testid="stock-row"][data-stock-trade-id="7"]').getByTestId('stock-cell-type'))
    expect(sellTag).toBe(stockSellTag)
  })

  test('AC2 展开：S6 两个被卖出批次（转入 2026-10-01 100 股 −750.00、买入 50 股 −375.16）；S7 一批；O2 被平批次不变', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await c.open()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    expect(await c.soldLots('S6')).toEqual([
      `1 | 2026-10-01 | 转入 | 100 股 | ${MINUS}750.00`,
      `4 | 2026-11-03 10:00 | 买入 | 50 股 | ${MINUS}375.16`,
    ])
    expect(await c.soldLots('S7')).toEqual([`5 | 2026-11-04 10:00 | 买入 | 100 股 | ${MINUS}333.33`])
    await c.row('O2').getByTestId('closed-expand').click()
    const lots = c.row('O2').locator('xpath=following-sibling::tr[1]').getByTestId('closed-lot-row')
    await expect(lots).toHaveCount(1)
    expect(
      await lots.evaluateAll((rs) => rs.map((r) => ['closed-lot-time', 'closed-lot-action', 'closed-lot-contracts', 'closed-lot-net-amount'].map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '').join(' '))),
    ).toEqual(['2026-10-27 10:00 STO（卖出开仓） 1 张 +45.00'])
  })

  test('AC3 类型筛选：股票 3 笔 +121.51 / 期权 2 笔 +20.00；标的 ixqz +109.84、+股票 +74.84；QFZ 无结果；地址栏刷新与后退；FUTURE 报错不查询', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await c.open()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await expect(c.f('record-type-any')).toBeChecked()

    await c.f('record-type-stock').check()
    await c.query()
    await c.expect(['S7', 'S6', 'S3'], 3, '+121.51')
    expect(new URL(page.url()).searchParams.get('type')).toBe('STOCK')
    await page.reload()
    await c.expect(['S7', 'S6', 'S3'], 3, '+121.51')
    await expect(c.f('record-type-stock')).toBeChecked()
    await page.goBack()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await expect(c.f('record-type-any')).toBeChecked()

    await c.f('record-type-option').check()
    await c.query()
    await c.expect(['O4', 'O2'], 2, '+20.00')
    expect(new URL(page.url()).searchParams.get('type')).toBe('OPTION')

    await c.f('record-type-any').check()
    await c.f('symbol').fill('ixqz')
    await c.query()
    await c.expect(['S6', 'O2'], 2, '+109.84')
    expect(new URL(page.url()).searchParams.has('type')).toBe(false)
    await c.f('record-type-stock').check()
    await c.query()
    await c.expect(['S6'], 1, '+74.84')

    await c.open('?symbol=QFZ')
    await expect(page.getByTestId('closed-no-match')).toContainText('没有符合条件的平仓记录')
    await expect(c.count).toHaveText('共 0 笔平仓')
    await expect(c.total).toHaveText('0.00')

    // invalid type in the URL: error under 类型, no query, no crash
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/closed-trades')) reqs.push(r.url())
    })
    await c.open('?type=FUTURE')
    await expect(c.fieldError('closed-filter-record-type')).toHaveText('类型只能是 OPTION 或 STOCK')
    await expect(page.getByTestId('closed-invalid')).toBeVisible()
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)
    await c.f('clear').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
  })

  test('AC4 Q90：方向/到期日/Call/行权价只显示期权并提示；改为股票时四项清空并禁用、地址栏不含；改回不限恢复可用；地址"股票+BTC"→ 空结果', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await c.open()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    // labels: Call/Put field relabelled, dates "平仓 / 卖出…"
    const form = page.getByTestId('closed-filter-form')
    await expect(form.locator('.ant-form-item').filter({ has: c.f('type') }).locator('.ant-form-item-label label')).toHaveText('Call / Put')
    await expect(form.locator('.ant-form-item').filter({ has: c.f('record-type') }).locator('.ant-form-item-label label')).toHaveText('类型')
    await expect(form).toContainText('平仓 / 卖出开始日期（美东日期）')
    await expect(form).toContainText('平仓 / 卖出结束日期（美东日期）')
    const hint = c.f('option-only-hint')
    await expect(hint).toHaveCount(0)

    await c.f('action-btc').check()
    await expect(hint).toHaveText('按到期日 / 行权价 / Call·Put / 方向筛选时只显示期权')
    await c.query()
    await c.expect(['O4', 'O2'], 2, '+20.00')
    await expect(hint).toBeVisible()

    await c.f('action-btc').uncheck()
    await expect(hint).toHaveCount(0)
    await c.f('expiration').fill('2026-11-20')
    await expect(hint).toBeVisible()
    await c.query()
    await c.expect(['O2'], 1, '+35.00')
    await c.f('expiration').fill('')
    await c.f('type-call').check()
    await c.query()
    await c.expect(['O4'], 1, `${MINUS}15.00`)
    await c.f('type-any').check()
    await c.f('strike').fill('3')
    await expect(hint).toBeVisible()
    await c.query()
    await c.expect(['O4'], 1, `${MINUS}15.00`)

    // BTC + expiration, then 股票 → emptied and disabled, not sent
    await c.f('clear').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await c.f('action-btc').check()
    await c.f('expiration').fill('2026-11-20')
    await c.f('record-type-stock').check()
    await expect(c.f('expiration')).toHaveValue('')
    await expect(c.f('action-btc')).not.toBeChecked()
    for (const id of ['expiration', 'strike', 'type-any', 'type-call', 'type-put', 'action-btc', 'action-stc', 'action-exp']) {
      await expect(c.f(id), id).toBeDisabled()
    }
    await expect(hint).toHaveCount(0)
    await c.query()
    await c.expect(['S7', 'S6', 'S3'], 3, '+121.51')
    const p = new URL(page.url()).searchParams
    expect(p.get('type')).toBe('STOCK')
    for (const k of ['action', 'expirationDate', 'strike', 'optionType']) expect(p.has(k), k).toBe(false)

    await c.f('record-type-any').check()
    for (const id of ['expiration', 'strike', 'type-call', 'action-btc']) await expect(c.f(id), id).toBeEnabled()
    await expect(c.f('expiration')).toHaveValue('')
    await expect(c.f('strike')).toHaveValue('')
    await expect(c.f('action-btc')).not.toBeChecked()
    await expect(hint).toHaveCount(0)

    // URL with 股票 + BTC: form filled from the URL (disabled), queried, empty result
    await c.open('?type=STOCK&action=BTC')
    await expect(page.getByTestId('closed-no-match')).toContainText('没有符合条件的平仓记录')
    await expect(c.count).toHaveText('共 0 笔平仓')
    await expect(c.total).toHaveText('0.00')
    await expect(c.f('record-type-stock')).toBeChecked()
    await expect(c.f('action-btc')).toBeChecked()
    await expect(c.f('action-btc')).toBeDisabled()
  })

  test('AC5 美东日期：10-31 只有 S3 −20.00；11-01 无；11-06..11-09 O4 S7 +51.67；颠倒报错不查询', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await c.open()
    const go = async (s: string, e: string) => {
      await c.f('start-date').fill(s)
      await c.f('end-date').fill(e)
      await c.query()
    }
    await go('2026-10-31', '2026-10-31')
    await c.expect(['S3'], 1, `${MINUS}20.00`)
    await go('2026-11-01', '2026-11-01')
    await expect(page.getByTestId('closed-no-match')).toBeVisible()
    await expect(c.total).toHaveText('0.00')
    await go('2026-11-06', '2026-11-09')
    await c.expect(['O4', 'S7'], 2, '+51.67')
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/closed-trades')) reqs.push(r.url())
    })
    await go('2026-11-07', '2026-11-06')
    await expect(page.getByTestId('closed-filter-form').locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')).toHaveText('开始日期不能晚于结束日期')
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)
  })

  test('AC7 同一时间：股票 QRTV 卖出 +5.00 在 BTC +40.00 之上；共 7 笔 +186.51；期权 #6 与股票 #6 是两行', async ({ page }) => {
    await scenarioM()
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-10T09:00')
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '5', type: 'PUT', netAmount: '60.00', time: '2026-11-10T09:30' })
    await apiStock('BUY', 'QRTV', 10, '70.00', '2026-11-10T09:30')
    expect(await apiStock('SELL', 'QRTV', 10, '75.00', '2026-11-11T10:00')).toBe(9)
    expect(await apiClose({ symbol: 'QRTV', expirationDate: '2026-12-18', strike: '5', optionType: 'PUT', action: 'BTC', netAmount: '20.00', tradedAtLocal: '2026-11-11T10:00' })).toBe(6)
    const c = new ClosedUi(page)
    await c.open()
    await c.expect(['S9', 'O6', 'O4', 'S7', 'S6', 'S3', 'O2'], 7, '+186.51')
    const cells = await c.cells()
    expect(cells.slice(0, 2).map((x) => [x.time, x.type, x.contract, x.action, x.pnl].join(' '))).toEqual([
      '2026-11-11 10:00 股票 QRTV 卖出 +5.00',
      '2026-11-11 10:00 期权 QRTV 2026-12-18 5 P BTC（买入平仓） +40.00',
    ])
    // ids collide: two different rows with data-trade-id 6
    await expect(page.locator('[data-testid="closed-row"][data-trade-id="6"]')).toHaveCount(2)
    await expect(c.row('O6').getByTestId('closed-cell-contract')).toHaveText('QRTV 2026-12-18 5 P')
    await expect(c.row('S6').getByTestId('closed-cell-contract')).toHaveText('IXQZ')
    // expanding one does not expand the other (row key = type + id)
    await c.row('S6').getByTestId('closed-expand').click()
    await expect(page.getByTestId('closed-sold-lot-row')).toHaveCount(2)
    await expect(page.getByTestId('closed-lot-row')).toHaveCount(0)
  })

  test('AC8 历史按类型+id：改 S1 → 无；股票页改 S6 备注 → 只有 S6，进入 股票交易 #6；交易列表改 O2 → 期权交易 #2；点 IXQZ → 股票页筛选；点 O2 合约 → 交易列表', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    const stocks = new StockPage(page)
    await page.goto('/')
    await stocks.edit(1, { note: '期初' })
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await expect(page.getByTestId('closed-history')).toHaveCount(0)

    await stocks.edit(6, { note: '减仓' })
    await page.getByTestId('nav-closed').click()
    await expect(c.row('S6').getByTestId('closed-cell-note')).toHaveText('减仓')
    await expect(page.getByTestId('closed-history')).toHaveCount(1)
    await expect(c.row('S6').getByTestId('closed-history')).toBeVisible()
    await c.row('S6').getByTestId('closed-history').click()
    await expect(page).toHaveURL(/\/audit\?/)
    let p = new URL(page.url()).searchParams
    expect([p.get('objectType'), p.get('objectId')]).toEqual(['STOCK_TRADE', '6'])
    await expect(page.getByTestId('audit-filter-type-stock')).toBeChecked()
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    await expect(page.getByTestId('audit-row')).toContainText('股票交易 #6')

    // edit O2 in the trade list
    await page.getByTestId('nav-trades').click()
    await page.locator('[data-testid="trade-row"][data-trade-id="2"]').getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await d.getByTestId('trade-edit-note').fill('提前平仓')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d).toBeHidden()
    await page.getByTestId('nav-closed').click()
    await expect(page.getByTestId('closed-history')).toHaveCount(2)
    await expect(c.row('O2').getByTestId('closed-history')).toBeVisible()
    await expect(c.row('S3').getByTestId('closed-history')).toHaveCount(0)
    await expect(c.row('S7').getByTestId('closed-history')).toHaveCount(0)
    await c.row('O2').getByTestId('closed-history').click()
    p = new URL(page.url()).searchParams
    expect([p.get('objectType'), p.get('objectId')]).toEqual(['OPTION_TRADE', '2'])
    await expect(page.getByTestId('audit-filter-type-trade')).toBeChecked()
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    await expect(page.getByTestId('audit-row')).toContainText('期权交易 #2')

    // symbol link of a stock row → 股票 page filtered by IXQZ (S6, S4, S1)
    await page.getByTestId('nav-closed').click()
    await c.row('S6').getByTestId('closed-cell-contract').click()
    await expect(page).toHaveURL(/\/stocks\?.*symbol=IXQZ/)
    await expect(page.getByTestId('stock-filter-symbol')).toHaveValue('IXQZ')
    await expect.poll(() => page.getByTestId('stock-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-stock-trade-id')))).toEqual(['6', '4', '1'])

    // contract link of an option row → trade list filtered by the contract (O2, O1)
    await page.getByTestId('nav-closed').click()
    await c.row('O2').getByTestId('closed-cell-contract').click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect(page.getByTestId('trade-filter-symbol')).toHaveValue('IXQZ')
    await expect(page.getByTestId('trade-filter-type-put')).toBeChecked()
    await expect.poll(() => page.getByTestId('trade-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id')))).toEqual(['2', '1'])
  })

  test('AC9 盈亏汇总：共 5 笔 +141.51，期权 2 笔 +20.00 · 股票 3 笔 +121.51；按标的/按月 5 列，— 与颜色，合计行；说明', async ({ page }) => {
    await scenarioM()
    await page.goto('/')
    await page.getByTestId('nav-pnl-summary').click()
    await expectSumTotals(page, '5', '+141.51', '期权 2 笔 +20.00 · 股票 3 笔 +121.51')
    await expect(page.getByTestId('pnl-total-line')).toHaveText('共 5 笔，已实现盈亏合计 +141.51')
    await expect.poll(() => sumRows(page, 'symbol')).toEqual([
      'IXQZ 2 +35.00 +74.84 +109.84',
      `QRTV 1 — ${MINUS}20.00 ${MINUS}20.00`,
      `XYZ 2 ${MINUS}15.00 +66.67 +51.67`,
    ])
    expect(await sumTotalRow(page, 'symbol')).toBe('5 +20.00 +121.51 +141.51')
    await expect.poll(() => sumRows(page, 'month')).toEqual([
      `2026-11 3 ${MINUS}15.00 +141.51 +126.51`,
      `2026-10 2 +35.00 ${MINUS}20.00 +15.00`,
    ])
    expect(await sumTotalRow(page, 'month')).toBe('5 +20.00 +121.51 +141.51')
    for (const card of ['pnl-by-symbol', 'pnl-by-month']) {
      const headers = (await page.getByTestId(card).locator('thead th').allTextContents()).map((h) => h.trim())
      expect(headers.slice(1)).toEqual(['笔数', '期权', '股票', '合计'])
    }
    await expect(page.getByTestId('pnl-note')).toHaveText('已实现盈亏来自期权平仓、到期作废和股票卖出，已含手续费；不含入金、出金、股息和利息；股票转入（期初持仓）和买入没有已实现盈亏。')
    // colors: QRTV stock red, XYZ option red / stock green, '—' neutral; split columns are not links
    const qrtv = page.getByTestId('pnl-symbol-row').nth(1)
    expect(await colorOf(qrtv.getByTestId('pnl-symbol-stock'))).toBe('red')
    expect(await colorOf(qrtv.getByTestId('pnl-symbol-option'))).toBe('other')
    const xyz = page.getByTestId('pnl-symbol-row').nth(2)
    expect(await colorOf(xyz.getByTestId('pnl-symbol-option'))).toBe('red')
    expect(await colorOf(xyz.getByTestId('pnl-symbol-stock'))).toBe('green')
    expect(await colorOf(xyz.getByTestId('pnl-symbol-pnl'))).toBe('green')
    await expect(page.getByTestId('pnl-by-symbol').locator('a [data-testid="pnl-symbol-option"], a[data-testid="pnl-symbol-option"]')).toHaveCount(0)
    const weight = await xyz.getByTestId('pnl-symbol-pnl').evaluate((e) => Number(getComputedStyle(e).fontWeight))
    expect(weight).toBeGreaterThanOrEqual(600)
  })

  test('AC10 汇总日期范围：10-31 → 1 笔 −20.00；从 11-01 → 3 笔 +126.51、无 QRTV；2027 → 空；= 已平仓页；点 QRTV / 2026-10 跳转', async ({ page }) => {
    await scenarioM()
    await page.goto('/pnl-summary?startDate=2026-10-31&endDate=2026-10-31')
    await expectSumTotals(page, '1', `${MINUS}20.00`, `期权 0 笔 0.00 · 股票 1 笔 ${MINUS}20.00`)
    await expect.poll(() => sumRows(page, 'symbol')).toEqual([`QRTV 1 — ${MINUS}20.00 ${MINUS}20.00`])
    await expect.poll(() => sumRows(page, 'month')).toEqual([`2026-10 1 — ${MINUS}20.00 ${MINUS}20.00`])
    expect(await sumTotalRow(page, 'symbol')).toBe(`1 — ${MINUS}20.00 ${MINUS}20.00`)

    await page.goto('/pnl-summary')
    await page.getByTestId('pnl-filter-start-date').fill('2026-11-01')
    await page.getByTestId('pnl-filter-submit').click()
    await expectSumTotals(page, '3', '+126.51', `期权 1 笔 ${MINUS}15.00 · 股票 2 笔 +141.51`)
    await expect.poll(() => sumRows(page, 'symbol')).toEqual(['IXQZ 1 — +74.84 +74.84', `XYZ 2 ${MINUS}15.00 +66.67 +51.67`])
    await page.goto('/closed?startDate=2026-11-01')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+126.51')

    await page.goto('/pnl-summary?startDate=2027-01-01')
    await expect(page.getByTestId('pnl-empty')).toHaveText('所选范围内没有平仓记录')
    await expectSumTotals(page, '0', '0.00', '期权 0 笔 0.00 · 股票 0 笔 0.00')

    // jumps from the unfiltered summary: no type in the closed-page URL
    await page.goto('/pnl-summary')
    await page.getByTestId('pnl-symbol-link').filter({ hasText: 'QRTV' }).click()
    await expect(page).toHaveURL(/\/closed\?/)
    let p = new URL(page.url()).searchParams
    expect(p.get('symbol')).toBe('QRTV')
    expect(p.has('type')).toBe(false)
    const c = new ClosedUi(page)
    await c.expect(['S3'], 1, `${MINUS}20.00`)
    await expect(c.f('record-type-any')).toBeChecked()
    await page.goto('/pnl-summary')
    await page.getByTestId('pnl-month-link').filter({ hasText: '2026-10' }).click()
    p = new URL(page.url()).searchParams
    expect([p.get('startDate'), p.get('endDate'), p.get('type')]).toEqual(['2026-10-01', '2026-10-31', null])
    await c.expect(['S3', 'O2'], 2, '+15.00')
  })

  test('AC11 首页：总计 +141.51（5）、本月 +126.51（3）、期权持仓 1 个、股票持仓 2 个标的；点击滚动到股票持仓；卖出 XYZ 200 后不刷新即更新', async ({ page }) => {
    await scenarioM()
    await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-18', strike: '9', type: 'CALL', netAmount: '30.00', time: '2026-11-12T10:00' })
    await page.setViewportSize({ width: 1280, height: 420 })
    await page.goto('/')
    await expect(page.getByTestId('cash-summary-total')).toHaveText('19,529.35')
    await expect(page.getByTestId('cash-summary-frozen')).toHaveText('0.00')
    await expect(page.getByTestId('cash-summary-available')).toHaveText('19,529.35')
    await expectHome(page, '+141.51', '5', '+126.51', '3', '1', '2')
    await expect(page.getByTestId('dashboard-frozen-total')).toHaveText('0.00')
    await expect(page.getByTestId('dashboard-expiring-count')).toHaveText('0')
    await expect(page.getByTestId('dashboard-expired-count')).toHaveText('0')
    // "本月" link still has no type
    expect(await page.getByTestId('dashboard-month-link').getAttribute('href')).not.toContain('type=')

    await markNoReload(page)
    await page.getByTestId('dashboard-stock-positions-link').click()
    await expect(page).toHaveURL(/\/positions#stock-positions$/)
    await expect(page.locator('[data-testid="stock-position-row"][data-symbol="XYZ"]')).toBeVisible()
    await expect(page.getByTestId('stock-positions')).toBeInViewport()
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
    await expect.poll(() => page.getByTestId('stock-position-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-symbol')))).toEqual(['IXQZ', 'XYZ'])

    // sell XYZ 200 through the page; home updates without reload
    await page.setViewportSize({ width: 1280, height: 900 })
    await new StockPage(page).enter({ type: 'SELL', symbol: 'XYZ', shares: '200', amount: '700.00', time: '2026-11-12T11:00' })
    await expect(page.getByTestId('stock-entry-confirmation-realized-pnl')).toHaveText('+33.33')
    await page.getByTestId('nav-home').click()
    await expectHome(page, '+174.84', '6', '+159.84', '4', '1', '1')
    await expectNoReload(page)
  })

  test('AC13 改转入成本 S1 → 800.00（股票页）：已平仓 S6 −1,175.16 每股 7.8344 +24.84、合计 +91.51；汇总与首页不刷新即更新', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/')
    await markNoReload(page)
    await new StockPage(page).edit(1, { amount: '800.00' })
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+91.51')
    await expect(c.row('S6').getByTestId('closed-cell-opening-net')).toHaveText(`${MINUS}1,175.16`)
    await expect(c.row('S6').getByTestId('closed-cell-opening-net-per')).toHaveText('每股 7.8344')
    await expect(c.row('S6').getByTestId('closed-cell-pnl')).toHaveText('+24.84')
    await expect(c.row('S6').getByTestId('closed-history')).toHaveCount(0)
    await page.getByTestId('nav-pnl-summary').click()
    await expectSumTotals(page, '5', '+91.51', '期权 2 笔 +20.00 · 股票 3 笔 +71.51')
    await expect.poll(async () => (await sumRows(page, 'symbol'))[0]).toBe('IXQZ 2 +35.00 +24.84 +59.84')
    await expect.poll(async () => (await sumRows(page, 'month'))[0]).toBe(`2026-11 3 ${MINUS}15.00 +91.51 +76.51`)
    await page.getByTestId('nav-home').click()
    await expectHome(page, '+91.51', '5', '+76.51', '3', '0', '2')
    await expectNoReload(page)
  })

  test('AC13 改卖出实收 S7 → 300.00：S7 −33.33（红），合计 +41.51；汇总 XYZ −48.33', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/closed')
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await markNoReload(page)
    await new StockPage(page).edit(7, { amount: '300.00' })
    await page.getByTestId('nav-closed').click()
    await expect(c.row('S7').getByTestId('closed-cell-pnl')).toHaveText(`${MINUS}33.33`)
    expect(await colorOf(c.row('S7').getByTestId('closed-cell-pnl'))).toBe('red')
    await expect(c.row('S7').getByTestId('closed-cell-close-net')).toHaveText('+300.00')
    await expect(c.row('S7').getByTestId('closed-cell-close-net-per')).toHaveText('每股 3.0000')
    await expect(c.row('S7').getByTestId('closed-history')).toBeVisible()
    await expect(c.total).toHaveText('+41.51')
    await page.getByTestId('nav-pnl-summary').click()
    await expect.poll(async () => (await sumRows(page, 'symbol'))[2]).toBe(`XYZ 2 ${MINUS}15.00 ${MINUS}33.33 ${MINUS}48.33`)
    await expectNoReload(page)
  })

  test('AC13 删除卖出 S7：共 4 笔 +74.84；汇总 XYZ 只剩期权（股票 —）、2026-11 2 笔 +59.84；首页 +74.84（4 笔）、股票持仓仍 2', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/')
    await markNoReload(page)
    await new StockPage(page).remove(7)
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S6', 'S3', 'O2'], 4, '+74.84')
    await page.getByTestId('nav-pnl-summary').click()
    await expect.poll(async () => (await sumRows(page, 'symbol'))[2]).toBe(`XYZ 1 ${MINUS}15.00 — ${MINUS}15.00`)
    await expect.poll(async () => (await sumRows(page, 'month'))[0]).toBe(`2026-11 2 ${MINUS}15.00 +74.84 +59.84`)
    await page.getByTestId('nav-home').click()
    await expectHome(page, '+74.84', '4', '+59.84', '2', '0', '2')
    await expectNoReload(page)
  })

  test('AC13 补录卖出 XYZ 100 @420（录入页）：成本 −333.34 每股 3.3334、+86.66；共 6 笔 +228.17；2026-11 4 笔 +213.17', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/')
    await markNoReload(page)
    await new StockPage(page).enter({ type: 'SELL', symbol: 'XYZ', shares: '100', amount: '420.00', time: '2026-11-12T10:00' })
    await page.getByTestId('nav-closed').click()
    await c.expect(['S8', 'O4', 'S7', 'S6', 'S3', 'O2'], 6, '+228.17')
    const s8 = (await c.cells())[0]!
    expect([s8.openingNet, s8.openingPer, s8.closeNet, s8.closePer, s8.pnl]).toEqual([`${MINUS}333.34`, '每股 3.3334', '+420.00', '每股 4.2000', '+86.66'])
    await page.getByTestId('nav-pnl-summary').click()
    await expect.poll(async () => (await sumRows(page, 'month'))[0]).toBe(`2026-11 4 ${MINUS}15.00 +228.17 +213.17`)
    await expectNoReload(page)
  })

  test('AC13 删除被用到的转入 S1：S6 改为只用 S4，−1,125.49、+74.51，合计 +141.18；展开只剩买入批次', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/')
    await markNoReload(page)
    await new StockPage(page).remove(1)
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.18')
    await expect(c.row('S6').getByTestId('closed-cell-opening-net')).toHaveText(`${MINUS}1,125.49`)
    await expect(c.row('S6').getByTestId('closed-cell-pnl')).toHaveText('+74.51')
    expect(await c.soldLots('S6')).toEqual([`4 | 2026-11-03 10:00 | 买入 | 150 股 | ${MINUS}1,125.49`])
    await expectNoReload(page)
  })

  test('AC13 转入 QFZ（录入页）没有盈亏：已平仓、汇总、首页盈亏和笔数不变；首页股票持仓 3 个标的', async ({ page }) => {
    await scenarioM()
    const c = new ClosedUi(page)
    await page.goto('/')
    await expectHome(page, '+141.51', '5', '+126.51', '3', '0', '2')
    await markNoReload(page)
    await new StockPage(page).enter({ type: 'TRANSFER_IN', symbol: 'QFZ', shares: '100', amount: '300.00', date: '2026-11-10' })
    await page.getByTestId('nav-home').click()
    await expectHome(page, '+141.51', '5', '+126.51', '3', '0', '3')
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await page.getByTestId('nav-pnl-summary').click()
    await expectSumTotals(page, '5', '+141.51', '期权 2 笔 +20.00 · 股票 3 笔 +121.51')
    await expect(page.getByTestId('pnl-symbol-link').filter({ hasText: 'QFZ' })).toHaveCount(0)
    await expectNoReload(page)
  })

  test('AC14 按账户隔离：小账户已平仓 1 行 IXQZ +60.00、汇总 IXQZ（期权 —）、首页 +60.00（1 笔）/ 期权持仓 0 / 股票持仓 0；切回默认仍 5 笔 +141.51', async ({ page }) => {
    await scenarioM()
    const small = await apiCreateAccount('小账户')
    setApiAccount(small)
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await apiStock('TRANSFER_IN', 'IXQZ', 100, '700.00', '2026-11-02')
    const x2 = await apiStock('SELL', 'IXQZ', 100, '760.00', '2026-11-10T10:00')
    setApiAccount(1)
    const c = new ClosedUi(page)
    await c.open()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await switchTo(page, small, '小账户')
    await expect(c.count).toHaveText('共 1 笔平仓')
    await expect(c.total).toHaveText('+60.00')
    await expect.poll(() => c.rows.evaluateAll((rs) => rs.map((r) => `${r.getAttribute('data-record-type')}-${r.getAttribute('data-trade-id')}`))).toEqual([`STOCK-${x2}`])
    expect((await c.cells())[0]).toMatchObject({ type: '股票', contract: 'IXQZ', contracts: '100 股', pnl: '+60.00' })
    await page.getByTestId('nav-pnl-summary').click()
    await expectSumTotals(page, '1', '+60.00', '期权 0 笔 0.00 · 股票 1 笔 +60.00')
    await expect.poll(() => sumRows(page, 'symbol')).toEqual(['IXQZ 1 — +60.00 +60.00'])
    await page.getByTestId('nav-home').click()
    await expectHome(page, '+60.00', '1', '+60.00', '1', '0', '0')
    await switchTo(page, 1, '默认账户')
    await page.getByTestId('nav-closed').click()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
    await page.getByTestId('nav-pnl-summary').click()
    await expect.poll(async () => (await sumRows(page, 'symbol'))[0]).toBe('IXQZ 2 +35.00 +74.84 +109.84')
  })

  test('AC15 只有期权（FEAT-008 场景 Q）：每行类型"期权"；汇总股票列全为 —，期权 6 笔 +703.05 · 股票 0 笔 0.00；首页股票持仓 0 个标的', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'BTO', symbol: 'TSLA', strike: '300', type: 'CALL', netAmount: '500.00', time: '2026-10-27T10:00' })
    await apiTrade({ action: 'STO', symbol: 'AAPL', expiration: '2026-11-20', strike: '180', type: 'PUT', netAmount: '150.00', time: '2026-10-28T10:00' })
    await apiClose({ symbol: 'TSLA', expirationDate: '2026-12-18', strike: '300', optionType: 'CALL', action: 'STC', netAmount: '620.00', tradedAtLocal: '2026-10-30T10:00' })
    await apiClose({ symbol: 'AAPL', expirationDate: '2026-11-20', strike: '180', optionType: 'PUT', action: 'BTC', netAmount: '200.00', tradedAtLocal: '2026-10-31T23:30' })
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', contracts: 2, netAmount: '698.70', time: '2026-11-02T10:30' })
    await apiTrade({ action: 'BTO', symbol: 'MSFT', expiration: '2027-01-15', strike: '450', type: 'CALL', netAmount: '900.00', time: '2026-11-02T14:00' })
    await apiTrade({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-11-02T15:00', multiplier: 10 })
    await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', contracts: 2, netAmount: '1000.00', time: '2026-11-03T09:45' })
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-03T11:00' })
    await apiClose({ symbol: 'AAPL', expirationDate: '2026-12-18', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 3, netAmount: '400.00', tradedAtLocal: '2026-11-05T10:00' })
    await apiClose({ symbol: 'XYZ', expirationDate: '2026-12-18', strike: '200', optionType: 'PUT', action: 'BTC', netAmount: '50.00', tradedAtLocal: '2026-11-06T10:00' })
    await apiClose({ symbol: 'MSFT', expirationDate: '2027-01-15', strike: '450', optionType: 'CALL', action: 'STC', netAmount: '1250.00', tradedAtLocal: '2026-11-06T23:30' })
    await apiClose({ symbol: 'AAPL', expirationDate: '2026-12-18', strike: '210', optionType: 'CALL', action: 'STC', contracts: 2, netAmount: '640.00', tradedAtLocal: '2026-11-07T10:00' })
    const c = new ClosedUi(page)
    await c.open()
    await expect(c.count).toHaveText('共 6 笔平仓')
    await expect(c.total).toHaveText('+703.05')
    expect((await c.cells()).map((x) => x.type)).toEqual(['期权', '期权', '期权', '期权', '期权', '期权'])
    await page.getByTestId('nav-pnl-summary').click()
    await expectSumTotals(page, '6', '+703.05', '期权 6 笔 +703.05 · 股票 0 笔 0.00')
    await expect.poll(() => sumRows(page, 'symbol')).toEqual([
      'AAPL 3 +248.70 — +248.70',
      'MSFT 1 +350.00 — +350.00',
      'TSLA 1 +120.00 — +120.00',
      `XYZ 1 ${MINUS}15.65 — ${MINUS}15.65`,
    ])
    await expect.poll(() => sumRows(page, 'month')).toEqual(['2026-11 4 +633.05 — +633.05', '2026-10 2 +70.00 — +70.00'])
    expect(await sumTotalRow(page, 'month')).toBe('6 +703.05 — +703.05')
    await page.getByTestId('nav-home').click()
    await expectHome(page, '+703.05', '6', '+633.05', '4', '0', '0')
  })

  test('AC16 空状态：只有入金、买入、转入 → 已平仓"暂无平仓记录" 0 笔 0.00；汇总"暂无平仓记录"；首页 0.00（0 笔）、股票持仓 2 个标的', async ({ page }) => {
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await apiStock('BUY', 'IXQZ', 10, '80.00', '2026-11-03T10:00')
    await apiStock('TRANSFER_IN', 'QRTV', 10, '70.00', '2026-10-01')
    const c = new ClosedUi(page)
    await c.open()
    await expect(page.getByTestId('closed-empty')).toHaveText('暂无平仓记录')
    await expect(c.count).toHaveText('共 0 笔平仓')
    await expect(c.total).toHaveText('0.00')
    await page.getByTestId('nav-pnl-summary').click()
    await expect(page.getByTestId('pnl-empty')).toHaveText('暂无平仓记录')
    await expectSumTotals(page, '0', '0.00', '期权 0 笔 0.00 · 股票 0 笔 0.00')
    await page.getByTestId('nav-home').click()
    await expectHome(page, '0.00', '0', '0.00', '0', '0', '2')
    await expect(page.getByTestId('dashboard-stock-positions-link')).toBeVisible()
  })

  test('AC17 股票页说明', async ({ page }) => {
    await page.goto('/stocks')
    await expect(page.getByTestId('stock-list-note')).toHaveText('金额均已含手续费；每股价格 = 金额 ÷ 股数，仅供参考；股票已实现盈亏同时计入已平仓页、盈亏汇总和首页')
  })

  test('边界：盈亏 0.00 的卖出显示 0.00 不着色、计 1 笔；首页股票持仓 0 个标的也显示', async ({ page }) => {
    await apiStock('TRANSFER_IN', 'QFZ', 100, '300.00', '2026-10-01')
    await apiStock('SELL', 'QFZ', 100, '300.00', '2026-11-06T16:00')
    const c = new ClosedUi(page)
    await c.open()
    await expect(c.count).toHaveText('共 1 笔平仓')
    await expect(c.total).toHaveText('0.00')
    await expect(c.row('S2').getByTestId('closed-cell-pnl')).toHaveText('0.00')
    expect(await colorOf(c.row('S2').getByTestId('closed-cell-pnl'))).toBe('other')
    await page.getByTestId('nav-pnl-summary').click()
    await expectSumTotals(page, '1', '0.00', '期权 0 笔 0.00 · 股票 1 笔 0.00')
    await expect.poll(() => sumRows(page, 'symbol')).toEqual(['QFZ 1 — 0.00 0.00'])
    await page.getByTestId('nav-home').click()
    await expectHome(page, '0.00', '1', '0.00', '1', '0', '0')
  })

  test('后端报错：已平仓、汇总、首页 500 显示错误不崩溃；后端返回 type 字段错误时显示在类型下', async ({ page }) => {
    await scenarioM()
    const body = JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/x', timestamp: '2026-11-13T15:00:00Z', details: [] })
    await page.route((u) => u.pathname.startsWith('/api/closed-trades'), (r) => r.fulfill({ status: 500, contentType: 'application/json', body }))
    await page.route((u) => u.pathname.startsWith('/api/pnl-summary'), (r) => r.fulfill({ status: 500, contentType: 'application/json', body }))
    await page.route((u) => u.pathname.startsWith('/api/dashboard'), (r) => r.fulfill({ status: 500, contentType: 'application/json', body }))
    await page.goto('/closed')
    await expect(page.getByTestId('closed-error')).toBeVisible()
    await page.goto('/pnl-summary')
    await expect(page.getByTestId('pnl-error')).toBeVisible()
    await page.goto('/')
    await expect(page.getByTestId('dashboard-pnl-error')).toBeVisible()
    await expect(page.getByTestId('dashboard-positions-error')).toBeVisible()
    await page.unrouteAll()

    const bad = JSON.stringify({ code: 'BAD_REQUEST', message: 'Invalid parameter value', status: 400, path: '/api/closed-trades', timestamp: '2026-11-13T15:00:00Z', details: [{ field: 'type', message: '类型只能是 OPTION 或 STOCK' }] })
    await page.route((u) => u.pathname.startsWith('/api/closed-trades'), (r) => r.fulfill({ status: 400, contentType: 'application/json', body: bad }))
    const c = new ClosedUi(page)
    await c.open('?type=STOCK')
    await expect(c.fieldError('closed-filter-record-type')).toHaveText('类型只能是 OPTION 或 STOCK')
    await page.unrouteAll()
    await c.open()
    await c.expect(['O4', 'S7', 'S6', 'S3', 'O2'], 5, '+141.51')
  })
})
