import { Button, Col, Descriptions, Row, Table, Tag } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes, ReactNode } from 'react'
import type { AuditRecord, AuditSnapshot } from '../../api/types'
import { EMPTY_VALUE, formatDateTimeSecondsNY, formatMoney } from '../../utils/format'
import { tradeActionLabel } from '../trade/tradeLabels'

type FieldFormat = 'text' | 'money' | 'signedMoney' | 'localTime' | 'action' | 'transferType'

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
  CASH_TRANSFER: [
    { key: 'type', label: '类型', format: 'transferType' },
    { key: 'amount', label: '金额', format: 'money' },
    { key: 'occurredAtLocal', label: '时间（美东）', format: 'localTime' },
    { key: 'remark', label: '备注', format: 'text' },
  ],
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
    case 'transferType':
      return text === 'DEPOSIT' ? '入金' : text === 'WITHDRAWAL' ? '出金' : text
    case 'text':
      return text
  }
}

/** "期权交易 #2" / "入金 #1" / "出金 #3" (transfer kind from the snapshot before the change). */
function objectLabel(record: AuditRecord): string {
  if (record.objectType === 'OPTION_TRADE') return `期权交易 #${String(record.objectId)}`
  if (record.objectType === 'CASH_TRANSFER') {
    const kind = record.before.type === 'WITHDRAWAL' ? '出金' : '入金'
    return `${kind} #${String(record.objectId)}`
  }
  return `${record.objectType} #${String(record.objectId)}`
}

function Snapshot({
  title,
  snapshot,
  objectType,
  testId,
}: {
  title: string
  snapshot: AuditSnapshot | null
  objectType: string
  testId: string
}) {
  const fields =
    SNAPSHOT_FIELDS[objectType] ??
    Object.keys(snapshot ?? {}).map((key) => ({ key, label: key, format: 'text' as const }))
  return (
    <Descriptions
      title={title}
      column={1}
      size="small"
      bordered
      data-testid={testId}
      items={
        snapshot === null
          ? [{ key: 'deleted', label: '状态', children: '已删除' }]
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
        color={record.operation === 'DELETE' ? 'red' : 'blue'}
        data-testid="audit-cell-operation"
      >
        {record.operation === 'DELETE'
          ? '删除'
          : record.operation === 'UPDATE'
            ? '修改'
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
              />
            </Col>
            <Col xs={24} md={12}>
              <Snapshot
                title={record.operation === 'DELETE' ? '修改后（已删除）' : '修改后'}
                snapshot={record.after}
                objectType={record.objectType}
                testId="audit-after"
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
