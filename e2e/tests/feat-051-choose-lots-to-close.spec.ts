import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow } from '../support/clock'
import { chooseLotsById, lotLines, lotSelection } from '../support/closeLots'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares, TradeForm } from '../support/trade'

/**
 * FEAT-051 平仓时指定批次 E2E (docs/requirements/FEAT-051-choose-lots-to-close.md, contract docs/api/FEAT-051.md).
 * Fixed clock: backend + browser at 2026-11-20 10:00 New York (Fri). Fictional ticker ZZQ, fictional accounts 甲 (id 1,
 * the default account renamed) / 乙 (id 2). The DB is reset before each test, so ids restart at 1.
 *
 *   Scenario Z (甲): deposit 1,000.00 (11-01 10:00); 转入 200 ZZQ 1,600.00 (11-02);
 *     A = STO 1 ZZQ 2026-12-18 9 C 18.34 (11-10 10:00) = trade #1; B = STO 1 same 37.34 (11-11 10:00) = trade #2
 *     → 1,055.68 / 0.00 / 1,055.68; ZZQ 200 股 被覆盖 200 可用 0
 *   Scenario P (甲): deposit 30,000.00; P1 = STO 2 ZZQ 2026-12-18 8 P 30.00 (11-10 10:00, frozen 1,600.00) = #1;
 *     P2 = STO 1 same 21.00 (11-11 10:00, frozen 800.00) = #2 → 30,051.00 / 2,400.00 / 27,651.00
 *
 * Test ids (docs/architecture.md "FEAT-051 testid"): close dialog `close-trade-lot-row[data-trade-id][data-lot-index]
 * [data-selected]` → `close-trade-lot-checkbox` / `-lot-time` / `-lot-fifo-default` / `-lot-action` / `-lot-contracts`
 * / `-lot-net-amount` / `-lot-frozen`; `close-trade-lots-total`, `close-trade-lots-hint`, `close-trade-lots-none-selected`;
 * edit form the same with prefix `trade-edit-`; confirmation `close-trade-confirmation-covered-lots-title` /
 * `open-trade-confirmation-covered-lots-title`; audit snapshot `audit-before-selectedLots` / `audit-after-selectedLots`.
 * Shared helpers in support/closeLots.ts.
 */

const C9 = 'ZZQ 2026-12-18 9 C'
const P8 = 'ZZQ 2026-12-18 8 P'
const JIA = '甲'
const NOW_UTC = '2026-11-20T15:00:00Z' // 10:00 New York
const HINT = '选择要整笔平掉的开仓批次（默认最早一笔，FIFO）'

// ------------------------------------------------------------------------------------------------ seeding (API)

