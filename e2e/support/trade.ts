import { expect, type Locator, type Page } from '@playwright/test'
import { BACKEND_URL } from './backend'

export interface TradeInput {
  action?: 'BTO' | 'STO'
  symbol?: string
  expiration?: string
  strike?: string
  type?: 'CALL' | 'PUT'
  contracts?: string
  netAmount?: string
  time?: string
  multiplier?: string
  note?: string
}

/** Seeds an opening trade through the API (for "Given" states). */
export async function apiTrade(t: {
  action: 'BTO' | 'STO'
  symbol?: string
  expiration?: string
  strike: string
  type: 'CALL' | 'PUT'
  contracts?: number
  netAmount: string
  time: string
  multiplier?: number
  note?: string
}): Promise<{ id: number; tradedAt: string; tradedAtLocal: string }> {
  const r = await fetch(`${BACKEND_URL}/api/option-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      symbol: t.symbol ?? 'AAPL',
      expirationDate: t.expiration ?? '2026-12-18',
      strike: t.strike,
      optionType: t.type,
      action: t.action,
      contracts: t.contracts ?? 1,
      netAmount: t.netAmount,
      tradedAtLocal: t.time,
      multiplier: t.multiplier ?? 100,
      note: t.note ?? null,
    }),
  })
  if (r.status !== 201) throw new Error(`seed trade failed: ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

/**
 * FEAT-039 scenario L: an STO call "entered before FEAT-039 went live" (coverage status LEGACY, Q79) via the e2e-only
 * POST /api/test-support/legacy-option-trades (same body / response as POST /api/option-trades; skips E1 / E3, then
 * runs the automatic inclusion once). Sent with the current API account (X-Account-Id added by the fetch patch).
 */
export async function apiLegacyTrade(t: {
  symbol?: string
  expiration?: string
  strike: string
  contracts?: number
  netAmount: string
  time: string
  multiplier?: number
  note?: string
}): Promise<{ id: number; tradedAt: string; tradedAtLocal: string }> {
  const r = await fetch(`${BACKEND_URL}/api/test-support/legacy-option-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      symbol: t.symbol ?? 'AAPL',
      expirationDate: t.expiration ?? '2026-12-18',
      strike: t.strike,
      optionType: 'CALL',
      action: 'STO',
      contracts: t.contracts ?? 1,
      netAmount: t.netAmount,
      tradedAtLocal: t.time,
      multiplier: t.multiplier ?? 100,
      note: t.note ?? null,
    }),
  })
  if (r.status !== 201) throw new Error(`seed legacy call failed: ${r.status} ${await r.text()}`)
  return (await r.json()) as never
}

/**
 * FEAT-039: STO calls need shares. Seeds a stock 转入 (TRANSFER_IN: no cash effect, Q71) of `shares` `symbol` on the
 * New York date `tradeDate` (counted from 00:00, Q86) in the current API account; returns the stock record id.
 */
export async function apiTransferInShares(symbol: string, shares: number, tradeDate: string, amount = '1.00'): Promise<number> {
  const r = await fetch(`${BACKEND_URL}/api/stock-trades`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'TRANSFER_IN', symbol, shares, amount, tradeDate, note: null }),
  })
  if (r.status !== 201) throw new Error(`seed TRANSFER_IN ${shares} ${symbol} failed: ${r.status} ${await r.text()}`)
  return ((await r.json()) as { id: number }).id
}

export async function apiGetTrade(id: number): Promise<Record<string, unknown>> {
  return (await (await fetch(`${BACKEND_URL}/api/option-trades/${id}`)).json()) as Record<string, unknown>
}

export async function apiTradeCount(): Promise<number> {
  // No list endpoint in FEAT-001: count OPTION_OPEN ledger entries (1:1 with trades).
  const r = await fetch(`${BACKEND_URL}/api/cash/ledger?page=1&size=100`)
  const body = (await r.json()) as { items: { sourceType: string }[] }
  return body.items.filter((i) => i.sourceType === 'OPTION_OPEN').length
}

export class TradeForm {
  readonly actionSto: Locator
  readonly actionBto: Locator
  readonly symbol: Locator
  readonly expiration: Locator
  readonly strike: Locator
  readonly typeCall: Locator
  readonly typePut: Locator
  readonly contracts: Locator
  readonly netAmount: Locator
  readonly netAmountLabel: Locator
  readonly time: Locator
  readonly multiplier: Locator
  readonly note: Locator
  readonly submit: Locator
  readonly clear: Locator
  readonly error: Locator
  readonly confirmation: Locator

  constructor(readonly page: Page) {
    const id = (s: string) => page.getByTestId(s)
    this.actionSto = id('open-trade-action-sto')
    this.actionBto = id('open-trade-action-bto')
    this.symbol = id('open-trade-symbol')
    this.expiration = id('open-trade-expiration')
    this.strike = id('open-trade-strike')
    this.typeCall = id('open-trade-type-call')
    this.typePut = id('open-trade-type-put')
    this.contracts = id('open-trade-contracts')
    this.netAmount = id('open-trade-net-amount')
    this.netAmountLabel = id('open-trade-net-amount-label')
    this.time = id('open-trade-time')
    this.multiplier = id('open-trade-multiplier')
    this.note = id('open-trade-note')
    this.submit = id('open-trade-submit')
    this.clear = id('open-trade-clear')
    this.error = id('open-trade-error')
    this.confirmation = id('open-trade-confirmation')
  }

  confirm(part: string): Locator {
    return this.page.getByTestId(`open-trade-confirmation-${part}`)
  }

  async open(): Promise<void> {
    await this.page.goto('/trades/new')
    await expect(this.page.getByTestId('open-trade-form')).toBeVisible()
    await expect(this.page.getByTestId('cash-summary-total')).toBeVisible()
  }

  fieldError(field: Locator): Locator {
    return this.page.locator('.ant-form-item').filter({ has: field }).locator('.ant-form-item-explain-error')
  }

  allFieldErrors(): Locator {
    return this.page.getByTestId('open-trade-form').locator('.ant-form-item-explain-error')
  }

  async fill(t: TradeInput): Promise<void> {
    if (t.action) await (t.action === 'STO' ? this.actionSto : this.actionBto).check()
    if (t.symbol !== undefined) await this.symbol.fill(t.symbol)
    if (t.expiration !== undefined) await this.expiration.fill(t.expiration)
    if (t.strike !== undefined) await this.strike.fill(t.strike)
    if (t.type) await (t.type === 'CALL' ? this.typeCall : this.typePut).check()
    if (t.contracts !== undefined) await this.contracts.fill(t.contracts)
    if (t.netAmount !== undefined) await this.netAmount.fill(t.netAmount)
    if (t.time !== undefined) await this.time.fill(t.time)
    if (t.multiplier !== undefined) await this.multiplier.fill(t.multiplier)
    if (t.note !== undefined) await this.note.fill(t.note)
  }

  /** Default trade: AAPL 2026-12-18, 1 contract, multiplier 100, 2026-11-10 10:30. */
  static base(overrides: TradeInput): TradeInput {
    return {
      symbol: 'AAPL',
      expiration: '2026-12-18',
      contracts: '1',
      time: '2026-11-10T10:30',
      multiplier: '100',
      ...overrides,
    }
  }

  async submitAndExpectSaved(t: TradeInput): Promise<void> {
    await this.fill(t)
    await this.submit.click()
    await expect(this.confirmation).toBeVisible()
    await expect(this.error).toHaveCount(0)
  }

  async submitAndExpectError(t: TradeInput, code: string, message: string | RegExp): Promise<void> {
    await this.fill(t)
    await this.submit.click()
    await expect(this.error).toHaveText(message)
    await expect(this.error).toHaveAttribute('data-error-code', code)
  }

  /** All form values, for "input kept" checks. */
  async values(): Promise<Record<string, string | boolean>> {
    return {
      sto: await this.actionSto.isChecked(),
      bto: await this.actionBto.isChecked(),
      symbol: await this.symbol.inputValue(),
      expiration: await this.expiration.inputValue(),
      strike: await this.strike.inputValue(),
      call: await this.typeCall.isChecked(),
      put: await this.typePut.isChecked(),
      contracts: await this.contracts.inputValue(),
      netAmount: await this.netAmount.inputValue(),
      time: await this.time.inputValue(),
      multiplier: await this.multiplier.inputValue(),
      note: await this.note.inputValue(),
    }
  }
}
