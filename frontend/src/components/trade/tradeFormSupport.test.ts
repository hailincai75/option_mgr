import { QueryClient } from '@tanstack/react-query'
import { refreshAfterChange } from './tradeFormSupport'

describe('refreshAfterChange', () => {
  it('invalidates cash, trades, positions, audit log and closed trades and stocks and unrealized totals, the Greeks summary and the 按标的 summaries (FEAT-005, FEAT-006, FEAT-011, FEAT-017, FEAT-019, FEAT-023)', async () => {
    const queryClient = new QueryClient()
    const spy = vi.spyOn(queryClient, 'invalidateQueries')
    await refreshAfterChange(queryClient)
    expect(spy.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['cash'],
      ['option-trades'],
      ['option-positions'],
      ['unrealized-summary'],
      ['underlying-summary'],
      ['greeks-summary'],
      ['audit-log'],
      ['closed-trades'],
      ['dashboard'],
      ['pnl-summary'],
      ['stock-trades'],
      ['stock-positions'],
      ['adhoc-closure-alerts'],
    ])
  })
})
