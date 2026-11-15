import type { Locator, Page } from '@playwright/test'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, nyInstant, setBackendNow } from '../support/clock'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'

/**
 * FEAT-046 全部账户合并查看（二）E2E: 交易列表、股票交易、已平仓、资金页流水 under 全部账户 (fixed backend clock +
 * page.clock; no market data needed; never the real CBOE / Finnhub; ports from support/backend.ts, never 8080 / 5173).
 *
 * Scenario L (requirement; today 2026-11-23 Mon 10:00 New York; fictional names / tickers; entered in order #1..#13,
 * so the system ids are: option trades #4→1 … #11→8; stock records #3→1, #12→2, #13→3; ledger #1→1, #2→2, #4→3 … #13→12):
 *   #1 甲 deposit 50,000.00 10-01 09:30 · #2 乙 deposit 20,000.00 10-01 09:30 · #3 甲 转入 IXQZ 100 sh 5,880.00 10-01
 *   #4 乙 BTO QRTV 12-18 55C 50.00 10-15 · #5 乙 STC 55C 80.00 10-20 (+30) · #6 甲 STO 12-18 48P 100.00 11-02
 *   #7 乙 STO 11-20 45P 60.00 11-02 · #8 甲 BTC 48P 40.00 11-10 (+60) · #9 甲 STO 12-18 50P 118.70 11-16 10:00
 *   #10 乙 STO 12-18 50P 120.00 11-16 10:00 · #11 甲 STO IXQZ 11-27 65C 84.35 11-16 10:30 (covered)
 *   #12 乙 buy QRTV 100 sh 4,800.00 11-17 · #13 乙 sell QRTV 50 sh 2,450.00 11-18 (+50)
 *   then in 乙 the note of #10 is changed to "同价位" (the only record with an audit log).
 *
 * Test ids: new FEAT-046 ids are in the TID block below, reconciled with docs/architecture.md "FEAT-046 testid";
 * known ids from FEAT-002/005/006/007/011/035/040 are used directly.
 */

// ================================================================================================ test ids (FEAT-046)
const TID = {
  /** 账户 cell (first column, ALL only) of each list row; rows carry data-account-id. */
  tradeAccountCell: 'trade-cell-account',
  stockAccountCell: 'stock-cell-account',
  closedAccountCell: 'closed-cell-account',
  ledgerAccountCell: 'cash-ledger-account',
  rowAccountAttr: 'data-account-id',
  /** FEAT-035 placeholder (must be gone on the 4 pages) and its ledger variant. */
  placeholder: 'all-accounts-placeholder',
  ledgerPlaceholder: 'cash-ledger-all-accounts',
  /** FEAT-035 switcher entry and cash summary sum row. */
  optionAll: 'account-option-all',
  cashTotalRow: 'cash-summary-sum-row',
} as const

// ================================================================================================ texts (requirement)
const A_NAME = '甲账户'
const B_NAME = '乙账户'
const CURRENT_ALL = '当前：全部账户（只读）'
const CURRENT_ALL_NAME = '全部账户（只读）'
const BALANCE_HEADER_ALL = '账户余额' // Q228
const C_48P = 'QRTV 2026-12-18 48 P'
const C_55C = 'QRTV 2026-12-18 55 C'
const C_50P = 'QRTV 2026-12-18 50 P'

/** System ids of scenario L (contract table). */
const T = { n4: 1, n5: 2, n6: 3, n7: 4, n8: 5, n9: 6, n10: 7, n11: 8 } as const
const S = { n3: 1, n12: 2, n13: 3 } as const

const ny = (local: string): string => nyInstant(local)

// ================================================================================================ seeding (API)

async function accountsCall(method: 'POST' | 'PUT', path: string, name: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/accounts${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, brokerNote: null }),
  })
  if (r.status !== (method === 'POST' ? 201 : 200)) throw new Error(`${method} account ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiClose(t: { action: 'BTC' | 'STC'; strike: string; type: 'CALL' | 'PUT'; netAmount: string; time: string }): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades/closes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ symbol: 'QRTV', expirationDate: '2026-12-18', strike: t.strike, optionType: t.type, action: t.action, contracts: 1, netAmount: t.netAmount, tradedAtLocal: t.time }),
  })
  if (r.status !== 201) throw new Error(`close failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiStock(type: 'BUY' | 'SELL', shares: number, amount: string, time: string): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, symbol: 'QRTV', shares, amount, tradedAtLocal: time, note: null }),
  })
  if (r.status !== 201) throw new Error(`${type} failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

async function apiEditNote(tradeId: number, net: string, time: string, note: string, account: number | 'ALL'): Promise<Response> {
  return fetch(`${BACKEND_URL}/api/option-trades/${tradeId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'X-Account-Id': String(account) },
    body: JSON.stringify({ contracts: 1, netAmount: net, tradedAtLocal: time, multiplier: 100, note }),
  })
}

