import type { Locator, Page } from '@playwright/test'
import { apiLedger, apiTransfer, CashPage, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { apiTrade, TradeForm } from '../support/trade'
import { BACKEND_URL } from '../support/backend'

/**
 * FEAT-004 平仓 E2E: both entries (positions-page 平仓 button → modal, and the 录入交易 page with BTC / STC).
 * Real clock (2026-11-11 at the time of writing); all acceptance times are in the past.
 */

const BACKEND = BACKEND_URL
const today = () => nyLocal(new Date()).slice(0, 10)

async function scenarioA(): Promise<{ l1: number; l2: number }> {
  await apiTransfer('DEPOSIT', '80000.00', '2026-10-26T10:00')
  const l1 = (await apiTrade({ action: 'STO', strike: '200', type: 'PUT', contracts: 2, netAmount: '698.70', time: '2026-11-03T10:30' })).id
  const l2 = (await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-04T11:00' })).id
  return { l1, l2 }
}

async function apiClose(body: Record<string, unknown>): Promise<Record<string, unknown>> {
  const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol: 'AAPL', expirationDate: '2026-12-18', strike: '200', optionType: 'PUT', note: null, ...body }),
  })
  if (r.status !== 201) throw new Error(`close failed ${r.status} ${await r.text()}`)
  return (await r.json()) as Record<string, unknown>
}

async function apiGet<T>(path: string): Promise<T> {
  return (await (await fetch(`${BACKEND}${path}`)).json()) as T
}

