import type { Locator, Page } from '@playwright/test'
import { apiTransfer, expect, test } from '../support/fixtures'
import { apiLegacyTrade, apiTrade, TradeForm } from '../support/trade'
import { BACKEND_URL } from '../support/backend'
import { addDays, nyToday } from '../support/ny'

/**
 * FEAT-039 covered call 股票覆盖检查与锁定 E2E (real clock). As the requirement says, the acceptance dates are relative:
 * B = today − 12 plays 2026-11-02 (d(n) = B + n days), short calls expire FUT = today + 40 unless a test needs an
 * already expired contract. Amounts are those of the requirement. Fictional tickers only. Scenario L uses the e2e-only
 * POST /api/test-support/legacy-option-trades. The DB is reset per test, so ids restart at 1 in every test:
 * scenario D = deposit #1, S1 = stock #1, C1 / C2 = option trades #1 / #2.
 */

// ------------------------------------------------------------------------------------------------ selectors
// All data-testids used by this spec, in one place (FEAT-039 ones from the frontend implementation).
const S = {
  // trade entry
  entryForm: 'open-trade-form',
  entryActionStc: 'open-trade-action-stc',
  shortCallHint: 'open-trade-short-call-hint',
  entryError: 'open-trade-error',
  entryConfirmation: 'open-trade-confirmation',
  // positions page (options)
  positionsFrozenTotal: 'positions-frozen-total',
  posRow: (contract: string) => `[data-testid="position-row"][data-contract="${contract}"]`,
  posFrozen: 'position-cell-frozen',
  posCoverageChecked: 'position-cell-coverage-checked', // "已覆盖 N 股" / hover
  posCoverageLegacy: 'position-cell-coverage-legacy', // "未覆盖" / "未覆盖 k 张" / hover
  posContracts: 'position-cell-contracts',
  posClose: 'position-close',
  // close dialog
  closeModal: 'close-trade-modal',
  closeNet: 'close-trade-net-amount',
  closeTime: 'close-trade-time',
  closeSubmit: 'close-trade-submit',
  closeRealizedPnl: 'close-trade-confirmation-realized-pnl',
  closeDone: 'close-trade-done',
  // positions page (stocks)
  stockPositions: 'stock-positions',
  stockPosRow: (symbol: string) => `[data-testid="stock-position-row"][data-symbol="${symbol}"]`,
  stockPosShares: 'stock-position-shares',
  stockPosCovered: 'stock-position-covered-shares',
  stockPosAvailable: 'stock-position-available-shares',
  // stock entry
  stockEntryForm: 'stock-entry-form',
  stockEntryType: (t: 'buy' | 'sell' | 'transfer-in') => `stock-entry-type-${t}`,
  stockEntrySymbol: 'stock-entry-symbol',
  stockEntryShares: 'stock-entry-shares',
  stockEntryAmount: 'stock-entry-amount',
  stockEntryTime: 'stock-entry-time',
  stockEntryDate: 'stock-entry-date',
  stockEntrySubmit: 'stock-entry-submit',
  stockEntryError: 'stock-entry-error',
  stockEntryConfirmation: 'stock-entry-confirmation',
  // stock list
  stockListPage: 'stock-list-page',
  stockRow: (id: number) => `[data-testid="stock-row"][data-stock-trade-id="${id}"]`,
  stockEdit: 'stock-edit',
  stockEditModal: 'stock-edit-modal',
  stockEditShares: 'stock-edit-shares',
  stockEditSubmit: 'stock-edit-submit',
  stockEditError: 'stock-edit-error',
  stockDelete: 'stock-delete',
  stockDeleteModal: 'stock-delete-modal',
  stockDeleteConfirm: 'stock-delete-confirm',
  stockDeleteCancel: 'stock-delete-cancel',
  stockDeleteError: 'stock-delete-error',
  // trade list
  tradeRow: (id: number) => `[data-testid="trade-row"][data-trade-id="${id}"]`,
  tradeEdit: 'trade-edit',
  tradeEditModal: 'trade-edit-modal',
  tradeEditContracts: 'trade-edit-contracts',
  tradeEditNet: 'trade-edit-net-amount',
  tradeEditTime: 'trade-edit-time',
  tradeEditSubmit: 'trade-edit-submit',
  tradeEditCancel: 'trade-edit-cancel',
  tradeEditError: 'trade-edit-error',
  tradeDelete: 'trade-delete',
  tradeDeleteModal: 'trade-delete-modal',
  tradeDeleteConfirm: 'trade-delete-confirm',
  tradeDeleteError: 'trade-delete-error',
  // home / accounts
  dashFrozenTotal: 'dashboard-frozen-total',
  accountSwitcher: 'account-switcher',
  accountOption: (id: number) => `account-option-${id}`,
  accountCurrent: 'account-current',
} as const

