import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  ApiErrorResponse,
  NonTradingDay,
  NonTradingDaysResponse,
  OptionPosition,
  OptionPositionsResponse,
  TradingCalendarStatus,
} from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'
import { TradeEntryPage } from './TradeEntryPage'

// FEAT-009 frontend: 剩余交易日 column, stale warning, 到期日 picker with backend non-trading days.
// Scenario per the requirement: today 2026-11-23 (Mon) 10:00 New York. Tickers are fictional.

const NOW = new Date('2026-11-23T15:00:00Z')

/** Holidays the fake backend knows (weekends are added for any requested range). */
const HOLIDAYS: Record<string, string> = {
  '2026-11-26': 'Thanksgiving Day',
  '2026-12-25': 'Christmas Day',
  '2027-01-01': "New Year's Day",
  '2027-03-26': 'Good Friday',
}

/** Fake backend for `non-trading-days`: weekends + HOLIDAYS within [from, to]. */
function nonTradingDays(from: string, to: string): NonTradingDaysResponse {
  const days: NonTradingDay[] = []
  for (
    let d = new Date(`${from}T00:00:00Z`);
    d <= new Date(`${to}T00:00:00Z`);
    d = new Date(d.getTime() + 86_400_000)
  ) {
    const date = d.toISOString().slice(0, 10)
    const weekday = d.getUTCDay()
    if (weekday === 0 || weekday === 6) {
      days.push({ date, type: 'WEEKEND', name: weekday === 6 ? '星期六' : '星期日', source: null })
    } else if (HOLIDAYS[date] !== undefined) {
      days.push({ date, type: 'HOLIDAY', name: HOLIDAYS[date], source: 'FINNHUB' })
    }
  }
  return { from, to, days }
}

const STATUS_OK: TradingCalendarStatus = {
  stale: false,
  warning: null,
  lastAttemptAt: '2026-11-23T13:00:00Z',
  lastAttemptResult: 'SUCCESS',
  lastFailureDetail: null,
  lastSuccessAt: '2026-11-23T13:00:00Z',
  lastSuccessAtLocal: '2026-11-23T08:00:00',
  coveredYears: [2023, 2024, 2025, 2026, 2027],
}

const STATUS_STALE: TradingCalendarStatus = {
  ...STATUS_OK,
  stale: true,
  warning: '休市日数据未更新（上次成功：2026-11-20 08:00 美东）',
  lastAttemptAt: '2026-11-21T13:00:00Z',
  lastAttemptResult: 'HTTP_ERROR',
  lastFailureDetail: 'HTTP 500',
  lastSuccessAt: '2026-11-20T13:00:00Z',
  lastSuccessAtLocal: '2026-11-20T08:00:00',
}

