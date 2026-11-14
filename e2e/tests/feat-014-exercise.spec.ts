import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiTrade } from '../support/trade'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, PHASES, phaseInstant } from '../support/clock'
import { addDays } from '../support/ny'

/**
 * FEAT-014 主动行权 E2E (real clock). As FEAT-013 / FEAT-041, dates are relative to today's New York date T: the
 * scenario E expiration EXP = T−3 (already expired), the short call S1 expires at FAR = T+25, early exercises use
 * dates before EXP. Amounts are those of the requirement's scenario E. Fictional tickers only.
 * DB reset per test: scenario E = deposit #1; T1 = stock record #1; L1, L2, L3, S1 = option trades #1–#4; the first
 * exercise = option trade #5 and its generated EXERCISE_BUY / EXERCISE_SELL = stock record #2.
 */

// ------------------------------------------------------------------------------------------------ selectors
// All data-testids used by this spec, in one place. Shared names are those of FEAT-013 / FEAT-041; the exercise ones
// (ASSUMED until the frontend lands) follow the assignment dialog's pattern with "assign" → "exercise".
const S = {
  // positions page
  posRow: (contract: string) => `[data-testid="position-row"][data-contract="${contract}"]`,
  positionsAsOf: 'positions-as-of',
  positionRow: 'position-row',
  posExercise: 'position-exercise', // ASSUMED
  posContracts: 'position-cell-contracts',
  posPending: 'position-expiry-pending',
  posDteToday: 'position-cell-dte-today',
  // exercise dialog (ASSUMED)
  exModal: 'exercise-modal',
  exContract: 'exercise-contract',
  exDirection: 'exercise-direction',
  exStrike: 'exercise-strike',
  exMultiplier: 'exercise-multiplier',
  exPositionContracts: 'exercise-position-contracts',
  exAvailableCash: 'exercise-available-cash', // call: "可用现金 11,510.00"
  exUnderlyingShares: 'exercise-underlying-shares', // put: "当前持股 200 股，可用 100 股"
  exContracts: 'exercise-contracts',
  exContractsOption: (n: number) => `exercise-contracts-option-${n}`,
  exDate: 'exercise-date',
  exFee: 'exercise-fee',
  exNote: 'exercise-note',
  exExplanation: 'exercise-explanation',
  exSubmit: 'exercise-submit',
  exCancel: 'exercise-cancel',
  exError: 'exercise-error',
  exResult: 'exercise-result',
  exResultTime: 'exercise-result-time',
  exResultContracts: 'exercise-result-contracts',
  exResultLots: 'exercise-result-lots',
  exResultPremium: 'exercise-result-premium',
  exResultShares: 'exercise-result-shares',
  exResultPurchase: 'exercise-result-purchase', // call 买股金额
  exResultStockCost: 'exercise-result-stock-cost', // call 股票成本
  exResultCostPerShare: 'exercise-result-cost-per-share', // call 每股成本
  exResultSaleAmount: 'exercise-result-sale-amount', // put 行权金额
  exResultProceeds: 'exercise-result-proceeds', // put 卖出实收
  exResultSoldLots: 'exercise-result-sold-lots', // put 交出批次表（行 = sold-lot-row）
  exResultSoldCost: 'exercise-result-sold-cost', // put 交出成本
  exResultStockPnl: 'exercise-result-stock-pnl', // put 股票盈亏
  exResultFee: 'exercise-result-fee',
  exResultCashChange: 'exercise-result-cash-change',
  exResultPnl: 'exercise-result-pnl',
  exDone: 'exercise-done',
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
  tradeExerciseText: 'trade-cell-exercise', // ASSUMED: "买入 300 股 QRTV，现金 −9,000.00"
  tradeEdit: 'trade-edit',
  tradeDelete: 'trade-delete',
  tradeHistory: 'trade-history',
  tradeFilterAction: (a: string) => `trade-filter-action-${a}`,
  tradeFilterSubmit: 'trade-filter-submit',
  tradeDeleteModal: 'trade-delete-modal',
  tradeDeleteSummary: 'trade-delete-summary',
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
  closedExercisePageNote: 'closed-exercise-note', // ASSUMED: page explanation of the exercise rule
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
  closedExerciseText: 'closed-cell-exercise', // ASSUMED: option row small text
  closedExerciseLink: 'closed-stock-exercise-link', // ASSUMED: stock row "来自行权 #5"
  closedFilterAction: (a: string) => `closed-filter-action-${a}`,
  closedFilterSubmit: 'closed-filter-submit',
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
  stockExerciseLink: 'stock-exercise-link', // ASSUMED: "来自行权 #5"
  stockExerciseHint: 'stock-exercise-hint', // ASSUMED: "由行权生成，在交易列表删除"
  stockEdit: 'stock-edit',
  stockDelete: 'stock-delete',
  stockHistory: 'stock-history',
  stockFilterType: (t: string) => `stock-filter-type-${t}`, // ASSUMED values: exercise-buy / exercise-sell
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

/** Page text of the requirement (FEAT-014 "页面与交互"). */
const TEXT = {
  feeHint: '券商收取的行权手续费',
  putCoveredHint: '被 call 覆盖的股票不能卖出',
  stockHint: '由行权生成，在交易列表删除',
}

// ------------------------------------------------------------------------------------------------ dates & data

const BACKEND = BACKEND_URL
/** "Today" (FEAT-009, Q126: expirations relative to it must be trading days): fixed, backend and browser at 10:00 New York. */
const T = '2026-11-23'
/** Scenario E expiration: always 3 days in the past. */
const EXP = addDays(T, -3)
/** Expiration of the not-yet-expired short call S1. */
const FAR = addDays(T, 25)
const at = (date: string, hm = '10:00') => `${date}T${hm}`
const QC = `QRTV ${EXP} 30 C`
const XP = `XYZ ${EXP} 45 P`
const XC = `XYZ ${FAR} 50 C`

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

/** Scenario E: 11,510.00 / 0.00 / 11,510.00; XYZ 200 / 100 / 100. */
async function scenarioE(): Promise<void> {
  await apiTransfer('DEPOSIT', '20000.00', at(addDays(EXP, -18)))
  await apiStock('BUY', 'XYZ', 200, '8000.00', at(addDays(EXP, -17))) // T1 stock #1
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '30', type: 'CALL', contracts: 2, netAmount: '300.00', time: at(addDays(EXP, -16)) }) // L1 #1
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '30', type: 'CALL', netAmount: '160.00', time: at(addDays(EXP, -15)) }) // L2 #2
  await apiTrade({ action: 'BTO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '120.00', time: at(addDays(EXP, -15), '11:00') }) // L3 #3
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: FAR, strike: '50', type: 'CALL', netAmount: '90.00', time: at(addDays(EXP, -14)) }) // S1 #4
}

