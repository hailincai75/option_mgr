import { Space } from 'antd'
import { CashSummaryCard } from '../components/cash/CashSummaryCard'
import { OpenTradeForm } from '../components/trade/OpenTradeForm'

/** 录入开仓 page (FEAT-001): current cash summary above the opening-trade form. */
export function OpenTradePage() {
  return (
    <Space
      orientation="vertical"
      size={16}
      style={{ width: '100%', maxWidth: 720 }}
      data-testid="open-trade-page"
    >
      <CashSummaryCard size="small" />
      <OpenTradeForm />
    </Space>
  )
}