const CODE = 'COVERAGE_INSUFFICIENT'

/** Page texts of the requirement (FEAT-039 rule 7). */
const TEXT = {
  hint: '卖出 call 需要同一账户有足够的可用股票覆盖：每张 = multiplier 股，可用股数 = 持股 − 已被其它卖出 call 覆盖的股数，不足时不能保存。卖出 call 不冻结现金。',
  checkedHover: (symbol: string, n: number) => `占用 ${symbol} ${n} 股，平仓、作废或被指派前这些股票不能卖出`,
  legacyHover: (r: number, a: number, at: string, h: number) =>
    `上线前录入，未纳入覆盖检查：需要 ${r} 股，当前可用 ${a} 股；${at} 时持有 ${h} 股。补录该时间之前的股票后自动变为已覆盖`,
  e1_200_100: '需要 200 股 IXQZ，可用 100 股（持有 200 股，其中 100 股已覆盖其它 call）',
  e1_100_0: (symbol: string) => `需要 100 股 ${symbol}，可用 0 股（持有 0 股）`,
  e2: (h: number, c: number) => `卖出 100 股超过可用股数 0 股（持有 ${h} 股，其中 ${c} 股被 call 覆盖），请先平掉对应的 call`,
  e3: (prefix: string, at: string, symbol: string, r: number, h: number) =>
    `${prefix}后 ${at} 起 ${symbol} 不足以覆盖卖出的 call：需要 ${r} 股，持有 ${h} 股`,
}

// ------------------------------------------------------------------------------------------------ dates & data

const T = nyToday()
const B = addDays(T, -12)
/** B + n days (B plays 2026-11-02). */
const d = (n: number) => addDays(B, n)
const at = (date: string, hm = '10:00') => `${date}T${hm}`
const shown = (date: string, hm = '10:00') => `${date} ${hm}`
const FUT = addDays(T, 40)
const C10 = `IXQZ ${FUT} 10 C`
const C11 = `IXQZ ${FUT} 11 C`
const C12 = `IXQZ ${FUT} 12 C`
const C13 = `IXQZ ${FUT} 13 C`
const P40 = `IXQZ ${FUT} 40 P`

type StockType = 'BUY' | 'SELL' | 'TRANSFER_IN'

async function apiStock(type: StockType, symbol: string, shares: number, amount: string, time: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, symbol, shares, amount, ...(type === 'TRANSFER_IN' ? { tradeDate: time } : { tradedAtLocal: time }), note: null }),
  })
  if (r.status !== 201) throw new Error(`seed stock ${type} ${symbol} failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiAssign(body: { symbol: string; expirationDate: string; strike: string; optionType: 'CALL' | 'PUT'; contracts: number; assignmentDate: string }): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/assignments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assignmentFee: null, note: null, ...body }),
  })
  if (r.status !== 201) throw new Error(`assign failed ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiExpire(symbol: string, expirationDate: string, strike: string, optionType: 'CALL' | 'PUT', contracts: number): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/expirations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol, expirationDate, strike, optionType, contracts }),
  })
  if (r.status !== 201) throw new Error(`expire failed ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiCreateAccount(name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== 201) throw new Error(`create account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

/** Scenario D up to C1 (deposit #1, S1 = stock #1, C1 = option #1), expiration `exp`. */
async function scenarioDUpToC1(exp = FUT): Promise<void> {
  await apiTransfer('DEPOSIT', '10000.00', at(d(0)))
  await apiStock('TRANSFER_IN', 'IXQZ', 200, '1600.00', d(0))
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: exp, strike: '10', type: 'CALL', netAmount: '50.00', time: at(d(1)) })
}

