import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import { useLocation, useNavigate } from 'react-router-dom'
import type {
  ClosedOptionRecord,
  ClosedSoldLot,
  ClosedStockRecord,
  ClosedTrade,
  ClosedTradePage,
  CoveredLot,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { ClosedTradesPage } from './ClosedTradesPage'

// ---------------------------------------------------------------------------
// Scenario P (FEAT-006), in backend order: C4, C3, C2, C1 — total +633.05
// ---------------------------------------------------------------------------

function lot(partial: Partial<CoveredLot> & Pick<CoveredLot, 'tradeId'>): CoveredLot {
  return {
    action: 'STO',
    tradedAt: '2026-11-02T15:30:00Z',
    tradedAtLocal: '2026-11-02T10:30:00',
    contracts: 1,
    netAmount: '0.00',
    frozen: '0.00',
    ...partial,
  }
}

function closed(
  partial: Partial<ClosedOptionRecord> & Pick<ClosedOptionRecord, 'id'>,
): ClosedOptionRecord {
  const merged: ClosedOptionRecord = {
    type: 'OPTION',
    shares: null,
    costPerShare: null,
    pricePerShare: null,
    soldLots: null,
    tradedAt: '2026-11-05T15:00:00Z',
    tradedAtLocal: '2026-11-05T10:00:00',
    symbol: 'AAPL',
    expirationDate: '2026-12-18',
    strike: '200',
    optionType: 'PUT',
    contractLabel: '',
    action: 'BTC',
    closedDirection: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '0.00',
    openingNetPerContract: '0.00',
    closeNetAmount: '0.00',
    closeNetPerContract: '0.00',
    realizedPnl: '0.00',
    note: null,
    coveredLots: [],
    hasAuditLog: false,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const C1 = closed({
  id: 6,
  contracts: 3,
  openingNetTotal: '1058.70',
  openingNetPerContract: '352.90',
  closeNetAmount: '-400.00',
  closeNetPerContract: '-133.33',
  realizedPnl: '658.70',
  note: '全部平掉',
  hasAuditLog: true,
  coveredLots: [
    lot({ tradeId: 1, contracts: 2, netAmount: '698.70', frozen: '40000.00' }),
    lot({
      tradeId: 5,
      tradedAt: '2026-11-03T16:00:00Z',
      tradedAtLocal: '2026-11-03T11:00:00',
      netAmount: '360.00',
      frozen: '20000.00',
    }),
  ],
})
const C2 = closed({
  id: 7,
  symbol: 'XYZ',
  multiplier: 10,
  tradedAt: '2026-11-06T15:00:00Z',
  tradedAtLocal: '2026-11-06T10:00:00',
  openingNetTotal: '34.35',
  openingNetPerContract: '34.35',
  closeNetAmount: '-50.00',
  closeNetPerContract: '-50.00',
  realizedPnl: '-15.65',
  coveredLots: [lot({ tradeId: 3, netAmount: '34.35', frozen: '2000.00' })],
})
const C3 = closed({
  id: 8,
  symbol: 'MSFT',
  expirationDate: '2027-01-15',
  strike: '450',
  optionType: 'CALL',
  action: 'STC',
  closedDirection: 'LONG',
  tradedAt: '2026-11-07T04:30:00Z',
  tradedAtLocal: '2026-11-06T23:30:00',
  openingNetTotal: '-900.00',
  openingNetPerContract: '-900.00',
  closeNetAmount: '1250.00',
  closeNetPerContract: '1250.00',
  realizedPnl: '350.00',
  note: '盘后',
  coveredLots: [lot({ tradeId: 2, action: 'BTO', netAmount: '-900.00' })],
})
const C4 = closed({
  id: 9,
  strike: '210',
  optionType: 'CALL',
  action: 'STC',
  closedDirection: 'LONG',
  contracts: 2,
  tradedAt: '2026-11-07T15:00:00Z',
  tradedAtLocal: '2026-11-07T10:00:00',
  openingNetTotal: '-1000.00',
  openingNetPerContract: '-500.00',
  closeNetAmount: '640.00',
  closeNetPerContract: '320.00',
  realizedPnl: '-360.00',
  coveredLots: [
    lot({
      tradeId: 4,
      action: 'BTO',
      tradedAt: '2026-11-03T14:45:00Z',
      tradedAtLocal: '2026-11-03T09:45:00',
      contracts: 2,
      netAmount: '-1000.00',
    }),
  ],
})
const SCENARIO_P = [C4, C3, C2, C1]

/**
 * Test-side stand-in for the backend: filters like the contract (FEAT-040: `type`, and the
 * option-only filters leave stock rows out, Q90), sums cents for the total.
 */
function respond(all: ClosedTrade[], query: URLSearchParams): ClosedTradePage {
  const symbol = query.get('symbol')?.trim().toUpperCase()
  const actions = query.getAll('action').flatMap((a) => a.split(','))
  const startDate = query.get('startDate')
  const endDate = query.get('endDate')
  const strike = query.get('strike')
  const type = query.get('type')
  const optionOnly =
    actions.length > 0 ||
    Boolean(query.get('expirationDate')) ||
    Boolean(strike) ||
    Boolean(query.get('optionType'))
  const filtered = all.filter((t) => {
    const day = t.tradedAtLocal.slice(0, 10)
    if (type && t.type !== type) return false
    if (t.type === 'STOCK' && optionOnly) return false
    return (
      (!symbol || t.symbol === symbol) &&
      (!query.get('expirationDate') || t.expirationDate === query.get('expirationDate')) &&
      (!strike || Number(t.strike) === Number(strike)) &&
      (!query.get('optionType') || t.optionType === query.get('optionType')) &&
      (actions.length === 0 || actions.includes(t.action)) &&
      (!startDate || day >= startDate) &&
      (!endDate || day <= endDate)
    )
  })
  const page = Number(query.get('page'))
  const size = Number(query.get('size'))
  const cents = filtered.reduce((sum, t) => sum + Math.round(Number(t.realizedPnl) * 100), 0)
  return {
    items: filtered.slice((page - 1) * size, page * size),
    page,
    size,
    totalItems: filtered.length,
    totalPages: Math.ceil(filtered.length / size),
    filteredCount: filtered.length,
    filteredRealizedPnlTotal: (cents / 100).toFixed(2),
  }
}

interface FakeServer {
  closed: ClosedTrade[]
  onList?: (query: URLSearchParams) => Response | undefined
}

function startFakeServer(server: FakeServer) {
  const queries: URLSearchParams[] = []
  mockFetch().mockImplementation((input) => {
    const url = typeof input === 'string' ? input : ''
    const parsed = new URL(url, 'http://localhost')
    if (parsed.pathname === '/api/closed-trades') {
      queries.push(parsed.searchParams)
      const override = server.onList?.(parsed.searchParams)
      return Promise.resolve(override ?? jsonResponse(respond(server.closed, parsed.searchParams)))
    }
    if (parsed.pathname === '/api/option-trades') {
      return Promise.resolve(
        jsonResponse({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }),
      )
    }
    if (parsed.pathname === '/api/audit-log') {
      return Promise.resolve(
        jsonResponse({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }),
      )
    }
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return queries
}

function Probe() {
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

function renderPage(route = '/closed') {
  return renderWithProviders(
    <>
      <ClosedTradesPage />
      <Probe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function setText(testId: string, value: string) {
  fireEvent.change(byTestId(testId), { target: { value } })
}

async function expectRows(ids: number[]) {
  await waitFor(() => {
    expect(
      screen.queryAllByTestId('closed-row').map((row) => Number(row.getAttribute('data-trade-id'))),
    ).toEqual(ids)
  })
}

async function expectSummary(count: string, total: string) {
  await waitFor(() => {
    expect(byTestId('closed-count')).toHaveTextContent(count)
  })
  expect(byTestId('closed-pnl-total')).toHaveTextContent(total)
}

function rowCells(row: HTMLElement): string[] {
  const cell = (id: string) => within(row).getByTestId(id).textContent
  return [
    cell('closed-cell-time'),
    cell('closed-cell-contract'),
    cell('closed-cell-action'),
    cell('closed-cell-direction'),
    cell('closed-cell-contracts'),
    cell('closed-cell-opening-net'),
    cell('closed-cell-opening-net-per'),
    cell('closed-cell-close-net'),
    cell('closed-cell-close-net-per'),
    cell('closed-cell-pnl'),
  ]
}

function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

async function submitFilters() {
  await userEvent.click(byTestId('closed-filter-submit'))
}

// ---------------------------------------------------------------------------

describe('ClosedTradesPage', () => {
  it('shows count, total and every column in backend order (AC 1)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage()

    await expectRows([9, 8, 7, 6])
    expect(byTestId('closed-count')).toHaveTextContent('共 4 笔平仓')
    expect(byTestId('closed-pnl-total')).toHaveTextContent('+633.05')
    expect(byTestId('closed-pnl-total')).toHaveStyle({ color: '#389e0d' })
    expect(screen.queryByTestId('closed-filter-note')).not.toBeInTheDocument()

    expect(screen.getAllByTestId('closed-row').map(rowCells)).toEqual([
      [
        '2026-11-07 10:00',
        'AAPL 2026-12-18 210 C',
        'STC（卖出平仓）',
        '多头',
        '2',
        '−1,000.00',
        '每张 −500.00',
        '+640.00',
        '每张 +320.00',
        '−360.00',
      ],
      [
        '2026-11-06 23:30',
        'MSFT 2027-01-15 450 C',
        'STC（卖出平仓）',
        '多头',
        '1',
        '−900.00',
        '每张 −900.00',
        '+1,250.00',
        '每张 +1,250.00',
        '+350.00',
      ],
      [
        '2026-11-06 10:00',
        'XYZ 2026-12-18 200 P',
        'BTC（买入平仓）',
        '空头',
        '1（multiplier 10）',
        '+34.35',
        '每张 +34.35',
        '−50.00',
        '每张 −50.00',
        '−15.65',
      ],
      [
        '2026-11-05 10:00',
        'AAPL 2026-12-18 200 P',
        'BTC（买入平仓）',
        '空头',
        '3',
        '+1,058.70',
        '每张 +352.90',
        '−400.00',
        '每张 −133.33',
        '+658.70',
      ],
    ])
    const rows = screen.getAllByTestId('closed-row')
    expect(within(rows[0] as HTMLElement).getByTestId('closed-cell-pnl')).toHaveStyle({
      color: '#cf1322',
    })
    expect(within(rows[1] as HTMLElement).getByTestId('closed-cell-pnl')).toHaveStyle({
      color: '#389e0d',
    })
    expect(within(rows[3] as HTMLElement).getByTestId('closed-cell-note')).toHaveTextContent(
      '全部平掉',
    )
    expect(within(rows[0] as HTMLElement).getByTestId('closed-cell-note')).toHaveTextContent('—')
    expect(screen.getAllByText('平仓时间（美东时间）').length).toBeGreaterThan(0)
    for (const forbidden of ['成交价', '手续费', '持有天数']) {
      // Header texts via DOM (role queries over the whole antd tree are very slow in jsdom).
      const headers = [...document.querySelectorAll('th')].map((th) => th.textContent)
      expect(headers).not.toContain(forbidden)
    }
  })

  it('expands covered lots in FIFO order (AC 2)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])

    const c1 = screen.getAllByTestId('closed-row')[3] as HTMLElement
    await userEvent.click(within(c1).getByTestId('closed-expand'))
    const detail = c1.nextElementSibling as HTMLElement
    const lots = await within(detail).findAllByTestId('closed-lot-row')
    const texts = lots.map((row) => [
      within(row).getByTestId('closed-lot-time').textContent,
      within(row).getByTestId('closed-lot-action').textContent,
      within(row).getByTestId('closed-lot-contracts').textContent,
      within(row).getByTestId('closed-lot-net-amount').textContent,
    ])
    expect(texts).toEqual([
      ['2026-11-02 10:30', 'STO（卖出开仓）', '2 张', '+698.70'],
      ['2026-11-03 11:00', 'STO（卖出开仓）', '1 张', '+360.00'],
    ])

    const c4 = screen.getAllByTestId('closed-row')[0] as HTMLElement
    await userEvent.click(within(c4).getByTestId('closed-expand'))
    const c4Lots = await within(c4.nextElementSibling as HTMLElement).findAllByTestId(
      'closed-lot-row',
    )
    expect(within(c4Lots[0] as HTMLElement).getByTestId('closed-lot-net-amount')).toHaveTextContent(
      '−1,000.00',
    )
    expect(within(c4Lots[0] as HTMLElement).getByTestId('closed-lot-action')).toHaveTextContent(
      'BTO（买入开仓）',
    )
  })

  // AC 3 is split into three tests (same assertions) so no single test does four filter rounds.
  it('filters by symbol with the filtered total (AC 3)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])

    setText('closed-filter-symbol', 'aapl')
    await submitFilters()
    await expectRows([9, 6])
    await expectSummary('共 2 笔平仓', '+298.70')
    expect(byTestId('closed-filter-note')).toHaveTextContent('当前筛选')
  })

  it('filters by the full contract with the filtered total (AC 3)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])

    setText('closed-filter-symbol', 'AAPL')
    setText('closed-filter-expiration', '2026-12-18')
    setText('closed-filter-strike', '200.00')
    await userEvent.click(byTestId('closed-filter-type-put'))
    await submitFilters()
    await expectRows([6])
    await expectSummary('共 1 笔平仓', '+658.70')
  })

  it('filters by type, then clears and filters by direction (AC 3)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])

    await userEvent.click(byTestId('closed-filter-type-call'))
    await submitFilters()
    await expectRows([9, 8])
    await expectSummary('共 2 笔平仓', '−10.00')
    expect(byTestId('closed-pnl-total')).toHaveStyle({ color: '#cf1322' })

    await userEvent.click(byTestId('closed-filter-clear'))
    await userEvent.click(byTestId('closed-filter-action-btc'))
    await submitFilters()
    await expectRows([7, 6])
    await expectSummary('共 2 笔平仓', '+643.05')
    expect(queries.at(-1)?.getAll('action')).toEqual(['BTC'])
  })

  it('offers only BTC / STC and the 平仓 date labels', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])
    expect(byTestId('closed-filter-action-btc')).toBeInTheDocument()
    expect(byTestId('closed-filter-action-stc')).toBeInTheDocument()
    expect(screen.queryByTestId('closed-filter-action-bto')).not.toBeInTheDocument()
    expect(screen.queryByTestId('closed-filter-action-sto')).not.toBeInTheDocument()
    expect(screen.getByText('平仓 / 卖出开始日期（美东日期）')).toBeInTheDocument()
    expect(screen.getByText('平仓 / 卖出结束日期（美东日期）')).toBeInTheDocument()
  })

  it('shows 没有符合条件的平仓记录 with 0.00 and clears (AC 3)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage('/closed?symbol=TSLA')

    expect(await screen.findByTestId('closed-no-match')).toHaveTextContent('没有符合条件的平仓记录')
    await expectSummary('共 0 笔平仓', '0.00')
    await userEvent.click(byTestId('closed-no-match-clear'))
    await expectRows([9, 8, 7, 6])
  })

  it('rejects $AAPL and a reversed date range on the client (AC 3, 4)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])
    const before = queries.length

    setText('closed-filter-symbol', '$AAPL')
    setText('closed-filter-start-date', '2026-11-07')
    setText('closed-filter-end-date', '2026-11-06')
    await submitFilters()

    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('closed-filter-symbol'))).getByText(
          '标的只能包含字母、数字和点',
        ),
      ).toBeInTheDocument()
    })
    expect(
      within(formItemOf(byTestId('closed-filter-end-date'))).getByText('开始日期不能晚于结束日期'),
    ).toBeInTheDocument()
    expect(byTestId('closed-invalid')).toBeInTheDocument()
    expect(screen.queryByTestId('closed-row')).not.toBeInTheDocument()
    expect(queries).toHaveLength(before)
  })

  it('filters by NY close date (AC 4)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_P })
    renderPage('/closed?startDate=2026-11-06&endDate=2026-11-06')
    await expectRows([8, 7])
    await expectSummary('共 2 笔平仓', '+334.35')
    expect(queries.at(-1)?.get('startDate')).toBe('2026-11-06')

    setText('closed-filter-start-date', '')
    setText('closed-filter-end-date', '2026-11-05')
    await submitFilters()
    await expectRows([6])
  })

  it('rejects BTO / STO in the URL action filter without querying', async () => {
    const queries = startFakeServer({ closed: SCENARIO_P })
    renderPage('/closed?action=STO')
    expect(await screen.findByText('方向只能是 BTC、STC、EXP')).toBeInTheDocument()
    expect(queries).toHaveLength(0)
  })

  it('shows backend field errors under the filter', async () => {
    startFakeServer({
      closed: SCENARIO_P,
      onList: (query) =>
        query.get('expirationDate') === '2026-02-30'
          ? jsonResponse(
              {
                code: 'BAD_REQUEST',
                message: 'Malformed request',
                status: 400,
                path: '/api/closed-trades',
                timestamp: '2026-11-11T15:00:00Z',
                details: [{ field: 'expirationDate', message: '日期格式不正确（YYYY-MM-DD）' }],
              },
              400,
            )
          : undefined,
    })
    renderPage('/closed?expirationDate=2026-02-30')
    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('closed-filter-expiration'))).getByText(
          '日期格式不正确（YYYY-MM-DD）',
        ),
      ).toBeInTheDocument()
    })
  })

  it('pages with the total over all pages and resets to page 1 on filter (AC 6)', async () => {
    const many = Array.from({ length: 25 }, (_, i) => {
      const day = 25 - i
      const date = `2026-10-${String(day).padStart(2, '0')}`
      return closed({
        id: 100 + day,
        strike: '210',
        optionType: 'CALL',
        action: 'STC',
        closedDirection: 'LONG',
        tradedAt: `${date}T19:00:00Z`,
        tradedAtLocal: `${date}T15:00:00`,
        openingNetTotal: '-10.00',
        openingNetPerContract: '-10.00',
        closeNetAmount: '12.00',
        closeNetPerContract: '12.00',
        realizedPnl: '2.00',
      })
    })
    const queries = startFakeServer({ closed: many })
    renderPage()

    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(20)
    })
    await expectSummary('共 25 笔平仓', '+50.00')
    expect(
      within(screen.getAllByTestId('closed-row')[0] as HTMLElement).getByTestId('closed-cell-time'),
    ).toHaveTextContent('2026-10-25 15:00')

    await userEvent.click(
      within(byTestId('closed-pagination')).getByRole('button', { name: 'Page 2' }),
    )
    await waitFor(() => {
      expect(screen.getAllByTestId('closed-row')).toHaveLength(5)
    })
    expect(byTestId('closed-pnl-total')).toHaveTextContent('+50.00')
    expect(byTestId('location')).toHaveTextContent('page=2')

    setText('closed-filter-start-date', '2026-10-20')
    await submitFilters()
    await expectSummary('共 6 笔平仓', '+12.00')
    expect(queries.at(-1)?.get('page')).toBe('1')

    const size50 = byTestId('closed-size-50')
    await userEvent.click(size50.closest('label') ?? size50)
    await waitFor(() => {
      expect(queries.at(-1)?.get('size')).toBe('50')
    })
  })

  it('keeps filters in the URL and goes back (AC 7)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage()
    await expectRows([9, 8, 7, 6])

    await userEvent.click(byTestId('closed-filter-action-btc'))
    await submitFilters()
    await expectRows([7, 6])
    expect(byTestId('location')).toHaveTextContent('/closed?action=BTC')

    await userEvent.click(screen.getByText('browser-back'))
    await expectRows([9, 8, 7, 6])
    expect(byTestId('closed-filter-action-btc')).not.toBeChecked()
  })

  it('restores filters from a bookmarked URL (AC 7)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderPage('/closed?action=BTC')
    await expectRows([7, 6])
    await expectSummary('共 2 笔平仓', '+643.05')
    expect(byTestId('closed-filter-action-btc')).toBeChecked()
  })

  it('links contract and history (AC 7)', async () => {
    startFakeServer({ closed: SCENARIO_P })
    renderWithProviders(<App />, { route: '/closed' })
    await expectRows([9, 8, 7, 6])
    const c1 = screen.getAllByTestId('closed-row')[3] as HTMLElement

    expect(within(c1).getByTestId('closed-history')).toHaveAttribute(
      'href',
      '/audit?objectType=OPTION_TRADE&objectId=6',
    )
    // Rows without audit records have no 历史 (the actions cell is empty).
    for (const row of screen.getAllByTestId('closed-row').slice(0, 3)) {
      expect(within(row).queryByTestId('closed-history')).not.toBeInTheDocument()
    }
    const contract = within(c1).getByTestId('closed-cell-contract')
    expect(contract).toHaveAttribute(
      'href',
      '/trades?symbol=AAPL&expirationDate=2026-12-18&strike=200&optionType=PUT',
    )
    await userEvent.click(contract)
    expect(await screen.findByTestId('trade-list-page')).toBeInTheDocument()
    expect(byTestId('trade-filter-symbol')).toHaveValue('AAPL')
    expect(byTestId('trade-filter-type-put')).toBeChecked()
  })

  it('shows 暂无平仓记录 with zero totals (AC 8)', async () => {
    startFakeServer({ closed: [] })
    renderPage()
    expect(await screen.findByTestId('closed-empty')).toHaveTextContent('暂无平仓记录')
    await expectSummary('共 0 笔平仓', '0.00')
    expect(screen.queryByTestId('closed-pagination')).not.toBeInTheDocument()
  })

  it('shows a load error with retry', async () => {
    let calls = 0
    startFakeServer({
      closed: SCENARIO_P,
      onList: () => {
        calls += 1
        return calls === 1
          ? jsonResponse(
              {
                code: 'INTERNAL_ERROR',
                message: 'Internal server error',
                status: 500,
                path: '/api/closed-trades',
                timestamp: '2026-11-11T15:00:00Z',
                details: [],
              },
              500,
            )
          : undefined
      },
    })
    renderPage()
    expect(await screen.findByTestId('closed-error')).toHaveTextContent('INTERNAL_ERROR')
    await userEvent.click(byTestId('closed-retry'))
    await expectRows([9, 8, 7, 6])
  })

  it('re-renders backend re-matching after a backdated close (AC 5)', async () => {
    const X = closed({
      id: 3,
      tradedAt: '2026-11-06T15:00:00Z',
      tradedAtLocal: '2026-11-06T10:00:00',
      openingNetTotal: '349.35',
      openingNetPerContract: '349.35',
      closeNetAmount: '-100.00',
      closeNetPerContract: '-100.00',
      realizedPnl: '249.35',
      coveredLots: [lot({ tradeId: 1, netAmount: '349.35' })],
    })
    const server: FakeServer = { closed: [X] }
    startFakeServer(server)
    const { queryClient } = renderPage()
    await expectRows([3])
    await expectSummary('共 1 笔平仓', '+249.35')

    // A backdated close Y re-matches X onto L2 (the backend recomputes everything).
    server.closed = [
      {
        ...X,
        openingNetTotal: '360.00',
        openingNetPerContract: '360.00',
        realizedPnl: '260.00',
        coveredLots: [lot({ tradeId: 2, netAmount: '360.00' })],
      },
      closed({
        id: 4,
        openingNetTotal: '349.35',
        openingNetPerContract: '349.35',
        closeNetAmount: '-120.00',
        closeNetPerContract: '-120.00',
        realizedPnl: '229.35',
        coveredLots: [lot({ tradeId: 1, netAmount: '349.35' })],
      }),
    ]
    await queryClient.invalidateQueries({ queryKey: ['closed-trades'] })
    await expectRows([3, 4])
    await expectSummary('共 2 笔平仓', '+489.35')
    expect(
      within(screen.getAllByTestId('closed-row')[0] as HTMLElement).getByTestId('closed-cell-pnl'),
    ).toHaveTextContent('+260.00')
  })
})

