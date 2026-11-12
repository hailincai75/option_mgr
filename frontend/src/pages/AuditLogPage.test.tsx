import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import { useLocation } from 'react-router-dom'
import type { AuditLogPage, AuditRecord } from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { AuditLogPage as AuditLogPageComponent } from './AuditLogPage'

const tradeBefore = {
  id: 2,
  symbol: 'AAPL',
  expirationDate: '2026-12-18',
  strike: '200',
  optionType: 'PUT',
  contractLabel: 'AAPL 2026-12-18 200 P',
  action: 'STO',
  contracts: 1,
  multiplier: 100,
  netAmount: '360.00',
  tradedAt: '2026-11-04T16:00:00Z',
  tradedAtLocal: '2026-11-04T11:00:00',
  note: null,
  cashChange: '360.00',
  frozenChange: '20000.00',
}

/** AC 12: newest first — delete #2, note edit #2, net edit #2; plus a side effect and a transfer. */
const DELETE_L2: AuditRecord = {
  id: 3,
  operatedAt: '2026-11-11T15:02:00Z',
  operation: 'DELETE',
  objectType: 'OPTION_TRADE',
  objectId: 2,
  summary: 'STO AAPL 2026-12-18 200 P',
  before: { ...tradeBefore, netAmount: '365.00', cashChange: '365.00', note: '加仓' },
  after: null,
  changes: [],
  sideEffects: [],
}
const NOTE_L2: AuditRecord = {
  id: 2,
  operatedAt: '2026-11-11T15:01:00Z',
  operation: 'UPDATE',
  objectType: 'OPTION_TRADE',
  objectId: 2,
  summary: 'STO AAPL 2026-12-18 200 P',
  before: { ...tradeBefore, netAmount: '365.00', cashChange: '365.00' },
  after: { ...tradeBefore, netAmount: '365.00', cashChange: '365.00', note: '加仓' },
  changes: [{ field: 'note', label: '备注', before: '—', after: '加仓', text: '备注：— → 加仓' }],
  sideEffects: [],
}
const NET_L2: AuditRecord = {
  id: 1,
  operatedAt: '2026-11-11T15:00:05Z',
  operation: 'UPDATE',
  objectType: 'OPTION_TRADE',
  objectId: 2,
  summary: 'STO AAPL 2026-12-18 200 P',
  before: tradeBefore,
  after: { ...tradeBefore, netAmount: '365.00', cashChange: '365.00' },
  changes: [
    {
      field: 'netAmount',
      label: '净额',
      before: '360.00',
      after: '365.00',
      text: '净额：360.00 → 365.00',
    },
  ],
  sideEffects: [],
}
const SIDE_EFFECT: AuditRecord = {
  ...NET_L2,
  id: 4,
  objectId: 1,
  summary: 'STO F 2026-12-18 12.345 P',
  changes: [
    { field: 'contracts', label: '张数', before: '1', after: '2', text: '张数：1 → 2' },
    {
      field: 'frozenChange',
      label: '冻结变动',
      before: '+12.35',
      after: '+24.69',
      text: '冻结变动：+12.35 → +24.69',
    },
  ],
  sideEffects: [
    {
      tradeId: 3,
      action: 'BTC',
      contractLabel: 'F 2026-12-18 12.345 P',
      field: 'frozenChange',
      before: '−24.70',
      after: '−24.69',
      text: '交易 #3 BTC 冻结变动 −24.70 → −24.69',
    },
  ],
}
const TRANSFER: AuditRecord = {
  id: 5,
  operatedAt: '2026-11-11T15:03:00Z',
  operation: 'UPDATE',
  objectType: 'CASH_TRANSFER',
  objectId: 2,
  summary: '入金 500.00',
  before: {
    id: 2,
    type: 'DEPOSIT',
    amount: '500.00',
    occurredAt: '2026-11-03T15:00:00Z',
    occurredAtLocal: '2026-11-03T10:00:00',
    remark: null,
  },
  after: {
    id: 2,
    type: 'WITHDRAWAL',
    amount: '500.00',
    occurredAt: '2026-11-03T15:00:00Z',
    occurredAtLocal: '2026-11-03T10:00:00',
    remark: null,
  },
  changes: [
    { field: 'type', label: '类型', before: '入金', after: '出金', text: '类型：入金 → 出金' },
  ],
  sideEffects: [],
}

function LocationProbe() {
  const location = useLocation()
  return <span data-testid="location">{location.pathname + location.search}</span>
}

