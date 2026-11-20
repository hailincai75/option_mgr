import { Checkbox, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import type { UnrealizedFields } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { SignedMoney } from '../position/PositionCells'
import { UnrealizedPnlCell } from '../unrealized/UnrealizedCells'
import { tradeActionLabel } from './tradeLabels'

/** One selectable opening lot of a close (FEAT-051, Q258); every value comes from the backend. */
export interface CloseLotOption {
  tradeId: number
  action: string
  tradedAt: string
  contracts: number
  /** Opening net amount, signed by cash direction (STO +, BTO −). */
  netAmount: string
  frozen: string
  /** FEAT-048 lot fields (positions dialog); null = not available (edit form → "—"). */
  unrealized: UnrealizedFields | null
  /** Backend's default lot (rule 3), labelled "FIFO 默认". */
  closeDefault: boolean
}

interface CloseLotPickerProps {
  lots: readonly CloseLotOption[]
  selectedIds: readonly number[]
  /** Receives the new selection in list (opening time) order. */
  onChange: (ids: number[]) => void
  /** Frozen column: short puts only. */
  showFrozen: boolean
  /** `close-trade` (positions dialog) or `trade-edit` (edit form). */
  testIdPrefix: string
  hint: string
}

/**
 * FEAT-051 multi-select list of the opening lots a close can take (non-contiguous allowed). Below
 * it "平仓张数：N" is the plain sum of the ticked lots' contracts — display only, the backend
 * validates and computes everything.
 */
export function CloseLotPicker({
  lots,
  selectedIds,
  onChange,
  showFrozen,
  testIdPrefix,
  hint,
}: CloseLotPickerProps) {
  const id = (suffix: string) => `${testIdPrefix}-${suffix}`
  const selected = new Set(selectedIds)
  const chosen = lots.filter((lot) => selected.has(lot.tradeId))
  const total = chosen.reduce((sum, lot) => sum + lot.contracts, 0)

  const toggle = (tradeId: number, checked: boolean) => {
    const next = new Set(selected)
    if (checked) next.add(tradeId)
    else next.delete(tradeId)
    onChange(lots.filter((lot) => next.has(lot.tradeId)).map((lot) => lot.tradeId))
  }

  const columns: ColumnsType<CloseLotOption> = [
    {
      title: '平掉',
      key: 'select',
      width: 56,
      render: (_value: unknown, lot) => (
        <Checkbox
          checked={selected.has(lot.tradeId)}
          onChange={(event) => {
            toggle(lot.tradeId, event.target.checked)
          }}
          aria-label={`平掉 ${formatDateTimeNY(lot.tradedAt)} 的批次`}
          data-testid={id('lot-checkbox')}
        />
      ),
    },
    {
      title: '开仓时间（美东）',
      key: 'time',
      render: (_value: unknown, lot) => (
        <Space size={4} wrap={false}>
          <span data-testid={id('lot-time')} style={{ whiteSpace: 'nowrap' }}>
            {formatDateTimeNY(lot.tradedAt)}
          </span>
          {lot.closeDefault && (
            <Tag color="blue" data-testid={id('lot-fifo-default')} style={{ marginInlineEnd: 0 }}>
              FIFO 默认
            </Tag>
          )}
        </Space>
      ),
    },
    {
      title: '方向',
      key: 'action',
      render: (_value: unknown, lot) => (
        <span data-testid={id('lot-action')}>{tradeActionLabel(lot.action)}</span>
      ),
    },
    {
      title: '张数',
      key: 'contracts',
      align: 'right',
      render: (_value: unknown, lot) => (
        <span data-testid={id('lot-contracts')} style={{ whiteSpace: 'nowrap' }}>
          {lot.contracts} 张
        </span>
      ),
    },
    {
      title: '开仓净额',
      key: 'netAmount',
      align: 'right',
      render: (_value: unknown, lot) => (
        <SignedMoney value={lot.netAmount} testId={id('lot-net-amount')} />
      ),
    },
    ...(showFrozen
      ? [
          {
            title: '冻结',
            key: 'frozen',
            align: 'right' as const,
            render: (_value: unknown, lot: CloseLotOption) => (
              <span data-testid={id('lot-frozen')} style={{ fontVariantNumeric: 'tabular-nums' }}>
                {decimalSign(lot.frozen) === 0 ? EMPTY_VALUE : formatMoney(lot.frozen)}
              </span>
            ),
          },
        ]
      : []),
    {
      title: '未实现盈亏',
      key: 'unrealizedPnl',
      align: 'right',
      render: (_value: unknown, lot) =>
        lot.unrealized === null ? (
          <span data-testid={id('lot-unrealized-pnl')}>{EMPTY_VALUE}</span>
        ) : (
          <UnrealizedPnlCell row={lot.unrealized} testIdPrefix={id('lot')} />
        ),
    },
  ]

  return (
    <div data-testid={id('lots')}>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        <span data-testid={id('lots-hint')}>{hint}</span>
      </Typography.Paragraph>
      <Table<CloseLotOption>
        rowKey="tradeId"
        size="small"
        pagination={false}
        dataSource={[...lots]}
        columns={columns}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: <span data-testid={id('lots-empty')}>没有可选的开仓批次</span> }}
        onRow={(lot, index) =>
          ({
            'data-testid': id('lot-row'),
            'data-trade-id': lot.tradeId,
            'data-lot-index': (index ?? 0) + 1,
            'data-selected': selected.has(lot.tradeId) ? 'true' : 'false',
          }) as HTMLAttributes<HTMLElement>
        }
      />
      <div style={{ marginTop: 8 }}>
        <strong data-testid={id('lots-total')}>平仓张数：{total}</strong>
        {chosen.length === 0 && (
          <Typography.Text type="warning" style={{ marginInlineStart: 8 }}>
            <span data-testid={id('lots-none-selected')}>请至少选择一笔开仓批次</span>
          </Typography.Text>
        )}
      </div>
    </div>
  )
}
