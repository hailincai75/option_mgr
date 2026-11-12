import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Card, Form, Input, Radio } from 'antd'
import type { Rule } from 'antd/es/form'
import { useState } from 'react'
import { createCashTransfer } from '../../api/cash'
import { refreshAfterChange } from '../trade/tradeFormSupport'
import { ApiError } from '../../api/client'
import type { CashTransferType, CreateCashTransferRequest } from '../../api/types'
import { nowLocalDateTimeNY } from '../../utils/format'
import {
  normalizeAmount,
  REMARK_MAX_LENGTH,
  validateAmount,
  validateOccurredAt,
  validateRemark,
} from './cashTransferValidation'

export interface CashTransferFormValues {
  type?: CashTransferType
  amount: string
  occurredAtLocal: string
  remark: string
}

type FieldName = keyof CashTransferFormValues
const FIELD_NAMES: readonly FieldName[] = ['type', 'amount', 'occurredAtLocal', 'remark']

function toRule(validate: (value: unknown) => string | null): Rule {
  return {
    validator: (_rule, value: unknown) => {
      const error = validate(value)
      return error === null ? Promise.resolve() : Promise.reject(new Error(error))
    },
  }
}

function initialValues(): CashTransferFormValues {
  return { type: undefined, amount: '', occurredAtLocal: nowLocalDateTimeNY(), remark: '' }
}

function toRequest(values: CashTransferFormValues): CreateCashTransferRequest | null {
  if (!values.type) return null
  return {
    type: values.type,
    amount: normalizeAmount(values.amount),
    occurredAtLocal: values.occurredAtLocal,
    remark: values.remark.trim() === '' ? null : values.remark,
  }
}

function isFieldName(field: string): field is FieldName {
  return (FIELD_NAMES as readonly string[]).includes(field)
}

interface CashTransferFormProps {
  /** Called after a successful save (e.g. to jump the ledger back to page 1). */
  onSaved?: () => void
}

/**
 * 入金 / 出金 form (`POST /api/cash/transfers`). Time is entered as New York local time and sent
 * as-is (`occurredAtLocal`); the backend converts it to UTC. On success all cash queries are
 * invalidated so the summary and ledger refresh. On failure the input is kept: field errors from
 * the backend go under the matching field, other errors (e.g. CASH_INSUFFICIENT) are shown as-is.
 */
export function CashTransferForm({ onSaved }: CashTransferFormProps) {
  const [form] = Form.useForm<CashTransferFormValues>()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: createCashTransfer,
    onSuccess: async (transfer) => {
      void message.success(transfer.type === 'DEPOSIT' ? '入金已保存' : '出金已保存')
      form.setFieldsValue(initialValues())
      onSaved?.()
      // Cash changes also change the home dashboard (FEAT-008); refresh everything derived from it.
      await refreshAfterChange(queryClient)
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) {
        setSubmitError(error.message)
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
        setSubmitError(
          unmapped.length > 0 ? `${error.message}（${unmapped.join('；')}）` : error.message,
        )
      }
    },
  })

  const handleFinish = (values: CashTransferFormValues) => {
    const request = toRequest(values)
    if (!request) return
    setSubmitError(null)
    mutation.mutate(request)
  }

  return (
    <Card title="入金 / 出金" data-testid="cash-transfer-card">
      <Form<CashTransferFormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues()}
        onFinish={handleFinish}
        data-testid="cash-transfer-form"
        style={{ maxWidth: 480 }}
      >
        <Form.Item label="类型" name="type" rules={[{ required: true, message: '请选择类型' }]}>
          <Radio.Group data-testid="cash-transfer-type">
            <Radio value="DEPOSIT" data-testid="cash-transfer-type-deposit">
              入金
            </Radio>
            <Radio value="WITHDRAWAL" data-testid="cash-transfer-type-withdrawal">
              出金
            </Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item label="金额（USD）" name="amount" rules={[toRule(validateAmount)]}>
          <Input
            inputMode="decimal"
            placeholder="例如 30000.00"
            autoComplete="off"
            data-testid="cash-transfer-amount"
          />
        </Form.Item>
        <Form.Item
          label="时间（美东时间）"
          name="occurredAtLocal"
          rules={[toRule((value) => validateOccurredAt(value))]}
          extra="按美东时间（America/New_York）填写，默认当前美东时间"
        >
          <Input type="datetime-local" data-testid="cash-transfer-time" />
        </Form.Item>
        <Form.Item label="备注" name="remark" rules={[toRule(validateRemark)]}>
          <Input.TextArea
            rows={2}
            showCount={{ formatter: ({ count }) => `${count} / ${REMARK_MAX_LENGTH}` }}
            data-testid="cash-transfer-remark"
          />
        </Form.Item>
        {submitError !== null && (
          <Form.Item>
            <Alert
              type="error"
              showIcon
              title={<span data-testid="cash-transfer-error">{submitError}</span>}
            />
          </Form.Item>
        )}
        <Form.Item style={{ marginBottom: 0 }}>
          <Button
            type="primary"
            htmlType="submit"
            loading={mutation.isPending}
            data-testid="cash-transfer-submit"
          >
            保存
          </Button>
        </Form.Item>
      </Form>
    </Card>
  )
}