/** Positions page + close modal. */
class CloseUi {
  readonly modal: Locator
  constructor(readonly page: Page) {
    this.modal = page.getByTestId('close-trade-modal')
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  async openPositions(): Promise<void> {
    await this.page.goto('/positions')
    await expect(this.page.getByTestId('positions-as-of')).toBeVisible()
  }

  async openModal(contract: string): Promise<void> {
    await this.row(contract).getByTestId('position-close').click()
    await expect(this.modal.getByRole('dialog')).toBeVisible()
  }

  m(id: string): Locator {
    return this.modal.getByTestId(id)
  }

  fieldError(testId: string): Locator {
    return this.modal
      .locator('.ant-form-item')
      .filter({ has: this.page.getByTestId(testId) })
      .locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
  }

  async submit(t: { contracts?: number; net?: string; time?: string; note?: string }): Promise<void> {
    if (t.contracts !== undefined) await this.m(`close-trade-contracts-${t.contracts}`).check()
    if (t.net !== undefined) await this.m('close-trade-net-amount').fill(t.net)
    if (t.time !== undefined) await this.m('close-trade-time').fill(t.time)
    if (t.note !== undefined) await this.m('close-trade-note').fill(t.note)
    await this.m('close-trade-submit').click()
  }

  async coveredLots(scope: Locator): Promise<string[]> {
    return scope.getByTestId('covered-lot-row').evaluateAll((rs) =>
      rs.map((r) =>
        ['covered-lot-time', 'covered-lot-action', 'covered-lot-contracts', 'covered-lot-net-amount']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async positionCells(contract: string): Promise<Record<string, string>> {
    return this.row(contract).evaluate((r) => {
      const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
      return {
        direction: t('position-cell-direction'),
        contracts: t('position-cell-contracts'),
        openingNet: t('position-cell-opening-net'),
        average: t('position-cell-average-net'),
        frozen: t('position-cell-frozen'),
        lots: t('position-cell-lot-count'),
      }
    })
  }
}

/** Entry page helpers for BTC / STC. */
async function fillClose(form: TradeForm, action: 'BTC' | 'STC', t: { symbol?: string; strike?: string; type?: 'CALL' | 'PUT'; expiration?: string; contracts: string; net: string; time: string; note?: string }) {
  await form.page.getByTestId(`open-trade-action-${action.toLowerCase()}`).check()
  await form.fill({
    symbol: t.symbol ?? 'AAPL',
    expiration: t.expiration ?? '2026-12-18',
    strike: t.strike ?? '200',
    type: t.type ?? 'PUT',
    contracts: t.contracts,
    netAmount: t.net,
    time: t.time,
    note: t.note,
  })
}

test.describe('FEAT-004 平仓', () => {
  test('AC9+AC1 持仓页平仓：预填、可平 2/3 默认 2；提交后确认含批次与盈亏，持仓/现金/流水/交易列表自动更新', async ({ page }) => {
    const { l1 } = await scenarioA()
    const ui = new CloseUi(page)
    await ui.openPositions()
    await expect(ui.row('AAPL 2026-12-18 200 P').getByTestId('position-close')).toBeVisible()
    const before = nyLocal(new Date())
    await ui.openModal('AAPL 2026-12-18 200 P')
    const after = nyLocal(new Date())
    await expect(ui.m('close-trade-contract')).toHaveText('AAPL 2026-12-18 200 P')
    await expect(ui.m('close-trade-action')).toHaveText('BTC（买入平仓）')
    await expect(ui.m('close-trade-multiplier')).toHaveText('100')
    await expect(ui.modal.locator('[data-testid^="close-trade-contracts-"]')).toHaveCount(2)
    await expect(ui.m('close-trade-contracts-2')).toBeChecked()
    await expect(ui.m('close-trade-contracts-3')).not.toBeChecked()
    await expect(ui.m('close-trade-net-amount-label')).toHaveText('实付总额（含手续费）')
    expect([before, after]).toContain(await ui.m('close-trade-time').inputValue())
    await expect(ui.modal.getByText('美东时间').first()).toBeVisible()
    await expect(ui.m('cash-summary-total')).toHaveText('81,058.70')
    // contract / action / multiplier are display-only
    await expect(ui.modal.locator('input[value="AAPL"]')).toHaveCount(0)

    await ui.submit({ net: '241.30', time: '2026-11-05T10:00' })
    const c = (s: string) => ui.m(`close-trade-confirmation-${s}`)
    await expect(ui.m('close-trade-confirmation')).toBeVisible()
    await expect(c('contract')).toHaveText('AAPL 2026-12-18 200 P')
    await expect(c('action')).toHaveText('BTC（买入平仓）')
    await expect(c('contracts')).toHaveText('2 张')
    await expect(c('net-amount')).toHaveText('241.30')
    await expect(c('time')).toHaveText('2026-11-05 10:00（美东）')
    await expect(c('cash-change')).toHaveText(`${MINUS}241.30`)
    await expect(c('frozen-change')).toHaveText(`${MINUS}40,000.00`)
    await expect(c('realized-pnl')).toHaveText('+457.40')
    expect(await ui.coveredLots(c('covered-lots'))).toEqual(['2026-11-03 10:30 STO（卖出开仓） 2 张 +698.70'])
    await expect(c('covered-lots').getByTestId('covered-lot-row')).toHaveAttribute('data-trade-id', String(l1))
    await expect(ui.m('cash-summary-total')).toHaveText('80,817.40')
    await expect(ui.m('cash-summary-frozen')).toHaveText('20,000.00')
    await expect(ui.m('cash-summary-available')).toHaveText('60,817.40')

    await ui.m('close-trade-done').click()
    await expect(ui.modal.getByRole('dialog')).toBeHidden()
    expect(await ui.positionCells('AAPL 2026-12-18 200 P')).toEqual({
      direction: '空头', contracts: '1', openingNet: '+360.00', average: '+360.00', frozen: '20,000.00', lots: '1',
    })
    await expect(page.getByTestId('positions-frozen-total')).toHaveText('冻结现金合计 20,000.00')
    await ui.openModal('AAPL 2026-12-18 200 P')
    await expect(ui.modal.locator('[data-testid^="close-trade-contracts-"]')).toHaveCount(1)
    await expect(ui.m('close-trade-contracts-1')).toBeChecked()
    await ui.m('close-trade-cancel').click()

    // cash page ledger
    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('80,817.40', '20,000.00', '60,817.40')
    await cash.expectRows([
      { time: '2026-11-05 10:00', type: '期权平仓', amount: `${MINUS}241.30`, frozen: `${MINUS}40,000.00`, note: 'BTC 2 张 AAPL 2026-12-18 200 P', balance: '80,817.40' },
      { type: '期权开仓', amount: '+360.00', balance: '81,058.70' },
      { type: '期权开仓', amount: '+698.70', balance: '80,698.70' },
      { type: '入金', amount: '+80,000.00', balance: '80,000.00' },
    ])

    // trade list
    await page.getByTestId('nav-trades').click()
    const first = page.getByTestId('trade-row').first()
    await expect(first.getByTestId('trade-cell-time')).toHaveText('2026-11-05 10:00')
    await expect(first.getByTestId('trade-cell-action')).toHaveText('BTC（买入平仓）')
    await expect(first.getByTestId('trade-cell-contract')).toHaveText('AAPL 2026-12-18 200 P')
    await expect(first.getByTestId('trade-cell-contracts')).toHaveText('2')
    await expect(first.getByTestId('trade-cell-net-amount')).toHaveText(`${MINUS}241.30`)
    await expect(first.getByTestId('trade-cell-frozen')).toHaveText(`${MINUS}40,000.00`)
    await page.getByTestId('trade-filter-action-btc').check()
    await page.getByTestId('trade-filter-submit').click()
    await expect(page.getByTestId('trade-list-total')).toHaveText('共 1 条')
    await expect(page.getByTestId('trade-row')).toHaveCount(1)
  })

  test('AC2 录入交易页 BTC 1 张：整笔批次提示原文、输入保留、multiplier 隐藏、什么都不变；改 2 张成功', async ({ page, trade }) => {
    await scenarioA()
    await trade.open()
    await fillClose(trade, 'BTC', { contracts: '1', net: '120.65', time: '2026-11-05T10:00', note: '保留' })
    await expect(trade.multiplier).toBeHidden()
    await expect(trade.netAmountLabel).toHaveText('实付总额（含手续费）')
    await trade.submit.click()
    await expect(trade.error).toHaveText('必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张')
    await expect(trade.error).toHaveAttribute('data-error-code', 'CLOSE_NOT_WHOLE_LOTS')
    await expect(page.getByTestId('open-trade-action-btc')).toBeChecked()
    await expect(trade.symbol).toHaveValue('AAPL')
    await expect(trade.expiration).toHaveValue('2026-12-18')
    await expect(trade.strike).toHaveValue('200')
    await expect(trade.typePut).toBeChecked()
    await expect(trade.contracts).toHaveValue('1')
    await expect(trade.netAmount).toHaveValue('120.65')
    await expect(trade.time).toHaveValue('2026-11-05T10:00')
    await expect(trade.note).toHaveValue('保留')
    await new CashPage(page).expectSummary('81,058.70', '60,000.00', '21,058.70')
    expect((await apiLedger()).totalItems).toBe(3)

    await trade.contracts.fill('2')
    await trade.netAmount.fill('241.30')
    await trade.submit.click()
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText('+457.40')
    await expect(page.getByTestId('open-trade-confirmation-frozen-change')).toHaveText(`${MINUS}40,000.00`)
    await new CashPage(page).expectSummary('80,817.40', '20,000.00', '60,817.40')
  })

  test('AC3 平 3 张覆盖两批：冻结 −60,000.00、两行批次、盈亏 +658.70、持仓清空', async ({ page }) => {
    await scenarioA()
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 200 P')
    await ui.submit({ contracts: 3, net: '400.00', time: '2026-11-05T10:00' })
    const c = (s: string) => ui.m(`close-trade-confirmation-${s}`)
    await expect(c('frozen-change')).toHaveText(`${MINUS}60,000.00`)
    await expect(c('realized-pnl')).toHaveText('+658.70')
    expect(await ui.coveredLots(c('covered-lots'))).toEqual([
      '2026-11-03 10:30 STO（卖出开仓） 2 张 +698.70',
      '2026-11-04 11:00 STO（卖出开仓） 1 张 +360.00',
    ])
    await expect(ui.m('cash-summary-total')).toHaveText('80,658.70')
    await ui.m('close-trade-done').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 0 个持仓')
    await expect(page.getByTestId('positions-frozen-total')).toHaveText('冻结现金合计 0.00')
    await expect(page.getByTestId('position-row')).toHaveCount(0)
  })

  test('AC4 录入交易页：超过持仓 / 方向不对 / 没有持仓 / 早于开仓，均原文提示且不变', async ({ page, trade }) => {
    await scenarioA()
    await trade.open()
    const cases: [Parameters<typeof fillClose>[1], Parameters<typeof fillClose>[2], string, string][] = [
      ['BTC', { contracts: '4', net: '1.00', time: '2026-11-05T10:00' }, 'CLOSE_EXCEEDS_POSITION', '平仓 4 张超过当时持仓 3 张'],
      ['STC', { contracts: '2', net: '1.00', time: '2026-11-05T10:00' }, 'POSITION_DIRECTION_CONFLICT', '该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（3 张），应使用 Buy to Close（BTC）'],
      ['BTC', { strike: '205', contracts: '1', net: '1.00', time: '2026-11-05T10:00' }, 'NO_POSITION_TO_CLOSE', '该合约（AAPL 2026-12-18 205 P）在成交时没有持仓，无法平仓'],
      ['BTC', { contracts: '2', net: '1.00', time: '2026-11-03T10:00' }, 'NO_POSITION_TO_CLOSE', '该合约（AAPL 2026-12-18 200 P）在成交时没有持仓，无法平仓'],
    ]
    for (const [action, input, code, msg] of cases) {
      await fillClose(trade, action, input)
      await trade.submit.click()
      await expect(trade.error).toHaveText(msg)
      await expect(trade.error).toHaveAttribute('data-error-code', code)
    }
    await new CashPage(page).expectSummary('81,058.70', '60,000.00', '21,058.70')
    expect((await apiLedger()).totalItems).toBe(3)
  })

  test('AC5 全部平掉后反方向开仓：平 L2 盈亏 +210.00；BTO 1 张后持仓为多头 −300.00', async ({ page, trade }) => {
    await scenarioA()
    await apiClose({ action: 'BTC', contracts: 2, netAmount: '241.30', tradedAtLocal: '2026-11-05T10:00' })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 200 P')
    await ui.submit({ net: '150.00', time: '2026-11-06T10:00' })
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+210.00')
    await expect(ui.m('cash-summary-total')).toHaveText('80,667.40')
    await ui.m('close-trade-done').click()
    await expect(ui.row('AAPL 2026-12-18 200 P')).toHaveCount(0)

    await page.getByTestId('nav-open-trade').click()
    await trade.submitAndExpectSaved(TradeForm.base({ action: 'BTO', strike: '200', type: 'PUT', netAmount: '300.00', time: '2026-11-06T11:00' }))
    await new CashPage(page).expectSummary('80,367.40', '0.00', '80,367.40')
    await page.getByTestId('nav-positions').click()
    expect(await ui.positionCells('AAPL 2026-12-18 200 P')).toMatchObject({ direction: '多头', contracts: '1', openingNet: `${MINUS}300.00` })
  })

  test('AC6 先释放冻结：20,349.36 被拒（原文含释放冻结）且输入保留；20,349.35 成功 0/0/0、盈亏 −20,000.00', async ({ page }) => {
    await apiTransfer('DEPOSIT', '20000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 200 P')
    await ui.submit({ net: '20349.36', time: '2026-11-05T10:00', note: '留着' })
    await expect(ui.m('close-trade-error')).toHaveText('可用现金不足：需要 20,349.36，可用 349.35 + 释放冻结 20,000.00 = 20,349.35')
    await expect(ui.m('close-trade-error')).toHaveAttribute('data-error-code', 'CASH_INSUFFICIENT')
    await expect(ui.m('close-trade-net-amount')).toHaveValue('20349.36')
    await expect(ui.m('close-trade-time')).toHaveValue('2026-11-05T10:00')
    await expect(ui.m('close-trade-note')).toHaveValue('留着')
    await expect(ui.m('cash-summary-total')).toHaveText('20,349.35')
    await ui.submit({ net: '20349.35' })
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText(`${MINUS}20,000.00`)
    await expect(ui.m('cash-summary-total')).toHaveText('0.00')
    await expect(ui.m('cash-summary-frozen')).toHaveText('0.00')
    await expect(ui.m('cash-summary-available')).toHaveText('0.00')
  })

  test('AC6 BTC 500.00 成功：19,849.35 / 0.00 / 19,849.35，盈亏 −150.65', async ({ page }) => {
    await apiTransfer('DEPOSIT', '20000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 200 P')
    await ui.submit({ net: '500.00', time: '2026-11-05T10:00' })
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText(`${MINUS}150.65`)
    await expect(ui.m('cash-summary-total')).toHaveText('19,849.35')
    await expect(ui.m('cash-summary-available')).toHaveText('19,849.35')
  })

  test('AC7 多头 call STC：标签实收净额、+830.00、冻结 —、盈亏 +317.35、流水说明', async ({ page }) => {
    await apiTransfer('DEPOSIT', '10000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65', time: '2026-11-03T10:30' })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 210 C')
    await expect(ui.m('close-trade-action')).toHaveText('STC（卖出平仓）')
    await expect(ui.m('close-trade-net-amount-label')).toHaveText('实收净额（已扣手续费）')
    await ui.submit({ net: '830.00', time: '2026-11-05T10:00' })
    await expect(ui.m('close-trade-confirmation-cash-change')).toHaveText('+830.00')
    await expect(ui.m('close-trade-confirmation-frozen-change')).toHaveText('—')
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+317.35')
    expect(await ui.coveredLots(ui.m('close-trade-confirmation-covered-lots'))).toEqual([`2026-11-03 10:30 BTO（买入开仓） 1 张 ${MINUS}512.65`])
    await ui.m('close-trade-done').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 0 个持仓')
    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('10,317.35', '0.00', '10,317.35')
    await cash.expectRows([
      { type: '期权平仓', amount: '+830.00', frozen: '—', note: 'STC 1 张 AAPL 2026-12-18 210 C', balance: '10,317.35' },
      { type: '期权开仓', amount: `${MINUS}512.65`, balance: '9,487.35' },
      { type: '入金', amount: '+10,000.00', balance: '10,000.00' },
    ])
  })

  test('AC8 已过期：无平仓按钮；录入交易页 11-09 平仓错误在成交时间下；到期日 16:30 成功 +290.00', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '50000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', symbol: 'TSLA', expiration: '2026-11-06', strike: '250', type: 'PUT', netAmount: '300.00', time: '2026-11-02T10:00' })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await expect(ui.row('TSLA 2026-11-06 250 P').getByTestId('position-cell-dte')).toContainText('已过期')
    await expect(ui.row('TSLA 2026-11-06 250 P').getByTestId('position-close')).toHaveCount(0)

    await page.getByTestId('nav-open-trade').click()
    await fillClose(trade, 'BTC', { symbol: 'TSLA', expiration: '2026-11-06', strike: '250', contracts: '1', net: '10.00', time: '2026-11-09T10:00' })
    await trade.submit.click()
    await expect(trade.fieldError(trade.time)).toHaveText('到期日（2026-11-06）之后不能平仓')
    await expect(trade.time).toHaveValue('2026-11-09T10:00')
    await new CashPage(page).expectSummary('50,300.00', '25,000.00', '25,300.00')

    await trade.time.fill('2026-11-06T16:30')
    await trade.submit.click()
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText('+290.00')
    await new CashPage(page).expectSummary('50,290.00', '0.00', '50,290.00')
    await page.getByTestId('nav-positions').click()
    await expect(ui.row('TSLA 2026-11-06 250 P')).toHaveCount(0)
  })

  test('AC9 今天到期的多头：显示平仓按钮，方向预填 STC（卖出平仓）', async ({ page }) => {
    const d = today()
    await apiTransfer('DEPOSIT', '1000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'BTO', symbol: 'SPY', expiration: d, strike: '600', type: 'CALL', netAmount: '50.00', time: '2026-11-02T10:00' })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await expect(ui.row(`SPY ${d} 600 C`).getByTestId('position-cell-dte-today')).toBeVisible()
    await ui.openModal(`SPY ${d} 600 C`)
    await expect(ui.m('close-trade-action')).toHaveText('STC（卖出平仓）')
    await expect(ui.m('close-trade-contracts-1')).toBeChecked()
  })

  test('AC10 补录平仓：使之后平仓无持仓 → 拒绝并指出时间；BTC 1 张成功，之后那笔改平 L2（+260.00）', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '80000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' })
    const l2 = (await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-04T11:00' })).id
    const later = await apiClose({ action: 'BTC', contracts: 1, netAmount: '100.00', tradedAtLocal: '2026-11-06T10:00' })
    await trade.open()
    await new CashPage(page).expectSummary('80,609.35', '20,000.00', '60,609.35')
    await fillClose(trade, 'BTC', { contracts: '2', net: '200.00', time: '2026-11-05T10:00' })
    await trade.submit.click()
    await expect(trade.error).toHaveText('补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-06 10:00 的 BTC 1 张将没有持仓可平')
    await expect(trade.error).toHaveAttribute('data-error-code', 'NO_POSITION_TO_CLOSE')

    await trade.contracts.fill('1')
    await trade.netAmount.fill('120.00')
    await trade.submit.click()
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText('+229.35')
    await new CashPage(page).expectSummary('80,489.35', '0.00', '80,489.35')
    const re = await apiGet<{ realizedPnl: string; coveredLots: { tradeId: number }[] }>(`/api/option-trades/${String(later.id)}`)
    expect(re.realizedPnl).toBe('260.00')
    expect(re.coveredLots.map((l) => l.tradeId)).toEqual([l2])
    await page.getByTestId('nav-positions').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 0 个持仓')
  })