// ---------------------------------------------------------------------------
// FEAT-040 scenario M: option closes and stock sales together (backend order O4, S7, S6, S3, O2)
// ---------------------------------------------------------------------------

function soldLot(partial: Partial<ClosedSoldLot> & Pick<ClosedSoldLot, 'tradeId'>): ClosedSoldLot {
  return {
    type: 'BUY',
    tradedAt: '2026-11-03T15:00:00Z',
    tradedAtLocal: '2026-11-03T10:00:00',
    tradeDate: null,
    shares: 100,
    netAmount: '0.00',
    ...partial,
  }
}

function stockSale(
  partial: Partial<ClosedStockRecord> & Pick<ClosedStockRecord, 'id'>,
): ClosedStockRecord {
  return {
    type: 'STOCK',
    tradedAt: '2026-11-05T15:00:00Z',
    tradedAtLocal: '2026-11-05T10:00:00',
    symbol: 'IXQZ',
    expirationDate: null,
    strike: null,
    optionType: null,
    contractLabel: null,
    action: 'SELL',
    closedDirection: null,
    contracts: null,
    multiplier: null,
    shares: 100,
    openingNetTotal: '0.00',
    openingNetPerContract: null,
    costPerShare: '0.0000',
    closeNetAmount: '0.00',
    closeNetPerContract: null,
    pricePerShare: '0.0000',
    realizedPnl: '0.00',
    note: null,
    coveredLots: null,
    soldLots: [],
    hasAuditLog: false,
    ...partial,
  }
}

