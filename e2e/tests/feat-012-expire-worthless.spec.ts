import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiTrade } from '../support/trade'
import { BACKEND_URL } from '../support/backend'
import { addDays, nyToday, stablePhase } from '../support/ny'

/**
 * FEAT-012 到期作废 E2E (real clock). The UI flows use scenario E shifted to an expiration date that is always in
 * the past (2026-11-06, a Friday; same contracts / amounts as the requirement's 2026-11-20 scenario), because a
 * real clock cannot be moved to 2026-11-20 17:30. AC4 / AC9 use dates relative to today's New York date T and pick
 * their assertions by the current New York time (before 16:00 / 16:00–17:30 / from 17:30).
 * DB reset per test: scenario E = deposit #1, E1–E5 = trades #1–#5, the first expiry = trade #6.
 */

const BACKEND = BACKEND_URL
const EXP = '2026-11-06'
const XYZ = `XYZ ${EXP} 45 P`
const QRTV_C = `QRTV ${EXP} 30 C`
const IXQZ = `IXQZ ${EXP} 8 C`
const QRTV_P = `QRTV ${EXP} 25 P`

const days = (from: string, to: string) => Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000)

async function scenarioE(): Promise<void> {
  await apiTransfer('DEPOSIT', '20000.00', '2026-10-26T10:00')
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', contracts: 2, netAmount: '240.00', time: '2026-10-27T10:00' })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '30', type: 'CALL', netAmount: '85.65', time: '2026-10-28T10:00' })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '130.00', time: '2026-10-29T10:00' })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: EXP, strike: '8', type: 'CALL', netAmount: '22.34', time: '2026-10-30T10:00' })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '25', type: 'PUT', contracts: 2, netAmount: '40.00', time: '2026-10-30T11:00' })
}

