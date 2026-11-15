import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Checkbox, Descriptions, Modal, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState, type HTMLAttributes } from 'react'
import { adhocClosureAlertQueryKeys } from '../../api/adhocClosureAlerts'
import { ApiError } from '../../api/client'
import { optionPositionQueryKeys } from '../../api/optionPositions'
import { greeksSummaryQueryKeys } from '../../api/greeksSummary'
import { unrealizedSummaryQueryKeys } from '../../api/unrealizedSummary'
import {
  createExpirationBatch,
  expirationBatchFailures,
  optionTradeQueryKeys,
} from '../../api/optionTrades'
import type {
  CreateExpirationRequest,
  ExpirationBatchFailure,
  ExpirationBatchResponse,
  OptionPosition,
} from '../../api/types'
import { EMPTY_VALUE, formatLocalDateTime } from '../../utils/format'
import { refreshAfterChange, type SubmitError } from '../trade/tradeFormSupport'
import {
  contractKey,
  expiredCountText,
  matchExpirationsToRows,
  matchFailuresToRows,
  selectedCountText,
  toExpirationItem,
  type BatchResultRow,
} from './batchExpire'
import { directionLabel, frozenText } from './expireLabels'
import { SignedMoney } from './ExpirePositionModal'

const BATCH_EXPLANATION =
  '被指派或行权的持仓请取消勾选，在持仓行上单独处理；每条作废记在各自到期日 16:00，记错可在交易列表中逐条删除'

interface Rejection {
  error: SubmitError
  byRow: Map<string, ExpirationBatchFailure[]>
  unmatched: ExpirationBatchFailure[]
}

interface Saved {
  response: ExpirationBatchResponse
  rows: BatchResultRow[]
}

interface BatchExpireModalProps {
  /** Snapshot (table order) of the rows with the 作废 button when 批量作废 was clicked. */
  positions: OptionPosition[]
  onDone: () => void
}

function errorText(error: Error): string {
  const detail =
    error instanceof ApiError && error.details.length > 0
      ? `（${error.details.map((item) => `${item.field}: ${item.message}`).join('；')}）`
      : ''
  return `${error.message}${detail}`
}

/**
 * 批量作废 dialog (FEAT-015). Lists the snapshot rows, all checked (Q117); sends the checked ones to
 * `POST /api/option-trades/expirations/batch`. A rejection keeps the dialog open and shows every
 * failure verbatim beside its row (matched by contract); success shows the backend's totals and
 * per-row results (also matched by contract).
 */
