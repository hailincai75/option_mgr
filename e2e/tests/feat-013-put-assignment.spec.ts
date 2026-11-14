import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiLegacyTrade, apiTrade } from '../support/trade'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { addDays, nyToday, stablePhase } from '../support/ny'

/**
 * FEAT-013 卖出 put 被指派 E2E (real clock). As the requirement says, the dates are relative to today's New York date
 * T: the scenario P expiration EXP = T−3 (already expired, "已过期 3 天"), the not-yet-expired QRTV put expires at
 * T+25, early assignments use dates before EXP, the Q94 / Q95 checks use T and pick their assertions by the current
 * New York time. Amounts are those of the requirement's scenario P. Fictional tickers only.
 * DB reset per test: scenario P = deposit #1, P1–P5 = trades #1–#5, the first assignment = trade #6 and stock
 * record #1.
 */

// ------------------------------------------------------------------------------------------------ selectors
// All data-testids used by this spec, in one place (frontend testids for FEAT-013 may still change).
const S = {
  // positions page
  posRow: (contract: string) => `[data-testid="position-row"][data-contract="${contract}"]`,
  positionsAsOf: 'positions-as-of',
  positionRow: 'position-row',
  posAssign: 'position-assign',
  posClose: 'position-close',
  posExpire: 'position-expire',
  posPending: 'position-expiry-pending',
  posDte: 'position-cell-dte',
  posDteToday: 'position-cell-dte-today',
  posContracts: 'position-cell-contracts',
  posFrozen: 'position-cell-frozen',
  posLotCount: 'position-cell-lot-count',
  // assignment dialog
  assignModal: 'assign-modal',
  assignContract: 'assign-contract',
  assignDirection: 'assign-direction',
  assignStrike: 'assign-strike',
  assignMultiplier: 'assign-multiplier',
  assignPositionContracts: 'assign-position-contracts',
  assignFrozen: 'assign-frozen',
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
  assignResultPurchase: 'assign-result-purchase',
  assignResultFee: 'assign-result-fee',
  assignResultStockCost: 'assign-result-stock-cost',
  assignResultCostPerShare: 'assign-result-cost-per-share',
  assignResultCashChange: 'assign-result-cash-change',
  assignResultFrozenChange: 'assign-result-frozen-change',
  assignResultPnl: 'assign-result-pnl',
  assignDone: 'assign-done',
  coveredLotRow: 'covered-lot-row',
  coveredLotCells: ['covered-lot-time', 'covered-lot-action', 'covered-lot-contracts', 'covered-lot-net-amount'],
  // expire dialog (FEAT-012)
  expireModal: 'expire-modal',
  expireSubmit: 'expire-submit',
  expireResult: 'expire-result',
  expireResultPnl: 'expire-result-pnl',
  expireDone: 'expire-done',
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
  tradeFilterAction: (a: string) => `trade-filter-action-${a}`,
  tradeFilterSubmit: 'trade-filter-submit',
  tradeDeleteModal: 'trade-delete-modal',
  tradeDeleteSummary: 'trade-delete-summary',
  tradeDeleteWarning: 'trade-delete-warning',
  tradeDeleteConfirm: 'trade-delete-confirm',
  tradeDeleteCancel: 'trade-delete-cancel',
  tradeDeleteError: 'trade-delete-error',
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
  closedFilterAction: (a: string) => `closed-filter-action-${a}`,
  closedFilterSubmit: 'closed-filter-submit',
  closedExpand: 'closed-expand',
  closedLotRow: 'closed-lot-row',
  closedLotCells: ['closed-lot-time', 'closed-lot-action', 'closed-lot-contracts', 'closed-lot-net-amount'],
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
  stockAssignBuyHint: 'stock-assign-buy-hint',
  stockEdit: 'stock-edit',
  stockDelete: 'stock-delete',
  stockHistory: 'stock-history',
  stockFilterType: (t: string) => `stock-filter-type-${t}`,
  stockFilterSubmit: 'stock-filter-submit',
  stockPositionRow: (symbol: string) => `[data-testid="stock-position-row"][data-symbol="${symbol}"]`,
  stockPositionRowAny: 'stock-position-row',
  stockPositionCells: ['stock-position-symbol', 'stock-position-shares', 'stock-position-total-cost', 'stock-position-average-cost'],
  stockPositionExpand: 'stock-position-expand',
  stockLotRow: 'stock-lot-row',
  stockLotCells: ['stock-lot-source', 'stock-lot-time', 'stock-lot-original-shares', 'stock-lot-remaining-shares', 'stock-lot-remaining-cost'],
  // pnl summary / dashboard
  pnlTotal: 'pnl-total',
  pnlSymbolRow: 'pnl-symbol-row',
  pnlSymbolCells: ['pnl-symbol-link', 'pnl-symbol-count', 'pnl-symbol-option', 'pnl-symbol-stock', 'pnl-symbol-pnl'],
  dashRealizedTotal: 'dashboard-realized-total',
  dashRealizedCount: 'dashboard-realized-count',
  dashMonthTotal: 'dashboard-month-total',
  dashMonthCount: 'dashboard-month-count',
  dashExpiredCount: 'dashboard-expired-count',
  dashFrozenTotal: 'dashboard-frozen-total',
  dashStockPositionCount: 'dashboard-stock-position-count',
  // audit
  auditRow: 'audit-row',
  auditEmpty: 'audit-empty',
  auditOperation: 'audit-cell-operation',
  auditObject: 'audit-cell-object',
  auditSummary: 'audit-cell-summary',
  auditChanges: 'audit-cell-changes',
  auditSideEffectLine: 'audit-cell-side-effects-line',
  auditExpand: 'audit-expand',
  auditDetail: 'audit-detail',
  auditBefore: (field: string) => `audit-before-${field}`,
  // cash ledger
  ledgerTradesLink: 'cash-ledger-trades-link',
  ledgerEdit: 'cash-ledger-edit',
  ledgerDelete: 'cash-ledger-delete',
  ledgerHistory: 'cash-ledger-history',
  // navigation / accounts
  nav: (page: 'home' | 'positions' | 'trades' | 'closed' | 'pnl-summary' | 'cash' | 'stocks' | 'audit') => `nav-${page}`,
  accountSwitcher: 'account-switcher',
  accountOption: (id: number) => `account-option-${id}`,
  accountCurrent: 'account-current',
} as const

