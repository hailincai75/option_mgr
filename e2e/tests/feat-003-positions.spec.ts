import type { Locator, Page } from '@playwright/test'
import { apiLedger, apiTransfer, CashPage, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { apiTrade, TradeForm } from '../support/trade'
import { stablePhase } from '../support/ny'

/**
 * FEAT-003 期权持仓 E2E. Real clock: "today" is the New York date at run time; DTE expectations are computed
 * as expiration − today (calendar days). Expired / 0DTE positions use backfilled trades (trade date ≤ expiry).
 */

const today = () => nyLocal(new Date()).slice(0, 10)
const days = (from: string, to: string) =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000)

async function seedBaseline(): Promise<number[]> {
  await apiTransfer('DEPOSIT', '100000.00', '2026-10-26T10:00')
  const ids: number[] = []
  ids.push((await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30', note: '第一笔 CSP' })).id)
  ids.push((await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65', time: '2026-11-03T10:30' })).id)
  ids.push((await apiTrade({ action: 'STO', symbol: 'F', strike: '12.5', type: 'PUT', contracts: 2, netAmount: '48.70', time: '2026-11-02T15:59' })).id)
  ids.push((await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-04T11:00', note: '加仓' })).id)
  ids.push((await apiTrade({ action: 'BTO', symbol: 'MSFT', expiration: '2027-01-15', strike: '450', type: 'CALL', netAmount: '900.00', time: '2026-11-03T23:30' })).id)
  ids.push((await apiTrade({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-10-30T10:00', multiplier: 10 })).id)
  return ids
}

class Positions {
  readonly asOf: Locator
  readonly count: Locator
  readonly frozenTotal: Locator
  readonly rows: Locator

  constructor(readonly page: Page) {
    this.asOf = page.getByTestId('positions-as-of')
    this.count = page.getByTestId('positions-count')
    this.frozenTotal = page.getByTestId('positions-frozen-total')
    this.rows = page.getByTestId('position-row')
  }

  async open(): Promise<void> {
    await this.page.goto('/positions')
    await expect(this.asOf).toBeVisible()
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  async order(): Promise<string[]> {
    return this.rows.evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract') ?? ''))
  }

  async cells(): Promise<Record<string, string>[]> {
    return this.rows.evaluateAll((rs) =>
      rs.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
        return {
          contract: t('position-cell-contract'),
          direction: t('position-cell-direction'),
          contracts: t('position-cell-contracts'),
          openingNet: t('position-cell-opening-net'),
          average: t('position-cell-average-net'),
          frozen: t('position-cell-frozen'),
          expiration: t('position-cell-expiration'),
          dte: t('position-cell-dte'),
          lots: t('position-cell-lot-count'),
        }
      }),
    )
  }

  /** Expands a position and returns its lot rows' texts (FIFO order as displayed). */
  async lots(contract: string): Promise<Record<string, string>[]> {
    const row = this.row(contract)
    const btn = row.getByTestId('position-expand')
    if ((await btn.getAttribute('aria-expanded')) !== 'true') await btn.click()
    await expect(btn).toHaveAttribute('aria-expanded', 'true')
    const lotTable = row.locator('xpath=following-sibling::tr[1]').getByTestId('position-lots')
    await expect(lotTable).toBeVisible()
    return lotTable.getByTestId('lot-row').evaluateAll((rs) =>
      rs.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        return {
          tradeId: r.getAttribute('data-trade-id') ?? '',
          time: t('lot-cell-time'),
          action: t('lot-cell-action'),
          contracts: t('lot-cell-contracts'),
          net: t('lot-cell-net-amount'),
          frozen: t('lot-cell-frozen'),
          note: t('lot-cell-note'),
        }
      }),
    )
  }
}

test.describe('FEAT-003 期权持仓', () => {
  test('AC1+AC5 基准数据：截至今天（美东）、共 5 个持仓、冻结合计 44,500.00 = 资金页；顺序与各列；DTE = 到期日 − 今天', async ({ page }) => {
    await seedBaseline()
    await page.goto('/')
    await page.getByTestId('nav-positions').click()
    await expect(page).toHaveURL(/\/positions$/)
    const pos = new Positions(page)
    const d = today()
    await expect(pos.asOf).toHaveText(`截至 ${d}（美东）`)
    await expect(pos.count).toHaveText('共 5 个持仓')
    await expect(pos.frozenTotal).toHaveText('冻结现金合计 44,500.00')
    await expect(page.locator('.ant-menu-item-selected')).toHaveCount(1)
    await expect(page.locator('.ant-menu-item-selected')).toContainText('持仓')
    const dec = String(days(d, '2026-12-18'))
    const jan = String(days(d, '2027-01-15'))
    expect(await pos.cells()).toEqual([
      { contract: 'AAPL 2026-12-18 200 P', direction: '空头', contracts: '2', openingNet: '+709.35', average: '+354.68', frozen: '40,000.00', expiration: '2026-12-18', dte: dec, lots: '2' },
      { contract: 'AAPL 2026-12-18 210 C', direction: '多头', contracts: '1', openingNet: `${MINUS}512.65`, average: `${MINUS}512.65`, frozen: '—', expiration: '2026-12-18', dte: dec, lots: '1' },
      { contract: 'F 2026-12-18 12.5 P', direction: '空头', contracts: '2', openingNet: '+48.70', average: '+24.35', frozen: '2,500.00', expiration: '2026-12-18', dte: dec, lots: '1' },
      { contract: 'XYZ 2026-12-18 200 P', direction: '空头', contracts: '1（multiplier 10）', openingNet: '+34.35', average: '+34.35', frozen: '2,000.00', expiration: '2026-12-18', dte: dec, lots: '1' },
      { contract: 'MSFT 2027-01-15 450 C', direction: '多头', contracts: '1', openingNet: `${MINUS}900.00`, average: `${MINUS}900.00`, frozen: '—', expiration: '2027-01-15', dte: jan, lots: '1' },
    ])
    // header hints
    const headers = (await page.getByTestId('positions-page').locator('th').allTextContents()).join('|')
    expect(headers).toContain('仅供参考')
    await expect(page.getByText('空头为实收、多头为实付，均已含手续费').first()).toBeVisible()
    // FEAT-003 itself adds no operation buttons; later features add them by backend flags (Q34):
    // 平仓 (FEAT-004) and 被指派 on short puts (FEAT-013) — never 删除 / 编辑 / 行权 here
    await expect(page.getByTestId('positions-page').getByRole('button', { name: /删\s*除|编\s*辑|行\s*权/ })).toHaveCount(0)
    await expect
      .poll(() => page.getByTestId('position-assign').evaluateAll((bs) => bs.map((b) => b.closest('[data-contract]')?.getAttribute('data-contract'))))
      .toEqual(['AAPL 2026-12-18 200 P', 'F 2026-12-18 12.5 P', 'XYZ 2026-12-18 200 P'])
    // frozen total equals the cash page
    await page.getByTestId('nav-cash').click()
    await expect(page.getByTestId('cash-summary-frozen')).toHaveText('44,500.00')
  })

  test('AC2 展开批次：AAPL 200 P 两批 FIFO；F 一笔 2 张一批；AAPL 210 C 一批 BTO；收起后隐藏', async ({ page }) => {
    const ids = await seedBaseline()
    const pos = new Positions(page)
    await pos.open()
    expect(await pos.lots('AAPL 2026-12-18 200 P')).toEqual([
      { tradeId: String(ids[0]), time: '2026-11-03 10:30', action: 'STO（卖出开仓）', contracts: '1 / 1', net: '+349.35', frozen: '20,000.00', note: '第一笔 CSP' },
      { tradeId: String(ids[3]), time: '2026-11-04 11:00', action: 'STO（卖出开仓）', contracts: '1 / 1', net: '+360.00', frozen: '20,000.00', note: '加仓' },
    ])
    expect(await pos.lots('F 2026-12-18 12.5 P')).toEqual([
      { tradeId: String(ids[2]), time: '2026-11-02 15:59', action: 'STO（卖出开仓）', contracts: '2 / 2', net: '+48.70', frozen: '2,500.00', note: '—' },
    ])
    expect(await pos.lots('AAPL 2026-12-18 210 C')).toEqual([
      { tradeId: String(ids[1]), time: '2026-11-03 10:30', action: 'BTO（买入开仓）', contracts: '1 / 1', net: `${MINUS}512.65`, frozen: '—', note: '—' },
    ])
    const btn = pos.row('AAPL 2026-12-18 200 P').getByTestId('position-expand')
    await btn.click()
    await expect(btn).toHaveAttribute('aria-expanded', 'false')
    await expect(
      pos.row('AAPL 2026-12-18 200 P').locator('xpath=following-sibling::tr[1]').getByTestId('position-lots'),
    ).toBeHidden()
  })

  test('AC3 每张平均净额四舍五入：KO −66.67、PEP −33.33', async ({ page }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'BTO', symbol: 'KO', strike: '60', type: 'CALL', contracts: 3, netAmount: '200.00', time: '2026-11-02T10:00' })
    await apiTrade({ action: 'BTO', symbol: 'PEP', strike: '150', type: 'CALL', contracts: 3, netAmount: '100.00', time: '2026-11-02T10:00' })
    const pos = new Positions(page)
    await pos.open()
    const cells = await pos.cells()
    expect(cells.map((c) => [c.contract, c.openingNet, c.average])).toEqual([
      ['KO 2026-12-18 60 C', `${MINUS}200.00`, `${MINUS}66.67`],
      ['PEP 2026-12-18 150 C', `${MINUS}100.00`, `${MINUS}33.33`],
    ])
  })

  test('AC4 冻结按批次相加 24.70；顶部合计 24.70 = 资金页；两批各 12.35', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100.00', '2026-10-26T10:00')
    for (const time of ['2026-11-02T10:00', '2026-11-03T10:00']) {
      await apiTrade({ action: 'STO', symbol: 'F', strike: '12.345', type: 'PUT', netAmount: '0.50', time, multiplier: 1 })
    }
    const pos = new Positions(page)
    await pos.open()
    expect((await pos.cells())[0]).toMatchObject({
      contract: 'F 2026-12-18 12.345 P', direction: '空头', contracts: '2（multiplier 1）', openingNet: '+1.00', average: '+0.50', frozen: '24.70', lots: '2',
    })
    await expect(pos.frozenTotal).toHaveText('冻结现金合计 24.70')
    expect((await pos.lots('F 2026-12-18 12.345 P')).map((l) => l.frozen)).toEqual(['12.35', '12.35'])
    await page.getByTestId('nav-cash').click()
    await expect(page.getByTestId('cash-summary-frozen')).toHaveText('24.70')
  })

  test('AC6 已过期与今天到期：TSLA 2026-11-06 红色"已过期 N 天"；今天到期的 SPY 显示 0 + 今天到期；冻结合计 25,000.00', async ({ page }) => {
    const d = today()
    await apiTransfer('DEPOSIT', '50000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', symbol: 'TSLA', expiration: '2026-11-06', strike: '250', type: 'PUT', netAmount: '300.00', time: '2026-11-02T10:00' })
    await apiTrade({ action: 'BTO', symbol: 'SPY', expiration: d, strike: '600', type: 'CALL', netAmount: '50.00', time: '2026-11-02T10:00' })
    const pos = new Positions(page)
    // FEAT-012: between 16:00 and 17:30 NY a position expiring today also shows the 到期待确认 tag in the DTE cell
    const phase = await stablePhase()
    await pos.open()
    await expect(pos.asOf).toHaveText(`截至 ${d}（美东）`)
    await expect(pos.count).toHaveText('共 2 个持仓')
    await expect(pos.frozenTotal).toHaveText('冻结现金合计 25,000.00')
    const n = days('2026-11-06', d)
    expect(await pos.cells()).toEqual([
      { contract: 'TSLA 2026-11-06 250 P', direction: '空头', contracts: '1', openingNet: '+300.00', average: '+300.00', frozen: '25,000.00', expiration: '2026-11-06', dte: `已过期 ${n} 天`, lots: '1' },
      { contract: `SPY ${d} 600 C`, direction: '多头', contracts: '1', openingNet: `${MINUS}50.00`, average: `${MINUS}50.00`, frozen: '—', expiration: d, dte: phase === 'pending' ? '0 今天到期 到期待确认' : '0 今天到期', lots: '1' },
    ])
    const expiredCell = pos.row('TSLA 2026-11-06 250 P').getByTestId('position-cell-dte')
    const color = await expiredCell.evaluate((el) => getComputedStyle(el).color)
    const [r, g, b] = (color.match(/\d+/g) ?? []).map(Number)
    expect(r!).toBeGreaterThan(150)
    expect(g!).toBeLessThan(100)
    expect(b!).toBeLessThan(100)
    await expect(pos.row(`SPY ${d} 600 C`).getByTestId('position-cell-dte-today')).toHaveText('今天到期')
  })

  test('AC7 录入后自动刷新：AAPL 200 P 变 3 张 +1,049.35 平均 +349.78 冻结 60,000.00；NVDA 出现在 F 后 XYZ 前', async ({ page }) => {
    await seedBaseline()
    const pos = new Positions(page)
    await pos.open()
    await expect(pos.count).toHaveText('共 5 个持仓')
    await page.getByTestId('nav-open-trade').click()
    const form = new TradeForm(page)
    await form.submitAndExpectSaved(TradeForm.base({ action: 'STO', strike: '200', type: 'PUT', netAmount: '340.00', time: '2026-11-05T10:00' }))
    await page.getByTestId('nav-positions').click()
    await expect(pos.frozenTotal).toHaveText('冻结现金合计 64,500.00')
    await expect(pos.count).toHaveText('共 5 个持仓')
    expect((await pos.cells())[0]).toMatchObject({
      contract: 'AAPL 2026-12-18 200 P', contracts: '3', openingNet: '+1,049.35', average: '+349.78', frozen: '60,000.00', lots: '3',
    })
    const lots = await pos.lots('AAPL 2026-12-18 200 P')
    expect(lots.map((l) => l.time)).toEqual(['2026-11-03 10:30', '2026-11-04 11:00', '2026-11-05 10:00'])

    await page.getByTestId('nav-open-trade').click()
    await form.submitAndExpectSaved(TradeForm.base({ action: 'BTO', symbol: 'NVDA', strike: '150', type: 'CALL', netAmount: '400.00', time: '2026-11-05T10:30' }))
    await page.getByTestId('nav-positions').click()
    await expect(pos.count).toHaveText('共 6 个持仓')
    expect(await pos.order()).toEqual([
      'AAPL 2026-12-18 200 P', 'AAPL 2026-12-18 210 C', 'F 2026-12-18 12.5 P', 'NVDA 2026-12-18 150 C', 'XYZ 2026-12-18 200 P', 'MSFT 2027-01-15 450 C',
    ])
    expect((await pos.cells())[3]).toMatchObject({ dte: String(days(today(), '2026-12-18')), frozen: '—' })
  })

  test('AC8 点击合约跳到交易列表：筛选 AAPL / 2026-12-18 / 200 / Put，显示 T4、T1', async ({ page }) => {
    const ids = await seedBaseline()
    const pos = new Positions(page)
    await pos.open()
    await pos.row('AAPL 2026-12-18 200 P').getByTestId('position-cell-contract').click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect(page.getByTestId('trade-filter-symbol')).toHaveValue('AAPL')
    await expect(page.getByTestId('trade-filter-expiration')).toHaveValue('2026-12-18')
    await expect(page.getByTestId('trade-filter-strike')).toHaveValue('200')
    await expect(page.getByTestId('trade-filter-type-put')).toBeChecked()
    await expect
      .poll(() => page.getByTestId('trade-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id'))))
      .toEqual([String(ids[3]), String(ids[0])])
    await expect(page.getByTestId('trade-list-total')).toHaveText('共 2 条')
  })

  test('AC9 空状态：暂无持仓 + 录入开仓入口，共 0 个持仓、冻结现金合计 0.00', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100.00', '2026-10-26T10:00')
    const pos = new Positions(page)
    await pos.open()
    await expect(page.getByTestId('positions-empty')).toContainText('暂无持仓')
    await expect(pos.count).toHaveText('共 0 个持仓')
    await expect(pos.frozenTotal).toHaveText('冻结现金合计 0.00')
    await expect(pos.asOf).toHaveText(`截至 ${today()}（美东）`)
    await expect(pos.rows).toHaveCount(0)
    await page.getByTestId('positions-empty-new').click()
    await expect(page).toHaveURL(/\/trades\/new$/)
  })

  test('AC10 只读：展开/收起各行后现金汇总不变、交易仍 6 笔、流水 7 条', async ({ page }) => {
    await seedBaseline()
    const pos = new Positions(page)
    await pos.open()
    for (const c of await pos.order()) {
      await pos.lots(c)
      await pos.row(c).getByTestId('position-expand').click()
    }
    await page.reload()
    await expect(pos.count).toHaveText('共 5 个持仓')
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('99,379.75', '44,500.00', '54,879.75')
    expect((await apiLedger()).totalItems).toBe(7)
    await page.getByTestId('nav-trades').click()
    await expect(page.getByTestId('trade-list-total')).toHaveText('共 6 条')
  })
})