function expectId(what: string, actual: number, expected: number): void {
  if (actual !== expected) throw new Error(`${what}: expected system id ${expected}, got ${actual}`)
}

/** Scenario L; backend and browser clock 2026-11-23 10:00 New York; API account 1. */
async function scenarioL(page: Page): Promise<void> {
  await setBackendNow(ny('2026-11-23T10:00'))
  setApiAccount(1)
  await accountsCall('PUT', '/1', A_NAME)
  const b = await accountsCall('POST', '', B_NAME)
  if (b !== 2) throw new Error(`乙账户 expected id 2, got ${b}`)

  setApiAccount(1)
  await apiTransfer('DEPOSIT', '50000.00', '2026-10-01T09:30') // #1
  setApiAccount(2)
  await apiTransfer('DEPOSIT', '20000.00', '2026-10-01T09:30') // #2
  setApiAccount(1)
  expectId('#3', await apiTransferInShares('IXQZ', 100, '2026-10-01', '5880.00'), S.n3)
  setApiAccount(2)
  expectId('#4', (await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: '2026-12-18', strike: '55', type: 'CALL', netAmount: '50.00', time: '2026-10-15T10:00' })).id, T.n4)
  expectId('#5', await apiClose({ action: 'STC', strike: '55', type: 'CALL', netAmount: '80.00', time: '2026-10-20T10:00' }), T.n5)
  setApiAccount(1)
  expectId('#6', (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '48', type: 'PUT', netAmount: '100.00', time: '2026-11-02T10:00' })).id, T.n6)
  setApiAccount(2)
  expectId('#7', (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-11-20', strike: '45', type: 'PUT', netAmount: '60.00', time: '2026-11-02T10:00' })).id, T.n7)
  setApiAccount(1)
  expectId('#8', await apiClose({ action: 'BTC', strike: '48', type: 'PUT', netAmount: '40.00', time: '2026-11-10T10:00' }), T.n8)
  expectId('#9', (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '118.70', time: '2026-11-16T10:00' })).id, T.n9)
  setApiAccount(2)
  expectId('#10', (await apiTrade({ action: 'STO', symbol: 'QRTV', expiration: '2026-12-18', strike: '50', type: 'PUT', netAmount: '120.00', time: '2026-11-16T10:00' })).id, T.n10)
  setApiAccount(1)
  expectId('#11', (await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: '2026-11-27', strike: '65', type: 'CALL', netAmount: '84.35', time: '2026-11-16T10:30' })).id, T.n11)
  setApiAccount(2)
  expectId('#12', await apiStock('BUY', 100, '4800.00', '2026-11-17T10:00'), S.n12)
  expectId('#13', await apiStock('SELL', 50, '2450.00', '2026-11-18T10:00'), S.n13)
  const edit = await apiEditNote(T.n10, '120.00', '2026-11-16T10:00', '同价位', 2)
  if (edit.status !== 200) throw new Error(`edit #10 note: ${edit.status} ${await edit.text()}`)
  setApiAccount(1)
  await fixNow(page, ny('2026-11-23T10:00'))
}

// ================================================================================================ page helpers

const id = (page: Page, testId: string): Locator => page.getByTestId(testId)

async function expectAllMode(page: Page): Promise<void> {
  await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'true')
  await expect(id(page, 'account-switcher')).toContainText(CURRENT_ALL)
  await expect(id(page, 'account-current')).toHaveText(CURRENT_ALL_NAME)
}

async function switchToAll(page: Page): Promise<void> {
  await id(page, 'account-switcher').click()
  await id(page, TID.optionAll).click()
  await expectAllMode(page)
}

async function switchTo(page: Page, accountId: number, name: string): Promise<void> {
  await id(page, 'account-switcher').click()
  await id(page, `account-option-${accountId}`).click()
  await expect(id(page, 'account-current')).toHaveText(name)
  await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
}

