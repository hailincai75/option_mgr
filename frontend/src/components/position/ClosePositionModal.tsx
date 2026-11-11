import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Form, Input, Modal, Radio, Space } from 'antd'
import { useState } from 'react'
import { createCloseTrade } from '../../api/optionTrades'
import type { OptionPosition, OptionTrade } from '../../api/types'
import { nowLocalDateTimeNY } from '../../utils/format'
import { CashSummaryCard } from '../cash/CashSummaryCard'
import {
  normalizeDecimal,
  NOTE_MAX_LENGTH,
  validateNetAmount,
  validateNote,
  validateTradedAt,
} from '../trade/openTradeValidation'
import { TradeConfirmation } from '../trade/TradeConfirmation'
import {
  applySubmitError,
  netAmountLabel,
  refreshAfterTradeSaved,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import { tradeActionLabel } from '../trade/tradeLabels'

interface CloseFormValues {
  contracts?: number
  netAmount: string
  tradedAtLocal: string
  note: string
}

const FIELD_NAMES: readonly (keyof CloseFormValues)[] = [
  'contracts',
  'netAmount',
  'tradedAtLocal',
  'note',
]

interface ClosePositionModalProps {
  /** Snapshot of the position when 平仓 was clicked (contract, direction, allowed counts). */
  position: OptionPosition
  onDone: () => void
}

/**
 * 平仓 dialog opened from a position row (FEAT-004 entry 1). Contract, direction and multiplier
 * are fixed from the position; contracts can only be one of the backend's `allowedCloseContracts`
 * (whole earliest lots, FIFO). Submits to `POST /api/option-trades/closes`.
 */
export function ClosePositionModal({ position, onDone }: ClosePositionModalProps) {
  const [form] = Form.useForm<CloseFormValues>()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [saved, setSaved] = useState<OptionTrade | null>(null)

  const mutation = useMutation({
    mutationFn: (values: CloseFormValues) =>
      createCloseTrade({
        symbol: position.symbol,
        expirationDate: position.expirationDate,
        strike: position.strike,
        optionType: position.optionType,
        action: position.closeAction,
        contracts: values.contracts ?? 0,
        netAmount: normalizeDecimal(values.netAmount),
        tradedAtLocal: values.tradedAtLocal,
        note: values.note.trim() === '' ? null : values.note,
      }),
    onSuccess: async (trade) => {
      setSaved(trade)
      await refreshAfterTradeSaved(queryClient, trade)
    },
    onError: (error) => {
      setSubmitError(
        applySubmitError(FIELD_NAMES, error, (fields) => {
          form.setFields(fields)
        }),
      )
    },
  })

  return (
    <Modal
      open
      title={`平仓 ${position.contractLabel}`}
      onCancel={onDone}
      footer={null}
      width={640}
      mask={{ closable: false }}
      data-testid="close-trade-modal"
    >
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <CashSummaryCard size="small" />
        {saved !== null ? (
          <>
            <TradeConfirmation trade={saved} testIdPrefix="close-trade" onClose={onDone} />
            <Button type="primary" onClick={onDone} data-testid="close-trade-done">
              完成
            </Button>
          </>
        ) : (
          <>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="合约">
                <span data-testid="close-trade-contract">{position.contractLabel}</span>
              </Descriptions.Item>
              <Descriptions.Item label="方向">
                <span data-testid="close-trade-action">
                  {tradeActionLabel(position.closeAction)}
                </span>
              </Descriptions.Item>
              <Descriptions.Item label="Multiplier">
                <span data-testid="close-trade-multiplier">{position.multiplier}</span>
              </Descriptions.Item>
            </Descriptions>
            <Form<CloseFormValues>
              form={form}
              layout="vertical"
              initialValues={{
                contracts: position.allowedCloseContracts[0],
                netAmount: '',
                tradedAtLocal: nowLocalDateTimeNY(),
                note: '',
              }}
              onFinish={(values) => {
                setSubmitError(null)
                mutation.mutate(values)
              }}
              data-testid="close-trade-form"
            >
              <Form.Item
                label="张数"
                name="contracts"
                rules={[{ required: true, message: '请选择张数' }]}
                extra="必须整笔平掉开仓批次（FIFO，从最早的一笔起）"
              >
                <Radio.Group data-testid="close-trade-contracts">
                  {position.allowedCloseContracts.map((count) => (
                    <Radio
                      key={count}
                      value={count}
                      data-testid={`close-trade-contracts-${String(count)}`}
                    >
                      {count} 张
                    </Radio>
                  ))}
                </Radio.Group>
              </Form.Item>
              <Form.Item
                label={
                  <span data-testid="close-trade-net-amount-label">
                    {netAmountLabel(position.closeAction)}
                  </span>
                }
                name="netAmount"
                required
                rules={[toRule(validateNetAmount)]}
              >
                <Input
                  inputMode="decimal"
                  placeholder="例如 241.30"
                  autoComplete="off"
                  data-testid="close-trade-net-amount"
                />
              </Form.Item>
              <Form.Item
                label="成交时间（美东时间）"
                name="tradedAtLocal"
                required
                rules={[toRule((value) => validateTradedAt(value))]}
                extra="按美东时间填写，默认当前美东时间；可改为过去的时间补录"
              >
                <Input type="datetime-local" data-testid="close-trade-time" />
              </Form.Item>
              <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
                <Input.TextArea
                  rows={2}
                  showCount={{
                    formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
                  }}
                  data-testid="close-trade-note"
                />
              </Form.Item>
              {submitError !== null && (
                <Form.Item>
                  <Alert
                    type="error"
                    showIcon
                    title={
                      <span data-testid="close-trade-error" data-error-code={submitError.code}>
                        {submitError.message}
                      </span>
                    }
                  />
                </Form.Item>
              )}
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={mutation.isPending}
                  data-testid="close-trade-submit"
                >
                  保存平仓
                </Button>
                <Button onClick={onDone} data-testid="close-trade-cancel">
                  取消
                </Button>
              </Space>
            </Form>
          </>
        )}
      </Space>
    </Modal>
  )
}
