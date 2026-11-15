import type { OptionPosition, StockPosition } from '../../api/types'
import { groupByUnderlying, underlyingSummary } from './groupByUnderlying'

function option(symbol: string, accountId: number | undefined, label: string): OptionPosition {
  return {
    symbol,
    accountId,
    accountName: accountId === undefined ? undefined : `账户${String(accountId)}`,
    contractLabel: label,
  } as OptionPosition
}

function stock(symbol: string, accountId: number | undefined, shares: number): StockPosition {
  return {
    symbol,
    accountId,
    accountName: accountId === undefined ? undefined : `账户${String(accountId)}`,
    shares,
  } as StockPosition
}

describe('groupByUnderlying (FEAT-021 / FEAT-047)', () => {
  it('makes one group per account + symbol, ascending by symbol then account id', () => {
    const groups = groupByUnderlying(
      [option('ZZB', 2, 'b2'), option('ZZB', 1, 'b1'), option('ZZA', 2, 'a2'), option('ZZA', 1, 'a1')],
      [stock('ZZA', 1, 100)],
    )
    expect(groups.map((g) => [g.symbol, g.accountId, g.accountName])).toEqual([
      ['ZZA', 1, '账户1'],
      ['ZZA', 2, '账户2'],
      ['ZZB', 1, '账户1'],
      ['ZZB', 2, '账户2'],
    ])
    expect(new Set(groups.map((g) => g.key)).size).toBe(4)
    expect(groups[0]?.stock?.shares).toBe(100)
    expect(groups[1]?.stock).toBeNull()
    expect(groups[0] && underlyingSummary(groups[0])).toBe('期权 1 个持仓 · 股票 100 股')
    expect(groups[1] && underlyingSummary(groups[1])).toBe('期权 1 个持仓')
  })

  it('keeps the API order of option rows inside a group', () => {
    const groups = groupByUnderlying(
      [option('ZZA', 1, 'first'), option('ZZB', 1, 'other'), option('ZZA', 1, 'second')],
      [],
    )
    expect(groups[0]?.options.map((o) => o.contractLabel)).toEqual(['first', 'second'])
  })

  it('still groups by symbol when rows carry no account', () => {
    const groups = groupByUnderlying([option('ZZA', undefined, 'a')], [stock('ZZA', undefined, 50)])
    expect(groups).toHaveLength(1)
    expect(groups[0]?.accountId).toBeUndefined()
    expect(groups[0] && underlyingSummary(groups[0])).toBe('期权 1 个持仓 · 股票 50 股')
  })
})
