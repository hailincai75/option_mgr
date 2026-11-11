import { Space } from 'antd'
import { CashSummaryCard } from '../components/cash/CashSummaryCard'
import { TradeEntryForm } from '../components/trade/TradeEntryForm'

/** 录入交易 page (FEAT-001 opens, FEAT-004 closes): current cash summary above the trade form. */
export function TradeEntryPage() {
  return (
    <Space
      orientation="vertical"
      size={16}
      style={{ width: '100%', maxWidth: 720 }}
      data-testid="open-trade-page"
    >
      <CashSummaryCard size="small" />
      <TradeEntryForm />
    </Space>
  )
}
