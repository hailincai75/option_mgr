import type { ExpirationBatchFailure, OptionPosition, OptionTrade } from '../../api/types'
import {
  batchExpirableRows,
  batchExpireButtonLabel,
  contractKey,
  expiredCountText,
  matchExpirationsToRows,
  matchFailuresToRows,
  selectedCountText,
  showsBatchExpire,
  toExpirationItem,
} from './batchExpire'

// FEAT-015 helpers. Fictional tickers only.

function row(symbol: string, strike: string, optionType: 'CALL' | 'PUT', expirable = true) {
  return {
    symbol,
    expirationDate: '2026-11-20',
    strike,
    optionType,
    contractLabel: `${symbol} 2026-11-20 ${strike} ${optionType === 'CALL' ? 'C' : 'P'}`,
    expirable,
    expiryContracts: 2,
  } as OptionPosition
}

function failure(partial: Partial<ExpirationBatchFailure>): ExpirationBatchFailure {
  return {
    index: 0,
    symbol: 'XYZ',
    expirationDate: '2026-11-20',
    strike: '42',
    optionType: 'PUT',
    contractLabel: 'XYZ 2026-11-20 42 P',
    code: 'POSITION_CHANGED',
    message: '持仓已变化（当前 2 张），请刷新后重试',
    ...partial,
  }
}

const IXQZ = row('IXQZ', '9', 'CALL')
const XYZ_42 = row('XYZ', '42', 'PUT')
const XYZ_45 = row('XYZ', '45', 'PUT')

describe('showsBatchExpire (Q118)', () => {
  it('only for 已过期未处理 and 今天到期', () => {
    expect(showsBatchExpire('expired')).toBe(true)
    expect(showsBatchExpire('today')).toBe(true)
    expect(showsBatchExpire('all')).toBe(false)
    expect(showsBatchExpire('7')).toBe(false)
    expect(showsBatchExpire('14')).toBe(false)
    expect(showsBatchExpire('30')).toBe(false)
  })
})

describe('texts', () => {
  it('formats the button, the selection count and the result count', () => {
    expect(batchExpireButtonLabel(4)).toBe('批量作废（4）')
    expect(selectedCountText(3, 4)).toBe('已选 3 / 4 个')
    expect(expiredCountText(4)).toBe('作废 4 个')
  })
})

describe('batchExpirableRows', () => {
  it('keeps only expirable rows in table order', () => {
    const pending = row('QRTV', '30', 'CALL', false)
    expect(batchExpirableRows([IXQZ, pending, XYZ_42])).toEqual([IXQZ, XYZ_42])
  })
})

describe('contractKey', () => {
  it('treats equivalent strikes and symbol case as the same contract', () => {
    const base = {
      symbol: 'XYZ',
      expirationDate: '2026-11-20',
      optionType: 'PUT',
    }
    expect(contractKey({ ...base, strike: '45' })).toBe(contractKey({ ...base, strike: '45.00' }))
    expect(contractKey({ ...base, strike: '12.5' })).toBe(contractKey({ ...base, strike: '12.50' }))
    expect(contractKey({ ...base, strike: '45' })).toBe(
      contractKey({ ...base, symbol: 'xyz', strike: '45' }),
    )
    expect(contractKey({ ...base, strike: '45' })).not.toBe(contractKey({ ...base, strike: '4.5' }))
    expect(contractKey({ ...base, strike: '450' })).not.toBe(contractKey({ ...base, strike: '45' }))
    expect(contractKey({ ...base, strike: '45' })).not.toBe(
      contractKey({ ...base, strike: '45', optionType: 'CALL' }),
    )
  })
})

describe('toExpirationItem', () => {
  it('sends the FEAT-012 fields with contracts = expiryContracts', () => {
    expect(toExpirationItem(XYZ_45)).toEqual({
      symbol: 'XYZ',
      expirationDate: '2026-11-20',
      strike: '45',
      optionType: 'PUT',
      contracts: 2,
    })
  })
})

describe('matchFailuresToRows', () => {
  const rows = [IXQZ, XYZ_42, XYZ_45]
  const sent = rows.map(toExpirationItem)

  it('matches by contract, not by index', () => {
    // index 0 is IXQZ in the request, but the failure's contract is XYZ 42 P.
    const result = matchFailuresToRows([failure({ index: 0, strike: '42.00' })], rows, sent)
    expect([...result.byRow.keys()]).toEqual([contractKey(XYZ_42)])
    expect(result.unmatched).toEqual([])
  })

  it('keeps every failure', () => {
    const result = matchFailuresToRows(
      [
        failure({ index: 1 }),
        failure({
          index: 0,
          symbol: 'IXQZ',
          strike: '9',
          optionType: 'CALL',
          contractLabel: 'IXQZ 2026-11-20 9 C',
          code: 'EXPIRY_BLOCKED_BY_LATE_TRADE',
          message: '晚于到期日美东 16:00',
        }),
      ],
      rows,
      sent,
    )
    expect(result.byRow.get(contractKey(XYZ_42))).toHaveLength(1)
    expect(result.byRow.get(contractKey(IXQZ))?.[0]?.code).toBe('EXPIRY_BLOCKED_BY_LATE_TRADE')
  })

  it('falls back to the index, then to the unmatched list', () => {
    const odd = failure({
      index: 2,
      symbol: 'ZZZ',
      contractLabel: 'ZZZ 2026-11-20 42 P',
    })
    expect([...matchFailuresToRows([odd], rows, sent).byRow.keys()]).toEqual([contractKey(XYZ_45)])
    const lost = failure({ index: 9, symbol: 'ZZZ' })
    expect(matchFailuresToRows([lost], rows, sent).unmatched).toEqual([lost])
  })
})

describe('matchExpirationsToRows', () => {
  const exp = (symbol: string, strike: string, optionType: 'CALL' | 'PUT') =>
    ({
      id: 1,
      symbol,
      expirationDate: '2026-11-20',
      strike,
      optionType,
    }) as OptionTrade

  it('pairs records with rows by contract in dialog order', () => {
    const records = [exp('XYZ', '45', 'PUT'), exp('IXQZ', '9', 'CALL'), exp('XYZ', '42', 'PUT')]
    const result = matchExpirationsToRows(records, [IXQZ, XYZ_42, XYZ_45])
    expect(result.map((r) => r.position)).toEqual([IXQZ, XYZ_42, XYZ_45])
    expect(result.map((r) => r.expiration)).toEqual([records[1], records[2], records[0]])
  })

  it('keeps rows without a record and appends records without a row', () => {
    const extra = exp('QRTV', '30', 'CALL')
    const result = matchExpirationsToRows([extra], [IXQZ])
    expect(result).toEqual([
      { key: contractKey(IXQZ), position: IXQZ, expiration: null },
      { key: contractKey(extra), position: null, expiration: extra },
    ])
  })
})
