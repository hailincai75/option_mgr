import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, Radio, Space, Typography } from 'antd'
import { useState } from 'react'
import { createCloseTrade, createOptionTrade } from '../../api/optionTrades'
import type { OptionTrade, OptionType, TradeAction } from '../../api/types'
import { nowLocalDateTimeNY } from '../../utils/format'
import {
  normalizeDecimal,
  NOTE_MAX_LENGTH,
  validateContracts,
  validateExpirationDate,
  validateMultiplier,
  validateNetAmount,
  validateNote,
  validateStrike,
  validateSymbol,
  validateTradedAt,
} from './openTradeValidation'
import { TradeConfirmation } from './TradeConfirmation'
import {
  applySubmitError,
  isClosingAction,
  netAmountLabel,
  refreshAfterTradeSaved,
  toRule,
  type SubmitError,
} from './tradeFormSupport'

export interface TradeEntryFormValues {
  symbol: string
  expirationDate: string
  strike: string
  optionType?: OptionType
  action?: EntryAction
  contracts: string
  netAmount: string
  tradedAtLocal: string
  multiplier: string
  note: string
}

const FIELD_NAMES: readonly (keyof TradeEntryFormValues)[] = [
  'symbol',
  'expirationDate',
  'strike',
  'optionType',
  'action',
  'contracts',
  'netAmount',
  'tradedAtLocal',
  'multiplier',
  'note',
]

/** Directions recorded on this page (EXP 到期作废 / ASG 被指派 / EXR 行权 only come from the positions page). */
type EntryAction = Exclude<TradeAction, 'EXP' | 'ASG' | 'EXR'>

const ACTION_OPTIONS: { value: EntryAction; label: string }[] = [
  { value: 'STO', label: 'STO 卖出开仓' },
  { value: 'BTO', label: 'BTO 买入开仓' },
  { value: 'BTC', label: 'BTC 买入平仓' },
  { value: 'STC', label: 'STC 卖出平仓' },
]

/** Q61 wording (FEAT-038; replaced by FEAT-039 rule 7). */
const SHORT_CALL_HINT =
  '卖出 call 需要同一账户有足够的可用股票覆盖：每张 = multiplier 股，可用股数 = 持股 − 已被其它卖出 call 覆盖的股数，不足时不能保存。卖出 call 不冻结现金。'

function initialValues(): TradeEntryFormValues {
  return {
    symbol: '',
    expirationDate: '',
    strike: '',
    optionType: undefined,
    action: undefined,
    contracts: '',
    netAmount: '',
    tradedAtLocal: nowLocalDateTimeNY(),
    multiplier: '100',
    note: '',
  }
}

/**
 * Sends the trade to the endpoint for its action: opens (BTO / STO) to `POST /api/option-trades`
 * with multiplier, closes (BTC / STC) to `POST /api/option-trades/closes` without (FEAT-004).
 */
function submitTrade(values: TradeEntryFormValues): Promise<OptionTrade> {
  const { action, optionType } = values
  if (!action || !optionType) return Promise.reject(new Error('请选择方向和类型'))
  const common = {
    // Sent as typed; the backend trims and upper-cases it.
    symbol: values.symbol,
    expirationDate: values.expirationDate,
    strike: normalizeDecimal(values.strike),
    optionType,
    // Already validated as a positive integer within range, so Number() is exact.
    contracts: Number(values.contracts.trim()),
    netAmount: normalizeDecimal(values.netAmount),
    tradedAtLocal: values.tradedAtLocal,
    note: values.note.trim() === '' ? null : values.note,
  }
  if (isClosingAction(action)) return createCloseTrade({ ...common, action })
  return createOptionTrade({ ...common, action, multiplier: Number(values.multiplier.trim()) })
}

/**
 * 录入交易 form (FEAT-001 opens + FEAT-004 closes). Only basic format checks run here; every
 * business rule (collateral, cash, positions, whole lots, expiry) is the backend's.
 * On success: confirmation from the response, cash / trade list / positions refreshed, form cleared.
 * On failure: field errors under the fields, other errors (422 messages) shown verbatim, input kept.
 */
