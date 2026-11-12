import { QueryClient } from '@tanstack/react-query'
import { refreshAfterChange } from './tradeFormSupport'

describe('refreshAfterChange', () => {
  it('invalidates cash, trades, positions, audit log and closed trades (FEAT-005, FEAT-006)', async () => {
    const queryClient = new QueryClient()
    const spy = vi.spyOn(queryClient, 'invalidateQueries')
    await refreshAfterChange(queryClient)
    expect(spy.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['cash'],
      ['option-trades'],
      ['option-positions'],
      ['audit-log'],
      ['closed-trades'],
      ['dashboard'],
      ['pnl-summary'],
    ])
  })
})
