import { Button, Space, Table, Tag, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { OptionPosition, OptionPositionLot } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { SIGNED_MONEY_COLORS, tradeActionLabel } from '../trade/tradeLabels'
import { coverageTags } from './coverageLabels'
import { tradesUrlForPosition } from './positionLinks'

const DIRECTION_TAGS: Record<string, { label: string; color: string }> = {
  SHORT: { label: '空头', color: 'orange' },
  LONG: { label: '多头', color: 'blue' },
}

function SignedMoney({ value, testId }: { value: string; testId: string }) {
  return (
    <span
      data-testid={testId}
      style={{
        color: SIGNED_MONEY_COLORS[decimalSign(value)],
        fontVariantNumeric: 'tabular-nums',
        whiteSpace: 'nowrap',
      }}
    >
      {formatMoney(value, { signed: true })}
    </span>
  )
}

function FrozenMoney({ value, testId }: { value: string; testId: string }) {
  return (
    <span data-testid={testId} style={{ fontVariantNumeric: 'tabular-nums' }}>
      {decimalSign(value) === 0 ? EMPTY_VALUE : formatMoney(value)}
    </span>
  )
}

/**
 * FEAT-039 (Q62): short calls freeze no cash; the cell shows `已覆盖 N 股` (受检) and / or
 * `未覆盖` (旧批次) with hover text, all values from the backend.
 */
function CoverageCell({ position }: { position: OptionPosition }) {
  const tags = coverageTags(position)
  return (
    <span data-testid="position-cell-frozen" style={{ whiteSpace: 'nowrap' }}>
      {tags.length === 0
        ? EMPTY_VALUE
        : tags.map((tag, index) => (
            <Tooltip key={tag.kind} title={tag.hint}>
              <Tag
                color={tag.kind === 'checked' ? 'success' : 'warning'}
                data-testid={`position-cell-coverage-${tag.kind}`}
                style={{
                  cursor: 'help',
                  marginInlineEnd: index === tags.length - 1 ? 0 : undefined,
                }}
              >
                {tag.label}
              </Tag>
            </Tooltip>
          ))}
    </span>
  )
}

/** DTE from the backend: number, "0 今天到期", or red "已过期 N 天" (no client-side date math). */
function DteCell({ position }: { position: OptionPosition }) {
  if (position.expirationStatus === 'EXPIRED') {
    return (
      <Typography.Text type="danger" strong data-testid="position-cell-dte">
        已过期 {position.daysPastExpiration} 天
      </Typography.Text>
    )
  }
  if (position.expirationStatus === 'EXPIRES_TODAY') {
    return (
      <span data-testid="position-cell-dte" style={{ whiteSpace: 'nowrap' }}>
        {position.dte}{' '}
        <Tag color="warning" data-testid="position-cell-dte-today">
          今天到期
        </Tag>
        {position.expiryPending && <ExpiryPendingTag />}
      </span>
    )
  }
  return <span data-testid="position-cell-dte">{position.dte}</span>
}

const EXPIRY_PENDING_HINT = '16:00 已停止交易；17:30 前仍可能被指派，17:30（美东）起可作废'

/**
 * FEAT-012 (Q10 / Q83): 到期待确认 between 16:00 and 17:30 NY on the expiration date, shown from
 * the backend's `expiryPending` (never computed here); meanwhile short rows only have 被指派
 * (FEAT-013 / FEAT-041) and long rows only 行权 (FEAT-014).
 */
function ExpiryPendingTag() {
  return (
    <Tooltip title={EXPIRY_PENDING_HINT}>
      <Tag color="processing" data-testid="position-expiry-pending" style={{ cursor: 'help' }}>
        到期待确认
      </Tag>
    </Tooltip>
  )
}

const lotColumns: ColumnsType<OptionPositionLot> = [
  {
    title: '开仓时间（美东）',
    key: 'tradedAt',
    render: (_value: unknown, lot) => (
      <span data-testid="lot-cell-time" style={{ whiteSpace: 'nowrap' }}>
        {formatDateTimeNY(lot.tradedAt)}
      </span>
    ),
  },
  {
    title: '方向',
    key: 'action',
    render: (_value: unknown, lot) => (
      <span data-testid="lot-cell-action">{tradeActionLabel(lot.action)}</span>
    ),
  },
  {
    title: '剩余 / 原张数',
    key: 'contracts',
    align: 'right',
    render: (_value: unknown, lot) => (
      <span data-testid="lot-cell-contracts">
        {lot.remainingContracts} / {lot.originalContracts}
      </span>
    ),
  },
  {
    title: '剩余开仓净额',
    key: 'remainingNetAmount',
    align: 'right',
    render: (_value: unknown, lot) => (
      <SignedMoney value={lot.remainingNetAmount} testId="lot-cell-net-amount" />
    ),
  },
  {
    title: '冻结',
    key: 'remainingFrozen',
    align: 'right',
    render: (_value: unknown, lot) => (
      <FrozenMoney value={lot.remainingFrozen} testId="lot-cell-frozen" />
    ),
  },
  {
    title: '备注',
    key: 'note',
    render: (_value: unknown, lot) => {
      const note = lot.note?.trim() ? lot.note : null
      return (
        <Typography.Text
          data-testid="lot-cell-note"
          style={{ maxWidth: 240 }}
          ellipsis={note === null ? false : { tooltip: note }}
          title={note ?? undefined}
        >
          {note ?? EMPTY_VALUE}
        </Typography.Text>
      )
    },
  },
]

function LotTable({ lots }: { lots: OptionPositionLot[] }) {
  return (
    <Table<OptionPositionLot>
      rowKey="tradeId"
      size="small"
      columns={lotColumns}
      dataSource={lots}
      pagination={false}
      onRow={(lot) =>
        ({ 'data-testid': 'lot-row', 'data-trade-id': lot.tradeId }) as HTMLAttributes<HTMLElement>
      }
      data-testid="position-lots"
    />
  )
}

function positionColumns(
  onClosePosition: (position: OptionPosition) => void,
  onExpirePosition: (position: OptionPosition) => void,
  onAssignPosition: (position: OptionPosition) => void,
  onExercisePosition: (position: OptionPosition) => void,
): ColumnsType<OptionPosition> {
  return [
    {
      title: '合约',
      key: 'contract',
      render: (_value: unknown, position) => (
        <Link
          to={tradesUrlForPosition(position)}
          data-testid="position-cell-contract"
          style={{ whiteSpace: 'nowrap' }}
        >
          {position.contractLabel}
        </Link>
      ),
    },
    {
      title: '方向',
      key: 'direction',
      render: (_value: unknown, position) => {
        const tag = DIRECTION_TAGS[position.direction]
        return (
          <Tag color={tag?.color} data-testid="position-cell-direction">
            {tag?.label ?? position.direction}
          </Tag>
        )
      },
    },
    {
      title: '张数',
      key: 'contracts',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="position-cell-contracts" style={{ whiteSpace: 'nowrap' }}>
          {position.contracts}
          {position.multiplier !== 100 && `（multiplier ${String(position.multiplier)}）`}
        </span>
      ),
    },
    {
      title: '开仓净额合计',
      key: 'openingNetTotal',
      align: 'right',
      render: (_value: unknown, position) => (
        <SignedMoney value={position.openingNetTotal} testId="position-cell-opening-net" />
      ),
    },
    {
      title: '每张平均净额（仅供参考）',
      key: 'averageNetPerContract',
      align: 'right',
      render: (_value: unknown, position) => (
        <SignedMoney value={position.averageNetPerContract} testId="position-cell-average-net" />
      ),
    },
    {
      title: '冻结现金',
      key: 'frozenCash',
      align: 'right',
      render: (_value: unknown, position) =>
        position.direction === 'SHORT' && position.optionType === 'CALL' ? (
          <CoverageCell position={position} />
        ) : (
          <FrozenMoney value={position.frozenCash} testId="position-cell-frozen" />
        ),
    },
    {
      title: '到期日',
      key: 'expirationDate',
      render: (_value: unknown, position) => (
        <span data-testid="position-cell-expiration">{position.expirationDate}</span>
      ),
    },
    {
      title: 'DTE',
      key: 'dte',
      align: 'right',
      render: (_value: unknown, position) => <DteCell position={position} />,
    },
    {
      title: '批次数',
      key: 'lotCount',
      align: 'right',
      render: (_value: unknown, position) => (
        <span data-testid="position-cell-lot-count">{position.lotCount}</span>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      // Backend flags only: 平仓 before 16:00 NY on the expiration date (`closable`), 作废 from
      // 17:30 (`expirable`, FEAT-012), and 被指派 on short puts / calls at any time (`assignable`,
      // FEAT-013 / FEAT-041 Q95 — the only button while 到期待确认), and 行权 on long calls / puts at
      // any time (`exercisable`, FEAT-014 Q81 — next to 平仓 / 作废, alone while 到期待确认).
      render: (_value: unknown, position) => {
        const exercisable = position.exercisable === true
        if (!position.closable && !position.expirable && !position.assignable && !exercisable) {
          return null
        }
        return (
          <Space size={4} style={{ whiteSpace: 'nowrap' }}>
            {position.closable && (
              <Button
                size="small"
                onClick={() => {
                  onClosePosition(position)
                }}
                data-testid="position-close"
              >
                平仓
              </Button>
            )}
            {position.expirable && (
              <Button
                size="small"
                danger
                onClick={() => {
                  onExpirePosition(position)
                }}
                data-testid="position-expire"
              >
                作废
              </Button>
            )}
            {position.assignable && (
              <Button
                size="small"
                onClick={() => {
                  onAssignPosition(position)
                }}
                data-testid="position-assign"
              >
                被指派
              </Button>
            )}
            {exercisable && (
              <Button
                size="small"
                onClick={() => {
                  onExercisePosition(position)
                }}
                data-testid="position-exercise"
              >
                行权
              </Button>
            )}
          </Space>
        )
      },
    },
  ]
}

interface PositionTableProps {
  positions: OptionPosition[]
  loading: boolean
  empty: ReactNode
  /** Opens the 平仓 dialog for a closable position. */
  onClosePosition: (position: OptionPosition) => void
  /** Opens the 作废 dialog for an expirable position (FEAT-012). */
  onExpirePosition: (position: OptionPosition) => void
  /** Opens the 被指派 dialog for an assignable position (FEAT-013). */
  onAssignPosition: (position: OptionPosition) => void
  /** Opens the 行权 dialog for an exercisable long position (FEAT-014). */
  onExercisePosition: (position: OptionPosition) => void
}

/**
 * Positions in backend order; each row expands to its FIFO lots and has 平仓 (closable),
 * 作废 (expirable, FEAT-012), 被指派 (assignable, FEAT-013) and 行权 (exercisable, FEAT-014) by
 * the backend's flags.
 */
export function PositionTable({
  positions,
  loading,
  empty,
  onClosePosition,
  onExpirePosition,
  onAssignPosition,
  onExercisePosition,
}: PositionTableProps) {
  return (
    <Table<OptionPosition>
      rowKey="contractLabel"
      size="small"
      columns={positionColumns(
        onClosePosition,
        onExpirePosition,
        onAssignPosition,
        onExercisePosition,
      )}
      dataSource={positions}
      loading={loading}
      pagination={false}
      onRow={(position) =>
        ({
          'data-testid': 'position-row',
          'data-contract': position.contractLabel,
        }) as HTMLAttributes<HTMLElement>
      }
      expandable={{
        expandedRowRender: (position) => <LotTable lots={position.lots} />,
        expandIcon: ({ expanded, onExpand, record }) => (
          <Button
            type="text"
            size="small"
            aria-expanded={expanded}
            aria-label={
              expanded
                ? `收起 ${record.contractLabel} 的批次`
                : `展开 ${record.contractLabel} 的批次`
            }
            onClick={(event) => {
              onExpand(record, event)
            }}
            data-testid="position-expand"
          >
            {expanded ? '−' : '+'}
          </Button>
        ),
      }}
      locale={{ emptyText: empty }}
      scroll={{ x: 'max-content' }}
    />
  )
}