const STATUS_NEVER: TradingCalendarStatus = {
  stale: true,
  warning: '休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）',
  lastAttemptAt: '2026-11-23T15:00:00Z',
  lastAttemptResult: 'NO_KEY',
  lastFailureDetail: null,
  lastSuccessAt: null,
  lastSuccessAtLocal: null,
  coveredYears: [],
}

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'QRTV',
    expirationDate: '2026-11-27',
    strike: '50',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '120.00',
    averageNetPerContract: '120.00',
    frozenCash: '5000.00',
    dte: 4,
    remainingTradingDays: 3,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: 1,
    lots: [
      {
        tradeId: 1,
        action: 'STO',
        tradedAt: '2026-11-20T15:00:00Z',
        tradedAtLocal: '2026-11-20T10:00:00',
        originalContracts: 1,
        remainingContracts: 1,
        originalNetAmount: '120.00',
        remainingNetAmount: '120.00',
        originalFrozen: '5000.00',
        remainingFrozen: '5000.00',
        note: null,
      },
    ],
    closeAction: 'BTC',
    allowedCloseContracts: [1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: 1,
    expiryAt: '2026-11-27T21:00:00Z',
    expiryAtLocal: '2026-11-27T16:00:00',
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const P1 = position({})
const P2 = position({
  expirationDate: '2026-12-18',
  strike: '45',
  openingNetTotal: '150.00',
  averageNetPerContract: '150.00',
  frozenCash: '4500.00',
  dte: 25,
  remainingTradingDays: 18,
})
const EXPIRED = position({
  symbol: 'IXQZ',
  expirationDate: '2026-11-20',
  strike: '9',
  dte: -3,
  remainingTradingDays: null,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
})
const TODAY = position({
  symbol: 'IXQZ',
  expirationDate: '2026-11-23',
  strike: '8',
  dte: 0,
  remainingTradingDays: 0,
  expirationStatus: 'EXPIRES_TODAY',
})

const POSITIONS: OptionPositionsResponse = {
  asOfDate: '2026-11-23',
  positionCount: 4,
  frozenTotal: '19500.00',
  positions: [P1, P2, TODAY, EXPIRED],
}

interface RecordedRequest {
  method: string
  url: string
  accountHeader: string | null
  body: unknown
}

interface FakeServer {
  status?: () => Response
  calendar?: (from: string, to: string) => Response
  onPost?: (body: unknown) => Response
}

function startFakeServer(server: FakeServer = {}) {
  const requests: RecordedRequest[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const headers = new Headers(init?.headers)
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, accountHeader: headers.get('X-Account-Id'), body })
    if (method === 'GET' && url === '/api/trading-calendar/status') {
      return Promise.resolve(server.status ? server.status() : jsonResponse(STATUS_OK))
    }
    if (method === 'GET' && url.startsWith('/api/trading-calendar/non-trading-days?')) {
      const params = new URL(url, 'http://localhost').searchParams
      const from = params.get('from') ?? ''
      const to = params.get('to') ?? ''
      return Promise.resolve(
        server.calendar ? server.calendar(from, to) : jsonResponse(nonTradingDays(from, to)),
      )
    }
    if (method === 'GET' && url === '/api/option-positions') {
      return Promise.resolve(jsonResponse(POSITIONS))
    }
    if (method === 'GET' && url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    if (method === 'GET' && url === '/api/cash/summary') {
      return Promise.resolve(
        jsonResponse({ totalCash: '10770.00', frozenCash: '0.00', availableCash: '10770.00' }),
      )
    }
    if (method === 'POST' && server.onPost) return Promise.resolve(server.onPost(body))
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

const byTestId = (id: string) => screen.getByTestId(id)

function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-cell-contract')
    .find((cell) => cell.textContent === contract)
    ?.closest('tr')
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

/** The panel cell (td) of a date; the picker must be open on its month. */
async function dayCell(date: string): Promise<HTMLElement> {
  const inner = await screen.findByTestId(`open-trade-expiration-day-${date}`)
  const cell = inner.closest('td')
  if (!cell) throw new Error(`cell ${date} not found`)
  return cell
}

async function fillOpenTrade(values: { action: 'sto' | 'bto' | 'btc'; expiration: string }) {
  const user = userEvent.setup()
  await user.click(await screen.findByTestId(`open-trade-action-${values.action}`))
  await user.click(byTestId('open-trade-type-call'))
  const text: [string, string][] = [
    ['open-trade-symbol', 'IXQZ'],
    ['open-trade-expiration', values.expiration],
    ['open-trade-strike', '30'],
    ['open-trade-contracts', '1'],
    ['open-trade-net-amount', '80.00'],
    ['open-trade-time', '2026-11-23T10:00'],
  ]
  for (const [testId, value] of text) {
    fireEvent.change(byTestId(testId), { target: { value } })
  }
  return user
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('positions page (FEAT-009)', () => {
  it('shows the backend remaining trading days next to DTE, "—" when null', async () => {
    startFakeServer()
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-cell-contract')

    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent)
    expect(headers.indexOf('剩余交易日')).toBe(headers.indexOf('DTE') + 1)

    const cell = (contract: string, testId: string) =>
      within(rowOf(contract)).getByTestId(testId).textContent
    expect(cell('QRTV 2026-11-27 50 P', 'position-cell-dte')).toBe('4')
    expect(cell('QRTV 2026-11-27 50 P', 'position-cell-remaining-trading-days')).toBe('3')
    expect(cell('QRTV 2026-12-18 45 P', 'position-cell-dte')).toBe('25')
    expect(cell('QRTV 2026-12-18 45 P', 'position-cell-remaining-trading-days')).toBe('18')
    expect(cell('IXQZ 2026-11-23 8 P', 'position-cell-remaining-trading-days')).toBe('0')
    expect(cell('IXQZ 2026-11-20 9 P', 'position-cell-dte')).toBe('已过期 3 天')
    expect(cell('IXQZ 2026-11-20 9 P', 'position-cell-remaining-trading-days')).toBe('—')
  })

  it('explains the column on hover', async () => {
    startFakeServer()
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-cell-contract')
    const user = userEvent.setup()
    await user.hover(within(screen.getByRole('columnheader', { name: '剩余交易日' })).getByText('剩余交易日'))
    expect(
      await screen.findByText('今天之后到到期日（含）的美股交易日数，不含周末和休市日'),
    ).toBeInTheDocument()
  })

  it('shows "—" when an older response has no remainingTradingDays', async () => {
    const legacy = position({})
    delete legacy.remainingTradingDays
    mockFetch().mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ''
      if (url === '/api/option-positions') {
        return Promise.resolve(jsonResponse({ ...POSITIONS, positionCount: 1, positions: [legacy] }))
      }
      if (url === '/api/stock-positions') {
        return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
      }
      if (url === '/api/trading-calendar/status') return Promise.resolve(jsonResponse(STATUS_OK))
      return Promise.reject(new Error(`Unexpected request ${url}`))
    })
    renderWithProviders(<PositionsPage />)
    expect(await screen.findByTestId('position-cell-remaining-trading-days')).toHaveTextContent(
      '—',
    )
  })

  it('shows no stale warning while the holiday data is up to date', async () => {
    const requests = startFakeServer()
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-cell-contract')
    await waitFor(() => {
      expect(requests.some((r) => r.url === '/api/trading-calendar/status')).toBe(true)
    })
    expect(screen.queryByTestId('trading-calendar-stale-warning')).not.toBeInTheDocument()
  })

  it('shows the backend warning verbatim when the data is stale, without X-Account-Id', async () => {
    const requests = startFakeServer({ status: () => jsonResponse(STATUS_STALE) })
    renderWithProviders(<PositionsPage />)
    expect(await screen.findByTestId('trading-calendar-stale-warning')).toHaveTextContent(
      '休市日数据未更新（上次成功：2026-11-20 08:00 美东）',
    )
    const status = requests.find((r) => r.url === '/api/trading-calendar/status')
    expect(status?.accountHeader).toBeNull()
    // Positions themselves are still account-scoped.
    expect(requests.find((r) => r.url === '/api/option-positions')?.accountHeader).toBe('1')
  })

  it('keeps the page working when the status request fails', async () => {
    startFakeServer({
      status: () =>
        jsonResponse(
          {
            code: 'INTERNAL_ERROR',
            message: 'Internal server error',
            status: 500,
            path: '/api/trading-calendar/status',
            timestamp: '2026-11-23T15:00:00Z',
            details: [],
          },
          500,
        ),
    })
    renderWithProviders(<PositionsPage />)
    expect(await screen.findAllByTestId('position-cell-contract')).toHaveLength(4)
    expect(screen.queryByTestId('trading-calendar-stale-warning')).not.toBeInTheDocument()
  })
})

