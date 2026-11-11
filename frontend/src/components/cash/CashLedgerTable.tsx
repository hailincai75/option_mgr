import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Card, Pagination, Table, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import type { HTMLAttributes } from 'react'
import { CASH_LEDGER_PAGE_SIZE, cashQueryKeys, getCashLedger } from '../../api/cash'
import type { CashLedgerEntry, CashSourceType } from '../../api/types'
import { decimalSign, EMPTY_VALUE, formatDateTimeNY, formatMoney } from '../../utils/format'
import { QueryError } from '../QueryError'

const SOURCE_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: '入金',
  WITHDRAWAL: '出金',
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

interface CashLedgerTableProps {
  /** 1-based page number (controlled by the parent so a new entry can jump back to page 1). */
  page: number
  onPageChange: (page: number) => void
  pageSize?: number
}

/** Paginated cash ledger (`GET /api/cash/ledger`), newest first. */
export function CashLedgerTable({
  page,
  onPageChange,
  pageSize = CASH_LEDGER_PAGE_SIZE,
}: CashLedgerTableProps) {
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: cashQueryKeys.ledger(page, pageSize),
    queryFn: ({ signal }) => getCashLedger(page, pageSize, signal),
    placeholderData: keepPreviousData,
  })

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
            columns={columns}
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
    </Card>
  )
}