export function TradeEntryForm() {
  const [form] = Form.useForm<TradeEntryFormValues>()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [saved, setSaved] = useState<OptionTrade | null>(null)
  const action = Form.useWatch('action', form)
  const optionType = Form.useWatch('optionType', form)
  // FEAT-038 (Q61): selling a call freezes no cash; one line of guidance, no confirm, no block.
  const shortCall = action === 'STO' && optionType === 'CALL'
  const closing = isClosingAction(action)

  const mutation = useMutation({
    mutationFn: submitTrade,
    onSuccess: async (trade) => {
      setSaved(trade)
      form.resetFields()
      form.setFieldsValue(initialValues())
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

  const handleFinish = (values: TradeEntryFormValues) => {
    setSubmitError(null)
    setSaved(null)
    mutation.mutate(values)
  }

  const handleClear = () => {
    form.resetFields()
    form.setFieldsValue(initialValues())
    setSubmitError(null)
  }

  return (
    <Card title="录入交易（开仓 / 平仓）" data-testid="open-trade-card">
      {saved !== null && (
        <TradeConfirmation
          trade={saved}
          testIdPrefix="open-trade"
          onClose={() => {
            setSaved(null)
          }}
        />
      )}
      <Form<TradeEntryFormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues()}
        onFinish={handleFinish}
        data-testid="open-trade-form"
        style={{ maxWidth: 560 }}
      >
        <Form.Item
          label="方向"
          name="action"
          rules={[{ required: true, message: '请选择方向' }]}
          extra={
            closing ? '平仓须整笔平掉最早的一笔或几笔开仓（FIFO）；multiplier 取持仓的' : undefined
          }
        >
          <Radio.Group data-testid="open-trade-action">
            {ACTION_OPTIONS.map((option) => (
              <Radio
                key={option.value}
                value={option.value}
                data-testid={`open-trade-action-${option.value.toLowerCase()}`}
              >
                {option.label}
              </Radio>
            ))}
          </Radio.Group>
        </Form.Item>
        <Form.Item label="标的" name="symbol" required rules={[toRule(validateSymbol)]}>
          <Input
            placeholder="例如 AAPL、BRK.B"
            autoComplete="off"
            data-testid="open-trade-symbol"
          />
        </Form.Item>
        <Form.Item
          label="到期日"
          required
          name="expirationDate"
          dependencies={['tradedAtLocal', 'action']}
          rules={[
            ({ getFieldValue }) =>
              toRule((value) =>
                // Opens: expiry not before the trade date. Closes: the backend reports
                // "到期日之后不能平仓" under the trade time, so only the format is checked here.
                validateExpirationDate(
                  value,
                  isClosingAction(getFieldValue('action') as string | undefined)
                    ? ''
                    : getFieldValue('tradedAtLocal'),
                ),
              ),
          ]}
        >
          <Input type="date" data-testid="open-trade-expiration" />
        </Form.Item>
        <Form.Item label="行权价" name="strike" required rules={[toRule(validateStrike)]}>
          <Input
            inputMode="decimal"
            placeholder="例如 200 或 12.5"
            autoComplete="off"
            data-testid="open-trade-strike"
          />
        </Form.Item>
        <Form.Item
          label="类型"
          name="optionType"
          rules={[{ required: true, message: '请选择 Call 或 Put' }]}
        >
          <Radio.Group data-testid="open-trade-type">
            <Radio value="CALL" data-testid="open-trade-type-call">
              Call
            </Radio>
            <Radio value="PUT" data-testid="open-trade-type-put">
              Put
            </Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item label="张数" name="contracts" required rules={[toRule(validateContracts)]}>
          <Input
            inputMode="numeric"
            placeholder="例如 1"
            autoComplete="off"
            data-testid="open-trade-contracts"
          />
        </Form.Item>
        <Form.Item
          label={<span data-testid="open-trade-net-amount-label">{netAmountLabel(action)}</span>}
          name="netAmount"
          required
          rules={[toRule(validateNetAmount)]}
          extra={
            <>
              <div>只填正数；方向决定现金流入还是流出</div>
              {shortCall && (
                <Typography.Text type="warning" data-testid="open-trade-short-call-hint">
                  {SHORT_CALL_HINT}
                </Typography.Text>
              )}
            </>
          }
        >
          <Input
            inputMode="decimal"
            placeholder="例如 349.35"
            autoComplete="off"
            data-testid="open-trade-net-amount"
          />
        </Form.Item>
        <Form.Item
          label="成交时间（美东时间）"
          required
          name="tradedAtLocal"
          rules={[toRule((value) => validateTradedAt(value))]}
          extra="按美东时间（America/New_York）填写，默认当前美东时间"
        >
          <Input type="datetime-local" data-testid="open-trade-time" />
        </Form.Item>
        {!closing && (
          <Form.Item
            label="Multiplier"
            name="multiplier"
            required
            rules={[toRule(validateMultiplier)]}
          >
            <Input inputMode="numeric" autoComplete="off" data-testid="open-trade-multiplier" />
          </Form.Item>
        )}
        <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
          <Input.TextArea
            rows={2}
            showCount={{
              formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
            }}
            data-testid="open-trade-note"
          />
        </Form.Item>
        {submitError !== null && (
          <Form.Item>
            <Alert
              type="error"
              showIcon
              title={
                <span data-testid="open-trade-error" data-error-code={submitError.code}>
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
              data-testid="open-trade-submit"
            >
              保存
            </Button>
            <Button onClick={handleClear} data-testid="open-trade-clear">
              清空
            </Button>
          </Space>
        </Form.Item>
      </Form>
    </Card>
  )
}
