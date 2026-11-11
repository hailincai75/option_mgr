import { Alert, Descriptions } from 'antd'
import type { OptionTrade } from '../../api/types'
import { decimalSign, formatDateTimeNY, formatMoney } from '../../utils/format'

const ACTION_LABELS: Record<string, string> = {
  BTO: 'BTO（买入开仓）',
  STO: 'STO（卖出开仓）',
}

const SIGN_COLORS = { 1: '#389e0d', [-1]: '#cf1322', 0: undefined } as const

interface OpenTradeConfirmationProps {
  trade: OptionTrade
  onClose: () => void
}

/** Success message for a saved opening trade; every value comes from the backend response. */
export function OpenTradeConfirmation({ trade, onClose }: OpenTradeConfirmationProps) {
  return (
    <Alert
      type="success"
      showIcon
      closable={{ onClose, 'aria-label': '关闭确认信息' }}
      style={{ marginBottom: 16 }}
      data-testid="open-trade-confirmation"
      title="开仓交易已保存"
      description={
        <Descriptions column={1} size="small">
          <Descriptions.Item label="合约">
            <span data-testid="open-trade-confirmation-contract">{trade.contractLabel}</span>
          </Descriptions.Item>
          <Descriptions.Item label="方向">
            <span data-testid="open-trade-confirmation-action">
              {ACTION_LABELS[trade.action] ?? trade.action}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="张数">
            <span data-testid="open-trade-confirmation-contracts">{trade.contracts} 张</span>
            {trade.multiplier !== 100 && (
              <span data-testid="open-trade-confirmation-multiplier">
                （multiplier {trade.multiplier}）
              </span>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="净额">
            <span data-testid="open-trade-confirmation-net-amount">
              {formatMoney(trade.netAmount)}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="成交时间">
            <span data-testid="open-trade-confirmation-time">
              {formatDateTimeNY(trade.tradedAt)}（美东）
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="现金变动">
            <span
              data-testid="open-trade-confirmation-cash-change"
              style={{ color: SIGN_COLORS[decimalSign(trade.cashChange)] }}
            >
              {formatMoney(trade.cashChange, { signed: true })}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="冻结变动">
            <span data-testid="open-trade-confirmation-frozen-change">
              {formatMoney(trade.frozenChange, { signed: true })}
            </span>
          </Descriptions.Item>
        </Descriptions>
      }
    />
  )
}
