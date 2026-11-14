import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Modal, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { adhocClosureAlertQueryKeys } from '../../api/adhocClosureAlerts'
import { ApiError } from '../../api/client'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { unrealizedSummaryQueryKeys } from '../../api/unrealizedSummary'
import { createExpiration } from '../../api/optionTrades'
import type { OptionPosition, OptionTrade } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatLocalDateTime, formatMoney } from '../../utils/format'
import { CoveredLotTable } from '../trade/TradeConfirmation'
import { refreshAfterTradeSaved, type SubmitError } from '../trade/tradeFormSupport'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import { directionLabel, frozenText } from './expireLabels'

const EXPLANATION =
  '到期作废记在到期日 16:00；空头保留全部权利金、多头亏掉全部开仓成本；记错可以在交易列表中删除'

/** Signed backend amount, green when positive and red when negative (also used by FEAT-015). */
export function SignedMoney({ value, testId }: { value: string; testId: string }) {
  return (
    <span
      data-testid={testId}
      style={{ color: SIGNED_MONEY_COLORS[decimalSign(value)], fontVariantNumeric: 'tabular-nums' }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

interface ExpirePositionModalProps {
  /** Snapshot of the position when 作废 was clicked. */
  position: OptionPosition
  onDone: () => void
}

/**
 * 作废 dialog opened from an expirable position row (FEAT-012). Nothing to fill in (Q84): it shows
 * the backend's values and sends `POST /api/option-trades/expirations` with `contracts` =
 * `expiryContracts`. Rejections are shown verbatim and keep the dialog open; on success the result
 * (realized P&L, frozen change, expired lots) comes from the response.
 */
export function ExpirePositionModal({ position, onDone }: ExpirePositionModalProps) {
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [saved, setSaved] = useState<OptionTrade | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      createExpiration({
        symbol: position.symbol,
        expirationDate: position.expirationDate,
        strike: position.strike,
        optionType: position.optionType,
        contracts: position.expiryContracts,
      }),
    onSuccess: async (trade) => {
      setSaved(trade)
      await refreshAfterTradeSaved(queryClient, trade)
    },
    onError: async (error) => {
      const detail =
        error instanceof ApiError && error.details.length > 0
          ? `（${error.details.map((item) => `${item.field}: ${item.message}`).join('；')}）`
          : ''
      setSubmitError({
        code: error instanceof ApiError ? error.code : 'UNKNOWN',
        message: `${error.message}${detail}`,
      })
      // The position may have changed (e.g. 持仓已变化): reload the list behind the dialog.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all }),
        // FEAT-017: the account totals follow the positions.
        queryClient.invalidateQueries({ queryKey: unrealizedSummaryQueryKeys.all }),
        queryClient.invalidateQueries({ queryKey: adhocClosureAlertQueryKeys.all }),
      ])
    },
  })

  const multiplierNote =
    position.multiplier !== 100 ? `（multiplier ${String(position.multiplier)}）` : ''

  return (
    <Modal
      open
      title={`作废 ${position.contractLabel}`}
      onCancel={onDone}
      footer={null}
      width={640}
      mask={{ closable: false }}
      data-testid="expire-modal"
    >
      {saved !== null ? (
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Alert type="success" showIcon title="已到期作废" data-testid="expire-result" />
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="合约">
              <span data-testid="expire-result-contract">{saved.contractLabel}</span>
            </Descriptions.Item>
            <Descriptions.Item label="方向">
              <span data-testid="expire-result-direction">
                {directionLabel(saved.expiredDirection)}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="张数">
              <span data-testid="expire-result-contracts">
                {saved.contracts} 张
                {saved.multiplier !== 100 && `（multiplier ${String(saved.multiplier)}）`}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="记账时间">
              <span data-testid="expire-result-time">
                {formatLocalDateTime(saved.tradedAtLocal)}（美东）
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="已实现盈亏">
              {saved.realizedPnl === null ? (
                <span data-testid="expire-result-pnl">{EMPTY_VALUE}</span>
              ) : (
                <strong>
                  <SignedMoney value={saved.realizedPnl} testId="expire-result-pnl" />
                </strong>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="冻结变动">
              <span data-testid="expire-result-frozen-change">
                {frozenText(saved.frozenChange, true)}
              </span>
            </Descriptions.Item>
          </Descriptions>
          {saved.coveredLots !== null && (
            <div data-testid="expire-result-lots">
              <div style={{ margin: '0 0 4px' }}>被作废的开仓批次（FIFO）</div>
              <CoveredLotTable lots={saved.coveredLots} />
            </div>
          )}
          <Button type="primary" onClick={onDone} data-testid="expire-done">
            完成
          </Button>
        </Space>
      ) : (
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="合约">
              <span data-testid="expire-contract">{position.contractLabel}</span>
            </Descriptions.Item>
            <Descriptions.Item label="方向">
              <Tag data-testid="expire-direction">{directionLabel(position.direction)}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="作废张数">
              <span data-testid="expire-contracts">
                {position.expiryContracts} 张（全部剩余）{multiplierNote}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="记账时间">
              <span data-testid="expire-time">
                {formatLocalDateTime(position.expiryAtLocal)}（美东）
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="开仓净额合计">
              <SignedMoney value={position.openingNetTotal} testId="expire-opening-net" />
            </Descriptions.Item>
            <Descriptions.Item label="将释放的冻结">
              <span data-testid="expire-frozen">{frozenText(position.frozenCash, false)}</span>
            </Descriptions.Item>
          </Descriptions>
          <Typography.Text type="secondary" data-testid="expire-explanation">
            {EXPLANATION}
          </Typography.Text>
          {submitError !== null && (
            <Alert
              type="error"
              showIcon
              title={
                <span data-testid="expire-error" data-error-code={submitError.code}>
                  {submitError.message}
                </span>
              }
            />
          )}
          <Space>
            <Button
              type="primary"
              danger
              loading={mutation.isPending}
              onClick={() => {
                setSubmitError(null)
                mutation.mutate()
              }}
              data-testid="expire-submit"
            >
              确认作废
            </Button>
            <Button onClick={onDone} data-testid="expire-cancel">
              取消
            </Button>
          </Space>
        </Space>
      )}
    </Modal>
  )
}