/** Scenario D: + C2 = option #2. IXQZ 200 / 200 / 0. */
async function scenarioD(exp = FUT): Promise<void> {
  await scenarioDUpToC1(exp)
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: exp, strike: '11', type: 'CALL', netAmount: '30.00', time: at(d(2)) })
}

/** Scenario L: deposit 2,000.00; L1 = pre-launch STO 2 IXQZ 10 C 90.00 (option #1); no stock. */
async function scenarioL(): Promise<void> {
  await apiTransfer('DEPOSIT', '2000.00', at(d(0)))
  await apiLegacyTrade({ symbol: 'IXQZ', expiration: FUT, strike: '10', contracts: 2, netAmount: '90.00', time: at(d(1)) })
}

// ------------------------------------------------------------------------------------------------ page helpers

class Ui {
  readonly trade: TradeForm

  constructor(readonly page: Page) {
    this.trade = new TradeForm(page)
  }

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

  async openPositions(): Promise<void> {
    await this.page.goto('/positions')
    await expect(this.id(S.positionsFrozenTotal)).toBeVisible()
  }

  /** Stock position "shares / covered / available" as shown. */
  async expectStock(symbol: string, shares: string, covered: string, available: string): Promise<void> {
    const row = this.page.locator(S.stockPosRow(symbol))
    await expect(row.getByTestId(S.stockPosShares)).toHaveText(shares)
    await expect(row.getByTestId(S.stockPosCovered)).toHaveText(covered)
    await expect(row.getByTestId(S.stockPosAvailable)).toHaveText(available)
  }

  async expectChecked(contract: string, text: string): Promise<void> {
    const cell = this.posRow(contract).getByTestId(S.posFrozen)
    await expect(cell.getByTestId(S.posCoverageChecked)).toHaveText(text)
    await expect(cell.getByTestId(S.posCoverageLegacy)).toHaveCount(0)
  }

  async expectLegacyOnly(contract: string, text = '未覆盖'): Promise<void> {
    const cell = this.posRow(contract).getByTestId(S.posFrozen)
    await expect(cell.getByTestId(S.posCoverageLegacy)).toHaveText(text)
    await expect(cell.getByTestId(S.posCoverageChecked)).toHaveCount(0)
  }

  /** Hovers a coverage tag and expects the tooltip text. */
  async expectHover(tag: Locator, text: string): Promise<void> {
    await tag.hover()
    await expect(this.page.getByRole('tooltip').filter({ hasText: text })).toBeVisible()
    await this.page.mouse.move(0, 0)
  }

  async openEntry(): Promise<void> {
    await this.trade.open()
  }

  /** STO call through the 录入交易 page; returns nothing, caller checks result. */
  async fillShortCall(t: { strike: string; contracts?: string; net: string; time: string; multiplier?: string; symbol?: string }): Promise<void> {
    await this.trade.fill({
      action: 'STO',
      symbol: t.symbol ?? 'IXQZ',
      expiration: FUT,
      strike: t.strike,
      type: 'CALL',
      contracts: t.contracts ?? '1',
      netAmount: t.net,
      time: t.time,
      multiplier: t.multiplier ?? '100',
    })
  }

  async expectEntryRejected(message: string): Promise<void> {
    await this.trade.submit.click()
    await expect(this.id(S.entryError)).toHaveText(message)
    await expect(this.id(S.entryError)).toHaveAttribute('data-error-code', CODE)
    await expect(this.id(S.entryConfirmation)).toHaveCount(0)
  }

  async expectEntrySaved(): Promise<void> {
    await this.trade.submit.click()
    await expect(this.id(S.entryConfirmation)).toBeVisible()
    await expect(this.id(S.entryError)).toHaveCount(0)
  }