const O4 = closed({
  id: 4,
  symbol: 'XYZ',
  strike: '3',
  optionType: 'CALL',
  tradedAt: '2026-11-09T15:00:00Z',
  tradedAtLocal: '2026-11-09T10:00:00',
  openingNetTotal: '25.00',
  openingNetPerContract: '25.00',
  closeNetAmount: '-40.00',
  closeNetPerContract: '-40.00',
  realizedPnl: '-15.00',
  coveredLots: [lot({ tradeId: 3, netAmount: '25.00' })],
})
const S7 = stockSale({
  id: 7,
  symbol: 'XYZ',
  tradedAt: '2026-11-06T15:00:00Z',
  tradedAtLocal: '2026-11-06T10:00:00',
  shares: 100,
  openingNetTotal: '-333.33',
  costPerShare: '3.3333',
  closeNetAmount: '400.00',
  pricePerShare: '4.0000',
  realizedPnl: '66.67',
  soldLots: [
    soldLot({
      tradeId: 5,
      tradedAt: '2026-11-04T15:00:00Z',
      tradedAtLocal: '2026-11-04T10:00:00',
      netAmount: '-333.33',
    }),
  ],
})
const S6 = stockSale({
  id: 6,
  shares: 150,
  openingNetTotal: '-1125.16',
  costPerShare: '7.5011',
  closeNetAmount: '1200.00',
  pricePerShare: '8.0000',
  realizedPnl: '74.84',
  soldLots: [
    soldLot({
      tradeId: 1,
      type: 'TRANSFER_IN',
      tradedAt: '2026-10-01T04:00:00Z',
      tradedAtLocal: '2026-10-01T00:00:00',
      tradeDate: '2026-10-01',
      netAmount: '-750.00',
    }),
    soldLot({ tradeId: 4, shares: 50, netAmount: '-375.16' }),
  ],
})
const S3 = stockSale({
  id: 3,
  symbol: 'QRTV',
  tradedAt: '2026-11-01T03:30:00Z',
  tradedAtLocal: '2026-10-31T23:30:00',
  shares: 1000,
  openingNetTotal: '-400.00',
  costPerShare: '0.4000',
  closeNetAmount: '380.00',
  pricePerShare: '0.3800',
  realizedPnl: '-20.00',
  note: '盘后',
})
const O2 = closed({
  id: 2,
  symbol: 'IXQZ',
  expirationDate: '2026-11-20',
  strike: '7',
  tradedAt: '2026-10-30T14:00:00Z',
  tradedAtLocal: '2026-10-30T10:00:00',
  openingNetTotal: '45.00',
  openingNetPerContract: '45.00',
  closeNetAmount: '-10.00',
  closeNetPerContract: '-10.00',
  realizedPnl: '35.00',
  coveredLots: [lot({ tradeId: 1, netAmount: '45.00' })],
})
const SCENARIO_M: ClosedTrade[] = [O4, S7, S6, S3, O2]

