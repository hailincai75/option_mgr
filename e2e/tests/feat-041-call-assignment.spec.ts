import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiLegacyTrade, apiTrade } from '../support/trade'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, PHASES, phaseInstant } from '../support/clock'
import { addDays } from '../support/ny'

/**
 * FEAT-041 卖出 call 被指派 E2E (real clock). As FEAT-013, dates are relative to today's New York date T: the scenario C
 * expiration EXP = T−3 (already expired), early assignments use dates before EXP. Amounts are those of the
 * requirement's scenario C. Fictional tickers only.
 * DB reset per test: scenario C = deposit #1; S1, S2 = stock records #1, #2; C1, C2, L1 = option trades #1–#3; the
 * first assignment = option trade #4 and its generated ASSIGN_SELL = stock record #3.
 */

// ------------------------------------------------------------------------------------------------ selectors
// All data-testids used by this spec, in one place. FEAT-013 names are reused; the call-only ones follow the frontend
// implementation of FEAT-041.
const S = {
  // positions page
  posRow: (contract: string) => `[data-testid="position-row"][data-contract="${contract}"]`,
  positionsAsOf: 'positions-as-of',
  positionRow: 'position-row',
  posAssign: 'position-assign',
  posContracts: 'position-cell-contracts',
  posPending: 'position-expiry-pending',
  posDteToday: 'position-cell-dte-today',
  // assignment dialog
  assignModal: 'assign-modal',
  assignContract: 'assign-contract',
  assignDirection: 'assign-direction',
  assignStrike: 'assign-strike',
  assignMultiplier: 'assign-multiplier',
  assignPositionContracts: 'assign-position-contracts',
  assignFrozen: 'assign-frozen',
  assignUnderlyingShares: 'assign-underlying-shares', // "当前持股 300 股 IXQZ"
  assignContracts: 'assign-contracts',
  assignContractsOption: (n: number) => `assign-contracts-option-${n}`,
  assignDate: 'assign-date',
  assignFee: 'assign-fee',
  assignNote: 'assign-note',
  assignExplanation: 'assign-explanation',
  assignSubmit: 'assign-submit',
  assignCancel: 'assign-cancel',
  assignError: 'assign-error',
  assignResult: 'assign-result',
  assignResultTime: 'assign-result-time',
  assignResultContracts: 'assign-result-contracts',
  assignResultLots: 'assign-result-lots',
  assignResultPremium: 'assign-result-premium',
  assignResultShares: 'assign-result-shares',
  assignResultSaleAmount: 'assign-result-sale-amount', // 行权金额
  assignResultFee: 'assign-result-fee',
  assignResultProceeds: 'assign-result-proceeds', // 卖出实收
  assignResultSoldLots: 'assign-result-sold-lots', // 交出批次表（行 = sold-lot-row）
  assignResultSoldCost: 'assign-result-sold-cost', // 交出成本
  assignResultStockPnl: 'assign-result-stock-pnl', // 股票盈亏
  assignResultCashChange: 'assign-result-cash-change',
  assignResultPnl: 'assign-result-pnl',
  assignDone: 'assign-done',
  coveredLotRow: 'covered-lot-row',
  coveredLotCells: ['covered-lot-time', 'covered-lot-action', 'covered-lot-contracts', 'covered-lot-net-amount'],
  soldLotRow: 'sold-lot-row',
  soldLotCells: ['sold-lot-source', 'sold-lot-time', 'sold-lot-shares', 'sold-lot-cost'],
  // trade list
  tradeRow: (id: number) => `[data-testid="trade-row"][data-trade-id="${id}"]`,
  tradeRowAny: 'trade-row',
  tradeTotal: 'trade-list-total',
  tradeCells: {
    time: 'trade-cell-time',
    action: 'trade-cell-action',
    contract: 'trade-cell-contract',
    contracts: 'trade-cell-contracts',
    net: 'trade-cell-net-amount',
    frozen: 'trade-cell-frozen',
    note: 'trade-cell-note',
  },
  tradeAssignmentText: 'trade-cell-assignment',
  tradeEdit: 'trade-edit',
  tradeDelete: 'trade-delete',
  tradeHistory: 'trade-history',
  tradeDeleteModal: 'trade-delete-modal',
  tradeDeleteSummary: 'trade-delete-summary',
  tradeDeleteConfirm: 'trade-delete-confirm',
  tradeEditModal: 'trade-edit-modal',
  tradeEditNet: 'trade-edit-net-amount',
  tradeEditSubmit: 'trade-edit-submit',
  // closed trades
  closedRow: (type: 'OPTION' | 'STOCK', id: number) =>
    `[data-testid="closed-row"][data-record-type="${type}"][data-trade-id="${id}"]`,
  closedRowAny: 'closed-row',
  closedCount: 'closed-count',
  closedTotal: 'closed-pnl-total',
  closedAssignmentPageNote: 'closed-assignment-note',
  closedCells: {
    time: 'closed-cell-time',
    type: 'closed-cell-type',
    contract: 'closed-cell-contract',
    action: 'closed-cell-action',
    direction: 'closed-cell-direction',
    contracts: 'closed-cell-contracts',
    openingNet: 'closed-cell-opening-net',
    openingPer: 'closed-cell-opening-net-per',
    closeNet: 'closed-cell-close-net',
    closePer: 'closed-cell-close-net-per',
    pnl: 'closed-cell-pnl',
  },
  closedAssignmentText: 'closed-cell-assignment',
  closedAssignmentLink: 'closed-stock-assignment-link', // stock row "来自被指派 #4"
  closedCallAssignmentNote: 'closed-call-assignment-note',
  // stock page / stock positions
  stockListPage: 'stock-list-page',
  stockRow: (id: number) => `[data-testid="stock-row"][data-stock-trade-id="${id}"]`,
  stockRowAny: 'stock-row',
  stockCells: {
    time: 'stock-cell-time',
    type: 'stock-cell-type',
    symbol: 'stock-cell-symbol',
    shares: 'stock-cell-shares',
    amount: 'stock-cell-amount',
    price: 'stock-cell-price',
    pnl: 'stock-cell-pnl',
  },
  stockAssignmentLink: 'stock-assignment-link',
  stockAssignSellHint: 'stock-assign-buy-hint', // reused for ASSIGN_SELL rows
  stockEdit: 'stock-edit',
  stockDelete: 'stock-delete',
  stockHistory: 'stock-history',
  stockDeleteModal: 'stock-delete-modal',
  stockDeleteConfirm: 'stock-delete-confirm',
  stockDeleteCancel: 'stock-delete-cancel',
  stockDeleteError: 'stock-delete-error',
  stockFilterType: (t: string) => `stock-filter-type-${t}`,
  stockFilterSubmit: 'stock-filter-submit',
  stockPositionRowAny: 'stock-position-row',
  stockPositionCells: ['stock-position-symbol', 'stock-position-shares', 'stock-position-total-cost', 'stock-position-average-cost'],
  // pnl summary / dashboard
  pnlTotal: 'pnl-total',
  pnlSymbolRow: 'pnl-symbol-row',
  pnlSymbolCells: ['pnl-symbol-link', 'pnl-symbol-count', 'pnl-symbol-option', 'pnl-symbol-stock', 'pnl-symbol-pnl'],
  dashRealizedTotal: 'dashboard-realized-total',
  dashRealizedCount: 'dashboard-realized-count',
  // audit
  auditRow: 'audit-row',
  auditEmpty: 'audit-empty',
  auditOperation: 'audit-cell-operation',
  auditSummary: 'audit-cell-summary',
  auditSideEffectLine: 'audit-cell-side-effects-line',
  // navigation / accounts
  nav: (page: 'home' | 'positions' | 'trades' | 'closed' | 'pnl-summary' | 'cash' | 'stocks' | 'audit') => `nav-${page}`,
  accountSwitcher: 'account-switcher',
  accountOption: (id: number) => `account-option-${id}`,
  accountCurrent: 'account-current',
} as const