function startFakeServer(records: AuditRecord[]) {
  const queries: URLSearchParams[] = []
  mockFetch().mockImplementation((input) => {
    const url = typeof input === 'string' ? input : ''
    const parsed = new URL(url, 'http://localhost')
    if (parsed.pathname !== '/api/audit-log') {
      return Promise.reject(new Error(`Unexpected request ${url}`))
    }
    const query = parsed.searchParams
    queries.push(query)
    const filtered = records.filter(
      (record) =>
        (!query.get('objectType') || record.objectType === query.get('objectType')) &&
        (!query.get('objectId') || record.objectId === Number(query.get('objectId'))),
    )
    const pageNumber = Number(query.get('page'))
    const size = Number(query.get('size'))
    const body: AuditLogPage = {
      items: filtered.slice((pageNumber - 1) * size, pageNumber * size),
      page: pageNumber,
      size,
      totalItems: filtered.length,
      totalPages: Math.ceil(filtered.length / size),
    }
    return Promise.resolve(jsonResponse(body))
  })
  return queries
}

function renderPage(route = '/audit') {
  return renderWithProviders(
    <>
      <AuditLogPageComponent />
      <LocationProbe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function rowTexts(row: HTMLElement): string[] {
  const cell = (id: string) => within(row).getByTestId(id).textContent
  return [
    cell('audit-cell-time'),
    cell('audit-cell-operation'),
    cell('audit-cell-object'),
    cell('audit-cell-summary'),
    cell('audit-cell-changes'),
    cell('audit-cell-side-effects'),
  ]
}

describe('AuditLogPage', () => {
  it('lists records in backend order with operation, object, summary and changes (AC 12)', async () => {
    startFakeServer([DELETE_L2, NOTE_L2, NET_L2])
    renderPage()

    const rows = await screen.findAllByTestId('audit-row')
    expect(rows.map(rowTexts)).toEqual([
      ['2026-11-11 10:02:00', '删除', '期权交易 #2', 'STO AAPL 2026-12-18 200 P', '已删除', '—'],
      [
        '2026-11-11 10:01:00',
        '修改',
        '期权交易 #2',
        'STO AAPL 2026-12-18 200 P',
        '备注：— → 加仓',
        '—',
      ],
      [
        '2026-11-11 10:00:05',
        '修改',
        '期权交易 #2',
        'STO AAPL 2026-12-18 200 P',
        '净额：360.00 → 365.00',
        '—',
      ],
    ])
    expect(byTestId('audit-total')).toHaveTextContent('共 3 条')
    // Read-only: only expand toggles, no edit / delete buttons in the table.
    const tableButtons = [...byTestId('audit-page').querySelectorAll('table button')]
    expect(tableButtons.every((b) => b.getAttribute('data-testid') === 'audit-expand')).toBe(true)
  })

  it('shows several changes and side effects line by line (AC 10)', async () => {
    startFakeServer([SIDE_EFFECT])
    renderPage()
    const row = (await screen.findAllByTestId('audit-row'))[0] as HTMLElement
    expect(
      within(row)
        .getAllByTestId('audit-cell-changes-line')
        .map((line) => line.textContent),
    ).toEqual(['张数：1 → 2', '冻结变动：+12.35 → +24.69'])
    expect(within(row).getByTestId('audit-cell-side-effects')).toHaveTextContent(
      '交易 #3 BTC 冻结变动 −24.70 → −24.69',
    )
  })

  it('expands a row into the full before / after (AC 1, 6)', async () => {
    startFakeServer([DELETE_L2, NET_L2])
    renderPage()
    const rows = await screen.findAllByTestId('audit-row')

    await userEvent.click(within(rows[1] as HTMLElement).getByTestId('audit-expand'))
    let detail = await screen.findByTestId('audit-detail')
    expect(within(detail).getByTestId('audit-before-netAmount')).toHaveTextContent('360.00')
    expect(within(detail).getByTestId('audit-after-netAmount')).toHaveTextContent('365.00')
    expect(within(detail).getByTestId('audit-before-contractLabel')).toHaveTextContent(
      'AAPL 2026-12-18 200 P',
    )
    expect(within(detail).getByTestId('audit-before-tradedAtLocal')).toHaveTextContent(
      '2026-11-04 11:00',
    )
    expect(within(detail).getByTestId('audit-before-frozenChange')).toHaveTextContent('+20,000.00')
    expect(within(detail).getByTestId('audit-before-note')).toHaveTextContent('—')
    await userEvent.click(within(rows[1] as HTMLElement).getByTestId('audit-expand'))

    await userEvent.click(within(rows[0] as HTMLElement).getByTestId('audit-expand'))
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-detail').length).toBeGreaterThan(0)
    })
    detail = screen.getAllByTestId('audit-detail')[0] as HTMLElement
    expect(within(detail).getByTestId('audit-before-action')).toHaveTextContent('STO（卖出开仓）')
    expect(within(detail).getByTestId('audit-before-cashChange')).toHaveTextContent('+365.00')
    expect(within(detail).getByTestId('audit-after')).toHaveTextContent('已删除')
  })

  it('labels transfers by kind and shows their fields (AC 9)', async () => {
    startFakeServer([TRANSFER])
    renderPage()
    const row = (await screen.findAllByTestId('audit-row'))[0] as HTMLElement
    expect(rowTexts(row).slice(2, 5)).toEqual(['入金 #2', '入金 500.00', '类型：入金 → 出金'])
    await userEvent.click(within(row).getByTestId('audit-expand'))
    const detail = await screen.findByTestId('audit-detail')
    expect(within(detail).getByTestId('audit-before-type')).toHaveTextContent('入金')
    expect(within(detail).getByTestId('audit-after-type')).toHaveTextContent('出金')
    expect(within(detail).getByTestId('audit-after-occurredAtLocal')).toHaveTextContent(
      '2026-11-03 10:00',
    )
  })

  it('filters by object from the URL, shows 暂无审计记录 and clears (AC 12)', async () => {
    const queries = startFakeServer([DELETE_L2, NOTE_L2, NET_L2])
    renderPage('/audit?objectType=OPTION_TRADE&objectId=3')

    expect(await screen.findByTestId('audit-empty')).toHaveTextContent('暂无审计记录')
    expect(byTestId('audit-filter-type-trade')).toBeChecked()
    expect(byTestId('audit-filter-id')).toHaveValue('3')
    expect(queries.at(-1)?.toString()).toBe('objectType=OPTION_TRADE&objectId=3&page=1&size=20')

    await userEvent.click(byTestId('audit-empty-clear'))
    expect(await screen.findAllByTestId('audit-row')).toHaveLength(3)
    expect(byTestId('location')).toHaveTextContent(/^\/audit$/)
    expect(byTestId('audit-filter-type-all')).toBeChecked()
  })

  it('applies a filter from the form and puts it in the URL', async () => {
    startFakeServer([TRANSFER, NET_L2])
    renderPage()
    await screen.findAllByTestId('audit-row')

    await userEvent.click(byTestId('audit-filter-type-transfer'))
    fireEvent.change(byTestId('audit-filter-id'), { target: { value: '2' } })
    await userEvent.click(byTestId('audit-filter-submit'))

    await waitFor(() => {
      expect(screen.getAllByTestId('audit-row')).toHaveLength(1)
    })
    expect(byTestId('location')).toHaveTextContent('/audit?objectType=CASH_TRANSFER&objectId=2')
  })

  it('shows invalid URL filters under the fields without querying', async () => {
    const queries = startFakeServer([NET_L2])
    renderPage('/audit?objectId=abc')
    expect(await screen.findByTestId('audit-invalid')).toBeInTheDocument()
    expect(screen.getByText('对象 ID 必须是正整数')).toBeInTheDocument()

    renderPage('/audit?objectId=3')
    expect(screen.getByText('请同时指定对象类型')).toBeInTheDocument()
    expect(queries).toHaveLength(0)
  })

  it('shows 暂无审计记录 without a clear button when nothing was changed yet', async () => {
    startFakeServer([])
    renderPage()
    expect(await screen.findByTestId('audit-empty')).toHaveTextContent('暂无审计记录')
    expect(screen.queryByTestId('audit-empty-clear')).not.toBeInTheDocument()
    expect(screen.queryByTestId('audit-pagination')).not.toBeInTheDocument()
  })

  it('pages through records and changes the page size', async () => {
    const many = Array.from({ length: 25 }, (_, i) => ({ ...NET_L2, id: 25 - i }))
    const queries = startFakeServer(many)
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-row')).toHaveLength(20)
    })
    await userEvent.click(
      within(byTestId('audit-pagination')).getByRole('button', { name: 'Page 2' }),
    )
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-row')).toHaveLength(5)
    })
    expect(queries.at(-1)?.get('page')).toBe('2')

    const size50 = byTestId('audit-size-50')
    await userEvent.click(size50.closest('label') ?? size50)
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-row')).toHaveLength(25)
    })
    expect(queries.at(-1)?.toString()).toBe('page=1&size=50')
  })
})
