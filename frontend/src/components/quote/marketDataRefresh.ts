import type { QueryClient } from '@tanstack/react-query'
import { marketDataQueryKeys } from '../../api/marketData'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { greeksSummaryQueryKeys } from '../../api/greeksSummary'
import { underlyingSummaryQueryKeys } from '../../api/underlyingSummary'
import { unrealizedSummaryQueryKeys } from '../../api/unrealizedSummary'
import { stockPositionQueryKeys } from '../../api/stockTrades'

/**
 * FEAT-016: after 立即刷新 or a manual override change, the quotes on the option and stock
 * positions (every account — quotes are shared) and the market data status reload.
 */
export async function refreshQuotes(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all }),
    // FEAT-017: the account totals follow the positions.
    queryClient.invalidateQueries({ queryKey: unrealizedSummaryQueryKeys.all }),
    // FEAT-023: the 按标的 card summaries follow the positions and realized P&L.
    queryClient.invalidateQueries({ queryKey: underlyingSummaryQueryKeys.all }),
    // FEAT-019: the per-symbol Greeks summary follows the positions too.
    queryClient.invalidateQueries({ queryKey: greeksSummaryQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: stockPositionQueryKeys.all }),
    queryClient.invalidateQueries({ queryKey: marketDataQueryKeys.all }),
  ])
}
