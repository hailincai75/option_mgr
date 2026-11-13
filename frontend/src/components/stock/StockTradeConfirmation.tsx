import { Alert, Descriptions } from 'antd'
import type { StockTrade } from '../../api/types'
import { decimalSign, formatInteger, formatMoney } from '../../utils/format'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import { SoldLotTable } from './SoldLotTable'
import { stockAmountLabel, stockTimeText, stockTypeLabel } from './stockLabels'
import { StockPnlText } from './StockTradeTable'

interface StockTradeConfirmationProps {
  trade: StockTrade
  onClose: () => void
}

/**
 * Result of a saved stock record; every value comes from the backend response. Sales also show
 * the lots sold, the sold cost and the realized P&L.
 */
export function StockTradeConfirmation({ trade, onClose }: StockTradeConfirmationProps) {
  const id = (suffix: string) => `stock-entry-confirmation${suffix}`
  const isTransfer = trade.type === 'TRANSFER_IN'
  return (
    <Alert
      type="success"
      showIcon
      closable={{ onClose, 'aria-label': '关闭确认信息' }}
      style={{ marginBottom: 16 }}
      data-testid={id('')}
      title={`${stockTypeLabel(trade.type)}已保存`}
      description={
        <>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="类型">
              <span data-testid={id('-type')}>{stockTypeLabel(trade.type)}</span>
            </Descriptions.Item>
            <Descriptions.Item label="标的">
              <span data-testid={id('-symbol')}>{trade.symbol}</span>
            </Descriptions.Item>
            <Descriptions.Item label="股数">
              <span data-testid={id('-shares')}>{formatInteger(trade.shares)} 股</span>
            </Descriptions.Item>
            <Descriptions.Item label={stockAmountLabel(trade.type)}>
              <span data-testid={id('-amount')}>{formatMoney(trade.amount)}</span>
            </Descriptions.Item>
            <Descriptions.Item label={isTransfer ? '每股成本' : '每股价格'}>
              <span data-testid={id('-price')}>{formatMoney(trade.pricePerShare)}</span>
            </Descriptions.Item>
            <Descriptions.Item label={isTransfer ? '日期' : '时间（美东）'}>
              <span data-testid={id('-time')}>{stockTimeText(trade)}</span>
            </Descriptions.Item>
            <Descriptions.Item label="现金变动">
              <span
                data-testid={id('-cash-change')}
                style={{ color: SIGNED_MONEY_COLORS[decimalSign(trade.cashChange)] }}
              >
                {formatMoney(trade.cashChange, { signed: true })}
              </span>
            </Descriptions.Item>
            {trade.soldCost !== null && (
              <Descriptions.Item label="被卖出成本">
                <span data-testid={id('-sold-cost')}>{formatMoney(trade.soldCost)}</span>
              </Descriptions.Item>
            )}
            {trade.realizedPnl !== null && (
              <Descriptions.Item label="已实现盈亏">
                <strong>
                  <StockPnlText value={trade.realizedPnl} testId={id('-realized-pnl')} />
                </strong>
              </Descriptions.Item>
            )}
          </Descriptions>
          {trade.soldLots !== null && (
            <div data-testid={id('-sold-lots')}>
              <div style={{ margin: '8px 0 4px' }}>被卖出批次（FIFO）</div>
              <SoldLotTable lots={trade.soldLots} />
            </div>
          )}
        </>
      }
    />
  )
}
