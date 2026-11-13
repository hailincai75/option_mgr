import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Descriptions, Form, Input, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import { updateStockTrade } from '../../api/stockTrades'
import type { StockTrade } from '../../api/types'
import { toLocalDateTimeInputNY } from '../../utils/format'
import { NOTE_MAX_LENGTH, validateNote, validateTradedAt } from '../trade/openTradeValidation'
import {
  applySubmitError,
  refreshAfterChange,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import { stockAmountLabel, stockTypeLabel, TRANSFER_IN_DATE_HINT } from './stockLabels'
import {
  normalizeStockAmount,
  validateShares,
  validateStockAmount,
  validateTradeDate,
} from './stockValidation'

interface EditStockTradeValues {
  shares: string
  amount: string
  tradedAtLocal: string
  tradeDate: string
  note: string
}

const FIELD_NAMES: readonly (keyof EditStockTradeValues)[] = [
  'shares',
  'amount',
  'tradedAtLocal',
  'tradeDate',
  'note',
]

interface EditStockTradeModalProps {
  trade: StockTrade
  onDone: () => void
}

/**
 * 修改股票交易 dialog (FEAT-011). Editable: shares, amount, time (date for transfers) and note;
 * symbol and type are read-only (delete and re-enter instead). `PUT /api/stock-trades/{id}`
 * replaces every editable field, so all of them are always sent; symbol / type are never sent.
 * Rejections (incl. 没有修改) are shown verbatim, the dialog stays open with the input kept.
 */
export function EditStockTradeModal({ trade, onDone }: EditStockTradeModalProps) {
  const [form] = Form.useForm<EditStockTradeValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const isTransfer = trade.type === 'TRANSFER_IN'

  const mutation = useMutation({
    mutationFn: (values: EditStockTradeValues) =>
      updateStockTrade(trade.id, {
        shares: Number(values.shares.trim()),
        amount: normalizeStockAmount(values.amount),
        ...(isTransfer
          ? { tradeDate: values.tradeDate }
          : { tradedAtLocal: values.tradedAtLocal }),
        note: values.note.trim() === '' ? null : values.note,
      }),
    onSuccess: async () => {
      void message.success('股票交易已修改')
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
      title={`修改股票交易 #${String(trade.id)}`}
      onCancel={onDone}
      footer={null}
      width={600}
      mask={{ closable: false }}
      data-testid="stock-edit-modal"
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="类型">
            <span data-testid="stock-edit-type">{stockTypeLabel(trade.type)}</span>
          </Descriptions.Item>
          <Descriptions.Item label="标的">
            <span data-testid="stock-edit-symbol">{trade.symbol}</span>
          </Descriptions.Item>
        </Descriptions>
        <Typography.Text type="secondary" data-testid="stock-edit-identity-hint">
          标的或类型录错请删除后重新录入
        </Typography.Text>
        <Form<EditStockTradeValues>
          form={form}
          layout="vertical"
          initialValues={{
            shares: String(trade.shares),
            amount: trade.amount,
            // Seconds kept when not zero, so an unchanged time is submitted unchanged.
            tradedAtLocal: toLocalDateTimeInputNY(trade.tradedAt),
            tradeDate: trade.tradeDate ?? '',
            note: trade.note ?? '',
          }}
          onFinish={(values) => {
            setSubmitError(null)
            mutation.mutate(values)
          }}
          data-testid="stock-edit-form"
        >
          <Form.Item label="股数" name="shares" required rules={[toRule(validateShares)]}>
            <Input inputMode="numeric" autoComplete="off" data-testid="stock-edit-shares" />
          </Form.Item>
          <Form.Item
            label={
              <span data-testid="stock-edit-amount-label">{stockAmountLabel(trade.type)}</span>
            }
            name="amount"
            required
            rules={[toRule(validateStockAmount)]}
          >
            <Input inputMode="decimal" autoComplete="off" data-testid="stock-edit-amount" />
          </Form.Item>
          {isTransfer ? (
            <Form.Item
              label="日期（美东）"
              name="tradeDate"
              required
              rules={[toRule((value) => validateTradeDate(value))]}
              extra={TRANSFER_IN_DATE_HINT}
            >
              <Input type="date" data-testid="stock-edit-date" />
            </Form.Item>
          ) : (
            <Form.Item
              label="成交时间（美东时间）"
              name="tradedAtLocal"
              required
              rules={[toRule((value) => validateTradedAt(value))]}
            >
              <Input type="datetime-local" step={1} data-testid="stock-edit-time" />
            </Form.Item>
          )}
          <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
            <Input.TextArea
              rows={2}
              showCount={{
                formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
              }}
              data-testid="stock-edit-note"
            />
          </Form.Item>
          {submitError !== null && (
            <Form.Item>
              <Alert
                type="error"
                showIcon
                title={
                  <span data-testid="stock-edit-error" data-error-code={submitError.code}>
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
              data-testid="stock-edit-submit"
            >
              保存修改
            </Button>
            <Button onClick={onDone} data-testid="stock-edit-cancel">
              取消
            </Button>
          </Space>
        </Form>
      </Space>
    </Modal>
  )
}