/** Text of cell `cellId` in each row matched by `rowTestId`, top to bottom. */
async function column(page: Page, rowTestId: string, cellId: string): Promise<string[]> {
  return page.getByTestId(rowTestId).evaluateAll(
    (rs, c) => rs.map((r) => r.querySelector(`[data-testid="${c}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''),
    cellId,
  )
}

async function attr(page: Page, rowTestId: string, name: string): Promise<string[]> {
  return page.getByTestId(rowTestId).evaluateAll((rs, n) => rs.map((r) => r.getAttribute(n) ?? ''), name)
}

/** Header texts of the table inside `container`. */
async function headers(container: Locator): Promise<string[]> {
  return (await container.locator('thead th').allTextContents()).map((h) => h.trim())
}

// --- 交易列表
const tradeRow = (page: Page, tradeId: number): Locator => page.locator(`[data-testid="trade-row"][data-trade-id="${tradeId}"]`)
const tradeIds = async (page: Page): Promise<number[]> => (await attr(page, 'trade-row', 'data-trade-id')).map(Number)

async function openTrades(page: Page, query = ''): Promise<void> {
  await page.goto(`/trades${query}`)
  await expect(id(page, 'trade-list-page')).toBeVisible()
}

async function expectTrades(page: Page, ids: number[], total: number): Promise<void> {
  await expect.poll(() => tradeIds(page)).toEqual(ids)
  await expect(id(page, 'trade-list-total')).toHaveText(`共 ${total} 条`)
}

// --- 股票交易
const stockRow = (page: Page, stockId: number): Locator => page.locator(`[data-testid="stock-row"][data-stock-trade-id="${stockId}"]`)
const stockIds = async (page: Page): Promise<number[]> => (await attr(page, 'stock-row', 'data-stock-trade-id')).map(Number)

// --- 已平仓
const closedKeys = async (page: Page): Promise<string[]> =>
  page.getByTestId('closed-row').evaluateAll((rs) => rs.map((r) => `${r.getAttribute('data-record-type')}-${r.getAttribute('data-trade-id')}`))
const closedRow = (page: Page, key: string): Locator => {
  const [type, tid] = key.split('-')
  return page.locator(`[data-testid="closed-row"][data-record-type="${type}"][data-trade-id="${tid}"]`)
}

async function expectClosed(page: Page, keys: string[], count: number, total: string): Promise<void> {
  await expect(id(page, 'closed-count')).toHaveText(`共 ${count} 笔平仓`)
  await expect(id(page, 'closed-pnl-total')).toHaveText(total)
  await expect.poll(() => closedKeys(page)).toEqual(keys)
}

async function expectNoListWriteControls(page: Page): Promise<void> {
  for (const w of ['trade-list-new', 'trade-list-empty-new', 'trade-edit', 'trade-delete', 'stock-list-new', 'stock-list-empty-new', 'stock-edit', 'stock-delete', 'cash-ledger-edit', 'cash-ledger-delete', 'cash-ledger-trades-link', 'cash-ledger-stocks-link']) {
    await expect(id(page, w), `${w} hidden under ALL`).toHaveCount(0)
  }
  await expect(page.getByRole('button', { name: '修改' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0)
}

// ================================================================================================ tests

test.describe('FEAT-046 全部账户合并查看（二）：交易列表、股票交易、已平仓、资金流水', () => {
  test('AC1 交易列表 ALL：共 8 条，#11 甲 +84.35、#10 乙 +120.00 冻结 +5,000.00、#9 甲 +118.70、#8 甲 BTC −40.00 冻结 −4,800.00、#7 乙、#6 甲、#5 乙 STC +80.00、#4 乙 BTO −50.00；没有录入开仓 / 修改 / 删除', async ({ page }) => {
    await scenarioL(page)
    await openTrades(page)
    await switchToAll(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)
    expect(await column(page, 'trade-row', TID.tradeAccountCell)).toEqual([A_NAME, B_NAME, A_NAME, A_NAME, B_NAME, A_NAME, B_NAME, B_NAME])
    expect(await attr(page, 'trade-row', TID.rowAccountAttr)).toEqual(['1', '2', '1', '1', '2', '1', '2', '2'])
    expect((await headers(id(page, 'trade-list')))[0]).toBe('账户')

    const r11 = tradeRow(page, T.n11)
    await expect(r11.getByTestId('trade-cell-contract')).toHaveText('IXQZ 2026-11-27 65 C')
    await expect(r11.getByTestId('trade-cell-net-amount')).toHaveText('+84.35')
    const r10 = tradeRow(page, T.n10)
    await expect(r10.getByTestId('trade-cell-net-amount')).toHaveText('+120.00')
    await expect(r10.getByTestId('trade-cell-frozen')).toHaveText('+5,000.00')
    await expect(tradeRow(page, T.n9).getByTestId('trade-cell-net-amount')).toHaveText('+118.70')
    const r8 = tradeRow(page, T.n8)
    await expect(r8.getByTestId('trade-cell-action')).toContainText('BTC')
    await expect(r8.getByTestId('trade-cell-net-amount')).toHaveText(`${MINUS}40.00`)
    await expect(r8.getByTestId('trade-cell-frozen')).toHaveText(`${MINUS}4,800.00`)
    const r5 = tradeRow(page, T.n5)
    await expect(r5.getByTestId('trade-cell-action')).toContainText('STC')
    await expect(r5.getByTestId('trade-cell-net-amount')).toHaveText('+80.00')
    const r4 = tradeRow(page, T.n4)
    await expect(r4.getByTestId('trade-cell-action')).toContainText('BTO')
    await expect(r4.getByTestId('trade-cell-net-amount')).toHaveText(`${MINUS}50.00`)

    await expectNoListWriteControls(page)
    await expect(page.getByRole('link', { name: '录入开仓' })).toHaveCount(0)
  })

  test('AC2 交易列表筛选 ALL：qrtv → 7 条；点 #10 合约 → QRTV 2026-12-18 50 P，#10 #9 共 2 条；清空后只选 STO → 5 条；后退恢复；切到乙账户 → 地址栏不变，#10 #7，无账户列', async ({ page }) => {
    await scenarioL(page)
    await openTrades(page)
    await switchToAll(page)
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)

    await id(page, 'trade-filter-symbol').fill('qrtv')
    await id(page, 'trade-filter-submit').click()
    await expectTrades(page, [T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 7)

    await tradeRow(page, T.n10).getByTestId('trade-cell-contract').click()
    await expect.poll(() => new URL(page.url()).searchParams.get('strike')).toBe('50')
    const p = new URL(page.url()).searchParams
    expect([p.get('expirationDate'), p.get('optionType')]).toEqual(['2026-12-18', 'PUT'])
    expect(p.get('symbol')?.toUpperCase()).toBe('QRTV')
    await expectTrades(page, [T.n10, T.n9], 2)
    expect(await column(page, 'trade-row', TID.tradeAccountCell)).toEqual([B_NAME, A_NAME])
    await expectAllMode(page)

    await id(page, 'trade-filter-clear').click()
    await expect.poll(() => new URL(page.url()).search).toBe('')
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)
    await page.waitForTimeout(100) // the form is re-keyed from the URL (same as feat-002)
    await id(page, 'trade-filter-action-sto').check()
    await id(page, 'trade-filter-submit').click()
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n7, T.n6], 5)
    const stoSearch = new URL(page.url()).search

    // back restores the previous (cleared) list, forward the STO filter
    await page.goBack()
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)
    await page.goForward()
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n7, T.n6], 5)

    // switch to 乙 with the STO filter: URL kept (Q55), only 乙's rows, no 账户 column
    await switchTo(page, 2, B_NAME)
    expect(new URL(page.url()).search).toBe(stoSearch)
    await expectTrades(page, [T.n10, T.n7], 2)
    await expect(id(page, TID.tradeAccountCell)).toHaveCount(0)
    expect(await headers(id(page, 'trade-list'))).not.toContain('账户')
  })

  test('AC3 股票交易 ALL：共 3 条 #13 乙 卖出 +2,450.00（展开：来自 #12 买入 50 股 2,400.00）、#12 乙 买入 −4,800.00、#3 甲 转入 IXQZ 5,880.00；IXQZ → #3；卖出 → #13；无录入 / 修改 / 删除', async ({ page }) => {
    await scenarioL(page)
    await page.goto('/stocks')
    await switchToAll(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expect.poll(() => stockIds(page)).toEqual([S.n13, S.n12, S.n3])
    await expect(id(page, 'stock-list-total')).toHaveText('共 3 条')
    expect(await column(page, 'stock-row', TID.stockAccountCell)).toEqual([B_NAME, B_NAME, A_NAME])
    expect(await column(page, 'stock-row', 'stock-cell-type')).toEqual(['卖出', '买入', '转入'])
    expect(await column(page, 'stock-row', 'stock-cell-symbol')).toEqual(['QRTV', 'QRTV', 'IXQZ'])
    await expect(stockRow(page, S.n13).getByTestId('stock-cell-amount')).toHaveText('+2,450.00')
    await expect(stockRow(page, S.n13).getByTestId('stock-cell-shares')).toContainText('50')
    await expect(stockRow(page, S.n12).getByTestId('stock-cell-amount')).toHaveText(`${MINUS}4,800.00`)
    await expect(stockRow(page, S.n3).getByTestId('stock-cell-amount')).toContainText('5,880.00')

    await stockRow(page, S.n13).getByTestId('stock-expand').click()
    const detail = id(page, 'stock-sold-detail').first()
    await expect
      .poll(() =>
        detail.getByTestId('sold-lot-row').evaluateAll((rs) =>
          rs.map((r) => ['sold-lot-source', 'sold-lot-time', 'sold-lot-shares', 'sold-lot-cost'].map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '').join(' | ')),
        ),
      )
      .toEqual([`买入 #${S.n12} | 2026-11-17 10:00 | 50 股 | 2,400.00`])
    await expect(detail.getByTestId('stock-sold-cost')).toHaveText('2,400.00')
    await expect(detail.getByTestId('stock-sold-pnl')).toHaveText('+50.00')

    await expectNoListWriteControls(page)
    await expect(page.getByRole('link', { name: /录入股票/ })).toHaveCount(0)

    await id(page, 'stock-filter-symbol').fill('IXQZ')
    await id(page, 'stock-filter-submit').click()
    await expect.poll(() => stockIds(page)).toEqual([S.n3])
    await expect(id(page, 'stock-list-total')).toHaveText('共 1 条')
    await id(page, 'stock-filter-clear').click()
    await expect.poll(() => stockIds(page)).toEqual([S.n13, S.n12, S.n3])
    await page.waitForTimeout(100)
    await id(page, 'stock-filter-type-sell').check()
    await id(page, 'stock-filter-submit').click()
    await expect.poll(() => stockIds(page)).toEqual([S.n13])
    await expect(page).toHaveURL(/type=SELL/)
    await expectAllMode(page)
  })

  test('AC4 已平仓 ALL：共 3 笔 +140.00（#13 乙 股票 +50.00、#8 甲 48 P +60.00、#5 乙 55 C +30.00）；股票 1 笔 +50.00；期权 2 笔 +90.00；11 月 #13 #8 2 笔 +110.00', async ({ page }) => {
    await scenarioL(page)
    await page.goto('/closed')
    await switchToAll(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expectClosed(page, [`STOCK-${S.n13}`, `OPTION-${T.n8}`, `OPTION-${T.n5}`], 3, '+140.00')
    expect(await column(page, 'closed-row', TID.closedAccountCell)).toEqual([B_NAME, A_NAME, B_NAME])
    expect(await attr(page, 'closed-row', TID.rowAccountAttr)).toEqual(['2', '1', '2'])
    expect(await column(page, 'closed-row', 'closed-cell-contract')).toEqual(['QRTV', C_48P, C_55C])
    expect(await column(page, 'closed-row', 'closed-cell-pnl')).toEqual(['+50.00', '+60.00', '+30.00'])
    expect((await headers(id(page, 'closed-list')))).toContain('账户')

    const f = (n: string) => id(page, `closed-filter-${n}`)
    await f('record-type-stock').check()
    await f('submit').click()
    await expectClosed(page, [`STOCK-${S.n13}`], 1, '+50.00')
    await f('record-type-option').check()
    await f('submit').click()
    await expectClosed(page, [`OPTION-${T.n8}`, `OPTION-${T.n5}`], 2, '+90.00')
    await f('record-type-any').check()
    await f('start-date').fill('2026-11-01')
    await f('end-date').fill('2026-11-30')
    await f('submit').click()
    await expectClosed(page, [`STOCK-${S.n13}`, `OPTION-${T.n8}`], 2, '+110.00')
    await expectAllMode(page)
  })

  test('AC5 盈亏汇总 ALL：3 笔 +140.00，QRTV 3 笔（期权 +90.00、股票 +50.00），2026-11 2 笔 +110.00、2026-10 1 笔 +30.00；点 QRTV → 已平仓 共 3 笔 +140.00；点 2026-10 → 只有 #5 +30.00', async ({ page }) => {
    await scenarioL(page)
    await page.goto('/pnl-summary')
    await switchToAll(page)
    await expect(id(page, 'pnl-total-count')).toHaveText('3')
    await expect(id(page, 'pnl-total')).toHaveText('+140.00')
    const rows = async (kind: 'symbol' | 'month') =>
      page.getByTestId(`pnl-${kind}-row`).evaluateAll(
        (rs, p) => rs.map((r) => [`${p}-link`, `${p}-count`, `${p}-option`, `${p}-stock`, `${p}-pnl`].map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '').join(' ')),
        `pnl-${kind}`,
      )
    await expect.poll(() => rows('symbol')).toEqual(['QRTV 3 +90.00 +50.00 +140.00'])
    await expect.poll(() => rows('month')).toEqual(['2026-11 2 +60.00 +50.00 +110.00', '2026-10 1 +30.00 — +30.00'])

    await page.getByTestId('pnl-symbol-link').filter({ hasText: 'QRTV' }).click()
    await expect(page).toHaveURL(/\/closed\?/)
    await expectAllMode(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expectClosed(page, [`STOCK-${S.n13}`, `OPTION-${T.n8}`, `OPTION-${T.n5}`], 3, '+140.00')

    await page.goto('/pnl-summary')
    await page.getByTestId('pnl-month-link').filter({ hasText: '2026-10' }).click()
    const p = new URL(page.url()).searchParams
    expect([p.get('startDate'), p.get('endDate')]).toEqual(['2026-10-01', '2026-10-31'])
    await expectClosed(page, [`OPTION-${T.n5}`], 1, '+30.00')
    await expectAllMode(page)
  })

  test('AC6 深链接：首页 本月 +110.00（2 笔）→ 已平仓 2026-11-01 ~ 2026-11-23 #13 #8；点 #8 合约 → 交易列表 #8 #6；持仓页股票 IXQZ → 股票交易 #3；全程全部账户', async ({ page }) => {
    await scenarioL(page)
    await page.goto('/')
    await switchToAll(page)
    await expect(id(page, 'dashboard-month-total')).toHaveText('+110.00')
    await expect(id(page, 'dashboard-month-count')).toHaveText('2')
    await id(page, 'dashboard-month-link').click()
    await expect(page).toHaveURL(/\/closed\?startDate=2026-11-01&endDate=2026-11-23$/)
    await expectAllMode(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expectClosed(page, [`STOCK-${S.n13}`, `OPTION-${T.n8}`], 2, '+110.00')

    await closedRow(page, `OPTION-${T.n8}`).getByTestId('closed-cell-contract').click()
    await expect(page).toHaveURL(/\/trades\?/)
    const p = new URL(page.url()).searchParams
    expect([p.get('expirationDate'), p.get('strike'), p.get('optionType')]).toEqual(['2026-12-18', '48', 'PUT'])
    await expectAllMode(page)
    await expectTrades(page, [T.n8, T.n6], 2)

    await page.goto('/positions')
    await expectAllMode(page)
    const ixqz = page.locator(`[data-testid="stock-position-row"][data-symbol="IXQZ"][data-account-id="1"]`)
    await ixqz.getByTestId('stock-position-symbol').click()
    await expect(page).toHaveURL(/\/stocks\?.*symbol=IXQZ/i)
    await expectAllMode(page)
    await expect(id(page, TID.placeholder)).toHaveCount(0)
    await expect.poll(() => stockIds(page)).toEqual([S.n3])
    expect(await column(page, 'stock-row', TID.stockAccountCell)).toEqual([A_NAME])
  })

  test('AC7 资金流水 ALL：12 条，第 1 行乙 股票卖出 +2,450.00 余额 17,860.00；第 3 行甲 +84.35 50,263.05；第 4、5 行乙 20,210.00、甲 50,178.70；最后两行乙 / 甲 入金；表头 账户余额；汇总 68,123.05 / 14,500.00 / 53,623.05（需求原文 70,123.05 为加法错误）；无修改 / 删除 / 在…中修改', async ({ page }) => {
    await scenarioL(page)
    await page.goto('/cash')
    await switchToAll(page)
    await expect(id(page, TID.ledgerPlaceholder)).toHaveCount(0)
    const rows = id(page, 'cash-ledger-row')
    await expect(rows).toHaveCount(12)
    await expect(id(page, 'cash-ledger-total')).toHaveText('共 12 条')

    const cells = async () =>
      rows.evaluateAll(
        (rs, acc) => rs.map((r) => [acc, 'cash-ledger-time', 'cash-ledger-type', 'cash-ledger-amount', 'cash-ledger-balance'].map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '').join(' | ')),
        TID.ledgerAccountCell,
      )
    const c = await cells()
    expect(c[0]).toBe(`${B_NAME} | 2026-11-18 10:00 | 股票卖出 | +2,450.00 | 17,860.00`)
    expect(c[1]).toBe(`${B_NAME} | 2026-11-17 10:00 | 股票买入 | ${MINUS}4,800.00 | 15,410.00`)
    expect(c[2]).toBe(`${A_NAME} | 2026-11-16 10:30 | 期权开仓 | +84.35 | 50,263.05`)
    expect(c[3]).toBe(`${B_NAME} | 2026-11-16 10:00 | 期权开仓 | +120.00 | 20,210.00`)
    expect(c[4]).toBe(`${A_NAME} | 2026-11-16 10:00 | 期权开仓 | +118.70 | 50,178.70`)
    expect(c[10]).toBe(`${B_NAME} | 2026-10-01 09:30 | 入金 | +20,000.00 | 20,000.00`)
    expect(c[11]).toBe(`${A_NAME} | 2026-10-01 09:30 | 入金 | +50,000.00 | 50,000.00`)
    expect(await column(page, 'cash-ledger-row', TID.ledgerAccountCell)).toEqual([
      B_NAME, B_NAME, A_NAME, B_NAME, A_NAME, A_NAME, B_NAME, A_NAME, B_NAME, B_NAME, B_NAME, A_NAME,
    ])
    const h = await headers(id(page, 'cash-ledger'))
    expect(h[0]).toBe('账户')
    expect(h).toContain(BALANCE_HEADER_ALL)
    expect(h).not.toContain('余额')

    const total = id(page, TID.cashTotalRow)
    await expect(total.getByTestId('cash-summary-total')).toHaveText('68,123.05')
    await expect(total.getByTestId('cash-summary-frozen')).toHaveText('14,500.00')
    await expect(total.getByTestId('cash-summary-available')).toHaveText('53,623.05')

    await expectNoListWriteControls(page)
    await expect(page.getByText('在交易列表中修改')).toHaveCount(0)
    await expect(page.getByText('在股票交易中修改')).toHaveCount(0)
    await expect(id(page, 'cash-transfer-submit')).toHaveCount(0)
  })

  test('AC8 历史：ALL 交易列表只有 #10 有 历史；点它 → 当前账户变为乙账户，审计页按 期权交易 #<#10> 筛选，显示那次备注修改', async ({ page }) => {
    await scenarioL(page)
    await openTrades(page)
    await switchToAll(page)
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)
    await expect(id(page, 'trade-history')).toHaveCount(1)
    await expect(tradeRow(page, T.n10).getByTestId('trade-history')).toHaveText('历史')

    await tradeRow(page, T.n10).getByTestId('trade-history').click()
    await expect(id(page, 'account-current')).toHaveText(B_NAME)
    await expect(id(page, 'account-switcher')).toHaveAttribute('data-all-accounts', 'false')
    await expect(page).toHaveURL(/\/audit\?.*objectType=OPTION_TRADE/)
    expect(new URL(page.url()).searchParams.get('objectId')).toBe(String(T.n10))
    await expect(id(page, 'audit-filter-id')).toHaveValue(String(T.n10))
    await expect(id(page, 'all-accounts-placeholder')).toHaveCount(0)
    await expect(id(page, 'audit-row')).toHaveCount(1)
    await expect(id(page, 'audit-row')).toContainText(`期权交易 #${T.n10}`)
    await expect(id(page, 'audit-row')).toContainText('同价位')
  })

  test('AC9 切回甲账户：交易列表 4 条（#11 #9 #8 #6）无账户列，修改 / 删除 / 录入开仓恢复；已平仓 共 1 笔 +60.00；流水 5 条首行余额 50,263.05、表头 余额', async ({ page }) => {
    await scenarioL(page)
    await openTrades(page)
    await switchToAll(page)
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)

    await switchTo(page, 1, A_NAME)
    await expectTrades(page, [T.n11, T.n9, T.n8, T.n6], 4)
    await expect(id(page, TID.tradeAccountCell)).toHaveCount(0)
    expect(await headers(id(page, 'trade-list'))).not.toContain('账户')
    await expect(id(page, 'trade-delete')).toHaveCount(4)
    await expect(tradeRow(page, T.n9).getByTestId('trade-edit')).toBeVisible()
    await expect(id(page, 'trade-list-new')).toBeVisible()

    await page.goto('/closed')
    await expectClosed(page, [`OPTION-${T.n8}`], 1, '+60.00')
    await expect(id(page, TID.closedAccountCell)).toHaveCount(0)

    await page.goto('/cash')
    await expect(id(page, 'cash-ledger-row')).toHaveCount(5)
    await expect(id(page, 'cash-ledger-row').first().getByTestId('cash-ledger-balance')).toHaveText('50,263.05')
    const h = await headers(id(page, 'cash-ledger'))
    expect(h).toContain('余额')
    expect(h).not.toContain(BALANCE_HEADER_ALL)
    await expect(id(page, TID.ledgerAccountCell)).toHaveCount(0)
    await expect(id(page, 'cash-ledger-edit').first()).toBeVisible()
    await expect(id(page, 'cash-ledger-trades-link').first()).toBeVisible()
  })

  test('AC10 后端：ALL 交易列表 size=5 第 2 页 #6 #5 #4（8 条 2 页）；ALL 修改 #10 / 删除 #13 被拒且数据不变；审计 ALL 不支持；新增空账户后页面数字不变', async ({ page }) => {
    await scenarioL(page)
    const all = { 'X-Account-Id': 'ALL' }
    const p2 = (await (await fetch(`${BACKEND_URL}/api/option-trades?page=2&size=5`, { headers: all })).json()) as {
      items: { id: number; accountId: number; accountName: string }[]
      totalItems: number
      totalPages: number
    }
    expect(p2.items.map((i) => i.id)).toEqual([T.n6, T.n5, T.n4])
    expect(p2.items.map((i) => i.accountName)).toEqual([A_NAME, B_NAME, B_NAME])
    expect([p2.totalItems, p2.totalPages]).toEqual([8, 2])

    const put = await apiEditNote(T.n10, '120.00', '2026-11-16T10:00', '改不了', 'ALL')
    expect(put.status).toBe(400)
    expect(((await put.json()) as { code: string }).code).toBe('ALL_ACCOUNTS_READ_ONLY')
    const del = await fetch(`${BACKEND_URL}/api/stock-trades/${S.n13}`, { method: 'DELETE', headers: all })
    expect(del.status).toBe(400)
    expect(((await del.json()) as { code: string }).code).toBe('ALL_ACCOUNTS_READ_ONLY')
    const audit = await fetch(`${BACKEND_URL}/api/audit-log`, { headers: all })
    expect(audit.status).toBe(400)
    expect(((await audit.json()) as { code: string }).code).toBe('ALL_ACCOUNTS_NOT_SUPPORTED')
    const one = await fetch(`${BACKEND_URL}/api/option-trades/${T.n10}`, { headers: all })
    expect(((await one.json()) as { code: string }).code).toBe('ALL_ACCOUNTS_NOT_SUPPORTED')

    // a new empty account changes nothing on the pages
    await accountsCall('POST', '', '丙账户')
    await openTrades(page)
    await switchToAll(page)
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)
    await expect(tradeRow(page, T.n10).getByTestId('trade-cell-note')).toHaveText('同价位')
    await page.goto('/stocks')
    await expect.poll(() => stockIds(page)).toEqual([S.n13, S.n12, S.n3])
    await page.goto('/closed')
    await expectClosed(page, [`STOCK-${S.n13}`, `OPTION-${T.n8}`, `OPTION-${T.n5}`], 3, '+140.00')
    await page.goto('/cash')
    await expect(id(page, 'cash-ledger-row')).toHaveCount(12)
    await expect(id(page, TID.cashTotalRow).getByTestId('cash-summary-total')).toHaveText('68,123.05')
  })

  test('边界 地址栏参数不合法（ALL）：/trades?symbol=$QRTV → 字段错误、不显示结果；/closed?type=FOO → 类型错误', async ({ page }) => {
    await scenarioL(page)
    await openTrades(page)
    await switchToAll(page)
    await openTrades(page, '?symbol=$QRTV')
    await expectAllMode(page)
    const fieldError = (field: Locator) => page.locator('.ant-form-item').filter({ has: field }).locator('.ant-form-item-explain-error')
    await expect(fieldError(id(page, 'trade-filter-symbol'))).toHaveText('标的只能包含字母、数字和点')
    await expect(id(page, 'trade-row')).toHaveCount(0)

    await page.goto('/closed?type=FOO')
    await expect(fieldError(id(page, 'closed-filter-record-type'))).toHaveText('类型只能是 OPTION 或 STOCK')
    await expect(id(page, 'closed-row')).toHaveCount(0)
  })

  test('边界 在甲账户修改 #9 备注后切到全部账户：无需刷新即显示新备注和 历史', async ({ page }) => {
    await scenarioL(page)
    await openTrades(page)
    await switchTo(page, 1, A_NAME)
    await expectTrades(page, [T.n11, T.n9, T.n8, T.n6], 4)
    await tradeRow(page, T.n9).getByTestId('trade-edit').click()
    const modal = id(page, 'trade-edit-modal')
    await id(page, 'trade-edit-note').fill('甲改')
    await id(page, 'trade-edit-submit').click()
    await expect(modal.getByRole('dialog')).toHaveCount(0)
    await expect(tradeRow(page, T.n9).getByTestId('trade-cell-note')).toHaveText('甲改')

    await switchToAll(page)
    await expectTrades(page, [T.n11, T.n10, T.n9, T.n8, T.n7, T.n6, T.n5, T.n4], 8)
    await expect(tradeRow(page, T.n9).getByTestId('trade-cell-note')).toHaveText('甲改')
    await expect(tradeRow(page, T.n9).getByTestId('trade-history')).toBeVisible()
    await expect(id(page, 'trade-history')).toHaveCount(2)
  })

  test('边界 后端报错（ALL）：流水接口 500 时资金页流水区显示错误与重试，不显示占位', async ({ page }) => {
    await scenarioL(page)
    await page.goto('/cash')
    await switchToAll(page)
    await expect(id(page, 'cash-ledger-row')).toHaveCount(12)
    await page.route('**/api/cash/ledger**', (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 'INTERNAL_ERROR', status: 500, message: '服务器内部错误', details: [] }) }),
    )
    await page.reload()
    await expectAllMode(page)
    await expect(id(page, 'cash-ledger-error')).toBeVisible()
    await expect(id(page, TID.ledgerPlaceholder)).toHaveCount(0)
  })
})
