import { screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import userEvent from '../test/userEvent'
import type { OptionPosition, OptionPositionsResponse } from '../api/types'
import { optionPositionQueryKeys } from '../api/optionPositions'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// FEAT-010 scenario R at 2026-11-18 10:00 NY (fictional tickers), exactly as the backend returns it.

function position(
  symbol: string,
  expirationDate: string,
  strike: string,
  optionType: 'CALL' | 'PUT',
  dte: number,
  extra: Partial<OptionPosition> = {},
): OptionPosition {
  const cp = optionType === 'CALL' ? 'C' : 'P'
  return {
    symbol,
    expirationDate,
    strike,
    optionType,
    contractLabel: `${symbol} ${expirationDate} ${strike} ${cp}`,
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '0.00',
    averageNetPerContract: '0.00',
    frozenCash: '0.00',
    dte,
    expirationStatus: dte < 0 ? 'EXPIRED' : dte === 0 ? 'EXPIRES_TODAY' : 'ACTIVE',
    daysPastExpiration: dte < 0 ? -dte : 0,
    lotCount: 1,
    lots: [],
    closeAction: 'BTC',
    allowedCloseContracts: [1],
    closable: dte >= 0,
    expiryPending: false,
    expirable: dte < 0,
    expiryContracts: 1,
    expiryAt: `${expirationDate}T21:00:00Z`,
    expiryAtLocal: `${expirationDate}T16:00:00`,
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    ...extra,
  }
}

const R2 = position('QRTV', '2026-11-13', '30', 'CALL', -5, { direction: 'LONG' })
const R1 = position('XYZ', '2026-11-13', '40', 'PUT', -5, { frozenCash: '4000.00' })
const R3 = position('XYZ', '2026-11-18', '42', 'PUT', 0, { frozenCash: '8400.00' })
const R4 = position('QRTV', '2026-11-20', '25', 'PUT', 2, { direction: 'LONG' })
const R5 = position('XYZ', '2026-11-25', '38', 'PUT', 7, { frozenCash: '3800.00' })
const R6 = position('XYZ', '2026-11-27', '35', 'PUT', 9, { frozenCash: '3500.00' })
const R7 = position('QRTV', '2026-12-18', '30', 'CALL', 30, { direction: 'LONG' })
const R8 = position('QRTV', '2027-01-15', '30', 'CALL', 58, { direction: 'LONG' })

function response(positions: OptionPosition[], frozenTotal: string): OptionPositionsResponse {
  return { asOfDate: '2026-11-18', positionCount: positions.length, frozenTotal, positions }
}

const SCENARIO_R = response([R2, R1, R3, R4, R5, R6, R7, R8], '19700.00')

function startFakeServer(initial: OptionPositionsResponse) {
  const state = { positions: initial }
  const urls: string[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    urls.push(`${method} ${url}`)
    if (method === 'GET' && url === '/api/option-positions')
      return Promise.resolve(jsonResponse(state.positions))
    if (method === 'GET' && url === '/api/stock-positions')
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return { state, urls }
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{`${location.pathname}${location.search}`}</div>
}

function renderPage(route: string) {
  return renderWithProviders(
    <>
      <PositionsPage />
      <LocationProbe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function shownContracts(): (string | null)[] {
  return screen
    .queryAllByTestId('position-row')
    .map((row) => row.getAttribute('data-contract'))
}

describe('PositionsPage 到期过滤 (FEAT-010)', () => {
  it('defaults to 全部: every row in backend order, no 显示 M / N, stock section shown (AC 1)', async () => {
    const { urls } = startFakeServer(SCENARIO_R)
    renderPage('/positions')
    await screen.findAllByTestId('position-row')

    expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'all')
    expect(shownContracts()).toEqual(SCENARIO_R.positions.map((p) => p.contractLabel))
    expect(byTestId('positions-count')).toHaveTextContent('共 8 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 19,700.00')
    expect(screen.queryByTestId('positions-filter-count')).not.toBeInTheDocument()
    expect(screen.queryByTestId('positions-stock-hidden')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(urls).toContain('GET /api/stock-positions')
    })
  })

  it('selecting 已过期未处理 / 今天到期 filters rows and writes the URL (AC 2)', async () => {
    const { urls } = startFakeServer(SCENARIO_R)
    renderPage('/positions')
    await screen.findAllByTestId('position-row')

    await userEvent.click(byTestId('positions-expiry-filter-expired'))
    expect(byTestId('location')).toHaveTextContent('/positions?expiry=expired')
    expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'expired')
    expect(shownContracts()).toEqual(['QRTV 2026-11-13 30 C', 'XYZ 2026-11-13 40 P'])
    for (const row of screen.getAllByTestId('position-row')) {
      expect(within(row).getByTestId('position-cell-dte')).toHaveTextContent('已过期 5 天')
    }
    expect(byTestId('positions-filter-count')).toHaveTextContent('显示 2 / 8 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 19,700.00')
    expect(byTestId('positions-stock-hidden')).toHaveTextContent(
      "股票持仓不参与到期过滤，选择'全部'查看",
    )

    await userEvent.click(byTestId('positions-expiry-filter-today'))
    expect(byTestId('location')).toHaveTextContent('/positions?expiry=today')
    expect(shownContracts()).toEqual(['XYZ 2026-11-18 42 P'])
    expect(byTestId('positions-filter-count')).toHaveTextContent('显示 1 / 8 个持仓')

    await userEvent.click(byTestId('positions-expiry-filter-all'))
    expect(byTestId('location')).toHaveTextContent(/^\/positions$/)
    expect(shownContracts()).toHaveLength(8)
    expect(screen.queryByTestId('positions-filter-count')).not.toBeInTheDocument()
    // Only one positions request: filtering is client-side on the same data.
    expect(urls.filter((u) => u === 'GET /api/option-positions')).toHaveLength(1)
  })

  it.each([
    ['7', ['XYZ 2026-11-18 42 P', 'QRTV 2026-11-20 25 P', 'XYZ 2026-11-25 38 P']],
    [
      '14',
      ['XYZ 2026-11-18 42 P', 'QRTV 2026-11-20 25 P', 'XYZ 2026-11-25 38 P', 'XYZ 2026-11-27 35 P'],
    ],
    [
      '30',
      [
        'XYZ 2026-11-18 42 P',
        'QRTV 2026-11-20 25 P',
        'XYZ 2026-11-25 38 P',
        'XYZ 2026-11-27 35 P',
        'QRTV 2026-12-18 30 C',
      ],
    ],
  ])('?expiry=%s shows only rows with 0 ≤ DTE ≤ N (AC 3)', async (days, expected) => {
    startFakeServer(SCENARIO_R)
    renderPage(`/positions?expiry=${days}`)
    await screen.findAllByTestId('position-row')
    expect(shownContracts()).toEqual(expected)
    expect(byTestId('positions-filter-count')).toHaveTextContent(
      `显示 ${String(expected.length)} / 8 个持仓`,
    )
  })

  it('treats an unknown ?expiry as 全部 (AC 3)', async () => {
    startFakeServer(SCENARIO_R)
    renderPage('/positions?expiry=10')
    await screen.findAllByTestId('position-row')
    expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'all')
    expect(shownContracts()).toHaveLength(8)
    expect(screen.queryByTestId('positions-filter-count')).not.toBeInTheDocument()
    expect(screen.queryByTestId('positions-stock-hidden')).not.toBeInTheDocument()
  })

  it('keeps 到期待确认 (DTE 0) under 今天到期, not 已过期未处理 (AC 4)', async () => {
    const pendingR3 = { ...R3, closable: false, expiryPending: true, assignable: true }
    startFakeServer(response([R2, R1, pendingR3, R4, R5, R6, R7, R8], '19700.00'))
    renderPage('/positions?expiry=today')
    await screen.findAllByTestId('position-row')
    expect(shownContracts()).toEqual(['XYZ 2026-11-18 42 P'])
    expect(byTestId('position-expiry-pending')).toHaveTextContent('到期待确认')
    expect(byTestId('position-assign')).toBeInTheDocument()
    expect(screen.queryByTestId('position-close')).not.toBeInTheDocument()

    await userEvent.click(byTestId('positions-expiry-filter-expired'))
    expect(shownContracts()).toEqual(['QRTV 2026-11-13 30 C', 'XYZ 2026-11-13 40 P'])
  })

  it('drops rows that no longer match after a refetch, keeping the filter (AC 5)', async () => {
    const { state } = startFakeServer(SCENARIO_R)
    const { queryClient } = renderPage('/positions?expiry=expired')
    await screen.findAllByTestId('position-row')
    expect(shownContracts()).toHaveLength(2)

    // R1 expired worthless: the page refetches through the existing invalidation.
    state.positions = response([R2, R3, R4, R5, R6, R7, R8], '15700.00')
    await queryClient.invalidateQueries({ queryKey: optionPositionQueryKeys.all })
    await waitFor(() => {
      expect(shownContracts()).toEqual(['QRTV 2026-11-13 30 C'])
    })
    expect(byTestId('positions-filter-count')).toHaveTextContent('显示 1 / 7 个持仓')
    expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 15,700.00')
    expect(byTestId('positions-expiry-filter')).toHaveAttribute('data-value', 'expired')
    expect(byTestId('location')).toHaveTextContent('/positions?expiry=expired')
  })

  it.each([
    ['expired', '没有已过期未处理的持仓'],
    ['today', '没有今天到期的持仓'],
    ['7', '没有 7 天内到期的持仓'],
    ['14', '没有 14 天内到期的持仓'],
    ['30', '没有 30 天内到期的持仓'],
  ])('shows the empty message for ?expiry=%s (AC 6)', async (filter, message) => {
    startFakeServer(response([R8], '0.00'))
    renderPage(`/positions?expiry=${filter}`)
    expect(await screen.findByTestId('positions-expiry-empty')).toHaveTextContent(message)
    expect(screen.queryByTestId('position-row')).not.toBeInTheDocument()
    expect(screen.queryByTestId('positions-empty')).not.toBeInTheDocument()
    expect(byTestId('positions-filter-count')).toHaveTextContent('显示 0 / 1 个持仓')
  })

  it('keeps the original empty state when the account has no option positions', async () => {
    startFakeServer(response([], '0.00'))
    renderPage('/positions?expiry=7')
    expect(await screen.findByTestId('positions-empty')).toHaveTextContent('暂无持仓')
    expect(screen.queryByTestId('positions-expiry-empty')).not.toBeInTheDocument()
  })
})