  async openStockEntry(): Promise<void> {
    await this.page.goto('/stocks/new')
    await expect(this.id(S.stockEntryForm)).toBeVisible()
  }

  async fillStock(e: { type: 'buy' | 'sell' | 'transfer-in'; symbol: string; shares: string; amount: string; time?: string; date?: string }): Promise<void> {
    await this.id(S.stockEntryType(e.type)).check()
    await this.id(S.stockEntrySymbol).fill(e.symbol)
    await this.id(S.stockEntryShares).fill(e.shares)
    await this.id(S.stockEntryAmount).fill(e.amount)
    if (e.time !== undefined) await this.id(S.stockEntryTime).fill(e.time)
    if (e.date !== undefined) await this.id(S.stockEntryDate).fill(e.date)
  }

  async stockRejected(message: string): Promise<void> {
    await this.id(S.stockEntrySubmit).click()
    await expect(this.id(S.stockEntryError)).toHaveText(message)
    await expect(this.id(S.stockEntryError)).toHaveAttribute('data-error-code', CODE)
    await expect(this.id(S.stockEntryConfirmation)).toHaveCount(0)
  }

  async stockSaved(): Promise<void> {
    await this.id(S.stockEntrySubmit).click()
    await expect(this.id(S.stockEntryConfirmation)).toBeVisible()
    await expect(this.id(S.stockEntryError)).toHaveCount(0)
  }

  async openStocks(): Promise<void> {
    await this.page.goto('/stocks')
    await expect(this.id(S.stockListPage)).toBeVisible()
  }

  async openTrades(): Promise<void> {
    await this.page.goto('/trades')
    await expect(this.id('trade-row').first()).toBeVisible()
  }

  async openTradeEdit(id: number): Promise<Locator> {
    await this.page.locator(S.tradeRow(id)).getByTestId(S.tradeEdit).click()
    const dlg = this.dialog(S.tradeEditModal)
    await expect(dlg).toBeVisible()
    return dlg
  }

  async tradeEditRejected(dlg: Locator, message: string): Promise<void> {
    await dlg.getByTestId(S.tradeEditSubmit).click()
    await expect(dlg.getByTestId(S.tradeEditError)).toHaveText(message)
    await expect(dlg.getByTestId(S.tradeEditError)).toHaveAttribute('data-error-code', CODE)
    await expect(dlg).toBeVisible()
  }

  async tradeDeleteRejected(id: number, message: string): Promise<void> {
    await this.page.locator(S.tradeRow(id)).getByTestId(S.tradeDelete).click()
    const dlg = this.dialog(S.tradeDeleteModal)
    await expect(dlg).toBeVisible()
    await dlg.getByTestId(S.tradeDeleteConfirm).click()
    await expect(dlg.getByTestId(S.tradeDeleteError)).toHaveText(message)
    await expect(dlg.getByTestId(S.tradeDeleteError)).toHaveAttribute('data-error-code', CODE)
    await expect(dlg).toBeVisible()
    await this.page.keyboard.press('Escape')
    await expect(dlg).toHaveCount(0)
    await expect(this.page.locator(S.tradeRow(id))).toBeVisible()
  }

  async stockDeleteRejected(id: number, message: string): Promise<void> {
    await this.page.locator(S.stockRow(id)).getByTestId(S.stockDelete).click()
    const dlg = this.dialog(S.stockDeleteModal)
    await dlg.getByTestId(S.stockDeleteConfirm).click()
    await expect(dlg.getByTestId(S.stockDeleteError)).toHaveText(message)
    await expect(dlg.getByTestId(S.stockDeleteError)).toHaveAttribute('data-error-code', CODE)
    await expect(dlg).toBeVisible()
    await dlg.getByTestId(S.stockDeleteCancel).click()
    await expect(dlg).toHaveCount(0)
  }

  async stockDeleteOk(id: number): Promise<void> {
    await this.page.locator(S.stockRow(id)).getByTestId(S.stockDelete).click()
    const dlg = this.dialog(S.stockDeleteModal)
    await dlg.getByTestId(S.stockDeleteConfirm).click()
    await expect(dlg).toHaveCount(0)
    await expect(this.page.locator(S.stockRow(id))).toHaveCount(0)
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id(S.accountSwitcher).click()
    await this.id(S.accountOption(id)).click()
    await expect(this.id(S.accountCurrent)).toHaveText(name)
  }
}

