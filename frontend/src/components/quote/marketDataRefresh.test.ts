import { QueryClient } from '@tanstack/react-query'
import { refreshQuotes } from './marketDataRefresh'

describe('refreshQuotes', () => {
  it('invalidates positions, their totals, the Greeks summary, the 按标的 summaries and the market data status (FEAT-016, FEAT-017, FEAT-019, FEAT-023)', async () => {
    const queryClient = new QueryClient()
    const spy = vi.spyOn(queryClient, 'invalidateQueries')
    await refreshQuotes(queryClient)
    expect(spy.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['option-positions'],
      ['unrealized-summary'],
      ['underlying-summary'],
      ['greeks-summary'],
      ['stock-positions'],
      ['market-data'],
    ])
  })
})
