import { chooseFifoLots } from '../support/closeLots'
import type { Locator, Page } from '@playwright/test'
import { apiTransfer, expect, MINUS, test } from '../support/fixtures'
import { apiTrade } from '../support/trade'
import { BACKEND_URL, setApiAccount } from '../support/backend'
import { fixNow } from '../support/clock'
import { addDays } from '../support/ny'

/**
 * FEAT-010 持仓页到期过滤 E2E (frontend only).
 *
 * FEAT-009 (Q126: expirations must be trading days): fixed clock instead of the real one. Backend and browser are at
 * the requirement's 2026-11-18 10:00 New York (beforeEach); scenario R uses its real dates (base date B = T =
 * 2026-11-18, all expirations are trading days). "Next day" (AC4) and "Saturday" (AC8) move the clock to 2026-11-19 /
 * 2026-11-21; the 16:00–17:30 到期待确认 / from-17:30 作废 phases (AC4) are set explicitly.
 *
 * Testids (FEAT-010): positions-expiry-filter (container; data-value = all / expired / today / 7 / 14 / 30) with
 * options positions-expiry-filter-<value>; positions-expiry-empty, positions-filter-count, positions-stock-hidden
 * (notice, inside positions-stock-hidden-card), dashboard-expiring-link, dashboard-expired-link.
 */

const BACKEND = BACKEND_URL
const T = '2026-11-18'
/** T 10:00 New York. */
const NOW_T = '2026-11-18T15:00:00Z'

type FilterLabel = '全部' | '已过期未处理' | '今天到期' | '7 天内' | '14 天内' | '30 天内'

const FILTER_VALUE: Record<FilterLabel, string> = {
  全部: 'all',
  已过期未处理: 'expired',
  今天到期: 'today',
  '7 天内': '7',
  '14 天内': '14',
  '30 天内': '30',
}

/** Contract labels of scenario R for base date `b`. */
function contractsR(b: string) {
  return {
    R1: `XYZ ${addDays(b, -5)} 40 P`,
    R2: `QRTV ${addDays(b, -5)} 30 C`,
    R3: `XYZ ${b} 42 P`,
    R4: `QRTV ${addDays(b, 2)} 25 P`,
    R5: `XYZ ${addDays(b, 7)} 38 P`,
    R6: `XYZ ${addDays(b, 9)} 35 P`,
    R7: `QRTV ${addDays(b, 30)} 30 C`,
    R8: `QRTV ${addDays(b, 58)} 30 C`,
  }
}

const at = (date: string, hm = '10:00') => `${date}T${hm}`

/** Scenario R in the current API account; `b` stands for 2026-11-18. */
async function scenarioR(b: string): Promise<ReturnType<typeof contractsR>> {
  await apiTransfer('DEPOSIT', '30000.00', at(addDays(b, -16)))
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: addDays(b, -5), strike: '40', type: 'PUT', netAmount: '95.00', time: at(addDays(b, -15)) })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: addDays(b, -5), strike: '30', type: 'CALL', contracts: 2, netAmount: '120.00', time: at(addDays(b, -15), '11:00') })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: b, strike: '42', type: 'PUT', contracts: 2, netAmount: '180.00', time: at(addDays(b, -14)) })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: addDays(b, 2), strike: '25', type: 'PUT', netAmount: '45.00', time: at(addDays(b, -13)) })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: addDays(b, 7), strike: '38', type: 'PUT', netAmount: '70.00', time: at(addDays(b, -12)) })
  await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: addDays(b, 9), strike: '35', type: 'PUT', netAmount: '60.00', time: at(addDays(b, -9)) })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: addDays(b, 30), strike: '30', type: 'CALL', netAmount: '210.00', time: at(addDays(b, -8)) })
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: addDays(b, 58), strike: '30', type: 'CALL', netAmount: '150.00', time: at(addDays(b, -8), '11:00') })
  return contractsR(b)
}