/** Page text of the requirement (FEAT-041 "页面与交互"). */
const TEXT = {
  explanation:
    '按行权价按 FIFO 交出股票；卖出实收 = 行权价 × 股数 + 权利金 − 指派费，期权已实现盈亏记 0.00（暂定口径）。持股不足时请先录入股票。',
  feeHint: '从卖出实收中扣',
  stockHint: '由被指派生成，在交易列表删除',
}

// ------------------------------------------------------------------------------------------------ dates & data

const BACKEND = BACKEND_URL
/** "Today" (FEAT-009, Q126: expirations relative to it must be trading days): fixed, backend and browser at 10:00 New York. */
const T = '2026-11-23'
/** Scenario C expiration: always 3 days in the past. */
const EXP = addDays(T, -3)
const at = (date: string, hm = '10:00') => `${date}T${hm}`
const C9 = `IXQZ ${EXP} 9 C`
const C10 = `IXQZ ${EXP} 10 C`

type StockType = 'BUY' | 'SELL' | 'TRANSFER_IN'

async function apiStock(type: StockType, symbol: string, shares: number, amount: string, time: string): Promise<number> {
  const r = await fetch(`${BACKEND}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, symbol, shares, amount, ...(type === 'TRANSFER_IN' ? { tradeDate: time } : { tradedAtLocal: time }), note: null }),
  })
  if (r.status !== 201) throw new Error(`seed stock ${type} ${symbol} failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

/** Scenario C: 7,520.00 / 0.00 / 7,520.00; IXQZ 300 shares 2,550.00. */
async function scenarioC(): Promise<void> {
  await apiTransfer('DEPOSIT', '10000.00', at(addDays(EXP, -18)))
  await apiStock('BUY', 'IXQZ', 150, '1200.00', at(addDays(EXP, -17))) // S1 #1
  await apiStock('BUY', 'IXQZ', 150, '1350.00', at(addDays(EXP, -16))) // S2 #2
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: EXP, strike: '9', type: 'CALL', contracts: 2, netAmount: '60.00', time: at(addDays(EXP, -15)) }) // C1 #1
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: EXP, strike: '9', type: 'CALL', netAmount: '25.00', time: at(addDays(EXP, -14)) }) // C2 #2
  await apiTrade({ action: 'BTO', symbol: 'IXQZ', expiration: EXP, strike: '10', type: 'CALL', netAmount: '15.00', time: at(addDays(EXP, -14), '11:00') }) // L1 #3
}

