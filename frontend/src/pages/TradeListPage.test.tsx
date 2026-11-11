import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation, useNavigate } from 'react-router-dom'
import type { ApiErrorResponse, OptionTrade, OptionTradePage } from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { TradeListPage } from './TradeListPage'

// ---------------------------------------------------------------------------
// Baseline data (FEAT-002 acceptance criteria), already in backend order: T4 T5 T2 T1 T3 T6
// ---------------------------------------------------------------------------

function trade(partial: Partial<OptionTrade> & Pick<OptionTrade, 'id'>): OptionTrade {
  const base: OptionTrade = {
    id: partial.id,
    symbol: 'AAPL',
    expirationDate: '2026-12-18',
    strike: '200',
    optionType: 'PUT',
    action: 'STO',
    contracts: 1,
    multiplier: 100,
    netAmount: '0.00',
    tradedAt: '2026-11-03T15:30:00Z',
    tradedAtLocal: '2026-11-03T10:30:00',
    cashChange: '0.00',
    frozenChange: '0.00',
    contractLabel: '',
    note: null,
    ledgerEntryId: partial.id + 1,
    createdAt: '2026-11-05T15:00:00Z',
    coveredLots: null,
    realizedPnl: null,
  }
  const merged = { ...base, ...partial }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const T1 = trade({
  id: 1,
  netAmount: '349.35',
  cashChange: '349.35',
  frozenChange: '20000.00',
  note: '第一笔 CSP',
})
const T2 = trade({
  id: 2,
  strike: '210',
  optionType: 'CALL',
  action: 'BTO',
  netAmount: '512.65',
  cashChange: '-512.65',
})
const T3 = trade({
  id: 3,
  symbol: 'F',
  strike: '12.5',
  contracts: 2,
  netAmount: '48.70',
  cashChange: '48.70',
  frozenChange: '2500.00',
  tradedAt: '2026-11-02T20:59:00Z',
  tradedAtLocal: '2026-11-02T15:59:00',
})
const T4 = trade({
  id: 4,
  netAmount: '360.00',
  cashChange: '360.00',
  frozenChange: '20000.00',
  tradedAt: '2026-11-04T16:00:00Z',
  tradedAtLocal: '2026-11-04T11:00:00',
  note: '加仓',
})
const T5 = trade({
  id: 5,
  symbol: 'MSFT',
  expirationDate: '2027-01-15',
  strike: '450',
  optionType: 'CALL',
  action: 'BTO',
  netAmount: '900.00',
  cashChange: '-900.00',
  tradedAt: '2026-11-04T04:30:00Z',
  tradedAtLocal: '2026-11-03T23:30:00',
})
const T6 = trade({
  id: 6,
  symbol: 'XYZ',
  multiplier: 10,
  netAmount: '34.35',
  cashChange: '34.35',
  frozenChange: '2000.00',
  tradedAt: '2026-10-30T14:00:00Z',
  tradedAtLocal: '2026-10-30T10:00:00',
})
const BASELINE = [T4, T5, T2, T1, T3, T6]

// ---------------------------------------------------------------------------
// Fake backend: emulates the contract's filters and pagination over `server.trades`.
// ---------------------------------------------------------------------------

interface FakeServer {
  trades: OptionTrade[]
  /** Overrides the list response (e.g. errors). */
  onList?: (query: URLSearchParams) => Response | undefined
  onPost?: (body: unknown) => Response
}

function filterTrades(trades: OptionTrade[], query: URLSearchParams): OptionTrade[] {
  const symbol = query.get('symbol')?.trim().toUpperCase()
  const expirationDate = query.get('expirationDate')
  const strike = query.get('strike')
  const optionType = query.get('optionType')
  const actions = query.getAll('action').flatMap((a) => a.split(','))
  const startDate = query.get('startDate')
  const endDate = query.get('endDate')
  return trades.filter((t) => {
    const day = t.tradedAtLocal.slice(0, 10)
    return (
      (!symbol || t.symbol === symbol) &&
      (!expirationDate || t.expirationDate === expirationDate) &&
      (!strike || Number(t.strike) === Number(strike)) &&
      (!optionType || t.optionType === optionType) &&
      (actions.length === 0 || actions.includes(t.action)) &&
      (!startDate || day >= startDate) &&
      (!endDate || day <= endDate)
    )
  })
}

function startFakeServer(server: FakeServer) {
  const listRequests: URLSearchParams[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const parsed = new URL(url, 'http://localhost')
    if (method === 'GET' && parsed.pathname === '/api/option-trades') {
      listRequests.push(parsed.searchParams)
      const override = server.onList?.(parsed.searchParams)
      if (override) return Promise.resolve(override)
      const page = Number(parsed.searchParams.get('page'))
      const size = Number(parsed.searchParams.get('size'))
      const all = filterTrades(server.trades, parsed.searchParams)
      const body: OptionTradePage = {
        items: all.slice((page - 1) * size, page * size),
        page,
        size,
        totalItems: all.length,
        totalPages: Math.ceil(all.length / size),
      }
      return Promise.resolve(jsonResponse(body))
    }
    if (method === 'GET' && parsed.pathname === '/api/cash/summary') {
      return Promise.resolve(
        jsonResponse({ totalCash: '0.00', frozenCash: '0.00', availableCash: '0.00' }),
      )
    }
    if (method === 'POST' && parsed.pathname === '/api/option-trades' && server.onPost) {
      const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      return Promise.resolve(server.onPost(body))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return listRequests
}

function errorBody(code: string, details: ApiErrorResponse['details']): ApiErrorResponse {
  return {
    code,
    message: code === 'BAD_REQUEST' ? 'Malformed request' : 'Request validation failed',
    status: 400,
    path: '/api/option-trades',
    timestamp: '2026-11-05T15:00:00Z',
    details,
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function LocationProbe() {
  const location = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <span data-testid="location">{location.pathname + location.search}</span>
      <button
        type="button"
        onClick={() => {
          void navigate(-1)
        }}
      >
        browser-back
      </button>
    </>
  )
}

function renderPage(route = '/trades') {
  return renderWithProviders(
    <>
      <TradeListPage />
      <LocationProbe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function searchOf(): URLSearchParams {
  const text = byTestId('location').textContent
  return new URLSearchParams(text.slice(text.indexOf('?') + 1))
}

async function rowContracts(): Promise<string[]> {
  const rows = await screen.findAllByTestId('trade-row')
  return rows.map((row) => within(row).getByTestId('trade-cell-contract').textContent)
}

async function expectRowIds(ids: number[]) {
  await waitFor(() => {
    const rows = screen.queryAllByTestId('trade-row')
    expect(rows.map((row) => Number(row.getAttribute('data-trade-id')))).toEqual(ids)
  })
}

function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

/** Waits for `message` under the field; re-queries because the filter form remounts on apply. */
async function expectFieldError(testId: string, message: string) {
  await waitFor(() => {
    expect(within(formItemOf(byTestId(testId))).getByText(message)).toBeInTheDocument()
  })
}

function setText(testId: string, value: string) {
  fireEvent.change(byTestId(testId), { target: { value } })
}

async function submitFilters() {
  await userEvent.click(byTestId('trade-filter-submit'))
}

function lastList(requests: URLSearchParams[]): URLSearchParams {
  const last = requests.at(-1)
  if (!last) throw new Error('no list request')
  return last
}

// ---------------------------------------------------------------------------

describe('TradeListPage', () => {
  it('shows the baseline in backend order with every column (AC 1)', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage()

    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(byTestId('trade-list-total')).toHaveTextContent('共 6 条')
    expect(lastList(requests).toString()).toBe('page=1&size=20')

    const cells = (index: number) => {
      const row = screen.getAllByTestId('trade-row')[index]
      if (!row) throw new Error('row missing')
      const cell = (id: string) => within(row).getByTestId(id).textContent
      return [
        cell('trade-cell-time'),
        cell('trade-cell-action'),
        cell('trade-cell-contract'),
        cell('trade-cell-contracts'),
        cell('trade-cell-net-amount'),
        cell('trade-cell-frozen'),
        cell('trade-cell-note'),
      ]
    }
    expect(cells(0)).toEqual([
      '2026-11-04 11:00',
      'STO（卖出开仓）',
      'AAPL 2026-12-18 200 P',
      '1',
      '+360.00',
      '+20,000.00',
      '加仓',
    ])
    expect(cells(1)).toEqual([
      '2026-11-03 23:30',
      'BTO（买入开仓）',
      'MSFT 2027-01-15 450 C',
      '1',
      '−900.00',
      '—',
      '—',
    ])
    expect(cells(2).slice(0, 6)).toEqual([
      '2026-11-03 10:30',
      'BTO（买入开仓）',
      'AAPL 2026-12-18 210 C',
      '1',
      '−512.65',
      '—',
    ])
    expect(cells(3)[6]).toBe('第一笔 CSP')
    expect(cells(4).slice(2, 6)).toEqual(['F 2026-12-18 12.5 P', '2', '+48.70', '+2,500.00'])
    expect(cells(5).slice(3, 6)).toEqual(['1（multiplier 10）', '+34.35', '+2,000.00'])

    const first = screen.getAllByTestId('trade-row')[0] as HTMLElement
    expect(within(first).getByTestId('trade-cell-net-amount')).toHaveStyle({ color: '#389e0d' })
    const second = screen.getAllByTestId('trade-row')[1] as HTMLElement
    expect(within(second).getByTestId('trade-cell-net-amount')).toHaveStyle({ color: '#cf1322' })

    expect(screen.getAllByText('成交时间（美东时间）').length).toBeGreaterThan(0)
    for (const forbidden of ['价格', '每张价格', '手续费']) {
      expect(screen.queryByRole('columnheader', { name: forbidden })).not.toBeInTheDocument()
    }
  })

  it('filters by symbol, puts it in the URL and goes to page 1 (AC 2)', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage('/trades?page=1')
    await expectRowIds([4, 5, 2, 1, 3, 6])

    setText('trade-filter-symbol', ' aapl ')
    await submitFilters()

    await expectRowIds([4, 2, 1])
    expect(byTestId('trade-list-total')).toHaveTextContent('共 3 条')
    expect(lastList(requests).get('symbol')).toBe('aapl')
    expect(lastList(requests).get('page')).toBe('1')
    expect(searchOf().get('symbol')).toBe('aapl')
  })

  it('shows "没有符合条件的交易" with a clear button when nothing matches (AC 2, 6)', async () => {
    startFakeServer({ trades: BASELINE })
    renderPage('/trades?symbol=AAP')

    expect(await screen.findByTestId('trade-list-no-match')).toHaveTextContent('没有符合条件的交易')
    expect(screen.queryByTestId('trade-row')).not.toBeInTheDocument()
    expect(screen.queryByTestId('trade-list-empty')).not.toBeInTheDocument()
    // BUG-002: AC 2 requires 共 0 条; pager and page-size buttons stay hidden.
    expect(byTestId('trade-list-total')).toHaveTextContent('共 0 条')
    expect(screen.queryByRole('button', { name: 'Page 1' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('trade-list-size-20')).not.toBeInTheDocument()

    await userEvent.click(byTestId('trade-list-no-match-clear'))
    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(byTestId('trade-filter-symbol')).toHaveValue('')
    expect(byTestId('location')).toHaveTextContent(/^\/trades$/)
  })

  it.each([
    ['trade-filter-symbol', '$AAPL', '标的只能包含字母、数字和点'],
    ['trade-filter-symbol', 'ABCDEFGHIJK', '标的最多 10 个字符'],
    ['trade-filter-strike', '0', '行权价必须大于 0'],
    ['trade-filter-strike', '-5', '行权价必须大于 0'],
    ['trade-filter-strike', '200.1234', '行权价最多 3 位小数'],
    ['trade-filter-strike', 'abc', '行权价必须是数字'],
  ])('rejects %s = %j on the client without querying (AC 2, 3)', async (testId, value, message) => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage()
    await expectRowIds([4, 5, 2, 1, 3, 6])
    const before = requests.length

    setText(testId, value)
    await submitFilters()

    await expectFieldError(testId, message)
    expect(byTestId('trade-list-invalid')).toBeInTheDocument()
    expect(screen.queryByTestId('trade-row')).not.toBeInTheDocument()
    expect(requests).toHaveLength(before)
    expect(byTestId(testId)).toHaveValue(value)
  })

  it('filters by contract fields (AC 3)', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage()
    await expectRowIds([4, 5, 2, 1, 3, 6])

    setText('trade-filter-symbol', 'AAPL')
    setText('trade-filter-expiration', '2026-12-18')
    setText('trade-filter-strike', '200.00')
    await userEvent.click(byTestId('trade-filter-type-put'))
    await submitFilters()

    await expectRowIds([4, 1])
    expect(byTestId('trade-list-total')).toHaveTextContent('共 2 条')
    const query = lastList(requests)
    expect(query.get('expirationDate')).toBe('2026-12-18')
    expect(query.get('strike')).toBe('200.00')
    expect(query.get('optionType')).toBe('PUT')
  })

  it('clicking a contract filters by that contract on page 1 (AC 3)', async () => {
    startFakeServer({ trades: BASELINE })
    renderPage('/trades?size=50')
    await expectRowIds([4, 5, 2, 1, 3, 6])

    const t6 = screen.getAllByTestId('trade-row')[5] as HTMLElement
    await userEvent.click(within(t6).getByTestId('trade-cell-contract'))

    await expectRowIds([6])
    expect(byTestId('trade-filter-symbol')).toHaveValue('XYZ')
    expect(byTestId('trade-filter-expiration')).toHaveValue('2026-12-18')
    expect(byTestId('trade-filter-strike')).toHaveValue('200')
    expect(byTestId('trade-filter-type-put')).toBeChecked()
    const search = searchOf()
    expect(search.get('page')).toBeNull()
    expect(search.get('size')).toBe('50')
  })

  it('filters by several directions incl. BTC without error (AC 4)', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage()
    await expectRowIds([4, 5, 2, 1, 3, 6])

    await userEvent.click(byTestId('trade-filter-action-sto'))
    await submitFilters()
    await expectRowIds([4, 1, 3, 6])
    expect(searchOf().get('action')).toBe('STO')

    await userEvent.click(byTestId('trade-filter-action-bto'))
    await submitFilters()
    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(lastList(requests).getAll('action').sort()).toEqual(['BTO', 'STO'])

    await userEvent.click(byTestId('trade-filter-clear'))
    await userEvent.click(byTestId('trade-filter-action-btc'))
    await submitFilters()
    expect(await screen.findByTestId('trade-list-no-match')).toBeInTheDocument()
    expect(lastList(requests).getAll('action')).toEqual(['BTC'])
    expect(byTestId('trade-list-total')).toHaveTextContent('共 0 条')
  })

  it('filters by NY date range and rejects a reversed range without querying (AC 5)', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage()
    await expectRowIds([4, 5, 2, 1, 3, 6])

    setText('trade-filter-start-date', '2026-11-03')
    setText('trade-filter-end-date', '2026-11-03')
    await submitFilters()
    await expectRowIds([5, 2, 1])
    expect(lastList(requests).get('startDate')).toBe('2026-11-03')
    expect(lastList(requests).get('endDate')).toBe('2026-11-03')

    const before = requests.length
    setText('trade-filter-start-date', '2026-11-04')
    await submitFilters()
    await expectFieldError('trade-filter-end-date', '开始日期不能晚于结束日期')
    expect(requests).toHaveLength(before)
    expect(screen.queryByTestId('trade-row')).not.toBeInTheDocument()
  })

  it('combines filters and clears them all (AC 6)', async () => {
    startFakeServer({ trades: BASELINE })
    renderPage('/trades?symbol=AAPL&action=STO&startDate=2026-11-03&endDate=2026-11-04&page=1')
    await expectRowIds([4, 1])

    setText('trade-filter-end-date', '2026-11-03')
    await submitFilters()
    await expectRowIds([1])

    await userEvent.click(byTestId('trade-filter-clear'))
    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(byTestId('trade-filter-symbol')).toHaveValue('')
    expect(byTestId('trade-filter-action-sto')).not.toBeChecked()
    expect(byTestId('trade-filter-end-date')).toHaveValue('')
  })

  it('pages, changes the page size and returns to page 1 on a filter change (AC 7)', async () => {
    const many = Array.from({ length: 45 }, (_, i) => {
      const day = 45 - i // newest first: 2026-10-15 … 2026-09-01
      const date = new Date(Date.UTC(2026, 8, day, 14))
      const local = date.toISOString().slice(0, 10)
      return trade({
        id: day,
        strike: '210',
        optionType: 'CALL',
        action: 'BTO',
        netAmount: '1.00',
        cashChange: '-1.00',
        tradedAt: `${local}T14:00:00Z`,
        tradedAtLocal: `${local}T10:00:00`,
      })
    })
    const requests = startFakeServer({ trades: many })
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(20)
    })
    expect(byTestId('trade-list-total')).toHaveTextContent('共 45 条')
    const firstTime = () =>
      within(screen.getAllByTestId('trade-row')[0] as HTMLElement).getByTestId('trade-cell-time')
    expect(firstTime()).toHaveTextContent('2026-10-15 10:00')

    const pagination = byTestId('trade-list-pagination')
    await userEvent.click(within(pagination).getByRole('button', { name: 'Page 3' }))
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(5)
    })
    expect(searchOf().get('page')).toBe('3')
    const rows = screen.getAllByTestId('trade-row')
    expect(within(rows[4] as HTMLElement).getByTestId('trade-cell-time')).toHaveTextContent(
      '2026-09-01 10:00',
    )

    setText('trade-filter-start-date', '2026-10-01')
    await submitFilters()
    await waitFor(() => {
      expect(byTestId('trade-list-total')).toHaveTextContent('共 15 条')
    })
    expect(lastList(requests).get('page')).toBe('1')
    expect(searchOf().get('page')).toBeNull()

    await userEvent.click(byTestId('trade-filter-clear'))
    await waitFor(() => {
      expect(byTestId('trade-list-total')).toHaveTextContent('共 45 条')
    })
    await userEvent.click(byTestId('trade-list-size-50').closest('label') ?? byTestId('trade-list-size-50'))
    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(45)
    })
    expect(lastList(requests).get('size')).toBe('50')
    expect(lastList(requests).get('page')).toBe('1')
    expect(searchOf().get('size')).toBe('50')
  })

  it('jumps back to the last page when the URL page is past the end', async () => {
    startFakeServer({ trades: BASELINE })
    renderPage('/trades?page=9')

    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(searchOf().get('page')).toBeNull()
  })

  it('ignores invalid page / size in the URL instead of sending them', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage('/trades?page=abc&size=0')

    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(requests.every((query) => query.get('page') === '1' && query.get('size') === '20')).toBe(
      true,
    )
  })

  it('shows "暂无交易" with a link to 录入开仓 when there are no trades (AC 8)', async () => {
    startFakeServer({ trades: [] })
    renderPage()

    expect(await screen.findByTestId('trade-list-empty')).toHaveTextContent('暂无交易')
    expect(byTestId('trade-list-empty-new')).toHaveAttribute('href', '/trades/new')
    expect(screen.queryByTestId('trade-row')).not.toBeInTheDocument()
    // AC 8 does not ask for 共 0 条 in the fully empty state.
    expect(screen.queryByTestId('trade-list-pagination')).not.toBeInTheDocument()
    expect(screen.queryByTestId('trade-list-total')).not.toBeInTheDocument()
  })

  it('restores filters from the URL and goes back to the previous filters (AC 9)', async () => {
    startFakeServer({ trades: BASELINE })
    renderPage('/trades?symbol=AAPL&action=STO')

    await expectRowIds([4, 1])
    expect(byTestId('trade-filter-symbol')).toHaveValue('AAPL')
    expect(byTestId('trade-filter-action-sto')).toBeChecked()

    await userEvent.click(byTestId('trade-filter-action-sto'))
    await userEvent.click(byTestId('trade-filter-action-bto'))
    await submitFilters()
    await expectRowIds([2])
    expect(searchOf().get('action')).toBe('BTO')

    await userEvent.click(screen.getByRole('button', { name: 'browser-back' }))
    await expectRowIds([4, 1])
    expect(byTestId('trade-filter-action-sto')).toBeChecked()
    expect(byTestId('trade-filter-action-bto')).not.toBeChecked()
  })

  it('accepts comma-separated and repeated action params in the URL', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage('/trades?action=BTO,STC&action=STO')

    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(lastList(requests).getAll('action')).toEqual(['BTO', 'STC', 'STO'])
  })

  it('shows invalid URL params under the fields without crashing, then clears (AC 9)', async () => {
    const requests = startFakeServer({ trades: BASELINE })
    renderPage('/trades?startDate=2026-11-04&endDate=2026-11-03&strike=abc&optionType=X&action=FOO')

    expect(
      within(formItemOf(byTestId('trade-filter-end-date'))).getByText('开始日期不能晚于结束日期'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('trade-filter-strike'))).getByText('行权价必须是数字'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('trade-filter-type-call'))).getByText('类型只能是 CALL 或 PUT'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('trade-filter-action-bto'))).getByText(
        '方向只能是 BTO、STO、BTC、STC',
      ),
    ).toBeInTheDocument()
    expect(byTestId('trade-filter-strike')).toHaveValue('abc')
    expect(requests).toHaveLength(0)

    await userEvent.click(byTestId('trade-filter-clear'))
    await expectRowIds([4, 5, 2, 1, 3, 6])
    expect(screen.queryByTestId('trade-list-invalid')).not.toBeInTheDocument()
  })

  it('shows backend field errors under the matching filter', async () => {
    startFakeServer({
      trades: BASELINE,
      onList: (query) =>
        query.get('expirationDate') === '2026-02-30'
          ? jsonResponse(
              errorBody('BAD_REQUEST', [
                { field: 'expirationDate', message: '日期格式不正确（YYYY-MM-DD）' },
              ]),
              400,
            )
          : undefined,
    })
    renderPage('/trades?expirationDate=2026-02-30')

    await expectFieldError('trade-filter-expiration', '日期格式不正确（YYYY-MM-DD）')
    expect(byTestId('trade-list-invalid')).toBeInTheDocument()

    await userEvent.click(byTestId('trade-filter-clear'))
    await expectRowIds([4, 5, 2, 1, 3, 6])
  })

  it('shows a load error with retry', async () => {
    let calls = 0
    startFakeServer({
      trades: BASELINE,
      onList: () => {
        calls += 1
        return calls === 1
          ? jsonResponse(
              { ...errorBody('INTERNAL_ERROR', []), message: 'Internal server error', status: 500 },
              500,
            )
          : undefined
      },
    })
    renderPage()

    expect(await screen.findByTestId('trade-list-error')).toHaveTextContent('INTERNAL_ERROR')
    await userEvent.click(byTestId('trade-list-retry'))
    await expectRowIds([4, 5, 2, 1, 3, 6])
  })

  it('shows a long note truncated with the full text on hover', async () => {
    const note = '长'.repeat(500)
    startFakeServer({ trades: [{ ...T1, note }] })
    renderPage()

    const cell = await screen.findByTestId('trade-cell-note')
    expect(cell).toHaveAttribute('title', note)
    expect(cell).toHaveStyle({ maxWidth: '240px' })
  })

  it('shows a newly recorded trade without reload (AC 10)', async () => {
    const newTrade = trade({
      id: 7,
      symbol: 'F',
      strike: '12.5',
      netAmount: '25.00',
      cashChange: '25.00',
      frozenChange: '1250.00',
      tradedAt: '2026-11-05T14:45:00Z',
      tradedAtLocal: '2026-11-05T09:45:00',
    })
    const server: FakeServer = { trades: [...BASELINE] }
    server.onPost = () => {
      server.trades = [newTrade, ...server.trades]
      return jsonResponse(newTrade, 201)
    }
    startFakeServer(server)

    renderWithProviders(<App />, { route: '/trades' })
    await expectRowIds([4, 5, 2, 1, 3, 6])

    const user = userEvent.setup()
    await user.click(byTestId('nav-open-trade'))
    await user.click(byTestId('open-trade-action-sto'))
    await user.click(byTestId('open-trade-type-put'))
    setText('open-trade-symbol', 'F')
    setText('open-trade-expiration', '2026-12-18')
    setText('open-trade-strike', '12.5')
    setText('open-trade-contracts', '1')
    setText('open-trade-net-amount', '25.00')
    setText('open-trade-time', '2026-11-05T09:45')
    await user.click(byTestId('open-trade-submit'))
    expect(await screen.findByTestId('open-trade-confirmation')).toBeInTheDocument()

    await user.click(byTestId('nav-trades'))
    await expectRowIds([7, 4, 5, 2, 1, 3, 6])
    const first = screen.getAllByTestId('trade-row')[0] as HTMLElement
    expect(within(first).getByTestId('trade-cell-time')).toHaveTextContent('2026-11-05 09:45')
    expect(within(first).getByTestId('trade-cell-net-amount')).toHaveTextContent('+25.00')
    expect(within(first).getByTestId('trade-cell-frozen')).toHaveTextContent('+1,250.00')
    expect(byTestId('trade-list-total')).toHaveTextContent('共 7 条')
    expect(await rowContracts()).toHaveLength(7)
  })
})
