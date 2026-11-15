import type { Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { apiTrade, TradeForm } from '../support/trade'

/**
 * FEAT-033 多账户 E2E. After the per-test reset there is only account 1 "默认账户"; the next new account gets id 2.
 * Default account data: deposit 100,000.00 and a short AAPL 2026-11-20 190 P (so the same contract can be long in
 * the small account) → 100,120.00 / 19,000.00 / 81,120.00.
 */

const BACKEND = BACKEND_URL

async function apiCreateAccount(name: string, brokerNote: string | null = null): Promise<number> {
  const r = await fetch(`${BACKEND}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function seedDefault(): Promise<void> {
  setApiAccount(1)
  await apiTransfer('DEPOSIT', '100000.00', '2026-10-26T10:00')
  await apiTrade({ action: 'STO', expiration: '2026-11-20', strike: '190', type: 'PUT', netAmount: '120.00', time: '2026-11-09T10:00' })
}

async function switchTo(page: Page, id: number, name: string): Promise<void> {
  await page.getByTestId('account-switcher').click()
  await page.getByTestId(`account-option-${id}`).click()
  await expect(page.getByTestId('account-current')).toHaveText(name)
}

async function auditRows(page: Page): Promise<string[]> {
  await expect(page.getByTestId('audit-row').first()).toBeVisible()
  return page.getByTestId('audit-row').evaluateAll((rs) =>
    rs.map((r) =>
      ['audit-cell-operation', 'audit-cell-object', 'audit-cell-changes']
        .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
        .join(' | '),
    ),
  )
}

test.describe('FEAT-033 多账户', () => {
  test('AC3 重置后只有默认账户：每个页面都显示"当前账户：默认账户"，各页为空', async ({ page }) => {
    for (const path of ['/', '/cash', '/trades/new', '/trades', '/positions', '/closed', '/pnl-summary', '/audit', '/accounts']) {
      await page.goto(path)
      await expect(page.getByTestId('account-switcher')).toContainText('当前账户：')
      await expect(page.getByTestId('account-current')).toHaveText('默认账户')
    }
    await page.goto('/cash')
    await new CashPage(page).expectSummary('0.00', '0.00', '0.00')
    await page.goto('/trades')
    await expect(page.getByTestId('trade-list-empty')).toContainText('暂无交易')
    await page.getByTestId('account-switcher').click()
    await expect(page.locator('[data-testid^="account-option-"]')).toHaveCount(1)
    await expect(page.getByTestId('account-manage-link')).toBeVisible()
  })

  test('AC4 账户管理：新增 " 小账户 "（不自动切换、出现在切换器）；重名/空/31 字符报错在名称下；改名"主账户"；无删除按钮；审计记录', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('account-switcher').click()
    await page.getByTestId('account-manage-link').click()
    await expect(page).toHaveURL(/\/accounts$/)
    await expect(page.getByTestId('accounts-page')).toContainText('请勿填写账号')
    await expect(page.getByTestId('account-row')).toHaveCount(1)
    await expect(page.getByTestId('account-row').first().getByTestId('account-cell-broker-note')).toHaveText('—')

    const modal = page.getByTestId('account-form-modal').getByRole('dialog')
    const nameErr = modal
      .locator('.ant-form-item')
      .filter({ has: page.getByTestId('account-form-name') })
      .locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
    await page.getByTestId('account-create').click()
    await expect(modal).toBeVisible()
    await expect(modal).toContainText('请勿填写账号')
    await modal.getByTestId('account-form-name').fill(' 小账户 ')
    await modal.getByTestId('account-form-broker-note').fill('券商 B')
    await modal.getByTestId('account-form-submit').click()
    await expect(modal).toBeHidden()
    const row2 = page.locator('[data-testid="account-row"][data-account-id="2"]')
    await expect(row2.getByTestId('account-cell-name')).toHaveText('小账户')
    await expect(row2.getByTestId('account-cell-broker-note')).toHaveText('券商 B')
    await expect(row2.getByTestId('account-cell-created')).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
    await expect(page.getByTestId('account-current')).toHaveText('默认账户')
    await page.getByTestId('account-switcher').click()
    await expect(page.getByTestId('account-option-2')).toContainText('小账户')
    await page.keyboard.press('Escape')

    for (const [name, msg] of [
      ['默认账户', '账户名称已存在'],
      ['小账户', '账户名称已存在'],
      ['', null],
      ['x'.repeat(31), null],
    ] as const) {
      await page.getByTestId('account-create').click()
      await modal.getByTestId('account-form-name').fill(name)
      await modal.getByTestId('account-form-submit').click()
      if (msg) await expect(nameErr).toHaveText(msg)
      else await expect(nameErr).not.toHaveText('')
      await expect(modal).toBeVisible()
      await modal.getByTestId('account-form-cancel').click()
      await expect(modal).toBeHidden()
    }
    await expect(page.getByTestId('account-row')).toHaveCount(2)

    // rename the default account
    const row1 = page.locator('[data-testid="account-row"][data-account-id="1"]')
    await row1.getByTestId('account-edit').click()
    await expect(modal.getByTestId('account-form-name')).toHaveValue('默认账户')
    await modal.getByTestId('account-form-submit').click()
    await expect(modal.getByTestId('account-form-error')).toHaveText('没有修改')
    await modal.getByTestId('account-form-name').fill('主账户')
    await modal.getByTestId('account-form-submit').click()
    await expect(modal).toBeHidden()
    await expect(page.getByTestId('account-current')).toHaveText('主账户')
    await expect(row1.getByTestId('account-cell-name')).toHaveText('主账户')
    await expect(page.getByTestId('accounts-page').getByRole('button', { name: /删除/ })).toHaveCount(0)

    await page.getByTestId('nav-audit').click()
    expect((await auditRows(page))[0]).toBe('修改 | 账户 #1 | 名称：默认账户 → 主账户')
    await page.getByTestId('audit-filter-type-account').check()
    await page.getByTestId('audit-filter-submit').click()
    await expect(page.getByTestId('audit-row')).toHaveCount(1)
  })

  test('AC5–AC7 隔离：小账户从零开始；现金与担保、同合约一多一空、FIFO 平仓都只在本账户；切回默认账户数字不变', async ({ page }) => {
    await seedDefault()
    await apiCreateAccount('小账户', '券商 B')
    await page.goto('/')
    await new CashPage(page).expectSummary('100,120.00', '19,000.00', '81,120.00')
    await switchTo(page, 2, '小账户')

    // AC 5: everything empty
    await new CashPage(page).expectSummary('0.00', '0.00', '0.00')
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('0.00')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('0')
    await page.getByTestId('nav-trades').click()
    await expect(page.getByTestId('trade-list-empty')).toBeVisible()
    await page.getByTestId('nav-positions').click()
    await expect(page.getByTestId('positions-empty')).toContainText('暂无持仓')
    await page.getByTestId('nav-closed').click()
    await expect(page.getByTestId('closed-empty')).toHaveText('暂无平仓记录')
    await page.getByTestId('nav-audit').click()
    expect(await auditRows(page)).toEqual(['新增 | 账户 #2 | 已新增'])

    // AC 6: cash and collateral in the small account only
    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.save({ type: 'DEPOSIT', amount: '30000.00', time: '2026-11-02T10:00' })
    await page.getByTestId('nav-open-trade').click()
    const form = new TradeForm(page)
    await form.submitAndExpectSaved(TradeForm.base({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' }))
    await cash.expectSummary('30,349.35', '20,000.00', '10,349.35')
    await form.submitAndExpectError(
      TradeForm.base({ action: 'BTO', symbol: 'MSFT', strike: '450', type: 'CALL', netAmount: '20000.00', time: '2026-11-04T10:00' }),
      'CASH_INSUFFICIENT',
      '可用现金不足：需要 20,000.00，可用 10,349.35',
    )

    // AC 7: long the same contract that the default account is short
    await form.clear.click()
    await form.submitAndExpectSaved(
      TradeForm.base({ action: 'BTO', expiration: '2026-11-20', strike: '190', type: 'PUT', netAmount: '300.00', time: '2026-11-04T10:00' }),
    )
    await cash.expectSummary('30,049.35', '20,000.00', '10,049.35')
    await page.getByTestId('nav-positions').click()
    const posRows = page.getByTestId('position-row')
    await expect(posRows).toHaveCount(2)
    await expect(page.locator('[data-testid="position-row"][data-contract="AAPL 2026-11-20 190 P"]').getByTestId('position-cell-direction')).toHaveText('多头')
    await expect(page.locator('[data-testid="position-row"][data-contract="AAPL 2026-12-18 200 P"]').getByTestId('position-cell-direction')).toHaveText('空头')

    // close the small account's own 200 P lot
    await page.locator('[data-testid="position-row"][data-contract="AAPL 2026-12-18 200 P"]').getByTestId('position-close').click()
    const close = page.getByTestId('close-trade-modal')
    await close.getByTestId('close-trade-net-amount').fill('100.00')
    await close.getByTestId('close-trade-time').fill('2026-11-05T10:00')
    await close.getByTestId('close-trade-submit').click()
    await expect(close.getByTestId('close-trade-confirmation-realized-pnl')).toHaveText('+249.35')
    await close.getByTestId('close-trade-done').click()
    await page.getByTestId('nav-closed').click()
    await expect(page.getByTestId('closed-count')).toHaveText('共 1 笔平仓')
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+249.35')
    await page.getByTestId('nav-home').click()
    await expect(page.getByTestId('dashboard-realized-total')).toHaveText('+249.35')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('1')
    await cash.expectSummary('29,949.35', '0.00', '29,949.35')

    // back to the default account: nothing changed
    await switchTo(page, 1, '默认账户')
    await cash.expectSummary('100,120.00', '19,000.00', '81,120.00')
    await expect(page.getByTestId('dashboard-realized-count')).toHaveText('0')
    await page.getByTestId('nav-positions').click()
    await expect(page.getByTestId('position-row')).toHaveCount(1)
    await expect(page.locator('[data-testid="position-row"][data-contract="AAPL 2026-11-20 190 P"]').getByTestId('position-cell-direction')).toHaveText('空头')
    await page.getByTestId('nav-trades').click()
    await expect(page.getByTestId('trade-list-total')).toHaveText('共 1 条')
  })

  test('AC8 小账户里修改平仓、审计按账户；默认账户审计为空', async ({ page }) => {
    await seedDefault()
    const sid = await apiCreateAccount('小账户')
    setApiAccount(sid)
    await apiTransfer('DEPOSIT', '30000.00', '2026-11-02T10:00')
    await apiTrade({ action: 'STO', strike: '200', type: 'PUT', netAmount: '349.35', time: '2026-11-03T10:30' })
    const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbol: 'AAPL', expirationDate: '2026-12-18', strike: '200', optionType: 'PUT', action: 'BTC', contracts: 1, netAmount: '100.00', tradedAtLocal: '2026-11-05T10:00' }),
    })
    const closeId = ((await r.json()) as { id: number }).id
    setApiAccount(1)

    await page.goto('/trades')
    await switchTo(page, sid, '小账户')
    await page.locator(`[data-testid="trade-row"][data-trade-id="${closeId}"]`).getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await d.getByTestId('trade-edit-net-amount').fill('110.00')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d).toBeHidden()
    await page.getByTestId('nav-closed').click()
    await expect(page.getByTestId('closed-pnl-total')).toHaveText('+239.35')
    await page.getByTestId('nav-audit').click()
    expect(await auditRows(page)).toEqual([`修改 | 期权交易 #${closeId} | 净额：100.00 → 110.00`, '新增 | 账户 #2 | 已新增'])
    await switchTo(page, 1, '默认账户')
    await expect(page.getByTestId('audit-empty')).toContainText('暂无审计记录')

    // the small account's trade is not reachable from the default account
    const cross = await fetch(`${BACKEND}/api/option-trades/${closeId}`)
    expect(cross.status).toBe(404)
  })

  test('AC9 切换器：同一筛选按新账户显示且不刷新；刷新后记住；清除存储后取最小 id；录入的交易只在当前账户', async ({ page }) => {
    await seedDefault()
    const sid = await apiCreateAccount('小账户')
    setApiAccount(sid)
    await apiTransfer('DEPOSIT', '30000.00', '2026-11-02T10:00')
    await apiTrade({ action: 'BTO', strike: '210', type: 'CALL', netAmount: '512.65', time: '2026-11-03T10:30' })
    await apiTrade({ action: 'BTO', symbol: 'MSFT', strike: '450', type: 'CALL', netAmount: '100.00', time: '2026-11-03T11:00' })
    setApiAccount(1)

    await page.goto('/trades?symbol=AAPL')
    await expect(page.getByTestId('trade-row')).toHaveCount(1)
    await expect(page.getByTestId('trade-row').first().getByTestId('trade-cell-contract')).toHaveText('AAPL 2026-11-20 190 P')
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })
    await switchTo(page, sid, '小账户')
    await expect(page.locator('.ant-message').getByText('已切换到账户：小账户')).toBeVisible()
    await expect(page).toHaveURL(/\/trades\?symbol=AAPL$/)
    await expect(page.getByTestId('trade-filter-symbol')).toHaveValue('AAPL')
    await expect(page.getByTestId('trade-row').first().getByTestId('trade-cell-contract')).toHaveText('AAPL 2026-12-18 210 C')
    await expect(page.getByTestId('trade-row')).toHaveCount(1)
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true)
    expect(await page.evaluate(() => localStorage.getItem('option-mgr.accountId'))).toBe(String(sid))

    await page.reload()
    await expect(page.getByTestId('account-current')).toHaveText('小账户')
    await expect(page.getByTestId('trade-row').first().getByTestId('trade-cell-contract')).toHaveText('AAPL 2026-12-18 210 C')

    // a trade entered now lands only in the small account
    await page.getByTestId('nav-open-trade').click()
    await new TradeForm(page).submitAndExpectSaved(
      TradeForm.base({ action: 'BTO', symbol: 'KO', strike: '60', type: 'CALL', netAmount: '10.00', time: '2026-11-04T10:00' }),
    )
    setApiAccount(sid)
    const small = (await (await fetch(`${BACKEND}/api/option-trades`)).json()) as { totalItems: number }
    setApiAccount(1)
    const def = (await (await fetch(`${BACKEND}/api/option-trades`)).json()) as { totalItems: number }
    expect(small.totalItems).toBe(3)
    expect(def.totalItems).toBe(1)

    // forgotten selection or a removed account → smallest id
    await page.evaluate(() => localStorage.clear())
    await page.reload()
    await expect(page.getByTestId('account-current')).toHaveText('默认账户')
    await page.evaluate(() => localStorage.setItem('option-mgr.accountId', '999'))
    await page.reload()
    await expect(page.getByTestId('account-current')).toHaveText('默认账户')
  })

  test('切换账户会关闭打开的对话框和未保存的表单，不会写进切换后的账户', async ({ page }) => {
    await seedDefault()
    const sid = await apiCreateAccount('小账户')
    // open edit modal in the default account, then switch
    await page.goto('/trades')
    await page.getByTestId('trade-row').first().getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await expect(d).toBeVisible()
    await d.getByTestId('trade-edit-net-amount').fill('999.00')
    // the modal mask covers the header, so drive the switcher with DOM click events (a click on the mask would
    // close the dialog for the wrong reason)
    await page.getByTestId('account-switcher').dispatchEvent('click')
    await page.getByTestId(`account-option-${sid}`).dispatchEvent('click')
    await expect(page.getByTestId('account-current')).toHaveText('小账户')
    await expect(d).toBeHidden()
    // the "已切换到账户" toast sits over the top nav and stays while hovered: move the mouse away, let it close
    await page.mouse.move(5, 400)
    await expect(page.locator('.ant-message-notice')).toHaveCount(0)

    // half-filled entry form is discarded
    await page.getByTestId('nav-open-trade').click()
    const form = new TradeForm(page)
    await form.fill({ action: 'BTO', symbol: 'MSFT', netAmount: '1.00' })
    await switchTo(page, 1, '默认账户')
    await expect(form.symbol).toHaveValue('')
    setApiAccount(1)
    const def = (await (await fetch(`${BACKEND}/api/option-trades/1`)).json()) as { netAmount: string }
    expect(def.netAmount).toBe('120.00')
    setApiAccount(sid)
    const small = (await (await fetch(`${BACKEND}/api/option-trades`)).json()) as { totalItems: number }
    expect(small.totalItems).toBe(0)
  })

  test('平仓对话框打开时切换账户 → 对话框关闭；资金页现金表单内容被丢弃', async ({ page }) => {
    await seedDefault()
    const sid = await apiCreateAccount('小账户')
    await page.goto('/positions')
    await page.getByTestId('position-close').first().click()
    await expect(page.getByTestId('close-trade-modal').getByRole('dialog')).toBeVisible()
    await page.getByTestId('account-switcher').dispatchEvent('click')
    await page.getByTestId(`account-option-${sid}`).dispatchEvent('click')
    await expect(page.getByTestId('account-current')).toHaveText('小账户')
    await expect(page.getByTestId('close-trade-modal').getByRole('dialog')).toHaveCount(0)
    await expect(page.getByTestId('positions-empty')).toBeVisible()

    await page.getByTestId('nav-cash').click()
    const cash = new CashPage(page)
    await cash.amount.fill('123.45')
    await switchTo(page, 1, '默认账户')
    await expect(cash.amount).toHaveValue('')
    await cash.expectSummary('100,120.00', '19,000.00', '81,120.00')
    expect((await (await fetch(`${BACKEND}/api/cash/ledger`)).json() as { totalItems: number }).totalItems).toBe(2)
    await expect(page.getByTestId('cash-ledger-row').first().getByTestId('cash-ledger-amount')).toHaveText('+120.00')
    void MINUS
  })
})
