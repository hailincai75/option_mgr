import type { Locator, Page } from '@playwright/test'
import { apiLedger, apiTransfer, CashPage, expect, MINUS, nyLocal, test } from '../support/fixtures'
import { apiTrade } from '../support/trade'
import { BACKEND_URL } from '../support/backend'

/**
 * FEAT-005 修改 / 删除 / 审计 E2E. The database is reset before each test, so ids restart at 1:
 * scenario A = deposit #1, L1 = trade #1, L2 = trade #2, C1 = trade #3.
 */

const BACKEND = BACKEND_URL

async function apiClose(body: Record<string, unknown>): Promise<number> {
  const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol: 'AAPL', expirationDate: '2026-12-18', strike: '200', optionType: 'PUT', ...body }),
  })
  if (r.status !== 201) throw new Error(`close failed ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function scenarioA(): Promise<void> {
  await apiTransfer('DEPOSIT', '80000.00', '2026-10-26T10:00')
  await apiTrade({ action: 'STO', strike: '200', type: 'PUT', contracts: 2, netAmount: '698.70', time: '2026-11-03T10:30' })
  await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '360.00', time: '2026-11-04T11:00' })
  await apiClose({ action: 'BTC', contracts: 2, netAmount: '241.30', tradedAtLocal: '2026-11-05T10:00' })
}

async function auditCount(): Promise<number> {
  return ((await (await fetch(`${BACKEND}/api/audit-log`)).json()) as { totalItems: number }).totalItems
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

  tradeRow(id: number): Locator {
    return this.page.locator(`[data-testid="trade-row"][data-trade-id="${id}"]`)
  }

  fieldError(scope: Locator, testId: string): Locator {
    return scope
      .locator('.ant-form-item')
      .filter({ has: this.page.getByTestId(testId) })
      .locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
  }

  async openTrades(): Promise<void> {
    await this.page.goto('/trades')
    await expect(this.id('trade-row').first()).toBeVisible()
  }

  async openEdit(tradeId: number): Promise<Locator> {
    await this.tradeRow(tradeId).getByTestId('trade-edit').click()
    const d = this.dialog('trade-edit-modal')
    await expect(d).toBeVisible()
    return d
  }

  async openDelete(tradeId: number): Promise<Locator> {
    await this.tradeRow(tradeId).getByTestId('trade-delete').click()
    const d = this.dialog('trade-delete-modal')
    await expect(d).toBeVisible()
    return d
  }

  ledgerRowByAmount(amount: string): Locator {
    return this.page.getByTestId('cash-ledger-row').filter({ has: this.page.getByTestId('cash-ledger-amount').getByText(amount, { exact: true }) })
  }

  async auditRows(): Promise<{ op: string; object: string; summary: string; changes: string[]; side: string[] }[]> {
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
          side: lines('audit-cell-side-effects-line'),
        }
      }),
    )
  }
}

test.describe('FEAT-005 修改 / 删除 / 审计', () => {
  test('AC1+AC13 交易列表修改 L2 净额：预填与只读字段；不刷新页面，列表/持仓/汇总/流水/审计都更新', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    await ui.openTrades()
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })
    const d = await ui.openEdit(2)
    await expect(d.getByTestId('trade-edit-contract')).toHaveText('AAPL 2026-12-18 200 P')
    await expect(d.getByTestId('trade-edit-action')).toHaveText('STO（卖出开仓）')
    await expect(d.getByText('合约或方向录错请删除后重新录入').first()).toBeVisible()
    await expect(d.getByTestId('trade-edit-contracts')).toHaveValue('1')
    await expect(d.getByTestId('trade-edit-net-amount')).toHaveValue('360.00')
    await expect(d.getByTestId('trade-edit-net-amount-label')).toHaveText('实收净额（已扣手续费）')
    await expect(d.getByTestId('trade-edit-time')).toHaveValue('2026-11-04T11:00')
    await expect(d.getByTestId('trade-edit-multiplier')).toHaveValue('100')
    await expect(d.getByTestId('trade-edit-note')).toHaveValue('')
    await expect(d.getByTestId('trade-edit-multiplier-readonly')).toHaveCount(0)
    // contract / direction are not inputs
    await expect(d.locator('input[value="AAPL"]')).toHaveCount(0)
    await d.getByTestId('trade-edit-net-amount').fill('365.00')
    await d.getByTestId('trade-edit-submit').click()
    await expect(ui.dialog('trade-edit-modal')).toBeHidden()
    await expect(page.locator('.ant-message').getByText('交易已修改')).toBeVisible()
    await expect(ui.tradeRow(2).getByTestId('trade-cell-net-amount')).toHaveText('+365.00')

    await page.getByTestId('nav-positions').click()
    const pos = page.locator('[data-testid="position-row"][data-contract="AAPL 2026-12-18 200 P"]')
    await expect(pos.getByTestId('position-cell-opening-net')).toHaveText('+365.00')
    await expect(pos.getByTestId('position-cell-average-net')).toHaveText('+365.00')

    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('80,822.40', '20,000.00', '60,822.40')
    await expect(cash.ledgerRows).toHaveCount(4)
    await cash.expectRows([
      { type: '期权平仓', amount: `${MINUS}241.30`, balance: '80,822.40' },
      { type: '期权开仓', amount: '+365.00', balance: '81,063.70' },
      { type: '期权开仓', amount: '+698.70', balance: '80,698.70' },
      { type: '入金', amount: '+80,000.00', balance: '80,000.00' },
    ])

    await page.getByTestId('nav-audit').click()
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    const [first] = await ui.auditRows()
    expect(first?.op).toBe('修改')
    expect(first?.object).toBe('期权交易 #2')
    expect(first?.summary).toContain('AAPL 2026-12-18 200 P')
    expect(first?.summary).toContain('STO')
    expect(first?.changes).toEqual(['净额：360.00 → 365.00'])
    await page.getByTestId('audit-row').first().getByTestId('audit-expand').click()
    await expect(page.getByTestId('audit-before-netAmount').first()).toContainText('360.00')
    await expect(page.getByTestId('audit-after-netAmount').first()).toContainText('365.00')
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
  })

  test('AC3 修改平仓：显示只读 multiplier；张数 1 被拒（原文、输入保留、不写审计）；改 3 张成功、持仓清空', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    await ui.openTrades()
    const d = await ui.openEdit(3)
    await expect(d.getByTestId('trade-edit-action')).toHaveText('BTC（买入平仓）')
    await expect(d.getByTestId('trade-edit-multiplier-readonly')).toHaveText('100')
    await expect(d.getByTestId('trade-edit-multiplier')).toHaveCount(0)
    await expect(d.getByTestId('trade-edit-net-amount-label')).toHaveText('实付总额（含手续费）')
    await d.getByTestId('trade-edit-contracts').fill('1')
    await d.getByTestId('trade-edit-note').fill('试试')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d.getByTestId('trade-edit-error')).toHaveText('必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张')
    await expect(d.getByTestId('trade-edit-error')).toHaveAttribute('data-error-code', 'CLOSE_NOT_WHOLE_LOTS')
    await expect(d.getByTestId('trade-edit-contracts')).toHaveValue('1')
    await expect(d.getByTestId('trade-edit-note')).toHaveValue('试试')
    expect(await auditCount()).toBe(0)
    await d.getByTestId('trade-edit-contracts').fill('3')
    await d.getByTestId('trade-edit-note').fill('')
    await d.getByTestId('trade-edit-submit').click()
    await expect(ui.dialog('trade-edit-modal')).toBeHidden()
    await expect(ui.tradeRow(3).getByTestId('trade-cell-frozen')).toHaveText(`${MINUS}60,000.00`)
    await page.getByTestId('nav-positions').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 0 个持仓')
  })

  test('AC4+AC5 修改开仓张数/时间被拒原文；未来时间为字段错误', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    await ui.openTrades()
    const d = await ui.openEdit(1)
    await d.getByTestId('trade-edit-contracts').fill('3')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d.getByTestId('trade-edit-error')).toHaveText(
      '修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将不是整笔开仓（最早一笔是 3 张，可平 3 张或 4 张）',
    )
    await d.getByTestId('trade-edit-contracts').fill('2')
    await d.getByTestId('trade-edit-time').fill('2026-11-06T10:00')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d.getByTestId('trade-edit-error')).toHaveText('修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将超过当时持仓 1 张')
    await d.getByTestId('trade-edit-time').fill(nyLocal(new Date(Date.now() + 3600_000)))
    await d.getByTestId('trade-edit-submit').click()
    await expect(ui.fieldError(d, 'trade-edit-time')).toHaveText('成交时间不能晚于当前时间')
    await d.getByTestId('trade-edit-cancel').click()

    // AC4 success: L1 2 → 1
    const d2 = await ui.openEdit(1)
    await d2.getByTestId('trade-edit-contracts').fill('1')
    await d2.getByTestId('trade-edit-submit').click()
    await expect(ui.dialog('trade-edit-modal')).toBeHidden()
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('80,817.40', '0.00', '80,817.40')
  })

  test('AC6 删除：L1 被拒原文且对话框保留；L2 删除成功，列表/持仓/流水/审计更新', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    await ui.openTrades()
    const d = await ui.openDelete(1)
    await expect(d.getByTestId('trade-delete-summary')).toHaveText('STO 2 张 AAPL 2026-12-18 200 P，2026-11-03 10:30，+698.70')
    await expect(d.getByText('删除后不可恢复，会写入审计记录').first()).toBeVisible()
    await d.getByTestId('trade-delete-confirm').click()
    await expect(d.getByTestId('trade-delete-error')).toHaveText(
      '该开仓被 2026-11-05 10:00 的 BTC 2 张平掉（删除后那笔平仓将超过当时持仓 1 张），请先修改或删除那笔平仓',
    )
    await expect(d).toBeVisible()
    await d.getByTestId('trade-delete-cancel').click()
    await expect(ui.dialog('trade-delete-modal')).toBeHidden()
    await expect(ui.tradeRow(1)).toBeVisible()

    const d2 = await ui.openDelete(2)
    await expect(d2.getByTestId('trade-delete-summary')).toHaveText('STO 1 张 AAPL 2026-12-18 200 P，2026-11-04 11:00，+360.00')
    await d2.getByTestId('trade-delete-confirm').click()
    await expect(ui.dialog('trade-delete-modal')).toBeHidden()
    await expect
      .poll(() => page.getByTestId('trade-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-trade-id'))))
      .toEqual(['3', '1'])
    await page.getByTestId('nav-positions').click()
    await expect(page.getByTestId('positions-count')).toHaveText('共 0 个持仓')
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('80,457.40', '0.00', '80,457.40')
    await expect(page.getByTestId('cash-ledger-row')).toHaveCount(3)
    await page.getByTestId('nav-audit').click()
    const [first] = await ui.auditRows()
    expect(first).toMatchObject({ op: '删除', object: '期权交易 #2', changes: ['已删除'] })
    await page.getByTestId('audit-row').first().getByTestId('audit-expand').click()
    const detail = page.getByTestId('audit-detail').first()
    await expect(detail.getByTestId('audit-before-contractLabel')).toContainText('AAPL 2026-12-18 200 P')
    await expect(detail.getByTestId('audit-before-netAmount')).toContainText('360.00')
    await expect(detail.getByTestId('audit-before-cashChange')).toContainText('+360.00')
    await expect(detail.getByTestId('audit-before-frozenChange')).toContainText('+20,000.00')
    await expect(detail.getByText('已删除').first()).toBeVisible()
  })

  test('AC7 有后续出金时删除平仓：原文"删除后 … −28,941.30"', async ({ page }) => {
    await scenarioA()
    await apiTransfer('WITHDRAWAL', '50000.00', '2026-11-06T10:00')
    const ui = new Ui(page)
    await ui.openTrades()
    const d = await ui.openDelete(3)
    await d.getByTestId('trade-delete-confirm').click()
    await expect(d.getByTestId('trade-delete-error')).toHaveText(`可用现金不足：删除后 2026-11-06 10:00 时可用现金将变为 ${MINUS}28,941.30`)
    await expect(d.getByTestId('trade-delete-error')).toHaveAttribute('data-error-code', 'CASH_INSUFFICIENT')
  })

  test('AC8 资金页修改入金：只有入金出金行有操作；预填；70,000 成功余额重算；58,900 被拒原文且保留输入', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    const cash = new CashPage(page)
    await cash.open()
    await expect(cash.ledgerRows).toHaveCount(4)
    await expect(page.getByTestId('cash-ledger-edit')).toHaveCount(1)
    await expect(page.getByTestId('cash-ledger-delete')).toHaveCount(1)
    await expect(page.getByTestId('cash-ledger-trades-link')).toHaveCount(3)
    const depRow = cash.ledgerRows.nth(3)
    await depRow.getByTestId('cash-ledger-edit').click()
    const d = ui.dialog('transfer-edit-modal')
    await expect(d).toBeVisible()
    await expect(d.getByTestId('transfer-edit-type-deposit')).toBeChecked()
    await expect(d.getByTestId('transfer-edit-amount')).toHaveValue('80000.00')
    await expect(d.getByTestId('transfer-edit-time')).toHaveValue('2026-10-26T10:00')
    await d.getByTestId('transfer-edit-amount').fill('70000.00')
    await d.getByTestId('transfer-edit-submit').click()
    await expect(d).toBeHidden()
    await cash.expectSummary('70,817.40', '20,000.00', '50,817.40')
    await cash.expectRows([
      { amount: `${MINUS}241.30`, balance: '70,817.40' },
      { amount: '+360.00', balance: '71,058.70' },
      { amount: '+698.70', balance: '70,698.70' },
      { type: '入金', amount: '+70,000.00', balance: '70,000.00' },
    ])

    await cash.ledgerRows.nth(3).getByTestId('cash-ledger-edit').click()
    await d.getByTestId('transfer-edit-amount').fill('58900.00')
    await d.getByTestId('transfer-edit-remark').fill('改小')
    await d.getByTestId('transfer-edit-submit').click()
    await expect(d.getByTestId('transfer-edit-error')).toHaveText(`可用现金不足：修改后 2026-11-04 11:00 时可用现金将变为 ${MINUS}41.30`)
    await expect(d.getByTestId('transfer-edit-amount')).toHaveValue('58900.00')
    await expect(d.getByTestId('transfer-edit-remark')).toHaveValue('改小')
    await d.getByTestId('transfer-edit-cancel').click()
    await cash.expectSummary('70,817.40', '20,000.00', '50,817.40')
  })

  test('AC9 改类型入金 → 出金（流水 −500.00、审计"类型：入金 → 出金"）；删除重复入金', async ({ page }) => {
    await apiTransfer('DEPOSIT', '1000.00', '2026-11-02T10:00')
    await apiTransfer('DEPOSIT', '500.00', '2026-11-03T10:00')
    const ui = new Ui(page)
    const cash = new CashPage(page)
    await cash.open()
    await cash.ledgerRows.first().getByTestId('cash-ledger-edit').click()
    const d = ui.dialog('transfer-edit-modal')
    await d.getByTestId('transfer-edit-type-withdrawal').check()
    await d.getByTestId('transfer-edit-submit').click()
    await expect(d).toBeHidden()
    await cash.expectSummary('500.00', '0.00', '500.00')
    await cash.expectRows([{ type: '出金', amount: `${MINUS}500.00`, balance: '500.00' }, { amount: '+1,000.00', balance: '1,000.00' }])
    await page.getByTestId('nav-audit').click()
    expect((await ui.auditRows())[0]).toMatchObject({ op: '修改', object: '入金 #2', changes: ['类型：入金 → 出金'] })

    // delete the (now) withdrawal #2 → back to 1,000
    await page.getByTestId('nav-cash').click()
    await cash.ledgerRows.first().getByTestId('cash-ledger-delete').click()
    const del = ui.dialog('transfer-delete-modal')
    await expect(del.getByTestId('transfer-delete-summary')).toContainText('500.00')
    await del.getByTestId('transfer-delete-confirm').click()
    await expect(del).toBeHidden()
    await cash.expectSummary('1,000.00', '0.00', '1,000.00')
    await expect(cash.ledgerRows).toHaveCount(1)
    await page.getByTestId('nav-audit').click()
    expect((await ui.auditRows())[0]).toMatchObject({ op: '删除', changes: ['已删除'] })
    expect((await ui.auditRows())[0]?.object).toMatch(/#2$/)
  })

  test('AC9 场景 A 删除 D1 被拒（原文），对话框保留', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    const cash = new CashPage(page)
    await cash.open()
    await cash.ledgerRows.nth(3).getByTestId('cash-ledger-delete').click()
    const del = ui.dialog('transfer-delete-modal')
    await expect(del.getByTestId('transfer-delete-summary')).toHaveText('入金 +80,000.00，2026-10-26 10:00')
    await del.getByTestId('transfer-delete-confirm').click()
    await expect(del.getByTestId('transfer-delete-error')).toHaveText(`可用现金不足：删除后 2026-11-03 10:30 时可用现金将变为 ${MINUS}39,301.30`)
    await expect(del).toBeVisible()
  })

  test('AC10 连带变化：改 F1 张数 1 → 2，审计页同一行显示两条改动和"交易 #3 BTC 冻结变动 −24.70 → −24.69"', async ({ page }) => {
    await apiTransfer('DEPOSIT', '100.00', '2026-10-26T10:00')
    for (const time of ['2026-11-02T10:00', '2026-11-03T10:00']) {
      await apiTrade({ action: 'STO', symbol: 'F', strike: '12.345', type: 'PUT', netAmount: '0.50', time, multiplier: 1 })
    }
    await apiClose({ symbol: 'F', strike: '12.345', action: 'BTC', contracts: 2, netAmount: '0.20', tradedAtLocal: '2026-11-05T10:00' })
    const ui = new Ui(page)
    await ui.openTrades()
    const d = await ui.openEdit(1)
    await expect(d.getByTestId('trade-edit-multiplier')).toHaveValue('1')
    await d.getByTestId('trade-edit-contracts').fill('2')
    await d.getByTestId('trade-edit-submit').click()
    await expect(ui.dialog('trade-edit-modal')).toBeHidden()
    await expect(ui.tradeRow(3).getByTestId('trade-cell-frozen')).toHaveText(`${MINUS}24.69`)
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('100.80', '12.35', '88.45')
    await page.getByTestId('nav-audit').click()
    expect(await ui.auditRows()).toEqual([
      {
        op: '修改',
        object: '期权交易 #1',
        summary: expect.stringContaining('F 2026-12-18 12.345 P') as unknown as string,
        changes: ['张数：1 → 2', '冻结变动：+12.35 → +24.69'],
        side: [`交易 #3 BTC 冻结变动 ${MINUS}24.70 → ${MINUS}24.69`],
      },
    ])
  })

  test('AC11 没有修改、字段错误（张数 0 / 1.5、净额 0 / 3 位小数、备注 501、金额 0）不发请求不写审计', async ({ page }) => {
    await scenarioA()
    const ui = new Ui(page)
    await ui.openTrades()
    const d = await ui.openEdit(2)
    await d.getByTestId('trade-edit-submit').click()
    await expect(d.getByTestId('trade-edit-error')).toHaveText('没有修改')
    await expect(d.getByTestId('trade-edit-error')).toHaveAttribute('data-error-code', 'NO_CHANGES')
    const puts: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'PUT') puts.push(r.url())
    })
    const cases: [string, string, RegExp | string][] = [
      ['trade-edit-contracts', '0', /./],
      ['trade-edit-contracts', '1.5', /./],
      ['trade-edit-net-amount', '0', '净额必须大于 0'],
      ['trade-edit-net-amount', '360.001', '净额最多 2 位小数'],
      ['trade-edit-note', 'x'.repeat(501), '备注最多 500 个字符'],
    ]
    for (const [field, value, msg] of cases) {
      const original = await d.getByTestId(field).inputValue()
      await d.getByTestId(field).fill(value)
      await d.getByTestId('trade-edit-submit').click()
      await expect(ui.fieldError(d, field)).toHaveText(msg)
      await d.getByTestId(field).fill(original)
    }
    await d.getByTestId('trade-edit-cancel').click()

    await page.getByTestId('nav-cash').click()
    await new CashPage(page).ledgerRows.nth(3).getByTestId('cash-ledger-edit').click()
    const t = ui.dialog('transfer-edit-modal')
    await t.getByTestId('transfer-edit-amount').fill('0')
    await t.getByTestId('transfer-edit-submit').click()
    await expect(ui.fieldError(t, 'transfer-edit-amount')).not.toHaveText('')
    await page.waitForTimeout(300)
    expect(puts).toHaveLength(0)
    expect(await auditCount()).toBe(0)
  })

  test('AC12 审计页：3 条倒序；没有修改/删除按钮；"历史"只在有审计记录的行上显示；按对象筛选为空时的提示；清除后 3 条', async ({ page }) => {
    await scenarioA()
    const put = (body: Record<string, unknown>) =>
      fetch(`${BACKEND}/api/option-trades/2`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    expect((await put({ contracts: 1, netAmount: '365.00', tradedAtLocal: '2026-11-04T11:00', multiplier: 100 })).status).toBe(200)
    expect((await put({ contracts: 1, netAmount: '365.00', tradedAtLocal: '2026-11-04T11:00', multiplier: 100, note: '加仓' })).status).toBe(200)
    expect((await fetch(`${BACKEND}/api/option-trades/2`, { method: 'DELETE' })).status).toBe(204)
    const put3 = (body: Record<string, unknown>) =>
      fetch(`${BACKEND}/api/option-trades/3`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })

    const ui = new Ui(page)
    await page.goto('/audit')
    await expect(page.getByTestId('audit-row')).toHaveCount(3)
    expect((await ui.auditRows()).map((r) => [r.op, r.object, r.changes.join('|')])).toEqual([
      ['删除', '期权交易 #2', '已删除'],
      ['修改', '期权交易 #2', '备注：— → 加仓'],
      ['修改', '期权交易 #2', '净额：360.00 → 365.00'],
    ])
    await expect(page.getByTestId('audit-total')).toHaveText('共 3 条')
    await expect(page.getByTestId('audit-page').getByRole('button', { name: /^(修改|删除|编辑)$/ })).toHaveCount(0)

    // "历史" only on rows with audit records (contract: 列表行的 hasAuditLog). #2 (the only audited trade) is deleted,
    // L1 #1 and C1 #3 were never edited, the deposit #1 was never edited → no link anywhere yet.
    await page.getByTestId('nav-trades').click()
    await expect(ui.tradeRow(3)).toBeVisible()
    await expect(ui.tradeRow(1)).toBeVisible()
    await expect(page.getByTestId('trade-history')).toHaveCount(0)
    await page.getByTestId('nav-cash').click()
    await expect(new CashPage(page).ledgerRows).toHaveCount(3) // deposit, L1 open, C1 close (L2 deleted)
    await expect(page.getByTestId('cash-ledger-history')).toHaveCount(0)

    // Never-edited object opened directly by URL: empty state with the "never changed" hint, no in-table clear button.
    await page.goto('/audit?objectType=OPTION_TRADE&objectId=3')
    await expect(page.getByTestId('audit-empty')).toContainText('暂无审计记录')
    await expect(page.getByTestId('audit-empty-hint')).toHaveText('期权交易 #3 录入后没有被修改或删除过（审计只记录修改和删除）')
    await expect(page.getByTestId('audit-empty-clear')).toHaveCount(0)
    await expect(page.getByTestId('audit-filter-id')).toHaveValue('3')
    await expect(page.getByTestId('audit-filter-type-trade')).toBeChecked()
    await page.getByTestId('audit-filter-clear').click()
    await expect(page.getByTestId('audit-row')).toHaveCount(3)
    await expect(page.getByTestId('audit-empty-hint')).toHaveCount(0)

    await page.goBack()
    await expect(page.getByTestId('audit-empty')).toBeVisible()
    await expect(page.getByTestId('audit-empty-hint')).toHaveText('期权交易 #3 录入后没有被修改或删除过（审计只记录修改和删除）')

    await page.goto('/audit?objectType=OPTION_TRADE&objectId=2')
    await expect(page.getByTestId('audit-row')).toHaveCount(3)
    await expect(page.getByTestId('audit-empty-hint')).toHaveCount(0)
    await page.goto('/audit?objectType=CASH_TRANSFER&objectId=1')
    await expect(page.getByTestId('audit-empty')).toContainText('暂无审计记录')
    await expect(page.getByTestId('audit-empty-hint')).toHaveText('入金/出金 #1 录入后没有被修改或删除过（审计只记录修改和删除）')
    await expect(page.getByTestId('audit-empty-clear')).toHaveCount(0)
    await page.goto('/audit?objectId=1')
    await expect(page.getByTestId('audit-page')).toContainText('请同时指定对象类型')

    // Edit the deposit #1 and C1 #3 → their "历史" links appear and open the filtered audit page.
    const putTransfer = await fetch(`${BACKEND}/api/cash/transfers/1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type: 'DEPOSIT', amount: '80000.00', occurredAtLocal: '2026-10-26T10:00', remark: '初始资金' }),
    })
    expect(putTransfer.status).toBe(200)
    const putClose = await put3({ contracts: 2, netAmount: '241.30', tradedAtLocal: '2026-11-05T10:00', note: '提前平仓' })
    expect(putClose.status).toBe(200)

    await page.goto('/cash')
    const ledger = new CashPage(page).ledgerRows
    await expect(ledger).toHaveCount(3)
    await expect(page.getByTestId('cash-ledger-history')).toHaveCount(1)
    await ledger.last().getByTestId('cash-ledger-history').click()
    await expect(page).toHaveURL(/objectType=CASH_TRANSFER/)
    expect(new URL(page.url()).searchParams.get('objectId')).toBe('1')
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    await expect(page.getByTestId('audit-row')).toContainText('入金 #1')
    await expect(page.getByTestId('audit-row')).toContainText('备注：— → 初始资金')
    await expect(page.getByTestId('audit-empty')).toHaveCount(0)

    await page.getByTestId('nav-trades').click()
    await expect(ui.tradeRow(3).getByTestId('trade-history')).toBeVisible()
    await expect(ui.tradeRow(1).getByTestId('trade-history')).toHaveCount(0)
    await expect(page.getByTestId('trade-history')).toHaveCount(1)
    await ui.tradeRow(3).getByTestId('trade-history').click()
    await expect(page).toHaveURL(/\/audit\?.*objectType=OPTION_TRADE/)
    expect(new URL(page.url()).searchParams.get('objectId')).toBe('3')
    await expect(page.getByTestId('audit-filter-id')).toHaveValue('3')
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
    await expect(page.getByTestId('audit-row')).toContainText('期权交易 #3')
    await expect(page.getByTestId('audit-row')).toContainText('提前平仓')
  })

  test('审计页导航与空状态', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('nav-audit').click()
    await expect(page).toHaveURL(/\/audit$/)
    await expect(page.getByTestId('audit-empty')).toContainText('暂无审计记录')
    expect((await apiLedger()).totalItems).toBe(0)
  })
})
