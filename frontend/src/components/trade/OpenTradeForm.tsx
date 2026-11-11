import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, Radio, Space } from 'antd'
import type { RuleObject } from 'antd/es/form'
import { useState } from 'react'
import { cashQueryKeys } from '../../api/cash'
import { ApiError } from '../../api/client'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { createOptionTrade, optionTradeQueryKeys } from '../../api/optionTrades'
import type {
  CreateOptionTradeRequest,
  OpeningAction,
  OptionTrade,
  OptionType,
} from '../../api/types'
import { nowLocalDateTimeNY } from '../../utils/format'
import { OpenTradeConfirmation } from './OpenTradeConfirmation'
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

export interface OpenTradeFormValues {
  symbol: string
  expirationDate: string
  strike: string
  optionType?: OptionType
  action?: OpeningAction
  contracts: string
  netAmount: string
  tradedAtLocal: string
  multiplier: string
  note: string
}

type FieldName = keyof OpenTradeFormValues
const FIELD_NAMES: readonly FieldName[] = [
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

function isFieldName(field: string): field is FieldName {
  return (FIELD_NAMES as readonly string[]).includes(field)
}

function toRule(validate: (value: unknown) => string | null): RuleObject {
  return {
    validator: (_rule, value: unknown) => {
      const error = validate(value)
      return error === null ? Promise.resolve() : Promise.reject(new Error(error))
    },
  }
}

function initialValues(): OpenTradeFormValues {
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

function toRequest(values: OpenTradeFormValues): CreateOptionTradeRequest | null {
  if (!values.optionType || !values.action) return null
  return {
    // Sent as typed; the backend trims and upper-cases it.
    symbol: values.symbol,
    expirationDate: values.expirationDate,
    strike: normalizeDecimal(values.strike),
    optionType: values.optionType,
    action: values.action,
    // Already validated as a positive integer within range, so Number() is exact.
    contracts: Number(values.contracts.trim()),
    netAmount: normalizeDecimal(values.netAmount),
    tradedAtLocal: values.tradedAtLocal,
    multiplier: Number(values.multiplier.trim()),
    note: values.note.trim() === '' ? null : values.note,
  }
}

/** Net-amount label depends on the action (requirement: STO = received, BTO = paid). */
function netAmountLabel(action: OpeningAction | undefined): string {
  if (action === 'STO') return '实收净额（已扣手续费）'
  if (action === 'BTO') return '实付总额（含手续费）'
  return '净额（含手续费）'
}

/**
 * 录入开仓 form (`POST /api/option-trades`, FEAT-001). Only basic format checks run here; every
 * business rule (collateral, cash, position direction, multiplier) is the backend's.
 * On success: confirmation from the response, cash queries invalidated, form cleared.
 * On failure: field errors under the fields, other errors (422 messages) shown verbatim, input kept.
 */
export function OpenTradeForm() {
  const [form] = Form.useForm<OpenTradeFormValues>()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<{ code: string; message: string } | null>(null)
  const [saved, setSaved] = useState<OptionTrade | null>(null)
  const action = Form.useWatch('action', form)

  const mutation = useMutation({
    mutationFn: createOptionTrade,
    onSuccess: async (trade) => {
      setSaved(trade)
      form.resetFields()
      form.setFieldsValue(initialValues())
      queryClient.setQueryData(optionTradeQueryKeys.detail(trade.id), trade)
      // Cash summary/ledger, trade lists and positions must show the new trade without reload.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: cashQueryKeys.all }),
        queryClient.invalidateQueries({ queryKey: optionTradeQueryKeys.lists }),
        queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all }),
      ])
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) {
        setSubmitError({ code: 'UNKNOWN', message: error.message })
        return
      }
      const grouped = new Map<FieldName, string[]>()
      const unmapped: string[] = []
      for (const detail of error.details) {
        if (isFieldName(detail.field)) {
          grouped.set(detail.field, [...(grouped.get(detail.field) ?? []), detail.message])
        } else {
          unmapped.push(`${detail.field}: ${detail.message}`)
        }
      }
      form.setFields([...grouped.entries()].map(([name, errors]) => ({ name, errors })))
      if (grouped.size === 0 || unmapped.length > 0) {
        setSubmitError({
          code: error.code,
          message:
            unmapped.length > 0 ? `${error.message}（${unmapped.join('；')}）` : error.message,
        })
      }
    },
  })

  const handleFinish = (values: OpenTradeFormValues) => {
    const request = toRequest(values)
    if (!request) return
    setSubmitError(null)
    setSaved(null)
    mutation.mutate(request)
  }

  const handleClear = () => {
    form.resetFields()
    form.setFieldsValue(initialValues())
    setSubmitError(null)
  }

  return (
    <Card title="录入开仓交易（BTO / STO）" data-testid="open-trade-card">
      {saved !== null && (
        <OpenTradeConfirmation
          trade={saved}
          onClose={() => {
            setSaved(null)
          }}
        />
      )}
      <Form<OpenTradeFormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues()}
        onFinish={handleFinish}
        data-testid="open-trade-form"
        style={{ maxWidth: 560 }}
      >
        <Form.Item label="方向" name="action" rules={[{ required: true, message: '请选择方向' }]}>
          <Radio.Group data-testid="open-trade-action">
            <Radio value="STO" data-testid="open-trade-action-sto">
              STO 卖出开仓
            </Radio>
            <Radio value="BTO" data-testid="open-trade-action-bto">
              BTO 买入开仓
            </Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item label="标的" name="symbol" required rules={[toRule(validateSymbol)]}>
          <Input placeholder="例如 AAPL、BRK.B" autoComplete="off" data-testid="open-trade-symbol" />
        </Form.Item>
        <Form.Item
          label="到期日"
          required
          name="expirationDate"
          dependencies={['tradedAtLocal']}
          rules={[
            ({ getFieldValue }) =>
              toRule((value) => validateExpirationDate(value, getFieldValue('tradedAtLocal'))),
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
          extra="只填正数；方向决定现金流入还是流出"
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
        <Form.Item label="Multiplier" name="multiplier" required rules={[toRule(validateMultiplier)]}>
          <Input inputMode="numeric" autoComplete="off" data-testid="open-trade-multiplier" />
        </Form.Item>
        <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
          <Input.TextArea
            rows={2}
            showCount={{ formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}` }}
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
