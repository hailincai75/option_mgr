import { ApiError } from '../../api/client'
import type { MarketDataRefreshResponse, MarketDataStatus, OptionQuote } from '../../api/types'
import {
  bidAskText,
  isFinnhubPrice,
  lastSuccessText,
  manualTagText,
  NO_QUOTE_TEXT,
  overrideErrorDisplay,
  quotePriceText,
  quoteTimeText,
  refreshOutcome,
  refreshRecheckDelay,
  validateManualPrice,
} from './quoteLabels'

const STATUS: MarketDataStatus = {
  stale: false,
  warning: null,
  lastSuccessAt: '2026-11-23T15:00:00Z',
  lastSuccessAtLocal: '2026-11-23T10:00:00',
  lastAttemptAt: '2026-11-23T15:00:00Z',
  lastAttemptAtLocal: '2026-11-23T10:00:00',
  lastRefreshAt: null,
  lastRefreshAtLocal: null,
  refreshAvailableAt: null,
  refreshAllowed: true,
  trackedSymbolCount: 2,
  optionSource: 'YAHOO',
  riskFreeRatePercent: '4.00',
}

function optionQuote(partial: Partial<OptionQuote>): OptionQuote {
  return {
    bid: null,
    ask: null,
    mark: null,
    automaticMark: null,
    markBasis: null,
    source: null,
    quoteTime: null,
    quoteTimeLocal: null,
    contractStatus: 'NEVER_QUOTED',
    contractStatusLabel: null,
    manual: false,
    manualEnteredAt: null,
    manualEnteredAtLocal: null,
    fetchFailed: false,
    lastSuccessAt: null,
    lastSuccessAtLocal: null,
    staleLabel: null,
    ...partial,
  }
}

describe('quote display texts', () => {
  it('shows backend prices without rounding, 无报价 for null', () => {
    expect(quotePriceText('1.25')).toBe('1.25')
    expect(quotePriceText('0.035')).toBe('0.035')
    expect(quotePriceText('1.255')).toBe('1.255')
    expect(quotePriceText(null)).toBe(NO_QUOTE_TEXT)
    expect(quotePriceText(undefined)).toBe('无报价')
  })

  it('builds the manual tag from the New York entry time', () => {
    expect(manualTagText('2026-11-23T10:05:00')).toBe('手动 11-23 10:05')
    expect(manualTagText(null)).toBe('手动')
  })

  it('shows bid / ask, each side — when null', () => {
    expect(bidAskText(optionQuote({ bid: '1.20', ask: '1.30' }))).toBe('1.20 / 1.30')
    expect(bidAskText(optionQuote({ bid: '0.00', ask: '0.05' }))).toBe('0.00 / 0.05')
    expect(bidAskText(optionQuote({ bid: '1.20', ask: null }))).toBe('1.20 / —')
    expect(bidAskText(optionQuote({}))).toBe('—')
    expect(bidAskText(undefined)).toBe('—')
  })

  it('shows the quote time as MM-DD HH:mm', () => {
    expect(quoteTimeText(optionQuote({ quoteTimeLocal: '2026-11-23T09:45:00' }))).toBe(
      '11-23 09:45',
    )
    expect(quoteTimeText(optionQuote({}))).toBe('—')
    expect(quoteTimeText(undefined)).toBe('—')
  })

  it('marks only an automatic Finnhub price for the hover', () => {
    const base = {
      symbol: 'IXQZ',
      price: '61.05',
      automaticPrice: '61.05',
      source: 'FINNHUB',
      quoteTime: null,
      quoteTimeLocal: null,
      manual: false,
      manualEnteredAt: null,
      manualEnteredAtLocal: null,
      fetchFailed: false,
      lastSuccessAt: null,
      lastSuccessAtLocal: null,
      staleLabel: null,
    }
    expect(isFinnhubPrice(base)).toBe(true)
    expect(isFinnhubPrice({ ...base, source: 'CBOE' })).toBe(false)
    expect(isFinnhubPrice({ ...base, source: 'YAHOO' })).toBe(false)
    expect(isFinnhubPrice({ ...base, manual: true, price: '60.00' })).toBe(false)
    expect(isFinnhubPrice(undefined)).toBe(false)
  })

  it('describes the last successful run', () => {
    expect(lastSuccessText(STATUS)).toBe('行情更新于 2026-11-23 10:00（美东）')
    expect(lastSuccessText({ ...STATUS, lastSuccessAtLocal: null })).toBe('行情尚未成功获取')
    expect(lastSuccessText(undefined)).toBeNull()
  })
})

