import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Form, Input, Modal, Select, Space, Tag, Typography } from 'antd'
import { useState } from 'react'
import { createAssignment } from '../../api/optionTrades'
import type { OptionPosition, OptionTrade } from '../../api/types'
import {
  decimalSign,
  EMPTY_VALUE,
  formatInteger,
  formatLocalDateTime,
  formatMoney,
} from '../../utils/format'
import { NOTE_MAX_LENGTH, validateNote } from '../trade/openTradeValidation'
import { CoveredLotTable } from '../trade/TradeConfirmation'
import {
  applySubmitError,
  refreshAfterTradeSaved,
  toRule,
  type SubmitError,
} from '../trade/tradeFormSupport'
import { SIGNED_MONEY_COLORS } from '../trade/tradeLabels'
import {
  assignmentFeeForRequest,
  validateAssignmentDate,
  validateAssignmentFee,
} from './assignValidation'

interface AssignFormValues {
  contracts?: number
  assignmentDate: string
  assignmentFee: string
  note: string
}

/** Form fields that the backend's `details[].field` can name. */
const FIELD_NAMES: readonly (keyof AssignFormValues)[] = [
  'contracts',
  'assignmentDate',
  'assignmentFee',
  'note',
]

const EXPLANATION =
  '被指派后按行权价用该批次冻结的现金买入股票；股票成本 = 行权价 × 股数 − 被指派批次的权利金 + 指派费，期权一侧已实现盈亏记 0.00（权利金在股票卖出时体现）。记错可以在交易列表中删除。'

function SignedMoney({ value, testId }: { value: string; testId: string }) {
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
  value: string
  testId: string
  signed?: boolean
  dashZero?: boolean
}) {
  return (
    <span data-testid={testId} style={{ fontVariantNumeric: 'tabular-nums' }}>
      {dashZero && decimalSign(value) === 0 ? EMPTY_VALUE : formatMoney(value, { signed })}
    </span>
  )
}

function AssignResult({ trade, onDone }: { trade: OptionTrade; onDone: () => void }) {
  const assignment = trade.assignment
  return (
    <Space orientation="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="success" showIcon title="已记录被指派" data-testid="assign-result" />
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="合约">
          <span data-testid="assign-result-contract">{trade.contractLabel}</span>
        </Descriptions.Item>
        <Descriptions.Item label="记账时间">
          <span data-testid="assign-result-time">
            {formatLocalDateTime(trade.tradedAtLocal)}（美东）
          </span>
        </Descriptions.Item>
        <Descriptions.Item label="张数">
          <span data-testid="assign-result-contracts">
            {trade.contracts} 张
            {trade.multiplier !== 100 && `（multiplier ${String(trade.multiplier)}）`}
          </span>
        </Descriptions.Item>
        {assignment !== null && (
          <>
            <Descriptions.Item label="权利金合计">
              <Money value={assignment.premium} testId="assign-result-premium" />
            </Descriptions.Item>
            <Descriptions.Item label="买入股数">
              <span data-testid="assign-result-shares">
                {formatInteger(assignment.shares)} 股 {trade.symbol}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="买股金额">
              <Money value={assignment.purchaseAmount} testId="assign-result-purchase" />
            </Descriptions.Item>
            <Descriptions.Item label="指派费">
              <Money value={assignment.assignmentFee} testId="assign-result-fee" />
            </Descriptions.Item>
            <Descriptions.Item label="股票成本">
              <strong>
                <Money value={assignment.stockCost} testId="assign-result-stock-cost" />
              </strong>
            </Descriptions.Item>
            <Descriptions.Item label="每股成本">
              <Money
                value={assignment.stockCostPerShare}
                testId="assign-result-cost-per-share"
              />
            </Descriptions.Item>
          </>
        )}
        <Descriptions.Item label="现金变动">
          <SignedMoney value={trade.cashChange} testId="assign-result-cash-change" />
        </Descriptions.Item>
        <Descriptions.Item label="冻结变动">
          <Money value={trade.frozenChange} testId="assign-result-frozen-change" signed dashZero />
        </Descriptions.Item>
        <Descriptions.Item label="期权已实现盈亏">
          <Money value={trade.realizedPnl ?? '0.00'} testId="assign-result-pnl" signed />
        </Descriptions.Item>
      </Descriptions>
      {trade.coveredLots !== null && (
        <div data-testid="assign-result-lots">
          <div style={{ margin: '0 0 4px' }}>被指派的开仓批次（FIFO）</div>
          <CoveredLotTable lots={trade.coveredLots} />
        </div>
      )}
      <Button type="primary" onClick={onDone} data-testid="assign-done">
        完成
      </Button>
    </Space>
  )
}

interface AssignPositionModalProps {
  /** Snapshot of the position when 被指派 was clicked (choices and dates from the backend). */
  position: OptionPosition
  onDone: () => void
}