function recordKeys(): string[] {
  return screen
    .queryAllByTestId('closed-row')
    .map(
      (row) =>
        `${row.getAttribute('data-record-type') ?? ''}:${row.getAttribute('data-trade-id') ?? ''}`,
    )
}

async function expectRecords(keys: string[]) {
  await waitFor(() => {
    expect(recordKeys()).toEqual(keys)
  })
}

const ALL_M = ['OPTION:4', 'STOCK:7', 'STOCK:6', 'STOCK:3', 'OPTION:2']
const OPTION_ONLY_TEST_IDS = [
  'closed-filter-expiration',
  'closed-filter-strike',
  'closed-filter-type-call',
  'closed-filter-action-btc',
]

function stockRowCells(row: HTMLElement): (string | null)[] {
  const cell = (id: string) => within(row).getByTestId(id).textContent
  return [
    cell('closed-cell-time'),
    cell('closed-cell-type'),
    cell('closed-cell-contract'),
    cell('closed-cell-action'),
    cell('closed-cell-contracts'),
    cell('closed-cell-opening-net'),
    cell('closed-cell-opening-net-per'),
    cell('closed-cell-close-net'),
    cell('closed-cell-close-net-per'),
    cell('closed-cell-pnl'),
  ]
}

const rowAt = (index: number) => screen.getAllByTestId('closed-row')[index] as HTMLElement

