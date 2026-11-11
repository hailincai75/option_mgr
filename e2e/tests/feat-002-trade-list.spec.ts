import type { Locator, Page } from '@playwright/test'
import { apiLedger, apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, TradeForm } from '../support/trade'

/**
 * FEAT-002 交易列表与筛选 E2E. Baseline T1–T6 (all ≤ 2026-11-04, real clock is later) is seeded through the API;
 * every filter / paging action goes through the page.
 */

type Label = 'T1' | 'T2' | 'T3' | 'T4' | 'T5' | 'T6'

async function seedBaseline(): Promise<Record<number, Label>> {
  await apiTransfer('DEPOSIT', '100000.00', '2026-10-26T10:00')
  const ids: Record<number, Label> = {}
  const add = async (label: Label, t: Parameters<typeof apiTrade>[0], note?: string) => {
    const r = await fetch('http://localhost:8080/api/option-trades', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        symbol: t.symbol ?? 'AAPL',
        expirationDate: t.expiration ?? '2026-12-18',
        strike: t.strike,
        optionType: t.type,
        action: t.action,
        contracts: t.contracts ?? 1,
        netAmount: t.netAmount,
        tradedAtLocal: t.time,
        multiplier: t.multiplier ?? 100,
        note: note ?? null,
      }),
    })
    if (r.status !== 201) throw new Error(`seed ${label}: ${r.status} ${await r.text()}`)
    ids[((await r.json()) as { id: number }).id] = label
  }
  await add('T1', { action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' }, '第一笔 CSP')
  await add('T2', { action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65', time: '2026-11-03T10:30' })
  await add('T3', { action: 'STO', symbol: 'F', strike: '12.5', type: 'PUT', contracts: 2, netAmount: '48.70', time: '2026-11-02T15:59' })
  await add('T4', { action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-04T11:00' }, '加仓')
  await add('T5', { action: 'BTO', symbol: 'MSFT', expiration: '2027-01-15', strike: '450', type: 'CALL', netAmount: '900.00', time: '2026-11-03T23:30' })
  await add('T6', { action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-10-30T10:00', multiplier: 10 })
  return ids
}

class TradeList {
  readonly symbol: Locator
  readonly expiration: Locator
  readonly strike: Locator
  readonly typeAny: Locator
  readonly typeCall: Locator
  readonly typePut: Locator
  readonly startDate: Locator
  readonly endDate: Locator
  readonly submit: Locator
  readonly clear: Locator
  readonly rowsLoc: Locator
  readonly total: Locator
  readonly noMatch: Locator
  readonly empty: Locator
  readonly invalid: Locator
  readonly pagination: Locator

  constructor(readonly page: Page, private readonly ids: Record<number, Label> = {}) {
    const id = (s: string) => page.getByTestId(s)
    this.symbol = id('trade-filter-symbol')
    this.expiration = id('trade-filter-expiration')
    this.strike = id('trade-filter-strike')
    this.typeAny = id('trade-filter-type-any')
    this.typeCall = id('trade-filter-type-call')
    this.typePut = id('trade-filter-type-put')
    this.startDate = id('trade-filter-start-date')
    this.endDate = id('trade-filter-end-date')
    this.submit = id('trade-filter-submit')
    this.clear = id('trade-filter-clear')
    this.rowsLoc = id('trade-row')
    this.total = id('trade-list-total')
    this.noMatch = id('trade-list-no-match')
    this.empty = id('trade-list-empty')
    this.invalid = id('trade-list-invalid')
    this.pagination = id('trade-list-pagination')
  }

  action(a: 'bto' | 'sto' | 'btc' | 'stc'): Locator {
    return this.page.getByTestId(`trade-filter-action-${a}`)
  }

  fieldError(field: Locator): Locator {
    return this.page.locator('.ant-form-item').filter({ has: field }).locator('.ant-form-item-explain-error')
  }

  async open(query = ''): Promise<void> {
    await this.page.goto(`/trades${query}`)
    await expect(this.page.getByTestId('trade-list-page')).toBeVisible()
  }

  async query(): Promise<void> {
    await this.submit.click()
  }

  /** Click 清除筛选 and wait until the URL and the list have settled (the form is re-filled from the URL). */
  async clearAll(): Promise<void> {
    await this.clear.click()
    await expect.poll(() => new URL(this.page.url()).search).toBe('')
    await expect(this.page.getByTestId('trade-list-total').or(this.empty)).toBeVisible()
    await this.page.waitForTimeout(100)
  }

  /** Labels (T1..T6) of the visible rows, top to bottom. */
  async labels(): Promise<string[]> {
    const ids = await this.rowsLoc.evaluateAll((rows) => rows.map((r) => r.getAttribute('data-trade-id') ?? ''))
    return ids.map((i) => this.ids[Number(i)] ?? `#${i}`)
  }

  async expectLabels(expected: string[], total: number): Promise<void> {
    await expect.poll(() => this.labels()).toEqual(expected)
    if (total > 0) await expect(this.total).toHaveText(`共 ${total} 条`)
  }

  async cells(): Promise<Record<string, string>[]> {
    return this.rowsLoc.evaluateAll((rows) =>
      rows.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        return {
          time: t('trade-cell-time'),
          action: t('trade-cell-action'),
          contract: t('trade-cell-contract'),
          contracts: t('trade-cell-contracts'),
          net: t('trade-cell-net-amount'),
          frozen: t('trade-cell-frozen'),
          note: t('trade-cell-note'),
        }
      }),
    )
  }
}

const ALL: Label[] = ['T4', 'T5', 'T2', 'T1', 'T3', 'T6']

test.describe('FEAT-002 交易列表与筛选', () => {
  test('AC1 默认排序与列显示：T4 T5 T2 T1 T3 T6、共 6 条，没有价格/手续费列', async ({ page }) => {
    const ids = await seedBaseline()
    await page.goto('/')
    await page.getByTestId('nav-trades').click()
    await expect(page).toHaveURL(/\/trades$/)
    const list = new TradeList(page, ids)
    await list.expectLabels(ALL, 6)
    expect(await list.cells()).toEqual([
      { time: '2026-11-04 11:00', action: 'STO（卖出开仓）', contract: 'AAPL 2026-12-18 200 P', contracts: '1', net: '+360.00', frozen: '+20,000.00', note: '加仓' },
      { time: '2026-11-03 23:30', action: 'BTO（买入开仓）', contract: 'MSFT 2027-01-15 450 C', contracts: '1', net: `${MINUS}900.00`, frozen: '—', note: '—' },
      { time: '2026-11-03 10:30', action: 'BTO（买入开仓）', contract: 'AAPL 2026-12-18 210 C', contracts: '1', net: `${MINUS}512.65`, frozen: '—', note: '—' },
      { time: '2026-11-03 10:30', action: 'STO（卖出开仓）', contract: 'AAPL 2026-12-18 200 P', contracts: '1', net: '+349.35', frozen: '+20,000.00', note: '第一笔 CSP' },
      { time: '2026-11-02 15:59', action: 'STO（卖出开仓）', contract: 'F 2026-12-18 12.5 P', contracts: '2', net: '+48.70', frozen: '+2,500.00', note: '—' },
      { time: '2026-10-30 10:00', action: 'STO（卖出开仓）', contract: 'XYZ 2026-12-18 200 P', contracts: '1（multiplier 10）', net: '+34.35', frozen: '+2,000.00', note: '—' },
    ])
    const headers = (await page.getByTestId('trade-list').locator('th').allTextContents()).join('|')
    expect(headers).toContain('美东时间')
    expect(headers).not.toMatch(/价格|手续费/)
    // nav highlight: only 交易 selected here; only 录入开仓 on /trades/new
    await expect(page.locator('.ant-menu-item-selected')).toHaveCount(1)
    await expect(page.locator('.ant-menu-item-selected')).toContainText('交易')
    await page.getByTestId('trade-list-new').click()
    await expect(page).toHaveURL(/\/trades\/new$/)
    await expect(page.locator('.ant-menu-item-selected')).toHaveCount(1)
    await expect(page.locator('.ant-menu-item-selected')).toContainText('录入开仓')
  })

  test('AC2 标的精确匹配：aapl / " AAPL " → T4 T2 T1；AAP → 没有符合条件的交易、共 0 条、清除筛选；$AAPL → 字段错误', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.symbol.fill('aapl')
    await list.query()
    await list.expectLabels(['T4', 'T2', 'T1'], 3)
    await list.symbol.fill(' AAPL ')
    await list.query()
    await list.expectLabels(['T4', 'T2', 'T1'], 3)

    await list.symbol.fill('AAP')
    await list.query()
    await expect(list.noMatch).toContainText('没有符合条件的交易')
    await expect(page.getByTestId('trade-list-no-match-clear')).toBeVisible()
    await expect(list.rowsLoc).toHaveCount(0)
    await expect(page.getByText('共 0 条')).toBeVisible()
  })

  test('AC2 $AAPL：标的下显示格式错误，不显示查询结果', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.expectLabels(ALL, 6)
    await list.symbol.fill('$AAPL')
    await list.query()
    await expect(list.fieldError(list.symbol)).toHaveText('标的只能包含字母、数字和点')
    await expect(list.rowsLoc).toHaveCount(0)
  })

  test('AC3 合约筛选：四项 → T4 T1；200.00 等价；只到期日 → 5 条；只 Call → T5 T2；只 12.5 → T3', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.symbol.fill('AAPL')
    await list.expiration.fill('2026-12-18')
    await list.strike.fill('200')
    await list.typePut.check()
    await list.query()
    await list.expectLabels(['T4', 'T1'], 2)
    await list.strike.fill('200.00')
    await list.query()
    await list.expectLabels(['T4', 'T1'], 2)

    await list.clearAll()
    await list.expectLabels(ALL, 6)
    await list.expiration.fill('2026-12-18')
    await list.query()
    await list.expectLabels(['T4', 'T2', 'T1', 'T3', 'T6'], 5)

    await list.clearAll()
    await list.typeCall.check()
    await list.query()
    await list.expectLabels(['T5', 'T2'], 2)

    await list.clearAll()
    await list.strike.fill('12.5')
    await list.query()
    await list.expectLabels(['T3'], 1)
  })

  for (const bad of [
    { strike: '0', msg: '行权价必须大于 0' },
    { strike: '-5', msg: null },
    { strike: '200.1234', msg: '行权价最多 3 位小数' },
  ]) {
    test(`AC3 行权价 ${bad.strike}：字段错误，不发查询`, async ({ page }) => {
      const list = new TradeList(page, await seedBaseline())
      await list.open()
      await list.expectLabels(ALL, 6)
      const requests: string[] = []
      page.on('request', (r) => {
        if (r.url().includes('/api/option-trades')) requests.push(r.url())
      })
      await list.strike.fill(bad.strike)
      await list.query()
      const err = list.fieldError(list.strike)
      if (bad.msg) await expect(err).toHaveText(bad.msg)
      else await expect(err).not.toHaveText('')
      await page.waitForTimeout(300)
      expect(requests).toHaveLength(0)
    })
  }

  test('AC3 点击合约 XYZ 2026-12-18 200 P：筛选区回填四项，只显示 T6，第 1 页', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.expectLabels(ALL, 6)
    const t6 = list.rowsLoc.nth(5).getByTestId('trade-cell-contract')
    await expect(t6).toHaveAttribute('title', '按此合约筛选')
    await t6.click()
    await list.expectLabels(['T6'], 1)
    await expect(list.symbol).toHaveValue('XYZ')
    await expect(list.expiration).toHaveValue('2026-12-18')
    await expect(list.strike).toHaveValue('200')
    await expect(list.typePut).toBeChecked()
    await expect(list.pagination.locator('.ant-pagination-item-active')).toHaveText('1')
    expect(new URL(page.url()).searchParams.get('page')).toBeNull()
  })

  test('AC4 方向多选：STO → 4；BTO → 2；BTO+STO → 6；BTC / STC → 没有符合条件的交易', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.action('sto').check()
    await list.query()
    await list.expectLabels(['T4', 'T1', 'T3', 'T6'], 4)
    await list.action('sto').uncheck()
    await list.action('bto').check()
    await list.query()
    await list.expectLabels(['T5', 'T2'], 2)
    await list.action('sto').check()
    await list.query()
    await list.expectLabels(ALL, 6)

    for (const a of ['btc', 'stc'] as const) {
      await list.clearAll()
      await list.expectLabels(ALL, 6)
      await list.action(a).check()
      await list.query()
      await expect(list.noMatch).toBeVisible()
      await expect(page.getByTestId('trade-list-error')).toHaveCount(0)
    }
  })

  test('AC5 日期范围：11-03 → T5 T2 T1；11-04 → T4；只开始 / 只结束；颠倒 → 错误；未来范围 → 无结果', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await expect(page.getByText('美东日期').first()).toBeVisible()
    const range = async (start: string, end: string) => {
      await list.startDate.fill(start)
      await list.endDate.fill(end)
      await list.query()
    }
    await range('2026-11-03', '2026-11-03')
    await list.expectLabels(['T5', 'T2', 'T1'], 3)
    await range('2026-11-04', '2026-11-04')
    await list.expectLabels(['T4'], 1)
    await range('2026-11-03', '')
    await list.expectLabels(['T4', 'T5', 'T2', 'T1'], 4)
    await range('', '2026-11-02')
    await list.expectLabels(['T3', 'T6'], 2)
    await range('2026-11-06', '2099-12-31')
    await expect(list.noMatch).toBeVisible()
    await expect(page.getByTestId('trade-list-error')).toHaveCount(0)
    await range('2026-11-04', '2026-11-03')
    await expect(list.fieldError(list.endDate)).toHaveText('开始日期不能晚于结束日期')
    await expect(list.rowsLoc).toHaveCount(0)
  })

  test('AC5 夏令时：美东 2026-07-15 21:00 的交易属于 07-15，不属于 07-16', async ({ page }) => {
    await apiTransfer('DEPOSIT', '1000.00', '2026-07-01T10:00')
    await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '100.00', time: '2026-07-15T21:00' })
    const list = new TradeList(page)
    await list.open('?startDate=2026-07-15&endDate=2026-07-15')
    await expect(list.rowsLoc).toHaveCount(1)
    await expect(list.rowsLoc.first().getByTestId('trade-cell-time')).toHaveText('2026-07-15 21:00')
    await list.open('?startDate=2026-07-16&endDate=2026-07-16')
    await expect(list.noMatch).toBeVisible()
  })

  test('AC6 组合筛选：AAPL+STO+11-03..11-04 → T4 T1；结束改 11-03 → T1；清除筛选 → 6 条第 1 页', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.symbol.fill('AAPL')
    await list.action('sto').check()
    await list.startDate.fill('2026-11-03')
    await list.endDate.fill('2026-11-04')
    await list.query()
    await list.expectLabels(['T4', 'T1'], 2)
    await list.endDate.fill('2026-11-03')
    await list.query()
    await list.expectLabels(['T1'], 1)
    await list.clearAll()
    await list.expectLabels(ALL, 6)
    for (const f of [list.symbol, list.expiration, list.strike, list.startDate, list.endDate]) await expect(f).toHaveValue('')
    await expect(list.action('sto')).not.toBeChecked()
    await expect(list.typeAny).toBeChecked()
    expect(new URL(page.url()).search).toBe('')
  })

  test('AC7 分页 45 笔：20/20/5 无重复遗漏；每页 50 → 1 页；第 3 页筛选开始日期 → 回到第 1 页共 15 条', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100000.00', '2026-08-01T10:00')
    const start = Date.UTC(2026, 8, 1)
    for (let i = 0; i < 45; i++) {
      const d = new Date(start + i * 86400000).toISOString().slice(0, 10)
      await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '1.00', time: `${d}T10:00` })
    }
    const list = new TradeList(page)
    await list.open()
    await expect(list.total).toHaveText('共 45 条')
    await expect(list.rowsLoc).toHaveCount(20)
    await expect(list.rowsLoc.first().getByTestId('trade-cell-time')).toHaveText('2026-10-15 10:00')
    await expect(list.pagination.locator('.ant-pagination-item')).toHaveCount(3)

    const seen: string[] = []
    for (let p = 1; p <= 3; p++) {
      if (p > 1) await list.pagination.locator(`.ant-pagination-item-${p}`).click()
      await expect(list.pagination.locator('.ant-pagination-item-active')).toHaveText(`${p}`)
      await expect(list.rowsLoc).toHaveCount(p === 3 ? 5 : 20)
      seen.push(...(await list.cells()).map((c) => c.time ?? ""))
    }
    expect(new Set(seen).size).toBe(45)
    expect(seen).toEqual([...seen].sort().reverse())
    expect(seen.at(-1)).toBe('2026-09-01 10:00')
    expect(new URL(page.url()).searchParams.get('page')).toBe('3')

    // still on page 3: filter -> page 1
    await list.startDate.fill('2026-10-01')
    await list.query()
    await expect(list.total).toHaveText('共 15 条')
    await expect(list.pagination.locator('.ant-pagination-item-active')).toHaveText('1')
    await expect(list.rowsLoc).toHaveCount(15)

    await list.clearAll()
    await expect(list.total).toHaveText('共 45 条')
    await page.getByTestId('trade-list-size-50').locator('xpath=ancestor::label[1]').click()
    await expect(list.rowsLoc).toHaveCount(45)
    await expect(list.pagination.locator('.ant-pagination-item')).toHaveCount(1)
    expect(new URL(page.url()).searchParams.get('size')).toBe('50')
    await page.reload()
    await expect(list.rowsLoc).toHaveCount(45)
  })

  test('AC8 空状态：只有入金没有交易 → 暂无交易 + 录入开仓入口', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100.00', '2026-10-26T10:00')
    const list = new TradeList(page)
    await list.open()
    await expect(list.empty).toContainText('暂无交易')
    await expect(list.rowsLoc).toHaveCount(0)
    await page.getByTestId('trade-list-empty-new').click()
    await expect(page).toHaveURL(/\/trades\/new$/)
  })

  test('AC9 地址栏：AAPL+STO 写入地址，新标签页打开回填；改 BTO 后后退恢复；颠倒日期地址显示错误，清除后 6 条', async ({ page, context }) => {
    const ids = await seedBaseline()
    const list = new TradeList(page, ids)
    await list.open()
    await list.symbol.fill('AAPL')
    await list.action('sto').check()
    await list.query()
    await list.expectLabels(['T4', 'T1'], 2)
    const url = page.url()
    const params = new URL(url).searchParams
    expect(params.get('symbol')?.toUpperCase()).toBe('AAPL')
    expect(params.getAll('action').join(',')).toContain('STO')

    const tab = await context.newPage()
    const list2 = new TradeList(tab, ids)
    await tab.goto(url)
    await list2.expectLabels(['T4', 'T1'], 2)
    await expect(list2.symbol).toHaveValue(/aapl/i)
    await expect(list2.action('sto')).toBeChecked()
    await tab.reload()
    await list2.expectLabels(['T4', 'T1'], 2)
    await tab.close()

    await list.action('sto').uncheck()
    await list.action('bto').check()
    await list.query()
    await list.expectLabels(['T2'], 1)
    await page.goBack()
    await list.expectLabels(['T4', 'T1'], 2)
    await expect(list.action('sto')).toBeChecked()
    await expect(list.action('bto')).not.toBeChecked()

    await list.open('?startDate=2026-11-04&endDate=2026-11-03')
    await expect(list.fieldError(list.endDate)).toHaveText('开始日期不能晚于结束日期')
    await expect(list.invalid).toBeVisible()
    await list.clearAll()
    await list.expectLabels(ALL, 6)
  })

  test('非法地址参数不崩溃：strike=abc、不存在的日期（后端错误）、action=XYZ、size=30、page=99', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open('?strike=abc')
    await expect(list.fieldError(list.strike)).not.toHaveText('')
    await expect(list.invalid).toBeVisible()

    await list.open('?expirationDate=2026-02-30')
    await expect(list.fieldError(list.expiration)).toHaveText('日期格式不正确（YYYY-MM-DD）')

    await list.open('?action=XYZ')
    await expect(page.getByTestId('trade-list-page')).toBeVisible()
    await expect(list.fieldError(page.getByTestId('trade-filter-action-bto'))).not.toHaveText('')

    await list.open('?size=30')
    await list.expectLabels(ALL, 6)

    await list.open('?page=99')
    await list.expectLabels(ALL, 6)
    await expect(list.pagination.locator('.ant-pagination-item-active')).toHaveText('1')
  })

  test('AC10 录入后无需刷新：已打开列表 → 录入 STO F 12.5 P 25.00 @11-05 09:45 → 回列表第一行即此笔、共 7 条', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.expectLabels(ALL, 6)
    await page.getByTestId('nav-open-trade').click()
    const form = new TradeForm(page)
    await form.submitAndExpectSaved(
      TradeForm.base({ action: 'STO', symbol: 'F', strike: '12.5', type: 'PUT', netAmount: '25.00', time: '2026-11-05T09:45' }),
    )
    await page.getByTestId('nav-trades').click()
    await expect(list.total).toHaveText('共 7 条')
    const first = (await list.cells())[0]
    expect(first).toMatchObject({ time: '2026-11-05 09:45', net: '+25.00', frozen: '+1,250.00', contract: 'F 2026-12-18 12.5 P' })
  })

  test('AC11 只读：筛选、翻页后现金汇总 99,379.75 / 44,500.00 / 54,879.75，流水仍 7 条', async ({ page }) => {
    const list = new TradeList(page, await seedBaseline())
    await list.open()
    await list.symbol.fill('aapl')
    await list.query()
    await list.expectLabels(['T4', 'T2', 'T1'], 3)
    await list.open('?size=20&page=2')
    await list.open('?startDate=2026-11-03')
    await list.expectLabels(['T4', 'T5', 'T2', 'T1'], 4)
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('99,379.75', '44,500.00', '54,879.75')
    expect((await apiLedger()).totalItems).toBe(7)
  })

  test('长备注（500 字符）截断显示，悬停 title 为全文', async ({ page }) => {
    await apiTransfer('DEPOSIT', '1000.00', '2026-10-26T10:00')
    const note = '长'.repeat(500)
    const r = await fetch('http://localhost:8080/api/option-trades', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        symbol: 'AAPL', expirationDate: '2026-12-18', strike: '210', optionType: 'CALL', action: 'BTO',
        contracts: 1, netAmount: '1.00', tradedAtLocal: '2026-11-03T10:00', multiplier: 100, note,
      }),
    })
    expect(r.status).toBe(201)
    const list = new TradeList(page)
    await list.open()
    const cell = list.rowsLoc.first().getByTestId('trade-cell-note')
    await expect(cell).toHaveAttribute('title', note)
    const box = await cell.boundingBox()
    expect(box?.width ?? 9999).toBeLessThanOrEqual(260)
    const tableBox = await page.getByTestId('trade-list').boundingBox()
    const viewport = page.viewportSize()
    expect(tableBox!.width).toBeLessThanOrEqual(viewport!.width)
  })
})
