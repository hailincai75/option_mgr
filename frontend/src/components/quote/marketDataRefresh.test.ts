import { QueryClient } from '@tanstack/react-query'
import { refreshQuotes } from './marketDataRefresh'

describe('refreshQuotes', () => {
  it('invalidates positions, their totals, the Greeks summary and the market data status (FEAT-016, FEAT-017, FEAT-019)', async () => {
    const queryClient = new QueryClient()
    const spy = vi.spyOn(queryClient, 'invalidateQueries')
    await refreshQuotes(queryClient)
    expect(spy.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['option-positions'],
      ['unrealized-summary'],
      ['greeks-summary'],
      ['stock-positions'],
      ['market-data'],
    ])
  })
})
