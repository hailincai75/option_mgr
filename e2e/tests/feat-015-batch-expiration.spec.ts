import type { Locator, Page } from '@playwright/test'
import { apiTransfer, CashPage, expect, MINUS, test } from '../support/fixtures'
import { apiTrade, apiTransferInShares } from '../support/trade'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow, PHASES, phaseInstant } from '../support/clock'
import { addDays } from '../support/ny'

/**
 * FEAT-015 到期批量作废 E2E (real clock).
 *
 * Scenario S is seeded relative to an expiration date EXP standing for the requirement's 2026-11-20 (Friday); all other
 * dates keep the same day offsets (deposit EXP−18, 转入 EXP−50, S1 EXP−17 … S5 EXP−14 expiring EXP+7). As the
 * requirement says, with the real clock EXP = T − 3 (T = today's New York date) stands for "clock 2026-11-23"; AC6 uses
 * EXP = T and picks its assertions by the current New York phase; AC7 uses EXP = T − 10 ("clock 2026-11-30", so S5's
 * EXP+7 is T − 3 and already expired). DB reset per test: S1–S5 = trades #1–#5; a batch of the four EXP contracts
 * creates #6–#9 in processing order (IXQZ 9 C, QRTV 30 C, XYZ 42 P, XYZ 45 P).
 *
 * Testids (FEAT-015, as implemented in BatchExpireModal.tsx / PositionsPage.tsx):
 *   positions-batch-expire                 button above the option table, text `批量作废（N）`
 *   batch-expire-modal                     modal root (the visible part is the dialog inside it)
 *   batch-expire-selected-count            `已选 K / N 个`
 *   batch-expire-select-all / batch-expire-select-none
 *   batch-expire-explanation               explanation text
 *   batch-expire-table > batch-expire-row  (data-contract), same order as the table; inside each row:
 *     batch-expire-row-checkbox, batch-expire-row-contract, batch-expire-row-direction,
 *     batch-expire-row-contracts (`2 张`), batch-expire-row-opening-net, batch-expire-row-frozen (`—` for 0),
 *     batch-expire-row-error (per-row backend reason, data-error-code = failures[].code)
 *   batch-expire-submit ("确认作废", disabled when K = 0), batch-expire-cancel
 *   batch-expire-error                     dialog-level error (data-error-code); batch-expire-unmatched-error
 *   batch-expire-result, batch-expire-result-count (`作废 4 个`), batch-expire-result-pnl-total,
 *     batch-expire-result-frozen-total, batch-expire-result-table > batch-expire-result-row (data-contract) with
 *     batch-expire-result-row-{contract,direction,contracts,time,pnl,frozen-change}, batch-expire-done
 * Existing testids: positions-expiry-filter-<value> (FEAT-010), position-row / position-assign / assign-* (FEAT-013).
 */

const BACKEND = BACKEND_URL
/** "Today" (FEAT-009, Q126: expirations relative to it must be trading days): fixed, backend and browser at 10:00 New York. */
const T = '2026-11-23'

const at = (date: string, hm = '10:00') => `${date}T${hm}`

interface ScenarioS {
  EXP: string
  IXQZ: string
  QRTV: string
  XYZ42: string
  XYZ45: string
  XYZ40: string
}

/** Scenario S in the current API account; EXP stands for 2026-11-20. */
async function scenarioS(EXP: string): Promise<ScenarioS> {
  await apiTransfer('DEPOSIT', '50000.00', at(addDays(EXP, -18)))
  await apiTransferInShares('IXQZ', 100, addDays(EXP, -50), '750.00')
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', contracts: 2, netAmount: '240.00', time: at(addDays(EXP, -17)) })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '42', type: 'PUT', netAmount: '80.00', time: at(addDays(EXP, -17), '11:00') })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: EXP, strike: '30', type: 'CALL', netAmount: '60.00', time: at(addDays(EXP, -16)) })
  await apiTrade({ action: 'STO', symbol: 'IXQZ', expiration: EXP, strike: '9', type: 'CALL', netAmount: '25.00', time: at(addDays(EXP, -15)) })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: addDays(EXP, 7), strike: '40', type: 'PUT', netAmount: '70.00', time: at(addDays(EXP, -14)) })
  return {
    EXP,
    IXQZ: `IXQZ ${EXP} 9 C`,
    QRTV: `QRTV ${EXP} 30 C`,
    XYZ42: `XYZ ${EXP} 42 P`,
    XYZ45: `XYZ ${EXP} 45 P`,
    XYZ40: `XYZ ${addDays(EXP, 7)} 40 P`,
  }
}