describe('trade entry form (FEAT-009)', () => {
  it('shows the never-succeeded warning above the form', async () => {
    startFakeServer({ status: () => jsonResponse(STATUS_NEVER) })
    renderWithProviders(<TradeEntryPage />)
    expect(await screen.findByTestId('trading-calendar-stale-warning')).toHaveTextContent(
      '休市日数据未更新（从未成功获取，暂按 NYSE 规则推算）',
    )
  })

  it('loads the visible months without X-Account-Id', async () => {
    const requests = startFakeServer()
    renderWithProviders(<TradeEntryPage />)
    await waitFor(() => {
      expect(
        requests.filter((r) => r.url.startsWith('/api/trading-calendar/non-trading-days')),
      ).toHaveLength(3)
    })
    const calendar = requests.filter((r) =>
      r.url.startsWith('/api/trading-calendar/non-trading-days'),
    )
    expect(calendar.map((r) => r.url).sort()).toEqual([
      '/api/trading-calendar/non-trading-days?from=2026-10-01&to=2026-10-31',
      '/api/trading-calendar/non-trading-days?from=2026-11-01&to=2026-11-30',
      '/api/trading-calendar/non-trading-days?from=2026-12-01&to=2026-12-31',
    ])
    expect(calendar.every((r) => r.accountHeader === null)).toBe(true)
  })

  it('disables the backend non-trading days in the picker (Nov 2026 and Mar 2027)', async () => {
    const requests = startFakeServer()
    renderWithProviders(<TradeEntryPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-trade-action-sto'))
    await user.click(byTestId('open-trade-expiration'))

    await waitFor(async () => {
      expect(await dayCell('2026-11-26')).toHaveClass('ant-picker-cell-disabled')
    })
    expect(await dayCell('2026-11-28')).toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-11-29')).toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-11-27')).not.toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-11-25')).not.toHaveClass('ant-picker-cell-disabled')
    expect(screen.getByTestId('open-trade-expiration-day-2026-11-26')).toHaveAttribute(
      'title',
      'Thanksgiving Day',
    )

    // Flip to March 2027: that month (and neighbours) are requested, Good Friday disabled.
    const next = document.querySelector<HTMLElement>('.ant-picker-header-next-btn')
    if (!next) throw new Error('next month button not found')
    for (let i = 0; i < 4; i += 1) await user.click(next)
    await waitFor(async () => {
      expect(await dayCell('2027-03-26')).toHaveClass('ant-picker-cell-disabled')
    })
    expect(await dayCell('2027-03-25')).not.toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2027-03-27')).toHaveClass('ant-picker-cell-disabled')
    expect(requests.map((r) => r.url)).toContain(
      '/api/trading-calendar/non-trading-days?from=2027-03-01&to=2027-03-31',
    )

    // Picking a trading day fills the field.
    await user.click(await dayCell('2027-03-25'))
    expect(byTestId('open-trade-expiration')).toHaveValue('2027-03-25')
  })

  it('reports a typed non-trading day under the field and does not submit', async () => {
    const requests = startFakeServer()
    renderWithProviders(<TradeEntryPage />)
    const user = await fillOpenTrade({ action: 'bto', expiration: '2027-03-26' })

    expect(
      await within(formItemOf(byTestId('open-trade-expiration'))).findByText('所选日期不是美股交易日'),
    ).toBeInTheDocument()
    expect(byTestId('open-trade-expiration')).toHaveValue('2027-03-26')
    await user.click(byTestId('open-trade-submit'))
    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('open-trade-expiration'))).getByText('所选日期不是美股交易日'),
      ).toBeInTheDocument()
    })
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('submits a typed trading day', async () => {
    const posted: unknown[] = []
    startFakeServer({
      onPost: (body) => {
        posted.push(body)
        return jsonResponse(
          {
            code: 'CASH_INSUFFICIENT',
            message: '可用现金不足',
            status: 422,
            path: '/api/option-trades',
            timestamp: '2026-11-23T15:00:00Z',
            details: [],
          },
          422,
        )
      },
    })
    renderWithProviders(<TradeEntryPage />)
    const user = await fillOpenTrade({ action: 'sto', expiration: '2026-11-27' })
    await user.click(byTestId('open-trade-submit'))
    await waitFor(() => {
      expect(posted).toHaveLength(1)
    })
    expect(posted[0]).toMatchObject({ action: 'STO', expirationDate: '2026-11-27' })
  })

  it('does not restrict the expiry of closes (BTC on a Saturday expiry)', async () => {
    const posted: unknown[] = []
    startFakeServer({
      onPost: (body) => {
        posted.push(body)
        return jsonResponse(
          {
            code: 'POSITION_INSUFFICIENT',
            message: '没有可平的持仓',
            status: 422,
            path: '/api/option-trades/closes',
            timestamp: '2026-11-23T15:00:00Z',
            details: [],
          },
          422,
        )
      },
    })
    renderWithProviders(<TradeEntryPage />)
    const user = await fillOpenTrade({ action: 'btc', expiration: '2026-11-28' })
    await user.click(byTestId('open-trade-submit'))
    await waitFor(() => {
      expect(posted).toHaveLength(1)
    })
    expect(screen.queryByText('所选日期不是美股交易日')).not.toBeInTheDocument()
  })

  it('shows the backend EXPIRATION_NOT_TRADING_DAY message verbatim and keeps the input', async () => {
    const rejection: ApiErrorResponse = {
      code: 'EXPIRATION_NOT_TRADING_DAY',
      message: '到期日 2027-03-26 不是美股交易日（Good Friday）',
      status: 422,
      path: '/api/option-trades',
      timestamp: '2026-11-23T15:00:00Z',
      details: [],
    }
    // The calendar lookup fails, so nothing is disabled and only the backend rejects it.
    startFakeServer({
      calendar: () => jsonResponse({ ...rejection, code: 'INTERNAL_ERROR', status: 500 }, 500),
      onPost: () => jsonResponse(rejection, 422),
    })
    renderWithProviders(<TradeEntryPage />)
    expect(await screen.findByTestId('open-trade-expiration-calendar-error')).toHaveTextContent(
      '休市日查询失败，暂不禁用非交易日，保存时由后端校验',
    )
    const user = await fillOpenTrade({ action: 'bto', expiration: '2027-03-26' })
    await user.click(byTestId('open-trade-submit'))

    const error = await screen.findByTestId('open-trade-error')
    expect(error).toHaveTextContent('到期日 2027-03-26 不是美股交易日（Good Friday）')
    expect(error).toHaveAttribute('data-error-code', 'EXPIRATION_NOT_TRADING_DAY')
    expect(byTestId('open-trade-expiration')).toHaveValue('2027-03-26')
  })

  it('does not disable anything when the calendar lookup fails', async () => {
    startFakeServer({
      calendar: () => jsonResponse({ code: 'X' }, 500),
    })
    renderWithProviders(<TradeEntryPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-trade-action-sto'))
    await screen.findByTestId('open-trade-expiration-calendar-error')
    await user.click(byTestId('open-trade-expiration'))
    expect(await dayCell('2026-11-26')).not.toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-11-28')).not.toHaveClass('ant-picker-cell-disabled')
  })
})
