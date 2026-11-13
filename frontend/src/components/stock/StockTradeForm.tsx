import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, Radio, Space, Typography } from 'antd'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { createStockTrade, stockTradeQueryKeys } from '../../api/stockTrades'
import type { CreateStockTradeRequest, StockTrade, StockTradeType } from '../../api/types'
import { nowLocalDateTimeNY } from '../../utils/format'
import {
  NOTE_MAX_LENGTH,
  validateNote,
  validateSymbol,
  validateTradedAt,
} from '../trade/openTradeValidation'
import {
  applySubmitError,
  refreshAfterChange,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import { stockAmountLabel, TRANSFER_IN_DATE_HINT, TRANSFER_IN_HINT } from './stockLabels'
import { StockTradeConfirmation } from './StockTradeConfirmation'
import {
  normalizeStockAmount,
  todayNY,
  validateShares,
  validateStockAmount,
  validateTradeDate,
} from './stockValidation'

export interface StockTradeFormValues {
  type?: StockTradeType
  symbol: string
  shares: string
  amount: string
  tradedAtLocal: string
  tradeDate: string
  note: string
}

const FIELD_NAMES: readonly (keyof StockTradeFormValues)[] = [
  'type',
  'symbol',
  'shares',
  'amount',
  'tradedAtLocal',
  'tradeDate',
  'note',
]

const TYPE_OPTIONS: { value: StockTradeType; label: string }[] = [
  { value: 'BUY', label: '买入' },
  { value: 'SELL', label: '卖出' },
  { value: 'TRANSFER_IN', label: '转入（期初持仓）' },
]

function blankValues(prefill: { type?: StockTradeType; symbol?: string } = {}): StockTradeFormValues {
  return {
    type: prefill.type,
    symbol: prefill.symbol ?? '',
    shares: '',
    amount: '',
    tradedAtLocal: nowLocalDateTimeNY(),
    tradeDate: todayNY(),
    note: '',
  }
}

function toRequest(values: StockTradeFormValues): CreateStockTradeRequest | null {
  const { type } = values
  if (type === undefined) return null
  return {
    type,
    // Sent as typed; the backend trims and upper-cases it.
    symbol: values.symbol,
    // Already validated as an integer within range, so Number() is exact.
    shares: Number(values.shares.trim()),
    amount: normalizeStockAmount(values.amount),
    ...(type === 'TRANSFER_IN'
      ? { tradeDate: values.tradeDate }
      : { tradedAtLocal: values.tradedAtLocal }),
    note: values.note.trim() === '' ? null : values.note,
  }
}

interface StockTradeFormProps {
  /** Prefill from the positions page "卖出" button (type SELL + symbol). */
  initialType?: StockTradeType
  initialSymbol?: string
}

/**
 * 录入股票交易 (FEAT-011): 买入 / 卖出 / 转入. Only format checks run here; shares held, cash and
 * FIFO are the backend's. On success the backend result is shown and the form is cleared; on
 * failure field errors go under their fields and other errors (422) are shown verbatim, input kept.
 */
export function StockTradeForm({ initialType, initialSymbol }: StockTradeFormProps) {
  const [form] = Form.useForm<StockTradeFormValues>()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [saved, setSaved] = useState<StockTrade | null>(null)
  const type = Form.useWatch('type', form)
  const isTransfer = type === 'TRANSFER_IN'

  const mutation = useMutation({
    mutationFn: (values: StockTradeFormValues) => {
      const request = toRequest(values)
      return request === null
        ? Promise.reject(new Error('请选择类型'))
        : createStockTrade(request)
    },
    onSuccess: async (trade) => {
      setSaved(trade)
      form.resetFields()
      form.setFieldsValue(blankValues())
      await refreshAfterChange(queryClient)
      queryClient.setQueryData(stockTradeQueryKeys.detail(trade.id), trade)
    },
    onError: (error) => {
      setSubmitError(
        applySubmitError(FIELD_NAMES, error, (fields) => {
          form.setFields(fields)
        }),
      )
    },
  })

  const handleClear = () => {
    form.resetFields()
    form.setFieldsValue(blankValues())
    setSubmitError(null)
  }

  return (
    <Card
      title="录入股票交易"
      extra={
        <Link to="/stocks" data-testid="stock-entry-list-link">
          股票交易列表
        </Link>
      }
      data-testid="stock-entry-card"
    >
      {saved !== null && (
        <StockTradeConfirmation
          trade={saved}
          onClose={() => {
            setSaved(null)
          }}
        />
      )}
      <Form<StockTradeFormValues>
        form={form}
        layout="vertical"
        initialValues={blankValues({ type: initialType, symbol: initialSymbol })}
        onFinish={(values) => {
          setSubmitError(null)
          setSaved(null)
          mutation.mutate(values)
        }}
        data-testid="stock-entry-form"
        style={{ maxWidth: 560 }}
      >
        <Form.Item
          label="类型"
          name="type"
          rules={[{ required: true, message: '请选择类型' }]}
          extra={
            isTransfer ? (
              <Typography.Text type="secondary" data-testid="stock-entry-transfer-hint">
                {TRANSFER_IN_HINT}
              </Typography.Text>
            ) : undefined
          }
        >
          <Radio.Group data-testid="stock-entry-type">
            {TYPE_OPTIONS.map((option) => (
              <Radio
                key={option.value}
                value={option.value}
                data-testid={`stock-entry-type-${option.value.toLowerCase().replace('_', '-')}`}
              >
                {option.label}
              </Radio>
            ))}
          </Radio.Group>
        </Form.Item>
        <Form.Item label="标的" name="symbol" required rules={[toRule(validateSymbol)]}>
          <Input placeholder="例如 IXQZ、BRK.B" autoComplete="off" data-testid="stock-entry-symbol" />
        </Form.Item>
        <Form.Item label="股数" name="shares" required rules={[toRule(validateShares)]}>
          <Input
            inputMode="numeric"
            placeholder="例如 100"
            autoComplete="off"
            data-testid="stock-entry-shares"
          />
        </Form.Item>
        <Form.Item
          label={<span data-testid="stock-entry-amount-label">{stockAmountLabel(type)}</span>}
          name="amount"
          required
          rules={[toRule(validateStockAmount)]}
          extra={isTransfer ? '只填正数' : '只填正数；类型决定现金流入还是流出'}
        >
          <Input
            inputMode="decimal"
            placeholder="例如 1500.65"
            autoComplete="off"
            data-testid="stock-entry-amount"
          />
        </Form.Item>
        {isTransfer ? (
          <Form.Item
            label="日期（美东）"
            name="tradeDate"
            required
            rules={[toRule((value) => validateTradeDate(value))]}
            extra={<span data-testid="stock-entry-date-hint">{TRANSFER_IN_DATE_HINT}</span>}
          >
            <Input type="date" data-testid="stock-entry-date" />
          </Form.Item>
        ) : (
          <Form.Item
            label="成交时间（美东时间）"
            name="tradedAtLocal"
            required
            rules={[toRule((value) => validateTradedAt(value))]}
            extra="按美东时间（America/New_York）填写，默认当前美东时间"
          >
            <Input type="datetime-local" data-testid="stock-entry-time" />
          </Form.Item>
        )}
        <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
          <Input.TextArea
            rows={2}
            showCount={{
              formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
            }}
            data-testid="stock-entry-note"
          />
        </Form.Item>
        {submitError !== null && (
          <Form.Item>
            <Alert
              type="error"
              showIcon
              title={
                <span data-testid="stock-entry-error" data-error-code={submitError.code}>
                  {submitError.message}
                </span>
              }
            />
          </Form.Item>
        )}
        <Form.Item style={{ marginBottom: 0 }}>
          <Space>
            <Button
              type="primary"
              htmlType="submit"
              loading={mutation.isPending}
              data-testid="stock-entry-submit"
            >
              保存
            </Button>
            <Button onClick={handleClear} data-testid="stock-entry-clear">
              清空
            </Button>
          </Space>
        </Form.Item>
      </Form>
    </Card>
  )
}