/** AC6 account: deposit 1,000.00 and one BTO QRTV <B+58> 30 C for 150.00 (B = T). */
async function scenarioSingle(): Promise<string> {
  await apiTransfer('DEPOSIT', '1000.00', at(addDays(T, -16)))
  await apiTrade({ action: 'BTO', symbol: 'QRTV', expiration: addDays(T, 58), strike: '30', type: 'CALL', netAmount: '150.00', time: at(addDays(T, -8)) })
  return `QRTV ${addDays(T, 58)} 30 C`
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

const STOCK_HIDDEN_TEXT = "股票持仓不参与到期过滤，选择'全部'查看"

class Ui {
  constructor(readonly page: Page) {}

  id(s: string): Locator {
    return this.page.getByTestId(s)
  }

  dialog(testId: string): Locator {
    return this.page.getByTestId(testId).getByRole('dialog')
  }

  posRow(contract: string): Locator {
    return this.page.locator(`[data-testid="position-row"][data-contract="${contract}"]`)
  }

  get filter(): Locator {
    return this.id('positions-expiry-filter')
  }

  /** Opens the positions page (optionally with a raw query string such as `?expiry=7`) and waits for the summary. */
  async openPositions(search = ''): Promise<void> {
    await this.page.goto(`/positions${search}`)
    await expect(this.id('positions-as-of')).toBeVisible()
    await expect(this.filter).toBeVisible()
  }

  async choose(label: FilterLabel): Promise<void> {
    await this.id(`positions-expiry-filter-${FILTER_VALUE[label]}`).click()
  }

  /** The filter container's data-value is the current filter (all / expired / today / 7 / 14 / 30). */
  async expectSelected(label: FilterLabel): Promise<void> {
    await expect(this.filter).toHaveAttribute('data-value', FILTER_VALUE[label])
    await expect(this.id(`positions-expiry-filter-${FILTER_VALUE[label]}`)).toHaveText(label)
  }

  /** `expiry` query value, or null when absent. */
  expiryParam(): string | null {
    return new URL(this.page.url()).searchParams.get('expiry')
  }

  async expectExpiryParam(v: string | null): Promise<void> {
    await expect.poll(() => this.expiryParam()).toBe(v)
  }

  async positionContracts(): Promise<string[]> {
    return this.id('position-row').evaluateAll((rs) => rs.map((r) => r.getAttribute('data-contract') ?? ''))
  }

  async expectRows(contracts: string[]): Promise<void> {
    await expect.poll(() => this.positionContracts()).toEqual(contracts)
  }

  /** Header while filtered: unchanged 共 N / 冻结合计 plus 显示 M / N 个持仓; stock section replaced by the notice. */
  async expectFiltered(shown: number, total: number, frozen: string): Promise<void> {
    await expect(this.id('positions-filter-count')).toHaveText(`显示 ${shown} / ${total} 个持仓`)
    await expect(this.id('positions-count')).toHaveText(`共 ${total} 个持仓`)
    await expect(this.id('positions-frozen-total')).toHaveText(`冻结现金合计 ${frozen}`)
    await expect(this.id('stock-positions')).toHaveCount(0)
    await expect(this.id('positions-stock-hidden')).toHaveText(STOCK_HIDDEN_TEXT)
  }

  /** Header for 全部: no 显示 M / N, stock section shown, no notice. */
  async expectUnfiltered(total: number, frozen: string): Promise<void> {
    await expect(this.id('positions-count')).toHaveText(`共 ${total} 个持仓`)
    await expect(this.id('positions-frozen-total')).toHaveText(`冻结现金合计 ${frozen}`)
    await expect(this.id('positions-filter-count')).toHaveCount(0)
    // no "显示 M / N 个持仓" anywhere in the card (FEAT-018 adds an unrelated "显示 Greeks" switch label)
    await expect(this.id('positions-page')).not.toContainText(/显示 \d+ \/ \d+/)
    await expect(this.id('stock-positions')).toBeVisible()
    await expect(this.id('positions-stock-hidden-card')).toHaveCount(0)
    await expect(this.id('positions-expiry-empty')).toHaveCount(0)
  }

  async expectEmpty(text: string): Promise<void> {
    await expect(this.id('position-row')).toHaveCount(0)
    await expect(this.id('positions-expiry-empty')).toHaveText(text)
    await expect(this.id('positions-empty')).toHaveCount(0) // not the "暂无持仓" state
  }

  /** Action button texts of a position row, e.g. ["作废", "被指派"] (the 批次 expand toggle is also a button). */
  async rowButtons(contract: string): Promise<string[]> {
    return this.posRow(contract)
      .getByRole('button')
      .evaluateAll((bs) => bs.map((b) => (b.textContent ?? '').replace(/\s+/g, '')).filter((t) => ['平仓', '作废', '被指派'].includes(t)))
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

test.describe('FEAT-010 持仓页到期过滤', () => {
  test.beforeEach(async ({ page }) => {
    await fixNow(page, NOW_T)
  })

  test('AC1 默认"全部"：8 行按 Q35 排序，共 8 个持仓、冻结 19,700.00，没有"显示 M / N"，股票部分照常显示', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectSelected('全部')
    expect(ui.expiryParam()).toBeNull()
    await ui.expectRows([R.R2, R.R1, R.R3, R.R4, R.R5, R.R6, R.R7, R.R8])
    await ui.expectUnfiltered(8, '19,700.00')
    await expect(ui.posRow(R.R1).getByTestId('position-cell-dte')).toHaveText('已过期 5 天')
    await expect(ui.posRow(R.R3).getByTestId('position-cell-dte-today')).toHaveText('今天到期')
    await expect(ui.posRow(R.R4).getByTestId('position-cell-dte')).toHaveText('2')
    await expect(ui.posRow(R.R7).getByTestId('position-cell-dte')).toHaveText('30')
    await expect(ui.posRow(R.R8).getByTestId('position-cell-dte')).toHaveText('58')
  })

  test('AC2+AC9 已过期未处理 / 今天到期：地址栏、只显示符合的行、显示 M / 8、冻结合计不变、刷新保持；隐藏股票部分并显示说明；回到"全部"', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectRows([R.R2, R.R1, R.R3, R.R4, R.R5, R.R6, R.R7, R.R8])

    await ui.choose('已过期未处理')
    await ui.expectExpiryParam('expired')
    await ui.expectSelected('已过期未处理')
    await ui.expectRows([R.R2, R.R1])
    for (const c of [R.R2, R.R1]) {
      const dte = ui.posRow(c).getByTestId('position-cell-dte')
      await expect(dte).toHaveText('已过期 5 天')
      expect(await colorOf(dte)).toBe('red')
    }
    await ui.expectFiltered(2, 8, '19,700.00')

    await page.reload()
    await expect(ui.id('positions-as-of')).toBeVisible()
    await ui.expectExpiryParam('expired')
    await ui.expectSelected('已过期未处理')
    await ui.expectRows([R.R2, R.R1])
    await ui.expectFiltered(2, 8, '19,700.00')

    await ui.choose('今天到期')
    await ui.expectExpiryParam('today')
    await ui.expectSelected('今天到期')
    await ui.expectRows([R.R3])
    await expect(ui.posRow(R.R3).getByTestId('position-cell-dte-today')).toHaveText('今天到期')
    await ui.expectFiltered(1, 8, '19,700.00')

    await ui.choose('全部')
    await ui.expectExpiryParam(null)
    await ui.expectSelected('全部')
    await ui.expectRows([R.R2, R.R1, R.R3, R.R4, R.R5, R.R6, R.R7, R.R8])
    await ui.expectUnfiltered(8, '19,700.00')
  })

  test('AC3 7 / 14 / 30 天内含今天和第 N 天，R8 不出现；7 天内个数 = 首页"7 天内到期"；非法参数 10 / abc 按"全部"', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await page.goto('/')
    await expect(ui.id('dashboard-expiring-count')).toHaveText('3')

    await ui.openPositions('?expiry=7')
    await ui.expectSelected('7 天内')
    await ui.expectRows([R.R3, R.R4, R.R5])
    await expect(ui.posRow(R.R5).getByTestId('position-cell-dte')).toHaveText('7')
    await ui.expectFiltered(3, 8, '19,700.00')

    await ui.openPositions('?expiry=14')
    await ui.expectSelected('14 天内')
    await ui.expectRows([R.R3, R.R4, R.R5, R.R6])
    await ui.expectFiltered(4, 8, '19,700.00')

    await ui.openPositions('?expiry=30')
    await ui.expectSelected('30 天内')
    await ui.expectRows([R.R3, R.R4, R.R5, R.R6, R.R7])
    await expect(ui.posRow(R.R7).getByTestId('position-cell-dte')).toHaveText('30')
    await ui.expectFiltered(5, 8, '19,700.00')
    await expect(ui.posRow(R.R8)).toHaveCount(0)

    // switching between the N-day options through the UI updates the address bar too
    await ui.choose('14 天内')
    await ui.expectExpiryParam('14')
    await ui.expectRows([R.R3, R.R4, R.R5, R.R6])
    await ui.choose('7 天内')
    await ui.expectExpiryParam('7')
    await ui.expectRows([R.R3, R.R4, R.R5])

    for (const bad of ['10', 'abc', '']) {
      await ui.openPositions(`?expiry=${bad}`)
      await ui.expectSelected('全部')
      await ui.expectRows([R.R2, R.R1, R.R3, R.R4, R.R5, R.R6, R.R7, R.R8])
      await ui.expectUnfiltered(8, '19,700.00')
    }
  })

  test('AC4 到期日当天：R3 在"今天到期"，按钮按美东时刻（10:00 平仓+被指派 / 16:30 只有被指派且到期待确认 / 17:45 作废+被指派）；"已过期未处理"只有 R2、R1', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await ui.openPositions('?expiry=today')
    await ui.expectRows([R.R3])
    await expect(ui.posRow(R.R3).getByTestId('position-cell-dte-today')).toHaveText('今天到期')
    await expect.poll(() => ui.rowButtons(R.R3)).toEqual(['平仓', '被指派'])
    await expect(ui.posRow(R.R3).getByTestId('position-expiry-pending')).toHaveCount(0)

    await fixNow(page, '2026-11-18T21:30:00Z') // 16:30 New York
    await ui.openPositions('?expiry=today')
    await ui.expectRows([R.R3])
    await expect(ui.posRow(R.R3).getByTestId('position-expiry-pending')).toHaveText('到期待确认')
    await expect.poll(() => ui.rowButtons(R.R3)).toEqual(['被指派'])

    await fixNow(page, '2026-11-18T22:45:00Z') // 17:45 New York
    await ui.openPositions('?expiry=today')
    await ui.expectRows([R.R3])
    await expect.poll(() => ui.rowButtons(R.R3)).toEqual(['作废', '被指派'])
    await expect(ui.posRow(R.R3).getByTestId('position-expiry-pending')).toHaveCount(0)

    await ui.choose('已过期未处理')
    await ui.expectExpiryParam('expired')
    await ui.expectRows([R.R2, R.R1])
    await expect(ui.posRow(R.R3)).toHaveCount(0)
    await page.goto('/')
    await expect(ui.id('dashboard-expired-count')).toHaveText('2')
  })

  test('AC4 次日（时钟 2026-11-19）：R3 进入"已过期未处理"（已过期 1 天），首页已过期未处理 3 个；"今天到期"为空', async ({ page }) => {
    const R = await scenarioR(T)
    await fixNow(page, '2026-11-19T15:00:00Z')
    const ui = new Ui(page)
    await page.goto('/')
    await expect(ui.id('dashboard-expired-count')).toHaveText('3')

    await ui.openPositions('?expiry=expired')
    await ui.expectRows([R.R2, R.R1, R.R3])
    await expect(ui.posRow(R.R3).getByTestId('position-cell-dte')).toHaveText('已过期 1 天')
    await expect(ui.posRow(R.R1).getByTestId('position-cell-dte')).toHaveText('已过期 6 天')
    await ui.expectFiltered(3, 8, '19,700.00')

    await ui.choose('今天到期')
    await ui.expectEmpty('没有今天到期的持仓')
    await ui.expectFiltered(0, 8, '19,700.00')
  })

  test('AC5 过滤状态下作废 R1 / 被指派 R3 / 平仓 R4：不刷新页面，不再符合的行消失，过滤选项保持，显示 M / N 与冻结合计更新', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await ui.openPositions('?expiry=expired')
    await ui.markNoReload()
    await ui.expectRows([R.R2, R.R1])

    // 作废 R1 (expired 5 days: 作废 is available at any time of day)
    await ui.posRow(R.R1).getByTestId('position-expire').click()
    const ed = ui.dialog('expire-modal')
    await expect(ed).toBeVisible()
    await ed.getByTestId('expire-submit').click()
    await expect(ed.getByTestId('expire-result')).toBeVisible()
    await expect(ed.getByTestId('expire-result-pnl')).toHaveText('+95.00')
    await expect(ed.getByTestId('expire-result-frozen-change')).toHaveText(`${MINUS}4,000.00`)
    await ed.getByTestId('expire-done').click()
    await expect(ui.dialog('expire-modal')).toBeHidden()
    await ui.expectRows([R.R2])
    await ui.expectFiltered(1, 7, '15,700.00')
    await ui.expectSelected('已过期未处理')
    await ui.expectExpiryParam('expired')

    // 被指派 R3, 2 张. At 10:00 New York the assignment date T is only allowed from 16:00 (FEAT-013), so the
    // dialog's default is the latest allowed date T − 1.
    await ui.choose('今天到期')
    await ui.expectExpiryParam('today')
    await ui.expectRows([R.R3])
    await ui.posRow(R.R3).getByTestId('position-assign').click()
    const ad = ui.dialog('assign-modal')
    await expect(ad).toBeVisible()
    await expect(ad.getByTestId('assign-date')).toHaveValue(addDays(T, -1))
    await ad.getByTestId('assign-contracts').click()
    await page.locator('.ant-select-dropdown:visible').getByTestId('assign-contracts-option-2').click()
    await ad.getByTestId('assign-submit').click()
    await expect(ad.getByTestId('assign-result')).toBeVisible()
    await expect(ad.getByTestId('assign-result-contracts')).toHaveText('2 张')
    await ad.getByTestId('assign-done').click()
    await expect(ui.dialog('assign-modal')).toBeHidden()
    await ui.expectEmpty('没有今天到期的持仓')
    await ui.expectFiltered(0, 6, '7,300.00')
    await ui.expectSelected('今天到期')
    // the assignment created a 200-share XYZ stock position, still hidden while filtering (Q109)
    await expect(ui.id('stock-positions')).toHaveCount(0)

    // 平仓 R4: STC 1 张, 20.00. The requirement's time is 2026-11-18 11:00; T − 1 11:00 is used so the time is
    // always in the past on the real clock.
    await ui.choose('7 天内')
    await ui.expectExpiryParam('7')
    await ui.expectRows([R.R4, R.R5])
    await ui.posRow(R.R4).getByTestId('position-close').click()
    const cm = ui.id('close-trade-modal')
    await expect(cm.getByRole('dialog')).toBeVisible()
    await chooseFifoLots(cm, 1)
    await cm.getByTestId('close-trade-net-amount').fill('20.00')
    await cm.getByTestId('close-trade-time').fill(at(addDays(T, -1), '11:00'))
    await cm.getByTestId('close-trade-submit').click()
    await expect(cm.getByTestId('close-trade-confirmation')).toBeVisible()
    await cm.getByTestId('close-trade-done').click()
    await expect(cm.getByRole('dialog')).toBeHidden()
    await ui.expectRows([R.R5])
    await ui.expectFiltered(1, 5, '7,300.00')
    await ui.expectSelected('7 天内')

    // back to 全部: the stock position is shown again
    await ui.choose('全部')
    await ui.expectRows([R.R2, R.R5, R.R6, R.R7, R.R8])
    await ui.expectUnfiltered(5, '7,300.00')
    await expect(page.locator('[data-testid="stock-position-row"][data-symbol="XYZ"]')).toBeVisible()
    await ui.expectNoReload()
  })

  test('AC6 空结果提示：只有 1 个 58 天后到期的持仓，各过滤显示对应提示与"显示 0 / 1 个持仓"；"全部"为 1 行', async ({ page }) => {
    const c = await scenarioSingle()
    const ui = new Ui(page)
    await ui.openPositions()
    await ui.expectRows([c])

    const cases: [FilterLabel, string, string][] = [
      ['已过期未处理', 'expired', '没有已过期未处理的持仓'],
      ['今天到期', 'today', '没有今天到期的持仓'],
      ['7 天内', '7', '没有 7 天内到期的持仓'],
      ['14 天内', '14', '没有 14 天内到期的持仓'],
      ['30 天内', '30', '没有 30 天内到期的持仓'],
    ]
    for (const [label, param, text] of cases) {
      await ui.choose(label)
      await ui.expectExpiryParam(param)
      await ui.expectEmpty(text)
      await ui.expectFiltered(0, 1, '0.00')
    }

    await ui.choose('全部')
    await ui.expectRows([c])
    await ui.expectUnfiltered(1, '0.00')
  })

  test('AC7 首页链接：已过期未处理 2 个 → /positions?expiry=expired（内容同 AC2）；7 天内到期 3 个 → ?expiry=7（R3、R4、R5）', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await page.goto('/')
    await expect(ui.id('dashboard-expired-count')).toHaveText('2')
    await expect(ui.id('dashboard-expiring-count')).toHaveText('3')
    const expired = ui.id('dashboard-expired-link')
    const expiring = ui.id('dashboard-expiring-link')
    await expect(expired).toContainText('已过期未处理')
    await expect(expiring).toContainText('7 天内到期')
    await expect(expired).toHaveAttribute('href', /\/positions\?expiry=expired$/)
    await expect(expiring).toHaveAttribute('href', /\/positions\?expiry=7$/)

    await expired.click()
    await expect.poll(() => new URL(page.url()).pathname).toBe('/positions')
    await ui.expectExpiryParam('expired')
    await ui.expectSelected('已过期未处理')
    await ui.expectRows([R.R2, R.R1])
    await ui.expectFiltered(2, 8, '19,700.00')

    await ui.id('nav-home').click()
    await ui.id('dashboard-expiring-link').click()
    await expect.poll(() => new URL(page.url()).pathname).toBe('/positions')
    await ui.expectExpiryParam('7')
    await ui.expectSelected('7 天内')
    await ui.expectRows([R.R3, R.R4, R.R5])
    await ui.expectFiltered(3, 8, '19,700.00')
  })

  test('AC7 Q108 个数为 0 仍是链接：7 天内到期 0 个、已过期未处理 0 个，点击进入对应空结果', async ({ page }) => {
    await scenarioSingle()
    const ui = new Ui(page)
    await page.goto('/')
    await expect(ui.id('dashboard-expiring-count')).toHaveText('0')
    await expect(ui.id('dashboard-expired-count')).toHaveText('0')
    await expect(ui.id('dashboard-expiring-link')).toHaveAttribute('href', /\/positions\?expiry=7$/)
    await expect(ui.id('dashboard-expired-link')).toHaveAttribute('href', /\/positions\?expiry=expired$/)

    await ui.id('dashboard-expiring-link').click()
    await ui.expectExpiryParam('7')
    await ui.expectEmpty('没有 7 天内到期的持仓')
    await ui.expectFiltered(0, 1, '0.00')

    await ui.id('nav-home').click()
    await ui.id('dashboard-expired-link').click()
    await ui.expectExpiryParam('expired')
    await ui.expectEmpty('没有已过期未处理的持仓')
    await ui.expectFiltered(0, 1, '0.00')
  })

  test('AC8 周末按日历天（时钟 2026-11-21 周六）：已过期未处理 R2、R1、R3、R4 = 首页 4 个；7 天内 R5（DTE 4）、R6（DTE 6）', async ({ page }) => {
    const R = await scenarioR(T)
    await fixNow(page, '2026-11-21T15:00:00Z') // Saturday
    const ui = new Ui(page)
    await page.goto('/')
    await expect(ui.id('dashboard-expired-count')).toHaveText('4')
    await expect(ui.id('dashboard-expiring-count')).toHaveText('2')

    await ui.openPositions('?expiry=expired')
    await ui.expectRows([R.R2, R.R1, R.R3, R.R4])
    await expect(ui.posRow(R.R4).getByTestId('position-cell-dte')).toHaveText('已过期 1 天')
    await expect(ui.posRow(R.R3).getByTestId('position-cell-dte')).toHaveText('已过期 3 天')
    await ui.expectFiltered(4, 8, '19,700.00')

    await ui.openPositions('?expiry=7')
    await ui.expectRows([R.R5, R.R6])
    await expect(ui.posRow(R.R5).getByTestId('position-cell-dte')).toHaveText('4')
    await expect(ui.posRow(R.R6).getByTestId('position-cell-dte')).toHaveText('6')
    await ui.expectFiltered(2, 8, '19,700.00')
  })

  test('AC8 按账户隔离（Q53）："小账户" ?expiry=7 只有 XYZ <T+2> 45 P、显示 1 / 1；默认账户 ?expiry=7 仍是 R3、R4、R5', async ({ page }) => {
    const R = await scenarioR(T)
    const small = await apiCreateAccount('小账户')
    setApiAccount(small)
    await apiTransfer('DEPOSIT', '5000.00', at(addDays(T, -16)))
    await apiTrade({ action: 'STO', symbol: 'XYZ', expiration: addDays(T, 2), strike: '45', type: 'PUT', netAmount: '100.00', time: at(addDays(T, -14)) })
    setApiAccount(1)
    const smallContract = `XYZ ${addDays(T, 2)} 45 P`
    const ui = new Ui(page)

    await ui.openPositions('?expiry=7')
    await ui.expectRows([R.R3, R.R4, R.R5])
    await ui.switchAccount(small, '小账户')
    await ui.openPositions('?expiry=7')
    await ui.expectSelected('7 天内')
    await ui.expectRows([smallContract])
    await ui.expectFiltered(1, 1, '4,500.00')

    await ui.switchAccount(1, '默认账户')
    await ui.openPositions('?expiry=7')
    await ui.expectRows([R.R3, R.R4, R.R5])
    await ui.expectFiltered(3, 8, '19,700.00')
  })

  test('边界：账户没有任何期权持仓时选过滤仍是持仓页原有空状态（规则 6）', async ({ page }) => {
    await apiTransfer('DEPOSIT', '1000.00', at(addDays(T, -16)))
    const ui = new Ui(page)
    await ui.openPositions('?expiry=7')
    await expect(ui.id('position-row')).toHaveCount(0)
    await expect(ui.id('positions-empty')).toBeVisible()
    await expect(ui.id('positions-expiry-empty')).toHaveCount(0)
    await ui.choose('已过期未处理')
    await expect(ui.id('positions-empty')).toBeVisible()
    await expect(ui.id('positions-expiry-empty')).toHaveCount(0)
  })

  test('边界：持仓接口报错时带过滤参数的持仓页显示错误提示；恢复后重试显示过滤结果', async ({ page }) => {
    const R = await scenarioR(T)
    const ui = new Ui(page)
    await page.route('**/api/option-positions', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', message: '服务器内部错误', status: 500, path: '/api/option-positions', timestamp: '2026-11-13T15:00:00Z', details: [] }),
      }),
    )
    await page.goto('/positions?expiry=today')
    await expect(ui.id('positions-error')).toBeVisible({ timeout: 20_000 }) // one automatic retry on 5xx
    await expect(ui.id('positions-error')).toContainText('服务器内部错误')
    await expect(ui.id('position-row')).toHaveCount(0)
    await page.unroute('**/api/option-positions')
    await ui.id('positions-retry').click()
    await ui.expectRows([R.R3])
    await ui.expectExpiryParam('today')
    await ui.expectFiltered(1, 8, '19,700.00')
  })
})
