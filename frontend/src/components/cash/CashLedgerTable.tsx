import { useAccountQuery } from '../../account/useAccountQuery'
import { Button, Card, Pagination, Space, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { type HTMLAttributes, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  CASH_LEDGER_PAGE_SIZE,
  cashQueryKeys,
  deleteCashTransfer,
  getCashLedger,
} from '../../api/cash'
import { auditUrl } from '../audit/auditLinks'
import { DeleteConfirmModal } from '../DeleteConfirmModal'
import { EditTransferModal } from './EditTransferModal'
import type { CashLedgerEntry, CashSourceType } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { QueryError } from '../QueryError'

const SOURCE_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: '入金',
  WITHDRAWAL: '出金',
  OPTION_OPEN: '期权开仓',
  OPTION_CLOSE: '期权平仓',
  OPTION_EXPIRE: '到期作废',
}

/** Known source types get a Chinese label; unknown ones (added by later features) are shown as-is. */
function sourceTypeLabel(sourceType: CashSourceType): string {
  return SOURCE_TYPE_LABELS[sourceType] ?? sourceType
}

const AMOUNT_COLORS = { 1: '#389e0d', [-1]: '#cf1322', 0: undefined } as const

const columns: ColumnsType<CashLedgerEntry> = [
  {
    title: '时间（美东）',
    dataIndex: 'occurredAt',
    key: 'occurredAt',
    render: (value: string) => (
      <span data-testid="cash-ledger-time">{formatDateTimeNY(value)}</span>
    ),
  },
  {
    title: '类型',
    dataIndex: 'sourceType',
    key: 'sourceType',
    render: (value: CashSourceType) => (
      <span data-testid="cash-ledger-type">{sourceTypeLabel(value)}</span>
    ),
  },
  {
    title: '金额',
    dataIndex: 'amount',
    key: 'amount',
    align: 'right',
    render: (value: string) => (
      <span
        data-testid="cash-ledger-amount"
        style={{ color: AMOUNT_COLORS[decimalSign(value)], fontVariantNumeric: 'tabular-nums' }}
      >
        {formatMoney(value, { signed: true })}
      </span>
    ),
  },
  {
    title: '余额',
    dataIndex: 'balanceAfter',
    key: 'balanceAfter',
    align: 'right',
    render: (value: string) => (
      <span data-testid="cash-ledger-balance" style={{ fontVariantNumeric: 'tabular-nums' }}>
        {formatMoney(value)}
      </span>
    ),
  },
  {
    title: '冻结变动',
    dataIndex: 'frozenChange',
    key: 'frozenChange',
    align: 'right',
    // Zero (deposits, withdrawals, BTO) is shown as a dash to keep the column quiet.
    render: (value: string | undefined) => (
      <span data-testid="cash-ledger-frozen" style={{ fontVariantNumeric: 'tabular-nums' }}>
        {decimalSign(value) === 0 ? EMPTY_VALUE : formatMoney(value, { signed: true })}
      </span>
    ),
  },
  {
    title: '说明 / 备注',
    key: 'note',
    render: (_value: unknown, entry: CashLedgerEntry) => {
      const parts = [entry.description, entry.remark].filter(
        (part): part is string => part != null && part.trim() !== '',
      )
      return (
        <span data-testid="cash-ledger-note" style={{ whiteSpace: 'pre-wrap' }}>
          {parts.length > 0 ? parts.join(' — ') : EMPTY_VALUE}
        </span>
      )
    },
  },
]

/** Only deposits / withdrawals are edited here; option rows are edited in the trade list. */
function isTransfer(entry: CashLedgerEntry): boolean {
  return entry.sourceType === 'DEPOSIT' || entry.sourceType === 'WITHDRAWAL'
}

/** e.g. "入金 +80,000.00，2026-10-26 10:00". */
function transferSummary(entry: CashLedgerEntry): string {
  const amount = formatMoney(entry.amount, { signed: true })
  return `${sourceTypeLabel(entry.sourceType)} ${amount}，${formatDateTimeNY(entry.occurredAt)}`
}