describe('ClosedTradesPage with stock sales (FEAT-040)', () => {
  it('shows option and stock rows together with type-specific cells (AC 1)', async () => {
    startFakeServer({ closed: SCENARIO_M })
    renderPage()
    await expectRecords(ALL_M)
    await expectSummary('共 5 笔平仓', '+141.51')

    expect(within(rowAt(0)).getByTestId('closed-cell-type')).toHaveTextContent('期权')
    expect(within(rowAt(0)).getByTestId('closed-cell-direction')).toHaveTextContent('空头')
    expect(stockRowCells(rowAt(2))).toEqual([
      '2026-11-05 10:00',
      '股票',
      'IXQZ',
      '卖出',
      '150 股',
      '−1,125.16',
      '每股 7.5011',
      '+1,200.00',
      '每股 8.0000',
      '+74.84',
    ])
    const s3 = rowAt(3)
    expect(within(s3).getByTestId('closed-cell-contracts')).toHaveTextContent('1,000 股')
    expect(within(s3).getByTestId('closed-cell-pnl')).toHaveStyle({ color: '#cf1322' })
    expect(within(s3).getByTestId('closed-cell-note')).toHaveTextContent('盘后')
    expect(within(s3).getByTestId('closed-cell-time')).toHaveTextContent('2026-10-31 23:30')
    for (const index of [1, 2, 3]) {
      const row = rowAt(index)
      expect(within(row).queryByTestId('closed-cell-direction')).not.toBeInTheDocument()
      expect(within(row).getByTestId('closed-cell-contracts').textContent).not.toContain(
        'multiplier',
      )
    }
    expect(screen.queryByTestId('closed-history')).not.toBeInTheDocument()
    const headers = [...document.querySelectorAll('th')].map((th) => th.textContent)
    for (const header of [
      '类型',
      '合约 / 标的',
      '张数 / 股数',
      '开仓净额合计 / 成本',
      '平仓净额 / 卖出实收',
    ]) {
      expect(headers).toContain(header)
    }
    expect(byTestId('closed-stock-note')).toHaveTextContent(
      '股票卖出：成本为按 FIFO 被卖出批次的成本（部分卖出按比例四舍五入到分），每股数字保留 4 位小数，仅供参考；股票转入和买入没有已实现盈亏。',
    )
  })

  it('expands sold lots of a stock sale (AC 2)', async () => {
    startFakeServer({ closed: SCENARIO_M })
    renderPage()
    await expectRecords(ALL_M)

    const s6 = rowAt(2)
    await userEvent.click(within(s6).getByTestId('closed-expand'))
    const lots = await within(s6.nextElementSibling as HTMLElement).findAllByTestId(
      'closed-sold-lot-row',
    )
    expect(
      lots.map((row) => [
        within(row).getByTestId('closed-sold-lot-time').textContent,
        within(row).getByTestId('closed-sold-lot-source').textContent,
        within(row).getByTestId('closed-sold-lot-shares').textContent,
        within(row).getByTestId('closed-sold-lot-cost').textContent,
      ]),
    ).toEqual([
      ['2026-10-01', '转入', '100 股', '−750.00'],
      ['2026-11-03 10:00', '买入', '50 股', '−375.16'],
    ])

    const o2 = rowAt(4)
    await userEvent.click(within(o2).getByTestId('closed-expand'))
    const optionLots = await within(o2.nextElementSibling as HTMLElement).findAllByTestId(
      'closed-lot-row',
    )
    expect(optionLots).toHaveLength(1)
  })

  it('filters by record type in the URL and goes back (AC 3)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_M })
    renderPage()
    await expectRecords(ALL_M)

    await userEvent.click(byTestId('closed-filter-record-type-stock'))
    await submitFilters()
    await expectRecords(['STOCK:7', 'STOCK:6', 'STOCK:3'])
    await expectSummary('共 3 笔平仓', '+121.51')
    expect(queries.at(-1)?.get('type')).toBe('STOCK')
    expect(byTestId('location')).toHaveTextContent('/closed?type=STOCK')

    await userEvent.click(screen.getByText('browser-back'))
    await expectRecords(ALL_M)
    expect(queries.at(-1)?.get('type')).toBeNull()
  })

  it('restores 期权 from the URL and combines with symbol (AC 3)', async () => {
    startFakeServer({ closed: SCENARIO_M })
    renderPage('/closed?type=OPTION')
    await expectRecords(['OPTION:4', 'OPTION:2'])
    await expectSummary('共 2 笔平仓', '+20.00')
    expect(byTestId('closed-filter-record-type-option')).toBeChecked()

    setText('closed-filter-symbol', 'ixqz')
    await userEvent.click(byTestId('closed-filter-record-type-any'))
    await submitFilters()
    await expectRecords(['STOCK:6', 'OPTION:2'])
    await expectSummary('共 2 笔平仓', '+109.84')
  })

  it('rejects an invalid record type in the URL without querying (AC 3)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_M })
    renderPage('/closed?type=FUTURE')
    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('closed-filter-record-type'))).getByText(
          '类型只能是 OPTION 或 STOCK',
        ),
      ).toBeInTheDocument()
    })
    expect(byTestId('closed-invalid')).toBeInTheDocument()
    expect(queries).toHaveLength(0)
  })

  it('shows a backend type error under the record type filter', async () => {
    startFakeServer({
      closed: SCENARIO_M,
      onList: () =>
        jsonResponse(
          {
            code: 'BAD_REQUEST',
            message: 'Invalid parameter value',
            status: 400,
            path: '/api/closed-trades',
            timestamp: '2026-11-13T15:00:00Z',
            details: [{ field: 'type', message: '类型只能是 OPTION 或 STOCK' }],
          },
          400,
        ),
    })
    renderPage('/closed?type=STOCK')
    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('closed-filter-record-type'))).getByText(
          '类型只能是 OPTION 或 STOCK',
        ),
      ).toBeInTheDocument()
    })
  })

  it('hints that option-only filters show only options (AC 4, Q90)', async () => {
    startFakeServer({ closed: SCENARIO_M })
    renderPage()
    await expectRecords(ALL_M)
    expect(screen.queryByTestId('closed-filter-option-only-hint')).not.toBeInTheDocument()

    await userEvent.click(byTestId('closed-filter-action-btc'))
    expect(await screen.findByTestId('closed-filter-option-only-hint')).toHaveTextContent(
      '按到期日 / 行权价 / Call·Put / 方向筛选时只显示期权',
    )
    await submitFilters()
    await expectRecords(['OPTION:4', 'OPTION:2'])
    await expectSummary('共 2 笔平仓', '+20.00')
    expect(await screen.findByTestId('closed-filter-option-only-hint')).toBeInTheDocument()

    // 期权 is not 不限: no hint.
    await userEvent.click(byTestId('closed-filter-record-type-option'))
    await waitFor(() => {
      expect(screen.queryByTestId('closed-filter-option-only-hint')).not.toBeInTheDocument()
    })
  })

  it('disables and empties the option-only filters for 股票, re-enables empty (AC 4, Q90)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_M })
    renderPage()
    await expectRecords(ALL_M)

    await userEvent.click(byTestId('closed-filter-action-btc'))
    setText('closed-filter-expiration', '2026-11-20')
    await userEvent.click(byTestId('closed-filter-record-type-stock'))

    await waitFor(() => {
      expect(byTestId('closed-filter-action-btc')).not.toBeChecked()
    })
    expect(byTestId('closed-filter-expiration')).toHaveValue('')
    for (const id of OPTION_ONLY_TEST_IDS) expect(byTestId(id)).toBeDisabled()
    expect(screen.queryByTestId('closed-filter-option-only-hint')).not.toBeInTheDocument()

    await submitFilters()
    await expectRecords(['STOCK:7', 'STOCK:6', 'STOCK:3'])
    await expectSummary('共 3 笔平仓', '+121.51')
    const last = queries.at(-1)
    expect(last?.get('type')).toBe('STOCK')
    expect(last?.getAll('action')).toEqual([])
    expect(last?.get('expirationDate')).toBeNull()
    expect(byTestId('location')).toHaveTextContent('/closed?type=STOCK')

    await userEvent.click(byTestId('closed-filter-record-type-any'))
    await waitFor(() => {
      for (const id of OPTION_ONLY_TEST_IDS) expect(byTestId(id)).toBeEnabled()
    })
    expect(byTestId('closed-filter-expiration')).toHaveValue('')
    expect(byTestId('closed-filter-action-btc')).not.toBeChecked()
  })

  it('keeps 股票 + option filters from a URL, disabled, and shows the empty result (AC 4)', async () => {
    const queries = startFakeServer({ closed: SCENARIO_M })
    renderPage('/closed?type=STOCK&action=BTC')
    expect(await screen.findByTestId('closed-no-match')).toHaveTextContent('没有符合条件的平仓记录')
    await expectSummary('共 0 笔平仓', '0.00')
    expect(queries.at(-1)?.get('type')).toBe('STOCK')
    expect(queries.at(-1)?.getAll('action')).toEqual(['BTC'])
    expect(byTestId('closed-filter-action-btc')).toBeChecked()
    expect(byTestId('closed-filter-action-btc')).toBeDisabled()
  })

  it('links stock symbols to the 股票 page and 历史 by record type (AC 8)', async () => {
    const edited: ClosedTrade[] = [
      O4,
      S7,
      { ...S6, hasAuditLog: true },
      S3,
      { ...O2, hasAuditLog: true },
    ]
    startFakeServer({ closed: edited })
    renderPage()
    await expectRecords(ALL_M)

    expect(within(rowAt(2)).getByTestId('closed-history')).toHaveAttribute(
      'href',
      '/audit?objectType=STOCK_TRADE&objectId=6',
    )
    expect(within(rowAt(4)).getByTestId('closed-history')).toHaveAttribute(
      'href',
      '/audit?objectType=OPTION_TRADE&objectId=2',
    )
    for (const index of [0, 1, 3]) {
      expect(within(rowAt(index)).queryByTestId('closed-history')).not.toBeInTheDocument()
    }
    expect(within(rowAt(2)).getByTestId('closed-cell-contract')).toHaveAttribute(
      'href',
      '/stocks?symbol=IXQZ',
    )
    expect(within(rowAt(4)).getByTestId('closed-cell-contract')).toHaveAttribute(
      'href',
      '/trades?symbol=IXQZ&expirationDate=2026-11-20&strike=7&optionType=PUT',
    )
  })

  it('renders an option and a stock row with the same id (type + id keys)', async () => {
    const sameId = stockSale({ id: 4, symbol: 'XYZ', realizedPnl: '1.00' })
    startFakeServer({ closed: [sameId, O4] })
    renderPage()
    await expectRecords(['STOCK:4', 'OPTION:4'])
    expect(within(rowAt(0)).getByTestId('closed-cell-contract')).toHaveTextContent('XYZ')
    expect(within(rowAt(1)).getByTestId('closed-cell-contract')).toHaveTextContent(
      'XYZ 2026-12-18 3 C',
    )
  })
})
