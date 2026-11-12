import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import { useLocation, useNavigate } from 'react-router-dom'
import type { PnlSummary } from '../api/types'
import { App } from '../App'
import {
  closedUrlForMonth,
  closedUrlForSymbol,
  monthBounds,
  monthRangeWithin,
  validatePnlRange,
} from '../components/pnl/pnlSummarySearch'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PnlSummaryPage } from './PnlSummaryPage'

/** FEAT-008 AC 3 (scenario Q, all time). */
const ALL_TIME: PnlSummary = {
  startDate: null,
  endDate: null,
  closedCount: 6,
  realizedPnlTotal: '703.05',
  bySymbol: [
    { symbol: 'AAPL', closedCount: 3, realizedPnl: '248.70' },
    { symbol: 'MSFT', closedCount: 1, realizedPnl: '350.00' },
    { symbol: 'TSLA', closedCount: 1, realizedPnl: '120.00' },
    { symbol: 'XYZ', closedCount: 1, realizedPnl: '-15.65' },
  ],
  byMonth: [
    { month: '2026-11', closedCount: 4, realizedPnl: '633.05' },
    { month: '2026-10', closedCount: 2, realizedPnl: '70.00' },
  ],
}

/** AC 4: 2026-11-01 .. 2026-11-06. */
const NOV_1_6: PnlSummary = {
  startDate: '2026-11-01',
  endDate: '2026-11-06',
  closedCount: 3,
  realizedPnlTotal: '993.05',
  bySymbol: [
    { symbol: 'AAPL', closedCount: 1, realizedPnl: '658.70' },
    { symbol: 'MSFT', closedCount: 1, realizedPnl: '350.00' },
    { symbol: 'XYZ', closedCount: 1, realizedPnl: '-15.65' },
  ],
  byMonth: [{ month: '2026-11', closedCount: 3, realizedPnl: '993.05' }],
}

const EMPTY: PnlSummary = {
  startDate: null,
  endDate: null,
  closedCount: 0,
  realizedPnlTotal: '0.00',
  bySymbol: [],
  byMonth: [],
}