async function apiExercise(body: {
  symbol: string
  expirationDate: string
  strike: string
  optionType: 'CALL' | 'PUT'
  contracts: number
  exerciseDate: string
  exerciseFee?: string
}): Promise<{ id: number; exercise: { stockTradeId: number } }> {
  const r = await fetch(`${BACKEND}/api/option-trades/exercises`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ exerciseFee: null, note: null, ...body }),
  })
  if (r.status !== 201) throw new Error(`exercise failed ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

const exerciseCallAll = () =>
  apiExercise({ symbol: 'QRTV', expirationDate: EXP, strike: '30', optionType: 'CALL', contracts: 3, exerciseDate: EXP })
const exercisePut = () =>
  apiExercise({ symbol: 'XYZ', expirationDate: EXP, strike: '45', optionType: 'PUT', contracts: 1, exerciseDate: EXP })

async function apiClose(body: Record<string, unknown>): Promise<void> {
  const r = await fetch(`${BACKEND}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (r.status !== 201) throw new Error(`close failed ${r.status} ${await r.text()}`)
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

/** AC6 / AC10 "小账户": deposit 1,000.00; BTO 1 XYZ EXP 45 P 120.00; BTO 1 QRTV EXP 30 C 50.00; no stock. Leaves the API account on it. */
async function smallAccount(): Promise<number> {
  const acc = await apiCreateAccount('小账户')
  setApiAccount(acc)
  await apiTransfer('DEPOSIT', '1000.00', at(addDays(EXP, -18)))
  await apiTrade({ action: 'BTO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '120.00', time: at(addDays(EXP, -17)) })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '30', type: 'CALL', netAmount: '50.00', time: at(addDays(EXP, -16)) })
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
      .evaluateAll((bs) =>
        bs.map((b) => (b.textContent ?? '').replace(/\s+/g, '')).filter((t) => ['平仓', '作废', '被指派', '行权'].includes(t)),
      )
  }

  async openExercise(contract: string): Promise<Locator> {
    await this.posRow(contract).getByTestId(S.posExercise).click()
    const d = this.dialog(S.exModal)
    await expect(d).toBeVisible()
    return d
  }

  async contractOptions(d: Locator): Promise<string[]> {
    await d.getByTestId(S.exContracts).click()
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
    const shown = d.locator('[data-testid^="exercise-contracts-option-"]')
    return (await shown.count()) === 0 ? '' : ((await shown.first().textContent()) ?? '').trim()
  }

  async chooseContracts(d: Locator, n: number): Promise<void> {
    await d.getByTestId(S.exContracts).click()
    await this.page.locator('.ant-select-dropdown:visible').getByTestId(S.exContractsOption(n)).click()
    await expect.poll(() => this.selectedContracts(d)).toMatch(new RegExp(`^${n} 张`))
  }

  async submitExercise(d: Locator): Promise<void> {
    await d.getByTestId(S.exSubmit).click()
    await expect(d.getByTestId(S.exResult)).toBeVisible()
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
    return (await rowTexts(this.id(S.stockPositionRowAny), S.stockPositionCells)).sort()
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

test.describe('FEAT-014 主动行权', () => {
  test.beforeEach(async ({ page }) => {
    await fixNow(page, phaseInstant(T, 'closable'))
  })

  test('AC1 按钮与对话框：已过期多头 作废+行权、S1 无行权；QRTV 选项 2 张 / 3 张、默认 3、日期默认 / 最晚 EXP、可用现金 11,510.00；XYZ put 当前持股 200 股，可用 100 股', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    await expect.poll(() => ui.rowButtons(QC)).toEqual(['作废', '行权'])
    await expect.poll(() => ui.rowButtons(XP)).toEqual(['作废', '行权'])
    await expect.poll(() => ui.rowButtons(XC)).not.toContain('行权')
    await expect(ui.posRow(XC).getByTestId(S.posExercise)).toHaveCount(0)

    const d = await ui.openExercise(QC)
    await expect(d.getByTestId(S.exContract)).toHaveText(QC)
    await expect(d.getByTestId(S.exDirection)).toHaveText('多头')
    await expect(d.getByTestId(S.exStrike)).toHaveText('30')
    await expect(d.getByTestId(S.exMultiplier)).toHaveText('100')
    await expect(d.getByTestId(S.exPositionContracts)).toHaveText('3 张')
    await expect(d.getByTestId(S.exAvailableCash)).toContainText('11,510.00')
    await expect(d).toContainText('可用现金')
    await expect(d.getByTestId(S.exUnderlyingShares)).toHaveCount(0)
    expect(await ui.contractOptions(d)).toEqual(['2 张（200 股）', '3 张（300 股）'])
    expect(await ui.selectedContracts(d)).toBe('3 张（300 股）')
    await expect(d.getByTestId(S.exDate)).toHaveValue(EXP)
    await expect(d.getByTestId(S.exDate)).toHaveAttribute('max', EXP)
    await expect(d.getByTestId(S.exFee)).toHaveValue('0.00')
    await expect(d).toContainText(TEXT.feeHint)
    await expect(d.getByTestId(S.exExplanation)).toBeVisible()
    await expect(d.getByTestId(S.exExplanation)).not.toContainText(TEXT.putCoveredHint)
    await d.getByTestId(S.exCancel).click()
    await expect(ui.dialog(S.exModal)).toBeHidden()

    const p = await ui.openExercise(XP)
    await expect(p.getByTestId(S.exDirection)).toHaveText('多头')
    await expect(p.getByTestId(S.exUnderlyingShares)).toHaveText('当前持股 200 股，可用 100 股')
    await expect(p.getByTestId(S.exAvailableCash)).toHaveCount(0)
    expect(await ui.contractOptions(p)).toEqual(['1 张（100 股）'])
    await expect(p.getByTestId(S.exExplanation)).toContainText(TEXT.putCoveredHint)
    await p.getByTestId(S.exCancel).click()
    await expect(ui.dialog(S.exModal)).toBeHidden()
  })

  for (const p of PHASES) {
  test(`AC1 / Q81：今天到期的多头 call 任何时刻都有行权；到期前与平仓并列、到期待确认期间唯一按钮、17:30 起与作废并列 [固定时钟 2026-11-23 ${p}]`, async ({ page }) => {
    await fixNow(page, phaseInstant(T, p))
    await apiTransfer('DEPOSIT', '5000.00', at(addDays(T, -5)))
    await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: T, strike: '30', type: 'CALL', netAmount: '160.00', time: at(addDays(T, -2)) })
    const ui = new Ui(page)
    const phase = p
    await ui.openPositions()
    const call = `QRTV ${T} 30 C`
    await expect(ui.posRow(call).getByTestId(S.posDteToday)).toHaveText('今天到期')
    if (phase === 'closable') {
      await expect.poll(() => ui.rowButtons(call)).toEqual(['平仓', '行权'])
    } else if (phase === 'pending') {
      await expect(ui.posRow(call).getByTestId(S.posPending)).toHaveText('到期待确认')
      await expect.poll(() => ui.rowButtons(call)).toEqual(['行权'])
    } else {
      await expect.poll(() => ui.rowButtons(call)).toEqual(['作废', '行权'])
    }
  })
  }

  test('AC1 S1 行权被拒（API）：空头持仓不能行权（被行权请用"被指派"）', async () => {
    await scenarioE()
    const r = await fetch(`${BACKEND}/api/option-trades/exercises`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbol: 'XYZ', expirationDate: FAR, strike: '50', optionType: 'CALL', contracts: 1, exerciseDate: EXP, exerciseFee: null, note: null }),
    })
    expect(r.status).toBe(422)
    const body = (await r.json()) as { code: string; message: string }
    expect(body.code).toBe('EXERCISE_SHORT_POSITION')
    expect(body.message).toBe('空头持仓不能行权（被行权请用"被指派"）')
  })

  test('AC2 call 全部行权（对话框）：结果全部后端值；不刷新页面，持仓 / 资金 / 交易列表 / 股票页 / 已平仓 1 行 0.00 共 1 笔都更新；审计无记录', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await page.goto('/')
    await ui.markNoReload()
    await ui.id(S.nav('positions')).click()
    await expect(ui.posRow(QC)).toBeVisible()

    const d = await ui.openExercise(QC)
    await ui.submitExercise(d)
    await expect(d.getByTestId(S.exResultTime)).toHaveText(`${EXP} 16:00（美东）`)
    await expect(d.getByTestId(S.exResultContracts)).toHaveText('3 张')
    expect(await ui.coveredLots(d.getByTestId(S.exResultLots))).toEqual([
      `${addDays(EXP, -16)} 10:00 BTO（买入开仓） 2 张 ${MINUS}300.00`,
      `${addDays(EXP, -15)} 10:00 BTO（买入开仓） 1 张 ${MINUS}160.00`,
    ])
    await expect(d.getByTestId(S.exResultPremium)).toHaveText('460.00')
    await expect(d.getByTestId(S.exResultShares)).toHaveText('300 股 QRTV')
    await expect(d.getByTestId(S.exResultPurchase)).toHaveText('9,000.00')
    await expect(d.getByTestId(S.exResultFee)).toHaveText('0.00')
    await expect(d.getByTestId(S.exResultStockCost)).toHaveText('9,460.00')
    await expect(d.getByTestId(S.exResultCostPerShare)).toHaveText('31.5333')
    await expect(d.getByTestId(S.exResultCashChange)).toHaveText(`${MINUS}9,000.00`)
    await expect(d.getByTestId(S.exResultPnl)).toHaveText('0.00')
    await expect(d.getByTestId(S.exResultSaleAmount)).toHaveCount(0)
    await expect(d.getByTestId(S.exResultSoldLots)).toHaveCount(0)
    await d.getByTestId(S.exDone).click()
    await expect(ui.dialog(S.exModal)).toBeHidden()
    expect((await apiGet<{ action: string }>('/api/option-trades/5')).action).toBe('EXR')

    // positions: QRTV option gone; QRTV stock 300 shares 9,460.00
    await expect.poll(() => ui.positionContracts()).not.toContain(QC)
    await expect.poll(() => ui.stockPositionRows()).toEqual(['QRTV 300 9,460.00 31.5333', 'XYZ 200 8,000.00 40.0000'])

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('2,510.00', '0.00', '2,510.00')
    await expect.poll(async () => (await cash.rows())[0]).toEqual({
      time: `${EXP} 16:00`, type: '行权', amount: `${MINUS}9,000.00`, frozen: '—', balance: '2,510.00', note: `行权 3 张 ${QC}，买入 300 股 QRTV`,
    })

    await ui.id(S.nav('trades')).click()
    await expect.poll(async () => (await ui.tradeCells())[0]).toEqual({
      time: `${EXP} 16:00`, action: '行权', contract: QC, contracts: '3', net: '0.00', frozen: '—', note: '—',
    })
    const row = ui.tradeRow(5)
    await expect(row.getByTestId(S.tradeExerciseText)).toHaveText(`买入 300 股 QRTV，现金 ${MINUS}9,000.00`)
    await expect(row.getByTestId(S.tradeDelete)).toBeVisible()
    await expect(row.getByTestId(S.tradeEdit)).toHaveCount(0)
    await expect(row.getByTestId(S.tradeHistory)).toHaveCount(0)

    await ui.id(S.nav('stocks')).click()
    await expect(ui.id(S.stockListPage)).toBeVisible()
    await expect.poll(async () => (await ui.stockCells())[0]).toEqual({
      time: `${EXP} 16:00`, type: '行权买入', symbol: 'QRTV', shares: '300', amount: '成本 9,460.00', price: '每股 31.5333', pnl: '—',
    })
    const srow = ui.stockRow(2)
    await expect(srow.getByTestId(S.stockExerciseLink)).toHaveText('来自行权 #5')
    await expect(srow.getByTestId(S.stockEdit)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockDelete)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockHistory)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockExerciseHint)).toHaveText(TEXT.stockHint)

    await ui.id(S.nav('closed')).click()
    await expect(ui.id(S.closedCount)).toHaveText('共 1 笔平仓')
    await expect(ui.id(S.closedTotal)).toHaveText('0.00')
    await expect(ui.id(S.closedExercisePageNote)).toBeVisible()
    expect(await ui.closedCells()).toEqual([
      { time: `${EXP} 16:00`, type: '期权', contract: QC, action: '行权', direction: '多头', contracts: '3', openingNet: `${MINUS}460.00`, openingPer: `每张 ${MINUS}153.33`, closeNet: '0.00', closePer: '每张 0.00', pnl: '0.00' },
    ])
    await expect(ui.closedRow('OPTION', 5).getByTestId(S.closedExerciseText)).toHaveText('权利金已计入股票成本（300 股 QRTV，成本 9,460.00）')

    await ui.id(S.nav('home')).click()
    await expect(ui.id(S.dashRealizedTotal)).toHaveText('0.00')
    await expect(ui.id(S.dashRealizedCount)).toHaveText('1')

    await ui.id(S.nav('audit')).click()
    await expect(ui.id(S.auditEmpty)).toBeVisible()
    await expect(ui.id(S.auditRow)).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC3 put 行权（对话框）：行权金额 4,500.00、实收 4,380.00、交出 T1 100 股 4,000.00、+380.00、现金 +4,500.00；16,010.00；已平仓 2 行（行权卖出在上）共 2 笔；汇总 / 首页 +380.00（2 笔）', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openExercise(XP)
    await ui.submitExercise(d)
    await expect(d.getByTestId(S.exResultTime)).toHaveText(`${EXP} 16:00（美东）`)
    expect(await ui.coveredLots(d.getByTestId(S.exResultLots))).toEqual([
      `${addDays(EXP, -15)} 11:00 BTO（买入开仓） 1 张 ${MINUS}120.00`,
    ])
    await expect(d.getByTestId(S.exResultPremium)).toHaveText('120.00')
    await expect(d.getByTestId(S.exResultShares)).toHaveText('100 股 XYZ')
    await expect(d.getByTestId(S.exResultSaleAmount)).toHaveText('4,500.00')
    await expect(d.getByTestId(S.exResultFee)).toHaveText('0.00')
    await expect(d.getByTestId(S.exResultProceeds)).toHaveText('4,380.00')
    expect(await ui.soldLots(d.getByTestId(S.exResultSoldLots))).toEqual([
      `买入 #1 | ${addDays(EXP, -17)} 10:00 | 100 股 | 4,000.00`,
    ])
    await expect(d.getByTestId(S.exResultSoldCost)).toHaveText('4,000.00')
    await expect(d.getByTestId(S.exResultStockPnl)).toHaveText('+380.00')
    expect(await colorOf(d.getByTestId(S.exResultStockPnl))).toBe('green')
    await expect(d.getByTestId(S.exResultCashChange)).toHaveText('+4,500.00')
    await expect(d.getByTestId(S.exResultPnl)).toHaveText('0.00')
    await expect(d.getByTestId(S.exResultStockCost)).toHaveCount(0)
    await d.getByTestId(S.exDone).click()
    await expect(ui.dialog(S.exModal)).toBeHidden()

    await expect.poll(() => ui.positionContracts()).not.toContain(XP)
    await expect.poll(() => ui.stockPositionRows()).toEqual(['XYZ 100 4,000.00 40.0000'])

    await page.goto('/cash')
    const cash = new CashPage(page)
    await cash.expectSummary('16,010.00', '0.00', '16,010.00')
    await expect.poll(async () => (await cash.rows())[0]?.note).toBe(`行权 1 张 ${XP}，卖出 100 股 XYZ`)

    await page.goto('/trades')
    await expect(ui.tradeRow(5).getByTestId(S.tradeExerciseText)).toHaveText('卖出 100 股 XYZ，现金 +4,500.00')

    await page.goto('/stocks')
    await expect.poll(async () => (await ui.stockCells())[0]).toEqual({
      time: `${EXP} 16:00`, type: '行权卖出', symbol: 'XYZ', shares: '100', amount: '实收 4,380.00', price: '每股 43.8000', pnl: '+380.00',
    })
    await expect(ui.stockRow(2).getByTestId(S.stockExerciseLink)).toHaveText('来自行权 #5')
    await expect(ui.stockRow(2).getByTestId(S.stockDelete)).toHaveCount(0)

    await page.goto('/closed')
    await expect(ui.id(S.closedCount)).toHaveText('共 2 笔平仓')
    await expect(ui.id(S.closedTotal)).toHaveText('+380.00')
    expect(await ui.closedCells()).toEqual([
      { time: `${EXP} 16:00`, type: '股票', contract: 'XYZ', action: '行权卖出', direction: '', contracts: '100 股', openingNet: `${MINUS}4,000.00`, openingPer: '每股 40.0000', closeNet: '+4,380.00', closePer: '每股 43.8000', pnl: '+380.00' },
      { time: `${EXP} 16:00`, type: '期权', contract: XP, action: '行权', direction: '多头', contracts: '1', openingNet: `${MINUS}120.00`, openingPer: `每张 ${MINUS}120.00`, closeNet: '0.00', closePer: '每张 0.00', pnl: '0.00' },
    ])
    await expect(ui.closedRow('STOCK', 2).getByTestId(S.closedExerciseLink)).toHaveText('来自行权 #5')
    await expect(ui.closedRow('OPTION', 5).getByTestId(S.closedExerciseText)).toHaveText('权利金已从股票卖出实收中扣除（100 股 XYZ，实收 4,380.00）')

    // direction filter 行权 → option row only (Q90)
    await ui.id(S.closedFilterAction('exr')).check()
    await ui.id(S.closedFilterSubmit).click()
    await expect.poll(() => new URL(page.url()).searchParams.getAll('action').join(',')).toBe('EXR')
    await expect(ui.id(S.closedCount)).toHaveText('共 1 笔平仓')
    await expect.poll(async () => (await ui.closedCells()).map((c) => c.action)).toEqual(['行权'])

    await page.goto('/pnl-summary')
    await expect(ui.id(S.pnlTotal)).toHaveText('+380.00')
    await expect.poll(() => rowTexts(ui.id(S.pnlSymbolRow), S.pnlSymbolCells)).toEqual(['XYZ 2 0.00 +380.00 +380.00'])
    await page.goto('/')
    await expect(ui.id(S.dashRealizedTotal)).toHaveText('+380.00')
    await expect(ui.id(S.dashRealizedCount)).toHaveText('2')
  })

  test('AC4 提前行权 2 张 + 行权费 5.00：成本 6,305.00、每股 31.5250、现金 −6,005.00；剩 1 张；交易列表小字带行权费；1 张非整笔后端拒绝显示在对话框', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openExercise(QC)
    const early = addDays(EXP, -2)
    expect(await ui.contractOptions(d)).not.toContain('1 张（100 股）')
    await ui.chooseContracts(d, 2)
    await d.getByTestId(S.exDate).fill(early)
    await d.getByTestId(S.exFee).fill('5.00')
    await ui.submitExercise(d)
    await expect(d.getByTestId(S.exResultTime)).toHaveText(`${early} 16:00（美东）`)
    expect(await ui.coveredLots(d.getByTestId(S.exResultLots))).toEqual([
      `${addDays(EXP, -16)} 10:00 BTO（买入开仓） 2 张 ${MINUS}300.00`,
    ])
    await expect(d.getByTestId(S.exResultPremium)).toHaveText('300.00')
    await expect(d.getByTestId(S.exResultPurchase)).toHaveText('6,000.00')
    await expect(d.getByTestId(S.exResultFee)).toHaveText('5.00')
    await expect(d.getByTestId(S.exResultStockCost)).toHaveText('6,305.00')
    await expect(d.getByTestId(S.exResultCostPerShare)).toHaveText('31.5250')
    await expect(d.getByTestId(S.exResultCashChange)).toHaveText(`${MINUS}6,005.00`)
    await d.getByTestId(S.exDone).click()

    await expect(ui.posRow(QC).getByTestId(S.posContracts)).toHaveText('1')
    await expect.poll(() => ui.stockPositionRows()).toEqual(['QRTV 200 6,305.00 31.5250', 'XYZ 200 8,000.00 40.0000'])
    await page.goto('/trades')
    await expect(ui.tradeRow(5).getByTestId(S.tradeExerciseText)).toHaveText(`买入 200 股 QRTV，现金 ${MINUS}6,005.00，行权费 5.00`)
    await page.goto('/cash')
    await new CashPage(page).expectSummary('5,505.00', '0.00', '5,505.00')
  })

  test('AC4 先作废再行权：作废后行权 → 后端原文（含"如果实际是行权，请先删除该到期作废"）', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openExercise(QC)
    // the expiration is recorded meanwhile (e.g. in another tab)
    const r = await fetch(`${BACKEND}/api/option-trades/expirations`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbol: 'QRTV', expirationDate: EXP, strike: '30', optionType: 'CALL', contracts: 3 }),
    })
    expect(r.status).toBe(201)
    await d.getByTestId(S.exSubmit).click()
    await expect(d.getByTestId(S.exError)).toHaveText(
      `${EXP} 16:00 时该合约（${QC}）没有持仓可行权（已于 ${EXP} 16:00 到期作废；如果实际是行权，请先删除该到期作废）`,
    )
    await expect(d.getByTestId(S.exError)).toHaveAttribute('data-error-code', 'NO_POSITION_TO_EXERCISE')
    await expect(d).toBeVisible()
  })

  test('AC5 现金不足：加出金 3,000.00；行权 3 张 → "可用现金不足：需要 9,000.00；… 将变为 −490.00" 显示在对话框、保留内容；改 2 张成功 → 2,510.00', async ({ page }) => {
    await scenarioE()
    await apiTransfer('WITHDRAWAL', '3000.00', at(addDays(EXP, -10)))
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openExercise(QC)
    await expect(d.getByTestId(S.exAvailableCash)).toContainText('8,510.00')
    await d.getByTestId(S.exSubmit).click()
    await expect(d.getByTestId(S.exError)).toHaveText(`可用现金不足：需要 9,000.00；${EXP} 16:00 时可用现金将变为 ${MINUS}490.00`)
    await expect(d.getByTestId(S.exError)).toHaveAttribute('data-error-code', 'CASH_INSUFFICIENT')
    await expect(d).toBeVisible()
    expect(await ui.selectedContracts(d)).toBe('3 张（300 股）')
    await ui.chooseContracts(d, 2)
    await ui.submitExercise(d)
    await expect(d.getByTestId(S.exResultCashChange)).toHaveText(`${MINUS}6,000.00`)
    await d.getByTestId(S.exDone).click()
    await page.goto('/cash')
    await new CashPage(page).expectSummary('2,510.00', '0.00', '2,510.00')
  })

  test('AC6 持股不足：小账户 put 对话框"当前持股 0 股，可用 0 股"；提交 → 持股不足原文显示在对话框、不关闭', async ({ page }) => {
    const acc = await smallAccount()
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(acc, '小账户')
    const d = await ui.openExercise(XP)
    await expect(d.getByTestId(S.exUnderlyingShares)).toHaveText('当前持股 0 股，可用 0 股')
    await d.getByTestId(S.exSubmit).click()
    await expect(d.getByTestId(S.exError)).toHaveText(`持股不足：行权需要 100 股 XYZ，${EXP} 16:00 时持有 0 股。请先录入股票（转入或更早的买入）`)
    await expect(d.getByTestId(S.exError)).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(d).toBeVisible()
    await expect(d.getByTestId(S.exDate)).toHaveValue(EXP)
  })

  test('AC6 被覆盖（Q113）：再卖 1 张 XYZ call → 可用 0 股，行权 put 被拒；BTC 该 call 后再提交成功：实收 4,380.00、+380.00', async ({ page }) => {
    await scenarioE()
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: FAR, strike: '50', type: 'CALL', netAmount: '80.00', time: at(addDays(EXP, -13)) }) // #5
    const ui = new Ui(page)
    await ui.openPositions()
    const d = await ui.openExercise(XP)
    await expect(d.getByTestId(S.exUnderlyingShares)).toHaveText('当前持股 200 股，可用 0 股')
    await d.getByTestId(S.exSubmit).click()
    await expect(d.getByTestId(S.exError)).toHaveText('行权需要卖出 100 股，超过可用股数 0 股（持有 200 股，其中 200 股被 call 覆盖），请先平掉对应的 call')
    await expect(d.getByTestId(S.exError)).toHaveAttribute('data-error-code', 'COVERAGE_INSUFFICIENT')
    await expect(d).toBeVisible()

    await apiClose({ symbol: 'XYZ', expirationDate: FAR, strike: '50', optionType: 'CALL', action: 'BTC', contracts: 1, netAmount: '30.00', tradedAtLocal: at(addDays(EXP, -10)) })
    await ui.submitExercise(d)
    await expect(d.getByTestId(S.exResultProceeds)).toHaveText('4,380.00')
    await expect(d.getByTestId(S.exResultStockPnl)).toHaveText('+380.00')
  })

  test('AC7 交易列表删除行权：不刷新页面恢复现金 11,510.00、QRTV 3 张；股票 / 已平仓消失；审计摘要「行权 … 3 张（买入 300 股 QRTV）」', async ({ page }) => {
    await scenarioE()
    await exerciseCallAll() // #5, stock #2
    const ui = new Ui(page)
    await page.goto('/trades')
    await expect(ui.tradeRow(5)).toBeVisible()
    await ui.markNoReload()
    await ui.id(S.tradeFilterAction('exr')).check()
    await ui.id(S.tradeFilterSubmit).click()
    await expect.poll(() => new URL(page.url()).searchParams.getAll('action').join(',')).toBe('EXR')
    await expect(ui.id(S.tradeTotal)).toHaveText('共 1 条')

    await ui.tradeRow(5).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await expect(d).toContainText('删除行权 #5')
    await expect(d.getByTestId(S.tradeDeleteSummary)).toContainText('买入 300 股 QRTV')
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(ui.dialog(S.tradeDeleteModal)).toBeHidden()
    await expect(ui.tradeRow(5)).toHaveCount(0)

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('11,510.00', '0.00', '11,510.00')
    await expect(cash.ledgerRows).toHaveCount(6)

    await ui.id(S.nav('positions')).click()
    await expect(ui.posRow(QC).getByTestId(S.posContracts)).toHaveText('3')
    await expect.poll(() => ui.stockPositionRows()).toEqual(['XYZ 200 8,000.00 40.0000'])

    await ui.id(S.nav('stocks')).click()
    await expect(ui.id(S.stockRowAny)).toHaveCount(1)
    await expect(ui.stockRow(2)).toHaveCount(0)
    await ui.id(S.nav('closed')).click()
    await expect(ui.id(S.closedRowAny)).toHaveCount(0)

    await ui.id(S.nav('audit')).click()
    await expect(ui.id(S.auditRow)).toHaveCount(1)
    const first = ui.id(S.auditRow).first()
    await expect(first.getByTestId(S.auditOperation)).toHaveText('删除')
    await expect(first.getByTestId(S.auditSummary)).toHaveText(`行权 ${QC} 3 张（买入 300 股 QRTV）`)
    await ui.expectNoReload()
  })

  test('AC7 删除被拒：行权后卖出 QRTV 100 股 → 删除行权 → "删除后 … 的卖出 QRTV 100 股将超过当时持股 0 股" 显示在删除对话框、不关闭', async ({ page }) => {
    await scenarioE()
    await exerciseCallAll()
    const sellDay = addDays(EXP, 1)
    await apiStock('SELL', 'QRTV', 100, '3300.00', at(sellDay))
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(5).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveText(`删除后 ${sellDay} 10:00 的卖出 QRTV 100 股将超过当时持股 0 股`)
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    await expect(d).toBeVisible()
    await d.getByTestId(S.tradeDeleteCancel).click()
    await expect(ui.tradeRow(5)).toBeVisible()
    expect((await apiGet<{ totalItems: number }>('/api/audit-log')).totalItems).toBe(0)
  })

  test('AC7 股票页：行权买入行无修改 / 删除、"来自行权 #5" 跳到交易列表；类型筛选"行权买入"', async ({ page }) => {
    await scenarioE()
    await exerciseCallAll()
    const ui = new Ui(page)
    await page.goto('/stocks')
    await expect(ui.id(S.stockListPage)).toBeVisible()
    await ui.stockRow(2).getByTestId(S.stockExerciseLink).click()
    await expect(page).toHaveURL(/\/trades\?/)
    await expect(ui.tradeRow(5)).toBeVisible()

    await page.goto('/stocks')
    await ui.id(S.stockFilterType('exercise-buy')).check()
    await ui.id(S.stockFilterSubmit).click()
    await expect(page).toHaveURL(/type=EXERCISE_BUY/)
    await expect.poll(async () => (await ui.stockCells()).map((c) => c.type)).toEqual(['行权买入'])
  })

  test('AC7 股票页：行权卖出行无修改 / 删除、提示由行权生成；类型筛选"行权卖出"；交易列表方向筛选"行权"', async ({ page }) => {
    await scenarioE()
    await exercisePut() // #5, stock #2
    const ui = new Ui(page)
    await page.goto('/stocks')
    await expect(ui.id(S.stockListPage)).toBeVisible()
    const srow = ui.stockRow(2)
    await expect(srow.getByTestId(S.stockCells.type)).toHaveText('行权卖出')
    await expect(srow.getByTestId(S.stockEdit)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockDelete)).toHaveCount(0)
    await expect(srow.getByTestId(S.stockExerciseHint)).toHaveText(TEXT.stockHint)
    await ui.id(S.stockFilterType('exercise-sell')).check()
    await ui.id(S.stockFilterSubmit).click()
    await expect(page).toHaveURL(/type=EXERCISE_SELL/)
    await expect.poll(async () => (await ui.stockCells()).map((c) => c.type)).toEqual(['行权卖出'])

    await page.goto('/trades')
    await ui.id(S.tradeFilterAction('exr')).check()
    await ui.id(S.tradeFilterSubmit).click()
    await expect.poll(() => new URL(page.url()).searchParams.getAll('action').join(',')).toBe('EXR')
    await expect.poll(async () => (await ui.tradeCells()).map((c) => c.action)).toEqual(['行权'])
  })

  test('AC8 修改 L2 净额 160 → 170：股票页成本 9,470.00；已平仓小字成本 9,470.00；审计连带变化「行权 #5 股票成本 9,460.00 → 9,470.00」', async ({ page }) => {
    await scenarioE()
    await exerciseCallAll()
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(2).getByTestId(S.tradeEdit).click()
    const d = ui.dialog(S.tradeEditModal)
    await d.getByTestId(S.tradeEditNet).fill('170.00')
    await d.getByTestId(S.tradeEditSubmit).click()
    await expect(ui.dialog(S.tradeEditModal)).toBeHidden()

    await ui.id(S.nav('stocks')).click()
    await expect(ui.stockRow(2).getByTestId(S.stockCells.amount)).toHaveText('成本 9,470.00')
    await ui.id(S.nav('cash')).click()
    await new CashPage(page).expectSummary('2,500.00', '0.00', '2,500.00')
    await ui.id(S.nav('closed')).click()
    await expect(ui.closedRow('OPTION', 5).getByTestId(S.closedExerciseText)).toHaveText('权利金已计入股票成本（300 股 QRTV，成本 9,470.00）')
    await ui.id(S.nav('audit')).click()
    const first = ui.id(S.auditRow).first()
    await expect(first.getByTestId(S.auditOperation)).toHaveText('修改')
    await expect(first.getByTestId(S.auditSideEffectLine)).toContainText(['行权 #5 股票成本 9,460.00 → 9,470.00'])
  })

  test('AC8 删除被行权批次 L2 被拒："该开仓被 … 的行权 3 张覆盖（删除后行权将超过当时持仓 2 张），请先删除该行权"', async ({ page }) => {
    await scenarioE()
    await exerciseCallAll()
    const ui = new Ui(page)
    await page.goto('/trades')
    await ui.tradeRow(2).getByTestId(S.tradeDelete).click()
    const d = ui.dialog(S.tradeDeleteModal)
    await d.getByTestId(S.tradeDeleteConfirm).click()
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveText(`该开仓被 ${EXP} 16:00 的行权 3 张覆盖（删除后行权将超过当时持仓 2 张），请先删除该行权`)
    await expect(d.getByTestId(S.tradeDeleteError)).toHaveAttribute('data-error-code', 'CLOSE_EXCEEDS_POSITION')
    await expect(d).toBeVisible()
  })

  test('AC9 multiplier 1、行权价 12.345：call 买股 12.35（向上取整）、成本 12.85、现金 86.75；put 行权金额 12.34（向下取整）、实收 11.94、交出转入 10.00、+1.94、现金 99.09；流水说明带 multiplier', async ({ page }) => {
    const acc = await apiCreateAccount('测试乘数账户')
    setApiAccount(acc)
    await apiTransfer('DEPOSIT', '100.00', at(addDays(EXP, -18)))
    await apiStock('TRANSFER_IN', 'QFZ', 1, '10.00', addDays(EXP, -18))
    await apiTrade({ action: 'BTO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'CALL', netAmount: '0.50', time: at(addDays(EXP, -17)), multiplier: 1 })
    await apiTrade({ action: 'BTO', symbol: 'QFZ', expiration: EXP, strike: '12.345', type: 'PUT', netAmount: '0.40', time: at(addDays(EXP, -17), '10:05'), multiplier: 1 })
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.switchAccount(acc, '测试乘数账户')
    const call = `QFZ ${EXP} 12.345 C`
    const put = `QFZ ${EXP} 12.345 P`

    const c = await ui.openExercise(call)
    await expect(c.getByTestId(S.exMultiplier)).toHaveText('1')
    await expect(c.getByTestId(S.exAvailableCash)).toContainText('99.10')
    expect(await ui.contractOptions(c)).toEqual(['1 张（1 股）'])
    await ui.submitExercise(c)
    await expect(c.getByTestId(S.exResultPurchase)).toHaveText('12.35')
    await expect(c.getByTestId(S.exResultStockCost)).toHaveText('12.85')
    await expect(c.getByTestId(S.exResultCostPerShare)).toHaveText('12.8500')
    await expect(c.getByTestId(S.exResultCashChange)).toHaveText(`${MINUS}12.35`)
    await c.getByTestId(S.exDone).click()
    await expect(ui.dialog(S.exModal)).toBeHidden()

    const p = await ui.openExercise(put)
    await expect(p.getByTestId(S.exUnderlyingShares)).toHaveText('当前持股 2 股，可用 2 股')
    await ui.submitExercise(p)
    await expect(p.getByTestId(S.exResultSaleAmount)).toHaveText('12.34')
    await expect(p.getByTestId(S.exResultProceeds)).toHaveText('11.94')
    expect(await ui.soldLots(p.getByTestId(S.exResultSoldLots))).toEqual([`转入 #1 | ${addDays(EXP, -18)} | 1 股 | 10.00`])
    await expect(p.getByTestId(S.exResultSoldCost)).toHaveText('10.00')
    await expect(p.getByTestId(S.exResultStockPnl)).toHaveText('+1.94')
    await expect(p.getByTestId(S.exResultCashChange)).toHaveText('+12.34')
    await p.getByTestId(S.exDone).click()
    await expect.poll(() => ui.stockPositionRows()).toEqual(['QFZ 1 12.85 12.8500'])

    await ui.id(S.nav('cash')).click()
    const cash = new CashPage(page)
    await cash.expectSummary('99.09', '0.00', '99.09')
    await expect.poll(async () => (await cash.rows()).slice(0, 2).map((r) => r.note)).toEqual([
      `行权 1 张 ${put}（multiplier 1），卖出 1 股 QFZ`,
      `行权 1 张 ${call}（multiplier 1），买入 1 股 QFZ`,
    ])
  })

  test('AC10 按账户隔离：默认账户行权后切到小账户 QRTV 仍 1 张、可用现金 830.00、无股票；切回默认账户无 QRTV 期权、有 QRTV 股票', async ({ page }) => {
    await scenarioE()
    await exerciseCallAll()
    const small = await smallAccount()
    setApiAccount(1)
    const ui = new Ui(page)
    await ui.openPositions()
    await expect.poll(() => ui.positionContracts()).not.toContain(QC)

    await ui.switchAccount(small, '小账户')
    await expect(ui.posRow(QC).getByTestId(S.posContracts)).toHaveText('1')
    await expect(ui.id(S.stockPositionRowAny)).toHaveCount(0)
    const d = await ui.openExercise(QC)
    await expect(d.getByTestId(S.exAvailableCash)).toContainText('830.00')
    await d.getByTestId(S.exCancel).click()

    await ui.switchAccount(1, '默认账户')
    await expect.poll(() => ui.stockPositionRows()).toEqual(['QRTV 300 9,460.00 31.5333', 'XYZ 200 8,000.00 40.0000'])
  })

  test('AC10 格式校验：行权费 −1.00 / 1.234、日期为空、备注 501 字 → 字段下显示错误、不发请求、什么都不保存', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    const reqs: string[] = []
    page.on('request', (r) => {
      if (r.url().includes('/api/option-trades/exercises')) reqs.push(r.url())
    })
    const d = await ui.openExercise(QC)
    const feeError = ui.fieldError(d, S.exFee)
    for (const [fee, msg] of [
      ['-1.00', '行权费不能为负数'],
      ['1.234', '行权费最多 2 位小数'],
    ] as const) {
      await d.getByTestId(S.exFee).fill(fee)
      await d.getByTestId(S.exSubmit).click()
      await expect(feeError).toHaveText(msg)
    }
    await d.getByTestId(S.exFee).fill('0.00')
    await d.getByTestId(S.exDate).fill('')
    await d.getByTestId(S.exSubmit).click()
    await expect(ui.fieldError(d, S.exDate)).toHaveText('行权日期不能为空')
    await d.getByTestId(S.exDate).fill(EXP)
    await d.getByTestId(S.exNote).fill('x'.repeat(501))
    await d.getByTestId(S.exSubmit).click()
    await expect(ui.fieldError(d, S.exNote)).toHaveText('备注最多 500 个字符')
    await expect(d.getByTestId(S.exResult)).toHaveCount(0)
    await page.waitForTimeout(300)
    expect(reqs).toHaveLength(0)
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=EXR')).totalItems).toBe(0)
  })

  test('后端错误：行权接口返回 500 时对话框显示错误、不关闭、什么都不保存', async ({ page }) => {
    await scenarioE()
    const ui = new Ui(page)
    await ui.openPositions()
    const path = '**/api/option-trades/exercises'
    await page.route(path, (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误', status: 500, path: '/api/option-trades/exercises', timestamp: '2026-11-13T15:00:00Z', details: [] }),
      }),
    )
    const d = await ui.openExercise(QC)
    await d.getByTestId(S.exSubmit).click()
    await expect(d.getByTestId(S.exError)).toContainText('服务器内部错误')
    await expect(d).toBeVisible()
    await page.unroute(path)
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=EXR')).totalItems).toBe(0)
    await expect(ui.posRow(QC).getByTestId(S.posContracts)).toHaveText('3')
  })
})
