import type { OptionGreeks } from '../../api/types'
import {
  GREEKS_COLUMNS,
  GREEKS_NOTE,
  greeksDisplay,
  readShowGreeks,
  SHOW_GREEKS_STORAGE_KEY,
  writeShowGreeks,
} from './greeksLabels'

const EMPTY: OptionGreeks = {
  status: 'OK',
  label: null,
  ivPercent: null,
  deltaPerContract: null,
  deltaPosition: null,
  gammaPerContract: null,
  gammaPosition: null,
  thetaPerContract: null,
  thetaPosition: null,
  vegaPerContract: null,
  vegaPosition: null,
  quoteTime: null,
  quoteTimeLocal: null,
  manualMarkNote: null,
}

/** Contract example P1 (STO 1 QRTV 50P). */
const P1: OptionGreeks = {
  ...EMPTY,
  ivPercent: '35.12',
  deltaPerContract: '-0.3021',
  deltaPosition: '30.21',
  gammaPerContract: '0.0512',
  gammaPosition: '-5.12',
  thetaPerContract: '-0.0456',
  thetaPosition: '4.56',
  vegaPerContract: '0.0711',
  vegaPosition: '-7.11',
  quoteTime: '2026-11-23T14:45:00Z',
  quoteTimeLocal: '2026-11-23T09:45:00',
}

describe('greeksDisplay (FEAT-018)', () => {
  it('formats position values with an explicit sign and per-contract values with "−" only', () => {
    expect(greeksDisplay(P1)).toEqual({
      kind: 'value',
      iv: '35.12%',
      greeks: {
        delta: { position: '+30.21', perContract: '−0.3021' },
        gamma: { position: '−5.12', perContract: '0.0512' },
        theta: { position: '+4.56', perContract: '−0.0456' },
        vega: { position: '−7.11', perContract: '0.0711' },
      },
      manualMarkNote: null,
    })
  })

  it('shows 0 without a sign and keeps thousands separators', () => {
    const display = greeksDisplay({
      ...P1,
      deltaPosition: '0.00',
      deltaPerContract: '0.0000',
      gammaPosition: '1234.50',
      gammaPerContract: '1.0000',
    })
    if (display.kind !== 'value') throw new Error('expected values')
    expect(display.greeks.delta).toEqual({ position: '0.00', perContract: '0.0000' })
    expect(display.greeks.gamma).toEqual({ position: '+1,234.50', perContract: '1.0000' })
  })

  it('shows "—" only for the null fields (Q172)', () => {
    const display = greeksDisplay({
      ...P1,
      gammaPerContract: null,
      gammaPosition: null,
      vegaPerContract: null,
      vegaPosition: null,
    })
    if (display.kind !== 'value') throw new Error('expected values')
    expect(display.greeks.gamma).toEqual({ position: '—', perContract: '—' })
    expect(display.greeks.vega).toEqual({ position: '—', perContract: '—' })
    expect(display.greeks.delta.position).toBe('+30.21')
    expect(display.iv).toBe('35.12%')
    expect(greeksDisplay({ ...P1, ivPercent: null })).toMatchObject({ iv: '—' })
  })

  it('passes the manual-mark note through verbatim (Q170)', () => {
    const note = 'Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整'
    expect(greeksDisplay({ ...P1, manualMarkNote: note })).toMatchObject({ manualMarkNote: note })
  })

  it('shows the backend label when the status is not OK, ignoring any values', () => {
    expect(greeksDisplay({ ...P1, status: 'NO_QUOTE', label: '无报价' })).toEqual({
      kind: 'label',
      label: '无报价',
    })
    expect(greeksDisplay({ ...EMPTY, status: 'EXPIRED_PENDING', label: '已到期，待处理' })).toEqual(
      { kind: 'label', label: '已到期，待处理' },
    )
    expect(greeksDisplay({ ...EMPTY, status: 'NO_QUOTE', label: null })).toEqual({
      kind: 'label',
      label: '—',
    })
  })

  it('treats an older response without greeks as missing', () => {
    expect(greeksDisplay(undefined)).toEqual({ kind: 'missing' })
  })
})

describe('Greeks texts (FEAT-018)', () => {
  it('lists the five columns in order with plain-language hints', () => {
    expect(GREEKS_COLUMNS.map((column) => column.title)).toEqual([
      'IV',
      'Delta',
      'Gamma',
      'Theta',
      'Vega',
    ])
    const theta = GREEKS_COLUMNS.find((column) => column.key === 'theta')
    expect(theta?.hint).toContain('持仓值 = 每天大约赚 / 亏多少美元')
    expect(GREEKS_NOTE).toBe(
      'IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考',
    )
  })
})

describe('显示 Greeks storage', () => {
  it('defaults to off and remembers the choice', () => {
    expect(readShowGreeks()).toBe(false)
    writeShowGreeks(true)
    expect(window.localStorage.getItem(SHOW_GREEKS_STORAGE_KEY)).toBe('true')
    expect(readShowGreeks()).toBe(true)
    writeShowGreeks(false)
    expect(window.localStorage.getItem(SHOW_GREEKS_STORAGE_KEY)).toBeNull()
    expect(readShowGreeks()).toBe(false)
  })

  it('falls back to off when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(readShowGreeks()).toBe(false)
    expect(() => {
      writeShowGreeks(true)
    }).not.toThrow()
  })
})