  test('AC11 平仓对话框字段校验：净额 0 / −10.00 / 241.305、时间晚于当前、备注 501 → 字段错误、不提交', async ({ page }) => {
    await scenarioA()
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 200 P')
    const posts: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/api/option-trades')) posts.push(r.url())
    })
    const net = 'close-trade-net-amount'
    const time = 'close-trade-time'
    const note = 'close-trade-note'
    for (const v of ['0', '-10.00', '241.305']) {
      await ui.submit({ net: v, time: '2026-11-05T10:00' })
      await expect(ui.fieldError(net)).not.toHaveText('')
    }
    await expect(ui.fieldError(net)).toHaveText('净额最多 2 位小数')
    await ui.submit({ net: '241.30', time: nyLocal(new Date(Date.now() + 3600_000)) })
    await expect(ui.fieldError(time)).toHaveText('成交时间不能晚于当前时间')
    await ui.submit({ time: '2026-11-05T10:00', note: 'x'.repeat(501) })
    await expect(ui.fieldError(note)).toHaveText('备注最多 500 个字符')
    await page.waitForTimeout(300)
    expect(posts).toHaveLength(0)
    await ui.m('close-trade-cancel').click()
    await expect(ui.modal.getByRole('dialog')).toBeHidden()
    expect((await apiLedger()).totalItems).toBe(3)
  })

  test('AC11 录入交易页 BTC 张数 0 / −1 / 1.5 → 字段错误、不提交', async ({ page, trade }) => {
    await scenarioA()
    await trade.open()
    const posts: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/api/option-trades')) posts.push(r.url())
    })
    for (const v of ['0', '-1', '1.5']) {
      await fillClose(trade, 'BTC', { contracts: v, net: '1.00', time: '2026-11-05T10:00' })
      await trade.submit.click()
      await expect(trade.fieldError(trade.contracts)).not.toHaveText('')
    }
    await page.waitForTimeout(300)
    expect(posts).toHaveLength(0)
  })

  test('AC11 multiplier 10：对话框显示 10；冻结 −2,000.00、盈亏 +24.35、流水说明带 multiplier', async ({ page }) => {
    await apiTransfer('DEPOSIT', '5000.00', '2026-10-26T10:00')
    await apiTrade({ action: 'STO', symbol: 'XYZ', strike: '200', type: 'PUT', netAmount: '34.35', time: '2026-11-03T10:30', multiplier: 10 })
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('XYZ 2026-12-18 200 P')
    await expect(ui.m('close-trade-multiplier')).toHaveText('10')
    await ui.submit({ net: '10.00', time: '2026-11-05T10:00' })
    await expect(ui.m('close-trade-confirmation-frozen-change')).toHaveText(`${MINUS}2,000.00`)
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+24.35')
    await expect(ui.m('close-trade-confirmation-multiplier')).toContainText('multiplier 10')
    await ui.m('close-trade-done').click()
    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('5,024.35', '0.00', '5,024.35')
    await cash.expectRows([
      { type: '期权平仓', amount: `${MINUS}10.00`, frozen: `${MINUS}2,000.00`, note: 'BTC 1 张 XYZ 2026-12-18 200 P（multiplier 10）', balance: '5,024.35' },
      { type: '期权开仓', amount: '+34.35', balance: '5,034.35' },
      { type: '入金', amount: '+5,000.00', balance: '5,000.00' },
    ])
  })

  test('录入交易页：四个方向、导航文字"录入交易"、BTO/STO 显示 multiplier、BTC/STC 隐藏并切换净额标签', async ({ page, trade }) => {
    await trade.open()
    await expect(page.getByTestId('nav-open-trade')).toHaveText('录入交易')
    for (const a of ['sto', 'bto', 'btc', 'stc']) await expect(page.getByTestId(`open-trade-action-${a}`)).toHaveCount(1)
    const label = trade.netAmountLabel
    await trade.actionSto.check()
    await expect(trade.multiplier).toBeVisible()
    await expect(label).toHaveText('实收净额（已扣手续费）')
    await page.getByTestId('open-trade-action-btc').check()
    await expect(trade.multiplier).toBeHidden()
    await expect(label).toHaveText('实付总额（含手续费）')
    await page.getByTestId('open-trade-action-stc').check()
    await expect(trade.multiplier).toBeHidden()
    await expect(label).toHaveText('实收净额（已扣手续费）')
    await trade.actionBto.check()
    await expect(trade.multiplier).toBeVisible()
    await expect(label).toHaveText('实付总额（含手续费）')
  })

  test('取消对话框不提交、不改变数据', async ({ page }) => {
    await scenarioA()
    const ui = new CloseUi(page)
    await ui.openPositions()
    await ui.openModal('AAPL 2026-12-18 200 P')
    await ui.m('close-trade-net-amount').fill('241.30')
    await ui.m('close-trade-cancel').click()
    await expect(ui.modal.getByRole('dialog')).toBeHidden()
    expect((await apiLedger()).totalItems).toBe(3)
    expect(await ui.positionCells('AAPL 2026-12-18 200 P')).toMatchObject({ contracts: '3', lots: '2' })
  })
})
