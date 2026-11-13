import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { BACKEND_URL, e2eBackend, setApiAccount } from '../support/backend'
import { apiTrade } from '../support/trade'
import { addDays, nyToday } from '../support/ny'

/**
 * FEAT-011 股票交易与股票持仓 E2E (real clock; all scenario dates are before 2026-11-13). The DB is reset before
 * every test, so scenario S gives deposit #1 and stock records S1–S5 = #1–#5. Tickers are fictional.
 */

const BACKEND = BACKEND_URL

type StockType = 'BUY' | 'SELL' | 'TRANSFER_IN'

async function apiStock(type: StockType, symbol: string, shares: number, amount: string, time: string, note: string | null = null): Promise<number> {
  const r = await fetch(`${BACKEND}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      type,
      symbol,
      shares,
      amount,
      ...(type === 'TRANSFER_IN' ? { tradeDate: time } : { tradedAtLocal: time }),
      note,
    }),
  })
  if (r.status !== 201) throw new Error(`seed stock ${type} ${symbol} failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiGet<T>(path: string): Promise<T> {
  return (await (await fetch(`${BACKEND}${path}`)).json()) as T
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

/** Scenario S: D1 + S1..S5 (ids 1..5). Cash 9,099.35. */
async function scenarioS(): Promise<void> {
  await apiTransfer('DEPOSIT', '10000.00', '2026-11-02T10:00')
  await apiStock('TRANSFER_IN', 'IXQZ', 100, '750.00', '2026-10-01')
  await apiStock('BUY', 'IXQZ', 200, '1500.65', '2026-11-03T10:00')
  await apiStock('BUY', 'XYZ', 300, '1000.00', '2026-11-04T10:00')
  await apiStock('SELL', 'IXQZ', 150, '1200.00', '2026-11-05T10:00')
  await apiStock('SELL', 'XYZ', 100, '400.00', '2026-11-06T10:00')
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

interface StockRowText {
  id: string
  time: string
  type: string
  symbol: string
  shares: string
  amount: string
  price: string
  pnl: string
  note: string
  history: boolean
}

class StockUi {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  /** antd puts the modal testid on a zero-size root; the visible part is the dialog inside it. */
  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  row(id: number): Locator {
    return this.page.locator(`[data-testid="stock-row"][data-stock-trade-id="${id}"]`)
  }

  posRow(symbol: string): Locator {
    return this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
  }

  async openList(query = ''): Promise<void> {
    await this.page.goto(`/stocks${query}`)
    await expect(this.id('stock-list-page')).toBeVisible()
  }

  async rows(): Promise<StockRowText[]> {
    return this.page.getByTestId('stock-row').evaluateAll((rows) =>
      rows.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        return {
          id: r.getAttribute('data-stock-trade-id') ?? '',
          time: t('stock-cell-time'),
          type: t('stock-cell-type'),
          symbol: t('stock-cell-symbol'),
          shares: t('stock-cell-shares'),
          amount: t('stock-cell-amount'),
          price: t('stock-cell-price'),
          pnl: t('stock-cell-pnl'),
          note: t('stock-cell-note'),
          history: r.querySelector('[data-testid="stock-history"]') !== null,
        }
      }),
    )
  }

  async expectIds(ids: number[]): Promise<void> {
    await expect.poll(async () => (await this.rows()).map((r) => Number(r.id))).toEqual(ids)
  }

  async positionRows(): Promise<string[]> {
    return this.page.getByTestId('stock-position-row').evaluateAll((rows) =>
      rows.map((r) =>
        ['stock-position-symbol', 'stock-position-shares', 'stock-position-total-cost', 'stock-position-average-cost']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async lotRows(scope: Locator): Promise<string[]> {
    return scope.getByTestId('stock-lot-row').evaluateAll((rows) =>
      rows.map((r) =>
        ['stock-lot-source', 'stock-lot-time', 'stock-lot-original-shares', 'stock-lot-remaining-shares', 'stock-lot-remaining-cost']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' | '),
      ),
    )
  }

  async soldLotRows(scope: Locator): Promise<string[]> {
    return scope.getByTestId('sold-lot-row').evaluateAll((rows) =>
      rows.map((r) =>
        ['sold-lot-source', 'sold-lot-time', 'sold-lot-shares', 'sold-lot-cost']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' | '),
      ),
    )
  }

  fieldError(field: Locator): Locator {
    return this.page.locator('.ant-form-item').filter({ has: field }).locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
  }

  async openEntry(query = ''): Promise<void> {
    await this.page.goto(`/stocks/new${query}`)
    await expect(this.id('stock-entry-form')).toBeVisible()
    await expect(this.id('cash-summary-total')).toBeVisible()
  }

  async chooseType(type: StockType): Promise<void> {
    const suffix = type === 'TRANSFER_IN' ? 'transfer-in' : type.toLowerCase()
    await this.id(`stock-entry-type-${suffix}`).check()
  }

  async fillEntry(e: { type?: StockType; symbol?: string; shares?: string; amount?: string; time?: string; date?: string; note?: string }): Promise<void> {
    if (e.type) await this.chooseType(e.type)
    if (e.symbol !== undefined) await this.id('stock-entry-symbol').fill(e.symbol)
    if (e.shares !== undefined) await this.id('stock-entry-shares').fill(e.shares)
    if (e.amount !== undefined) await this.id('stock-entry-amount').fill(e.amount)
    if (e.time !== undefined) await this.id('stock-entry-time').fill(e.time)
    if (e.date !== undefined) await this.id('stock-entry-date').fill(e.date)
    if (e.note !== undefined) await this.id('stock-entry-note').fill(e.note)
  }

  async auditRows(): Promise<{ op: string; object: string; summary: string; changes: string[] }[]> {
    await expect(this.page.getByTestId('audit-row').first()).toBeVisible()
    return this.page.getByTestId('audit-row').evaluateAll((rows) =>
      rows.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        const lines = (id: string) => Array.from(r.querySelectorAll(`[data-testid="${id}"]`)).map((e) => e.textContent?.trim() ?? '')
        return {
          op: t('audit-cell-operation'),
          object: t('audit-cell-object'),
          summary: t('audit-cell-summary'),
          changes: lines('audit-cell-changes-line').length ? lines('audit-cell-changes-line') : [t('audit-cell-changes')],
        }
      }),
    )
  }
}

const S_ROWS: Omit<StockRowText, 'note' | 'history'>[] = [
  { id: '5', time: '2026-11-06 10:00', type: '卖出', symbol: 'XYZ', shares: '100', amount: '+400.00', price: '每股 4.0000', pnl: '+66.67' },
  { id: '4', time: '2026-11-05 10:00', type: '卖出', symbol: 'IXQZ', shares: '150', amount: '+1,200.00', price: '每股 8.0000', pnl: '+74.84' },
  { id: '3', time: '2026-11-04 10:00', type: '买入', symbol: 'XYZ', shares: '300', amount: `${MINUS}1,000.00`, price: '每股 3.3333', pnl: '—' },
  { id: '2', time: '2026-11-03 10:00', type: '买入', symbol: 'IXQZ', shares: '200', amount: `${MINUS}1,500.65`, price: '每股 7.5033', pnl: '—' },
  { id: '1', time: '2026-10-01', type: '转入', symbol: 'IXQZ', shares: '100', amount: '总成本 750.00', price: '每股 7.5000', pnl: '—' },
]

test.describe('FEAT-011 股票交易与股票持仓', () => {
  test('测试支持：reset 清空股票交易，id 从 1 开始', async () => {
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await apiStock('BUY', 'IXQZ', 10, '80.00', '2026-11-03T10:00')
    await apiStock('TRANSFER_IN', 'IXQZ', 10, '70.00', '2026-10-01')
    const acc = await apiCreateAccount('重置账户')
    setApiAccount(acc)
    await apiStock('TRANSFER_IN', 'QRTV', 10, '70.00', '2026-10-01')
    setApiAccount(1)
    await e2eBackend.reset()
    expect((await apiGet<{ totalItems: number }>('/api/stock-trades')).totalItems).toBe(0)
    expect((await apiGet<{ positionCount: number }>('/api/stock-positions')).positionCount).toBe(0)
    const id = await apiStock('TRANSFER_IN', 'IXQZ', 1, '1.00', '2026-10-01')
    expect(id).toBe(1)
  })

  test('AC1 持仓页：期权部分为空；股票持仓 共 2 个标的、数字、展开批次', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await page.goto('/positions')
    await expect(ui.id('positions-empty')).toBeVisible()
    const card = ui.id('stock-positions')
    await expect(card).toContainText('股票持仓')
    await expect(ui.id('stock-positions-count')).toHaveText('共 2 个标的')
    await expect(card).toContainText('总成本含手续费，部分卖出按比例扣减成本（四舍五入到分）；暂不检查 covered call 的股票覆盖')
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 150 1,125.49 7.5033', 'XYZ 200 666.67 3.3334'])
    await ui.posRow('IXQZ').getByTestId('stock-position-expand').click()
    await ui.posRow('XYZ').getByTestId('stock-position-expand').click()
    await expect.poll(() => ui.lotRows(card)).toEqual([
      '买入 #2 | 2026-11-03 10:00 | 200 | 150 | 1,125.49',
      '买入 #3 | 2026-11-04 10:00 | 300 | 200 | 666.67',
    ])
  })

  test('AC1 持仓页没有股票持仓时显示"暂无股票持仓"', async ({ page }) => {
    const ui = new StockUi(page)
    await page.goto('/positions')
    await expect(ui.id('stock-positions-empty')).toContainText('暂无股票持仓')
    await expect(ui.id('stock-positions-count')).toHaveText('共 0 个标的')
  })

  test('AC2 股票页：顺序、金额/每股/盈亏格式与颜色、展开被卖出批次、无"历史"；资金流水股票行', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await page.goto('/')
    await ui.id('nav-stocks').click()
    await expect(ui.id('stock-list-page')).toBeVisible()
    // FEAT-040 AC17 changed the last clause of this note
    await expect(ui.id('stock-list-note')).toHaveText(
      '金额均已含手续费；每股价格 = 金额 ÷ 股数，仅供参考；股票已实现盈亏同时计入已平仓页、盈亏汇总和首页',
    )
    await expect
      .poll(async () => (await ui.rows()).map(({ note: _n, history: _h, ...rest }) => rest))
      .toEqual(S_ROWS)
    const rows = await ui.rows()
    expect(rows.every((r) => !r.history)).toBe(true)
    expect(rows.every((r) => r.note === '—')).toBe(true)
    await expect(ui.id('stock-list-total')).toHaveText('共 5 条')

    // colors: sell amount + pnl green, buy amount red, transfer neutral
    expect(await colorOf(ui.row(5).getByTestId('stock-cell-amount'))).toBe('green')
    expect(await colorOf(ui.row(5).getByTestId('stock-cell-pnl'))).toBe('green')
    expect(await colorOf(ui.row(3).getByTestId('stock-cell-amount'))).toBe('red')
    expect(await colorOf(ui.row(1).getByTestId('stock-cell-amount'))).toBe('other')
    // only sales are expandable
    await expect(ui.row(3).getByTestId('stock-expand')).toHaveCount(0)
    await expect(ui.row(1).getByTestId('stock-expand')).toHaveCount(0)

    await ui.row(4).getByTestId('stock-expand').click()
    const d4 = ui.id('stock-sold-detail').first()
    await expect.poll(() => ui.soldLotRows(d4)).toEqual([
      '转入 #1 | 2026-10-01 | 100 股 | 750.00',
      '买入 #2 | 2026-11-03 10:00 | 50 股 | 375.16',
    ])
    await expect(d4.getByTestId('stock-sold-cost')).toHaveText('1,125.16')
    await expect(d4.getByTestId('stock-sold-pnl')).toHaveText('+74.84')
    await ui.row(4).getByTestId('stock-expand').click()
    await ui.row(5).getByTestId('stock-expand').click()
    const d5 = ui.id('stock-sold-detail').first()
    await expect.poll(() => ui.soldLotRows(d5)).toEqual(['买入 #3 | 2026-11-04 10:00 | 100 股 | 333.33'])
    await expect(d5.getByTestId('stock-sold-pnl')).toHaveText('+66.67')

    // cash ledger
    const cash = new CashPage(page)
    await ui.id('nav-cash').click()
    await cash.expectSummary('9,099.35', '0.00', '9,099.35')
    await cash.expectRows([
      { type: '股票卖出', amount: '+400.00', balance: '9,099.35', note: '卖出 100 股 XYZ' },
      { type: '股票卖出', amount: '+1,200.00', balance: '8,699.35', note: '卖出 150 股 IXQZ' },
      { type: '股票买入', amount: `${MINUS}1,000.00`, balance: '7,499.35', note: '买入 300 股 XYZ' },
      { type: '股票买入', amount: `${MINUS}1,500.65`, balance: '8,499.35', note: '买入 200 股 IXQZ' },
      { type: '入金', amount: '+10,000.00', balance: '10,000.00' },
    ])
    // stock rows: no edit/delete, link to the stock page instead
    const stockLedgerRows = page.getByTestId('cash-ledger-row').filter({ hasText: '股票' })
    await expect(stockLedgerRows).toHaveCount(4)
    await expect(stockLedgerRows.getByTestId('cash-ledger-edit')).toHaveCount(0)
    await expect(stockLedgerRows.getByTestId('cash-ledger-delete')).toHaveCount(0)
    await expect(stockLedgerRows.getByTestId('cash-ledger-stocks-link')).toHaveCount(4)
  })

  test('AC3 筛选：标的、类型多选、日期、地址栏、刷新与后退、格式错误、无结果与清除', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await ui.openList()
    await ui.expectIds([5, 4, 3, 2, 1])

    await ui.id('stock-filter-symbol').fill('ixqz')
    await ui.id('stock-filter-submit').click()
    await ui.expectIds([4, 2, 1])
    await expect(page).toHaveURL(/symbol=ixqz/i)
    await page.reload()
    await ui.expectIds([4, 2, 1])
    await expect(ui.id('stock-filter-symbol')).toHaveValue(/ixqz/i)

    await ui.id('stock-filter-clear').click()
    await ui.expectIds([5, 4, 3, 2, 1])
    await ui.id('stock-filter-type-sell').check()
    await ui.id('stock-filter-submit').click()
    await ui.expectIds([5, 4])
    await expect(page).toHaveURL(/type=SELL/)

    await page.goBack()
    await ui.expectIds([5, 4, 3, 2, 1])
    await page.goBack()
    await ui.expectIds([4, 2, 1])
    await page.goForward()
    await page.goForward()
    await ui.expectIds([5, 4])

    await ui.openList('?type=TRANSFER_IN')
    await ui.expectIds([1])
    await expect(ui.id('stock-filter-type-transfer-in')).toBeChecked()
    await ui.id('stock-filter-type-buy').check()
    await ui.id('stock-filter-submit').click()
    await ui.expectIds([3, 2, 1])
    await expect(page).toHaveURL(/type=BUY%2CTRANSFER_IN|type=BUY,TRANSFER_IN|type=TRANSFER_IN%2CBUY|type=TRANSFER_IN,BUY/)

    await ui.openList()
    await ui.id('stock-filter-start-date').fill('2026-11-04')
    await ui.id('stock-filter-end-date').fill('2026-11-05')
    await ui.id('stock-filter-submit').click()
    await ui.expectIds([4, 3])
    await expect(page).toHaveURL(/startDate=2026-11-04/)

    await ui.openList('?endDate=2026-10-31')
    await ui.expectIds([1])

    // invalid symbol → error under the symbol field, no query
    await ui.openList()
    await ui.expectIds([5, 4, 3, 2, 1])
    let listRequests = 0
    page.on('request', (r) => {
      if (r.url().includes('/api/stock-trades?') && r.url().includes('%24IXQZ')) listRequests++
    })
    await ui.id('stock-filter-symbol').fill('$IXQZ')
    await ui.id('stock-filter-submit').click()
    await expect(ui.fieldError(ui.id('stock-filter-symbol'))).toHaveText('标的只能包含字母、数字和点')
    await expect(ui.id('stock-list-invalid')).toBeVisible()
    expect(listRequests).toBe(0)

    // invalid URL params don't crash
    await ui.openList('?type=FOO&startDate=2026-02-30&page=abc')
    await expect(ui.id('stock-list-invalid')).toBeVisible()

    // no match → message + clear
    await ui.openList('?symbol=QRTV')
    await expect(ui.id('stock-list-no-match')).toContainText('没有符合条件的股票交易')
    await ui.id('stock-list-no-match-clear').click()
    await ui.expectIds([5, 4, 3, 2, 1])
  })

  test('AC3 空状态与分页：暂无股票交易；21 条记录 → 2 页，每页条数写在地址栏', async ({ page }) => {
    const ui = new StockUi(page)
    await ui.openList()
    await expect(ui.id('stock-list-empty')).toContainText('暂无股票交易')
    for (let i = 1; i <= 21; i++) {
      await apiStock('TRANSFER_IN', 'QPAG', i, '10.00', `2026-10-${String(i).padStart(2, '0')}`)
    }
    await ui.openList()
    await expect(page.getByTestId('stock-row')).toHaveCount(20)
    await expect(ui.id('stock-list-total')).toHaveText('共 21 条')
    await ui.id('stock-list-pagination').getByTitle('2', { exact: true }).click()
    await ui.expectIds([1])
    await expect(page).toHaveURL(/page=2/)
    await page.reload()
    await ui.expectIds([1])
    await page.locator('label').filter({ has: ui.id('stock-list-size-50') }).click()
    await expect(page.getByTestId('stock-row')).toHaveCount(21)
    await expect(page).toHaveURL(/size=50/)
    await expect(page).not.toHaveURL(/page=2/)
    // past the last page → jumps to the last page
    await ui.openList('?page=9&size=20')
    await ui.expectIds([1])
  })

  test('录入：买入 / 转入 / 卖出的表单、金额标签、转入说明、结果显示；列表与持仓更新', async ({ page }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-11-02T10:00')
    const ui = new StockUi(page)
    await ui.openList()
    await ui.id('stock-list-new').click()
    await expect(page).toHaveURL(/\/stocks\/new/)
    await expect(ui.id('cash-summary-total')).toHaveText('10,000.00')
    // no default type
    for (const t of ['buy', 'sell', 'transfer-in']) await expect(ui.id(`stock-entry-type-${t}`)).not.toBeChecked()
    await expect(ui.id('stock-entry-amount-label')).toHaveText('金额')

    // 转入
    await ui.chooseType('TRANSFER_IN')
    await expect(ui.id('stock-entry-amount-label')).toHaveText('总成本')
    await expect(ui.id('stock-entry-transfer-hint')).toHaveText('用于系统上线前已持有的股票（例如之前被指派得到的股票），不会从现金中扣款')
    await expect(ui.id('stock-entry-date-hint')).toHaveText('期初持仓：记在该日开始（美东 00:00），不影响现金')
    await expect(ui.id('stock-entry-time')).toHaveCount(0)
    await expect(ui.id('stock-entry-date')).toHaveValue(nyToday())
    await ui.fillEntry({ symbol: 'IXQZ', shares: '100', amount: '750', date: '2026-10-01' })
    await ui.id('stock-entry-submit').click()
    const conf = ui.id('stock-entry-confirmation')
    await expect(conf).toBeVisible()
    await expect(ui.id('stock-entry-confirmation-type')).toHaveText('转入')
    await expect(ui.id('stock-entry-confirmation-symbol')).toHaveText('IXQZ')
    await expect(ui.id('stock-entry-confirmation-shares')).toHaveText('100 股')
    await expect(ui.id('stock-entry-confirmation-amount')).toHaveText('750.00')
    await expect(ui.id('stock-entry-confirmation-price')).toHaveText('7.5000')
    await expect(ui.id('stock-entry-confirmation-time')).toHaveText('2026-10-01')
    await expect(ui.id('stock-entry-confirmation-cash-change')).toHaveText('0.00')
    await expect(ui.id('cash-summary-total')).toHaveText('10,000.00')

    // 买入（标的 trim + 大写由后端处理）
    await ui.chooseType('BUY')
    await expect(ui.id('stock-entry-amount-label')).toHaveText('实付总额（含手续费）')
    await expect(ui.id('stock-entry-transfer-hint')).toHaveCount(0)
    await expect(ui.id('stock-entry-time')).toHaveValue(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/)
    await ui.fillEntry({ symbol: ' ixqz ', shares: '200', amount: '1500.65', time: '2026-11-03T10:00', note: '上线后买入' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-confirmation-type')).toHaveText('买入')
    await expect(ui.id('stock-entry-confirmation-symbol')).toHaveText('IXQZ')
    await expect(ui.id('stock-entry-confirmation-price')).toHaveText('7.5033')
    await expect(ui.id('stock-entry-confirmation-time')).toHaveText('2026-11-03 10:00')
    await expect(ui.id('stock-entry-confirmation-cash-change')).toHaveText(`${MINUS}1,500.65`)
    await expect(ui.id('stock-entry-confirmation-sold-lots')).toHaveCount(0)
    await expect(ui.id('cash-summary-total')).toHaveText('8,499.35')

    // 卖出：跨批次
    await ui.chooseType('SELL')
    await expect(ui.id('stock-entry-amount-label')).toHaveText('实收净额（已扣手续费）')
    await ui.fillEntry({ symbol: 'IXQZ', shares: '150', amount: '1200.00', time: '2026-11-05T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-confirmation-type')).toHaveText('卖出')
    await expect(ui.id('stock-entry-confirmation-cash-change')).toHaveText('+1,200.00')
    await expect(ui.id('stock-entry-confirmation-sold-cost')).toHaveText('1,125.16')
    await expect(ui.id('stock-entry-confirmation-realized-pnl')).toHaveText('+74.84')
    expect(await colorOf(ui.id('stock-entry-confirmation-realized-pnl'))).toBe('green')
    await expect.poll(() => ui.soldLotRows(ui.id('stock-entry-confirmation-sold-lots'))).toEqual([
      '转入 #1 | 2026-10-01 | 100 股 | 750.00',
      '买入 #2 | 2026-11-03 10:00 | 50 股 | 375.16',
    ])
    await expect(ui.id('cash-summary-total')).toHaveText('9,699.35')

    // 亏损的卖出 → 红色
    await ui.chooseType('SELL')
    await ui.fillEntry({ symbol: 'IXQZ', shares: '50', amount: '300.00', time: '2026-11-06T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-confirmation-realized-pnl')).toHaveText(`${MINUS}75.16`)
    expect(await colorOf(ui.id('stock-entry-confirmation-realized-pnl'))).toBe('red')

    await ui.id('nav-stocks').click()
    await ui.expectIds([4, 3, 2, 1])
    await expect(ui.row(2).getByTestId('stock-cell-note')).toHaveText('上线后买入')
    await ui.id('nav-positions').click()
    await expect(ui.id('stock-positions-count')).toHaveText('共 1 个标的')
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 100 750.33 7.5033'])
  })

  test('录入被拒：冻结的现金不能买股票（原文、保留输入）；持股不足；补录卖出', async ({ page }) => {
    // AC5 small-account numbers, in the default account
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: '2026-12-18', strike: '5', type: 'PUT', netAmount: '20.00', time: '2026-11-03T09:00' })
    const ui = new StockUi(page)
    await ui.openEntry()
    await expect(ui.id('cash-summary-total')).toHaveText('1,020.00')
    await expect(ui.id('cash-summary-frozen')).toHaveText('500.00')
    await expect(ui.id('cash-summary-available')).toHaveText('520.00')
    await ui.fillEntry({ type: 'BUY', symbol: 'QRTV', shares: '100', amount: '600.00', time: '2026-11-03T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-error')).toHaveText(`可用现金不足：需要 600.00；2026-11-03 10:00 时可用现金将变为 ${MINUS}80.00`)
    await expect(ui.id('stock-entry-error')).toHaveAttribute('data-error-code', 'CASH_INSUFFICIENT')
    await expect(ui.id('stock-entry-symbol')).toHaveValue('QRTV')
    await expect(ui.id('stock-entry-shares')).toHaveValue('100')
    await expect(ui.id('stock-entry-amount')).toHaveValue('600.00')
    await expect(ui.id('stock-entry-type-buy')).toBeChecked()
    await expect(ui.id('stock-entry-confirmation')).toHaveCount(0)
    await expect(ui.id('cash-summary-available')).toHaveText('520.00')

    await ui.id('stock-entry-amount').fill('520.00')
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-confirmation')).toBeVisible()
    await expect(ui.id('stock-entry-error')).toHaveCount(0)
    await expect(ui.id('cash-summary-total')).toHaveText('500.00')
    await expect(ui.id('cash-summary-available')).toHaveText('0.00')

    // SHARES_INSUFFICIENT
    await ui.fillEntry({ type: 'SELL', symbol: 'QRTV', shares: '101', amount: '600.00', time: '2026-11-04T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-error')).toHaveText('持股不足：2026-11-04 10:00 卖出 QRTV 101 股，当时持有 100 股')
    await expect(ui.id('stock-entry-error')).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(ui.id('stock-entry-shares')).toHaveValue('101')

    // 补录卖出让之后的卖出不够
    await apiStock('SELL', 'QRTV', 100, '530.00', '2026-11-06T10:00')
    await ui.fillEntry({ shares: '50', amount: '260.00', time: '2026-11-05T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-error')).toHaveText('补录后 2026-11-06 10:00 的卖出 QRTV 100 股将超过当时持股 50 股')
  })

  test('录入格式校验：字段下报错、不发请求；时间/日期晚于当前（运行时刻 + 1 天）', async ({ page }) => {
    const ui = new StockUi(page)
    let posts = 0
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/api/stock-trades')) posts++
    })
    await ui.openEntry()
    await ui.id('stock-entry-submit').click()
    await expect(ui.fieldError(ui.id('stock-entry-type'))).toHaveText('请选择类型')
    await expect(ui.fieldError(ui.id('stock-entry-symbol'))).toHaveText('标的不能为空')
    await expect(ui.fieldError(ui.id('stock-entry-shares'))).toHaveText('股数不能为空')
    await expect(ui.fieldError(ui.id('stock-entry-amount'))).toHaveText('金额不能为空')

    await ui.fillEntry({ type: 'BUY', symbol: 'IX QZ', shares: '1.5', amount: '1.234', note: 'x'.repeat(501) })
    await ui.id('stock-entry-submit').click()
    await expect(ui.fieldError(ui.id('stock-entry-symbol'))).toHaveText('标的只能包含字母、数字和点')
    await expect(ui.fieldError(ui.id('stock-entry-shares'))).toHaveText('股数必须是正整数')
    await expect(ui.fieldError(ui.id('stock-entry-amount'))).toHaveText('金额最多 2 位小数')
    await expect(ui.fieldError(ui.id('stock-entry-note'))).toHaveText('备注最多 500 个字符')

    const cases: [string, Locator, string, string][] = [
      ['symbol', ui.id('stock-entry-symbol'), '$IXQZ', '标的只能包含字母、数字和点'],
      ['symbol', ui.id('stock-entry-symbol'), 'ABCDEFGHIJK', '标的最多 10 个字符'],
      ['shares', ui.id('stock-entry-shares'), '0', '股数必须是正整数'],
      ['shares', ui.id('stock-entry-shares'), '-5', '股数必须是正整数'],
      ['shares', ui.id('stock-entry-shares'), '10000001', '股数过大'],
      ['amount', ui.id('stock-entry-amount'), '0', '金额必须大于 0'],
      ['amount', ui.id('stock-entry-amount'), '-1.00', '金额必须大于 0'],
      ['amount', ui.id('stock-entry-amount'), '10000000000000.00', '金额过大'],
    ]
    for (const [, field, value, msg] of cases) {
      await field.fill(value)
      await ui.id('stock-entry-submit').click()
      await expect(ui.fieldError(field), `${value}`).toHaveText(msg)
    }
    const tomorrow = addDays(nyToday(), 1)
    await ui.fillEntry({ time: `${tomorrow}T10:00` })
    await ui.id('stock-entry-submit').click()
    await expect(ui.fieldError(ui.id('stock-entry-time'))).toHaveText('成交时间不能晚于当前时间')
    await ui.chooseType('TRANSFER_IN')
    await ui.fillEntry({ date: tomorrow })
    await ui.id('stock-entry-submit').click()
    await expect(ui.fieldError(ui.id('stock-entry-date'))).toHaveText('日期不能晚于今天')
    expect(posts).toBe(0)

    // legal edges are accepted (10,000,000 shares transfer; BRK.B)
    await ui.fillEntry({ symbol: 'BRK.B', shares: '10000000', amount: '9999999999999.99', date: nyToday(), note: 'x'.repeat(500) })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-confirmation-symbol')).toHaveText('BRK.B')
    await expect(ui.id('stock-entry-confirmation-shares')).toHaveText('10,000,000 股')
    expect(posts).toBe(1)
  })

  test('后端报错：股票页、持仓股票部分、录入提交的 500 都显示错误提示、不崩溃', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    const body = JSON.stringify({ code: 'INTERNAL_ERROR', message: 'Internal server error', status: 500, path: '/api/x', timestamp: '2026-11-13T15:00:00Z', details: [] })
    await page.route('**/api/stock-trades**', (route) => route.fulfill({ status: 500, contentType: 'application/json', body }))
    await page.route('**/api/stock-positions', (route) => route.fulfill({ status: 500, contentType: 'application/json', body }))
    await ui.openList()
    await expect(ui.id('stock-list-error')).toBeVisible()
    await page.goto('/positions')
    await expect(ui.id('stock-positions-error')).toBeVisible()
    await ui.openEntry()
    await ui.fillEntry({ type: 'TRANSFER_IN', symbol: 'IXQZ', shares: '1', amount: '1.00', date: '2026-10-01' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-error')).toBeVisible()
    await expect(ui.id('stock-entry-symbol')).toHaveValue('IXQZ')
    await page.unroute('**/api/stock-trades**')
    await page.unroute('**/api/stock-positions')
    await ui.openList()
    await ui.expectIds([5, 4, 3, 2, 1])
  })

  test('AC14 持仓页"卖出"预填并成功；点标的进入股票页筛选；资金页"在股票交易中修改"', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await page.goto('/positions')
    await ui.posRow('IXQZ').getByTestId('stock-position-sell').click()
    await expect(page).toHaveURL(/\/stocks\/new\?.*type=SELL/)
    await expect(ui.id('stock-entry-type-sell')).toBeChecked()
    await expect(ui.id('stock-entry-symbol')).toHaveValue('IXQZ')
    await ui.fillEntry({ shares: '50', amount: '400.00', time: '2026-11-12T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-confirmation-realized-pnl')).toHaveText('+24.84')
    await expect(ui.id('stock-entry-confirmation-sold-cost')).toHaveText('375.16')

    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 100 750.33 7.5033', 'XYZ 200 666.67 3.3334'])

    await ui.posRow('IXQZ').getByTestId('stock-position-symbol').click()
    await expect(page).toHaveURL(/\/stocks\?.*symbol=IXQZ/)
    await ui.expectIds([6, 4, 2, 1])
    await expect(ui.id('stock-filter-symbol')).toHaveValue('IXQZ')

    // symbol click inside the list filters as well
    await ui.openList()
    await ui.row(3).getByTestId('stock-cell-symbol').click()
    await ui.expectIds([5, 3])

    await page.goto('/cash')
    const s2Row = page.getByTestId('cash-ledger-row').filter({ hasText: '买入 200 股 IXQZ' })
    await s2Row.getByTestId('cash-ledger-stocks-link').click()
    await expect(page).toHaveURL(/\/stocks$/)
    await expect(ui.row(2)).toBeVisible()
    await expect(ui.row(2).getByTestId('stock-cell-amount')).toHaveText(`${MINUS}1,500.65`)
  })

  test('AC8 修改 S2 金额：对话框只读标的/类型；无需刷新各页更新；历史只在 S2；审计内容（AC15）', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await page.goto('/')
    await ui.id('nav-stocks').click()
    await ui.expectIds([5, 4, 3, 2, 1])
    await ui.row(2).getByTestId('stock-edit').click()
    const d = ui.dialog('stock-edit-modal')
    await expect(d).toBeVisible()
    await expect(d.getByTestId('stock-edit-type')).toHaveText('买入')
    await expect(d.getByTestId('stock-edit-symbol')).toHaveText('IXQZ')
    await expect(d.getByTestId('stock-edit-identity-hint')).toHaveText('标的或类型录错请删除后重新录入')
    await expect(d.locator('input[data-testid="stock-edit-symbol"], [data-testid="stock-edit-symbol"] input')).toHaveCount(0)
    await expect(d.getByTestId('stock-edit-amount-label')).toHaveText('实付总额（含手续费）')
    await expect(d.getByTestId('stock-edit-shares')).toHaveValue('200')
    await expect(d.getByTestId('stock-edit-amount')).toHaveValue('1500.65')
    await expect(d.getByTestId('stock-edit-time')).toHaveValue(/^2026-11-03T10:00/)
    await expect(d.getByTestId('stock-edit-date')).toHaveCount(0)

    // no change → 没有修改, dialog stays
    await d.getByTestId('stock-edit-submit').click()
    await expect(d.getByTestId('stock-edit-error')).toHaveText('没有修改')
    // shrinking shares → rejected verbatim, input kept
    await d.getByTestId('stock-edit-shares').fill('40')
    await d.getByTestId('stock-edit-submit').click()
    await expect(d.getByTestId('stock-edit-error')).toHaveText('修改后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 140 股')
    await expect(d).toBeVisible()
    await expect(d.getByTestId('stock-edit-shares')).toHaveValue('40')

    await d.getByTestId('stock-edit-shares').fill('200')
    await d.getByTestId('stock-edit-amount').fill('1500.00')
    await d.getByTestId('stock-edit-submit').click()
    await expect(d).toHaveCount(0)
    await expect(ui.row(2).getByTestId('stock-cell-amount')).toHaveText(`${MINUS}1,500.00`)
    await expect(ui.row(4).getByTestId('stock-cell-pnl')).toHaveText('+75.00')
    const rows = await ui.rows()
    expect(rows.filter((r) => r.history).map((r) => r.id)).toEqual(['2'])

    // other pages, no reload (SPA navigation)
    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 150 1,125.00 7.5000', 'XYZ 200 666.67 3.3334'])
    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('9,100.00', '0.00', '9,100.00')
    await expect(page.getByTestId('cash-ledger-row')).toHaveCount(5)
    await expect(page.getByTestId('cash-ledger-row').filter({ hasText: '买入 200 股 IXQZ' }).getByTestId('cash-ledger-amount')).toHaveText(`${MINUS}1,500.00`)
    await ui.id('nav-audit').click()
    expect((await ui.auditRows())[0]).toEqual({ op: '修改', object: '股票交易 #2', summary: '买入 IXQZ 200 股', changes: ['金额：1,500.65 → 1,500.00'] })

    // 历史 → audit filtered by the record, expandable snapshot
    await ui.id('nav-stocks').click()
    await ui.row(2).getByTestId('stock-history').click()
    await expect(page).toHaveURL(/\/audit\?.*objectType=STOCK_TRADE.*objectId=2/)
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    await expect(ui.id('audit-filter-type-stock')).toBeChecked()
    await expect(ui.id('audit-filter-id')).toHaveValue('2')
    await page.getByTestId('audit-row').first().getByTestId('audit-expand').click()
    const detail = ui.id('audit-detail').first()
    await expect(detail.getByTestId('audit-before-type')).toHaveText('买入')
    await expect(detail.getByTestId('audit-before-symbol')).toHaveText('IXQZ')
    await expect(detail.getByTestId('audit-before-shares')).toHaveText('200')
    await expect(detail.getByTestId('audit-before-amount')).toHaveText('1,500.65')
    await expect(detail.getByTestId('audit-before-tradedAtLocal')).toHaveText('2026-11-03 10:00')
    await expect(detail.getByTestId('audit-before-cashChange')).toHaveText(`${MINUS}1,500.65`)
    await expect(detail.getByTestId('audit-after-amount')).toHaveText('1,500.00')
    await expect(detail.getByTestId('audit-after-cashChange')).toHaveText(`${MINUS}1,500.00`)
  })

  test('AC8 修改转入日期：日期输入、FIFO 重新排序、审计"日期"', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await ui.openList()
    await ui.row(1).getByTestId('stock-edit').click()
    const d = ui.dialog('stock-edit-modal')
    await expect(d.getByTestId('stock-edit-type')).toHaveText('转入')
    await expect(d.getByTestId('stock-edit-amount-label')).toHaveText('总成本')
    await expect(d.getByTestId('stock-edit-time')).toHaveCount(0)
    await expect(d.getByTestId('stock-edit-date')).toHaveValue('2026-10-01')
    await d.getByTestId('stock-edit-date').fill('2026-11-04')
    await d.getByTestId('stock-edit-submit').click()
    await expect(d).toHaveCount(0)
    await expect(ui.row(1).getByTestId('stock-cell-time')).toHaveText('2026-11-04')
    await ui.expectIds([5, 4, 3, 1, 2])
    await expect(ui.row(4).getByTestId('stock-cell-pnl')).toHaveText('+74.51')
    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 150 1,125.16 7.5011', 'XYZ 200 666.67 3.3334'])
    await ui.posRow('IXQZ').getByTestId('stock-position-expand').click()
    await expect.poll(() => ui.lotRows(ui.id('stock-positions'))).toEqual([
      '买入 #2 | 2026-11-03 10:00 | 200 | 50 | 375.16',
      '转入 #1 | 2026-11-04 | 100 | 100 | 750.00',
    ])
    await ui.id('nav-audit').click()
    expect((await ui.auditRows())[0]).toEqual({ op: '修改', object: '股票交易 #1', summary: '转入 IXQZ 100 股', changes: ['日期：2026-10-01 → 2026-11-04'] })
  })

  test('AC9 删除：确认框摘要与警告；转入删除成功；被用到的买入删除被拒（对话框不关）；审计筛选"股票交易"', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await ui.openList()
    // S4 summary
    await ui.row(4).getByTestId('stock-delete').click()
    let d = ui.dialog('stock-delete-modal')
    await expect(d.getByTestId('stock-delete-summary')).toHaveText('卖出 150 股 IXQZ，2026-11-05 10:00，+1,200.00')
    await d.getByTestId('stock-delete-cancel').click()
    await expect(d).toHaveCount(0)

    // S2 rejected
    await ui.row(2).getByTestId('stock-delete').click()
    d = ui.dialog('stock-delete-modal')
    await expect(d.getByTestId('stock-delete-summary')).toHaveText(`买入 200 股 IXQZ，2026-11-03 10:00，${MINUS}1,500.65`)
    await d.getByTestId('stock-delete-confirm').click()
    await expect(d.getByTestId('stock-delete-error')).toHaveText('删除后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股')
    await expect(d).toBeVisible()
    await d.getByTestId('stock-delete-cancel').click()
    await ui.expectIds([5, 4, 3, 2, 1])

    // S1 deleted
    await ui.row(1).getByTestId('stock-delete').click()
    d = ui.dialog('stock-delete-modal')
    await expect(d.getByTestId('stock-delete-summary')).toHaveText('转入 100 股 IXQZ，2026-10-01，总成本 750.00')
    await expect(d.getByTestId('stock-delete-warning')).toContainText('删除后不可恢复，会写入审计记录')
    await d.getByTestId('stock-delete-confirm').click()
    await expect(d).toHaveCount(0)
    await ui.expectIds([5, 4, 3, 2])
    await expect(ui.row(4).getByTestId('stock-cell-pnl')).toHaveText('+74.51')

    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 50 375.16 7.5032', 'XYZ 200 666.67 3.3334'])
    await ui.id('nav-cash').click()
    await new CashPage(page).expectSummary('9,099.35', '0.00', '9,099.35')

    await ui.id('nav-audit').click()
    await ui.id('audit-filter-type-stock').check()
    await ui.id('audit-filter-submit').click()
    await expect(page).toHaveURL(/objectType=STOCK_TRADE/)
    expect(await ui.auditRows()).toEqual([{ op: '删除', object: '股票交易 #1', summary: '转入 IXQZ 100 股', changes: ['已删除'] }])
    await page.getByTestId('audit-row').first().getByTestId('audit-expand').click()
    const detail = ui.id('audit-detail').first()
    await expect(detail.getByTestId('audit-before-type')).toHaveText('转入')
    await expect(detail.getByTestId('audit-before-amount')).toHaveText('750.00')
    await expect(detail.getByTestId('audit-before-tradeDate')).toHaveText('2026-10-01')
    await expect(detail.getByTestId('audit-before-cashChange')).toHaveText('0.00')
    await expect(detail).toContainText('总成本')
    await expect(detail).toContainText('已删除')
  })

  test('审计：未被修改过的股票记录按对象筛选显示"录入后没有被修改或删除过"', async ({ page }) => {
    await scenarioS()
    await page.goto('/audit?objectType=STOCK_TRADE&objectId=3')
    await expect(page.getByTestId('audit-empty')).toBeVisible()
    await expect(page.getByTestId('audit-empty-hint')).toContainText('股票交易 #3 录入后没有被修改或删除过')
  })

  test('AC12 按账户隔离：小账户只看到自己的股票记录与持仓', async ({ page }) => {
    await scenarioS()
    const small = await apiCreateAccount('小账户')
    setApiAccount(small)
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await apiStock('BUY', 'QRTV', 100, '520.00', '2026-11-03T10:00')
    setApiAccount(1)
    const ui = new StockUi(page)
    await ui.openList()
    await ui.expectIds([5, 4, 3, 2, 1])
    await switchTo(page, small, '小账户')
    await ui.expectIds([6])
    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionRows()).toEqual(['QRTV 100 520.00 5.2000'])
    await ui.openEntry('?type=SELL&symbol=IXQZ')
    await expect(page.getByTestId('account-current')).toHaveText('小账户')
    await ui.fillEntry({ shares: '1', amount: '8.00', time: '2026-11-12T10:00' })
    await ui.id('stock-entry-submit').click()
    await expect(ui.id('stock-entry-error')).toHaveText('持股不足：2026-11-12 10:00 卖出 IXQZ 1 股，当时持有 0 股')
    await switchTo(page, 1, '默认账户')
    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionRows()).toEqual(['IXQZ 150 1,125.49 7.5033', 'XYZ 200 666.67 3.3334'])
  })

  test('AC13 与期权互不影响：Q61 提示新文案；STO call 不冻结；FEAT-040（AC15）起已平仓/盈亏汇总/首页包含股票卖出；列表互不显示', async ({ page }) => {
    await scenarioS()
    const ui = new StockUi(page)
    await page.goto('/trades/new')
    await page.getByTestId('open-trade-action-sto').check()
    await page.getByTestId('open-trade-type-call').check()
    await expect(page.getByTestId('open-trade-short-call-hint')).toHaveText(
      '卖出 call 不冻结现金，请自行确保账户有足够股票覆盖（每张 = multiplier 股）；covered call 覆盖检查（FEAT-039）上线后将自动检查。',
    )
    await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-12-18', strike: '9', type: 'CALL', contracts: 5, netAmount: '150.00', time: '2026-11-12T10:00' })

    // FEAT-040 AC15 replaces the old "共 0 笔平仓 / 总计 0.00（0 笔）": S4 +74.84, S5 +66.67
    await page.goto('/closed')
    await expect(page.getByTestId('closed-count')).toHaveText('共 2 笔平仓')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+141.51')
    await page.goto('/')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+141.51')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('2')
    await expect(page.getByTestId('dashboard-position-count')).toHaveText('1')
    await expect(page.getByTestId('dashboard-stock-position-count')).toHaveText('2')

    await apiStock('SELL', 'IXQZ', 150, '1300.00', '2026-11-12T11:00')

    await page.goto('/positions')
    await expect(page.getByTestId('position-row')).toHaveCount(1)
    await expect(page.getByTestId('positions-count')).toContainText('1')
    await expect(page.getByTestId('position-row').first()).toContainText('未冻结')
    await expect.poll(() => ui.positionRows()).toEqual(['XYZ 200 666.67 3.3334'])
    // the new sale sells the remaining S2 lot (cost 1,125.49): +174.51
    await page.goto('/closed')
    await expect(page.getByTestId('closed-count')).toHaveText('共 3 笔平仓')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+316.02')
    await page.goto('/pnl-summary')
    await expect(page.getByTestId('pnl-total')).toHaveText('+316.02')
    await expect(page.getByTestId('pnl-total-breakdown')).toHaveText('期权 0 笔 0.00 · 股票 3 笔 +316.02')
    await page.goto('/')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+316.02')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('3')
    await expect(page.getByTestId('dashboard-position-count')).toHaveText('1')
    await expect(page.getByTestId('dashboard-stock-position-count')).toHaveText('1')
    await page.goto('/trades')
    await expect(page.getByTestId('trade-row')).toHaveCount(1)
    await ui.openList()
    await ui.expectIds([6, 5, 4, 3, 2, 1])
  })
})
