import type { Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { apiTrade, TradeForm } from '../support/trade'

/**
 * FEAT-038 允许 STO call、不冻结现金 E2E (real clock). Scenario IXQZ is entered through the 录入交易 page.
 * DB reset per test: trade ids #1 #2 #3 #4a #4b = 1–5.
 */

const today = () => nyLocal(new Date()).slice(0, 10)
const days = (from: string, to: string) =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000)

const ixqz = (overrides: Parameters<typeof TradeForm.base>[0]) =>
  TradeForm.base({ symbol: 'IXQZ', type: 'CALL', ...overrides })

async function enterClose(page: Page, form: TradeForm, t: { expiration: string; strike: string; net: string; time: string }) {
  await page.getByTestId('open-trade-action-btc').check()
  await form.fill({ symbol: 'IXQZ', expiration: t.expiration, strike: t.strike, type: 'CALL', contracts: '1', netAmount: t.net, time: t.time })
  await expect(page.getByTestId('open-trade-short-call-hint')).toHaveCount(0)
  await form.submit.click()
  await expect(form.confirmation).toBeVisible()
}

function posRow(page: Page, contract: string) {
  return page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
}

test.describe('FEAT-038 卖出 call 不冻结', () => {
  test('AC1 提示只在 STO + Call 时显示且不阻止提交；#1 保存：+14.34、冻结 —、10,958.99；流水说明', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '10944.65', '2026-11-09T09:00')
    await trade.open()
    const hint = page.getByTestId('open-trade-short-call-hint')
    await expect(hint).toHaveCount(0)
    await trade.actionSto.check()
    await trade.typePut.check()
    await expect(hint).toHaveCount(0)
    await trade.typeCall.check()
    await expect(hint).toHaveText('卖出 call 不冻结现金，请自行确保账户有足够股票覆盖（每张 = multiplier 股）；covered call 覆盖检查（FEAT-039）上线后将自动检查。')
    await trade.actionBto.check()
    await expect(hint).toHaveCount(0)
    await page.getByTestId('open-trade-action-stc').check()
    await expect(hint).toHaveCount(0)
    await page.getByTestId('open-trade-action-btc').check()
    await expect(hint).toHaveCount(0)

    await trade.submitAndExpectSaved(ixqz({ action: 'STO', expiration: '2026-11-20', strike: '7.5', netAmount: '14.34', time: '2026-11-09T13:40' }))
    await expect(trade.confirm('contract')).toHaveText('IXQZ 2026-11-20 7.5 C')
    await expect(trade.confirm('cash-change')).toHaveText('+14.34')
    await expect(trade.confirm('frozen-change')).toHaveText('—')
    const cash = new CashPage(page)
    await cash.expectSummary('10,958.99', '0.00', '10,958.99')
    await page.getByTestId('nav-cash').click()
    await cash.expectRows([
      { type: '期权开仓', amount: '+14.34', frozen: '—', note: 'STO 1 张 IXQZ 2026-11-20 7.5 C', balance: '10,958.99' },
      { type: '入金', amount: '+10,944.65', balance: '10,944.65' },
    ])
  })

  test('AC2 场景 IXQZ 全部经页面录入：15,000/0/15,000；持仓 2 个、未冻结标签、批次；已平仓 −20.67（红）；交易列表、汇总、首页', async ({ page, trade }) => {
    await apiTransfer('DEPOSIT', '10944.65', '2026-11-09T09:00')
    await trade.open()
    await trade.submitAndExpectSaved(ixqz({ action: 'STO', expiration: '2026-11-20', strike: '7.5', netAmount: '14.34', time: '2026-11-09T13:40' }))
    await expect(page.getByTestId('open-trade-short-call-hint')).toHaveCount(0) // form reset after save
    await trade.submitAndExpectSaved(ixqz({ action: 'STO', expiration: '2026-12-18', strike: '9', netAmount: '18.34', time: '2026-11-10T12:43' }))
    await trade.submitAndExpectSaved(ixqz({ action: 'STO', expiration: '2026-12-18', strike: '10', netAmount: '20.34', time: '2026-11-11T10:26' }))
    await enterClose(page, trade, { expiration: '2026-11-20', strike: '7.5', net: '35.01', time: '2026-11-11T12:21' })
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText(`${MINUS}20.67`)
    await expect(page.getByTestId('open-trade-confirmation-frozen-change')).toHaveText('—')
    await trade.submitAndExpectSaved(ixqz({ action: 'STO', expiration: '2026-12-18', strike: '9', netAmount: '37.34', time: '2026-11-11T12:21' }))
    const cash = new CashPage(page)
    await cash.expectSummary('11,000.00', '0.00', '11,000.00')

    // positions
    await page.getByTestId('nav-positions').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 2 个持仓')
    await expect(page.getByTestId('positions-frozen-total')).toHaveText('冻结现金合计 0.00')
    await expect
      .poll(() => page.getByTestId('position-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract'))))
      .toEqual(['IXQZ 2026-12-18 9 C', 'IXQZ 2026-12-18 10 C'])
    const dte = String(days(today(), '2026-12-18'))
    const nine = posRow(page, 'IXQZ 2026-12-18 9 C')
    await expect(nine.getByTestId('position-cell-direction')).toHaveText('空头')
    await expect(nine.getByTestId('position-cell-contracts')).toHaveText('2')
    await expect(nine.getByTestId('position-cell-opening-net')).toHaveText('+55.68')
    await expect(nine.getByTestId('position-cell-average-net')).toHaveText('+27.84')
    await expect(nine.getByTestId('position-cell-frozen').getByTestId('position-cell-frozen-none')).toHaveText('未冻结')
    await expect(nine.getByTestId('position-cell-dte')).toHaveText(dte)
    await expect(nine.getByTestId('position-cell-lot-count')).toHaveText('2')
    await nine.getByTestId('position-cell-frozen-none').hover()
    await expect(page.getByText('卖出 call 不冻结现金，需自行保证股票覆盖')).toBeVisible()
    await nine.getByTestId('position-expand').click()
    const lots = nine.locator('xpath=following-sibling::tr[1]').getByTestId('lot-row')
    await expect(lots).toHaveCount(2)
    expect(
      await lots.evaluateAll((rs) =>
        rs.map((r) =>
          ['lot-cell-time', 'lot-cell-action', 'lot-cell-contracts', 'lot-cell-net-amount', 'lot-cell-frozen']
            .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
            .join(' '),
        ),
      ),
    ).toEqual(['2026-11-10 12:43 STO（卖出开仓） 1 / 1 +18.34 —', '2026-11-11 12:21 STO（卖出开仓） 1 / 1 +37.34 —'])
    const ten = posRow(page, 'IXQZ 2026-12-18 10 C')
    await expect(ten.getByTestId('position-cell-opening-net')).toHaveText('+20.34')
    await expect(ten.getByTestId('position-cell-frozen-none')).toHaveText('未冻结')
    await expect(ten.getByTestId('position-cell-dte')).toHaveText(dte)
    await expect(posRow(page, 'IXQZ 2026-11-20 7.5 C')).toHaveCount(0)
    // the 平仓 button pre-fills BTC like a short put
    await nine.getByTestId('position-close').click()
    await expect(page.getByTestId('close-trade-modal').getByTestId('close-trade-action')).toHaveText('BTC（买入平仓）')
    await page.getByTestId('close-trade-modal').getByTestId('close-trade-cancel').click()

    // closed trades
    await page.getByTestId('nav-closed').click()
    await expect(page.getByTestId('closed-count')).toHaveText('共 1 笔平仓')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText(`${MINUS}20.67`)
    const c = page.getByTestId('closed-row').first()
    await expect(c.getByTestId('closed-cell-time')).toHaveText('2026-11-11 12:21')
    await expect(c.getByTestId('closed-cell-contract')).toHaveText('IXQZ 2026-11-20 7.5 C')
    await expect(c.getByTestId('closed-cell-action')).toContainText('BTC（买入平仓）')
    await expect(c.getByTestId('closed-cell-direction')).toHaveText('空头')
    await expect(c.getByTestId('closed-cell-opening-net')).toHaveText('+14.34')
    await expect(c.getByTestId('closed-cell-close-net')).toHaveText(`${MINUS}35.01`)
    await expect(c.getByTestId('closed-cell-pnl')).toHaveText(`${MINUS}20.67`)
    const colors = await c.getByTestId('closed-cell-pnl').evaluate((e) => [e, ...Array.from(e.querySelectorAll('*'))].map((x) => getComputedStyle(x).color))
    expect(colors.some((s) => {
      const [r, g] = (s.match(/\d+/g) ?? []).map(Number)
      return r! - g! > 40
    })).toBe(true)

    // trade list: 5 trades, #4b (id 5) on top, frozen all —
    await page.getByTestId('nav-trades').click()
    await expect(page.getByTestId('trade-list-total')).toHaveText('共 5 条')
    await expect
      .poll(() => page.getByTestId('trade-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id'))))
      .toEqual(['5', '4', '3', '2', '1'])
    expect(await page.getByTestId('trade-cell-frozen').allTextContents()).toEqual(['—', '—', '—', '—', '—'])

    // pnl summary and home
    await page.getByTestId('nav-pnl-summary').click()
    await expect(page.getByTestId('pnl-total')).toHaveText(`${MINUS}20.67`)
    await expect(page.getByTestId('pnl-symbol-row').first().getByTestId('pnl-symbol-link')).toHaveText('IXQZ')
    await expect(page.getByTestId('pnl-month-row').first().getByTestId('pnl-month-link')).toHaveText('2026-11')
    await page.getByTestId('nav-home').click()
    await cash.expectSummary('11,000.00', '0.00', '11,000.00')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText(`${MINUS}20.67`)
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('1')
    if (today().startsWith('2026-11')) await expect(page.getByTestId('dashboard-month-total')).toHaveText(`${MINUS}20.67`)
    await expect(page.getByTestId('dashboard-position-count')).toHaveText('2')
    await expect(page.getByTestId('dashboard-frozen-total')).toHaveText('0.00')
  })

  test('AC4+AC5 零现金卖 call：25.00；持仓页平仓 BTC 30.00 被拒（无"释放冻结"）、输入保留；20.00 成功 +5.00，5/0/5，持仓清空', async ({ page, trade }) => {
    await trade.open()
    await new CashPage(page).expectSummary('0.00', '0.00', '0.00')
    await trade.submitAndExpectSaved(TradeForm.base({ action: 'STO', symbol: 'XYZ', strike: '50', type: 'CALL', netAmount: '25.00', time: '2026-11-10T10:00' }))
    await new CashPage(page).expectSummary('25.00', '0.00', '25.00')

    await page.getByTestId('nav-positions').click()
    await posRow(page, 'XYZ 2026-12-18 50 C').getByTestId('position-close').click()
    const m = page.getByTestId('close-trade-modal')
    await m.getByTestId('close-trade-net-amount').fill('30.00')
    await m.getByTestId('close-trade-time').fill('2026-11-11T10:00')
    await m.getByTestId('close-trade-submit').click()
    await expect(m.getByTestId('close-trade-error')).toHaveText('可用现金不足：需要 30.00，可用 25.00')
    await expect(m.getByTestId('close-trade-error')).not.toContainText('释放冻结')
    await expect(m.getByTestId('close-trade-net-amount')).toHaveValue('30.00')
    await m.getByTestId('close-trade-net-amount').fill('20.00')
    await m.getByTestId('close-trade-submit').click()
    await expect(m.getByTestId('close-trade-confirmation-realized-pnl')).toHaveText('+5.00')
    await expect(m.getByTestId('close-trade-confirmation-frozen-change')).toHaveText('—')
    await expect(m.getByTestId('cash-summary-total')).toHaveText('5.00')
    await m.getByTestId('close-trade-done').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 0 个持仓')
  })

  test('AC6+AC7 标签只在空头 call：空头 put 显示冻结金额、多头 call 显示 —；冻结合计只来自 put', async ({ page }) => {
    await apiTransfer('DEPOSIT', '25000.00', '2026-11-02T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' })
    await apiTrade({ action: 'STO', strike: '250', type: 'CALL', netAmount: '120.00', time: '2026-11-04T10:00' })
    await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '100.00', time: '2026-11-04T11:00' })
    await page.goto('/positions')
    await expect(page.getByTestId('positions-frozen-total')).toHaveText('冻结现金合计 20,000.00')
    await expect(page.getByTestId('position-cell-frozen-none')).toHaveCount(1)
    await expect(posRow(page, 'AAPL 2026-12-18 250 C').getByTestId('position-cell-frozen-none')).toHaveText('未冻结')
    await expect(posRow(page, 'AAPL 2026-12-18 200 P').getByTestId('position-cell-frozen')).toHaveText('20,000.00')
    await expect(posRow(page, 'AAPL 2026-12-18 210 C').getByTestId('position-cell-frozen')).toHaveText('—')
    await expect(posRow(page, 'AAPL 2026-12-18 210 C').getByTestId('position-cell-direction')).toHaveText('多头')
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('25,369.35', '20,000.00', '5,369.35')
    await page.getByTestId('nav-home').click()
    await expect(page.getByTestId('dashboard-frozen-total')).toHaveText('20,000.00')
  })
})