function startFakeServer(respond: (query: URLSearchParams) => Response) {
  const queries: string[] = []
  mockFetch().mockImplementation((input) => {
    const url = typeof input === 'string' ? input : ''
    const parsed = new URL(url, 'http://localhost')
    if (parsed.pathname === '/api/pnl-summary') {
      queries.push(parsed.search)
      return Promise.resolve(respond(parsed.searchParams))
    }
    if (parsed.pathname === '/api/closed-trades') {
      return Promise.resolve(
        jsonResponse({
          items: [],
          page: 1,
          size: 20,
          totalItems: 0,
          totalPages: 0,
          filteredCount: 0,
          filteredRealizedPnlTotal: '0.00',
        }),
      )
    }
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return queries
}

function byRange(query: URLSearchParams): Response {
  if (query.get('startDate') === '2026-11-01' && query.get('endDate') === '2026-11-06') {
    return jsonResponse(NOV_1_6)
  }
  if (query.get('startDate') === '2027-01-01') {
    return jsonResponse({ ...EMPTY, startDate: '2027-01-01' })
  }
  return jsonResponse(ALL_TIME)
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

function renderPage(route = '/pnl-summary') {
  return renderWithProviders(
    <>
      <PnlSummaryPage />
      <Probe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function rows(testId: string): string[][] {
  return screen
    .getAllByTestId(testId)
    .map((row) => [...row.querySelectorAll('td')].map((cell) => cell.textContent))
}

function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

describe('PnlSummaryPage', () => {
  it('shows the total, by-symbol and by-month tables with backend totals rows (AC 3)', async () => {
    const queries = startFakeServer(byRange)
    renderPage()

    expect(await screen.findByTestId('pnl-total-line')).toHaveTextContent(
      '共 6 笔平仓，已实现盈亏合计 +703.05',
    )
    expect(byTestId('pnl-total')).toHaveStyle({ color: '#389e0d' })
    expect(rows('pnl-symbol-row')).toEqual([
      ['AAPL', '3', '+248.70'],
      ['MSFT', '1', '+350.00'],
      ['TSLA', '1', '+120.00'],
      ['XYZ', '1', '−15.65'],
    ])
    expect(rows('pnl-month-row')).toEqual([
      ['2026-11', '4', '+633.05'],
      ['2026-10', '2', '+70.00'],
    ])
    expect(byTestId('pnl-symbol-total-row')).toHaveTextContent('合计6+703.05')
    expect(byTestId('pnl-month-total-row')).toHaveTextContent('合计6+703.05')
    const xyz = screen.getAllByTestId('pnl-symbol-row')[3] as HTMLElement
    expect(within(xyz).getByTestId('pnl-symbol-pnl')).toHaveStyle({ color: '#cf1322' })
    expect(queries).toEqual([''])
    expect(
      screen.getByText(/已实现盈亏只来自平仓，已含手续费；不含入金、出金和利息等/),
    ).toBeInTheDocument()
  })

  it('uses the backend totals for the totals rows (no client-side sums)', async () => {
    startFakeServer(() =>
      jsonResponse({
        ...ALL_TIME,
        closedCount: 99,
        realizedPnlTotal: '1.23',
      }),
    )
    renderPage()
    expect(await screen.findByTestId('pnl-symbol-total-count')).toHaveTextContent('99')
    expect(byTestId('pnl-symbol-total-pnl')).toHaveTextContent('+1.23')
    expect(byTestId('pnl-month-total-pnl')).toHaveTextContent('+1.23')
  })

  it('applies a date range from the form and keeps it in the URL (AC 4)', async () => {
    const queries = startFakeServer(byRange)
    renderPage()
    await screen.findByTestId('pnl-total-line')

    fireEvent.change(byTestId('pnl-filter-start-date'), { target: { value: '2026-11-01' } })
    fireEvent.change(byTestId('pnl-filter-end-date'), { target: { value: '2026-11-06' } })
    await userEvent.click(byTestId('pnl-filter-submit'))

    await waitFor(() => {
      expect(byTestId('pnl-total-line')).toHaveTextContent('共 3 笔平仓，已实现盈亏合计 +993.05')
    })
    expect(queries.at(-1)).toBe('?startDate=2026-11-01&endDate=2026-11-06')
    expect(byTestId('location')).toHaveTextContent(
      '/pnl-summary?startDate=2026-11-01&endDate=2026-11-06',
    )
    expect(rows('pnl-month-row')).toEqual([['2026-11', '3', '+993.05']])

    await userEvent.click(screen.getByText('browser-back'))
    await waitFor(() => {
      expect(byTestId('pnl-total-line')).toHaveTextContent('共 6 笔平仓')
    })
    expect(byTestId('pnl-filter-start-date')).toHaveValue('')
  })

  it('restores the range from a bookmarked URL (AC 4)', async () => {
    startFakeServer(byRange)
    renderPage('/pnl-summary?startDate=2026-11-01&endDate=2026-11-06')
    expect(await screen.findByTestId('pnl-total-line')).toHaveTextContent('+993.05')
    expect(byTestId('pnl-filter-start-date')).toHaveValue('2026-11-01')
    expect(byTestId('pnl-filter-end-date')).toHaveValue('2026-11-06')
  })

  it('rejects a reversed range and bad dates without querying (AC 4)', async () => {
    const queries = startFakeServer(byRange)
    renderPage('/pnl-summary?startDate=2026-11-07&endDate=2026-11-06')
    expect(
      within(formItemOf(byTestId('pnl-filter-end-date'))).getByText('开始日期不能晚于结束日期'),
    ).toBeInTheDocument()
    expect(byTestId('pnl-invalid')).toBeInTheDocument()
    expect(queries).toHaveLength(0)

    await userEvent.click(byTestId('pnl-filter-clear'))
    expect(await screen.findByTestId('pnl-total-line')).toHaveTextContent('+703.05')
    expect(byTestId('location')).toHaveTextContent(/^\/pnl-summary$/)
  })

  it('shows backend date errors under the field', async () => {
    startFakeServer(() =>
      jsonResponse(
        {
          code: 'BAD_REQUEST',
          message: 'Malformed request',
          status: 400,
          path: '/api/pnl-summary',
          timestamp: '2026-11-12T15:00:00Z',
          details: [{ field: 'startDate', message: '日期格式不正确（YYYY-MM-DD）' }],
        },
        400,
      ),
    )
    renderPage('/pnl-summary?startDate=2026-02-30')
    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('pnl-filter-start-date'))).getByText(
          '日期格式不正确（YYYY-MM-DD）',
        ),
      ).toBeInTheDocument()
    })
  })

  it('shows the empty states with 0.00 (AC 4, 7)', async () => {
    startFakeServer(byRange)
    renderPage('/pnl-summary?startDate=2027-01-01')
    expect(await screen.findByTestId('pnl-empty')).toHaveTextContent('所选范围内没有平仓记录')
    expect(byTestId('pnl-total-line')).toHaveTextContent('共 0 笔平仓，已实现盈亏合计 0.00')
    expect(screen.queryByTestId('pnl-by-symbol')).not.toBeInTheDocument()
  })

  it('shows 暂无平仓记录 without a range (AC 7)', async () => {
    startFakeServer(() => jsonResponse(EMPTY))
    renderPage()
    expect(await screen.findByTestId('pnl-empty')).toHaveTextContent('暂无平仓记录')
  })

  it('links symbols and months to /closed with the range (AC 5)', async () => {
    startFakeServer(byRange)
    renderPage('/pnl-summary?startDate=2026-11-01&endDate=2026-11-06')
    await screen.findByTestId('pnl-total-line')

    const aapl = screen.getAllByTestId('pnl-symbol-link')[0] as HTMLElement
    expect(aapl).toHaveAttribute(
      'href',
      '/closed?symbol=AAPL&startDate=2026-11-01&endDate=2026-11-06',
    )
    expect(screen.getAllByTestId('pnl-month-link')[0]).toHaveAttribute(
      'href',
      '/closed?startDate=2026-11-01&endDate=2026-11-06',
    )
  })

  it('opens /closed with the month range and the filters filled in (AC 5)', async () => {
    startFakeServer(byRange)
    renderWithProviders(<App />, { route: '/pnl-summary' })
    await screen.findByTestId('pnl-total-line')

    const october = screen.getAllByTestId('pnl-month-link')[1] as HTMLElement
    expect(october).toHaveAttribute('href', '/closed?startDate=2026-10-01&endDate=2026-10-31')
    await userEvent.click(october)
    expect(await screen.findByTestId('closed-page')).toBeInTheDocument()
    expect(byTestId('closed-filter-start-date')).toHaveValue('2026-10-01')
    expect(byTestId('closed-filter-end-date')).toHaveValue('2026-10-31')
  })

  it('shows a load error with retry', async () => {
    let calls = 0
    startFakeServer(() => {
      calls += 1
      return calls === 1
        ? jsonResponse(
            {
              code: 'INTERNAL_ERROR',
              message: 'Internal server error',
              status: 500,
              path: '/api/pnl-summary',
              timestamp: '2026-11-12T15:00:00Z',
              details: [],
            },
            500,
          )
        : jsonResponse(ALL_TIME)
    })
    renderPage()
    expect(await screen.findByTestId('pnl-error')).toHaveTextContent('INTERNAL_ERROR')
    await userEvent.click(byTestId('pnl-retry'))
    expect(await screen.findByTestId('pnl-total-line')).toHaveTextContent('+703.05')
  })
})

