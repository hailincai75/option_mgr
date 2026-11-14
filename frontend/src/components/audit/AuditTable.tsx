import { Button, Col, Descriptions, Row, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import type { AuditRecord, AuditSnapshot } from '../../api/types'
import { EMPTY_VALUE, formatDateTimeSecondsNY, formatMoney } from '../../utils/format'
import { stockTypeLabel } from '../stock/stockLabels'
import { tradeActionLabel } from '../trade/tradeLabels'

type FieldFormat =
  | 'text'
  | 'money'
  | 'signedMoney'
  | 'localTime'
  | 'action'
  | 'transferType'
  | 'stockType'

/** Fields of the before / after snapshots shown when a row is expanded (contract field list). */
const SNAPSHOT_FIELDS: Record<string, { key: string; label: string; format: FieldFormat }[]> = {
  OPTION_TRADE: [
    { key: 'contractLabel', label: '合约', format: 'text' },
    { key: 'action', label: '方向', format: 'action' },
    { key: 'contracts', label: '张数', format: 'text' },
    { key: 'multiplier', label: 'Multiplier', format: 'text' },
    { key: 'netAmount', label: '净额', format: 'money' },
    { key: 'tradedAtLocal', label: '成交时间（美东）', format: 'localTime' },
    { key: 'note', label: '备注', format: 'text' },
    { key: 'cashChange', label: '现金变动', format: 'signedMoney' },
    { key: 'frozenChange', label: '冻结变动', format: 'signedMoney' },
  ],
  ACCOUNT: [
    { key: 'name', label: '名称', format: 'text' },
    { key: 'brokerNote', label: '券商备注', format: 'text' },
  ],
  CASH_TRANSFER: [
    { key: 'type', label: '类型', format: 'transferType' },
    { key: 'amount', label: '金额', format: 'money' },
    { key: 'occurredAtLocal', label: '时间（美东）', format: 'localTime' },
    { key: 'remark', label: '备注', format: 'text' },
  ],
}

interface SnapshotField {
  key: string
  label: string
  format: FieldFormat
}

/** FEAT-013: extra flat fields of a 被指派 (ASG) snapshot (contract: audit `before`). */
const ASSIGNMENT_FIELDS: SnapshotField[] = [
  { key: 'assignmentDate', label: '指派日期', format: 'text' },
  { key: 'assignmentFee', label: '指派费', format: 'money' },
  { key: 'shares', label: '股数', format: 'text' },
  { key: 'purchaseAmount', label: '买股金额', format: 'money' },
  { key: 'stockCost', label: '股票成本', format: 'money' },
  { key: 'stockTradeId', label: '股票记录 ID', format: 'text' },
]

/** FEAT-041: extra flat fields of a call 被指派 snapshot (no purchaseAmount / stockCost). */
const CALL_ASSIGNMENT_FIELDS: SnapshotField[] = [
  { key: 'assignmentDate', label: '指派日期', format: 'text' },
  { key: 'assignmentFee', label: '指派费', format: 'money' },
  { key: 'shares', label: '股数', format: 'text' },
  { key: 'premium', label: '权利金', format: 'money' },
  { key: 'saleAmount', label: '行权金额', format: 'money' },
  { key: 'proceeds', label: '卖出实收', format: 'money' },
  { key: 'soldCost', label: '交出成本', format: 'money' },
  { key: 'stockRealizedPnl', label: '股票已实现盈亏', format: 'signedMoney' },
  { key: 'stockTradeId', label: '股票记录 ID', format: 'text' },
]

/** FEAT-014: extra flat fields of a call 行权 (EXR) snapshot (contract: audit `before`). */
const CALL_EXERCISE_FIELDS: SnapshotField[] = [
  { key: 'exerciseDate', label: '行权日期', format: 'text' },
  { key: 'exerciseFee', label: '行权费', format: 'money' },
  { key: 'shares', label: '股数', format: 'text' },
  { key: 'premium', label: '权利金', format: 'money' },
  { key: 'purchaseAmount', label: '买股金额', format: 'money' },
  { key: 'stockCost', label: '股票成本', format: 'money' },
  { key: 'stockTradeId', label: '股票记录 ID', format: 'text' },
]

/** FEAT-014: extra flat fields of a put 行权 (EXR) snapshot. */
const PUT_EXERCISE_FIELDS: SnapshotField[] = [
  { key: 'exerciseDate', label: '行权日期', format: 'text' },
  { key: 'exerciseFee', label: '行权费', format: 'money' },
  { key: 'shares', label: '股数', format: 'text' },
  { key: 'premium', label: '权利金', format: 'money' },
  { key: 'saleAmount', label: '行权金额', format: 'money' },
  { key: 'proceeds', label: '卖出实收', format: 'money' },
  { key: 'soldCost', label: '交出成本', format: 'money' },
  { key: 'stockRealizedPnl', label: '股票已实现盈亏', format: 'signedMoney' },
  { key: 'stockTradeId', label: '股票记录 ID', format: 'text' },
]

/**
 * Option trade snapshot; a 被指派 also shows its assignment fields: put (FEAT-013) or call
 * (FEAT-041, by the snapshot's `optionType`); a 行权 (FEAT-014) its exercise fields.
 */
function optionTradeFields(snapshot: AuditSnapshot | null): SnapshotField[] {
  const base = SNAPSHOT_FIELDS.OPTION_TRADE ?? []
  if (snapshot?.action === 'EXR') {
    const isCall = snapshot.optionType === 'CALL'
    return [...base, ...(isCall ? CALL_EXERCISE_FIELDS : PUT_EXERCISE_FIELDS)]
  }
  if (snapshot?.action !== 'ASG') return base
  const isCall = snapshot.optionType === 'CALL' || 'saleAmount' in snapshot
  return [...base, ...(isCall ? CALL_ASSIGNMENT_FIELDS : ASSIGNMENT_FIELDS)]
}

/**
 * FEAT-011 stock trade snapshot: for transfers the amount is 总成本 and the time is a date,
 * otherwise 金额 and the New York trade time.
 */
function stockTradeFields(snapshot: AuditSnapshot | null): SnapshotField[] {
  const transfer = snapshot?.type === 'TRANSFER_IN'
  return [
    { key: 'type', label: '类型', format: 'stockType' },
    { key: 'symbol', label: '标的', format: 'text' },
    { key: 'shares', label: '股数', format: 'text' },
    { key: 'amount', label: transfer ? '总成本' : '金额', format: 'money' },
    transfer
      ? { key: 'tradeDate', label: '日期', format: 'text' }
      : { key: 'tradedAtLocal', label: '时间（美东）', format: 'localTime' },
    { key: 'note', label: '备注', format: 'text' },
    { key: 'cashChange', label: '现金变动', format: 'signedMoney' },
  ]
}

function formatValue(value: string | number | null | undefined, format: FieldFormat): string {
  if (value === null || value === undefined || value === '') return EMPTY_VALUE
  const text = String(value)
  switch (format) {
    case 'money':
      return formatMoney(text)
    case 'signedMoney':
      return formatMoney(text, { signed: true })
    case 'localTime':
      // "2026-11-04T11:00:00" → "2026-11-04 11:00" (already New York local time).
      return text.replace('T', ' ').slice(0, 16)
    case 'action':
      return tradeActionLabel(text)
    case 'stockType':
      return stockTypeLabel(text)
    case 'transferType':
      return text === 'DEPOSIT' ? '入金' : text === 'WITHDRAWAL' ? '出金' : text
    case 'text':
      return text
  }
}

/**
 * "期权交易 #2" / "入金 #1" / "出金 #3" (transfer kind from the snapshot before the change) /
 * "账户 #1" (FEAT-033) / "股票交易 #12" (FEAT-011).
 */
function objectLabel(record: AuditRecord): string {
  if (record.objectType === 'ACCOUNT') return `账户 #${String(record.objectId)}`
  if (record.objectType === 'OPTION_TRADE') return `期权交易 #${String(record.objectId)}`
  if (record.objectType === 'STOCK_TRADE') return `股票交易 #${String(record.objectId)}`
  if (record.objectType === 'CASH_TRANSFER') {
    const kind = (record.before ?? record.after)?.type === 'WITHDRAWAL' ? '出金' : '入金'
    return `${kind} #${String(record.objectId)}`
  }
  return `${record.objectType} #${String(record.objectId)}`
}

function Snapshot({
  title,
  snapshot,
  objectType,
  testId,
  emptyText,
}: {
  title: string
  snapshot: AuditSnapshot | null
  objectType: string
  testId: string
  /** Shown when the snapshot is null: "已删除" (after a delete) or "无（新增）" (before a create). */
  emptyText: string
}) {
  const known =
    objectType === 'STOCK_TRADE'
      ? stockTradeFields(snapshot)
      : objectType === 'OPTION_TRADE'
        ? optionTradeFields(snapshot)
        : SNAPSHOT_FIELDS[objectType]
  const fields =
    known ?? Object.keys(snapshot ?? {}).map((key) => ({ key, label: key, format: 'text' as const }))
  return (
    <Descriptions
      title={title}
      column={1}
      size="small"
      bordered
      data-testid={testId}
      items={
        snapshot === null
          ? [{ key: 'empty', label: '状态', children: emptyText }]
          : fields.map((field) => ({
              key: field.key,
              label: field.label,
              children: (
                <span data-testid={`${testId}-${field.key}`}>
                  {formatValue(snapshot[field.key], field.format)}
                </span>
              ),
            }))
      }
    />
  )
}

function lines(texts: string[], testId: string): ReactNode {
  if (texts.length === 0) return <span data-testid={testId}>{EMPTY_VALUE}</span>
  return (
    <div data-testid={testId}>
      {texts.map((text, index) => (
        <div key={index} data-testid={`${testId}-line`}>
          {text}
        </div>
      ))}
    </div>
  )
}

const columns: ColumnsType<AuditRecord> = [
  {
    title: '操作时间（美东）',
    key: 'operatedAt',
    render: (_value: unknown, record) => (
      <span data-testid="audit-cell-time" style={{ whiteSpace: 'nowrap' }}>
        {formatDateTimeSecondsNY(record.operatedAt)}
      </span>
    ),
  },
  {
    title: '操作',
    key: 'operation',
    render: (_value: unknown, record) => (
      <Tag
        color={
          record.operation === 'DELETE' ? 'red' : record.operation === 'CREATE' ? 'green' : 'blue'
        }
        data-testid="audit-cell-operation"
      >
        {record.operation === 'DELETE'
          ? '删除'
          : record.operation === 'UPDATE'
            ? '修改'
            : record.operation === 'CREATE'
              ? '新增'
              : record.operation}
      </Tag>
    ),
  },
  {
    title: '对象',
    key: 'object',
    render: (_value: unknown, record) => (
      <span data-testid="audit-cell-object" style={{ whiteSpace: 'nowrap' }}>
        {objectLabel(record)}
      </span>
    ),
  },
  {
    title: '摘要',
    key: 'summary',
    render: (_value: unknown, record) => (
      <span data-testid="audit-cell-summary">{record.summary}</span>
    ),
  },
  {
    title: '改动',
    key: 'changes',
    render: (_value: unknown, record) =>
      record.operation === 'DELETE' ? (
        <span data-testid="audit-cell-changes">已删除</span>
      ) : record.operation === 'CREATE' ? (
        <span data-testid="audit-cell-changes">已新增</span>
      ) : (
        lines(
          record.changes.map((change) => change.text),
          'audit-cell-changes',
        )
      ),
  },
  {
    title: '连带变化',
    key: 'sideEffects',
    render: (_value: unknown, record) =>
      lines(
        record.sideEffects.map((effect) => effect.text),
        'audit-cell-side-effects',
      ),
  },
]

interface AuditTableProps {
  records: AuditRecord[]
  loading: boolean
  empty: ReactNode
}

/** Read-only audit records in backend order; each row expands to the full before / after. */
export function AuditTable({ records, loading, empty }: AuditTableProps) {
  return (
    <Table<AuditRecord>
      rowKey="id"
      size="small"
      columns={columns}
      dataSource={records}
      loading={loading}
      pagination={false}
      onRow={(record) =>
        ({ 'data-testid': 'audit-row', 'data-audit-id': record.id }) as HTMLAttributes<HTMLElement>
      }
      expandable={{
        expandedRowRender: (record) => (
          <Row gutter={16} data-testid="audit-detail">
            <Col xs={24} md={12}>
              <Snapshot
                title="修改前"
                snapshot={record.before}
                objectType={record.objectType}
                testId="audit-before"
                emptyText="无（新增）"
              />
            </Col>
            <Col xs={24} md={12}>
              <Snapshot
                title={record.operation === 'DELETE' ? '修改后（已删除）' : '修改后'}
                snapshot={record.after}
                objectType={record.objectType}
                testId="audit-after"
                emptyText="已删除"
              />
            </Col>
          </Row>
        ),
        expandIcon: ({ expanded, onExpand, record }) => (
          <Button
            type="text"
            size="small"
            aria-expanded={expanded}
            aria-label={expanded ? '收起修改前后内容' : '展开修改前后内容'}
            onClick={(event) => {
              onExpand(record, event)
            }}
            data-testid="audit-expand"
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