async function apiBatchExpire(items: { symbol: string; expirationDate: string; strike: string; optionType: 'CALL' | 'PUT'; contracts: number }[]): Promise<{ expirations: { id: number; contractLabel: string }[] }> {
  const r = await fetch(`${BACKEND}/api/option-trades/expirations/batch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items }),
  })
  if (r.status !== 201) throw new Error(`batch expire failed ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

const batchItemsS = (EXP: string) => [
  { symbol: 'IXQZ', expirationDate: EXP, strike: '9', optionType: 'CALL' as const, contracts: 1 },
  { symbol: 'QRTV', expirationDate: EXP, strike: '30', optionType: 'CALL' as const, contracts: 1 },
  { symbol: 'XYZ', expirationDate: EXP, strike: '42', optionType: 'PUT' as const, contracts: 1 },
  { symbol: 'XYZ', expirationDate: EXP, strike: '45', optionType: 'PUT' as const, contracts: 2 },
]

async function apiGet<T>(path: string): Promise<T> {
  return (await (await fetch(`${BACKEND}${path}`)).json()) as T
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

const FILTER_VALUE = { 全部: 'all', 已过期未处理: 'expired', 今天到期: 'today', '7 天内': '7' } as const
type FilterLabel = keyof typeof FILTER_VALUE

const EXPLANATION = '被指派或行权的持仓请取消勾选，在持仓行上单独处理；每条作废记在各自到期日 16:00，记错可在交易列表中逐条删除'

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  /** antd puts the modal testid on a zero-size root; the visible part is the dialog inside it. */
  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  get batchDialog(): Locator {
    return this.dialog('batch-expire-modal')
  }

  get batchButton(): Locator {
    return this.id('positions-batch-expire')
  }

  batchRow(i: number): Locator {
    return this.batchDialog.getByTestId('batch-expire-row').nth(i)
  }

  resultRow(contract: string): Locator {
    return this.batchDialog.locator(`[data-testid="batch-expire-result-row"][data-contract="${contract}"]`)
  }

  posRow(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  async openPositions(search = ''): Promise<void> {
    await this.page.goto(`/positions${search}`)
    await expect(this.id('positions-as-of')).toBeVisible()
    await expect(this.id('positions-expiry-filter')).toBeVisible()
  }

  async choose(label: FilterLabel): Promise<void> {
    await this.id(`positions-expiry-filter-${FILTER_VALUE[label]}`).click()
    await expect(this.id('positions-expiry-filter')).toHaveAttribute('data-value', FILTER_VALUE[label])
  }

  async positionContracts(): Promise<string[]> {
    return this.id('position-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract') ?? ''))
  }

  async expectRows(contracts: string[]): Promise<void> {
    await expect.poll(() => this.positionContracts()).toEqual(contracts)
  }

  async expectButton(n: number | null): Promise<void> {
    if (n === null) {
      await expect(this.batchButton).toHaveCount(0)
    } else {
      await expect(this.batchButton).toBeVisible()
      await expect(this.batchButton).toHaveText(`批量作废（${n}）`)
    }
  }

  async openBatch(): Promise<Locator> {
    await this.batchButton.click()
    await expect(this.batchDialog).toBeVisible()
    return this.batchDialog
  }

  /** Row contracts shown in the dialog, top to bottom. */
  async batchContracts(): Promise<string[]> {
    return this.batchDialog
      .getByTestId('batch-expire-row')
      .evaluateAll((rs) =>
        rs.map((r) => r.querySelector('[data-testid="batch-expire-row-contract"]')?.textContent?.trim() ?? ''),
      )
  }

  async confirmBatch(): Promise<void> {
    await this.batchDialog.getByTestId('batch-expire-submit').click()
    await expect(this.batchDialog.getByTestId('batch-expire-result')).toBeVisible()
  }

  async expectResult(count: number, pnl: string, frozen: string): Promise<void> {
    const d = this.batchDialog
    await expect(d.getByTestId('batch-expire-result-count')).toHaveText(`作废 ${count} 个`)
    await expect(d.getByTestId('batch-expire-result-pnl-total')).toHaveText(pnl)
    await expect(d.getByTestId('batch-expire-result-frozen-total')).toHaveText(frozen)
  }

  /** Result table rows as "contract direction contracts time pnl frozenChange", top to bottom. */
  async resultLines(): Promise<string[]> {
    return this.batchDialog.getByTestId('batch-expire-result-row').evaluateAll((rs) =>
      rs.map((r) =>
        ['contract', 'direction', 'contracts', 'time', 'pnl', 'frozen-change']
          .map((x) => r.querySelector(`[data-testid="batch-expire-result-row-${x}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async done(): Promise<void> {
    await this.batchDialog.getByTestId('batch-expire-done').click()
    await expect(this.batchDialog).toBeHidden()
  }

  async expectEmpty(text: string): Promise<void> {
    await expect(this.id('position-row')).toHaveCount(0)
    await expect(this.id('positions-expiry-empty')).toHaveText(text)
  }

  async tradeCells(): Promise<Record<string, string>[]> {
    return this.id('trade-row').evaluateAll((rows) =>
      rows.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? ''
        return {
          id: r.getAttribute('data-trade-id') ?? '',
          time: t('trade-cell-time'),
          action: t('trade-cell-action'),
          contract: t('trade-cell-contract'),
          net: t('trade-cell-net-amount'),
        }
      }),
    )
  }

  async closedPnl(): Promise<string[]> {
    return this.id('closed-row').evaluateAll((rs) =>
      rs.map((r) => {
        const t = (id: string) => r.querySelector(`[data-testid="${id}"]`)?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
        return `${t('closed-cell-contract')} ${t('closed-cell-action')} ${t('closed-cell-pnl')}`
      }),
    )
  }

  async pnlMonths(): Promise<string[]> {
    return this.id('pnl-month-row').evaluateAll((rs) =>
      rs.map((r) =>
        ['pnl-month-link', 'pnl-month-count', 'pnl-month-pnl']
          .map((x) => r.querySelector(`[data-testid="${x}"]`)?.textContent?.trim() ?? '')
          .join(' '),
      ),
    )
  }

  async expectStock(symbol: string, shares: string, covered: string, available: string): Promise<void> {
    const row = this.page.locator(`[data-testid="stock-position-row"][data-symbol="${symbol}"]`)
    await expect(row.getByTestId('stock-position-shares')).toHaveText(shares)
    await expect(row.getByTestId('stock-position-covered-shares')).toHaveText(covered)
    await expect(row.getByTestId('stock-position-available-shares')).toHaveText(available)
  }

  async switchAccount(id: number, name: string): Promise<void> {
    await this.id('account-switcher').click()
    await this.id(`account-option-${id}`).click()
    await expect(this.id('account-current')).toHaveText(name)
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

/** Expired-filter rows of scenario S (EXP = T − 3): IXQZ 9 C, QRTV 30 C, XYZ 42 P, XYZ 45 P. */
const rowsS = (s: ScenarioS) => [s.IXQZ, s.QRTV, s.XYZ42, s.XYZ45]

test.describe('FEAT-015 到期批量作废', () => {
  test.beforeEach(async ({ page }) => {
    await fixNow(page, phaseInstant(T, 'closable'))
  })

  test('AC1 按钮与确认框：已过期未处理 4 行、批量作废（4）；全部 / 7 天内 / 空的今天到期没有按钮；确认框全部勾选、已选 4 / 4 个、行内容与说明', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows(rowsS(s))
    await ui.expectButton(4)

    await ui.choose('全部')
    await ui.expectRows([...rowsS(s), s.XYZ40])
    await ui.expectButton(null)
    await ui.choose('7 天内')
    await ui.expectRows([s.XYZ40])
    await ui.expectButton(null)
    await ui.choose('今天到期')
    await ui.expectEmpty('没有今天到期的持仓')
    await ui.expectButton(null) // N = 0

    await ui.choose('已过期未处理')
    await ui.expectButton(4)
    const d = await ui.openBatch()
    await expect.poll(() => ui.batchContracts()).toEqual(rowsS(s))
    await expect(d.getByTestId('batch-expire-selected-count')).toHaveText('已选 4 / 4 个')
    for (let i = 0; i < 4; i++) await expect(ui.batchRow(i).getByTestId('batch-expire-row-checkbox')).toBeChecked()
    await expect(d.getByTestId('batch-expire-explanation')).toHaveText(EXPLANATION)

    const r45 = ui.batchRow(3)
    await expect(r45.getByTestId('batch-expire-row-contract')).toHaveText(s.XYZ45)
    await expect(r45.getByTestId('batch-expire-row-direction')).toHaveText('空头')
    await expect(r45.getByTestId('batch-expire-row-contracts')).toHaveText('2 张')
    await expect(r45.getByTestId('batch-expire-row-opening-net')).toHaveText('+240.00')
    await expect(r45.getByTestId('batch-expire-row-frozen')).toHaveText('9,000.00')

    const rq = ui.batchRow(1)
    await expect(rq.getByTestId('batch-expire-row-contract')).toHaveText(s.QRTV)
    await expect(rq.getByTestId('batch-expire-row-direction')).toHaveText('多头')
    await expect(rq.getByTestId('batch-expire-row-contracts')).toHaveText('1 张')
    await expect(rq.getByTestId('batch-expire-row-opening-net')).toHaveText(`${MINUS}60.00`)
    await expect(rq.getByTestId('batch-expire-row-frozen')).toHaveText('—')

    const ri = ui.batchRow(0)
    await expect(ri.getByTestId('batch-expire-row-contract')).toHaveText(s.IXQZ)
    await expect(ri.getByTestId('batch-expire-row-frozen')).toHaveText('—') // covered call: no frozen cash

    // cancelling saves nothing
    await d.getByTestId('batch-expire-cancel').click()
    await expect(ui.batchDialog).toBeHidden()
    await ui.expectRows(rowsS(s))
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=EXP')).totalItems).toBe(0)
  })

  test('AC2 全部作废：作废 4 个、+285.00、−13,200.00；没有已过期未处理的持仓；不刷新页面现金 / 交易列表 / 已平仓 / 股票覆盖都更新，审计无记录', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.markNoReload()
    await ui.expectRows(rowsS(s))
    await ui.openBatch()
    await ui.confirmBatch()
    await ui.expectResult(4, '+285.00', `${MINUS}13,200.00`)
    // per-row result (matched by contract)
    expect(await ui.resultLines()).toEqual([
      `${s.IXQZ} 空头 1 张 ${s.EXP} 16:00 +25.00 —`,
      `${s.QRTV} 多头 1 张 ${s.EXP} 16:00 ${MINUS}60.00 —`,
      `${s.XYZ42} 空头 1 张 ${s.EXP} 16:00 +80.00 ${MINUS}4,200.00`,
      `${s.XYZ45} 空头 2 张 ${s.EXP} 16:00 +240.00 ${MINUS}9,000.00`,
    ])
    await expect(ui.resultRow(s.XYZ45).getByTestId('batch-expire-result-row-pnl')).toHaveText('+240.00')
    await ui.done()
    await ui.expectEmpty('没有已过期未处理的持仓')
    await ui.expectButton(null)

    // processing order -> ids #6–#9
    const t6 = await apiGet<{ contractLabel: string; tradedAt: string }>('/api/option-trades/6')
    const t9 = await apiGet<{ contractLabel: string }>('/api/option-trades/9')
    expect(t6.contractLabel).toBe(s.IXQZ)
    expect(t9.contractLabel).toBe(s.XYZ45)

    await ui.id('nav-cash').click()
    const cash = new CashPage(page)
    await cash.expectSummary('50,355.00', '4,000.00', '46,355.00')

    await ui.id('nav-trades').click()
    await expect(ui.id('trade-list-total')).toHaveText('共 9 条')
    await expect
      .poll(async () => (await ui.tradeCells()).filter((c) => c.action === '到期作废').map((c) => `${c.time} ${c.net} ${c.contract}`).sort())
      .toEqual([`${s.EXP} 16:00 0.00 ${s.IXQZ}`, `${s.EXP} 16:00 0.00 ${s.QRTV}`, `${s.EXP} 16:00 0.00 ${s.XYZ42}`, `${s.EXP} 16:00 0.00 ${s.XYZ45}`].sort())

    await ui.id('nav-closed').click()
    await expect(ui.id('closed-count')).toHaveText('共 4 笔平仓')
    await expect(ui.id('closed-pnl-total')).toHaveText('+285.00')
    await expect
      .poll(async () => (await ui.closedPnl()).sort())
      .toEqual([`${s.IXQZ} 到期作废 +25.00`, `${s.QRTV} 到期作废 ${MINUS}60.00`, `${s.XYZ42} 到期作废 +80.00`, `${s.XYZ45} 到期作废 +240.00`].sort())

    await ui.id('nav-positions').click()
    await ui.choose('全部')
    await ui.expectRows([s.XYZ40])
    await ui.expectStock('IXQZ', '100', '0', '100')

    await ui.id('nav-audit').click()
    await expect(ui.id('audit-empty')).toBeVisible()
    await expect(ui.id('audit-row')).toHaveCount(0)
    await ui.expectNoReload()
  })

  test('AC3 全不选 → 已选 0 / 4 个、确认作废不可点；取消勾选 XYZ 45 P 后作废 3 个 +45.00 −4,200.00；剩 XYZ 45 P、批量作废（1）；行上被指派 2 张后没有已过期未处理的持仓', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows(rowsS(s))
    const d = await ui.openBatch()

    await d.getByTestId('batch-expire-select-none').click()
    await expect(d.getByTestId('batch-expire-selected-count')).toHaveText('已选 0 / 4 个')
    for (let i = 0; i < 4; i++) await expect(ui.batchRow(i).getByTestId('batch-expire-row-checkbox')).not.toBeChecked()
    await expect(d.getByTestId('batch-expire-submit')).toBeDisabled()
    await d.getByTestId('batch-expire-select-all').click()
    await expect(d.getByTestId('batch-expire-selected-count')).toHaveText('已选 4 / 4 个')
    await expect(d.getByTestId('batch-expire-submit')).toBeEnabled()

    await ui.batchRow(3).getByTestId('batch-expire-row-checkbox').uncheck()
    await expect(d.getByTestId('batch-expire-selected-count')).toHaveText('已选 3 / 4 个')
    await ui.confirmBatch()
    await ui.expectResult(3, '+45.00', `${MINUS}4,200.00`)
    await ui.done()
    await ui.expectRows([s.XYZ45])
    await ui.expectButton(1)

    // assignment on the row, unchanged single-row flow (FEAT-013)
    await ui.posRow(s.XYZ45).getByTestId('position-assign').click()
    const ad = ui.dialog('assign-modal')
    await expect(ad).toBeVisible()
    await expect(ad.getByTestId('assign-date')).toHaveValue(s.EXP)
    await ad.getByTestId('assign-contracts').click()
    await page.locator('.ant-select-dropdown:visible').getByTestId('assign-contracts-option-2').click()
    await ad.getByTestId('assign-submit').click()
    await expect(ad.getByTestId('assign-result')).toBeVisible()
    await expect(ad.getByTestId('assign-result-contracts')).toHaveText('2 张')
    await ad.getByTestId('assign-done').click()
    await expect(ui.dialog('assign-modal')).toBeHidden()
    await ui.expectEmpty('没有已过期未处理的持仓')
    await ui.expectButton(null)

    await ui.id('nav-cash').click()
    await new CashPage(page).expectSummary('41,355.00', '4,000.00', '37,355.00')
  })

  test('AC4 对话框打开期间补录 XYZ 42 P：整批被拒，XYZ 42 P 行旁显示"持仓已变化（当前 2 张）"，确认框不关闭、什么都不保存；重新打开 2 张 +130.00，作废 4 个 +335.00 −17,400.00', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows(rowsS(s))
    const d = await ui.openBatch()
    await expect(ui.batchRow(2).getByTestId('batch-expire-row-contracts')).toHaveText('1 张')

    // "another page" backfills STO 1 XYZ 42 P meanwhile
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: s.EXP, strike: '42', type: 'PUT', netAmount: '50.00', time: at(addDays(s.EXP, -1)) })
    await d.getByTestId('batch-expire-submit').click()
    const err = ui.batchRow(2).getByTestId('batch-expire-row-error')
    await expect(err).toHaveText('持仓已变化（当前 2 张），请刷新后重试')
    await expect(err).toHaveAttribute('data-error-code', 'POSITION_CHANGED')
    for (const i of [0, 1, 3]) await expect(ui.batchRow(i).getByTestId('batch-expire-row-error')).toHaveCount(0)
    await expect(d).toBeVisible()
    await expect(d.getByTestId('batch-expire-result')).toHaveCount(0)
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=EXP')).totalItems).toBe(0)
    expect((await apiGet<{ frozenCash: string }>('/api/cash/summary')).frozenCash).toBe('21400.00')

    await d.getByTestId('batch-expire-cancel').click()
    await expect(ui.batchDialog).toBeHidden()
    await expect(ui.posRow(s.XYZ42).getByTestId('position-cell-contracts')).toHaveText('2')
    await ui.openBatch()
    await expect(ui.batchRow(2).getByTestId('batch-expire-row-contract')).toHaveText(s.XYZ42)
    await expect(ui.batchRow(2).getByTestId('batch-expire-row-contracts')).toHaveText('2 张')
    await expect(ui.batchRow(2).getByTestId('batch-expire-row-opening-net')).toHaveText('+130.00')
    await expect(ui.batchRow(2).getByTestId('batch-expire-row-error')).toHaveCount(0)
    await ui.confirmBatch()
    await ui.expectResult(4, '+335.00', `${MINUS}17,400.00`)
    await ui.done()
    await ui.expectEmpty('没有已过期未处理的持仓')
  })

  test('AC4 失败后取消勾选出错的行再重试：其余 3 个作废成功，XYZ 42 P 留在表中', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows(rowsS(s))
    const d = await ui.openBatch()
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: s.EXP, strike: '42', type: 'PUT', netAmount: '50.00', time: at(addDays(s.EXP, -1)) })
    await d.getByTestId('batch-expire-submit').click()
    await expect(ui.batchRow(2).getByTestId('batch-expire-row-error')).toHaveText('持仓已变化（当前 2 张），请刷新后重试')
    await ui.batchRow(2).getByTestId('batch-expire-row-checkbox').uncheck()
    await expect(d.getByTestId('batch-expire-selected-count')).toHaveText('已选 3 / 4 个')
    await ui.confirmBatch()
    await ui.expectResult(3, '+205.00', `${MINUS}9,000.00`)
    await ui.done()
    await ui.expectRows([s.XYZ42])
    await ui.expectButton(1)
  })

  for (const p of PHASES) {
  test(`AC6（到期日当天）：今天到期 S1–S4；16:00–17:30 到期待确认、没有批量按钮；17:30 起批量作废（4），结果同 AC2，没有今天到期的持仓 [固定时钟 2026-11-23 ${p}]`, async ({ page }) => {
    await fixNow(page, phaseInstant(T, p))
    const s = await scenarioS(T)
    const ui = new Ui(page)
    const phase = p
    await ui.openPositions('?expiry=today')
    await ui.expectRows(rowsS(s))
    if (phase === 'closable') {
      await ui.expectButton(null)
      await expect(ui.posRow(s.XYZ45).getByTestId('position-close')).toBeVisible()
    } else if (phase === 'pending') {
      for (const c of rowsS(s)) await expect(ui.posRow(c).getByTestId('position-expiry-pending')).toHaveText('到期待确认')
      await ui.expectButton(null)
      // a direct request is rejected for every item
      const r = await fetch(`${BACKEND}/api/option-trades/expirations/batch`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: batchItemsS(T) }),
      })
      expect(r.status).toBe(422)
      const body = (await r.json()) as { code: string; failures: { code: string; message: string }[] }
      expect(body.code).toBe('BATCH_EXPIRATION_REJECTED')
      expect(body.failures.map((f) => `${f.code} ${f.message}`)).toEqual(
        Array(4).fill(`EXPIRY_TOO_EARLY 到期日（${T}）美东 17:30 起才能作废`),
      )
    } else {
      await ui.expectButton(4)
      await ui.openBatch()
      await ui.confirmBatch()
      await ui.expectResult(4, '+285.00', `${MINUS}13,200.00`)
      await ui.done()
      await ui.expectEmpty('没有今天到期的持仓')
      await ui.expectButton(null)
    }
  })
  }

  test('AC7 不同到期日一起作废（场景 S 前移 = 时钟 2026-11-30）：批量作废（5）→ 作废 5 个 +355.00 −17,200.00；4 条记在 EXP 16:00、XYZ 40 P 记在 EXP+7 16:00；冻结 0.00', async ({ page }) => {
    const s = await scenarioS(addDays(T, -10))
    const exp2 = addDays(s.EXP, 7)
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows([...rowsS(s), s.XYZ40])
    await ui.expectButton(5)
    await ui.openBatch()
    await expect.poll(() => ui.batchContracts()).toEqual([...rowsS(s), s.XYZ40])
    await expect(ui.batchDialog.getByTestId('batch-expire-selected-count')).toHaveText('已选 5 / 5 个')
    await ui.confirmBatch()
    await ui.expectResult(5, '+355.00', `${MINUS}17,200.00`)
    await expect(ui.resultRow(s.XYZ40).getByTestId('batch-expire-result-row-time')).toHaveText(`${exp2} 16:00`)
    await expect(ui.resultRow(s.XYZ45).getByTestId('batch-expire-result-row-time')).toHaveText(`${s.EXP} 16:00`)
    await ui.done()
    // no option position left at all: the page's original empty state (FEAT-010 rule 6), not the filter's empty text
    await expect(ui.id('position-row')).toHaveCount(0)
    await expect(ui.id('positions-empty')).toBeVisible()
    await ui.expectButton(null)

    await ui.id('nav-cash').click()
    await new CashPage(page).expectSummary('50,355.00', '0.00', '50,355.00')

    await ui.id('nav-trades').click()
    await expect
      .poll(async () => (await ui.tradeCells()).filter((c) => c.action === '到期作废').map((c) => `${c.time} ${c.contract}`).sort())
      .toEqual([`${s.EXP} 16:00 ${s.IXQZ}`, `${s.EXP} 16:00 ${s.QRTV}`, `${s.EXP} 16:00 ${s.XYZ42}`, `${s.EXP} 16:00 ${s.XYZ45}`, `${exp2} 16:00 ${s.XYZ40}`].sort())

    await ui.id('nav-pnl-summary').click()
    await expect(ui.id('pnl-total')).toHaveText('+355.00')
    const m1 = s.EXP.slice(0, 7)
    const m2 = exp2.slice(0, 7)
    const expected = m1 === m2 ? [`${m1} 5 +355.00`] : [`${m2} 1 +70.00`, `${m1} 4 +285.00`]
    await expect.poll(() => ui.pnlMonths()).toEqual(expected)
  })

  test('AC8 逐条删除：交易列表删除 XYZ 42 P 的到期作废 → 冻结恢复 4,200.00、其余 3 条不变；已过期未处理中 XYZ 42 P 重新出现、批量作废（1）；审计只有这条删除、没有"批量作废"', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const res = await apiBatchExpire(batchItemsS(s.EXP))
    const id42 = res.expirations.find((e) => e.contractLabel === s.XYZ42)?.id
    expect(id42).toBe(8)
    const ui = new Ui(page)
    await page.goto('/trades')
    const row = page.locator(`[data-testid="trade-row"][data-trade-id="${id42}"]`)
    await expect(row.getByTestId('trade-cell-action')).toHaveText('到期作废')
    await row.getByTestId('trade-delete').click()
    const dd = ui.dialog('trade-delete-modal')
    await expect(dd).toBeVisible()
    await expect(dd.getByTestId('trade-delete-summary')).toHaveText(`到期作废 1 张 ${s.XYZ42}，${s.EXP} 16:00，0.00`)
    await dd.getByTestId('trade-delete-confirm').click()
    await expect(ui.dialog('trade-delete-modal')).toBeHidden()
    await expect(row).toHaveCount(0)
    await expect.poll(async () => (await ui.tradeCells()).filter((c) => c.action === '到期作废').length).toBe(3)

    await ui.id('nav-cash').click()
    await new CashPage(page).expectSummary('50,355.00', '8,200.00', '42,155.00')

    await ui.openPositions('?expiry=expired')
    await ui.expectRows([s.XYZ42])
    await ui.expectButton(1)

    await ui.id('nav-audit').click()
    await expect(ui.id('audit-row')).toHaveCount(1)
    await expect(ui.id('audit-row').first().getByTestId('audit-cell-operation')).toHaveText('删除')
    await expect(ui.id('audit-row').first().getByTestId('audit-cell-summary')).toContainText(s.XYZ42)
    await expect(page.locator('body')).not.toContainText('批量作废')
  })

  test('AC9 按账户隔离：默认账户批量作废后小账户的 XYZ 45 P 仍在、冻结 4,500.00；切到小账户"已过期未处理"显示批量作废（1）', async ({ page }) => {
    const EXP = addDays(T, -3)
    const small = await apiCreateAccount('小账户')
    setApiAccount(small)
    await apiTransfer('DEPOSIT', '5000.00', at(addDays(EXP, -18)))
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: EXP, strike: '45', type: 'PUT', netAmount: '100.00', time: at(addDays(EXP, -16)) })
    setApiAccount(1)
    const s = await scenarioS(EXP)
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows(rowsS(s))
    await ui.openBatch()
    await ui.confirmBatch()
    await ui.expectResult(4, '+285.00', `${MINUS}13,200.00`)
    await ui.done()
    await ui.expectEmpty('没有已过期未处理的持仓')

    await ui.switchAccount(small, '小账户')
    await ui.openPositions('?expiry=expired')
    await ui.expectRows([s.XYZ45])
    await ui.expectButton(1)
    await expect(ui.posRow(s.XYZ45).getByTestId('position-cell-frozen')).toHaveText('4,500.00')
    await ui.id('nav-cash').click()
    await new CashPage(page).expectSummary('5,100.00', '4,500.00', '600.00')
  })

  test('后端错误：批量接口返回 500 / 网络失败时确认框显示错误、不关闭、什么都不保存', async ({ page }) => {
    const s = await scenarioS(addDays(T, -3))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.expectRows(rowsS(s))
    await page.route('**/api/option-trades/expirations/batch', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误', status: 500, path: '/api/option-trades/expirations/batch', timestamp: '2026-11-13T15:00:00Z', details: [] }),
      }),
    )
    const d = await ui.openBatch()
    await d.getByTestId('batch-expire-submit').click()
    await expect(d.getByTestId('batch-expire-error')).toContainText('服务器内部错误')
    await expect(d).toBeVisible()
    await expect(d.getByTestId('batch-expire-result')).toHaveCount(0)
    await page.unroute('**/api/option-trades/expirations/batch')
    await page.route('**/api/option-trades/expirations/batch', (route) => route.abort('connectionrefused'))
    await d.getByTestId('batch-expire-submit').click()
    await expect(d.getByTestId('batch-expire-error')).toBeVisible()
    await expect(d.getByTestId('batch-expire-error')).not.toHaveText('')
    await expect(d).toBeVisible()
    await page.unroute('**/api/option-trades/expirations/batch')
    expect((await apiGet<{ totalItems: number }>('/api/option-trades?action=EXP')).totalItems).toBe(0)
    await d.getByTestId('batch-expire-cancel').click()
    await ui.expectRows(rowsS(s))
  })
})