describe('refreshOutcome', () => {
  const result = (symbol: string, value: string) => ({
    symbol,
    result: value,
    optionSource: 'YAHOO',
    yahooResult: value === 'SUCCESS' ? 'SUCCESS' : 'HTTP_ERROR',
    cboeResult: null,
    finnhubResult: null,
    underlyingPriceSource: null,
    failureDetail: null,
  })
  const response = (partial: Partial<MarketDataRefreshResponse>): MarketDataRefreshResponse => ({
    performed: true,
    reason: null,
    results: [],
    status: STATUS,
    ...partial,
  })

  it('reports the cooldown', () => {
    expect(refreshOutcome(response({ performed: false, reason: 'COOLDOWN' }))).toEqual({
      kind: 'cooldown',
      text: '1 分钟内只能刷新一次，本次未重新获取',
    })
  })

  it('reports success and the symbols whose option chain was not updated', () => {
    expect(
      refreshOutcome(response({ results: [result('IXQZ', 'SUCCESS'), result('QRTV', 'SUCCESS')] })),
    ).toEqual({ kind: 'success', text: '已刷新 2 个标的' })
    expect(
      refreshOutcome(
        response({ results: [result('IXQZ', 'FALLBACK'), result('QRTV', 'FAILED')] }),
      ),
    ).toEqual({ kind: 'partial', text: '已刷新，期权报价未更新：IXQZ（仅股价）、QRTV' })
    expect(refreshOutcome(response({})).kind).toBe('success')
  })
})

describe('refreshRecheckDelay', () => {
  const now = Date.parse('2026-11-23T15:02:30Z')

  it('is null while refreshing is allowed or never happened', () => {
    expect(refreshRecheckDelay(STATUS, now)).toBeNull()
    expect(refreshRecheckDelay(undefined, now)).toBeNull()
    expect(
      refreshRecheckDelay({ refreshAllowed: false, refreshAvailableAt: null }, now),
    ).toBeNull()
  })

  it('waits until refreshAvailableAt, clamped to 1–60 s', () => {
    const cooling = (at: string) => ({ refreshAllowed: false, refreshAvailableAt: at })
    expect(refreshRecheckDelay(cooling('2026-11-23T15:03:00Z'), now)).toBe(30_500)
    expect(refreshRecheckDelay(cooling('2026-11-30T15:03:00Z'), now)).toBe(60_000)
    expect(refreshRecheckDelay(cooling('2026-11-20T15:03:00Z'), now)).toBe(1_000)
  })
})

describe('validateManualPrice', () => {
  it('accepts positive prices with up to 4 decimals below 1,000,000', () => {
    expect(validateManualPrice('1.40')).toBeNull()
    expect(validateManualPrice(' 52 ')).toBeNull()
    expect(validateManualPrice('0.0001')).toBeNull()
    expect(validateManualPrice('999999.9999')).toBeNull()
  })

  it('uses the backend messages', () => {
    expect(validateManualPrice('')).toBe('请输入价格')
    expect(validateManualPrice(undefined)).toBe('请输入价格')
    expect(validateManualPrice('0')).toBe('价格必须大于 0')
    expect(validateManualPrice('0.0000')).toBe('价格必须大于 0')
    expect(validateManualPrice('1.23456')).toBe('价格最多 4 位小数')
    expect(validateManualPrice('1000000')).toBe('价格过大')
    expect(validateManualPrice('-1')).toBe('请输入有效的价格')
    expect(validateManualPrice('abc')).toBe('请输入有效的价格')
    expect(validateManualPrice('1e3')).toBe('请输入有效的价格')
  })
})

describe('overrideErrorDisplay', () => {
  it('puts price / mark details under the input', () => {
    const error = new ApiError({
      code: 'VALIDATION_ERROR',
      message: 'Request validation failed',
      status: 400,
      details: [{ field: 'mark', message: '价格必须大于 0' }],
    })
    expect(overrideErrorDisplay(error)).toEqual({ fieldErrors: ['价格必须大于 0'], formError: null })
  })

  it('shows 422 messages verbatim and appends other details', () => {
    const notHeld = new ApiError({
      code: 'QUOTE_TARGET_NOT_HELD',
      message: '当前没有 QRTV 的持仓，不能设置手动价格',
      status: 422,
    })
    expect(overrideErrorDisplay(notHeld)).toEqual({
      fieldErrors: [],
      formError: { code: 'QUOTE_TARGET_NOT_HELD', message: '当前没有 QRTV 的持仓，不能设置手动价格' },
    })
    const other = new ApiError({
      code: 'VALIDATION_ERROR',
      message: 'Request validation failed',
      status: 400,
      details: [{ field: 'strike', message: '行权价必须大于 0' }],
    })
    expect(overrideErrorDisplay(other).formError?.message).toBe(
      'Request validation failed（strike: 行权价必须大于 0）',
    )
    expect(overrideErrorDisplay(new Error('boom')).formError).toEqual({
      code: 'UNKNOWN',
      message: 'boom',
    })
  })
})
