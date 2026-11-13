import { Space } from 'antd'
import { useSearchParams } from 'react-router-dom'
import { CashSummaryCard } from '../components/cash/CashSummaryCard'
import { isStockTradeType } from '../components/stock/stockLabels'
import { StockTradeForm } from '../components/stock/StockTradeForm'

/**
 * 录入股票交易 page (FEAT-011): cash summary above the stock form. `?type=SELL&symbol=IXQZ`
 * (from the positions page "卖出") preselects the type and prefills the symbol.
 */
export function StockTradeEntryPage() {
  const [searchParams] = useSearchParams()
  const typeParam = (searchParams.get('type') ?? '').trim()
  const initialType = isStockTradeType(typeParam) ? typeParam : undefined
  const initialSymbol = (searchParams.get('symbol') ?? '').trim()
  return (
    <Space
      orientation="vertical"
      size={16}
      style={{ width: '100%', maxWidth: 720 }}
      data-testid="stock-entry-page"
    >
      <CashSummaryCard size="small" />
      <StockTradeForm
        // A new prefill (e.g. another "卖出") starts a fresh form.
        key={`${initialType ?? ''}|${initialSymbol}`}
        initialType={initialType}
        initialSymbol={initialSymbol}
      />
    </Space>
  )
}
