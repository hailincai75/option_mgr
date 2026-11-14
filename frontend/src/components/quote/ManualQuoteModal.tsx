import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Descriptions, Form, Input, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import { setOptionOverride, setUnderlyingOverride } from '../../api/marketData'
import type { OptionContractKey } from '../../api/types'
import { toRule } from '../trade/tradeFormSupport'
import { refreshQuotes } from './marketDataRefresh'
import { overrideErrorDisplay, quotePriceText, validateManualPrice } from './quoteLabels'

/** What 手动设置 overrides: an underlying price, or a contract's mark. */
export type ManualQuoteTarget =
  | { kind: 'underlying'; symbol: string; currentPrice: string | null }
  | {
      kind: 'option'
      contract: OptionContractKey
      contractLabel: string
      currentMark: string | null
    }

interface ManualQuoteFormValues {
  price: string
}

interface ManualQuoteModalProps {
  target: ManualQuoteTarget
  onDone: () => void
}

/**
 * 手动设置 dialog (FEAT-016 rule 8): a price > 0 for the underlying (`PUT …/underlyings/{symbol}`)
 * or the contract's mark (`PUT …/options`). The input is sent as typed (string, no rounding); the
 * backend validates again and its errors are shown (field errors under the input, 422
 * `QUOTE_TARGET_NOT_HELD` verbatim above the buttons). On success every position quote reloads.
 */
export function ManualQuoteModal({ target, onDone }: ManualQuoteModalProps) {
  const [form] = Form.useForm<ManualQuoteFormValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [formError, setFormError] = useState<{ code: string; message: string } | null>(null)
  const isUnderlying = target.kind === 'underlying'
  const title = isUnderlying ? `手动设置标的价 ${target.symbol}` : `手动设置 Mark ${target.contractLabel}`
  const current = isUnderlying ? target.currentPrice : target.currentMark

  const mutation = useMutation({
    mutationFn: async (price: string) => {
      if (target.kind === 'underlying') {
        await setUnderlyingOverride(target.symbol, { price })
      } else {
        await setOptionOverride({ ...target.contract, mark: price })
      }
    },
    onSuccess: async () => {
      void message.success(isUnderlying ? '已设置手动标的价' : '已设置手动 Mark')
      onDone()
      await refreshQuotes(queryClient)
    },
    onError: (error) => {
      const display = overrideErrorDisplay(error)
      form.setFields([{ name: 'price', errors: display.fieldErrors }])
      setFormError(display.formError)
    },
  })

  return (
    <Modal
      open
      title={title}
      onCancel={onDone}
      footer={null}
      mask={{ closable: false }}
      data-testid="manual-quote-modal"
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label={isUnderlying ? '标的' : '合约'}>
            <span data-testid="manual-quote-target">
              {isUnderlying ? target.symbol : target.contractLabel}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label={isUnderlying ? '当前标的价' : '当前 Mark'}>
            <span data-testid="manual-quote-current">{quotePriceText(current)}</span>
          </Descriptions.Item>
        </Descriptions>
        <Form<ManualQuoteFormValues>
          form={form}
          layout="vertical"
          initialValues={{ price: '' }}
          onFinish={(values) => {
            setFormError(null)
            mutation.mutate(values.price.trim())
          }}
          disabled={mutation.isPending}
          data-testid="manual-quote-form"
        >
          <Form.Item
            label={isUnderlying ? '标的价' : 'Mark'}
            name="price"
            rules={[toRule(validateManualPrice)]}
          >
            <Input inputMode="decimal" autoFocus data-testid="manual-quote-price" />
          </Form.Item>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
            手动值所有账户共用，有效到该{isUnderlying ? '标的' : '合约'}
            下一次成功获取到行情为止，也可随时清除。
          </Typography.Paragraph>
          {formError !== null && (
            <Form.Item>
              <Alert
                type="error"
                showIcon
                title={
                  <span data-testid="manual-quote-error" data-error-code={formError.code}>
                    {formError.message}
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
              data-testid="manual-quote-submit"
            >
              保存
            </Button>
            <Button onClick={onDone} disabled={false} data-testid="manual-quote-cancel">
              取消
            </Button>
          </Space>
        </Form>
      </Space>
    </Modal>
  )
}
