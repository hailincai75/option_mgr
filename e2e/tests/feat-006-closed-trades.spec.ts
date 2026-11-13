import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, TradeForm } from '../support/trade'
import { BACKEND_URL } from '../support/backend'

/**
 * FEAT-006 已平仓 E2E. DB reset per test, ids restart at 1: scenario P trades T1–T5 = #1–#5, C1–C4 = #6–#9.
 */

const BACKEND = BACKEND_URL

async function apiClose(body: Record<string, unknown>): Promise<number> {
  const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expirationDate: '2026-12-18', ...body }),
  })
  if (r.status !== 201) throw new Error(`close failed ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function scenarioP(): Promise<void> {
  await apiTransfer('DEPOSIT', '100000.00', '2026-10-26T10:00')
  await apiTrade({ action: 'STO', strike: '200', type: 'PUT', contracts: 2, netAmount: '698.70', time: '2026-11-02T10:30' })
  await apiTrade({ action: 'BTO', symbol: 'MSFT', expiration: '2027-01-15', strike: '450', type: 'CALL', netAmount: '900.00', time: '2026-11-02T14:00' })
  await apiTrade({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-11-02T15:00', multiplier: 10 })
  await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', contracts: 2, netAmount: '1000.00', time: '2026-11-03T09:45' })
  await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-03T11:00' })
  await apiClose({ symbol: 'AAPL', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 3, netAmount: '400.00', tradedAtLocal: '2026-11-05T10:00', note: '全部平掉' })
  await apiClose({ symbol: 'XYZ', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 1, netAmount: '50.00', tradedAtLocal: '2026-11-06T10:00' })
  await apiClose({ symbol: 'MSFT', expirationDate: '2027-01-15', strike: '450', optionType: 'CALL', action: 'STC', contracts: 1, netAmount: '1250.00', tradedAtLocal: '2026-11-06T23:30', note: '盘后' })
  await apiClose({ symbol: 'AAPL', strike: '210', optionType: 'CALL', action: 'STC', contracts: 2, netAmount: '640.00', tradedAtLocal: '2026-11-07T10:00' })
}

const LABEL: Record<string, string> = { '6': 'C1', '7': 'C2', '8': 'C3', '9': 'C4' }

class Closed {
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

  async labels(): Promise<string[]> {
    const ids = await this.rows.evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id') ?? ''))
    return ids.map((i) => LABEL[i] ?? `#${i}`)
  }

  async expect(labels: string[], count: number, total: string): Promise<void> {
    await expect(this.count).toHaveText(`共 ${count} 笔平仓`)
    await expect(this.total).toHaveText(total)
    await expect.poll(() => this.labels()).toEqual(labels)
  }

  async query(): Promise<void> {
    await this.f('submit').click()
  }

  async clear(): Promise<void> {
    await this.f('clear').click()
    await expect.poll(() => new URL(this.page.url()).search).toBe('')
    await expect(this.count).toBeVisible()
    await this.page.waitForTimeout(100)
  }

  fieldError(testId: string): Locator {
    return this.page
      .locator('.ant-form-item')
      .filter({ has: this.page.getByTestId(testId) })
      .locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
  }

  async cells(): Promise<Record<string, string>[]> {
    return this.rows.evaluateAll((rs) =>
      rs.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
        return {
          time: t('closed-cell-time'),
          contract: t('closed-cell-contract'),
          action: t('closed-cell-action'),
          direction: t('closed-cell-direction'),
          contracts: t('closed-cell-contracts'),
          openingNet: t('closed-cell-opening-net'),
          openingPer: t('closed-cell-opening-net-per'),
          closeNet: t('closed-cell-close-net'),
          closePer: t('closed-cell-close-net-per'),
          pnl: t('closed-cell-pnl'),
          note: t('closed-cell-note'),
        }
      }),
    )
  }

  row(id: number): Locator {
    return this.page.locator(`[data-testid="closed-row"][data-trade-id="${id}"]`)
  }

  async lots(id: number): Promise<string[]> {
    await this.row(id).getByTestId('closed-expand').click()
    const lots = this.row(id).locator('xpath=following-sibling::tr[1]').getByTestId('closed-lot-row')
    await expect(lots.first()).toBeVisible()
    return lots.evaluateAll((rs) =>
      rs.map((r) =>
        ['closed-lot-time', 'closed-lot-action', 'closed-lot-contracts', 'closed-lot-net-amount']
          .map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }
}

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

test.describe('FEAT-006 已平仓', () => {
  test('AC1 列表：共 4 笔、合计 +633.05（绿）、顺序与各列、颜色、没有价格/手续费/持有天数列；导航高亮', async ({ page }) => {
    await scenarioP()
    await page.goto('/')
    await page.getByTestId('nav-closed').click()
    await expect(page).toHaveURL(/\/closed$/)
    const c = new Closed(page)
    await c.expect(['C4', 'C3', 'C2', 'C1'], 4, '+633.05')
    expect(await colorOf(c.total)).toBe('green')
    await expect(page.getByTestId('closed-filter-note')).toHaveCount(0)
    expect(await c.cells()).toEqual([
      { time: '2026-11-07 10:00', contract: 'AAPL 2026-12-18 210 C', action: 'STC（卖出平仓）', direction: '多头', contracts: '2', openingNet: `${MINUS}1,000.00`, openingPer: `每张 ${MINUS}500.00`, closeNet: '+640.00', closePer: '每张 +320.00', pnl: `${MINUS}360.00`, note: '—' },
      { time: '2026-11-06 23:30', contract: 'MSFT 2027-01-15 450 C', action: 'STC（卖出平仓）', direction: '多头', contracts: '1', openingNet: `${MINUS}900.00`, openingPer: `每张 ${MINUS}900.00`, closeNet: '+1,250.00', closePer: '每张 +1,250.00', pnl: '+350.00', note: '盘后' },
      { time: '2026-11-06 10:00', contract: 'XYZ 2026-12-18 200 P', action: 'BTC（买入平仓）', direction: '空头', contracts: '1（multiplier 10）', openingNet: '+34.35', openingPer: '每张 +34.35', closeNet: `${MINUS}50.00`, closePer: `每张 ${MINUS}50.00`, pnl: `${MINUS}15.65`, note: '—' },
      { time: '2026-11-05 10:00', contract: 'AAPL 2026-12-18 200 P', action: 'BTC（买入平仓）', direction: '空头', contracts: '3', openingNet: '+1,058.70', openingPer: '每张 +352.90', closeNet: `${MINUS}400.00`, closePer: `每张 ${MINUS}133.33`, pnl: '+658.70', note: '全部平掉' },
    ])
    expect(await colorOf(c.row(9).getByTestId('closed-cell-pnl'))).toBe('red')
    expect(await colorOf(c.row(8).getByTestId('closed-cell-pnl'))).toBe('green')
    expect(await colorOf(c.row(7).getByTestId('closed-cell-pnl'))).toBe('red')
    expect(await colorOf(c.row(6).getByTestId('closed-cell-pnl'))).toBe('green')
    const headers = (await page.getByTestId('closed-list').locator('th').allTextContents()).join('|')
    expect(headers).toContain('美东时间')
    expect(headers).not.toMatch(/成交价|价格|手续费|持有/)
    await expect(page.getByText('净额均已含手续费').first()).toBeVisible()
    await expect(page.locator('.ant-menu-item-selected')).toHaveCount(1)
    await expect(page.locator('.ant-menu-item-selected')).toContainText('已平仓')
  })

  test('AC2 展开：C1 两批（T1 2 张 +698.70、T5 1 张 +360.00）；C4 一批 BTO 2 张 −1,000.00', async ({ page }) => {
    await scenarioP()
    const c = new Closed(page)
    await c.open()
    expect(await c.lots(6)).toEqual([
      '2026-11-02 10:30 STO（卖出开仓） 2 张 +698.70',
      '2026-11-03 11:00 STO（卖出开仓） 1 张 +360.00',
    ])
    expect(await c.lots(9)).toEqual([`2026-11-03 09:45 BTO（买入开仓） 2 张 ${MINUS}1,000.00`])
  })

  test('AC3 筛选与合计：aapl / 合约四项 / Call / BTC / STC / TSLA 无结果 / $AAPL 字段错误；方向只有 BTC、STC', async ({ page }) => {
    await scenarioP()
    const c = new Closed(page)
    await c.open()
    await expect(page.getByTestId('closed-filter-action-bto')).toHaveCount(0)
    await expect(page.getByTestId('closed-filter-action-sto')).toHaveCount(0)
    await expect(page.getByText('平仓 / 卖出开始日期（美东日期）').first()).toBeVisible() // FEAT-040 label
    await expect(page.getByText('平仓 / 卖出结束日期（美东日期）').first()).toBeVisible() // FEAT-040 label

    await c.f('symbol').fill('aapl')
    await c.query()
    await c.expect(['C4', 'C1'], 2, '+298.70')
    await expect(page.getByTestId('closed-filter-note')).toBeVisible()

    await c.f('expiration').fill('2026-12-18')
    await c.f('strike').fill('200.00')
    await c.f('type-put').check()
    await c.query()
    await c.expect(['C1'], 1, '+658.70')

    await c.clear()
    await c.f('type-call').check()
    await c.query()
    await c.expect(['C4', 'C3'], 2, `${MINUS}10.00`)
    expect(await colorOf(c.total)).toBe('red')

    await c.clear()
    await c.f('action-btc').check()
    await c.query()
    await c.expect(['C2', 'C1'], 2, '+643.05')
    await c.f('action-btc').uncheck()
    await c.f('action-stc').check()
    await c.query()
    await c.expect(['C4', 'C3'], 2, `${MINUS}10.00`)

    await c.clear()
    await c.f('symbol').fill('TSLA')
    await c.query()
    await expect(page.getByTestId('closed-no-match')).toContainText('没有符合条件的平仓记录')
    await expect(page.getByTestId('closed-no-match-clear')).toBeVisible()
    await expect(c.count).toHaveText('共 0 笔平仓')
    await expect(c.total).toHaveText('0.00')

    await c.f('symbol').fill('$AAPL')
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/closed-trades')) reqs.push(r.url())
    })
    await c.query()
    await expect(c.fieldError('closed-filter-symbol')).toHaveText('标的只能包含字母、数字和点')
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)
  })

  test('AC4 平仓日期：11-06 → C3 C2 +334.35；11-07 → C4；只到 11-05 → C1；颠倒 → 字段错误', async ({ page }) => {
    await scenarioP()
    const c = new Closed(page)
    await c.open()
    const range = async (s: string, e: string) => {
      await c.f('start-date').fill(s)
      await c.f('end-date').fill(e)
      await c.query()
    }
    await range('2026-11-06', '2026-11-06')
    await c.expect(['C3', 'C2'], 2, '+334.35')
    await range('2026-11-07', '2026-11-07')
    await c.expect(['C4'], 1, `${MINUS}360.00`)
    await range('', '2026-11-05')
    await c.expect(['C1'], 1, '+658.70')
    await range('2026-11-07', '2026-11-06')
    await expect(c.fieldError('closed-filter-end-date')).toHaveText('开始日期不能晚于结束日期')
    await expect(c.rows).toHaveCount(0)
  })

  test('AC5 补录 / 修改 / 删除后重新匹配，已平仓页无需刷新', async ({ page }) => {
    await apiTransfer('DEPOSIT', '80000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' }) // L1 #1
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-04T11:00' }) // L2 #2
    await apiClose({ symbol: 'AAPL', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 1, netAmount: '100.00', tradedAtLocal: '2026-11-06T10:00' }) // X #3
    const c = new Closed(page)
    await c.open()
    await expect(c.count).toHaveText('共 1 笔平仓')
    await expect(c.total).toHaveText('+249.35')
    expect((await c.lots(3))[0]).toContain('2026-11-03 10:30')

    // backfill Y through the entry page
    await page.getByTestId('nav-open-trade').click()
    const form = new TradeForm(page)
    await page.getByTestId('open-trade-action-btc').check()
    await form.fill({ symbol: 'AAPL', expiration: '2026-12-18', strike: '200', type: 'PUT', contracts: '1', netAmount: '120.00', time: '2026-11-05T10:00' })
    await form.submit.click()
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText('+229.35')
    await page.getByTestId('nav-closed').click()
    await expect(c.count).toHaveText('共 2 笔平仓')
    await expect(c.total).toHaveText('+489.35')
    await expect(c.row(3).getByTestId('closed-cell-pnl')).toHaveText('+260.00')
    await expect(c.row(4).getByTestId('closed-cell-pnl')).toHaveText('+229.35')
    expect((await c.lots(3))[0]).toContain('2026-11-04 11:00')
    expect((await c.lots(4))[0]).toContain('2026-11-03 10:30')

    // edit L2 net in the trade list
    await page.getByTestId('nav-trades').click()
    await page.locator('[data-testid="trade-row"][data-trade-id="2"]').getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await d.getByTestId('trade-edit-net-amount').fill('365.00')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d).toBeHidden()
    await page.getByTestId('nav-closed').click()
    await expect(c.total).toHaveText('+494.35')
    await expect(c.row(3).getByTestId('closed-cell-pnl')).toHaveText('+265.00')

    // delete Y
    await page.getByTestId('nav-trades').click()
    await page.locator('[data-testid="trade-row"][data-trade-id="4"]').getByTestId('trade-delete').click()
    const del = page.getByTestId('trade-delete-modal').getByRole('dialog')
    await del.getByTestId('trade-delete-confirm').click()
    await expect(del).toBeHidden()
    await page.getByTestId('nav-closed').click()
    await expect(c.count).toHaveText('共 1 笔平仓')
    await expect(c.total).toHaveText('+249.35')
    await expect(c.row(3).getByTestId('closed-cell-pnl')).toHaveText('+249.35')
  })

  test('AC6 25 笔分页：合计 +50.00 在第 2 页不变；第 2 页 5 行最后 10-01 15:00；平仓开始 10-20 → 第 1 页 共 6 笔 +12.00', async ({ page }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-09-30T10:00')
    const start = Date.UTC(2026, 9, 1)
    for (let i = 0; i < 25; i++) {
      const d = new Date(start + i * 86400000).toISOString().slice(0, 10)
      await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '10.00', time: `${d}T09:30` })
      await apiClose({ symbol: 'AAPL', strike: '210', optionType: 'CALL', action: 'STC', contracts: 1, netAmount: '12.00', tradedAtLocal: `${d}T15:00` })
    }
    const c = new Closed(page)
    await c.open()
    await expect(c.count).toHaveText('共 25 笔平仓')
    await expect(c.total).toHaveText('+50.00')
    await expect(c.rows).toHaveCount(20)
    expect((await c.cells())[0]).toMatchObject({ time: '2026-10-25 15:00', pnl: '+2.00' })
    const pager = page.getByTestId('closed-pagination')
    await pager.locator('.ant-pagination-item-2').click()
    await expect(c.rows).toHaveCount(5)
    expect((await c.cells())[4]).toMatchObject({ time: '2026-10-01 15:00' })
    await expect(c.count).toHaveText('共 25 笔平仓')
    await expect(c.total).toHaveText('+50.00')
    expect(new URL(page.url()).searchParams.get('page')).toBe('2')

    await c.f('start-date').fill('2026-10-20')
    await c.query()
    await expect(c.count).toHaveText('共 6 笔平仓')
    await expect(c.total).toHaveText('+12.00')
    await expect(c.rows).toHaveCount(6)
    expect(new URL(page.url()).searchParams.get('page')).toBeNull()

    await c.clear()
    await page.getByTestId('closed-size-50').locator('xpath=ancestor::label[1]').click()
    await expect(c.rows).toHaveCount(25)
    await expect(c.total).toHaveText('+50.00')
  })

  test('AC7 地址栏（BTC 写入、刷新保留、后退恢复）；合约链接 → 交易列表 C1 T5 T1；历史 → 审计 期权交易 #6', async ({ page }) => {
    await scenarioP()
    const c = new Closed(page)
    await c.open()
    await c.expect(['C4', 'C3', 'C2', 'C1'], 4, '+633.05')
    await c.f('action-btc').check()
    await c.query()
    await c.expect(['C2', 'C1'], 2, '+643.05')
    expect(new URL(page.url()).searchParams.get('action')).toContain('BTC')
    await page.reload()
    await c.expect(['C2', 'C1'], 2, '+643.05')
    await expect(c.f('action-btc')).toBeChecked()
    await page.goBack()
    await c.expect(['C4', 'C3', 'C2', 'C1'], 4, '+633.05')

    await c.row(6).getByTestId('closed-cell-contract').click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect
      .poll(() => page.getByTestId('trade-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id'))))
      .toEqual(['6', '5', '1'])
    await expect(page.getByTestId('trade-filter-symbol')).toHaveValue('AAPL')
    await expect(page.getByTestId('trade-filter-type-put')).toBeChecked()

    // "历史" only on rows whose closing trade has audit records (contract: 列表行的 hasAuditLog). Nothing edited yet.
    await c.open()
    await expect(c.rows).toHaveCount(4)
    await expect(page.getByTestId('closed-history')).toHaveCount(0)
    await page.goto('/audit?objectType=OPTION_TRADE&objectId=6')
    await expect(page.getByTestId('audit-empty')).toContainText('暂无审计记录')
    await expect(page.getByTestId('audit-empty-hint')).toHaveText('期权交易 #6 录入后没有被修改或删除过（审计只记录修改和删除）')

    // Editing an opening lot (T1 #1, closed by C1) does not count for the closed row: still no link.
    const putT1 = await fetch(`${BACKEND}/api/option-trades/1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contracts: 2, netAmount: '698.70', tradedAtLocal: '2026-11-02T10:30', note: '第一笔' }),
    })
    expect(putT1.status).toBe(200)
    await c.open()
    await expect(c.rows).toHaveCount(4)
    await expect(page.getByTestId('closed-history')).toHaveCount(0)

    // Editing the closing trade C1 #6 → its row (only) gets the link, which opens the audit page filtered to #6.
    const putC1 = await fetch(`${BACKEND}/api/option-trades/6`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ contracts: 3, netAmount: '400.00', tradedAtLocal: '2026-11-05T10:00', note: '全部平掉（改）' }),
    })
    expect(putC1.status).toBe(200)
    await c.open()
    await expect(c.row(6).getByTestId('closed-history')).toBeVisible()
    await expect(page.getByTestId('closed-history')).toHaveCount(1)
    await c.row(6).getByTestId('closed-history').click()
    await expect(page).toHaveURL(/\/audit\?/)
    const p = new URL(page.url()).searchParams
    expect(p.get('objectType')).toBe('OPTION_TRADE')
    expect(p.get('objectId')).toBe('6')
    await expect(page.getByTestId('audit-filter-id')).toHaveValue('6')
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    await expect(page.getByTestId('audit-row')).toContainText('期权交易 #6')
  })

  test('AC8 只有开仓：暂无平仓记录、共 0 笔、合计 0.00', async ({ page }) => {
    await apiTransfer('DEPOSIT', '30000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' })
    const c = new Closed(page)
    await c.open()
    await expect(page.getByTestId('closed-empty')).toHaveText('暂无平仓记录')
    await expect(c.count).toHaveText('共 0 笔平仓')
    await expect(c.total).toHaveText('0.00')
    await expect(c.rows).toHaveCount(0)
  })

  test('AC9 只读：筛选、翻页、展开后现金 100,633.05 / 0.00 / 100,633.05，交易仍 9 笔', async ({ page }) => {
    await scenarioP()
    const c = new Closed(page)
    await c.open('?size=20')
    await c.lots(6)
    await c.f('symbol').fill('aapl')
    await c.query()
    await c.expect(['C4', 'C1'], 2, '+298.70')
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('100,633.05', '0.00', '100,633.05')
    await page.getByTestId('nav-trades').click()
    await expect(page.getByTestId('trade-list-total')).toHaveText('共 9 条')
  })

  test('非法地址参数：action=STO 报"方向只能是 BTC、STC、EXP"（FEAT-012 加 EXP）且不发请求；不存在的日期显示后端错误', async ({ page }) => {
    await scenarioP()
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/closed-trades')) reqs.push(r.url())
    })
    const c = new Closed(page)
    await c.open('?action=STO')
    await expect(page.getByTestId('closed-invalid')).toBeVisible()
    await expect(page.getByTestId('closed-page')).toContainText('方向只能是 BTC、STC、EXP')
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)
    await c.open('?expirationDate=2026-02-30')
    await expect(c.fieldError('closed-filter-expiration')).toHaveText('日期格式不正确（YYYY-MM-DD）')
  })
})
