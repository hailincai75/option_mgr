import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { createExercise } from '../../api/optionTrades'
import type { OptionPosition, OptionTrade } from '../../api/types'
import {
  decimalSign,
  EMPTY_VALUE,
  formatInteger,
  formatLocalDateTime,
  formatMoney,
} from '../../utils/format'
import { NOTE_MAX_LENGTH, validateNote } from '../trade/openTradeValidation'
import { SoldLotTable } from '../stock/SoldLotTable'
import { CoveredLotTable } from '../trade/TradeConfirmation'
import {
  applySubmitError,
  refreshAfterTradeSaved,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import {
  exerciseFeeForRequest,
  validateExerciseDate,
  validateExerciseFee,
} from './exerciseValidation'

interface ExerciseFormValues {
  contracts?: number
  exerciseDate: string
  exerciseFee: string
  note: string
}

/** Form fields that the backend's `details[].field` can name. */
const FIELD_NAMES: readonly (keyof ExerciseFormValues)[] = [
  'contracts',
  'exerciseDate',
  'exerciseFee',
  'note',
]

/** FEAT-014 rule 4 (Q111), call. */
const CALL_EXERCISE_EXPLANATION =
  '按行权价买入股票：买股金额 = 行权价 × 股数，股票成本 = 买股金额 + 被行权批次的权利金 + 行权费，现金减少（买股金额 + 行权费）；期权已实现盈亏记 0.00。记错可以在交易列表中删除。'

/** FEAT-014 rule 4 (Q111), put; Q113: shares covering a call cannot be sold. */
const PUT_EXERCISE_EXPLANATION =
  '按行权价按 FIFO 交出股票：行权金额 = 行权价 × 股数，卖出实收 = 行权金额 − 被行权批次的权利金 − 行权费，现金增加（行权金额 − 行权费）；期权已实现盈亏记 0.00。被 call 覆盖的股票不能卖出。记错可以在交易列表中删除。'

function SignedMoney({ value, testId }: { value: string | null; testId: string }) {
  if (value === null) return <span data-testid={testId}>{EMPTY_VALUE}</span>
  return (
    <span
      data-testid={testId}
      style={{ color: SIGNED_MONEY_COLORS[decimalSign(value)], fontVariantNumeric: 'tabular-nums' }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

/** Uncolored amount ("—" for a zero frozen amount when `dashZero`). */
function Money({
  value,
  testId,
  signed = false,
  dashZero = false,
}: {
  value: string | null
  testId: string
  signed?: boolean
  dashZero?: boolean
}) {
  return (
    <span data-testid={testId} style={{ fontVariantNumeric: 'tabular-nums' }}>
      {value !== null && dashZero && decimalSign(value) === 0
        ? EMPTY_VALUE
        : formatMoney(value, { signed })}
    </span>
  )
}

function ExerciseResult({ trade, onDone }: { trade: OptionTrade; onDone: () => void }) {
  const exercise = trade.exercise ?? null
  const isCall = trade.optionType === 'CALL'
  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="success" showIcon title="已记录行权" data-testid="exercise-result" />
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="合约">
          <span data-testid="exercise-result-contract">{trade.contractLabel}</span>
        </Descriptions.Item>
        <Descriptions.Item label="记账时间">
          <span data-testid="exercise-result-time">
            {formatLocalDateTime(trade.tradedAtLocal)}（美东）
          </span>
        </Descriptions.Item>
        <Descriptions.Item label="张数">
          <span data-testid="exercise-result-contracts">
            {trade.contracts} 张
            {trade.multiplier !== 100 && `（multiplier ${String(trade.multiplier)}）`}
          </span>
        </Descriptions.Item>
        {exercise !== null && (
          <>
            <Descriptions.Item label="权利金合计">
              <Money value={exercise.premium} testId="exercise-result-premium" />
            </Descriptions.Item>
            <Descriptions.Item label={isCall ? '买入股数' : '卖出股数'}>
              <span data-testid="exercise-result-shares">
                {formatInteger(exercise.shares)} 股 {trade.symbol}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="行权费">
              <Money value={exercise.exerciseFee} testId="exercise-result-fee" />
            </Descriptions.Item>
          </>
        )}
        {exercise !== null && isCall && (
          <>
            <Descriptions.Item label="买股金额">
              <Money value={exercise.purchaseAmount} testId="exercise-result-purchase" />
            </Descriptions.Item>
            <Descriptions.Item label="股票成本">
              <strong>
                <Money value={exercise.stockCost} testId="exercise-result-stock-cost" />
              </strong>
            </Descriptions.Item>
            <Descriptions.Item label="每股成本">
              <Money
                value={exercise.stockCostPerShare}
                testId="exercise-result-cost-per-share"
              />
            </Descriptions.Item>
          </>
        )}
        {exercise !== null && !isCall && (
          <>
            <Descriptions.Item label="行权金额">
              <Money value={exercise.saleAmount} testId="exercise-result-sale-amount" />
            </Descriptions.Item>
            <Descriptions.Item label="卖出实收">
              <strong>
                <Money value={exercise.proceeds} testId="exercise-result-proceeds" />
              </strong>
            </Descriptions.Item>
            <Descriptions.Item label="交出成本">
              <Money value={exercise.soldCost} testId="exercise-result-sold-cost" />
            </Descriptions.Item>
            <Descriptions.Item label="股票已实现盈亏">
              <strong>
                <SignedMoney
                  value={exercise.stockRealizedPnl}
                  testId="exercise-result-stock-pnl"
                />
              </strong>
            </Descriptions.Item>
          </>
        )}
        <Descriptions.Item label="现金变动">
          <SignedMoney value={trade.cashChange} testId="exercise-result-cash-change" />
        </Descriptions.Item>
        <Descriptions.Item label="期权已实现盈亏">
          <Money value={trade.realizedPnl ?? '0.00'} testId="exercise-result-pnl" signed />
        </Descriptions.Item>
        {exercise !== null && (
          <Descriptions.Item label="股票记录">
            <span data-testid="exercise-result-stock-trade">
              {isCall ? '行权买入' : '行权卖出'} #{exercise.stockTradeId}
            </span>
          </Descriptions.Item>
        )}
        {trade.note !== null && trade.note.trim() !== '' && (
          <Descriptions.Item label="备注">
            <span data-testid="exercise-result-note">{trade.note}</span>
          </Descriptions.Item>
        )}
      </Descriptions>
      {trade.coveredLots !== null && (
        <div data-testid="exercise-result-lots">
          <div style={{ margin: '0 0 4px' }}>被行权的开仓批次（FIFO）</div>
          <CoveredLotTable lots={trade.coveredLots} />
        </div>
      )}
      {!isCall && exercise?.soldLots != null && (
        <div data-testid="exercise-result-sold-lots">
          <div style={{ margin: '0 0 4px' }}>交出的股票批次（FIFO）</div>
          <SoldLotTable lots={exercise.soldLots} />
        </div>
      )}
      <Button type="primary" onClick={onDone} data-testid="exercise-done">
        完成
      </Button>
    </Space>
  )
}

/** "当前持股 X 股，可用 Y 股" of a long put row (backend's current values). */
function sharesText(position: OptionPosition): string {
  if (position.underlyingShares == null) return EMPTY_VALUE
  const held = `当前持股 ${formatInteger(position.underlyingShares)} 股`
  if (position.availableShares == null) return held
  return `${held}，可用 ${formatInteger(position.availableShares)} 股`
}

interface ExercisePositionModalProps {
  /** Snapshot of the position when 行权 was clicked (choices and dates from the backend). */
  position: OptionPosition
  onDone: () => void
}

/**
 * 记录行权 dialog (FEAT-014) opened from an exercisable long call / put row. Contracts can only be
 * one of the backend's `exerciseChoices` (default the last = all); the date defaults to
 * `exerciseDateDefault` and the picker stops at `exerciseDateMax`. A call shows the backend's
 * current `availableCash`, a put its `underlyingShares` / `availableShares` (display only). Only
 * format checks happen here: object / date / position / shares / coverage / cash rules come back
 * as 422 and are shown verbatim, keeping the dialog and its values. On success the result (call:
 * purchase amount, stock cost; put: sale amount, proceeds, sold cost, stock P&L and delivered lots)
 * is the backend's response.
 */
export function ExercisePositionModal({ position, onDone }: ExercisePositionModalProps) {
  const [form] = Form.useForm<ExerciseFormValues>()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [saved, setSaved] = useState<OptionTrade | null>(null)
  const choices = position.exerciseChoices ?? []
  const isCall = position.optionType === 'CALL'

  const mutation = useMutation({
    mutationFn: (values: ExerciseFormValues) =>
      createExercise({
        symbol: position.symbol,
        expirationDate: position.expirationDate,
        strike: position.strike,
        optionType: position.optionType,
        contracts: values.contracts ?? 0,
        exerciseDate: values.exerciseDate.trim(),
        exerciseFee: exerciseFeeForRequest(values.exerciseFee),
        note: values.note.trim() === '' ? null : values.note,
      }),
    onSuccess: async (trade) => {
      setSaved(trade)
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

  return (
    <Modal
      open
      title={`记录行权 ${position.contractLabel}`}
      onCancel={onDone}
      footer={null}
      width={680}
      mask={{ closable: false }}
      data-testid="exercise-modal"
    >
      {saved !== null ? (
        <ExerciseResult trade={saved} onDone={onDone} />
      ) : (
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="合约" span={2}>
              <span data-testid="exercise-contract">{position.contractLabel}</span>
            </Descriptions.Item>
            <Descriptions.Item label="方向">
              <Tag color="blue" data-testid="exercise-direction">
                多头
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="行权价">
              <span data-testid="exercise-strike">{position.strike}</span>
            </Descriptions.Item>
            <Descriptions.Item label="Multiplier">
              <span data-testid="exercise-multiplier">{position.multiplier}</span>
            </Descriptions.Item>
            <Descriptions.Item label="当前持仓">
              <span data-testid="exercise-position-contracts">{position.contracts} 张</span>
            </Descriptions.Item>
            {isCall ? (
              <Descriptions.Item label="现金" span={2}>
                <span data-testid="exercise-available-cash">
                  可用现金{' '}
                  {position.availableCash == null
                    ? EMPTY_VALUE
                    : formatMoney(position.availableCash)}
                </span>
              </Descriptions.Item>
            ) : (
              <Descriptions.Item label={`股票 ${position.symbol}`} span={2}>
                <span data-testid="exercise-underlying-shares">{sharesText(position)}</span>
              </Descriptions.Item>
            )}
          </Descriptions>
          <Form<ExerciseFormValues>
            form={form}
            layout="vertical"
            initialValues={{
              contracts: choices.at(-1)?.contracts,
              exerciseDate: position.exerciseDateDefault ?? '',
              exerciseFee: '0.00',
              note: '',
            }}
            onFinish={(values) => {
              setSubmitError(null)
              mutation.mutate(values)
            }}
            data-testid="exercise-form"
          >
            <Form.Item
              label="张数"
              name="contracts"
              rules={[{ required: true, message: '请选择张数' }]}
              extra="必须是最早的一笔或几笔整笔开仓"
            >
              <Select<number>
                data-testid="exercise-contracts"
                style={{ maxWidth: 240 }}
                options={choices.map((choice) => ({
                  value: choice.contracts,
                  label: (
                    <span data-testid={`exercise-contracts-option-${String(choice.contracts)}`}>
                      {choice.contracts} 张（{formatInteger(choice.shares)} 股）
                    </span>
                  ),
                }))}
              />
            </Form.Item>
            <Form.Item
              label="行权日期（美东）"
              name="exerciseDate"
              required
              rules={[toRule(validateExerciseDate)]}
              extra="按券商记录的行权日期填写；记在该日美东 16:00；提前行权填实际日期"
            >
              <Input
                type="date"
                max={position.exerciseDateMax ?? undefined}
                style={{ maxWidth: 240 }}
                data-testid="exercise-date"
              />
            </Form.Item>
            <Form.Item
              label="行权费"
              name="exerciseFee"
              rules={[toRule(validateExerciseFee)]}
              extra="券商收取的行权手续费"
            >
              <Input
                inputMode="decimal"
                autoComplete="off"
                style={{ maxWidth: 240 }}
                data-testid="exercise-fee"
              />
            </Form.Item>
            <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
              <Input.TextArea
                rows={2}
                showCount={{
                  formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
                }}
                data-testid="exercise-note"
              />
            </Form.Item>
            <Typography.Paragraph type="secondary" data-testid="exercise-explanation">
              {isCall ? CALL_EXERCISE_EXPLANATION : PUT_EXERCISE_EXPLANATION}
            </Typography.Paragraph>
            {submitError !== null && (
              <Form.Item>
                <Alert
                  type="error"
                  showIcon
                  title={
                    <span data-testid="exercise-error" data-error-code={submitError.code}>
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
                data-testid="exercise-submit"
              >
                确认行权
              </Button>
              <Button onClick={onDone} data-testid="exercise-cancel">
                取消
              </Button>
            </Space>
          </Form>
        </Space>
      )}
    </Modal>
  )
}