// ------------------------------------------------------------------------------------------------ tests

test.describe('FEAT-039 covered call 覆盖检查与锁定', () => {
  test('AC8 录入页提示（Q61 新文案）：STO + Call 显示全文；BTO + Call、STO + Put、平仓不显示；不阻止提交', async ({ page }) => {
    const ui = new Ui(page)
    await ui.openEntry()
    const hint = ui.id(S.shortCallHint)
    await expect(hint).toHaveCount(0)
    await ui.trade.actionSto.check()
    await ui.trade.typeCall.check()
    await expect(hint).toHaveText(TEXT.hint)
    await ui.trade.actionBto.check()
    await expect(hint).toHaveCount(0)
    await ui.trade.actionSto.check()
    await ui.trade.typePut.check()
    await expect(hint).toHaveCount(0)
    await ui.trade.typeCall.check()
    await expect(hint).toHaveText(TEXT.hint)
    await ui.id(S.entryActionStc).check()
    await expect(hint).toHaveCount(0)
    await expect(page.getByText('未冻结')).toHaveCount(0)
  })

  test('AC1 + AC8 场景 D 经页面：STO 2 张 11 C → E1 拒绝、输入保留；改 1 张保存；持仓页 C1/C2「已覆盖 100 股」+ 悬停；40 P 冻结 4,000.00；股票 200/200/0；无"未冻结"', async ({ page }) => {
    await scenarioDUpToC1()
    const ui = new Ui(page)
    await ui.openEntry()
    await ui.fillShortCall({ strike: '11', contracts: '2', net: '60.00', time: at(d(2)) })
    await ui.expectEntryRejected(TEXT.e1_200_100)
    await expect(ui.trade.contracts).toHaveValue('2')
    await expect(ui.trade.strike).toHaveValue('11')
    await ui.trade.contracts.fill('1')
    await ui.trade.netAmount.fill('30.00')
    await ui.expectEntrySaved()

    await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: FUT, strike: '40', type: 'PUT', netAmount: '70.00', time: at(d(2), '11:00') })
    await ui.openPositions()
    await ui.expectChecked(C10, '已覆盖 100 股')
    await ui.expectChecked(C11, '已覆盖 100 股')
    await expect(ui.posRow(P40).getByTestId(S.posFrozen)).toHaveText('4,000.00')
    await expect(ui.posRow(P40).getByTestId(S.posCoverageChecked)).toHaveCount(0)
    await expect(ui.id(S.positionsFrozenTotal)).toHaveText('冻结现金合计 4,000.00')
    await ui.expectStock('IXQZ', '200', '200', '0')
    await ui.expectHover(ui.posRow(C10).getByTestId(S.posCoverageChecked), TEXT.checkedHover('IXQZ', 100))
    await expect(page.getByText('未冻结')).toHaveCount(0)

    await page.goto('/')
    await expect(ui.id(S.dashFrozenTotal)).toHaveText('4,000.00')
    await expect(page.getByText('未冻结')).toHaveCount(0)
    await page.goto('/trades')
    await expect(ui.id('trade-row').first()).toBeVisible()
    await expect(page.getByText('未冻结')).toHaveCount(0)
  })

  test('AC2 锁定与 BTC 释放（Q78）：页面卖出 → E2；持仓页 BTC C2 → 200/100/100；10:30 卖出仍 E2；12:00 成功 100/100/0；删除 / 改时间 BTC → E3', async ({ page }) => {
    await scenarioD()
    const ui = new Ui(page)
    await ui.openStockEntry()
    await ui.fillStock({ type: 'sell', symbol: 'IXQZ', shares: '100', amount: '1000.00', time: at(d(3)) })
    await ui.stockRejected(TEXT.e2(200, 200))
    await expect(ui.id(S.stockEntryShares)).toHaveValue('100')

    await ui.openPositions()
    await ui.posRow(C11).getByTestId(S.posClose).click()
    const m = page.getByTestId(S.closeModal)
    await m.getByTestId(S.closeNet).fill('10.00')
    await m.getByTestId(S.closeTime).fill(at(d(3), '11:00'))
    await m.getByTestId(S.closeSubmit).click()
    await expect(m.getByTestId(S.closeRealizedPnl)).toHaveText('+20.00')
    await m.getByTestId(S.closeDone).click()
    await ui.expectStock('IXQZ', '200', '100', '100')
    await ui.expectChecked(C10, '已覆盖 100 股')

    await ui.openStockEntry()
    await ui.fillStock({ type: 'sell', symbol: 'IXQZ', shares: '100', amount: '1000.00', time: at(d(3), '10:30') })
    await ui.stockRejected(TEXT.e2(200, 200))
    await ui.id(S.stockEntryTime).fill(at(d(3), '12:00'))
    await ui.stockSaved()
    await ui.openPositions()
    await ui.expectStock('IXQZ', '100', '100', '0')

    // BTC = option trade #3
    await ui.openTrades()
    await ui.tradeDeleteRejected(3, TEXT.e3('删除', shown(d(3), '12:00'), 'IXQZ', 200, 100))
    const dlg = await ui.openTradeEdit(3)
    await dlg.getByTestId(S.tradeEditTime).fill(at(d(3), '12:30'))
    await ui.tradeEditRejected(dlg, TEXT.e3('修改', shown(d(3), '12:00'), 'IXQZ', 200, 100))
    await expect(dlg.getByTestId(S.tradeEditTime)).toHaveValue(at(d(3), '12:30'))
  })

  test('AC3 修改 / 删除重放（Q19）：S1 改 150 股 → E3；删除 S1 → E3；C2 张数改 2 → E1；C1 净额改 55.00 → 成功', async ({ page }) => {
    await scenarioD()
    const ui = new Ui(page)
    await ui.openStocks()
    await page.locator(S.stockRow(1)).getByTestId(S.stockEdit).click()
    const se = ui.dialog(S.stockEditModal)
    await expect(se).toBeVisible()
    await se.getByTestId(S.stockEditShares).fill('150')
    await se.getByTestId(S.stockEditSubmit).click()
    await expect(se.getByTestId(S.stockEditError)).toHaveText(TEXT.e3('修改', shown(d(2)), 'IXQZ', 200, 150))
    await expect(se.getByTestId(S.stockEditError)).toHaveAttribute('data-error-code', CODE)
    await expect(se.getByTestId(S.stockEditShares)).toHaveValue('150')
    await page.keyboard.press('Escape')
    await expect(se).toHaveCount(0)
    await ui.stockDeleteRejected(1, TEXT.e3('删除', shown(d(1)), 'IXQZ', 100, 0))

    await ui.openTrades()
    let dlg = await ui.openTradeEdit(2)
    await dlg.getByTestId(S.tradeEditContracts).fill('2')
    await ui.tradeEditRejected(dlg, TEXT.e1_200_100)
    await dlg.getByTestId(S.tradeEditCancel).click()
    await expect(dlg).toHaveCount(0)

    dlg = await ui.openTradeEdit(1)
    await dlg.getByTestId(S.tradeEditNet).fill('55.00')
    await dlg.getByTestId(S.tradeEditSubmit).click()
    await expect(dlg).toHaveCount(0)
    await ui.openPositions()
    await ui.expectChecked(C10, '已覆盖 100 股')
    await ui.expectStock('IXQZ', '200', '200', '0')
  })

  test('AC4 作废与 call 被指派（已过期合约）：被指派 C2 → 100/100/0；到期日 15:00 卖出 → E2；作废 C1 → 100/0/100；之后卖出成功；删除作废 → E3', async ({ page }) => {
    const EXP = addDays(T, -3)
    await scenarioD(EXP)
    await apiAssign({ symbol: 'IXQZ', expirationDate: EXP, strike: '11', optionType: 'CALL', contracts: 1, assignmentDate: EXP }) // option #3
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectStock('IXQZ', '100', '100', '0')
    await ui.expectChecked(`IXQZ ${EXP} 10 C`, '已覆盖 100 股')

    await ui.openStockEntry()
    await ui.fillStock({ type: 'sell', symbol: 'IXQZ', shares: '100', amount: '1050.00', time: at(EXP, '15:00') })
    await ui.stockRejected(TEXT.e2(200, 200))

    await apiExpire('IXQZ', EXP, '10', 'CALL', 1) // option #4
    await ui.openPositions()
    await ui.expectStock('IXQZ', '100', '0', '100')
    await ui.openStockEntry()
    await ui.fillStock({ type: 'sell', symbol: 'IXQZ', shares: '100', amount: '1050.00', time: at(addDays(EXP, 1)) })
    await ui.stockSaved()

    await ui.openTrades()
    await ui.tradeDeleteRejected(4, TEXT.e3('删除', shown(addDays(EXP, 1)), 'IXQZ', 100, 0))
  })

  test('AC5 wheel：无股票页面卖 call → E1；put 被指派后，到期日 15:00 仍拒、之后成功 100/100/0；删除 put 被指派 → E3', async ({ page }) => {
    const PEXP = addDays(T, -3)
    await apiTransfer('DEPOSIT', '5000.00', at(d(0)))
    await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: PEXP, strike: '40', type: 'PUT', netAmount: '80.00', time: at(d(1)) }) // #1
    const ui = new Ui(page)
    await ui.openEntry()
    await ui.fillShortCall({ symbol: 'QRTV', strike: '45', net: '60.00', time: at(d(3)) })
    await ui.expectEntryRejected(TEXT.e1_100_0('QRTV'))

    await apiAssign({ symbol: 'QRTV', expirationDate: PEXP, strike: '40', optionType: 'PUT', contracts: 1, assignmentDate: PEXP }) // #2
    await ui.openEntry()
    await ui.fillShortCall({ symbol: 'QRTV', strike: '45', net: '60.00', time: at(PEXP, '15:00') })
    await ui.expectEntryRejected(TEXT.e1_100_0('QRTV'))
    const after = at(addDays(T, -1), '09:45')
    await ui.trade.time.fill(after)
    await ui.expectEntrySaved()
    await ui.openPositions()
    await ui.expectStock('QRTV', '100', '100', '0')
    await ui.expectChecked(`QRTV ${FUT} 45 C`, '已覆盖 100 股')

    await ui.openTrades()
    await ui.tradeDeleteRejected(2, TEXT.e3('删除', shown(addDays(T, -1), '09:45'), 'QRTV', 100, 0))
  })

  test('AC6 + AC7 旧批次（场景 L）：未覆盖 + 悬停；买入后仍未覆盖；新卖 call 成功；改张数 → E1；页面转入 d(2) 仍未覆盖；删除后转入 d(−1) → 已覆盖 200 股；卖出 → E2；删除转入 → E3', async ({ page }) => {
    await scenarioL()
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectLegacyOnly(C10)
    await ui.expectHover(ui.posRow(C10).getByTestId(S.posCoverageLegacy), TEXT.legacyHover(200, 0, shown(d(1)), 0))
    await expect(page.getByText('未冻结')).toHaveCount(0)

    await apiStock('BUY', 'IXQZ', 100, '800.00', at(d(8))) // stock #1
    await ui.openEntry()
    await ui.fillShortCall({ strike: '12', net: '20.00', time: at(d(8), '11:00') })
    await ui.expectEntrySaved() // option #2
    await ui.openPositions()
    await ui.expectLegacyOnly(C10)
    await ui.expectChecked(C12, '已覆盖 100 股')
    await ui.expectStock('IXQZ', '100', '100', '0')
    await ui.expectHover(ui.posRow(C10).getByTestId(S.posCoverageLegacy), TEXT.legacyHover(200, 0, shown(d(1)), 0))

    await ui.openTrades()
    const dlg = await ui.openTradeEdit(1)
    await dlg.getByTestId(S.tradeEditContracts).fill('1')
    await ui.tradeEditRejected(dlg, TEXT.e1_100_0('IXQZ'))
    await dlg.getByTestId(S.tradeEditCancel).click()

    // AC7: 转入 on d(2) (after L1's d(1) open) → still 未覆盖
    await ui.openStockEntry()
    await ui.fillStock({ type: 'transfer-in', symbol: 'IXQZ', shares: '200', amount: '1500.00', date: d(2) })
    await ui.stockSaved() // stock #2
    await ui.openPositions()
    await ui.expectLegacyOnly(C10)
    await ui.expectStock('IXQZ', '300', '100', '200')
    await ui.expectHover(ui.posRow(C10).getByTestId(S.posCoverageLegacy), TEXT.legacyHover(200, 200, shown(d(1)), 0))

    await ui.openStocks()
    await ui.stockDeleteOk(2)
    await ui.openStockEntry()
    await ui.fillStock({ type: 'transfer-in', symbol: 'IXQZ', shares: '200', amount: '1500.00', date: d(-1) })
    await ui.stockSaved() // stock #3
    await ui.openPositions()
    await ui.expectChecked(C10, '已覆盖 200 股')
    await ui.expectStock('IXQZ', '300', '300', '0')

    await ui.openStockEntry()
    await ui.fillStock({ type: 'sell', symbol: 'IXQZ', shares: '100', amount: '1000.00', time: at(d(9)) })
    await ui.stockRejected(TEXT.e2(300, 300))
    await ui.openStocks()
    await ui.stockDeleteRejected(3, TEXT.e3('删除', shown(d(1)), 'IXQZ', 200, 0))
  })

  test('规则 7：一行同时有受检和旧批次 → 两个标签「已覆盖 100 股」「未覆盖 2 张」', async ({ page }) => {
    await scenarioL()
    await apiStock('BUY', 'IXQZ', 100, '800.00', at(d(8)))
    await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: FUT, strike: '10', type: 'CALL', netAmount: '20.00', time: at(d(8), '11:00') })
    const ui = new Ui(page)
    await ui.openPositions()
    await expect(ui.posRow(C10).getByTestId(S.posContracts)).toHaveText('3')
    const cell = ui.posRow(C10).getByTestId(S.posFrozen)
    await expect(cell.getByTestId(S.posCoverageChecked)).toHaveText('已覆盖 100 股')
    await expect(cell.getByTestId(S.posCoverageLegacy)).toHaveText('未覆盖 2 张')
  })

  test('AC9 multiplier 10 与账户隔离：买入 10 股 → 210/200/10；页面 STO 13 C ×10 → 已覆盖 10 股、210/210/0；再录 → E1 需要 10 股；小账户 → E1 需要 100 股；默认账户不变', async ({ page }) => {
    await scenarioD()
    await apiStock('BUY', 'IXQZ', 10, '85.00', at(d(2), '11:00'))
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectStock('IXQZ', '210', '200', '10')

    await ui.openEntry()
    await ui.fillShortCall({ strike: '13', net: '5.00', time: at(d(2), '12:00'), multiplier: '10' })
    await ui.expectEntrySaved()
    await ui.openPositions()
    await ui.expectChecked(C13, '已覆盖 10 股')
    await ui.expectStock('IXQZ', '210', '210', '0')
    await ui.openEntry()
    await ui.fillShortCall({ strike: '13', net: '5.00', time: at(d(2), '12:00'), multiplier: '10' })
    await ui.expectEntryRejected('需要 10 股 IXQZ，可用 0 股（持有 210 股，其中 210 股已覆盖其它 call）')

    const small = await apiCreateAccount('小账户')
    await ui.openEntry()
    await ui.switchAccount(small, '小账户')
    await ui.fillShortCall({ strike: '10', net: '50.00', time: at(d(2)) })
    await ui.expectEntryRejected(TEXT.e1_100_0('IXQZ'))
    await ui.switchAccount(1, '默认账户')
    await ui.openPositions()
    await ui.expectStock('IXQZ', '210', '210', '0')
    await ui.expectChecked(C10, '已覆盖 100 股')
  })
})
