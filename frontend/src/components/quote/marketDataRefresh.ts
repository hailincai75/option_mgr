import type { QueryClient } from '@tanstack/react-query'
import { marketDataQueryKeys } from '../../api/marketData'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { stockPositionQueryKeys } from '../../api/stockTrades'

/**
 * FEAT-016: after 立即刷新 or a manual override change, the quotes on the option and stock
 * positions (every account — quotes are shared) and the market data status reload.
 */
export async function refreshQuotes(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: stockPositionQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: marketDataQueryKeys.all }),
  ])
}