function actionsColumn(
  onEdit: (entry: CashLedgerEntry) => void,
  onDelete: (entry: CashLedgerEntry) => void,
): ColumnsType<CashLedgerEntry>[number] {
  return {
    title: '操作',
    key: 'actions',
    render: (_value: unknown, entry: CashLedgerEntry) =>
      isTransfer(entry) ? (
        <Space size={4} style={{ whiteSpace: 'nowrap' }}>
          <Button
            size="small"
            type="link"
            onClick={() => {
              onEdit(entry)
            }}
            data-testid="cash-ledger-edit"
          >
            修改
          </Button>
          <Button
            size="small"
            type="link"
            danger
            onClick={() => {
              onDelete(entry)
            }}
            data-testid="cash-ledger-delete"
          >
            删除
          </Button>
          {entry.hasAuditLog && (
            <Link to={auditUrl('CASH_TRANSFER', entry.sourceId)} data-testid="cash-ledger-history">
              历史
            </Link>
          )}
        </Space>
      ) : (
        <Link to="/trades" data-testid="cash-ledger-trades-link">
          在交易列表中修改
        </Link>
      ),
  }
}

interface CashLedgerTableProps {
  /** 1-based page number (controlled by the parent so a new entry can jump back to page 1). */
  page: number
  onPageChange: (page: number) => void
  pageSize?: number
}

/**
 * Paginated cash ledger (`GET /api/cash/ledger`), newest first. Deposit / withdrawal rows have
 * 修改 / 删除 / 历史 (FEAT-005).
 */
export function CashLedgerTable({
  page,
  onPageChange,
  pageSize = CASH_LEDGER_PAGE_SIZE,
}: CashLedgerTableProps) {
  const { data, error, isPending, isFetching, refetch } = useAccountQuery({
    // FEAT-033: per-account key; runs only once the current account is known.
    queryKey: cashQueryKeys.ledger(page, pageSize),
    queryFn: ({ signal }) => getCashLedger(page, pageSize, signal),
    keepPrevious: true,
  })
  const [editing, setEditing] = useState<CashLedgerEntry | null>(null)
  const [deleting, setDeleting] = useState<CashLedgerEntry | null>(null)

  return (
    <Card title="现金流水" data-testid="cash-ledger">
      {error && !isFetching ? (
        <QueryError
          title="无法加载现金流水"
          error={error}
          retrying={isFetching}
          onRetry={() => void refetch()}
          testIdPrefix="cash-ledger"
        />
      ) : (
        <>
          <Table<CashLedgerEntry>
            rowKey="id"
            size="small"
            columns={[...columns, actionsColumn(setEditing, setDeleting)]}
            dataSource={data?.items ?? []}
            loading={isPending || isFetching}
            pagination={false}
            onRow={() => ({ 'data-testid': 'cash-ledger-row' }) as HTMLAttributes<HTMLElement>}
            locale={{
              emptyText: isPending ? ' ' : <span data-testid="cash-ledger-empty">暂无流水</span>,
            }}
            scroll={{ x: 'max-content' }}
          />
          {data && data.totalItems > 0 && (
            <div
              data-testid="cash-ledger-pagination"
              style={{
                display: 'flex',
                justifyContent: 'flex-end',
                alignItems: 'center',
                gap: 16,
                marginTop: 16,
              }}
            >
              <Typography.Text type="secondary" data-testid="cash-ledger-total">
                共 {data.totalItems} 条
              </Typography.Text>
              <Pagination
                current={page}
                pageSize={pageSize}
                total={data.totalItems}
                showSizeChanger={false}
                onChange={onPageChange}
              />
            </div>
          )}
        </>
      )}
      {editing !== null && (
        <EditTransferModal
          key={editing.id}
          entry={editing}
          onDone={() => {
            setEditing(null)
          }}
        />
      )}
      {deleting !== null && (
        <DeleteConfirmModal
          key={deleting.id}
          title={`删除${sourceTypeLabel(deleting.sourceType)} #${String(deleting.sourceId)}`}
          summary={transferSummary(deleting)}
          onConfirm={() => deleteCashTransfer(deleting.sourceId)}
          onDone={() => {
            setDeleting(null)
          }}
          testIdPrefix="transfer-delete"
        />
      )}
    </Card>
  )
}
