import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Descriptions, Form, Input, Modal, Space, Typography } from 'antd'
import { useState } from 'react'
import { adhocClosureAlertQueryKeys } from '../../api/adhocClosureAlerts'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { greeksSummaryQueryKeys } from '../../api/greeksSummary'
import { unrealizedSummaryQueryKeys } from '../../api/unrealizedSummary'
import { createExpirationAdjustment } from '../../api/optionTrades'
import type { OptionPosition } from '../../api/types'
import { nowLocalDateTimeNY } from '../../utils/format'
import {
  CALENDAR_LOOKUP_FAILED_HINT,
  monthOf,
  useNonTradingDays,
  type YearMonth,
} from '../calendar/tradingCalendar'
import { ExpirationDatePicker } from '../trade/ExpirationDatePicker'
import { NOTE_MAX_LENGTH, validateNote } from '../trade/openTradeValidation'
import {
  applySubmitError,
  refreshAfterChange,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import {
  adjustmentExplanation,
  currentExpirationText,
  recordCountText,
  toExpirationAdjustmentRequest,
} from './expirationAdjustment'

interface AdjustFormValues {
  newExpirationDate: string
  note?: string
}

const FIELD_NAMES: readonly (keyof AdjustFormValues)[] = ['newExpirationDate', 'note']

interface AdjustExpirationModalProps {
  /** Snapshot of the position when 调整到期日 was clicked. */
  position: OptionPosition
  onDone: () => void
}

/**
 * 调整到期日 dialog (FEAT-043 rule 2) opened from a row the backend marks `expirationAdjustable`.
 * The picker defaults to the backend's `defaultNewExpirationDate`; dates outside
 * `newExpirationDateMin`..`newExpirationDateMax` and FEAT-009 non-trading days are disabled — every
 * other rule is checked by `POST /api/option-trades/expiration-adjustments`, whose errors are shown
 * verbatim with the dialog kept open. On success the dialog closes and everything refreshes.
 */
export function AdjustExpirationModal({ position, onDone }: AdjustExpirationModalProps) {
  const [form] = Form.useForm<AdjustFormValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const recordCount = position.expirationAdjustmentRecordCount ?? 0
  const defaultDate = position.defaultNewExpirationDate ?? ''
  const [todayMonth] = useState<YearMonth>(() => nowLocalDateTimeNY().slice(0, 7))
  const [viewMonth, setViewMonth] = useState<YearMonth>(
    () => monthOf(defaultDate) ?? monthOf(position.newExpirationDateMax) ?? todayMonth,
  )
  const calendar = useNonTradingDays(viewMonth)

  const mutation = useMutation({
    mutationFn: (values: AdjustFormValues) =>
      createExpirationAdjustment(
        toExpirationAdjustmentRequest(position, values.newExpirationDate, values.note),
      ),
    onSuccess: async (response) => {
      void message.success(`已调整到期日：${response.contractLabel}`)
      onDone()
      await refreshAfterChange(queryClient)
    },
    onError: async (error) => {
      setSubmitError(
        applySubmitError(FIELD_NAMES, error, (fields) => {
          form.setFields(fields)
        }),
      )
      // The row may have changed meanwhile: reload the table behind the dialog.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all }),
        // FEAT-017: the account totals follow the positions.
        queryClient.invalidateQueries({ queryKey: unrealizedSummaryQueryKeys.all }),
        // FEAT-019: the per-symbol Greeks summary follows the positions too.
        queryClient.invalidateQueries({ queryKey: greeksSummaryQueryKeys.all }),
        queryClient.invalidateQueries({ queryKey: adhocClosureAlertQueryKeys.all }),
      ])
    },
  })

  return (
    <Modal
      open
      title={`调整到期日 ${position.contractLabel}`}
      onCancel={onDone}
      footer={null}
      width={640}
      mask={{ closable: false }}
      data-testid="adjust-expiration-modal"
    >
      <Space orientation="vertical" size={12} style={{ width: '100%' }}>
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="合约">
            <span data-testid="adjust-expiration-contract">{position.contractLabel}</span>
          </Descriptions.Item>
          <Descriptions.Item label="当前到期日">
            <span data-testid="adjust-expiration-current">{currentExpirationText(position)}</span>
          </Descriptions.Item>
          <Descriptions.Item label="将修改的记录">
            <span data-testid="adjust-expiration-record-count">
              {recordCountText(recordCount)}
            </span>
          </Descriptions.Item>
        </Descriptions>
        <Form<AdjustFormValues>
          form={form}
          layout="vertical"
          initialValues={{ newExpirationDate: defaultDate, note: '' }}
          onFinish={(values) => {
            setSubmitError(null)
            mutation.mutate(values)
          }}
          disabled={mutation.isPending}
          data-testid="adjust-expiration-form"
        >
          <Form.Item
            label="新到期日"
            name="newExpirationDate"
            rules={[{ required: true, message: '请选择新到期日' }]}
            extra={
              calendar.currentMonthFailed ? (
                <span data-testid="adjust-expiration-calendar-error">
                  {CALENDAR_LOOKUP_FAILED_HINT}
                </span>
              ) : undefined
            }
          >
            <ExpirationDatePicker
              data-testid="adjust-expiration-date"
              dayTestIdPrefix="adjust-expiration-day"
              nonTradingDays={calendar.days}
              restrict
              minDate={position.newExpirationDateMin ?? null}
              maxDate={position.newExpirationDateMax ?? null}
              viewMonth={viewMonth}
              onViewMonthChange={setViewMonth}
              defaultMonth={monthOf(defaultDate) ?? todayMonth}
            />
          </Form.Item>
          <Form.Item label="备注（可选）" name="note" rules={[toRule(validateNote)]}>
            <Input.TextArea
              rows={2}
              showCount={{
                formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
              }}
              data-testid="adjust-expiration-note"
            />
          </Form.Item>
          <Typography.Paragraph type="secondary" data-testid="adjust-expiration-hint">
            {adjustmentExplanation(recordCount)}
          </Typography.Paragraph>
          {submitError !== null && (
            <Form.Item>
              <Alert
                type="error"
                showIcon
                title={
                  <span data-testid="adjust-expiration-error" data-error-code={submitError.code}>
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
              data-testid="adjust-expiration-submit"
            >
              确认调整
            </Button>
            <Button onClick={onDone} disabled={false} data-testid="adjust-expiration-cancel">
              取消
            </Button>
          </Space>
        </Form>
      </Space>
    </Modal>
  )
}