async function apiExpire(symbol: string, expirationDate: string, strike: string, optionType: 'CALL' | 'PUT', contracts: number): Promise<{ id: number }> {
  const r = await fetch(`${BACKEND}/api/option-trades/expirations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol, expirationDate, strike, optionType, contracts }),
  })
  if (r.status !== 201) throw new Error(`expire failed ${r.status} ${await r.text()}`)
  return (await r.json()) as { id: number }
}

async function apiGet<T>(path: string): Promise<T> {
  return (await (await fetch(`${BACKEND}${path}`)).json()) as T
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

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  /** antd puts the modal testid on a zero-size root; the visible part is the dialog inside it. */
  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  posRow(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  tradeRow(id: number): Locator {
    return this.page.locator(`[data-testid="trade-row"][data-trade-id="${id}"]`)
  }

  closedRow(id: number): Locator {
    return this.page.locator(`[data-testid="closed-row"][data-trade-id="${id}"]`)
  }

  async openPositions(): Promise<void> {
    await this.page.goto('/positions')
    await expect(this.id('positions-as-of')).toBeVisible()
  }

  async positionContracts(): Promise<string[]> {
    return this.id('position-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract') ?? ''))
  }

  async openExpire(contract: string): Promise<Locator> {
    await this.posRow(contract).getByTestId('position-expire').click()
    const d = this.dialog('expire-modal')
    await expect(d).toBeVisible()
    return d
  }

  /** Confirms the expire dialog and waits for the result. */
  async confirmExpire(d: Locator): Promise<void> {
    await d.getByTestId('expire-submit').click()
    await expect(d.getByTestId('expire-result')).toBeVisible()
  }

  async lots(scope: Locator): Promise<string[]> {
    return scope.getByTestId('covered-lot-row').evaluateAll((rs) =>
      rs.map((r) =>
        ['covered-lot-time', 'covered-lot-action', 'covered-lot-contracts', 'covered-lot-net-amount']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async tradeCells(): Promise<Record<string, string>[]> {
    return this.id('trade-row').evaluateAll((rows) =>
      rows.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        return {
          id: r.getAttribute('data-trade-id') ?? '',
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

  async closedCells(): Promise<Record<string, string>[]> {
    return this.id('closed-row').evaluateAll((rs) =>
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

  async closedLots(id: number): Promise<string[]> {
    await this.closedRow(id).getByTestId('closed-expand').click()
    const lots = this.closedRow(id).locator('xpath=following-sibling::tr[1]').getByTestId('closed-lot-row')
    await expect(lots.first()).toBeVisible()
    return lots.evaluateAll((rs) =>
      rs.map((r) =>
        ['closed-lot-time', 'closed-lot-action', 'closed-lot-contracts', 'closed-lot-net-amount']
          .map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async pnlRows(rowId: string, prefix: string): Promise<string[]> {
    return this.id(rowId).evaluateAll(
      (rs, p) =>
        rs.map((r) =>
          [`${p}-link`, `${p}-count`, `${p}-pnl`]
            .map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '')
            .join(' '),
        ),
      prefix,
    )
  }

  async markNoReload(): Promise<void> {
    await this.page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })
  }

  async expectNoReload(): Promise<void> {
    expect(await this.page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
  }
}

/** "This month" figures only apply while the run date is in 2026-11 (the expiries are recorded in 2026-11). */
const inExpiryMonth = () => nyToday().startsWith('2026-11')

test.describe('FEAT-012 到期作废', () => {
  test('AC1+AC2+AC13 已过期持仓只有作废；XYZ 两批次作废：确认框、结果；不刷新页面，首页/资金/交易列表/已平仓/盈亏汇总都更新，审计无记录', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    const n = days(EXP, nyToday())
    await page.goto('/')
    await ui.markNoReload()
    await expect(ui.id('dashboard-expired-count')).toHaveText('4')
    await expect(ui.id('dashboard-frozen-total')).toHaveText('13,500.00')

    await ui.id('nav-positions').click()
    await expect.poll(() => ui.positionContracts()).toEqual([IXQZ, QRTV_P, QRTV_C, XYZ])
    for (const c of [IXQZ, QRTV_P, QRTV_C, XYZ]) {
      const row = ui.posRow(c)
      await expect(row.getByTestId('position-cell-dte')).toHaveText(`已过期 ${n} 天`)
      expect(await colorOf(row.getByTestId('position-cell-dte'))).toBe('red')
      await expect(row.getByTestId('position-expire')).toBeVisible()
      await expect(row.getByTestId('position-expire')).toHaveText(/作\s*废/)
      await expect(row.getByTestId('position-close')).toHaveCount(0)
      await expect(row.getByTestId('position-expiry-pending')).toHaveCount(0)
    }

    const d = await ui.openExpire(XYZ)
    await expect(d.getByTestId('expire-contract')).toHaveText(XYZ)
    await expect(d.getByTestId('expire-direction')).toHaveText('空头')
    await expect(d.getByTestId('expire-contracts')).toHaveText('3 张（全部剩余）')
    await expect(d.getByTestId('expire-time')).toHaveText(`${EXP} 16:00（美东）`)
    await expect(d.getByTestId('expire-opening-net')).toHaveText('+370.00')
    await expect(d.getByTestId('expire-frozen')).toHaveText('13,500.00')
    await expect(d.getByTestId('expire-explanation')).toHaveText(
      '到期作废记在到期日 16:00；空头保留全部权利金、多头亏掉全部开仓成本；记错可以在交易列表中删除',
    )
    await expect(d.locator('input, textarea')).toHaveCount(0) // Q84: nothing to fill in
    await expect(d.getByTestId('expire-cancel')).toBeVisible()
    await ui.confirmExpire(d)
    await expect(d.getByTestId('expire-result-pnl')).toHaveText('+370.00')
    expect(await colorOf(d.getByTestId('expire-result-pnl'))).toBe('green')
    await expect(d.getByTestId('expire-result-frozen-change')).toHaveText(`${MINUS}13,500.00`)
    await expect(d.getByTestId('expire-result-direction')).toHaveText('空头')
    await expect(d.getByTestId('expire-result-contracts')).toHaveText('3 张')
    await expect(d.getByTestId('expire-result-time')).toHaveText(`${EXP} 16:00（美东）`)
    expect(await ui.lots(d.getByTestId('expire-result-lots'))).toEqual([
      '2026-10-27 10:00 STO（卖出开仓） 2 张 +240.00',
      '2026-10-29 10:00 STO（卖出开仓） 1 张 +130.00',
    ])
    await d.getByTestId('expire-done').click()
    await expect(ui.dialog('expire-modal')).toBeHidden()
    await expect.poll(() => ui.positionContracts()).toEqual([IXQZ, QRTV_P, QRTV_C])
    const stored = await apiGet<{ tradedAt: string }>('/api/option-trades/6')
    expect(stored.tradedAt).toBe('2026-11-06T21:00:00Z')

    // AC13: every page shows the new figures without a reload
    await ui.id('nav-home').click()
    await expect(ui.id('dashboard-expired-count')).toHaveText('3')
    await expect(ui.id('dashboard-frozen-total')).toHaveText('0.00')
    await expect(ui.id('dashboard-realized-total')).toHaveText('+370.00')
    await expect(ui.id('dashboard-realized-count')).toHaveText('1')
    await expect(ui.id('dashboard-month-total')).toHaveText(inExpiryMonth() ? '+370.00' : '0.00')

    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('20,266.69', '0.00', '20,266.69')
    await expect(cash.ledgerRows).toHaveCount(7)
    await expect.poll(async () => (await cash.rows())[0]).toEqual(
      { time: `${EXP} 16:00`, type: '到期作废', amount: '0.00', frozen: `${MINUS}13,500.00`, balance: '20,266.69', note: '到期作废 3 张 XYZ 2026-11-06 45 P' },
    )
    const ledgerTop = cash.ledgerRows.first()
    await expect(ledgerTop.getByTestId('cash-ledger-trades-link')).toBeVisible()
    await expect(ledgerTop.getByTestId('cash-ledger-edit')).toHaveCount(0)
    await expect(ledgerTop.getByTestId('cash-ledger-delete')).toHaveCount(0)
    await expect(ledgerTop.getByTestId('cash-ledger-history')).toHaveCount(0)

    await ui.id('nav-trades').click()
    await expect(ui.id('trade-list-total')).toHaveText('共 6 条')
    await expect.poll(async () => (await ui.tradeCells())[0]).toEqual({
      id: '6', time: `${EXP} 16:00`, action: '到期作废', contract: XYZ, contracts: '3', net: '0.00', frozen: `${MINUS}13,500.00`, note: '—',
    })
    expect(await colorOf(ui.tradeRow(6).getByTestId('trade-cell-net-amount'))).toBe('other')
    await expect(ui.tradeRow(6).getByTestId('trade-delete')).toBeVisible()
    await expect(ui.tradeRow(6).getByTestId('trade-edit')).toHaveCount(0)
    await expect(ui.tradeRow(6).getByTestId('trade-history')).toHaveCount(0)
    await expect(ui.tradeRow(1).getByTestId('trade-edit')).toBeVisible()

    await ui.id('nav-closed').click()
    await expect(ui.id('closed-count')).toHaveText('共 1 笔平仓')
    await expect(ui.id('closed-pnl-total')).toHaveText('+370.00')
    await expect(ui.id('closed-expiry-note')).toHaveText('到期作废的平仓净额为 0.00，记在到期日 16:00（美东）。')
    expect(await ui.closedCells()).toEqual([
      { time: `${EXP} 16:00`, contract: XYZ, action: '到期作废', direction: '空头', contracts: '3', openingNet: '+370.00', openingPer: '每张 +123.33', closeNet: '0.00', closePer: '每张 0.00', pnl: '+370.00', note: '—' },
    ])
    expect(await colorOf(ui.closedRow(6).getByTestId('closed-cell-pnl'))).toBe('green')
    expect(await ui.closedLots(6)).toEqual([
      '2026-10-27 10:00 STO（卖出开仓） 2 张 +240.00',
      '2026-10-29 10:00 STO（卖出开仓） 1 张 +130.00',
    ])

    await ui.id('nav-pnl-summary').click()
    await expect(ui.id('pnl-total')).toHaveText('+370.00')
    await expect.poll(() => ui.pnlRows('pnl-symbol-row', 'pnl-symbol')).toEqual(['XYZ 1 +370.00'])
    await expect.poll(() => ui.pnlRows('pnl-month-row', 'pnl-month')).toEqual(['2026-11 1 +370.00'])

    await ui.id('nav-audit').click()
    await expect(ui.id('audit-empty')).toBeVisible()
    await expect(ui.id('audit-row')).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC3 多头 call / 空头 call / 多头 put 作废：盈亏颜色、冻结 —、无新流水；已平仓 4 笔 +266.69；盈亏汇总、首页；方向筛选 到期作废 / STO / BTC', async ({ page }) => {
    await scenarioE()
    await apiExpire('XYZ', EXP, '45', 'PUT', 3) // #6
    const ui = new Ui(page)
    await ui.openPositions()

    let d = await ui.openExpire(QRTV_C)
    await expect(d.getByTestId('expire-direction')).toHaveText('多头')
    await expect(d.getByTestId('expire-opening-net')).toHaveText(`${MINUS}85.65`)
    await expect(d.getByTestId('expire-frozen')).toHaveText('—')
    await ui.confirmExpire(d)
    await expect(d.getByTestId('expire-result-pnl')).toHaveText(`${MINUS}85.65`)
    expect(await colorOf(d.getByTestId('expire-result-pnl'))).toBe('red')
    await expect(d.getByTestId('expire-result-frozen-change')).toHaveText('—')
    await d.getByTestId('expire-done').click()

    d = await ui.openExpire(IXQZ)
    await expect(d.getByTestId('expire-direction')).toHaveText('空头')
    await expect(d.getByTestId('expire-frozen')).toHaveText('—')
    await ui.confirmExpire(d)
    await expect(d.getByTestId('expire-result-pnl')).toHaveText('+22.34')
    await expect(d.getByTestId('expire-result-frozen-change')).toHaveText('—')
    await d.getByTestId('expire-done').click()

    d = await ui.openExpire(QRTV_P)
    await expect(d.getByTestId('expire-contracts')).toHaveText('2 张（全部剩余）')
    await ui.confirmExpire(d)
    await expect(d.getByTestId('expire-result-pnl')).toHaveText(`${MINUS}40.00`)
    expect(await ui.lots(d.getByTestId('expire-result-lots'))).toEqual([`2026-10-30 11:00 BTO（买入开仓） 2 张 ${MINUS}40.00`])
    await d.getByTestId('expire-done').click()
    await expect(ui.id('position-row')).toHaveCount(0)

    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('20,266.69', '0.00', '20,266.69')
    await expect(cash.ledgerRows).toHaveCount(7)

    await ui.id('nav-closed').click()
    await expect(ui.id('closed-count')).toHaveText('共 4 笔平仓')
    await expect(ui.id('closed-pnl-total')).toHaveText('+266.69')
    await expect
      .poll(async () => (await ui.closedCells()).map((c) => `${c.contract} ${c.direction} ${c.pnl}`))
      .toEqual([`${QRTV_P} 多头 ${MINUS}40.00`, `${IXQZ} 空头 +22.34`, `${QRTV_C} 多头 ${MINUS}85.65`, `${XYZ} 空头 +370.00`])

    await ui.id('nav-pnl-summary').click()
    await expect(ui.id('pnl-total')).toHaveText('+266.69')
    await expect.poll(() => ui.pnlRows('pnl-symbol-row', 'pnl-symbol')).toEqual(['IXQZ 1 +22.34', `QRTV 2 ${MINUS}125.65`, 'XYZ 1 +370.00'])
    await expect.poll(() => ui.pnlRows('pnl-month-row', 'pnl-month')).toEqual(['2026-11 4 +266.69'])

    await ui.id('nav-home').click()
    await expect(ui.id('dashboard-realized-total')).toHaveText('+266.69')
    await expect(ui.id('dashboard-realized-count')).toHaveText('4')
    await expect(ui.id('dashboard-month-total')).toHaveText(inExpiryMonth() ? '+266.69' : '0.00')
    await expect(ui.id('dashboard-month-count')).toHaveText(inExpiryMonth() ? '4' : '0')
    await expect(ui.id('dashboard-position-count')).toHaveText('0')
    await expect(ui.id('dashboard-frozen-total')).toHaveText('0.00')
    await expect(ui.id('dashboard-expired-count')).toHaveText('0')

    // trade list: only 到期作废 -> 4; only STO -> E4, E3, E1
    await page.goto('/trades')
    await expect(ui.id('trade-list-total')).toHaveText('共 9 条')
    await ui.id('trade-filter-action-exp').check()
    await ui.id('trade-filter-submit').click()
    await expect.poll(() => new URL(page.url()).searchParams.getAll('action').join(',')).toBe('EXP')
    await expect(ui.id('trade-list-total')).toHaveText('共 4 条')
    await expect.poll(async () => (await ui.tradeCells()).map((c) => c.action)).toEqual(['到期作废', '到期作废', '到期作废', '到期作废'])
    await ui.id('trade-filter-action-exp').uncheck()
    await ui.id('trade-filter-action-sto').check()
    await ui.id('trade-filter-submit').click()
    await expect(ui.id('trade-list-total')).toHaveText('共 3 条')
    await expect.poll(async () => (await ui.tradeCells()).map((c) => c.id)).toEqual(['4', '3', '1'])
    await page.goto('/trades?action=EXP&action=BTO')
    await expect(ui.id('trade-list-total')).toHaveText('共 6 条')

    // closed page filter
    await page.goto('/closed')
    await ui.id('closed-filter-action-exp').check()
    await ui.id('closed-filter-submit').click()
    await expect(ui.id('closed-count')).toHaveText('共 4 笔平仓')
    await expect(ui.id('closed-pnl-total')).toHaveText('+266.69')
    await ui.id('closed-filter-action-exp').uncheck()
    await ui.id('closed-filter-action-btc').check()
    await ui.id('closed-filter-submit').click()
    await expect(ui.id('closed-row')).toHaveCount(0)
  })

  test('AC7+AC13 交易列表删除作废：摘要与提示；不刷新页面恢复持仓/冻结；审计记录删除内容；再次作废没有"历史"', async ({ page }) => {
    await scenarioE()
    await apiExpire('XYZ', EXP, '45', 'PUT', 3) // #6
    const ui = new Ui(page)
    await page.goto('/trades')
    await expect(ui.tradeRow(6)).toBeVisible()
    await ui.markNoReload()
    await ui.tradeRow(6).getByTestId('trade-delete').click()
    const d = ui.dialog('trade-delete-modal')
    await expect(d).toBeVisible()
    await expect(d).toContainText('删除到期作废 #6')
    await expect(d.getByTestId('trade-delete-summary')).toHaveText(`到期作废 3 张 ${XYZ}，${EXP} 16:00，0.00`)
    await expect(d.getByTestId('trade-delete-warning')).toHaveText('删除后持仓恢复、冻结恢复，会写入审计记录。')
    await d.getByTestId('trade-delete-confirm').click()
    await expect(ui.dialog('trade-delete-modal')).toBeHidden()
    await expect(ui.tradeRow(6)).toHaveCount(0)
    await expect(ui.id('trade-list-total')).toHaveText('共 5 条')

    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('20,266.69', '13,500.00', '6,766.69')
    await expect(cash.ledgerRows).toHaveCount(6)

    await ui.id('nav-positions').click()
    const row = ui.posRow(XYZ)
    await expect(row.getByTestId('position-cell-contracts')).toHaveText('3')
    await expect(row.getByTestId('position-cell-opening-net')).toHaveText('+370.00')
    await expect(row.getByTestId('position-cell-frozen')).toHaveText('13,500.00')
    await expect(row.getByTestId('position-cell-lot-count')).toHaveText('2')
    await expect(row.getByTestId('position-cell-dte')).toHaveText(`已过期 ${days(EXP, nyToday())} 天`)
    await expect(row.getByTestId('position-expire')).toBeVisible()

    await ui.id('nav-closed').click()
    await expect(ui.id('closed-row')).toHaveCount(0)

    await ui.id('nav-audit').click()
    const first = ui.id('audit-row').first()
    await expect(first.getByTestId('audit-cell-operation')).toHaveText('删除')
    await expect(first.getByTestId('audit-cell-summary')).toContainText('到期作废')
    await expect(first.getByTestId('audit-cell-summary')).toContainText(XYZ)
    await expect(first.getByTestId('audit-cell-changes')).toHaveText('已删除')
    await first.getByTestId('audit-expand').click()
    const detail = ui.id('audit-detail').first()
    await expect(detail.getByTestId('audit-before-action')).toContainText('到期作废')
    await expect(detail.getByTestId('audit-before-contracts')).toContainText('3')
    await expect(detail.getByTestId('audit-before-multiplier')).toContainText('100')
    await expect(detail.getByTestId('audit-before-netAmount')).toContainText('0.00')
    await expect(detail.getByTestId('audit-before-tradedAtLocal')).toContainText(`${EXP} 16:00`)
    await expect(detail.getByTestId('audit-before-cashChange')).toContainText('0.00')
    await expect(detail.getByTestId('audit-before-frozenChange')).toContainText(`${MINUS}13,500.00`)

    // re-expire: new id, no 历史 link
    await ui.id('nav-positions').click()
    const d2 = await ui.openExpire(XYZ)
    await ui.confirmExpire(d2)
    await expect(d2.getByTestId('expire-result-pnl')).toHaveText('+370.00')
    await expect(d2.getByTestId('expire-result-frozen-change')).toHaveText(`${MINUS}13,500.00`)
    await d2.getByTestId('expire-done').click()
    await ui.id('nav-trades').click()
    await expect(ui.tradeRow(7)).toBeVisible()
    await expect(ui.tradeRow(7).getByTestId('trade-cell-action')).toHaveText('到期作废')
    await expect(ui.tradeRow(7).getByTestId('trade-history')).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC5 删除作废被 Q19 拒绝：原文显示在删除对话框、对话框不关闭；先删出金再删作废成功', async ({ page }) => {
    const exp = '2026-11-06'
    await apiTransfer('DEPOSIT', '5000.00', '2026-11-02T10:00')
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: exp, strike: '40', type: 'PUT', netAmount: '95.00', time: '2026-11-03T10:00' })
    await apiExpire('XYZ', exp, '40', 'PUT', 1) // #2
    await apiTransfer('WITHDRAWAL', '1500.00', '2026-11-09T10:00')
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(2).getByTestId('trade-delete').click()
    const d = ui.dialog('trade-delete-modal')
    await d.getByTestId('trade-delete-confirm').click()
    await expect(d.getByTestId('trade-delete-error')).toHaveText(`可用现金不足：删除后 2026-11-09 10:00 时可用现金将变为 ${MINUS}405.00`)
    await expect(d.getByTestId('trade-delete-error')).toHaveAttribute('data-error-code', 'CASH_INSUFFICIENT')
    await expect(d).toBeVisible()
    await d.getByTestId('trade-delete-cancel').click()
    await expect(ui.tradeRow(2)).toBeVisible()

    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('3,595.00', '0.00', '3,595.00')
    await cash.ledgerRows.first().getByTestId('cash-ledger-delete').click()
    await ui.dialog('transfer-delete-modal').getByTestId('transfer-delete-confirm').click()
    await cash.expectSummary('5,095.00', '0.00', '5,095.00')
    await ui.id('nav-trades').click()
    await ui.tradeRow(2).getByTestId('trade-delete').click()
    await ui.dialog('trade-delete-modal').getByTestId('trade-delete-confirm').click()
    await expect(ui.tradeRow(2)).toHaveCount(0)
    await ui.id('nav-cash').click()
    await cash.expectSummary('5,095.00', '4,000.00', '1,095.00')
  })

  test('AC12 对话框打开期间持仓变化：原文"持仓已变化（当前 4 张）"，对话框不关闭；重新打开为 4 张，作废 +480.00 / −18,000.00', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openExpire(XYZ)
    await expect(d.getByTestId('expire-contracts')).toHaveText('3 张（全部剩余）')
    // "another page" backfills a later lot meanwhile
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '110.00', time: '2026-11-05T10:00' })
    await d.getByTestId('expire-submit').click()
    await expect(d.getByTestId('expire-error')).toHaveText('持仓已变化（当前 4 张），请刷新后重试')
    await expect(d.getByTestId('expire-error')).toHaveAttribute('data-error-code', 'POSITION_CHANGED')
    await expect(d).toBeVisible()
    await expect(d.getByTestId('expire-result')).toHaveCount(0)
    await d.getByTestId('expire-cancel').click()
    await expect(ui.dialog('expire-modal')).toBeHidden()
    // the list behind the dialog was reloaded
    await expect(ui.posRow(XYZ).getByTestId('position-cell-contracts')).toHaveText('4')
    const d2 = await ui.openExpire(XYZ)
    await expect(d2.getByTestId('expire-contracts')).toHaveText('4 张（全部剩余）')
    await ui.confirmExpire(d2)
    await expect(d2.getByTestId('expire-result-pnl')).toHaveText('+480.00')
    await expect(d2.getByTestId('expire-result-frozen-change')).toHaveText(`${MINUS}18,000.00`)
    await expect(d2.getByTestId('covered-lot-row')).toHaveCount(3)
  })

  test('后端错误：作废接口返回 500 / 网络失败时对话框显示错误、不关闭、什么都不变', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    await page.route('**/api/option-trades/expirations', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误', status: 500, path: '/api/option-trades/expirations', timestamp: '2026-11-13T15:00:00Z', details: [] }),
      }),
    )
    const d = await ui.openExpire(XYZ)
    await d.getByTestId('expire-submit').click()
    await expect(d.getByTestId('expire-error')).toBeVisible()
    await expect(d.getByTestId('expire-error')).toContainText('服务器内部错误')
    await expect(d).toBeVisible()
    await page.unroute('**/api/option-trades/expirations')
    await page.route('**/api/option-trades/expirations', (route) => route.abort('connectionrefused'))
    await d.getByTestId('expire-submit').click()
    await expect(d.getByTestId('expire-error')).toBeVisible()
    await expect(d.getByTestId('expire-error')).not.toHaveText('')
    await expect(d).toBeVisible()
    await page.unroute('**/api/option-trades/expirations')
    const list = await apiGet<{ totalItems: number }>('/api/option-trades?action=EXP')
    expect(list.totalItems).toBe(0)
  })

  test('AC11 multiplier 1：确认框、交易列表张数、流水说明带 multiplier 附注；冻结变动 −24.70', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100.00', '2026-11-02T10:00')
    await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'PUT', netAmount: '0.50', time: '2026-11-03T10:00', multiplier: 1 })
    await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'PUT', netAmount: '0.50', time: '2026-11-04T10:00', multiplier: 1 })
    const ui = new Ui(page)
    await ui.openPositions()
    const contract = `QFZ ${EXP} 12.345 P`
    const d = await ui.openExpire(contract)
    await expect(d.getByTestId('expire-contracts')).toHaveText('2 张（全部剩余）（multiplier 1）')
    await expect(d.getByTestId('expire-frozen')).toHaveText('24.70')
    await ui.confirmExpire(d)
    await expect(d.getByTestId('expire-result-frozen-change')).toHaveText(`${MINUS}24.70`)
    await expect(d.getByTestId('expire-result-pnl')).toHaveText('+1.00')
    await expect(d.getByTestId('expire-result-contracts')).toHaveText('2 张（multiplier 1）')
    await d.getByTestId('expire-done').click()
    await ui.id('nav-trades').click()
    await expect(ui.tradeRow(3).getByTestId('trade-cell-contracts')).toHaveText('2（multiplier 1）')
    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('101.00', '0.00', '101.00')
    await expect.poll(async () => (await cash.rows())[0]).toEqual(
      { time: `${EXP} 16:00`, type: '到期作废', amount: '0.00', frozen: `${MINUS}24.70`, balance: '101.00', note: `到期作废 2 张 ${contract}（multiplier 1）` },
    )
  })

  test('AC4 E2E（真实时钟）：今天到期的 QRTV <T> 30 C 按美东时刻显示 平仓 / 到期待确认 / 作废；昨天到期的 QRTV <T−1> 25 C 已过期 1 天，作废 −40.00 记在 T−1 16:00', async ({ page }) => {
    const T = nyToday()
    await apiTransfer('DEPOSIT', '1000.00', `${addDays(T, -3)}T10:00`)
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: T, strike: '30', type: 'CALL', netAmount: '50.00', time: `${addDays(T, -1)}T10:00` })
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: addDays(T, -1), strike: '25', type: 'CALL', netAmount: '40.00', time: `${addDays(T, -2)}T10:00` })
    const ui = new Ui(page)
    const phase = await stablePhase()
    await ui.openPositions()

    const today = ui.posRow(`QRTV ${T} 30 C`)
    await expect(today.getByTestId('position-cell-dte-today')).toHaveText('今天到期')
    if (phase === 'closable') {
      await expect(today.getByTestId('position-close')).toBeVisible()
      await expect(today.getByTestId('position-expire')).toHaveCount(0)
      await expect(today.getByTestId('position-expiry-pending')).toHaveCount(0)
    } else if (phase === 'pending') {
      await expect(today.getByTestId('position-expiry-pending')).toHaveText('到期待确认')
      await expect(today.getByTestId('position-close')).toHaveCount(0)
      await expect(today.getByTestId('position-expire')).toHaveCount(0)
      await expect(today.getByRole('button')).toHaveCount(0)
      await today.getByTestId('position-expiry-pending').hover()
      await expect(page.getByRole('tooltip')).toHaveText('16:00 已停止交易；17:30 前仍可能被指派（空头 put 可记录被指派），17:30（美东）起可作废')
    } else {
      await expect(today.getByTestId('position-expire')).toBeVisible()
      await expect(today.getByTestId('position-close')).toHaveCount(0)
      await expect(today.getByTestId('position-expiry-pending')).toHaveCount(0)
    }

    const yesterday = ui.posRow(`QRTV ${addDays(T, -1)} 25 C`)
    await expect(yesterday.getByTestId('position-cell-dte')).toHaveText('已过期 1 天')
    await expect(yesterday.getByTestId('position-close')).toHaveCount(0)
    const d = await ui.openExpire(`QRTV ${addDays(T, -1)} 25 C`)
    await expect(d.getByTestId('expire-time')).toHaveText(`${addDays(T, -1)} 16:00（美东）`)
    await ui.confirmExpire(d)
    await expect(d.getByTestId('expire-result-pnl')).toHaveText(`${MINUS}40.00`)
    await expect(d.getByTestId('expire-result-time')).toHaveText(`${addDays(T, -1)} 16:00（美东）`)
    await d.getByTestId('expire-done').click()
    await expect(yesterday).toHaveCount(0)

    if (phase === 'expirable') {
      const d2 = await ui.openExpire(`QRTV ${T} 30 C`)
      await ui.confirmExpire(d2)
      await expect(d2.getByTestId('expire-result-pnl')).toHaveText(`${MINUS}50.00`)
      await expect(d2.getByTestId('expire-result-time')).toHaveText(`${T} 16:00（美东）`)
      await d2.getByTestId('expire-done').click()
      await expect(ui.id('position-row')).toHaveCount(0)
    }
  })

  test('AC9 E2E（真实时钟）：录入 STC 成交 T−1 16:01 → 成交时间字段显示"不能晚于到期日 16:00"；改为 16:00 成功 −20.00', async ({ page, trade }) => {
    const T = nyToday()
    const exp = addDays(T, -1)
    await apiTransfer('DEPOSIT', '1000.00', `${addDays(T, -3)}T10:00`)
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: exp, strike: '20', type: 'CALL', netAmount: '30.00', time: `${addDays(T, -2)}T10:00` })
    await trade.open()
    await page.getByTestId('open-trade-action-stc').check()
    await trade.fill({ symbol: 'QRTV', expiration: exp, strike: '20', type: 'CALL', contracts: '1', netAmount: '10.00', time: `${exp}T16:01` })
    await trade.submit.click()
    await expect(trade.fieldError(trade.time)).toHaveText(`成交时间不能晚于到期日（${exp}）美东 16:00`)
    await expect(trade.time).toHaveValue(`${exp}T16:01`)
    await expect(trade.confirmation).toHaveCount(0)
    await new CashPage(page).expectSummary('970.00', '0.00', '970.00')

    await trade.time.fill(`${exp}T16:00`)
    await trade.submit.click()
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText(`${MINUS}20.00`)
    await new CashPage(page).expectSummary('980.00', '0.00', '980.00')
  })

  test('Q83 修改交易：成交时间改到到期日 16:01 → 修改对话框成交时间字段报错、不关闭；改到 16:00 成功', async ({ page }) => {
    await apiTransfer('DEPOSIT', '20000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '130.00', time: '2026-10-29T10:00' })
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(1).getByTestId('trade-edit').click()
    const d = ui.dialog('trade-edit-modal')
    await d.getByTestId('trade-edit-time').fill(`${EXP}T16:01`)
    await d.getByTestId('trade-edit-submit').click()
    await expect(
      d.locator('.ant-form-item').filter({ has: page.getByTestId('trade-edit-time') }).locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)'),
    ).toHaveText(`成交时间不能晚于到期日（${EXP}）美东 16:00`)
    await expect(d).toBeVisible()
    expect(((await apiGet<{ totalItems: number }>('/api/audit-log')).totalItems)).toBe(0)
    await d.getByTestId('trade-edit-time').fill(`${EXP}T16:00`)
    await d.getByTestId('trade-edit-submit').click()
    await expect(ui.dialog('trade-edit-modal')).toBeHidden()
    await expect(ui.tradeRow(1).getByTestId('trade-cell-time')).toHaveText(`${EXP} 16:00`)
  })

  test('作废之后录入正好 16:00 的 BTC：录入页显示"成交时没有持仓"（排在作废之后）', async ({ page, trade }) => {
    await scenarioE()
    await apiExpire('XYZ', EXP, '45', 'PUT', 3)
    await trade.open()
    await page.getByTestId('open-trade-action-btc').check()
    await trade.fill({ symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', contracts: '1', netAmount: '10.00', time: `${EXP}T16:00` })
    await trade.submit.click()
    await expect(trade.error).toHaveText(`该合约（${XYZ}）在成交时没有持仓，无法平仓`)
    await expect(trade.error).toHaveAttribute('data-error-code', 'NO_POSITION_TO_CLOSE')
  })
})

