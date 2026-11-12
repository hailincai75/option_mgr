import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Descriptions, Form, Input, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import { updateOptionTrade } from '../../api/optionTrades'
import type { OptionTrade } from '../../api/types'
import {
  normalizeDecimal,
  NOTE_MAX_LENGTH,
  validateContracts,
  validateMultiplier,
  validateNetAmount,
  validateNote,
  validateTradedAt,
} from './openTradeValidation'
import {
  applySubmitError,
  isClosingAction,
  netAmountLabel,
  refreshAfterChange,
  toRule,
  type SubmitError,
} from './tradeFormSupport'
import { tradeActionLabel } from './tradeLabels'

interface EditTradeValues {
  contracts: string
  netAmount: string
  tradedAtLocal: string
  multiplier: string
  note: string
}

const FIELD_NAMES: readonly (keyof EditTradeValues)[] = [
  'contracts',
  'netAmount',
  'tradedAtLocal',
  'multiplier',
  'note',
]

/** "2026-11-04T11:00:00" → "2026-11-04T11:00" (seconds kept when not zero). */
function toInputValue(local: string): string {
  return local.length === 19 && local.endsWith(':00') ? local.slice(0, 16) : local
}

interface EditTradeModalProps {
  trade: OptionTrade
  onDone: () => void
}

/**
 * 修改交易 dialog (FEAT-005, Q43). Editable: contracts, net amount, trade time, note and — for
 * opens only — multiplier. Contract and direction are read-only (delete and re-enter instead).
 * `PUT /api/option-trades/{id}` replaces all editable fields, so every field is always sent.
 */
export function EditTradeModal({ trade, onDone }: EditTradeModalProps) {
  const [form] = Form.useForm<EditTradeValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const closing = isClosingAction(trade.action)

  const mutation = useMutation({
    mutationFn: (values: EditTradeValues) =>
      updateOptionTrade(trade.id, {
        contracts: Number(values.contracts.trim()),
        netAmount: normalizeDecimal(values.netAmount),
        tradedAtLocal: values.tradedAtLocal,
        ...(closing ? {} : { multiplier: Number(values.multiplier.trim()) }),
        note: values.note.trim() === '' ? null : values.note,
      }),
    onSuccess: async () => {
      void message.success('交易已修改')
      await refreshAfterChange(queryClient)
      onDone()
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
      title={`修改交易 #${String(trade.id)}`}
      onCancel={onDone}
      footer={null}
      width={600}
      mask={{ closable: false }}
      data-testid="trade-edit-modal"
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="合约">
            <span data-testid="trade-edit-contract">{trade.contractLabel}</span>
          </Descriptions.Item>
          <Descriptions.Item label="方向">
            <span data-testid="trade-edit-action">{tradeActionLabel(trade.action)}</span>
          </Descriptions.Item>
          {closing && (
            <Descriptions.Item label="Multiplier">
              <span data-testid="trade-edit-multiplier-readonly">{trade.multiplier}</span>
            </Descriptions.Item>
          )}
        </Descriptions>
        <Typography.Text type="secondary">合约或方向录错请删除后重新录入。</Typography.Text>
        <Form<EditTradeValues>
          form={form}
          layout="vertical"
          initialValues={{
            contracts: String(trade.contracts),
            netAmount: trade.netAmount,
            tradedAtLocal: toInputValue(trade.tradedAtLocal),
            multiplier: String(trade.multiplier),
            note: trade.note ?? '',
          }}
          onFinish={(values) => {
            setSubmitError(null)
            mutation.mutate(values)
          }}
          data-testid="trade-edit-form"
        >
          <Form.Item label="张数" name="contracts" required rules={[toRule(validateContracts)]}>
            <Input inputMode="numeric" autoComplete="off" data-testid="trade-edit-contracts" />
          </Form.Item>
          <Form.Item
            label={
              <span data-testid="trade-edit-net-amount-label">{netAmountLabel(trade.action)}</span>
            }
            name="netAmount"
            required
            rules={[toRule(validateNetAmount)]}
          >
            <Input inputMode="decimal" autoComplete="off" data-testid="trade-edit-net-amount" />
          </Form.Item>
          <Form.Item
            label="成交时间（美东时间）"
            name="tradedAtLocal"
            required
            rules={[toRule((value) => validateTradedAt(value))]}
          >
            <Input type="datetime-local" step={1} data-testid="trade-edit-time" />
          </Form.Item>
          {!closing && (
            <Form.Item
              label="Multiplier"
              name="multiplier"
              required
              rules={[toRule(validateMultiplier)]}
            >
              <Input inputMode="numeric" autoComplete="off" data-testid="trade-edit-multiplier" />
            </Form.Item>
          )}
          <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
            <Input.TextArea
              rows={2}
              showCount={{
                formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
              }}
              data-testid="trade-edit-note"
            />
          </Form.Item>
          {submitError !== null && (
            <Form.Item>
              <Alert
                type="error"
                showIcon
                title={
                  <span data-testid="trade-edit-error" data-error-code={submitError.code}>
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
              data-testid="trade-edit-submit"
            >
              保存修改
            </Button>
            <Button onClick={onDone} data-testid="trade-edit-cancel">
              取消
            </Button>
          </Space>
        </Form>
      </Space>
    </Modal>
  )
}