/**
 * 记录被指派 dialog (FEAT-013) opened from an assignable (short put) position row. Contracts can
 * only be one of the backend's `assignChoices`; the date defaults to `assignmentDateDefault` and
 * the picker stops at `assignmentDateMax`. Only format checks happen here: date / position / cash
 * rules come back as 422 and are shown verbatim, keeping the dialog and its values. On success the
 * result (stock cost, cash and frozen changes, assigned lots) is the backend's response.
 */
export function AssignPositionModal({ position, onDone }: AssignPositionModalProps) {
  const [form] = Form.useForm<AssignFormValues>()
  const queryClient = useQueryClient()
  const [submitError, setSubmitError] = useState<SubmitError | null>(null)
  const [saved, setSaved] = useState<OptionTrade | null>(null)
  const choices = position.assignChoices

  const mutation = useMutation({
    mutationFn: (values: AssignFormValues) =>
      createAssignment({
        symbol: position.symbol,
        expirationDate: position.expirationDate,
        strike: position.strike,
        optionType: position.optionType,
        contracts: values.contracts ?? 0,
        assignmentDate: values.assignmentDate.trim(),
        assignmentFee: assignmentFeeForRequest(values.assignmentFee),
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
      title={`记录被指派 ${position.contractLabel}`}
      onCancel={onDone}
      footer={null}
      width={680}
      mask={{ closable: false }}
      data-testid="assign-modal"
    >
      {saved !== null ? (
        <AssignResult trade={saved} onDone={onDone} />
      ) : (
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="合约" span={2}>
              <span data-testid="assign-contract">{position.contractLabel}</span>
            </Descriptions.Item>
            <Descriptions.Item label="方向">
              <Tag color="orange" data-testid="assign-direction">
                空头
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="行权价">
              <span data-testid="assign-strike">{position.strike}</span>
            </Descriptions.Item>
            <Descriptions.Item label="Multiplier">
              <span data-testid="assign-multiplier">{position.multiplier}</span>
            </Descriptions.Item>
            <Descriptions.Item label="当前持仓">
              <span data-testid="assign-position-contracts">{position.contracts} 张</span>
            </Descriptions.Item>
            <Descriptions.Item label="冻结现金" span={2}>
              <Money value={position.frozenCash} testId="assign-frozen" dashZero />
            </Descriptions.Item>
          </Descriptions>
          <Form<AssignFormValues>
            form={form}
            layout="vertical"
            initialValues={{
              contracts: choices.at(-1)?.contracts,
              assignmentDate: position.assignmentDateDefault ?? '',
              assignmentFee: '0.00',
              note: '',
            }}
            onFinish={(values) => {
              setSubmitError(null)
              mutation.mutate(values)
            }}
            data-testid="assign-form"
          >
            <Form.Item
              label="张数"
              name="contracts"
              rules={[{ required: true, message: '请选择张数' }]}
              extra="必须是最早的一笔或几笔整笔开仓"
            >
              <Select<number>
                data-testid="assign-contracts"
                style={{ maxWidth: 240 }}
                options={choices.map((choice) => ({
                  value: choice.contracts,
                  label: (
                    <span data-testid={`assign-contracts-option-${String(choice.contracts)}`}>
                      {choice.contracts} 张（{formatInteger(choice.shares)} 股）
                    </span>
                  ),
                }))}
              />
            </Form.Item>
            <Form.Item
              label="指派日期（美东）"
              name="assignmentDate"
              required
              rules={[toRule(validateAssignmentDate)]}
              extra="按券商通知的指派日期填写；记在该日美东 16:00；提前指派填实际日期"
            >
              <Input
                type="date"
                max={position.assignmentDateMax ?? undefined}
                style={{ maxWidth: 240 }}
                data-testid="assign-date"
              />
            </Form.Item>
            <Form.Item
              label="指派费"
              name="assignmentFee"
              rules={[toRule(validateAssignmentFee)]}
              extra="券商收取的指派手续费，计入股票成本"
            >
              <Input
                inputMode="decimal"
                autoComplete="off"
                style={{ maxWidth: 240 }}
                data-testid="assign-fee"
              />
            </Form.Item>
            <Form.Item label="备注" name="note" rules={[toRule(validateNote)]}>
              <Input.TextArea
                rows={2}
                showCount={{
                  formatter: ({ count }) => `${String(count)} / ${String(NOTE_MAX_LENGTH)}`,
                }}
                data-testid="assign-note"
              />
            </Form.Item>
            <Typography.Paragraph type="secondary" data-testid="assign-explanation">
              {EXPLANATION}
            </Typography.Paragraph>
            {submitError !== null && (
              <Form.Item>
                <Alert
                  type="error"
                  showIcon
                  title={
                    <span data-testid="assign-error" data-error-code={submitError.code}>
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
                data-testid="assign-submit"
              >
                确认被指派
              </Button>
              <Button onClick={onDone} data-testid="assign-cancel">
                取消
              </Button>
            </Space>
          </Form>
        </Space>
      )}
    </Modal>
  )
}