async function apiAssign(body: {
  symbol: string
  expirationDate: string
  strike: string
  contracts: number
  assignmentDate: string
  assignmentFee?: string
}): Promise<{ id: number; assignment: { stockTradeId: number } }> {
  const r = await fetch(`${BACKEND}/api/option-trades/assignments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ optionType: 'CALL', assignmentFee: null, note: null, ...body }),
  })
  if (r.status !== 201) throw new Error(`assign failed ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

const assignAll = () => apiAssign({ symbol: 'IXQZ', expirationDate: EXP, strike: '9', contracts: 3, assignmentDate: EXP })

async function apiCreateAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

/**
 * AC4's "小账户": deposit 1,000.00; STO 1 IXQZ EXP 9 C 20.00; no stock. Leaves the API account on it.
 * FEAT-039: a call without shares can only be a pre-launch (LEGACY) batch (Q79), seeded via the e2e-only endpoint.
 */
async function smallAccount(): Promise<number> {
  const acc = await apiCreateAccount('小账户')
  setApiAccount(acc)
  await apiTransfer('DEPOSIT', '1000.00', at(addDays(EXP, -18)))
  await apiLegacyTrade({ symbol: 'IXQZ', expiration: EXP, strike: '9', netAmount: '20.00', time: at(addDays(EXP, -15)) })
  return acc
}

async function apiGet<R>(path: string): Promise<R> {
  return (await (await fetch(`${BACKEND}${path}`)).json()) as R
}

async function colorOf(l: Locator): Promise<'green' | 'red' | 'other'> {
  const colors = await l.evaluate((el) => [el, ...Array.from(el.querySelectorAll('*'))].map((e) => getComputedStyle(e).color))
  for (const c of colors) {
    const [r, g] = (c.match(/\d+/g) ?? []).map(Number)
    if (r === undefined || g === undefined) continue
    if (g > r + 40) return 'green'
    if (r > g + 40) return 'red'
  }
  return 'other'
}

async function rowTexts(rows: Locator, ids: readonly string[], sep = ' '): Promise<string[]> {
  return rows.evaluateAll(
    (rs, [cells, s]) =>
      rs.map((r) =>
        (cells as string[]).map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? '').join(s as string),
      ),
    [ids, sep] as const,
  )
}

async function cellMap(rows: Locator, cells: Record<string, string>): Promise<Record<string, string>[]> {
  return rows.evaluateAll(
    (rs, c) =>
      rs.map((r) =>
        Object.fromEntries(
          Object.entries(c).map(([k, id]) => [k, r.querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? '']),
        ),
      ),
    cells,
  )
}

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  posRow(contract: string): Locator {
    return this.page.locator(S.posRow(contract))
  }

  tradeRow(id: number): Locator {
    return this.page.locator(S.tradeRow(id))
  }

  stockRow(id: number): Locator {
    return this.page.locator(S.stockRow(id))
  }

  closedRow(type: 'OPTION' | 'STOCK', id: number): Locator {
    return this.page.locator(S.closedRow(type, id))
  }

  async openPositions(): Promise<void> {
    await this.page.goto('/positions')
    await expect(this.id(S.positionsAsOf)).toBeVisible()
  }

  async positionContracts(): Promise<string[]> {
    return this.id(S.positionRow).evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract') ?? ''))
  }

  async rowButtons(contract: string): Promise<string[]> {
    return this.posRow(contract)
      .getByRole('button')
      .evaluateAll((bs) => bs.map((b) => (b.textContent ?? '').replace(/\s+/g, '')).filter((t) => ['平仓', '作废', '被指派'].includes(t)))
  }

  async openAssign(contract: string): Promise<Locator> {
    await this.posRow(contract).getByTestId(S.posAssign).click()
    const d = this.dialog(S.assignModal)
    await expect(d).toBeVisible()
    return d
  }

  async contractOptions(d: Locator): Promise<string[]> {
    await d.getByTestId(S.assignContracts).click()
    const opts = this.page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    await expect(opts.first()).toBeVisible()
    // the dropdown may still be animating in (or a previous one out): read once the option list is non-empty
    let texts: string[] = []
    await expect
      .poll(async () => {
        texts = (await opts.allTextContents()).map((t) => t.trim())
        return texts.length
      })
      .toBeGreaterThan(0)
    await this.page.keyboard.press('Escape')
    return texts
  }

  async selectedContracts(d: Locator): Promise<string> {
    const shown = d.locator('[data-testid^="assign-contracts-option-"]')
    return (await shown.count()) === 0 ? '' : ((await shown.first().textContent()) ?? '').trim()
  }

  async chooseContracts(d: Locator, n: number): Promise<void> {
    await d.getByTestId(S.assignContracts).click()
    await this.page.locator('.ant-select-dropdown:visible').getByTestId(S.assignContractsOption(n)).click()
    await expect.poll(() => this.selectedContracts(d)).toMatch(new RegExp(`^${n} 张`))
  }

  async submitAssign(d: Locator): Promise<void> {
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignResult)).toBeVisible()
  }

  fieldError(scope: Locator, testId: string): Locator {
    return scope
      .locator('.ant-form-item')
      .filter({ has: this.page.getByTestId(testId) })
      .locator('.ant-form-item-explain-error:not(.ant-form-show-help-item-leave)')
  }

  async coveredLots(scope: Locator): Promise<string[]> {
    return rowTexts(scope.getByTestId(S.coveredLotRow), S.coveredLotCells)
  }

  async soldLots(scope: Locator): Promise<string[]> {
    return rowTexts(scope.getByTestId(S.soldLotRow), S.soldLotCells, ' | ')
  }

  async tradeCells(): Promise<Record<string, string>[]> {
    return cellMap(this.id(S.tradeRowAny), S.tradeCells)
  }

  async closedCells(): Promise<Record<string, string>[]> {
    return cellMap(this.id(S.closedRowAny), S.closedCells)
  }

  async stockCells(): Promise<Record<string, string>[]> {
    return cellMap(this.id(S.stockRowAny), S.stockCells)
  }

  async stockPositionRows(): Promise<string[]> {
    return rowTexts(this.id(S.stockPositionRowAny), S.stockPositionCells)
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id(S.accountSwitcher).click()
    await this.id(S.accountOption(id)).click()
    await expect(this.id(S.accountCurrent)).toHaveText(name)
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

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-041 卖出 call 被指派', () => {
  test.beforeEach(async ({ page }) => {
    await fixNow(page, phaseInstant(T, 'closable'))
  })

  test('AC1 按钮与对话框：9 C 作废+被指派、10 C 只有作废；选项 2 张 / 3 张、默认 3、日期默认到期日；当前持股 300 股 IXQZ；不显示冻结；说明文字', async ({ page }) => {
    await scenarioC()
    const ui = new Ui(page)
    await ui.openPositions()
    await expect.poll(() => ui.rowButtons(C9)).toEqual(['作废', '被指派'])
    await expect.poll(() => ui.rowButtons(C10)).toEqual(['作废'])
    await expect(ui.posRow(C10).getByTestId(S.posAssign)).toHaveCount(0)

    const d = await ui.openAssign(C9)
    await expect(d.getByTestId(S.assignContract)).toHaveText(C9)
    await expect(d.getByTestId(S.assignDirection)).toHaveText('空头')
    await expect(d.getByTestId(S.assignStrike)).toHaveText('9')
    await expect(d.getByTestId(S.assignPositionContracts)).toHaveText('3 张')
    await expect(d.getByTestId(S.assignUnderlyingShares)).toContainText('300 股 IXQZ')
    await expect(d).toContainText('当前持股')
    await expect(d.getByTestId(S.assignFrozen)).toHaveCount(0)
    expect(await ui.contractOptions(d)).toEqual(['2 张（200 股）', '3 张（300 股）'])
    expect(await ui.selectedContracts(d)).toBe('3 张（300 股）')
    await expect(d.getByTestId(S.assignDate)).toHaveValue(EXP)
    await expect(d.getByTestId(S.assignDate)).toHaveAttribute('max', EXP)
    await expect(d.getByTestId(S.assignFee)).toHaveValue('0.00')
    await expect(d).toContainText(TEXT.feeHint)
    await expect(d.getByTestId(S.assignExplanation)).toHaveText(TEXT.explanation)
    await d.getByTestId(S.assignCancel).click()
    await expect(ui.dialog(S.assignModal)).toBeHidden()
  })

  for (const p of PHASES) {
  test(`AC1 / Q95：今天到期的空头 call 任何时刻都有被指派；到期待确认期间它是唯一按钮 [固定时钟 2026-11-23 ${p}]`, async ({ page }) => {
    await fixNow(page, phaseInstant(T, p))
    await apiTransfer('DEPOSIT', '5000.00', at(addDays(T, -5)))
    await apiStock('BUY', 'IXQZ', 100, '800.00', at(addDays(T, -4)))
    await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: T, strike: '9', type: 'CALL', netAmount: '30.00', time: at(addDays(T, -2)) })
    const ui = new Ui(page)
    const phase = p
    await ui.openPositions()
    const call = `IXQZ ${T} 9 C`
    await expect(ui.posRow(call).getByTestId(S.posDteToday)).toHaveText('今天到期')
    if (phase === 'closable') {
      await expect.poll(() => ui.rowButtons(call)).toEqual(['平仓', '被指派'])
    } else if (phase === 'pending') {
      await expect(ui.posRow(call).getByTestId(S.posPending)).toHaveText('到期待确认')
      await expect.poll(() => ui.rowButtons(call)).toEqual(['被指派'])
    } else {
      await expect.poll(() => ui.rowButtons(call)).toEqual(['作废', '被指派'])
    }
  })
  }

  test('AC2 全部被指派（对话框）：结果全部后端值；不刷新页面，持仓/资金/交易列表/股票页/已平仓 2 行/盈亏汇总/首页 +235.00（2 笔）都更新；审计无记录', async ({ page }) => {
    await scenarioC()
    const ui = new Ui(page)
    await page.goto('/')
    await ui.markNoReload()
    await ui.id(S.nav('positions')).click()
    await expect(ui.posRow(C9)).toBeVisible()
    await expect.poll(() => ui.stockPositionRows()).toEqual(['IXQZ 300 2,550.00 8.5000'])

    const d = await ui.openAssign(C9)
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultTime)).toHaveText(`${EXP} 16:00（美东）`)
    await expect(d.getByTestId(S.assignResultContracts)).toHaveText('3 张')
    expect(await ui.coveredLots(d.getByTestId(S.assignResultLots))).toEqual([
      `${addDays(EXP, -15)} 10:00 STO（卖出开仓） 2 张 +60.00`,
      `${addDays(EXP, -14)} 10:00 STO（卖出开仓） 1 张 +25.00`,
    ])
    await expect(d.getByTestId(S.assignResultPremium)).toHaveText('85.00')
    await expect(d.getByTestId(S.assignResultShares)).toHaveText('300 股 IXQZ')
    await expect(d.getByTestId(S.assignResultSaleAmount)).toHaveText('2,700.00')
    await expect(d.getByTestId(S.assignResultFee)).toHaveText('0.00')
    await expect(d.getByTestId(S.assignResultProceeds)).toHaveText('2,785.00')
    expect(await ui.soldLots(d.getByTestId(S.assignResultSoldLots))).toEqual([
      `买入 #1 | ${addDays(EXP, -17)} 10:00 | 150 股 | 1,200.00`,
      `买入 #2 | ${addDays(EXP, -16)} 10:00 | 150 股 | 1,350.00`,
    ])
    await expect(d.getByTestId(S.assignResultSoldCost)).toHaveText('2,550.00')
    await expect(d.getByTestId(S.assignResultStockPnl)).toHaveText('+235.00')
    expect(await colorOf(d.getByTestId(S.assignResultStockPnl))).toBe('green')
    await expect(d.getByTestId(S.assignResultCashChange)).toHaveText('+2,700.00')
    await expect(d.getByTestId(S.assignResultPnl)).toHaveText('0.00')
    await d.getByTestId(S.assignDone).click()
    await expect(ui.dialog(S.assignModal)).toBeHidden()
    expect((await apiGet<{ action: string }>('/api/option-trades/4')).action).toBe('ASG')

    // positions: 9 C gone, no IXQZ stock
    await expect.poll(() => ui.positionContracts()).toEqual([C10])
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('10,220.00', '0.00', '10,220.00')
    await expect.poll(async () => (await cash.rows())[0]).toEqual({
      time: `${EXP} 16:00`, type: '被指派', amount: '+2,700.00', frozen: '—', balance: '10,220.00', note: `被指派 3 张 ${C9}，卖出 300 股 IXQZ`,
    })

    await ui.id(S.nav('trades')).click()
    await expect.poll(async () => (await ui.tradeCells())[0]).toEqual({
      time: `${EXP} 16:00`, action: '被指派', contract: C9, contracts: '3', net: '0.00', frozen: '—', note: '—',
    })
    const row = ui.tradeRow(4)
    await expect(row.getByTestId(S.tradeAssignmentText)).toHaveText('卖出 300 股 IXQZ，现金 +2,700.00')
    await expect(row.getByTestId(S.tradeDelete)).toBeVisible()
    await expect(row.getByTestId(S.tradeEdit)).toHaveCount(0)
    await expect(row.getByTestId(S.tradeHistory)).toHaveCount(0)

    await ui.id(S.nav('stocks')).click()
    await expect(ui.id(S.stockListPage)).toBeVisible()
    await expect.poll(async () => (await ui.stockCells())[0]).toEqual({
      time: `${EXP} 16:00`, type: '指派卖出', symbol: 'IXQZ', shares: '300', amount: '实收 2,785.00', price: '每股 9.2833', pnl: '+235.00',
    })
    const srow = ui.stockRow(3)
    await expect(srow.getByTestId(S.stockAssignmentLink)).toHaveText('来自被指派 #4')
    expect(await colorOf(srow.getByTestId(S.stockCells.pnl))).toBe('green')
    await expect(srow.getByTestId(S.stockEdit)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockDelete)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockHistory)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockAssignSellHint)).toHaveText(TEXT.stockHint)

    await ui.id(S.nav('closed')).click()
    await expect(ui.id(S.closedCount)).toHaveText('共 2 笔平仓')
    await expect(ui.id(S.closedTotal)).toHaveText('+235.00')
    await expect(ui.id(S.closedAssignmentPageNote)).toBeVisible()
    await expect(ui.id(S.closedCallAssignmentNote)).toContainText('暂定')
    expect(await ui.closedCells()).toEqual([
      { time: `${EXP} 16:00`, type: '股票', contract: 'IXQZ', action: '指派卖出', direction: '', contracts: '300 股', openingNet: `${MINUS}2,550.00`, openingPer: '每股 8.5000', closeNet: '+2,785.00', closePer: '每股 9.2833', pnl: '+235.00' },
      { time: `${EXP} 16:00`, type: '期权', contract: C9, action: '被指派', direction: '空头', contracts: '3', openingNet: '+85.00', openingPer: '每张 +28.33', closeNet: '0.00', closePer: '每张 0.00', pnl: '0.00' },
    ])
    await expect(ui.closedRow('STOCK', 3).getByTestId(S.closedAssignmentLink)).toHaveText('来自被指派 #4')
    await expect(ui.closedRow('OPTION', 4).getByTestId(S.closedAssignmentText)).toHaveText('权利金已计入股票卖出（300 股 IXQZ，实收 2,785.00）')

    await ui.id(S.nav('pnl-summary')).click()
    await expect(ui.id(S.pnlTotal)).toHaveText('+235.00')
    await expect.poll(() => rowTexts(ui.id(S.pnlSymbolRow), S.pnlSymbolCells)).toEqual(['IXQZ 2 0.00 +235.00 +235.00'])

    await ui.id(S.nav('home')).click()
    await expect(ui.id(S.dashRealizedTotal)).toHaveText('+235.00')
    await expect(ui.id(S.dashRealizedCount)).toHaveText('2')

    await ui.id(S.nav('audit')).click()
    await expect(ui.id(S.auditEmpty)).toBeVisible()
    await expect(ui.id(S.auditRow)).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC3 提前指派 2 张 + 指派费 5.00：实收 1,855.00、交出 S1 150 + S2 50、+205.00、现金 +1,795.00；剩 C2 1 张、IXQZ 100 股 900.00；交易列表小字带指派费', async ({ page }) => {
    await scenarioC()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openAssign(C9)
    await ui.chooseContracts(d, 2)
    const early = addDays(EXP, -2)
    await d.getByTestId(S.assignDate).fill(early)
    await d.getByTestId(S.assignFee).fill('5.00')
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultTime)).toHaveText(`${early} 16:00（美东）`)
    await expect(d.getByTestId(S.assignResultPremium)).toHaveText('60.00')
    await expect(d.getByTestId(S.assignResultSaleAmount)).toHaveText('1,800.00')
    await expect(d.getByTestId(S.assignResultFee)).toHaveText('5.00')
    await expect(d.getByTestId(S.assignResultProceeds)).toHaveText('1,855.00')
    expect(await ui.soldLots(d.getByTestId(S.assignResultSoldLots))).toEqual([
      `买入 #1 | ${addDays(EXP, -17)} 10:00 | 150 股 | 1,200.00`,
      `买入 #2 | ${addDays(EXP, -16)} 10:00 | 50 股 | 450.00`,
    ])
    await expect(d.getByTestId(S.assignResultSoldCost)).toHaveText('1,650.00')
    await expect(d.getByTestId(S.assignResultStockPnl)).toHaveText('+205.00')
    await expect(d.getByTestId(S.assignResultCashChange)).toHaveText('+1,795.00')
    await d.getByTestId(S.assignDone).click()

    await expect(ui.posRow(C9).getByTestId(S.posContracts)).toHaveText('1')
    await expect.poll(() => ui.stockPositionRows()).toEqual(['IXQZ 100 900.00 9.0000'])
    await page.goto('/trades')
    await expect(ui.tradeRow(4).getByTestId(S.tradeAssignmentText)).toHaveText('卖出 200 股 IXQZ，现金 +1,795.00，指派费 5.00')
    await page.goto('/cash')
    await new CashPage(page).expectSummary('9,315.00', '0.00', '9,315.00')
  })

  test('AC4 持股不足（Q75）：小账户对话框显示当前持股 0 股；提交 → 后端原文显示在对话框、不关闭；补录更早的转入后再提交成功：实收 920.00、+120.00、现金 +900.00', async ({ page }) => {
    const acc = await smallAccount()
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(acc, '小账户')
    const d = await ui.openAssign(C9)
    await expect(d.getByTestId(S.assignUnderlyingShares)).toContainText('0 股 IXQZ')
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(`持股不足：被指派需要 100 股 IXQZ，${EXP} 16:00 时持有 0 股。请先录入股票（转入或更早的买入）`)
    await expect(d.getByTestId(S.assignError)).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(d).toBeVisible()
    await expect(d.getByTestId(S.assignDate)).toHaveValue(EXP)

    // a buy after the assignment time does not count
    const late = await apiStock('BUY', 'IXQZ', 100, '900.00', at(addDays(EXP, 1)))
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(`持股不足：被指派需要 100 股 IXQZ，${EXP} 16:00 时持有 0 股。请先录入股票（转入或更早的买入）`)
    const del = await fetch(`${BACKEND}/api/stock-trades/${late}`, { method: 'DELETE' })
    expect(del.status).toBe(204)
    await apiStock('TRANSFER_IN', 'IXQZ', 100, '800.00', addDays(EXP, -18))

    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultProceeds)).toHaveText('920.00')
    await expect(d.getByTestId(S.assignResultSoldCost)).toHaveText('800.00')
    await expect(d.getByTestId(S.assignResultStockPnl)).toHaveText('+120.00')
    await expect(d.getByTestId(S.assignResultCashChange)).toHaveText('+900.00')
    await d.getByTestId(S.assignDone).click()
    await expect(ui.posRow(C9)).toHaveCount(0)
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)
  })

  test('AC5 使之后卖出不成立：已有 EXP+1 卖出 100 股；被指派 3 张 → 「补录后 … 的卖出 IXQZ 100 股将超过当时持股 0 股」；改 2 张成功', async ({ page }) => {
    // FEAT-039: with C1 + C2 covering all 300 shares (CHECKED) this later sale would be refused, so as in scenario C but
    // the sale is recorded first and C1 / C2 are pre-launch LEGACY batches (at most C1 is auto-included: same numbers).
    // Ids as scenario C: C1, C2, L1 = option #1–#3; the sale = stock #3.
    await apiTransfer('DEPOSIT', '10000.00', at(addDays(EXP, -18)))
    await apiStock('BUY', 'IXQZ', 150, '1200.00', at(addDays(EXP, -17))) // S1 #1
    await apiStock('BUY', 'IXQZ', 150, '1350.00', at(addDays(EXP, -16))) // S2 #2
    await apiStock('SELL', 'IXQZ', 100, '1000.00', at(addDays(EXP, 1)))
    await apiLegacyTrade({ symbol: 'IXQZ', expiration: EXP, strike: '9', contracts: 2, netAmount: '60.00', time: at(addDays(EXP, -15)) }) // C1 #1
    await apiLegacyTrade({ symbol: 'IXQZ', expiration: EXP, strike: '9', netAmount: '25.00', time: at(addDays(EXP, -14)) }) // C2 #2
    await apiTrade({ action: 'BTO', symbol: 'IXQZ', expiration: EXP, strike: '10', type: 'CALL', netAmount: '15.00', time: at(addDays(EXP, -14), '11:00') }) // L1 #3
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openAssign(C9)
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(`补录后 ${addDays(EXP, 1)} 10:00 的卖出 IXQZ 100 股将超过当时持股 0 股`)
    await expect(d.getByTestId(S.assignError)).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(d).toBeVisible()
    await ui.chooseContracts(d, 2)
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultSoldCost)).toHaveText('1,650.00')
    await d.getByTestId(S.assignDone).click()
    await expect(ui.posRow(C9).getByTestId(S.posContracts)).toHaveText('1')
  })

  test('AC6 交易列表删除被指派：不刷新页面恢复现金 7,520.00、9 C 3 张、IXQZ 300 股 2,550.00；股票页 / 已平仓消失；审计摘要「被指派 … 9 C 3 张（卖出 300 股 IXQZ）」', async ({ page }) => {
    await scenarioC()
    await assignAll() // #4, stock #3
    const ui = new Ui(page)
    await page.goto('/trades')
    await expect(ui.tradeRow(4)).toBeVisible()
    await ui.markNoReload()
    await ui.tradeRow(4).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await expect(d).toContainText('删除被指派 #4')
    await expect(d.getByTestId(S.tradeDeleteSummary)).toContainText('卖出 300 股 IXQZ')
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(ui.dialog(S.tradeDeleteModal)).toBeHidden()
    await expect(ui.tradeRow(4)).toHaveCount(0)

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('7,520.00', '0.00', '7,520.00')
    await expect(cash.ledgerRows).toHaveCount(6)

    await ui.id(S.nav('positions')).click()
    await expect(ui.posRow(C9).getByTestId(S.posContracts)).toHaveText('3')
    await expect.poll(() => ui.stockPositionRows()).toEqual(['IXQZ 300 2,550.00 8.5000'])

    await ui.id(S.nav('stocks')).click()
    await expect(ui.id(S.stockRowAny)).toHaveCount(2)
    await expect(ui.stockRow(3)).toHaveCount(0)
    await ui.id(S.nav('closed')).click()
    await expect(ui.id(S.closedRowAny)).toHaveCount(0)

    await ui.id(S.nav('audit')).click()
    await expect(ui.id(S.auditRow)).toHaveCount(1)
    const first = ui.id(S.auditRow).first()
    await expect(first.getByTestId(S.auditOperation)).toHaveText('删除')
    await expect(first.getByTestId(S.auditSummary)).toHaveText(`被指派 ${C9} 3 张（卖出 300 股 IXQZ）`)
    await ui.expectNoReload()
  })

  test('AC6 股票页：指派卖出行无修改 / 删除、"来自被指派 #4" 跳到交易列表；类型筛选"指派卖出"', async ({ page }) => {
    await scenarioC()
    await assignAll()
    const ui = new Ui(page)
    await page.goto('/stocks')
    await expect(ui.id(S.stockListPage)).toBeVisible()
    await ui.stockRow(3).getByTestId(S.stockAssignmentLink).click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect(ui.tradeRow(4)).toBeVisible()

    await page.goto('/stocks')
    await ui.id(S.stockFilterType('assign-sell')).check()
    await ui.id(S.stockFilterSubmit).click()
    await expect(page).toHaveURL(/type=ASSIGN_SELL/)
    await expect.poll(async () => (await ui.stockCells()).map((c) => c.type)).toEqual(['指派卖出'])
  })

  test('AC7 修改 C2 净额 25 → 30：股票页实收 2,790.00、+240.00；现金 10,225.00；审计连带变化「被指派 #4 股票卖出实收 2,785.00 → 2,790.00」', async ({ page }) => {
    await scenarioC()
    await assignAll()
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(2).getByTestId(S.tradeEdit).click()
    const d = ui.dialog(S.tradeEditModal)
    await d.getByTestId(S.tradeEditNet).fill('30.00')
    await d.getByTestId(S.tradeEditSubmit).click()
    await expect(ui.dialog(S.tradeEditModal)).toBeHidden()

    await ui.id(S.nav('stocks')).click()
    await expect(ui.stockRow(3).getByTestId(S.stockCells.amount)).toHaveText('实收 2,790.00')
    await expect(ui.stockRow(3).getByTestId(S.stockCells.pnl)).toHaveText('+240.00')
    await ui.id(S.nav('cash')).click()
    await new CashPage(page).expectSummary('10,225.00', '0.00', '10,225.00')
    await ui.id(S.nav('closed')).click()
    await expect(ui.closedRow('OPTION', 4).getByTestId(S.closedAssignmentText)).toHaveText('权利金已计入股票卖出（300 股 IXQZ，实收 2,790.00）')
    await ui.id(S.nav('audit')).click()
    const first = ui.id(S.auditRow).first()
    await expect(first.getByTestId(S.auditOperation)).toHaveText('修改')
    await expect(first.getByTestId(S.auditSideEffectLine)).toContainText(['被指派 #4 股票卖出实收 2,785.00 → 2,790.00'])
  })

  test('AC7 股票页删除 S2 被拒：「删除后 … 的被指派 3 张 … 需要 300 股，当时持股 150 股，请先删除该被指派」显示在删除对话框、不关闭', async ({ page }) => {
    await scenarioC()
    await assignAll()
    const ui = new Ui(page)
    await page.goto('/stocks')
    await ui.stockRow(2).getByTestId(S.stockDelete).click()
    const d = ui.dialog(S.stockDeleteModal)
    await d.getByTestId(S.stockDeleteConfirm).click()
    await expect(d.getByTestId(S.stockDeleteError)).toHaveText(
      `删除后 ${EXP} 16:00 的被指派 3 张 ${C9} 需要 300 股，当时持股 150 股，请先删除该被指派`,
    )
    await expect(d).toBeVisible()
    await d.getByTestId(S.stockDeleteCancel).click()
    await expect(ui.stockRow(2)).toBeVisible()
    expect((await apiGet<{ totalItems: number }>('/api/audit-log')).totalItems).toBe(0)
  })

  test('AC8 multiplier 1、行权价 12.345：选项 "1 张（1 股）"；行权金额 12.34（向下取整）、实收 12.84、交出 10.00、+2.84；流水说明带 multiplier', async ({ page }) => {
    const acc = await apiCreateAccount('测试乘数账户')
    setApiAccount(acc)
    await apiTransfer('DEPOSIT', '100.00', at(addDays(EXP, -18)))
    await apiStock('TRANSFER_IN', 'QFZ', 2, '20.00', addDays(EXP, -18))
    await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'CALL', netAmount: '0.50', time: at(addDays(EXP, -17)), multiplier: 1 })
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(acc, '测试乘数账户')
    const contract = `QFZ ${EXP} 12.345 C`
    const d = await ui.openAssign(contract)
    await expect(d.getByTestId(S.assignMultiplier)).toHaveText('1')
    await expect(d.getByTestId(S.assignUnderlyingShares)).toContainText('2 股 QFZ')
    expect(await ui.contractOptions(d)).toEqual(['1 张（1 股）'])
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultSaleAmount)).toHaveText('12.34')
    await expect(d.getByTestId(S.assignResultProceeds)).toHaveText('12.84')
    await expect(d.getByTestId(S.assignResultSoldCost)).toHaveText('10.00')
    await expect(d.getByTestId(S.assignResultStockPnl)).toHaveText('+2.84')
    await expect(d.getByTestId(S.assignResultCashChange)).toHaveText('+12.34')
    await d.getByTestId(S.assignDone).click()
    await expect.poll(() => ui.stockPositionRows()).toEqual(['QFZ 1 10.00 10.0000'])
    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('112.84', '0.00', '112.84')
    await expect.poll(async () => (await cash.rows())[0]?.note).toBe(`被指派 1 张 ${contract}（multiplier 1），卖出 1 股 QFZ`)
  })

  test('AC9 按账户隔离：默认账户被指派后切到小账户 9 C 仍 1 张、当前持股 0 股、提交被拒；切回默认账户无 9 C', async ({ page }) => {
    await scenarioC()
    await assignAll()
    const small = await smallAccount()
    setApiAccount(1)
    const ui = new Ui(page)
    await ui.openPositions()
    await expect.poll(() => ui.positionContracts()).toEqual([C10])

    await ui.switchAccount(small, '小账户')
    await expect(ui.posRow(C9).getByTestId(S.posContracts)).toHaveText('1')
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)
    const d = await ui.openAssign(C9)
    await expect(d.getByTestId(S.assignUnderlyingShares)).toContainText('0 股 IXQZ')
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(d).toBeVisible()
  })

  test('AC9 格式校验：指派费 −1.00 / 1.234、日期为空、备注 501 字 → 字段下显示错误、不发请求、什么都不保存', async ({ page }) => {
    await scenarioC()
    const ui = new Ui(page)
    await ui.openPositions()
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/option-trades/assignments')) reqs.push(r.url())
    })
    const d = await ui.openAssign(C9)
    const feeError = ui.fieldError(d, S.assignFee)
    for (const [fee, msg] of [
      ['-1.00', '指派费不能为负数'],
      ['1.234', '指派费最多 2 位小数'],
    ] as const) {
      await d.getByTestId(S.assignFee).fill(fee)
      await d.getByTestId(S.assignSubmit).click()
      await expect(feeError).toHaveText(msg)
    }
    await d.getByTestId(S.assignFee).fill('0.00')
    await d.getByTestId(S.assignDate).fill('')
    await d.getByTestId(S.assignSubmit).click()
    await expect(ui.fieldError(d, S.assignDate)).toHaveText('指派日期不能为空')
    await d.getByTestId(S.assignDate).fill(EXP)
    await d.getByTestId(S.assignNote).fill('x'.repeat(501))
    await d.getByTestId(S.assignSubmit).click()
    await expect(ui.fieldError(d, S.assignNote)).toHaveText('备注最多 500 个字符')
    await expect(d.getByTestId(S.assignResult)).toHaveCount(0)
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=ASG')).totalItems).toBe(0)
  })

  test('后端错误：被指派接口返回 500 时对话框显示错误、不关闭、什么都不保存', async ({ page }) => {
    await scenarioC()
    const ui = new Ui(page)
    await ui.openPositions()
    const path = '**/api/option-trades/assignments'
    await page.route(path, (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误', status: 500, path: '/api/option-trades/assignments', timestamp: '2026-11-13T15:00:00Z', details: [] }),
      }),
    )
    const d = await ui.openAssign(C9)
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toContainText('服务器内部错误')
    await expect(d).toBeVisible()
    await page.unroute(path)
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=ASG')).totalItems).toBe(0)
    await expect(ui.posRow(C9).getByTestId(S.posContracts)).toHaveText('3')
  })
})
