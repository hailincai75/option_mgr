import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App, Button, Descriptions, Form, Input, Modal, Space, Spin, Typography } from 'antd'
import { useState } from 'react'
import {
  closeLotChoicesQueryKey,
  getCloseLotChoices,
  updateOptionTrade,
} from '../../api/optionTrades'
import type { OptionTrade } from '../../api/types'
import { CloseLotPicker, type CloseLotOption } from './CloseLotPicker'
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

const CLOSE_FIELD_NAMES: readonly (keyof EditTradeValues)[] = ['netAmount', 'tradedAtLocal', 'note']

/** FEAT-051 hint above the lot list of a close being edited. */
const EDIT_LOTS_HINT = '选择要整笔平掉的开仓批次（默认勾选这笔平仓当前的被平批次）'

/** A complete `datetime-local` value; only these are sent as the lot-choices time. */
const LOCAL_DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/

/** "2026-11-04T11:00:00" → "2026-11-04T11:00" (seconds kept when not zero). */
function toInputValue(local: string): string {
  return local.length === 19 && local.endsWith(':00') ? local.slice(0, 16) : local
}

interface EditTradeModalProps {
  trade: OptionTrade
  onDone: () => void
}

/**
 * 修改交易 dialog (FEAT-005, Q43). Editable: net amount, trade time, note; opens also contracts and
 * multiplier; closes (FEAT-051, Q256) their chosen lots instead of contracts (from
 * `GET /api/option-trades/{id}/close-lot-choices`, refetched for a changed trade time; sent as
 * `lotTradeIds`, contracts omitted). Contract and direction are read-only (delete and re-enter
 * instead). `PUT /api/option-trades/{id}` replaces all editable fields, so every field is always sent.
 */
export function EditTradeModal({ trade, onDone }: EditTradeModalProps) {
  const [form] = Form.useForm<EditTradeValues>()
  const queryClient = useQueryClient()
  const { message } = App.useApp()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const closing = isClosingAction(trade.action)

  const initialTime = toInputValue(trade.tradedAtLocal)

  // FEAT-051 (Q256): a close picks its lots from the backend's choices at the form's trade time.
  const watchedTime: unknown = Form.useWatch('tradedAtLocal', form)
  const choicesTime =
    closing &&
    typeof watchedTime === 'string' &&
    watchedTime !== initialTime &&
    LOCAL_DATE_TIME.test(watchedTime)
      ? watchedTime
      : null
  const choicesQuery = useQuery({
    queryKey: closeLotChoicesQueryKey(trade.id, choicesTime),
    queryFn: ({ signal }) => getCloseLotChoices(trade.id, choicesTime, signal),
    enabled: closing,
    placeholderData: keepPreviousData,
  })
  // null = not touched yet → the close's current lots (`selected`) are ticked.
  const [pickedIds, setPickedIds] = useState<number[] | null>(null)
  const choices = choicesQuery.data?.lots ?? []
  const lotOptions: CloseLotOption[] = choices.map((lot) => ({
    tradeId: lot.tradeId,
    action: lot.action,
    tradedAt: lot.tradedAt,
    contracts: lot.contracts,
    netAmount: lot.netAmount,
    frozen: lot.frozen,
    unrealized: null,
    closeDefault: false,
  }))
  // A lot no longer selectable at the new time drops out of the selection.
  const lotTradeIds = (
    pickedIds ?? choices.filter((lot) => lot.selected).map((lot) => lot.tradeId)
  ).filter((id) => choices.some((lot) => lot.tradeId === id))
  const lotsReady = !closing || (choicesQuery.data !== undefined && lotTradeIds.length > 0)

  const mutation = useMutation({
    mutationFn: (values: EditTradeValues) =>
      updateOptionTrade(
        trade.id,
        closing
          ? {
              lotTradeIds,
              netAmount: normalizeDecimal(values.netAmount),
              tradedAtLocal: values.tradedAtLocal,
              note: values.note.trim() === '' ? null : values.note,
            }
          : {
              contracts: Number(values.contracts.trim()),
              netAmount: normalizeDecimal(values.netAmount),
              tradedAtLocal: values.tradedAtLocal,
              multiplier: Number(values.multiplier.trim()),
              note: values.note.trim() === '' ? null : values.note,
            },
      ),
    onSuccess: async () => {
      void message.success('交易已修改')
      await refreshAfterChange(queryClient)
      onDone()
    },
    onError: (error) => {
      setSubmitError(
        // Closes have no contracts / multiplier inputs: their details stay in the error message.
        applySubmitError(closing ? CLOSE_FIELD_NAMES : FIELD_NAMES, error, (fields) => {
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
            tradedAtLocal: initialTime,
            multiplier: String(trade.multiplier),
            note: trade.note ?? '',
          }}
          onFinish={(values) => {
            if (!lotsReady) return
            setSubmitError(null)
            mutation.mutate(values)
          }}
          data-testid="trade-edit-form"
        >
          {closing ? (
            <Form.Item label="被平批次" required>
              {choicesQuery.isError ? (
                <Alert
                  type="error"
                  showIcon
                  title={
                    <span data-testid="trade-edit-lots-error">
                      可选批次加载失败：{choicesQuery.error.message}
                    </span>
                  }
                />
              ) : choicesQuery.data === undefined ? (
                <Spin size="small">
                  <span data-testid="trade-edit-lots-loading">正在加载可选批次…</span>
                </Spin>
              ) : (
                <CloseLotPicker
                  lots={lotOptions}
                  selectedIds={lotTradeIds}
                  onChange={setPickedIds}
                  showFrozen={trade.action === 'BTC' && trade.optionType === 'PUT'}
                  testIdPrefix="trade-edit"
                  hint={EDIT_LOTS_HINT}
                />
              )}
            </Form.Item>
          ) : (
            <Form.Item label="张数" name="contracts" required rules={[toRule(validateContracts)]}>
              <Input inputMode="numeric" autoComplete="off" data-testid="trade-edit-contracts" />
            </Form.Item>
          )}
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
              disabled={!lotsReady}
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