export function BatchExpireModal({ positions, onDone }: BatchExpireModalProps) {
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(positions.map((position) => contractKey(position))),
  )
  const [rejection, setRejection] = useState<Rejection | null>(null)
  const [saved, setSaved] = useState<Saved | null>(null)

  const selectedRows = positions.filter((position) => selected.has(contractKey(position)))

  const mutation = useMutation({
    mutationFn: (variables: { rows: OptionPosition[]; items: CreateExpirationRequest[] }) =>
      createExpirationBatch({ items: variables.items }),
    onSuccess: async (response, { rows }) => {
      setSaved({
        response,
        rows: matchExpirationsToRows(response.expirations, rows),
      })
      await refreshAfterChange(queryClient)
      for (const expiration of response.expirations) {
        queryClient.setQueryData(optionTradeQueryKeys.detail(expiration.id), expiration)
      }
    },
    onError: async (error, { items }) => {
      const failures = expirationBatchFailures(error)
      const matched = matchFailuresToRows(failures, positions, items)
      setRejection({
        error: {
          code: error instanceof ApiError ? error.code : 'UNKNOWN',
          message: errorText(error),
        },
        ...matched,
      })
      // Positions may have changed (e.g. 持仓已变化): reload the table behind the dialog. The
      // dialog keeps its snapshot; reopening it shows the new values.
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

  const setRowChecked = (key: string, checked: boolean) => {
    setSelected((previous) => {
      const next = new Set(previous)
      if (checked) next.add(key)
      else next.delete(key)
      return next
    })
  }

  const columns: ColumnsType<OptionPosition> = [
    {
      title: '',
      key: 'select',
      width: 40,
      render: (_, position) => {
        const key = contractKey(position)
        return (
          <Checkbox
            checked={selected.has(key)}
            disabled={mutation.isPending}
            onChange={(event) => {
              setRowChecked(key, event.target.checked)
            }}
            aria-label={`选择 ${position.contractLabel}`}
            data-testid="batch-expire-row-checkbox"
          />
        )
      },
    },
    {
      title: '合约',
      key: 'contract',
      render: (_, position) => {
        const failures = rejection?.byRow.get(contractKey(position)) ?? []
        return (
          <Space orientation="vertical" size={2}>
            <span data-testid="batch-expire-row-contract" style={{ whiteSpace: 'nowrap' }}>
              {position.contractLabel}
            </span>
            {failures.map((failure) => (
              <Typography.Text
                key={`${String(failure.index)}-${failure.code}`}
                type="danger"
                data-testid="batch-expire-row-error"
                data-error-code={failure.code}
              >
                {failure.message}
              </Typography.Text>
            ))}
          </Space>
        )
      },
    },
    {
      title: '方向',
      key: 'direction',
      render: (_, position) => (
        <Tag data-testid="batch-expire-row-direction">{directionLabel(position.direction)}</Tag>
      ),
    },
    {
      title: '张数',
      key: 'contracts',
      render: (_, position) => (
        <span data-testid="batch-expire-row-contracts" style={{ whiteSpace: 'nowrap' }}>
          {position.expiryContracts} 张
          {position.multiplier !== 100 && `（multiplier ${String(position.multiplier)}）`}
        </span>
      ),
    },
    {
      title: '开仓净额合计',
      key: 'openingNet',
      align: 'right',
      render: (_, position) => (
        <SignedMoney value={position.openingNetTotal} testId="batch-expire-row-opening-net" />
      ),
    },
    {
      title: '将释放的冻结',
      key: 'frozen',
      align: 'right',
      render: (_, position) => (
        <span data-testid="batch-expire-row-frozen">{frozenText(position.frozenCash, false)}</span>
      ),
    },
  ]

  const resultColumns: ColumnsType<BatchResultRow> = [
    {
      title: '合约',
      key: 'contract',
      render: (_, row) => (
        <span data-testid="batch-expire-result-row-contract" style={{ whiteSpace: 'nowrap' }}>
          {row.expiration?.contractLabel ?? row.position?.contractLabel ?? EMPTY_VALUE}
        </span>
      ),
    },
    {
      title: '方向',
      key: 'direction',
      render: (_, row) => (
        <span data-testid="batch-expire-result-row-direction">
          {directionLabel(row.expiration?.expiredDirection ?? null)}
        </span>
      ),
    },
    {
      title: '张数',
      key: 'contracts',
      render: (_, row) => (
        <span data-testid="batch-expire-result-row-contracts">
          {row.expiration === null ? EMPTY_VALUE : `${String(row.expiration.contracts)} 张`}
        </span>
      ),
    },
    {
      title: '记账时间（美东）',
      key: 'time',
      render: (_, row) => (
        <span data-testid="batch-expire-result-row-time" style={{ whiteSpace: 'nowrap' }}>
          {row.expiration === null
            ? EMPTY_VALUE
            : formatLocalDateTime(row.expiration.tradedAtLocal)}
        </span>
      ),
    },
    {
      title: '已实现盈亏',
      key: 'pnl',
      align: 'right',
      render: (_, row) =>
        row.expiration?.realizedPnl == null ? (
          <span data-testid="batch-expire-result-row-pnl">{EMPTY_VALUE}</span>
        ) : (
          <SignedMoney value={row.expiration.realizedPnl} testId="batch-expire-result-row-pnl" />
        ),
    },
    {
      title: '冻结变动',
      key: 'frozenChange',
      align: 'right',
      render: (_, row) => (
        <span data-testid="batch-expire-result-row-frozen-change">
          {row.expiration === null ? EMPTY_VALUE : frozenText(row.expiration.frozenChange, true)}
        </span>
      ),
    },
  ]

  const total = positions.length
  const selectedCount = selectedRows.length

  return (
    <Modal
      open
      title="批量作废"
      onCancel={onDone}
      footer={null}
      width={880}
      mask={{ closable: false }}
      data-testid="batch-expire-modal"
    >
      {saved !== null ? (
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Alert
            type="success"
            showIcon
            title={
              <span data-testid="batch-expire-result-count">
                {expiredCountText(saved.response.count)}
              </span>
            }
            data-testid="batch-expire-result"
          />
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="已实现盈亏合计">
              <strong>
                <SignedMoney
                  value={saved.response.realizedPnlTotal}
                  testId="batch-expire-result-pnl-total"
                />
              </strong>
            </Descriptions.Item>
            <Descriptions.Item label="冻结变动合计">
              <span data-testid="batch-expire-result-frozen-total">
                {frozenText(saved.response.frozenChangeTotal, true)}
              </span>
            </Descriptions.Item>
          </Descriptions>
          <Table<BatchResultRow>
            rowKey="key"
            size="small"
            columns={resultColumns}
            dataSource={saved.rows}
            pagination={false}
            onRow={(row) =>
              ({
                'data-testid': 'batch-expire-result-row',
                'data-contract': row.expiration?.contractLabel ?? row.position?.contractLabel,
              }) as HTMLAttributes<HTMLElement>
            }
            data-testid="batch-expire-result-table"
          />
          <Button type="primary" onClick={onDone} data-testid="batch-expire-done">
            完成
          </Button>
        </Space>
      ) : (
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <Space size={12} wrap>
            <Typography.Text data-testid="batch-expire-selected-count">
              {selectedCountText(selectedCount, total)}
            </Typography.Text>
            <Button
              size="small"
              disabled={mutation.isPending}
              onClick={() => {
                setSelected(new Set(positions.map((position) => contractKey(position))))
              }}
              data-testid="batch-expire-select-all"
            >
              全选
            </Button>
            <Button
              size="small"
              disabled={mutation.isPending}
              onClick={() => {
                setSelected(new Set())
              }}
              data-testid="batch-expire-select-none"
            >
              全不选
            </Button>
          </Space>
          <Table<OptionPosition>
            rowKey={(position) => contractKey(position)}
            size="small"
            columns={columns}
            dataSource={positions}
            pagination={false}
            onRow={(position) =>
              ({
                'data-testid': 'batch-expire-row',
                'data-contract': position.contractLabel,
              }) as HTMLAttributes<HTMLElement>
            }
            data-testid="batch-expire-table"
          />
          <Typography.Text type="secondary" data-testid="batch-expire-explanation">
            {BATCH_EXPLANATION}
          </Typography.Text>
          {rejection !== null && (
            <Alert
              type="error"
              showIcon
              title={
                <span data-testid="batch-expire-error" data-error-code={rejection.error.code}>
                  {rejection.error.message}
                </span>
              }
              description={
                rejection.unmatched.length > 0 ? (
                  <Space orientation="vertical" size={2}>
                    {rejection.unmatched.map((failure) => (
                      <span
                        key={`${String(failure.index)}-${failure.code}`}
                        data-testid="batch-expire-unmatched-error"
                        data-error-code={failure.code}
                      >
                        {failure.contractLabel}：{failure.message}
                      </span>
                    ))}
                  </Space>
                ) : undefined
              }
            />
          )}
          <Space>
            <Button
              type="primary"
              danger
              disabled={selectedCount === 0}
              loading={mutation.isPending}
              onClick={() => {
                setRejection(null)
                mutation.mutate({
                  rows: selectedRows,
                  items: selectedRows.map(toExpirationItem),
                })
              }}
              data-testid="batch-expire-submit"
            >
              确认作废
            </Button>
            <Button onClick={onDone} data-testid="batch-expire-cancel">
              取消
            </Button>
          </Space>
        </Space>
      )}
    </Modal>
  )
}