/** Page text of the requirement (FEAT-013 "页面与交互"). */
const TEXT = {
  assignButton: /被\s*指\s*派/,
  explanation:
    '被指派后按行权价用该批次冻结的现金买入股票；股票成本 = 行权价 × 股数 − 被指派批次的权利金 + 指派费，期权一侧已实现盈亏记 0.00（权利金在股票卖出时体现）。记错可以在交易列表中删除。',
  deleteWarning: '删除后期权持仓、冻结、现金恢复，生成的股票记录一起删除，会写入审计记录。',
  closedPageNote: '被指派（卖出 put）的期权已实现盈亏为 0.00，权利金计入股票成本，股票卖出时体现在股票盈亏中。',
  stockHint: '由被指派生成，在交易列表删除',
}

// ------------------------------------------------------------------------------------------------ dates & data

const BACKEND = BACKEND_URL
const T = nyToday()
/** Scenario P expiration: always 3 days in the past. */
const EXP = addDays(T, -3)
/** Expiration of the not-yet-expired short put P3. */
const FAR = addDays(T, 25)
const at = (date: string, hm = '10:00') => `${date}T${hm}`
const XYZ = `XYZ ${EXP} 45 P`
const QRTV_P = `QRTV ${FAR} 30 P`
const QRTV_C = `QRTV ${EXP} 25 C`
const IXQZ = `IXQZ ${EXP} 8 C`
/** "This month" dashboard figures count records dated EXP only while T is in the same month. */
const sameMonth = () => EXP.slice(0, 7) === T.slice(0, 7)

/** Scenario P: 20,437.00 / 16,500.00 / 3,937.00. */
async function scenarioP(): Promise<void> {
  await apiTransfer('DEPOSIT', '20000.00', at(addDays(EXP, -18)))
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', contracts: 2, netAmount: '240.00', time: at(addDays(EXP, -17)) })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '130.00', time: at(addDays(EXP, -15)) })
  await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: FAR, strike: '30', type: 'PUT', netAmount: '85.00', time: at(addDays(EXP, -14)) })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '25', type: 'CALL', netAmount: '40.00', time: at(addDays(EXP, -14), '11:00') })
  // FEAT-039: a short call without shares can only be a pre-launch (LEGACY) batch (Q79)
  await apiLegacyTrade({ symbol: 'IXQZ', expiration: EXP, strike: '8', netAmount: '22.00', time: at(addDays(EXP, -14), '12:00') })
}

async function apiAssign(body: {
  symbol: string
  expirationDate: string
  strike: string
  contracts: number
  assignmentDate: string
  assignmentFee?: string
  note?: string | null
}): Promise<{ id: number; assignment: { stockTradeId: number } }> {
  const r = await fetch(`${BACKEND}/api/option-trades/assignments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ optionType: 'PUT', assignmentFee: null, note: null, ...body }),
  })
  if (r.status !== 201) throw new Error(`assign failed ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

const assignXyzAll = () => apiAssign({ symbol: 'XYZ', expirationDate: EXP, strike: '45', contracts: 3, assignmentDate: EXP })

async function apiExpire(symbol: string, expirationDate: string, strike: string, optionType: 'CALL' | 'PUT', contracts: number): Promise<{ id: number }> {
  const r = await fetch(`${BACKEND}/api/option-trades/expirations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol, expirationDate, strike, optionType, contracts }),
  })
  if (r.status !== 201) throw new Error(`expire failed ${r.status} ${await r.text()}`)
  return (await r.json()) as { id: number }
}

async function apiStockSell(symbol: string, shares: number, amount: string, tradedAtLocal: string): Promise<{ id: number }> {
  const r = await fetch(`${BACKEND}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'SELL', symbol, shares, amount, tradedAtLocal, note: null }),
  })
  if (r.status !== 201) throw new Error(`sell failed ${r.status} ${await r.text()}`)
  return (await r.json()) as { id: number }
}

