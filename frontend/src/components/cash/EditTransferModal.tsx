import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Form, Input, Modal, Radio, Space } from 'antd'
import { useState } from 'react'
import { updateCashTransfer } from '../../api/cash'
import type { CashLedgerEntry, CashTransferType } from '../../api/types'
import { toLocalDateTimeInputNY } from '../../utils/format'
import {
  applySubmitError,
  refreshAfterChange,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import {
  normalizeAmount,
  REMARK_MAX_LENGTH,
  validateAmount,
  validateOccurredAt,
  validateRemark,
} from './cashTransferValidation'

interface EditTransferValues {
  type: CashTransferType
  amount: string
  occurredAtLocal: string
  remark: string
}

const FIELD_NAMES: readonly (keyof EditTransferValues)[] = [
  'type',
  'amount',
  'occurredAtLocal',
  'remark',
]

interface EditTransferModalProps {
  /** A DEPOSIT / WITHDRAWAL ledger entry; `sourceId` is the transfer id. */
  entry: CashLedgerEntry
  onDone: () => void
}

/**
 * 修改入金/出金 dialog (FEAT-005, Q24): type, amount, time and remark, prefilled from the ledger
 * entry (amount without its sign, time in New York). `PUT /api/cash/transfers/{id}`.
 */
export function EditTransferModal({ entry, onDone }: EditTransferModalProps) {
  const [form] = Form.useForm<EditTransferValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)

  const mutation = useMutation({
    mutationFn: (values: EditTransferValues) =>
      updateCashTransfer(entry.sourceId, {
        type: values.type,
        amount: normalizeAmount(values.amount),
        occurredAtLocal: values.occurredAtLocal,
        remark: values.remark.trim() === '' ? null : values.remark,
      }),
    onSuccess: async () => {
      void message.success('已修改')
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
      title={`修改${entry.sourceType === 'DEPOSIT' ? '入金' : '出金'} #${String(entry.sourceId)}`}
      onCancel={onDone}
      footer={null}
      mask={{ closable: false }}
      data-testid="transfer-edit-modal"
    >
      <Form<EditTransferValues>
        form={form}
        layout="vertical"
        initialValues={{
          type: entry.sourceType === 'WITHDRAWAL' ? 'WITHDRAWAL' : 'DEPOSIT',
          // The ledger amount is signed; the transfer amount is the same digits without the sign.
          amount: entry.amount.replace(/^[-+]/, ''),
          occurredAtLocal: toLocalDateTimeInputNY(entry.occurredAt),
          remark: entry.remark ?? '',
        }}
        onFinish={(values) => {
          setSubmitError(null)
          mutation.mutate(values)
        }}
        data-testid="transfer-edit-form"
      >
        <Form.Item label="类型" name="type" rules={[{ required: true, message: '请选择类型' }]}>
          <Radio.Group>
            <Radio value="DEPOSIT" data-testid="transfer-edit-type-deposit">
              入金
            </Radio>
            <Radio value="WITHDRAWAL" data-testid="transfer-edit-type-withdrawal">
              出金
            </Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item label="金额（USD）" name="amount" required rules={[toRule(validateAmount)]}>
          <Input inputMode="decimal" autoComplete="off" data-testid="transfer-edit-amount" />
        </Form.Item>
        <Form.Item
          label="时间（美东时间）"
          name="occurredAtLocal"
          required
          rules={[toRule((value) => validateOccurredAt(value))]}
        >
          <Input type="datetime-local" step={1} data-testid="transfer-edit-time" />
        </Form.Item>
        <Form.Item label="备注" name="remark" rules={[toRule(validateRemark)]}>
          <Input.TextArea
            rows={2}
            showCount={{
              formatter: ({ count }) => `${String(count)} / ${String(REMARK_MAX_LENGTH)}`,
            }}
            data-testid="transfer-edit-remark"
          />
        </Form.Item>
        {submitError !== null && (
          <Form.Item>
            <Alert
              type="error"
              showIcon
              title={
                <span data-testid="transfer-edit-error" data-error-code={submitError.code}>
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
            data-testid="transfer-edit-submit"
          >
            保存修改
          </Button>
          <Button onClick={onDone} data-testid="transfer-edit-cancel">
            取消
          </Button>
        </Space>
      </Form>
    </Modal>
  )
}