async function renameAccount(id: number, name: string): Promise<void> {
  const r = await fetch(`${BACKEND_URL}/api/accounts/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 200) throw new Error(`rename account ${id}: ${r.status} ${await r.text()}`)
}

async function createAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiCloseLots(t: { strike: string; type: 'CALL' | 'PUT'; lots: number[]; net: string; time: string }): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      symbol: 'ZZQ', expirationDate: '2026-12-18', strike: t.strike, optionType: t.type, action: 'BTC',
      lotTradeIds: t.lots, netAmount: t.net, tradedAtLocal: t.time, note: null,
    }),
  })
  if (r.status !== 201) throw new Error(`close failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiGet<T>(path: string): Promise<T> {
  return (await (await fetch(`${BACKEND_URL}${path}`)).json()) as T
}

async function scenarioZ(page: Page): Promise<{ a: number; b: number }> {
  await fixNow(page, NOW_UTC)
  setApiAccount(1)
  await renameAccount(1, JIA)
  await apiTransfer('DEPOSIT', '1000.00', '2026-11-01T10:00')
  await apiTransferInShares('ZZQ', 200, '2026-11-02', '1600.00')
  const a = (await apiTrade({ action: 'STO', symbol: 'ZZQ', strike: '9', type: 'CALL', netAmount: '18.34', time: '2026-11-10T10:00' })).id
  const b = (await apiTrade({ action: 'STO', symbol: 'ZZQ', strike: '9', type: 'CALL', netAmount: '37.34', time: '2026-11-11T10:00' })).id
  return { a, b }
}

async function scenarioP(page: Page): Promise<{ p1: number; p2: number }> {
  await fixNow(page, NOW_UTC)
  setApiAccount(1)
  await renameAccount(1, JIA)
  await apiTransfer('DEPOSIT', '30000.00', '2026-11-01T10:00')
  const p1 = (await apiTrade({ action: 'STO', symbol: 'ZZQ', strike: '8', type: 'PUT', contracts: 2, netAmount: '30.00', time: '2026-11-10T10:00' })).id
  const p2 = (await apiTrade({ action: 'STO', symbol: 'ZZQ', strike: '8', type: 'PUT', netAmount: '21.00', time: '2026-11-11T10:00' })).id
  return { p1, p2 }
}

// ------------------------------------------------------------------------------------------------ page helpers

class Ui {
  readonly modal: Locator
  constructor(readonly page: Page) {
    this.modal = page.getByTestId('close-trade-modal')
  }

  id(testId: string): Locator {
    return this.page.getByTestId(testId)
  }

  m(testId: string): Locator {
    return this.modal.getByTestId(testId)
  }

  row(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  async openPositions(): Promise<void> {
    await this.page.goto('/positions')
    await expect(this.id('positions-as-of')).toBeVisible()
  }

  async openClose(contract: string): Promise<void> {
    await this.row(contract).getByTestId('position-close').click()
    await expect(this.modal.getByRole('dialog')).toBeVisible()
    await expect(this.m('close-trade-lot-row').first()).toBeVisible()
  }

  async fillAndSubmit(net: string, time: string): Promise<void> {
    await this.m('close-trade-net-amount').fill(net)
    await this.m('close-trade-time').fill(time)
    await this.m('close-trade-submit').click()
  }

  /** "time action contracts net" of the confirmation's covered lots. */
  async confirmationLots(): Promise<string[]> {
    return this.m('close-trade-confirmation-covered-lots').getByTestId('covered-lot-row').evaluateAll((rs) =>
      rs.map((r) =>
        ['covered-lot-time', 'covered-lot-action', 'covered-lot-contracts', 'covered-lot-net-amount']
          .map((id) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async expectModalCash(total: string, frozen: string, available: string): Promise<void> {
    await expect(this.m('cash-summary-total')).toHaveText(total)
    await expect(this.m('cash-summary-frozen')).toHaveText(frozen)
    await expect(this.m('cash-summary-available')).toHaveText(available)
  }

  /** Trade ids of the expanded option row's lot table. */
  async positionLotIds(contract: string): Promise<number[]> {
    const btn = this.row(contract).getByTestId('position-expand')
    if ((await btn.getAttribute('aria-expanded')) !== 'true') await btn.click()
    const table = this.row(contract).locator('xpath=following-sibling::tr[1]').getByTestId('position-lots')
    await expect(table).toBeVisible()
    return table.getByTestId('lot-row').evaluateAll((rs) => rs.map((r) => Number(r.getAttribute('data-trade-id'))))
  }

  async expectStock(symbol: string, shares: string, covered: string, available: string): Promise<void> {
    const r = this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
    await expect(r.getByTestId('stock-position-shares')).toHaveText(shares)
    await expect(r.getByTestId('stock-position-covered-shares')).toHaveText(covered)
    await expect(r.getByTestId('stock-position-available-shares')).toHaveText(available)
  }

  closedRow(id: number): Locator {
    return this.page.locator(`[data-testid="closed-row"][data-trade-id="${id}"]`)
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

  async switchToAll(): Promise<void> {
    await this.id('account-switcher').click()
    await this.id('account-option-all').click()
    await expect(this.id('account-switcher')).toHaveAttribute('data-all-accounts', 'true')
  }
}

const LOT_A = '2026-11-10 10:00 STO（卖出开仓） 1 张 +18.34'
const LOT_B = '2026-11-11 10:00 STO（卖出开仓） 1 张 +37.34'

// ================================================================================================ tests

test.describe('FEAT-051 平仓时指定批次', () => {
  test('AC1 场景 Z 点 9 C 平仓：批次 2 行 A（勾选、FIFO 默认）/ B（未勾）；平仓张数：1；方向 BTC（买入平仓）；提示文字', async ({ page }) => {
    const { a, b } = await scenarioZ(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(C9)
    await expect(ui.m('close-trade-action')).toHaveText('BTC（买入平仓）')
    await expect(ui.m('close-trade-lot-row')).toHaveCount(2)
    expect(await lotLines(ui.modal)).toEqual([`${LOT_A} FIFO 默认`, LOT_B])
    expect(await lotSelection(ui.modal)).toEqual([true, false])
    expect(await ui.m('close-trade-lot-row').evaluateAll((rs) => rs.map((r) => Number(r.getAttribute('data-trade-id'))))).toEqual([a, b])
    await expect(ui.m('close-trade-lot-fifo-default')).toHaveCount(1)
    await expect(ui.m('close-trade-lot-fifo-default')).toHaveText('FIFO 默认')
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：1')
    await expect(ui.m('close-trade-lots-hint')).toHaveText(HINT)
    // short call: no frozen column
    await expect(ui.m('close-trade-lot-frozen')).toHaveCount(0)
    await expect(ui.m('close-trade-submit')).toBeEnabled()
  })

  test('AC2 取消 A、勾选 B、净额 15.10、11-19 10:00：确认只有批次 B、盈亏 +22.24、1,040.58/0.00/1,040.58；持仓剩 A；ZZQ 被覆盖 100 可用 100；已平仓 +37.34 / +22.24 展开为 B', async ({ page }) => {
    const { a, b } = await scenarioZ(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectStock('ZZQ', '200', '200', '0')
    await ui.openClose(C9)
    await chooseLotsById(ui.modal, [b])
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：1')
    await ui.fillAndSubmit('15.10', '2026-11-19T10:00')
    await expect(ui.m('close-trade-confirmation')).toBeVisible()
    await expect(ui.m('close-trade-confirmation-covered-lots-title')).toHaveText('被平掉的开仓批次')
    expect(await ui.confirmationLots()).toEqual([LOT_B])
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+22.24')
    await expect(ui.m('close-trade-confirmation-contracts')).toHaveText('1 张')
    await ui.expectModalCash('1,040.58', '0.00', '1,040.58')
    await ui.m('close-trade-done').click()
    await expect(ui.modal.getByRole('dialog')).toBeHidden()

    await expect(ui.row(C9).getByTestId('position-cell-contracts')).toHaveText('1')
    await expect(ui.row(C9).getByTestId('position-cell-opening-net')).toHaveText('+18.34')
    expect(await ui.positionLotIds(C9)).toEqual([a])
    await ui.expectStock('ZZQ', '200', '100', '100')

    await page.getByTestId('nav-closed').click()
    await expect(ui.id('closed-count')).toHaveText('共 1 笔平仓')
    await expect(ui.closedRow(b + 1).getByTestId('closed-cell-opening-net')).toHaveText('+37.34')
    await expect(ui.closedRow(b + 1).getByTestId('closed-cell-pnl')).toHaveText('+22.24')
    expect(await ui.closedLots(b + 1)).toEqual([LOT_B])
  })

  test('AC3 不改勾选：被平批次 A、盈亏 +3.24；剩余 B +37.34；1,040.58/0.00/1,040.58', async ({ page }) => {
    const { b } = await scenarioZ(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(C9)
    await ui.fillAndSubmit('15.10', '2026-11-19T10:00')
    expect(await ui.confirmationLots()).toEqual([LOT_A])
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+3.24')
    await ui.expectModalCash('1,040.58', '0.00', '1,040.58')
    await ui.m('close-trade-done').click()
    expect(await ui.positionLotIds(C9)).toEqual([b])
    await expect(ui.row(C9).getByTestId('position-cell-opening-net')).toHaveText('+37.34')
  })

  test('AC4 场景 P 只勾 P2（平仓张数 1）、9.65：冻结 −800.00、盈亏 +11.35、30,041.35/1,600.00/28,441.35；剩 P1 2 张；冻结列只在空头 put 显示', async ({ page }) => {
    const { p1, p2 } = await scenarioP(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(P8)
    expect(await lotLines(ui.modal)).toEqual([
      '2026-11-10 10:00 STO（卖出开仓） 2 张 +30.00 FIFO 默认',
      '2026-11-11 10:00 STO（卖出开仓） 1 张 +21.00',
    ])
    await expect(ui.m('close-trade-lot-frozen')).toHaveText(['1,600.00', '800.00'])
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：2')
    await chooseLotsById(ui.modal, [p2])
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：1')
    await ui.fillAndSubmit('9.65', '2026-11-19T10:00')
    await expect(ui.m('close-trade-confirmation-frozen-change')).toHaveText(`${MINUS}800.00`)
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+11.35')
    await ui.expectModalCash('30,041.35', '1,600.00', '28,441.35')
    await ui.m('close-trade-done').click()
    expect(await ui.positionLotIds(P8)).toEqual([p1])
    await expect(ui.row(P8).getByTestId('position-cell-contracts')).toHaveText('2')
    await expect(ui.row(P8).getByTestId('position-cell-frozen')).toContainText('1,600.00')
  })

  test('AC4（另起场景 P）勾 P1 + P2（平仓张数 3）、20.00：冻结 −2,400.00、盈亏 +31.00、30,031.00/0.00/30,031.00、持仓中没有该合约', async ({ page }) => {
    const { p1, p2 } = await scenarioP(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(P8)
    await chooseLotsById(ui.modal, [p1, p2])
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：3')
    await ui.fillAndSubmit('20.00', '2026-11-19T10:00')
    await expect(ui.m('close-trade-confirmation-contracts')).toHaveText('3 张')
    await expect(ui.m('close-trade-confirmation-frozen-change')).toHaveText(`${MINUS}2,400.00`)
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+31.00')
    await ui.expectModalCash('30,031.00', '0.00', '30,031.00')
    await ui.m('close-trade-done').click()
    await expect(ui.row(P8)).toHaveCount(0)
  })

  test('AC5 一个都不勾 → 平仓张数：0、提示、提交不可用、不发请求；B 在 11-10 15:00 → 后端原文、CLOSE_LOTS_INVALID、勾选与输入保留、现金不变', async ({ page }) => {
    const { b } = await scenarioZ(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(C9)
    const posts: string[] = []
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/api/option-trades')) posts.push(r.url())
    })
    await chooseLotsById(ui.modal, [])
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：0')
    await expect(ui.m('close-trade-lots-none-selected')).toHaveText('请至少选择一笔开仓批次')
    await expect(ui.m('close-trade-submit')).toBeDisabled()
    await page.waitForTimeout(300)
    expect(posts).toHaveLength(0)

    await chooseLotsById(ui.modal, [b])
    await ui.m('close-trade-note').fill('保留')
    await ui.fillAndSubmit('15.10', '2026-11-10T15:00')
    await expect(ui.m('close-trade-error')).toHaveText('所选批次（2026-11-11 10:00 STO 1 张）在成交时间 2026-11-10 15:00 还未开仓')
    await expect(ui.m('close-trade-error')).toHaveAttribute('data-error-code', 'CLOSE_LOTS_INVALID')
    expect(await lotSelection(ui.modal)).toEqual([false, true])
    await expect(ui.m('close-trade-net-amount')).toHaveValue('15.10')
    await expect(ui.m('close-trade-time')).toHaveValue('2026-11-10T15:00')
    await expect(ui.m('close-trade-note')).toHaveValue('保留')
    await expect(ui.m('cash-summary-total')).toHaveText('1,055.68')
  })

  test('AC5 B 已被平掉后再选 B（对话框打开期间被平）→ 原文"已被 2026-11-19 10:00 的 BTC 1 张平掉"', async ({ page }) => {
    const { b } = await scenarioZ(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(C9)
    await chooseLotsById(ui.modal, [b])
    await apiCloseLots({ strike: '9', type: 'CALL', lots: [b], net: '15.10', time: '2026-11-19T10:00' })
    await ui.fillAndSubmit('1.00', '2026-11-20T09:00')
    await expect(ui.m('close-trade-error')).toHaveText('所选批次（2026-11-11 10:00 STO 1 张）已被 2026-11-19 10:00 的 BTC 1 张平掉')
    await expect(ui.m('close-trade-error')).toHaveAttribute('data-error-code', 'CLOSE_LOTS_INVALID')
  })

  test('AC6 AC2 之后：交易列表删除 B → 原文指出那笔 BTC 和批次 B，对话框保留；改 B 时间到 11-19 11:00 → 原文；数据不变', async ({ page }) => {
    const { b } = await scenarioZ(page)
    await apiCloseLots({ strike: '9', type: 'CALL', lots: [b], net: '15.10', time: '2026-11-19T10:00' })
    const ui = new Ui(page)
    await page.goto('/trades')
    const bRow = page.locator(`[data-testid="trade-row"][data-trade-id="${b}"]`)
    await bRow.getByTestId('trade-delete').click()
    const del = page.getByTestId('trade-delete-modal').getByRole('dialog')
    await del.getByTestId('trade-delete-confirm').click()
    await expect(del.getByTestId('trade-delete-error')).toHaveText(
      '该开仓被 2026-11-19 10:00 的 BTC 1 张平掉（所选批次 2026-11-11 10:00 STO 1 张），请先修改或删除那笔平仓',
    )
    await expect(del.getByTestId('trade-delete-error')).toHaveAttribute('data-error-code', 'CLOSE_LOTS_INVALID')
    await expect(del).toBeVisible()
    await del.getByTestId('trade-delete-cancel').click()

    await bRow.getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await d.getByTestId('trade-edit-time').fill('2026-11-19T11:00')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d.getByTestId('trade-edit-error')).toHaveText(
      '修改后，2026-11-19 10:00 BTC 1 张 ZZQ 2026-12-18 9 C 选中的批次 2026-11-19 11:00 STO 1 张 在该平仓成交时还未开仓',
    )
    await d.getByTestId('trade-edit-cancel').click()
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('1,040.58', '0.00', '1,040.58')
    await page.getByTestId('nav-positions').click()
    await expect(ui.row(C9).getByTestId('position-cell-contracts')).toHaveText('1')
  })

  test('AC7 AC3 之后在交易列表修改那笔 BTC：批次表默认勾 A；改为只勾 B → 盈亏 +22.24、剩 A、现金不变；审计"被平批次：2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张"', async ({ page }) => {
    const { a, b } = await scenarioZ(page)
    const x = await apiCloseLots({ strike: '9', type: 'CALL', lots: [a], net: '15.10', time: '2026-11-19T10:00' })
    await page.goto('/trades')
    await page.locator(`[data-testid="trade-row"][data-trade-id="${x}"]`).getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    await expect(d.getByTestId('trade-edit-lot-row')).toHaveCount(2)
    expect(await lotLines(d, 'trade-edit')).toEqual([LOT_A, LOT_B])
    expect(await lotSelection(d, 'trade-edit')).toEqual([true, false])
    await expect(d.getByTestId('trade-edit-contracts')).toHaveCount(0)
    await chooseLotsById(d, [b], 'trade-edit')
    await expect(d.getByTestId('trade-edit-lots-total')).toHaveText('平仓张数：1')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d).toBeHidden()
    const t = await apiGet<{ realizedPnl: string; coveredLots: { tradeId: number }[] }>(`/api/option-trades/${x}`)
    expect(t.realizedPnl).toBe('22.24')
    expect(t.coveredLots.map((l) => l.tradeId)).toEqual([b])
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('1,040.58', '0.00', '1,040.58')

    await page.getByTestId('nav-audit').click()
    const first = page.getByTestId('audit-row').first()
    await expect(first.getByTestId('audit-cell-changes-line')).toHaveText(['被平批次：2026-11-10 10:00 1 张 → 2026-11-11 10:00 1 张'])
    await first.getByTestId('audit-expand').click()
    const detail = page.getByTestId('audit-detail').first()
    await expect(detail.getByTestId('audit-before-selectedLots')).toContainText('2026-11-10 10:00 1 张')
    await expect(detail.getByTestId('audit-after-selectedLots')).toContainText('2026-11-11 10:00 1 张')

    await page.getByTestId('nav-positions').click()
    const ui = new Ui(page)
    expect(await ui.positionLotIds(C9)).toEqual([a])
  })

  test('AC7（场景 P，P2 已平）修改所选批次 P2 → P1：平仓张数 2、冻结变动 −1,600.00、盈亏 +20.35、30,041.35/800.00/29,241.35', async ({ page }) => {
    const { p1, p2 } = await scenarioP(page)
    const x = await apiCloseLots({ strike: '8', type: 'PUT', lots: [p2], net: '9.65', time: '2026-11-19T10:00' })
    await page.goto('/trades')
    const row = page.locator(`[data-testid="trade-row"][data-trade-id="${x}"]`)
    await row.getByTestId('trade-edit').click()
    const d = page.getByTestId('trade-edit-modal').getByRole('dialog')
    expect(await lotSelection(d, 'trade-edit')).toEqual([false, true])
    await chooseLotsById(d, [p1], 'trade-edit')
    await expect(d.getByTestId('trade-edit-lots-total')).toHaveText('平仓张数：2')
    await d.getByTestId('trade-edit-submit').click()
    await expect(d).toBeHidden()
    await expect(row.getByTestId('trade-cell-contracts')).toHaveText('2')
    await expect(row.getByTestId('trade-cell-frozen')).toHaveText(`${MINUS}1,600.00`)
    const t = await apiGet<{ realizedPnl: string }>(`/api/option-trades/${x}`)
    expect(t.realizedPnl).toBe('20.35')
    await page.getByTestId('nav-cash').click()
    await new CashPage(page).expectSummary('30,041.35', '800.00', '29,241.35')
  })

  test('AC9 录入交易页 BTC 1 张（不选批次）：按 FIFO 平掉 A、+3.24、确认标题"被平掉的开仓批次（FIFO）"（Q257）', async ({ page }) => {
    const { b } = await scenarioZ(page)
    const form = new TradeForm(page)
    await form.open()
    await page.getByTestId('open-trade-action-btc').check()
    await form.fill({ symbol: 'ZZQ', expiration: '2026-12-18', strike: '9', type: 'CALL', contracts: '1', netAmount: '15.10', time: '2026-11-19T10:00' })
    await form.submit.click()
    await expect(page.getByTestId('open-trade-confirmation-realized-pnl')).toHaveText('+3.24')
    await expect(page.getByTestId('open-trade-confirmation-covered-lots-title')).toHaveText('被平掉的开仓批次（FIFO）')
    await new CashPage(page).expectSummary('1,040.58', '0.00', '1,040.58')
    await page.getByTestId('nav-positions').click()
    expect(await new Ui(page).positionLotIds(C9)).toEqual([b])
  })

  test('AC9 AC2 之后切到全部账户：持仓页没有平仓按钮；已平仓该行账户甲、展开为批次 B；交易列表没有修改', async ({ page }) => {
    const { b } = await scenarioZ(page)
    await createAccount('乙')
    const x = await apiCloseLots({ strike: '9', type: 'CALL', lots: [b], net: '15.10', time: '2026-11-19T10:00' })
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchToAll()
    await expect(ui.row(C9)).toHaveCount(1)
    await expect(page.getByTestId('position-close')).toHaveCount(0)
    await page.getByTestId('nav-closed').click()
    await expect(ui.closedRow(x).getByTestId('closed-cell-account')).toHaveText(JIA)
    expect(await ui.closedLots(x)).toEqual([LOT_B])
    await page.getByTestId('nav-trades').click()
    await expect(page.getByTestId('trade-row').first()).toBeVisible()
    await expect(page.getByTestId('trade-edit')).toHaveCount(0)
  })

  test('边界：不连续选择（P1、P2、P3 只勾 P1 + P3）→ 平仓张数 3、冻结 −2,400.00、盈亏 +43.00；剩 P2 且标 FIFO 默认', async ({ page }) => {
    const { p1, p2 } = await scenarioP(page)
    const p3 = (await apiTrade({ action: 'STO', symbol: 'ZZQ', strike: '8', type: 'PUT', netAmount: '25.00', time: '2026-11-12T10:00' })).id
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(P8)
    await chooseLotsById(ui.modal, [p1, p3])
    await expect(ui.m('close-trade-lots-total')).toHaveText('平仓张数：3')
    await ui.fillAndSubmit('12.00', '2026-11-19T10:00')
    await expect(ui.m('close-trade-confirmation-frozen-change')).toHaveText(`${MINUS}2,400.00`)
    await expect(ui.m('close-trade-confirmation-realized-pnl')).toHaveText('+43.00')
    expect(await ui.confirmationLots()).toEqual([
      '2026-11-10 10:00 STO（卖出开仓） 2 张 +30.00',
      '2026-11-12 10:00 STO（卖出开仓） 1 张 +25.00',
    ])
    await ui.expectModalCash('30,064.00', '800.00', '29,264.00')
    await ui.m('close-trade-done').click()
    expect(await ui.positionLotIds(P8)).toEqual([p2])
    await ui.openClose(P8)
    expect(await lotLines(ui.modal)).toEqual(['2026-11-11 10:00 STO（卖出开仓） 1 张 +21.00 FIFO 默认'])
  })

  test('边界：后端不可用时提交 → 显示错误、对话框和勾选保留', async ({ page, backend }) => {
    const { b } = await scenarioZ(page)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.openClose(C9)
    await chooseLotsById(ui.modal, [b])
    await ui.m('close-trade-net-amount').fill('15.10')
    await ui.m('close-trade-time').fill('2026-11-19T10:00')
    await backend.stop()
    try {
      await ui.m('close-trade-submit').click()
      await expect(ui.m('close-trade-error')).toBeVisible()
      await expect(ui.m('close-trade-error')).not.toHaveText('')
      expect(await lotSelection(ui.modal)).toEqual([false, true])
      await expect(ui.modal.getByRole('dialog')).toBeVisible()
    } finally {
      await backend.start()
    }
  })
})
