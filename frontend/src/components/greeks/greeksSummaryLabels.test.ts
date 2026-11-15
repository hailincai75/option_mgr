import type { GreeksSummaryGroup, GreeksSummaryPortfolio } from '../../api/types'
import {
  exposureText,
  groupNotes,
  portfolioNotes,
  signedValue,
  underlyingPriceText,
} from './greeksSummaryLabels'

const QRTV: GreeksSummaryGroup = {
  symbol: 'QRTV',
  optionDelta: '87.21',
  stockDelta: '0.00',
  netDelta: '87.21',
  gamma: '4.48',
  theta: '-3.24',
  vega: '5.89',
  underlyingPrice: '52.40',
  underlyingPriceManual: false,
  underlyingPriceLabel: null,
  dollarExposure: '4569.80',
  exposureStatus: 'OK',
  exposureLabel: null,
  manualPriceNote: null,
  optionRowCount: 3,
  includedCount: 2,
  noQuoteCount: 1,
  expiredPendingCount: 0,
  deltaMissingCount: 0,
  gammaMissingCount: 0,
  thetaMissingCount: 0,
  vegaMissingCount: 0,
  excludedLabel: '1 个持仓无报价，未计入',
  missingLabel: null,
}

const EMPTY_PORTFOLIO: GreeksSummaryPortfolio = {
  dollarExposure: '0.00',
  theta: '0.00',
  vega: '0.00',
  noQuoteCount: 0,
  expiredPendingCount: 0,
  noUnderlyingPriceCount: 0,
  deltaMissingCount: 0,
  thetaMissingCount: 0,
  vegaMissingCount: 0,
  excludedLabel: null,
  noUnderlyingPriceLabel: null,
  missingLabel: null,
}

describe('signedValue', () => {
  it('adds an explicit sign and thousands separators, none for zero; null → —', () => {
    expect(signedValue('4569.80')).toBe('+4,569.80')
    expect(signedValue('-18.35')).toBe('−18.35')
    expect(signedValue('-11916.62')).toBe('−11,916.62')
    expect(signedValue('0.00')).toBe('0.00')
    expect(signedValue(null)).toBe('—')
  })
})

describe('underlyingPriceText', () => {
  it('shows the backend price unsigned, or the 无标的价 label', () => {
    expect(underlyingPriceText(QRTV)).toEqual({ text: '52.40', muted: false })
    expect(underlyingPriceText({ ...QRTV, underlyingPrice: '52.125' })).toEqual({
      text: '52.125',
      muted: false,
    })
    expect(
      underlyingPriceText({ ...QRTV, underlyingPrice: null, underlyingPriceLabel: '无标的价' }),
    ).toEqual({ text: '无标的价', muted: true })
  })
})

describe('exposureText', () => {
  it('OK → signed exposure; NO_UNDERLYING_PRICE → label; NO_DELTA → —', () => {
    expect(exposureText(QRTV)).toEqual({ text: '+4,569.80', muted: false })
    expect(exposureText({ ...QRTV, dollarExposure: '0.00' })).toEqual({ text: '0.00', muted: false })
    expect(
      exposureText({
        ...QRTV,
        dollarExposure: null,
        exposureStatus: 'NO_UNDERLYING_PRICE',
        exposureLabel: '无标的价',
      }),
    ).toEqual({ text: '无标的价', muted: true })
    expect(
      exposureText({ ...QRTV, dollarExposure: null, exposureStatus: 'NO_DELTA', exposureLabel: null }),
    ).toEqual({ text: '—', muted: true })
  })
})

describe('groupNotes / portfolioNotes', () => {
  it('lists the non-null notes in contract order', () => {
    expect(groupNotes({ ...QRTV, excludedLabel: null })).toEqual([])
    expect(
      groupNotes({
        ...QRTV,
        manualPriceNote: '按手动标的价',
        missingLabel: 'Gamma、Vega：1 个持仓缺值，未计入',
      }),
    ).toEqual([
      { key: 'manual-price', text: '按手动标的价' },
      { key: 'excluded', text: '1 个持仓无报价，未计入' },
      { key: 'missing', text: 'Gamma、Vega：1 个持仓缺值，未计入' },
    ])
    expect(portfolioNotes(EMPTY_PORTFOLIO)).toEqual([])
    expect(
      portfolioNotes({
        ...EMPTY_PORTFOLIO,
        excludedLabel: '1 个持仓无报价，未计入',
        noUnderlyingPriceLabel: '1 个标的无标的价，美元敞口未计入',
        missingLabel: 'Vega：1 个持仓缺值，未计入',
      }),
    ).toEqual([
      { key: 'excluded', text: '1 个持仓无报价，未计入' },
      { key: 'no-underlying-price', text: '1 个标的无标的价，美元敞口未计入' },
      { key: 'missing', text: 'Vega：1 个持仓缺值，未计入' },
    ])
  })
})