async function apiCreateAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiGet<T>(path: string): Promise<T> {
  return (await (await fetch(`${BACKEND}${path}`)).json()) as T
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

/** Texts of the given testids inside each element matched by `rows`, joined by `sep`. */
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

  /** antd puts the modal testid on a zero-size root; the visible part is the dialog inside it. */
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

  /** Button texts of a position row, e.g. ["作废", "被指派"]. */
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

  /** Texts of the contracts choices in the open select dropdown. */
  async contractOptions(d: Locator): Promise<string[]> {
    await d.getByTestId(S.assignContracts).click()
    const opts = this.page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    await expect(opts.first()).toBeVisible()
    const texts = (await opts.allTextContents()).map((t) => t.trim())
    await this.page.keyboard.press('Escape')
    return texts
  }

  /** The selected choice: its option label is rendered inside the dialog (the dropdown lives in a body portal). */
  async selectedContracts(d: Locator): Promise<string> {
    const shown = d.locator('[data-testid^="assign-contracts-option-"]')
    return (await shown.count()) === 0 ? '' : ((await shown.first().textContent()) ?? '').trim()
  }

  /** True when the browser blocks the date (native `max` = backend's assignmentDateMax). */
  async dateOverMax(d: Locator): Promise<boolean> {
    return d.getByTestId(S.assignDate).evaluate((el) => (el as HTMLInputElement).validity.rangeOverflow)
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

  async lots(scope: Locator): Promise<string[]> {
    return rowTexts(scope.getByTestId(S.coveredLotRow), S.coveredLotCells)
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

test.describe('FEAT-013 卖出 put 被指派', () => {
  test('AC1 按钮：已过期 XYZ 作废+被指派、未到期 QRTV put 平仓+被指派、多头 call 只有作废、空头 call 作废+被指派（FEAT-041）；对话框只读信息、张数选项、默认日期与最晚日期、指派费 0.00', async ({ page }) => {
    await scenarioP()
    const ui = new Ui(page)
    await ui.openPositions()
    await expect.poll(() => ui.positionContracts()).toEqual([IXQZ, QRTV_C, XYZ, QRTV_P])

    await expect(ui.posRow(XYZ).getByTestId(S.posDte)).toHaveText('已过期 3 天')
    await expect.poll(() => ui.rowButtons(XYZ)).toEqual(['作废', '被指派'])
    await expect(ui.posRow(XYZ).getByTestId(S.posAssign)).toHaveText(TEXT.assignButton)
    await expect.poll(() => ui.rowButtons(QRTV_P)).toEqual(['平仓', '被指派'])
    await expect.poll(() => ui.rowButtons(QRTV_C)).toEqual(['作废'])
    await expect(ui.posRow(QRTV_C).getByTestId(S.posAssign)).toHaveCount(0)
    // FEAT-041: the short call is assignable as well
    await expect.poll(() => ui.rowButtons(IXQZ)).toEqual(['作废', '被指派'])

    const d = await ui.openAssign(XYZ)
    await expect(d.getByTestId(S.assignContract)).toHaveText(XYZ)
    await expect(d.getByTestId(S.assignDirection)).toHaveText('空头')
    await expect(d.getByTestId(S.assignStrike)).toHaveText('45')
    await expect(d.getByTestId(S.assignMultiplier)).toHaveText('100')
    await expect(d.getByTestId(S.assignPositionContracts)).toHaveText('3 张')
    await expect(d.getByTestId(S.assignFrozen)).toHaveText('13,500.00')
    expect(await ui.contractOptions(d)).toEqual(['2 张（200 股）', '3 张（300 股）'])
    expect(await ui.selectedContracts(d)).toBe('3 张（300 股）')
    await expect(d.getByTestId(S.assignDate)).toHaveValue(EXP)
    await expect(d.getByTestId(S.assignDate)).toHaveAttribute('max', EXP) // cannot pick EXP+1 or later
    await expect(d.getByTestId(S.assignFee)).toHaveValue('0.00')
    await expect(d.getByTestId(S.assignNote)).toHaveValue('')
    await expect(d.getByTestId(S.assignExplanation)).toHaveText(TEXT.explanation)
    await expect(d.getByTestId(S.assignSubmit)).toHaveText(/确认被指派/)
    await d.getByTestId(S.assignCancel).click()
    await expect(ui.dialog(S.assignModal)).toBeHidden()

    // not yet expired short put: default = latest = yesterday (before 16:00) or today (from 16:00)
    const d2 = await ui.openAssign(QRTV_P)
    const phase = await stablePhase()
    const latest = phase === 'closable' ? addDays(T, -1) : T
    await expect(d2.getByTestId(S.assignDate)).toHaveValue(latest)
    await expect(d2.getByTestId(S.assignDate)).toHaveAttribute('max', latest)
    expect(await ui.contractOptions(d2)).toEqual(['1 张（100 股）'])
  })

  test('AC1 / Q95（真实时钟）：今天到期的空头 put 任何时刻都有被指派；到期待确认期间它是唯一按钮；今天到期的多头 call 没有被指派', async ({ page }) => {
    await apiTransfer('DEPOSIT', '5000.00', at(addDays(T, -5)))
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: T, strike: '20', type: 'PUT', netAmount: '30.00', time: at(addDays(T, -2)) })
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: T, strike: '25', type: 'CALL', netAmount: '10.00', time: at(addDays(T, -2)) })
    const ui = new Ui(page)
    const phase = await stablePhase()
    await ui.openPositions()
    const put = `XYZ ${T} 20 P`
    const call = `QRTV ${T} 25 C`
    await expect(ui.posRow(put).getByTestId(S.posDteToday)).toHaveText('今天到期')
    if (phase === 'closable') {
      await expect.poll(() => ui.rowButtons(put)).toEqual(['平仓', '被指派'])
      await expect.poll(() => ui.rowButtons(call)).toEqual(['平仓'])
    } else if (phase === 'pending') {
      await expect(ui.posRow(put).getByTestId(S.posPending)).toHaveText('到期待确认')
      await expect.poll(() => ui.rowButtons(put)).toEqual(['被指派'])
      await expect.poll(() => ui.rowButtons(call)).toEqual([]) // the 批次 expand toggle is also a button
    } else {
      await expect.poll(() => ui.rowButtons(put)).toEqual(['作废', '被指派'])
      await expect.poll(() => ui.rowButtons(call)).toEqual(['作废'])
    }
    await expect(ui.posRow(call).getByTestId(S.posAssign)).toHaveCount(0)
  })

  test('AC2 到期被指派 3 张：结果全部后端值；不刷新页面，持仓/股票持仓/资金/交易列表/股票页/已平仓/盈亏汇总/首页都更新；审计无记录', async ({ page }) => {
    await scenarioP()
    const ui = new Ui(page)
    await page.goto('/')
    await ui.markNoReload()
    await expect(ui.id(S.dashExpiredCount)).toHaveText('3')
    await ui.id(S.nav('positions')).click()
    await expect(ui.posRow(XYZ)).toBeVisible()

    const d = await ui.openAssign(XYZ)
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultTime)).toHaveText(`${EXP} 16:00（美东）`)
    await expect(d.getByTestId(S.assignResultContracts)).toHaveText('3 张')
    expect(await ui.lots(d.getByTestId(S.assignResultLots))).toEqual([
      `${addDays(EXP, -17)} 10:00 STO（卖出开仓） 2 张 +240.00`,
      `${addDays(EXP, -15)} 10:00 STO（卖出开仓） 1 张 +130.00`,
    ])
    await expect(d.getByTestId(S.assignResultPremium)).toHaveText('370.00')
    await expect(d.getByTestId(S.assignResultShares)).toHaveText('300 股 XYZ')
    await expect(d.getByTestId(S.assignResultPurchase)).toHaveText('13,500.00')
    await expect(d.getByTestId(S.assignResultFee)).toHaveText('0.00')
    await expect(d.getByTestId(S.assignResultStockCost)).toHaveText('13,130.00')
    await expect(d.getByTestId(S.assignResultCostPerShare)).toHaveText('43.7667')
    await expect(d.getByTestId(S.assignResultCashChange)).toHaveText(`${MINUS}13,500.00`)
    await expect(d.getByTestId(S.assignResultFrozenChange)).toHaveText(`${MINUS}13,500.00`)
    await expect(d.getByTestId(S.assignResultPnl)).toHaveText('0.00')
    await d.getByTestId(S.assignDone).click()
    await expect(ui.dialog(S.assignModal)).toBeHidden()
    const stored = await apiGet<{ tradedAt: string; action: string }>('/api/option-trades/6')
    expect(stored.action).toBe('ASG')

    // positions: option XYZ gone; stock XYZ 300 / 13,130.00 / 43.7667, lot 指派买入 EXP 16:00
    await expect.poll(() => ui.positionContracts()).toEqual([IXQZ, QRTV_C, QRTV_P])
    await expect.poll(() => ui.stockPositionRows()).toEqual(['XYZ 300 13,130.00 43.7667'])
    await page.locator(S.stockPositionRow('XYZ')).getByTestId(S.stockPositionExpand).click()
    await expect.poll(() => rowTexts(ui.id(S.stockLotRow), S.stockLotCells, ' | ')).toEqual([`指派买入 #1 | ${EXP} 16:00 | 300 | 300 | 13,130.00`])

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('6,937.00', '3,000.00', '3,937.00')
    await expect(cash.ledgerRows).toHaveCount(7)
    await expect.poll(async () => (await cash.rows())[0]).toEqual({
      time: `${EXP} 16:00`, type: '被指派', amount: `${MINUS}13,500.00`, frozen: `${MINUS}13,500.00`, balance: '6,937.00', note: `被指派 3 张 ${XYZ}，买入 300 股 XYZ`,
    })
    const ledgerTop = cash.ledgerRows.first()
    await expect(ledgerTop.getByTestId(S.ledgerTradesLink)).toBeVisible()
    await expect(ledgerTop.getByTestId(S.ledgerEdit)).toHaveCount(0)
    await expect(ledgerTop.getByTestId(S.ledgerDelete)).toHaveCount(0)
    await expect(ledgerTop.getByTestId(S.ledgerHistory)).toHaveCount(0)

    await ui.id(S.nav('trades')).click()
    await expect(ui.id(S.tradeTotal)).toHaveText('共 6 条')
    await expect.poll(async () => (await ui.tradeCells())[0]).toEqual({
      time: `${EXP} 16:00`, action: '被指派', contract: XYZ, contracts: '3', net: '0.00', frozen: `${MINUS}13,500.00`, note: '—',
    })
    const row = ui.tradeRow(6)
    await expect(row.getByTestId(S.tradeAssignmentText)).toHaveText(`买入 300 股 XYZ，现金 ${MINUS}13,500.00`)
    expect(await colorOf(row.getByTestId(S.tradeCells.net))).toBe('other')
    await expect(row.getByTestId(S.tradeDelete)).toBeVisible()
    await expect(row.getByTestId(S.tradeEdit)).toHaveCount(0)
    await expect(row.getByTestId(S.tradeHistory)).toHaveCount(0)
    // 被指派 has its own color, different from 到期作废 / opening rows
    const tagColor = async (l: Locator) => l.evaluate((el) => getComputedStyle(el).backgroundColor)
    expect(await tagColor(row.getByTestId(S.tradeCells.action))).not.toBe(await tagColor(ui.tradeRow(1).getByTestId(S.tradeCells.action)))
    // direction filter 被指派 → only this row; URL parameter like the other directions
    await ui.id(S.tradeFilterAction('asg')).check()
    await ui.id(S.tradeFilterSubmit).click()
    await expect.poll(() => new URL(page.url()).searchParams.getAll('action').join(',')).toBe('ASG')
    await expect(ui.id(S.tradeTotal)).toHaveText('共 1 条')
    await expect.poll(async () => (await ui.tradeCells()).map((c) => c.action)).toEqual(['被指派'])

    await ui.id(S.nav('stocks')).click()
    await expect(ui.id(S.stockListPage)).toBeVisible()
    await expect.poll(() => ui.stockCells()).toEqual([
      { time: `${EXP} 16:00`, type: '指派买入', symbol: 'XYZ', shares: '300', amount: '成本 13,130.00', price: '每股 43.7667', pnl: '—' },
    ])
    const srow = ui.stockRow(1)
    await expect(srow.getByTestId(S.stockAssignmentLink)).toHaveText('来自被指派 #6')
    expect(await colorOf(srow.getByTestId(S.stockCells.amount))).toBe('other')
    await expect(srow.getByTestId(S.stockEdit)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockDelete)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockHistory)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockAssignBuyHint)).toHaveText(TEXT.stockHint)

    await ui.id(S.nav('closed')).click()
    await expect(ui.id(S.closedCount)).toHaveText('共 1 笔平仓')
    await expect(ui.id(S.closedTotal)).toHaveText('0.00')
    await expect(ui.id(S.closedAssignmentPageNote)).toHaveText(TEXT.closedPageNote)
    expect(await ui.closedCells()).toEqual([
      { time: `${EXP} 16:00`, type: '期权', contract: XYZ, action: '被指派', direction: '空头', contracts: '3', openingNet: '+370.00', openingPer: '每张 +123.33', closeNet: '0.00', closePer: '每张 0.00', pnl: '0.00' },
    ])
    const crow = ui.closedRow('OPTION', 6)
    expect(await colorOf(crow.getByTestId(S.closedCells.pnl))).toBe('other')
    await expect(crow.getByTestId(S.closedAssignmentText)).toHaveText('权利金已计入股票成本（300 股 XYZ，成本 13,130.00）')
    await crow.getByTestId(S.closedExpand).click()
    const lots = crow.locator('xpath=following-sibling::tr[1]').getByTestId(S.closedLotRow)
    await expect(lots).toHaveCount(2)
    expect(await rowTexts(lots, S.closedLotCells)).toEqual([
      `${addDays(EXP, -17)} 10:00 STO（卖出开仓） 2 张 +240.00`,
      `${addDays(EXP, -15)} 10:00 STO（卖出开仓） 1 张 +130.00`,
    ])

    await ui.id(S.nav('pnl-summary')).click()
    await expect(ui.id(S.pnlTotal)).toHaveText('0.00')
    await expect.poll(() => rowTexts(ui.id(S.pnlSymbolRow), S.pnlSymbolCells)).toEqual(['XYZ 1 0.00 — 0.00'])

    await ui.id(S.nav('home')).click()
    await expect(ui.id(S.dashRealizedTotal)).toHaveText('0.00')
    await expect(ui.id(S.dashRealizedCount)).toHaveText('1')
    await expect(ui.id(S.dashMonthCount)).toHaveText(sameMonth() ? '1' : '0')
    await expect(ui.id(S.dashExpiredCount)).toHaveText('2')
    await expect(ui.id(S.dashFrozenTotal)).toHaveText('3,000.00')
    await expect(ui.id(S.dashStockPositionCount)).toHaveText('1')

    await ui.id(S.nav('audit')).click()
    await expect(ui.id(S.auditEmpty)).toBeVisible()
    await expect(ui.id(S.auditRow)).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC3 之后在 EXP 16:00 卖出 100 股（+23.33）：已平仓 2 行股票在上、合计 +23.33；盈亏汇总 XYZ 期权 0.00 / 股票 +23.33；股票持仓 200 股 8,753.33', async ({ page }) => {
    await scenarioP()
    await assignXyzAll() // #6, stock #1
    const sell = await apiStockSell('XYZ', 100, '4400.00', at(EXP, '16:00')) // stock #2
    const ui = new Ui(page)
    await page.goto('/closed')
    await expect(ui.id(S.closedCount)).toHaveText('共 2 笔平仓')
    await expect(ui.id(S.closedTotal)).toHaveText('+23.33')
    await expect.poll(async () => (await ui.closedCells()).map((c) => `${c.type} ${c.action} ${c.pnl}`)).toEqual(['股票 卖出 +23.33', '期权 被指派 0.00'])
    await expect(ui.closedRow('STOCK', sell.id)).toBeVisible()

    await page.goto('/pnl-summary')
    await expect(ui.id(S.pnlTotal)).toHaveText('+23.33')
    await expect.poll(() => rowTexts(ui.id(S.pnlSymbolRow), S.pnlSymbolCells)).toEqual(['XYZ 2 0.00 +23.33 +23.33'])

    await ui.openPositions()
    await expect.poll(() => ui.stockPositionRows()).toEqual(['XYZ 200 8,753.33 43.7667'])
    await page.goto('/cash')
    await new CashPage(page).expectSummary('11,337.00', '3,000.00', '8,337.00')
  })

  test('AC4 提前指派（对话框）：1 张非整笔、日期晚于到期日 → 后端原文显示在对话框、不关闭、保留内容；2 张 + 提前日期 + 费 5.00 成功；剩余 1 张作废 +130.00', async ({ page }) => {
    await scenarioP()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openAssign(XYZ)

    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/option-trades/assignments')) reqs.push(r.url())
    })
    await ui.chooseContracts(d, 2)
    // only 2 / 3 are offered; 1 contract can only come from a direct request -> covered by the backend system test
    // a date after the expiration cannot be picked: the input's max (= backend's latest date) blocks the submit
    await d.getByTestId(S.assignDate).fill(addDays(EXP, 1))
    await d.getByTestId(S.assignFee).fill('5.00')
    await d.getByTestId(S.assignNote).fill('提前指派')
    await d.getByTestId(S.assignSubmit).click()
    expect(await ui.dateOverMax(d)).toBe(true)
    await expect(d.getByTestId(S.assignResult)).toHaveCount(0)
    expect(reqs).toHaveLength(0)

    // a date before the first lot was opened: backend rejects (no position), shown verbatim, values kept
    await d.getByTestId(S.assignDate).fill(addDays(EXP, -18))
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(`${addDays(EXP, -18)} 16:00 时该合约（${XYZ}）没有持仓可被指派`)
    await expect(d.getByTestId(S.assignError)).toHaveAttribute('data-error-code', 'NO_POSITION_TO_ASSIGN')
    await expect(d).toBeVisible()
    expect(await ui.selectedContracts(d)).toBe('2 张（200 股）')
    await expect(d.getByTestId(S.assignDate)).toHaveValue(addDays(EXP, -18))
    await expect(d.getByTestId(S.assignFee)).toHaveValue('5.00')
    await expect(d.getByTestId(S.assignNote)).toHaveValue('提前指派')

    const early = addDays(EXP, -2)
    await d.getByTestId(S.assignDate).fill(early)
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultTime)).toHaveText(`${early} 16:00（美东）`)
    await expect(d.getByTestId(S.assignResultContracts)).toHaveText('2 张')
    expect(await ui.lots(d.getByTestId(S.assignResultLots))).toEqual([`${addDays(EXP, -17)} 10:00 STO（卖出开仓） 2 张 +240.00`])
    await expect(d.getByTestId(S.assignResultShares)).toHaveText('200 股 XYZ')
    await expect(d.getByTestId(S.assignResultFee)).toHaveText('5.00')
    await expect(d.getByTestId(S.assignResultStockCost)).toHaveText('8,765.00')
    await expect(d.getByTestId(S.assignResultCostPerShare)).toHaveText('43.8250')
    await expect(d.getByTestId(S.assignResultCashChange)).toHaveText(`${MINUS}9,005.00`)
    await expect(d.getByTestId(S.assignResultFrozenChange)).toHaveText(`${MINUS}9,000.00`)
    await d.getByTestId(S.assignDone).click()

    await expect(ui.posRow(XYZ).getByTestId(S.posContracts)).toHaveText('1')
    await expect.poll(() => ui.rowButtons(XYZ)).toEqual(['作废', '被指派'])

    // trade list small text shows the fee
    await page.goto('/trades')
    await expect(ui.tradeRow(6).getByTestId(S.tradeAssignmentText)).toHaveText(`买入 200 股 XYZ，现金 ${MINUS}9,005.00，指派费 5.00`)
    await expect(ui.tradeRow(6).getByTestId(S.tradeCells.note)).toHaveText('提前指派')
    await page.goto('/cash')
    const cash = new CashPage(page)
    await cash.expectSummary('11,432.00', '7,500.00', '3,932.00')

    // expire the remaining lot P2
    await ui.openPositions()
    await ui.posRow(XYZ).getByTestId(S.posExpire).click()
    const e = ui.dialog(S.expireModal)
    await e.getByTestId(S.expireSubmit).click()
    await expect(e.getByTestId(S.expireResultPnl)).toHaveText('+130.00')
    await e.getByTestId(S.expireDone).click()
    await page.goto('/cash')
    await cash.expectSummary('11,432.00', '3,000.00', '8,432.00')
    await page.goto('/closed?symbol=XYZ')
    await expect.poll(async () => (await ui.closedCells()).map((c) => `${c.action} ${c.pnl} ${c.time}`)).toEqual([
      `到期作废 +130.00 ${EXP} 16:00`,
      `被指派 0.00 ${early} 16:00`,
    ])
  })

  test('AC4 / Q94（真实时钟）：今天未到美东 16:00 时最晚可选昨天，填今天无法提交（不发请求）；16:00 之后填今天成功', async ({ page }) => {
    await scenarioP()
    const ui = new Ui(page)
    const phase = await stablePhase()
    await ui.openPositions()
    const d = await ui.openAssign(QRTV_P)
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/option-trades/assignments')) reqs.push(r.url())
    })
    await d.getByTestId(S.assignDate).fill(T)
    if (phase === 'closable') {
      // the backend messages for today-before-16:00 / future dates are covered by the system test (AC4, Q94)
      await expect(d.getByTestId(S.assignDate)).toHaveAttribute('max', addDays(T, -1))
      await d.getByTestId(S.assignSubmit).click()
      expect(await ui.dateOverMax(d)).toBe(true)
      await expect(d.getByTestId(S.assignResult)).toHaveCount(0)
      await expect(d).toBeVisible()
      expect(reqs).toHaveLength(0)
    } else {
      await ui.submitAssign(d)
      await expect(d.getByTestId(S.assignResultTime)).toHaveText(`${T} 16:00（美东）`)
      await expect(d.getByTestId(S.assignResultStockCost)).toHaveText('2,915.00')
    }
  })

  test('AC5 先指派 2 张再作废：交易列表作废在上；删除被指派被拒，原文显示在删除对话框、不关闭；先删作废再删被指派都成功', async ({ page }) => {
    await scenarioP()
    await apiAssign({ symbol: 'XYZ', expirationDate: EXP, strike: '45', contracts: 2, assignmentDate: EXP }) // #6
    await apiExpire('XYZ', EXP, '45', 'PUT', 1) // #7
    const ui = new Ui(page)
    await page.goto('/trades')
    await expect.poll(async () => (await ui.tradeCells()).slice(0, 2).map((c) => c.action)).toEqual(['到期作废', '被指派'])

    await ui.tradeRow(6).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveText(
      `删除后该合约（${XYZ}）在 ${EXP} 16:00 的到期作废 1 张将不是整笔批次（最早一笔是 2 张，可为 2 张或 3 张），请先删除该到期作废`,
    )
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveAttribute('data-error-code', 'CLOSE_NOT_WHOLE_LOTS')
    await expect(d).toBeVisible()
    await d.getByTestId(S.tradeDeleteCancel).click()

    await ui.tradeRow(7).getByTestId(S.tradeDelete).click()
    await ui.dialog(S.tradeDeleteModal).getByTestId(S.tradeDeleteConfirm).click()
    await expect(ui.tradeRow(7)).toHaveCount(0)
    await ui.tradeRow(6).getByTestId(S.tradeDelete).click()
    await ui.dialog(S.tradeDeleteModal).getByTestId(S.tradeDeleteConfirm).click()
    await expect(ui.tradeRow(6)).toHaveCount(0)
    await ui.id(S.nav('cash')).click()
    await new CashPage(page).expectSummary('20,437.00', '16,500.00', '3,937.00')
    await ui.id(S.nav('positions')).click()
    await expect(ui.posRow(XYZ).getByTestId(S.posContracts)).toHaveText('3')
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)
  })

  test('AC5 先作废后发现被指派：对话框显示"…没有持仓可被指派（已于 … 到期作废；如果实际是被指派，请先删除该到期作废）"（对话框在作废前打开）', async ({ page }) => {
    await scenarioP()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openAssign(XYZ)
    await ui.chooseContracts(d, 2)
    // meanwhile the position is expired elsewhere
    await apiExpire('XYZ', EXP, '45', 'PUT', 3)
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(
      `${EXP} 16:00 时该合约（${XYZ}）没有持仓可被指派（已于 ${EXP} 16:00 到期作废；如果实际是被指派，请先删除该到期作废）`,
    )
    await expect(d.getByTestId(S.assignError)).toHaveAttribute('data-error-code', 'NO_POSITION_TO_ASSIGN')
    await expect(d).toBeVisible()
    await d.getByTestId(S.assignDate).fill(addDays(EXP, -1))
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(
      `补录后该合约（${XYZ}）在 ${EXP} 16:00 的到期作废 3 张将超过当时持仓 1 张，请先删除该到期作废`,
    )
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=ASG')).totalItems).toBe(0)
  })

  test('AC6 交易列表删除被指派：确认框摘要与提示；不刷新页面恢复持仓/冻结/现金、股票消失；审计删除记录；再次被指派新行没有"历史"', async ({ page }) => {
    await scenarioP()
    await assignXyzAll() // #6, stock #1
    const ui = new Ui(page)
    await page.goto('/trades')
    await expect(ui.tradeRow(6)).toBeVisible()
    await ui.markNoReload()
    await ui.tradeRow(6).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await expect(d).toBeVisible()
    await expect(d).toContainText('删除被指派 #6')
    await expect(d.getByTestId(S.tradeDeleteSummary)).toHaveText(`被指派 3 张 ${XYZ}，${EXP} 16:00，买入 300 股 XYZ`)
    await expect(d.getByTestId(S.tradeDeleteWarning)).toHaveText(TEXT.deleteWarning)
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(ui.dialog(S.tradeDeleteModal)).toBeHidden()
    await expect(ui.tradeRow(6)).toHaveCount(0)
    await expect(ui.id(S.tradeTotal)).toHaveText('共 5 条')

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('20,437.00', '16,500.00', '3,937.00')
    await expect(cash.ledgerRows).toHaveCount(6)

    await ui.id(S.nav('positions')).click()
    const row = ui.posRow(XYZ)
    await expect(row.getByTestId(S.posContracts)).toHaveText('3')
    await expect(row.getByTestId(S.posLotCount)).toHaveText('2')
    await expect(row.getByTestId(S.posFrozen)).toHaveText('13,500.00')
    await expect(row.getByTestId(S.posDte)).toHaveText('已过期 3 天')
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)

    await ui.id(S.nav('stocks')).click()
    await expect(ui.id(S.stockRowAny)).toHaveCount(0)
    await ui.id(S.nav('closed')).click()
    await expect(ui.id(S.closedRowAny)).toHaveCount(0)

    await ui.id(S.nav('audit')).click()
    await expect(ui.id(S.auditRow)).toHaveCount(1)
    const first = ui.id(S.auditRow).first()
    await expect(first.getByTestId(S.auditOperation)).toHaveText('删除')
    await expect(first.getByTestId(S.auditObject)).toContainText('期权交易 #6')
    await expect(first.getByTestId(S.auditSummary)).toHaveText(`被指派 ${XYZ} 3 张（买入 300 股 XYZ）`)
    await first.getByTestId(S.auditExpand).click()
    const detail = ui.id(S.auditDetail).first()
    await expect(detail.getByTestId(S.auditBefore('action'))).toContainText('被指派')
    await expect(detail.getByTestId(S.auditBefore('contracts'))).toContainText('3')
    await expect(detail.getByTestId(S.auditBefore('multiplier'))).toContainText('100')
    await expect(detail.getByTestId(S.auditBefore('tradedAtLocal'))).toContainText(`${EXP} 16:00`)
    await expect(detail.getByTestId(S.auditBefore('netAmount'))).toContainText('0.00')
    await expect(detail.getByTestId(S.auditBefore('cashChange'))).toContainText(`${MINUS}13,500.00`)
    await expect(detail.getByTestId(S.auditBefore('frozenChange'))).toContainText(`${MINUS}13,500.00`)
    await expect(detail.getByTestId(S.auditBefore('assignmentDate'))).toContainText(EXP)
    await expect(detail.getByTestId(S.auditBefore('assignmentFee'))).toContainText('0.00')
    await expect(detail.getByTestId(S.auditBefore('shares'))).toContainText('300')
    await expect(detail.getByTestId(S.auditBefore('purchaseAmount'))).toContainText('13,500.00')
    await expect(detail.getByTestId(S.auditBefore('stockCost'))).toContainText('13,130.00')
    await expect(detail.getByTestId(S.auditBefore('stockTradeId'))).toContainText('1')

    // re-assign: new record #7, no 历史
    await ui.id(S.nav('positions')).click()
    const d2 = await ui.openAssign(XYZ)
    await ui.submitAssign(d2)
    await expect(d2.getByTestId(S.assignResultStockCost)).toHaveText('13,130.00')
    await expect(d2.getByTestId(S.assignResultCashChange)).toHaveText(`${MINUS}13,500.00`)
    await d2.getByTestId(S.assignDone).click()
    await ui.id(S.nav('trades')).click()
    await expect(ui.tradeRow(7).getByTestId(S.tradeCells.action)).toHaveText('被指派')
    await expect(ui.tradeRow(7).getByTestId(S.tradeHistory)).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC6 已卖出 16:00 的 100 股后删除被指派被拒（持股不足原文），对话框不关闭；股票页"来自被指派 #6"跳到交易列表能找到该记录', async ({ page }) => {
    await scenarioP()
    await assignXyzAll()
    await apiStockSell('XYZ', 100, '4400.00', at(EXP, '16:00'))
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(6).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveText(`删除后 ${EXP} 16:00 的卖出 XYZ 100 股将超过当时持股 0 股`)
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(d).toBeVisible()
    await d.getByTestId(S.tradeDeleteCancel).click()
    expect((await apiGet<{ totalItems: number }>('/api/audit-log')).totalItems).toBe(0)

    await page.goto('/stocks')
    await expect(ui.id(S.stockListPage)).toBeVisible()
    await ui.stockRow(1).getByTestId(S.stockAssignmentLink).click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect(ui.tradeRow(6)).toBeVisible()

    // stock type filter 指派买入
    await page.goto('/stocks')
    await ui.id(S.stockFilterType('assign-buy')).check()
    await ui.id(S.stockFilterSubmit).click()
    await expect(page).toHaveURL(/type=ASSIGN_BUY/)
    await expect.poll(async () => (await ui.stockCells()).map((c) => c.type)).toEqual(['指派买入'])
  })

  test('AC7 修改 P2 净额 130 → 135：股票页成本 13,125.00 / 每股 43.7500；现金 6,942.00 / 3,000.00 / 3,942.00；审计连带变化含股票成本', async ({ page }) => {
    await scenarioP()
    await assignXyzAll()
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(2).getByTestId(S.tradeEdit).click()
    const d = ui.dialog(S.tradeEditModal)
    await d.getByTestId(S.tradeEditNet).fill('135.00')
    await d.getByTestId(S.tradeEditSubmit).click()
    await expect(ui.dialog(S.tradeEditModal)).toBeHidden()

    await ui.id(S.nav('stocks')).click()
    await expect(ui.stockRow(1).getByTestId(S.stockCells.amount)).toHaveText('成本 13,125.00')
    await expect(ui.stockRow(1).getByTestId(S.stockCells.price)).toHaveText('每股 43.7500')
    await ui.id(S.nav('cash')).click()
    await new CashPage(page).expectSummary('6,942.00', '3,000.00', '3,942.00')
    await ui.id(S.nav('closed')).click()
    await expect(ui.closedRow('OPTION', 6).getByTestId(S.closedAssignmentText)).toHaveText('权利金已计入股票成本（300 股 XYZ，成本 13,125.00）')
    await ui.id(S.nav('audit')).click()
    const first = ui.id(S.auditRow).first()
    await expect(first.getByTestId(S.auditOperation)).toHaveText('修改')
    await expect(first.getByTestId(S.auditObject)).toContainText('期权交易 #2')
    await expect(first.getByTestId(S.auditSideEffectLine)).toHaveText(['被指派 #6 股票成本 13,130.00 → 13,125.00'])
  })

  test('AC8 可用 0.00 的账户：指派费 5.00 → "可用现金不足…"显示在对话框、保留内容；改为 0.00 成功 0.00 / 0.00 / 0.00、股票 QRTV 100 股 4,450.00', async ({ page }) => {
    const acc = await apiCreateAccount('测试指派账户')
    setApiAccount(acc)
    await apiTransfer('DEPOSIT', '4500.00', at(addDays(EXP, -18)))
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: EXP, strike: '45', type: 'PUT', netAmount: '50.00', time: at(addDays(EXP, -17)) })
    await apiTransfer('WITHDRAWAL', '50.00', at(addDays(EXP, -10)))
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(acc, '测试指派账户')
    const contract = `QRTV ${EXP} 45 P`
    const d = await ui.openAssign(contract)
    await d.getByTestId(S.assignFee).fill('5.00')
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toHaveText(`可用现金不足：需要 5.00；${EXP} 16:00 时可用现金将变为 ${MINUS}5.00`)
    await expect(d.getByTestId(S.assignError)).toHaveAttribute('data-error-code', 'CASH_INSUFFICIENT')
    await expect(d).toBeVisible()
    await expect(d.getByTestId(S.assignFee)).toHaveValue('5.00')
    await expect(d.getByTestId(S.assignDate)).toHaveValue(EXP)

    await d.getByTestId(S.assignFee).fill('0.00')
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultStockCost)).toHaveText('4,450.00')
    await expect(d.getByTestId(S.assignResultCostPerShare)).toHaveText('44.5000')
    await d.getByTestId(S.assignDone).click()
    await expect.poll(() => ui.stockPositionRows()).toEqual(['QRTV 100 4,450.00 44.5000'])
    await ui.id(S.nav('cash')).click()
    await new CashPage(page).expectSummary('0.00', '0.00', '0.00')
  })

  test('AC9 multiplier 1、行权价 12.345：选项 "2 张（2 股）"；买股 24.69、冻结 −24.70、成本 23.69；交易列表 "2（multiplier 1）"；流水说明带 multiplier', async ({ page }) => {
    const acc = await apiCreateAccount('测试乘数账户')
    setApiAccount(acc)
    await apiTransfer('DEPOSIT', '100.00', at(addDays(EXP, -18)))
    await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'PUT', netAmount: '0.50', time: at(addDays(EXP, -17)), multiplier: 1 })
    await apiTrade({ action: 'STO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'PUT', netAmount: '0.50', time: at(addDays(EXP, -16)), multiplier: 1 })
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(acc, '测试乘数账户')
    const contract = `QFZ ${EXP} 12.345 P`
    const d = await ui.openAssign(contract)
    await expect(d.getByTestId(S.assignMultiplier)).toHaveText('1')
    await expect(d.getByTestId(S.assignFrozen)).toHaveText('24.70')
    expect(await ui.contractOptions(d)).toEqual(['1 张（1 股）', '2 张（2 股）'])
    await ui.submitAssign(d)
    await expect(d.getByTestId(S.assignResultContracts)).toHaveText('2 张（multiplier 1）')
    await expect(d.getByTestId(S.assignResultPurchase)).toHaveText('24.69')
    await expect(d.getByTestId(S.assignResultFrozenChange)).toHaveText(`${MINUS}24.70`)
    await expect(d.getByTestId(S.assignResultStockCost)).toHaveText('23.69')
    await expect(d.getByTestId(S.assignResultCostPerShare)).toHaveText('11.8450')
    await d.getByTestId(S.assignDone).click()

    await ui.id(S.nav('trades')).click()
    await expect(ui.id(S.tradeRowAny).first().getByTestId(S.tradeCells.contracts)).toHaveText('2（multiplier 1）')
    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('76.31', '0.00', '76.31')
    await expect.poll(async () => (await cash.rows())[0]).toEqual({
      time: `${EXP} 16:00`, type: '被指派', amount: `${MINUS}24.69`, frozen: `${MINUS}24.70`, balance: '76.31', note: `被指派 2 张 ${contract}（multiplier 1），买入 2 股 QFZ`,
    })
  })

  test('AC10 按账户隔离：默认账户被指派后切到小账户 XYZ 仍是 1 张、有作废+被指派、无股票；小账户被指派 1 张成本 4,400.00；切回默认 300 股 13,130.00', async ({ page }) => {
    await scenarioP()
    const small = await apiCreateAccount('小账户')
    setApiAccount(small)
    await apiTransfer('DEPOSIT', '5000.00', at(addDays(EXP, -18)))
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '100.00', time: at(addDays(EXP, -16)) })
    setApiAccount(1)
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openAssign(XYZ)
    await ui.submitAssign(d)
    await d.getByTestId(S.assignDone).click()

    await ui.switchAccount(small, '小账户')
    await expect(ui.posRow(XYZ).getByTestId(S.posContracts)).toHaveText('1')
    await expect.poll(() => ui.rowButtons(XYZ)).toEqual(['作废', '被指派'])
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)
    const d2 = await ui.openAssign(XYZ)
    expect(await ui.contractOptions(d2)).toEqual(['1 张（100 股）'])
    await ui.submitAssign(d2)
    await expect(d2.getByTestId(S.assignResultStockCost)).toHaveText('4,400.00')
    await d2.getByTestId(S.assignDone).click()
    await expect.poll(() => ui.stockPositionRows()).toEqual(['XYZ 100 4,400.00 44.0000'])

    await ui.switchAccount(1, '默认账户')
    await expect.poll(() => ui.stockPositionRows()).toEqual(['XYZ 300 13,130.00 43.7667'])
  })

  test('AC10 格式校验：指派费 −1.00 / 1.234 / 过大、日期为空、备注 501 字 → 字段下显示错误、不发请求、什么都不保存', async ({ page }) => {
    await scenarioP()
    const ui = new Ui(page)
    await ui.openPositions()
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/option-trades/assignments')) reqs.push(r.url())
    })
    const d = await ui.openAssign(XYZ)
    const feeError = ui.fieldError(d, S.assignFee)
    for (const [fee, msg] of [
      ['-1.00', '指派费不能为负数'],
      ['1.234', '指派费最多 2 位小数'],
      ['10000000000000.00', '指派费过大'],
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

  test('后端错误：被指派接口返回 500 / 网络失败时对话框显示错误、不关闭、什么都不保存', async ({ page }) => {
    await scenarioP()
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
    const d = await ui.openAssign(XYZ)
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toContainText('服务器内部错误')
    await expect(d).toBeVisible()
    await page.unroute(path)
    await page.route(path, (route) => route.abort('connectionrefused'))
    await d.getByTestId(S.assignSubmit).click()
    await expect(d.getByTestId(S.assignError)).toBeVisible()
    await expect(d.getByTestId(S.assignError)).not.toHaveText('')
    await expect(d).toBeVisible()
    await page.unroute(path)
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=ASG')).totalItems).toBe(0)
    await expect(ui.posRow(XYZ).getByTestId(S.posContracts)).toHaveText('3')
  })

  test('已平仓页方向筛选"被指派"：只看 ASG；类型选"股票"时方向被禁用（Q90）', async ({ page }) => {
    await scenarioP()
    await assignXyzAll()
    await apiExpire('IXQZ', EXP, '8', 'CALL', 1)
    const ui = new Ui(page)
    await page.goto('/closed')
    await expect(ui.id(S.closedCount)).toHaveText('共 2 笔平仓')
    await ui.id(S.closedFilterAction('asg')).check()
    await ui.id(S.closedFilterSubmit).click()
    await expect.poll(() => new URL(page.url()).searchParams.getAll('action').join(',')).toBe('ASG')
    await expect(ui.id(S.closedCount)).toHaveText('共 1 笔平仓')
    await expect.poll(async () => (await ui.closedCells()).map((c) => c.action)).toEqual(['被指派'])
    await page.goto('/closed?type=STOCK')
    await expect(ui.id(S.closedFilterAction('asg'))).toBeDisabled()
  })
})