describe('pnlSummarySearch', () => {
  it('computes month bounds including leap years', () => {
    expect(monthBounds('2026-10')).toEqual({ first: '2026-10-01', last: '2026-10-31' })
    expect(monthBounds('2026-11')).toEqual({ first: '2026-11-01', last: '2026-11-30' })
    expect(monthBounds('2026-02')).toEqual({ first: '2026-02-01', last: '2026-02-28' })
    expect(monthBounds('2028-02')).toEqual({ first: '2028-02-01', last: '2028-02-29' })
    expect(monthBounds('2026-12')).toEqual({ first: '2026-12-01', last: '2026-12-31' })
  })

  it('intersects a month with the current range (AC 5)', () => {
    const none = { startDate: '', endDate: '' }
    expect(monthRangeWithin('2026-10', none)).toEqual({
      startDate: '2026-10-01',
      endDate: '2026-10-31',
    })
    expect(monthRangeWithin('2026-10', { startDate: '2026-10-30', endDate: '2026-11-30' })).toEqual(
      {
        startDate: '2026-10-30',
        endDate: '2026-10-31',
      },
    )
    expect(monthRangeWithin('2026-11', { startDate: '2026-10-30', endDate: '2026-11-30' })).toEqual(
      {
        startDate: '2026-11-01',
        endDate: '2026-11-30',
      },
    )
    expect(monthRangeWithin('2026-11', { startDate: '', endDate: '2026-11-06' })).toEqual({
      startDate: '2026-11-01',
      endDate: '2026-11-06',
    })
    expect(closedUrlForMonth('2026-10', { startDate: '2026-10-30', endDate: '2026-11-30' })).toBe(
      '/closed?startDate=2026-10-30&endDate=2026-10-31',
    )
    expect(closedUrlForSymbol('AAPL', none)).toBe('/closed?symbol=AAPL')
  })

  it('validates the range like the backend', () => {
    expect(validatePnlRange({ startDate: '', endDate: '' })).toEqual({})
    expect(validatePnlRange({ startDate: '2026-11-06', endDate: '2026-11-06' })).toEqual({})
    expect(validatePnlRange({ startDate: '2026-11-07', endDate: '2026-11-06' })).toEqual({
      endDate: '开始日期不能晚于结束日期',
    })
    expect(validatePnlRange({ startDate: '20261101', endDate: '' })).toEqual({
      startDate: '日期格式不正确（YYYY-MM-DD）',
    })
  })
})
