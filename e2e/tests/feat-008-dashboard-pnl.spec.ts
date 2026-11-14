import type { Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { apiTrade } from '../support/trade'
import { BACKEND_URL } from '../support/backend'
import { fixNow } from '../support/clock'

/**
 * FEAT-008 首页概览 + 盈亏汇总 E2E (real clock). "Today" / "this month" come from the run day's NY date;
 * the month figure is +633.05 / 4 only when run in 2026-11, otherwise 0.00 / 0.
 * DB reset per test: scenario Q ids O1–O4 = #1–#4, T1–T5 = #5–#9, C1–C4 = #10–#13.
 */

const BACKEND = BACKEND_URL
const today = () => nyLocal(new Date()).slice(0, 10)
const addDays = (d: string, n: number) => new Date(Date.parse(`${d}T00:00:00Z`) + n * 86400000).toISOString().slice(0, 10)

async function apiClose(body: Record<string, unknown>): Promise<number> {
  const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expirationDate: '2026-12-18', ...body }),
  })
  if (r.status !== 201) throw new Error(`close failed ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function scenarioQ(): Promise<void> {
  await apiTransfer('DEPOSIT', '100000.00', '2026-10-26T10:00')
  await apiTrade({ action: 'BTO', symbol: 'TSLA', strike: '300', type: 'CALL', netAmount: '500.00', time: '2026-10-27T10:00' })
  await apiTrade({ action: 'STO', symbol: 'AAPL', expiration: '2026-11-20', strike: '180', type: 'PUT', netAmount: '150.00', time: '2026-10-28T10:00' })
  await apiClose({ symbol: 'TSLA', strike: '300', optionType: 'CALL', action: 'STC', contracts: 1, netAmount: '620.00', tradedAtLocal: '2026-10-30T10:00' })
  await apiClose({ symbol: 'AAPL', expirationDate: '2026-11-20', strike: '180', optionType: 'PUT', action: 'BTC', contracts: 1, netAmount: '200.00', tradedAtLocal: '2026-10-31T23:30' })
  await apiTrade({ action: 'STO', strike: '200', type: 'PUT', contracts: 2, netAmount: '698.70', time: '2026-11-02T10:30' })
  await apiTrade({ action: 'BTO', symbol: 'MSFT', expiration: '2027-01-15', strike: '450', type: 'CALL', netAmount: '900.00', time: '2026-11-02T14:00' })
  await apiTrade({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-11-02T15:00', multiplier: 10 })
  await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', contracts: 2, netAmount: '1000.00', time: '2026-11-03T09:45' })
  await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-03T11:00' })
  await apiClose({ symbol: 'AAPL', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 3, netAmount: '400.00', tradedAtLocal: '2026-11-05T10:00' })
  await apiClose({ symbol: 'XYZ', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 1, netAmount: '50.00', tradedAtLocal: '2026-11-06T10:00' })
  await apiClose({ symbol: 'MSFT', expirationDate: '2027-01-15', strike: '450', optionType: 'CALL', action: 'STC', contracts: 1, netAmount: '1250.00', tradedAtLocal: '2026-11-06T23:30' })
  await apiClose({ symbol: 'AAPL', strike: '210', optionType: 'CALL', action: 'STC', contracts: 2, netAmount: '640.00', tradedAtLocal: '2026-11-07T10:00' })
}

/** Expected "this month" figure for scenario Q (all closes are in 2026-10 / 2026-11). */
function monthExpect(total: string, count: string): { total: string; count: string } {
  return today().startsWith('2026-11') ? { total, count } : { total: '0.00', count: '0' }
}

async function rows(page: Page, rowId: string, prefix: string): Promise<string[]> {
  return page.getByTestId(rowId).evaluateAll(
    (rs, p) =>
      rs.map((r) =>
        [`${p}-link`, `${p}-count`, `${p}-pnl`]
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    prefix,
  )
}

async function totalRow(page: Page, prefix: string): Promise<string> {
  const r = page.getByTestId(`${prefix}-total-row`)
  return `${(await r.getByTestId(`${prefix}-total-count`).textContent())?.trim()} ${(await r.getByTestId(`${prefix}-total-pnl`).textContent())?.trim()}`
}

async function expectSummary(page: Page, count: string, total: string) {
  await expect(page.getByTestId('pnl-total-count')).toHaveText(count)
  await expect(page.getByTestId('pnl-total')).toHaveText(total)
}

test.describe('FEAT-008 首页概览与盈亏汇总', () => {
  test('AC1 首页：截至今天、现金、总计 +703.05（6 笔）、本月、持仓 3 个 / 冻结 44,000 / 7 天内 1 / 已过期 1（红）、底部后端状态、各链接', async ({ page }) => {
    // FEAT-009 (Q126): expirations d+1 / d+8 must be trading days → fixed today Mon 2026-11-23 10:00 NY
    await fixNow(page, '2026-11-23T15:00:00Z')
    await scenarioQ()
    const d = '2026-11-23'
    await apiTrade({ action: 'STO', symbol: 'TSLA', expiration: '2026-11-06', strike: '250', type: 'PUT', netAmount: '300.00', time: '2026-11-02T09:00' })
    await apiTrade({ action: 'BTO', symbol: 'SPY', expiration: addDays(d, 1), strike: '600', type: 'CALL', netAmount: '80.00', time: '2026-11-09T10:00' })
    await apiTrade({ action: 'STO', symbol: 'AAPL', expiration: addDays(d, 8), strike: '190', type: 'PUT', netAmount: '120.00', time: '2026-11-09T10:00' })
    await page.goto('/')
    await expect(page.getByTestId('dashboard-as-of')).toHaveText(`截至 ${d}（美东）`)
    await new CashPage(page).expectSummary('101,043.05', '44,000.00', '57,043.05')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+703.05')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('6')
    const m = { total: '+633.05', count: '4' } // fixed today is in 2026-11
    await expect(page.getByTestId('dashboard-month')).toHaveText(d.slice(0, 7))
    await expect(page.getByTestId('dashboard-month-total')).toHaveText(m.total)
    await expect(page.getByTestId('dashboard-month-count')).toHaveText(m.count)
    await expect(page.getByTestId('dashboard-position-count')).toHaveText('3')
    // FEAT-040 (Q93): label is now 期权持仓; plus 股票持仓 N 个标的
    await expect(page.getByTestId('dashboard-positions-card')).toContainText('期权持仓 3 个')
    await expect(page.getByTestId('dashboard-stock-positions-link')).toHaveText('股票持仓 0 个标的')
    await expect(page.getByTestId('dashboard-frozen-total')).toHaveText('44,000.00')
    await expect(page.getByTestId('dashboard-expiring-count')).toHaveText('1')
    await expect(page.getByTestId('dashboard-expiring-count')).toHaveAttribute('data-highlight', 'warning')
    await expect(page.getByTestId('dashboard-expired-count')).toHaveText('1')
    await expect(page.getByTestId('dashboard-expired-count')).toHaveAttribute('data-highlight', 'danger')
    const color = await page.getByTestId('dashboard-expired-count').evaluate((e) => getComputedStyle(e).color)
    const [r, g] = (color.match(/\d+/g) ?? []).map(Number)
    expect(r! - g!).toBeGreaterThan(40)
    // footer health status kept
    await expect(page.getByTestId('health-status')).toHaveText('UP')
    await expect(page.getByTestId('health-error')).toHaveCount(0)
    await expect(page.getByTestId('health-version')).not.toHaveText('')
    await expect(page.getByTestId('health-time')).not.toHaveText('')

    // links
    await expect(page.getByTestId('dashboard-month-link')).toHaveAttribute('href', `/closed?startDate=${d.slice(0, 7)}-01&endDate=${d}`)
    await page.getByTestId('dashboard-month-link').click()
    await expect(page).toHaveURL(/\/closed\?/)
    await expect(page.getByTestId('closed-count')).toHaveText(`共 ${m.count} 笔平仓`)
    await page.goBack()
    await page.getByTestId('dashboard-pnl-link').click()
    await expect(page).toHaveURL(/\/pnl-summary$/)
    await page.goBack()
    await page.getByTestId('dashboard-positions-link').click()
    await expect(page).toHaveURL(/\/positions$/)
    await page.goBack()
    await page.getByTestId('dashboard-cash-link').click()
    await expect(page).toHaveURL(/\/cash$/)
    await page.goBack()
    await page.getByTestId('dashboard-new-trade').click()
    await expect(page).toHaveURL(/\/trades\/new$/)
  })

  test('AC3 盈亏汇总全部时间：按标的 AAPL/MSFT/TSLA/XYZ、按月 11/10、合计行 6 +703.05；导航与说明', async ({ page }) => {
    await scenarioQ()
    await page.goto('/')
    await page.getByTestId('nav-pnl-summary').click()
    await expect(page).toHaveURL(/\/pnl-summary$/)
    await expectSummary(page, '6', '+703.05')
    await expect(page.getByTestId('pnl-total-line')).toContainText('共 6 笔，已实现盈亏合计') // FEAT-040 (AC9) wording
    await expect.poll(() => rows(page, 'pnl-symbol-row', 'pnl-symbol')).toEqual([
      'AAPL 3 +248.70',
      'MSFT 1 +350.00',
      'TSLA 1 +120.00',
      `XYZ 1 ${MINUS}15.65`,
    ])
    expect(await totalRow(page, 'pnl-symbol')).toBe('6 +703.05')
    await expect.poll(() => rows(page, 'pnl-month-row', 'pnl-month')).toEqual(['2026-11 4 +633.05', '2026-10 2 +70.00'])
    expect(await totalRow(page, 'pnl-month')).toBe('6 +703.05')
    // FEAT-040 replaced the note
    await expect(page.getByTestId('pnl-note')).toHaveText('已实现盈亏来自期权平仓、到期作废和股票卖出，已含手续费；不含入金、出金、股息和利息；股票转入（期初持仓）和买入没有已实现盈亏。')
    await expect(page.getByText('美东日期').first()).toBeVisible()
    await expect(page.locator('.ant-menu-item-selected')).toHaveCount(1)
    await expect(page.locator('.ant-menu-item-selected')).toContainText('盈亏汇总')
    // negative in red
    const xyz = page.getByTestId('pnl-symbol-row').nth(3).getByTestId('pnl-symbol-pnl')
    const c = await xyz.evaluate((e) => [e, ...Array.from(e.querySelectorAll('*'))].map((x) => getComputedStyle(x).color))
    expect(c.some((s) => {
      const [r, g] = (s.match(/\d+/g) ?? []).map(Number)
      return r! - g! > 40
    })).toBe(true)
  })

  test('AC4 日期范围：11-01..11-06 → 3 笔 +993.05（= 已平仓页）；10-31 → −50.00；09-01..11-30 两个月；从 11-07 → −360.00；颠倒报错不查询；未来 → 空；地址栏', async ({ page }) => {
    await scenarioQ()
    await page.goto('/pnl-summary')
    await expectSummary(page, '6', '+703.05')
    const start = page.getByTestId('pnl-filter-start-date')
    const end = page.getByTestId('pnl-filter-end-date')
    const go = async (s: string, e: string) => {
      await start.fill(s)
      await end.fill(e)
      await page.getByTestId('pnl-filter-submit').click()
    }
    await go('2026-11-01', '2026-11-06')
    await expectSummary(page, '3', '+993.05')
    await expect.poll(() => rows(page, 'pnl-symbol-row', 'pnl-symbol')).toEqual(['AAPL 1 +658.70', 'MSFT 1 +350.00', `XYZ 1 ${MINUS}15.65`])
    await expect.poll(() => rows(page, 'pnl-month-row', 'pnl-month')).toEqual(['2026-11 3 +993.05'])
    const url = new URL(page.url())
    expect(url.searchParams.get('startDate')).toBe('2026-11-01')
    expect(url.searchParams.get('endDate')).toBe('2026-11-06')
    await page.reload()
    await expectSummary(page, '3', '+993.05')
    await expect(start).toHaveValue('2026-11-01')

    await go('2026-10-31', '2026-10-31')
    await expectSummary(page, '1', `${MINUS}50.00`)
    await expect.poll(() => rows(page, 'pnl-symbol-row', 'pnl-symbol')).toEqual([`AAPL 1 ${MINUS}50.00`])
    await expect.poll(() => rows(page, 'pnl-month-row', 'pnl-month')).toEqual([`2026-10 1 ${MINUS}50.00`])
    await page.goBack()
    await expectSummary(page, '3', '+993.05')

    await go('2026-09-01', '2026-11-30')
    await expect.poll(() => rows(page, 'pnl-month-row', 'pnl-month')).toEqual(['2026-11 4 +633.05', '2026-10 2 +70.00'])
    await go('2026-11-07', '')
    await expectSummary(page, '1', `${MINUS}360.00`)

    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/pnl-summary')) reqs.push(r.url())
    })
    await go('2026-11-07', '2026-11-06')
    await expect(
      page.locator('.ant-form-item').filter({ has: page.getByTestId('pnl-filter-end-date') }).locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)'),
    ).toHaveText('开始日期不能晚于结束日期')
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)

    await go('2027-01-01', '')
    await expect(page.getByTestId('pnl-empty')).toHaveText('所选范围内没有平仓记录')
    await expectSummary(page, '0', '0.00')
    await page.getByTestId('pnl-filter-clear').click()
    await expectSummary(page, '6', '+703.05')

    // same range on the closed-trades page gives the same total
    await page.goto('/closed?startDate=2026-11-01&endDate=2026-11-06')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+993.05')
  })

  test('AC5 跳转：范围内点 AAPL → 已平仓只有 C1 +658.70；无筛选点 2026-10 → O4 O3 +70.00；范围 10-30..11-30 点 10 月 → 10-30..10-31', async ({ page }) => {
    await scenarioQ()
    await page.goto('/pnl-summary?startDate=2026-11-01&endDate=2026-11-06')
    await expectSummary(page, '3', '+993.05')
    await page.getByTestId('pnl-symbol-link').filter({ hasText: 'AAPL' }).click()
    await expect(page).toHaveURL(/\/closed\?/)
    let p = new URL(page.url()).searchParams
    expect([p.get('symbol'), p.get('startDate'), p.get('endDate')]).toEqual(['AAPL', '2026-11-01', '2026-11-06'])
    await expect(page.getByTestId('closed-filter-symbol')).toHaveValue('AAPL')
    await expect.poll(() => page.getByTestId('closed-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id')))).toEqual(['10'])
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+658.70')

    await page.goto('/pnl-summary')
    await page.getByTestId('pnl-month-link').filter({ hasText: '2026-10' }).click()
    await expect(page).toHaveURL(/\/closed\?/)
    p = new URL(page.url()).searchParams
    expect([p.get('startDate'), p.get('endDate')]).toEqual(['2026-10-01', '2026-10-31'])
    await expect.poll(() => page.getByTestId('closed-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id')))).toEqual(['4', '3'])
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+70.00')

    await page.goto('/pnl-summary?startDate=2026-10-30&endDate=2026-11-30')
    await page.getByTestId('pnl-month-link').filter({ hasText: '2026-10' }).click()
    p = new URL(page.url()).searchParams
    expect([p.get('startDate'), p.get('endDate')]).toEqual(['2026-10-30', '2026-10-31'])
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+70.00')
  })

  test('AC6 改 C4 / 删 O4 / 入金后，首页和汇总页无需刷新即更新；入金不计入盈亏', async ({ page }) => {
    await scenarioQ()
    await page.goto('/')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+703.05')
    await page.getByTestId('nav-pnl-summary').click()
    await expectSummary(page, '6', '+703.05')
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })

    await page.getByTestId('nav-trades').click()
    await page.locator('[data-testid="trade-row"][data-trade-id="13"]').getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await d.getByTestId('trade-edit-net-amount').fill('700.00')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d).toBeHidden()

    await page.getByTestId('nav-home').click()
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+763.05')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('6')
    const m = monthExpect('+693.05', '4')
    await expect(page.getByTestId('dashboard-month-total')).toHaveText(m.total)
    await page.getByTestId('nav-pnl-summary').click()
    await expectSummary(page, '6', '+763.05')
    await expect.poll(async () => (await rows(page, 'pnl-symbol-row', 'pnl-symbol'))[0]).toBe('AAPL 3 +308.70')
    await expect.poll(async () => (await rows(page, 'pnl-month-row', 'pnl-month'))[0]).toBe('2026-11 4 +693.05')

    await page.getByTestId('nav-trades').click()
    await page.locator('[data-testid="trade-row"][data-trade-id="4"]').getByTestId('trade-delete').click()
    const del = page.getByTestId('trade-delete-modal').getByRole('dialog')
    await del.getByTestId('trade-delete-confirm').click()
    await expect(del).toBeHidden()
    await page.getByTestId('nav-home').click()
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+813.05')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('5')
    await page.getByTestId('nav-pnl-summary').click()
    await expectSummary(page, '5', '+813.05')
    await expect.poll(async () => (await rows(page, 'pnl-symbol-row', 'pnl-symbol'))[0]).toBe('AAPL 2 +358.70')
    await expect.poll(() => rows(page, 'pnl-month-row', 'pnl-month')).toEqual(['2026-11 4 +693.05', '2026-10 1 +120.00'])

    await page.getByTestId('nav-home').click()
    const before = await page.getByTestId('cash-summary-total').textContent()
    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.save({ type: 'DEPOSIT', amount: '1000.00', time: '2026-11-10T10:00' })
    await page.getByTestId('nav-home').click()
    await expect(page.getByTestId('cash-summary-total')).not.toHaveText(before ?? '')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+813.05')
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
  })

  test('AC7 空：首页 0.00（0 笔）、持仓 0；汇总"暂无平仓记录"合计 0.00', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100.00', '2026-10-26T10:00')
    await page.goto('/')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('0.00')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('0')
    await expect(page.getByTestId('dashboard-month')).toHaveText(today().slice(0, 7))
    await expect(page.getByTestId('dashboard-month-total')).toHaveText('0.00')
    await expect(page.getByTestId('dashboard-month-count')).toHaveText('0')
    await expect(page.getByTestId('dashboard-position-count')).toHaveText('0')
    await expect(page.getByTestId('dashboard-frozen-total')).toHaveText('0.00')
    await expect(page.getByTestId('dashboard-expiring-count')).toHaveText('0')
    await expect(page.getByTestId('dashboard-expiring-count')).toHaveAttribute('data-highlight', 'none')
    await expect(page.getByTestId('dashboard-expired-count')).toHaveText('0')
    await expect(page.getByTestId('dashboard-expired-count')).toHaveAttribute('data-highlight', 'none')
    await page.getByTestId('nav-pnl-summary').click()
    await expect(page.getByTestId('pnl-empty')).toHaveText('暂无平仓记录')
    await expectSummary(page, '0', '0.00')
  })

  test('AC8 后端不可用：各卡片和底部显示错误与重试、页面不崩溃；后端恢复后重试成功', async ({ page, backend }) => {
    await backend.stop()
    await page.goto('/')
    await expect(page.getByTestId('health-error')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('cash-summary-error')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('dashboard-pnl-error')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('dashboard-positions-error')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('dashboard-pnl-error')).toContainText('账户未就绪')
    await expect(page.getByText('Account required')).toHaveCount(0)
    await backend.start()
    // one click on one card's 重试 recovers the account list and then every card, including the health footer
    // (the account list also auto-retries every 10 s; if that already recovered the cards there is no button left)
    const retry = page.getByTestId('dashboard-pnl-retry')
    if (await retry.isVisible()) await retry.click()
    await expect(page.getByTestId('health-status')).toHaveText('UP')
    await expect(page.getByTestId('health-error')).toHaveCount(0)
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('0.00')
    await expect(page.getByTestId('cash-summary-total')).toHaveText('0.00')
    await expect(page.getByTestId('dashboard-position-count')).toHaveText('0')
    await expect(page.getByTestId('cash-summary-error')).toHaveCount(0)
    await expect(page.getByTestId('dashboard-positions-error')).toHaveCount(0)
    await expect(page.getByText('Account required')).toHaveCount(0)
  })
})
