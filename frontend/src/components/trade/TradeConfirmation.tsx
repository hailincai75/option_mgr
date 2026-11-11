import { Alert, Descriptions, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import type { CoveredLot, OptionTrade } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { SIGNED_MONEY_COLORS, tradeActionLabel } from './tradeLabels'

interface TradeConfirmationProps {
  trade: OptionTrade
  onClose: () => void
  /** `open-trade` on the 录入交易 page, `close-trade` in the 平仓 dialog. */
  testIdPrefix: string
}

function SignedMoney({
  value,
  testId,
  strong,
}: {
  value: string
  testId: string
  strong?: boolean
}) {
  const style = { color: SIGNED_MONEY_COLORS[decimalSign(value)] }
  const text = formatMoney(value, { signed: true })
  return strong ? (
    <strong data-testid={testId} style={style}>
      {text}
    </strong>
  ) : (
    <span data-testid={testId} style={style}>
      {text}
    </span>
  )
}

const coveredLotColumns: ColumnsType<CoveredLot> = [
  {
    title: '开仓时间（美东）',
    key: 'time',
    render: (_value: unknown, lot) => (
      <span data-testid="covered-lot-time">{formatDateTimeNY(lot.tradedAt)}</span>
    ),
  },
  {
    title: '方向',
    key: 'action',
    render: (_value: unknown, lot) => (
      <span data-testid="covered-lot-action">{tradeActionLabel(lot.action)}</span>
    ),
  },
  {
    title: '张数',
    key: 'contracts',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="covered-lot-contracts">{lot.contracts} 张</span>
    ),
  },
  {
    title: '开仓净额',
    key: 'netAmount',
    align: 'right',
    render: (_value: unknown, lot) => (
      <SignedMoney value={lot.netAmount} testId="covered-lot-net-amount" />
    ),
  },
]

/**
 * Success message for a saved trade; every value comes from the backend response. Closing
 * trades (FEAT-004) also show the covered lots and this close's realized P&L.
 */
export function TradeConfirmation({ trade, onClose, testIdPrefix }: TradeConfirmationProps) {
  const id = (suffix: string) => `${testIdPrefix}-confirmation${suffix}`
  const coveredLots = trade.coveredLots

  return (
    <Alert
      type="success"
      showIcon
      closable={{ onClose, 'aria-label': '关闭确认信息' }}
      style={{ marginBottom: 16 }}
      data-testid={id('')}
      title={coveredLots === null ? '开仓交易已保存' : '平仓交易已保存'}
      description={
        <>
          <Descriptions column={1} size="small">
            <Descriptions.Item label="合约">
              <span data-testid={id('-contract')}>{trade.contractLabel}</span>
            </Descriptions.Item>
            <Descriptions.Item label="方向">
              <span data-testid={id('-action')}>{tradeActionLabel(trade.action)}</span>
            </Descriptions.Item>
            <Descriptions.Item label="张数">
              <span data-testid={id('-contracts')}>{trade.contracts} 张</span>
              {trade.multiplier !== 100 && (
                <span data-testid={id('-multiplier')}>（multiplier {trade.multiplier}）</span>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="净额">
              <span data-testid={id('-net-amount')}>{formatMoney(trade.netAmount)}</span>
            </Descriptions.Item>
            <Descriptions.Item label="成交时间">
              <span data-testid={id('-time')}>{formatDateTimeNY(trade.tradedAt)}（美东）</span>
            </Descriptions.Item>
            <Descriptions.Item label="现金变动">
              <SignedMoney value={trade.cashChange} testId={id('-cash-change')} />
            </Descriptions.Item>
            <Descriptions.Item label="冻结变动">
              <span data-testid={id('-frozen-change')}>
                {decimalSign(trade.frozenChange) === 0
                  ? EMPTY_VALUE
                  : formatMoney(trade.frozenChange, { signed: true })}
              </span>
            </Descriptions.Item>
            {trade.realizedPnl !== null && (
              <Descriptions.Item label="本笔已实现盈亏">
                <SignedMoney value={trade.realizedPnl} testId={id('-realized-pnl')} strong />
              </Descriptions.Item>
            )}
          </Descriptions>
          {coveredLots !== null && (
            <div data-testid={id('-covered-lots')}>
              <div style={{ margin: '8px 0 4px' }}>被平掉的开仓批次（FIFO）</div>
              <Table<CoveredLot>
                rowKey="tradeId"
                size="small"
                pagination={false}
                dataSource={coveredLots}
                columns={coveredLotColumns}
                onRow={(lot) =>
                  ({
                    'data-testid': 'covered-lot-row',
                    'data-trade-id': lot.tradeId,
                  }) as HTMLAttributes<HTMLElement>
                }
              />
            </div>
          )}
        </>
      }
    />
  )
}
