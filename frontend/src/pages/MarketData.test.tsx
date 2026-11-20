import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  MarketDataRefreshResponse,
  MarketDataStatus,
  OptionPosition,
  OptionPositionsResponse,
  OptionQuote,
  StockPositionsResponse,
  UnderlyingQuote,
} from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// ---------------------------------------------------------------------------
// FEAT-016 scene (fictional tickers QRTV / IXQZ), 2026-11-23 after the 10:00 fetch.
// ---------------------------------------------------------------------------

function underlying(partial: Partial<UnderlyingQuote> & Pick<UnderlyingQuote, 'symbol'>) {
  const quote: UnderlyingQuote = {
    price: null,
    automaticPrice: null,
    source: null,
    quoteTime: null,
    quoteTimeLocal: null,
    manual: false,
    manualEnteredAt: null,
    manualEnteredAtLocal: null,
    fetchFailed: false,
    lastSuccessAt: null,
    lastSuccessAtLocal: null,
    staleLabel: null,
    ...partial,
  }
  return quote
}

function optionQuote(partial: Partial<OptionQuote>): OptionQuote {
  return {
    bid: null,
    ask: null,
    mark: null,
    automaticMark: null,
    markBasis: null,
    source: null,
    quoteTime: null,
    quoteTimeLocal: null,
    contractStatus: 'NEVER_QUOTED',
    contractStatusLabel: null,
    manual: false,
    manualEnteredAt: null,
    manualEnteredAtLocal: null,
    fetchFailed: false,
    lastSuccessAt: null,
    lastSuccessAtLocal: null,
    staleLabel: null,
    ...partial,
  }
}

const QRTV_QUOTE = underlying({
  symbol: 'QRTV',
  price: '52.40',
  automaticPrice: '52.40',
  source: 'CBOE',
  quoteTime: '2026-11-23T14:45:00Z',
  quoteTimeLocal: '2026-11-23T09:45:00',
  lastSuccessAt: '2026-11-23T15:00:00Z',
  lastSuccessAtLocal: '2026-11-23T10:00:00',
})
const IXQZ_FINNHUB = underlying({
  symbol: 'IXQZ',
  price: '61.05',
  automaticPrice: '61.05',
  source: 'FINNHUB',
  quoteTime: '2026-11-23T14:59:58Z',
  quoteTimeLocal: '2026-11-23T09:59:58',
  lastSuccessAt: '2026-11-23T15:00:00Z',
  lastSuccessAtLocal: '2026-11-23T10:00:00',
})
const P1_QUOTE = optionQuote({
  bid: '1.20',
  ask: '1.30',
  mark: '1.25',
  automaticMark: '1.25',
  markBasis: 'MID',
  source: 'CBOE',
  quoteTime: '2026-11-23T14:45:00Z',
  quoteTimeLocal: '2026-11-23T09:45:00',
  contractStatus: 'QUOTED',
  lastSuccessAt: '2026-11-23T15:00:00Z',
  lastSuccessAtLocal: '2026-11-23T10:00:00',
})

function position(
  partial: Partial<OptionPosition> & Pick<OptionPosition, 'symbol' | 'strike' | 'optionType'>,
): OptionPosition {
  const cp = partial.optionType === 'CALL' ? 'C' : 'P'
  return {
    expirationDate: '2026-12-18',
    contractLabel: `${partial.symbol} 2026-12-18 ${partial.strike} ${cp}`,
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '120.00',
    averageNetPerContract: '120.00',
    frozenCash: '5000.00',
    dte: 25,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: 1,
    lots: [],
    closeAction: 'BTC',
    allowedCloseContracts: [1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: 1,
    expiryAt: '2026-12-18T21:00:00Z',
    expiryAtLocal: '2026-12-18T16:00:00',
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    ...partial,
  }
}

const P1 = position({
  symbol: 'QRTV',
  strike: '50',
  optionType: 'PUT',
  underlyingQuote: QRTV_QUOTE,
  optionQuote: P1_QUOTE,
})
/** Acceptance 5 P4: never in the chain. */
const P4 = position({
  symbol: 'QRTV',
  strike: '47.5',
  optionType: 'PUT',
  underlyingQuote: QRTV_QUOTE,
  optionQuote: optionQuote({ lastSuccessAt: '2026-11-23T15:15:00Z' }),
})
/** Acceptance 4 P2: CBOE failed (never quoted), Finnhub price. */
const P2 = position({
  symbol: 'IXQZ',
  strike: '60',
  optionType: 'CALL',
  underlyingQuote: IXQZ_FINNHUB,
  optionQuote: optionQuote({ fetchFailed: true, staleLabel: '行情未更新（从未成功）' }),
})

const STATUS_OK: MarketDataStatus = {
  stale: false,
  warning: null,
  lastSuccessAt: '2026-11-23T15:00:00Z',
  lastSuccessAtLocal: '2026-11-23T10:00:00',
  lastAttemptAt: '2026-11-23T15:00:00Z',
  lastAttemptAtLocal: '2026-11-23T10:00:00',
  lastRefreshAt: null,
  lastRefreshAtLocal: null,
  refreshAvailableAt: null,
  refreshAllowed: true,
  trackedSymbolCount: 2,
  optionSource: 'CBOE',
  riskFreeRatePercent: '4.00',
}

interface Request {
  method: string
  url: string
  body: unknown
  account: string | null
}

interface FakeServer {
  positions: OptionPosition[]
  stocks?: StockPositionsResponse
  status: MarketDataStatus
  statusFails?: boolean
  onRefresh?: () => Response
  onPut?: (url: string, body: unknown) => Response
  onDelete?: (url: string) => Response
}

function startFakeServer(server: FakeServer) {
  const requests: Request[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    const headers = (init?.headers ?? {}) as Record<string, string>
    requests.push({ method, url, body, account: headers['X-Account-Id'] ?? null })
    if (method === 'GET' && url === '/api/option-positions') {
      const response: OptionPositionsResponse = {
        asOfDate: '2026-11-23',
        positionCount: server.positions.length,
        frozenTotal: '0.00',
        positions: server.positions,
      }
      return Promise.resolve(jsonResponse(response))
    }
    if (method === 'GET' && url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse(server.stocks ?? { positionCount: 0, positions: [] }))
    }
    if (method === 'GET' && url === '/api/market-data/status') {
      if (server.statusFails) return Promise.reject(new TypeError('Failed to fetch'))
      return Promise.resolve(jsonResponse(server.status))
    }
    if (method === 'POST' && url === '/api/market-data/refresh' && server.onRefresh) {
      return Promise.resolve(server.onRefresh())
    }
    if (method === 'PUT' && url.startsWith('/api/market-data/overrides') && server.onPut) {
      return Promise.resolve(server.onPut(url, body))
    }
    if (method === 'DELETE' && url.startsWith('/api/market-data/overrides') && server.onDelete) {
      return Promise.resolve(server.onDelete(url))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`No row for ${contract}`)
  return row
}

function stockRowOf(symbol: string): HTMLElement {
  const row = screen
    .getAllByTestId('stock-position-row')
    .find((element) => element.getAttribute('data-symbol') === symbol)
  if (!row) throw new Error(`No stock row for ${symbol}`)
  return row
}

const cellText = (row: HTMLElement, testId: string) => within(row).getByTestId(testId).textContent

const IXQZ_STOCK: StockPositionsResponse = {
  positionCount: 1,
  positions: [
    {
      symbol: 'IXQZ',
      shares: 100,
      coveredShares: 100,
      availableShares: 0,
      totalCost: '6000.00',
      averageCost: '60.0000',
      lotCount: 1,
      lots: [],
      quote: IXQZ_FINNHUB,
    },
  ],
}

async function renderPage() {
  renderWithProviders(<PositionsPage />)
  await screen.findAllByTestId('position-row')
}

describe('positions page quotes (FEAT-016)', () => {
  it('shows the backend quotes on option and stock rows (acceptance 1 / 4 / 5)', async () => {
    startFakeServer({ positions: [P1, P4, P2], stocks: IXQZ_STOCK, status: STATUS_OK })
    await renderPage()

    const p1 = rowOf('QRTV 2026-12-18 50 P')
    expect(cellText(p1, 'position-underlying-price')).toBe('52.40')
    expect(cellText(p1, 'position-bid-ask')).toBe('1.20 / 1.30')
    expect(cellText(p1, 'position-mark')).toBe('1.25')
    expect(cellText(p1, 'position-quote-time')).toBe('11-23 09:45')
    expect(within(p1).queryByTestId('position-mark-manual')).toBeNull()
    expect(within(p1).queryByTestId('position-mark-stale')).toBeNull()

    const p4 = rowOf('QRTV 2026-12-18 47.5 P')
    expect(cellText(p4, 'position-mark')).toBe('无报价')
    expect(cellText(p4, 'position-bid-ask')).toBe('—')
    expect(cellText(p4, 'position-quote-time')).toBe('—')

    const p2 = rowOf('IXQZ 2026-12-18 60 C')
    expect(cellText(p2, 'position-underlying-price')).toBe('61.05')
    expect(within(p2).getByTestId('position-underlying-price')).toHaveAttribute(
      'data-source',
      'FINNHUB',
    )
    expect(cellText(p2, 'position-mark')).toBe('无报价')
    expect(cellText(p2, 'position-mark-stale')).toBe('行情未更新（从未成功）')

    const stock = await waitFor(() => stockRowOf('IXQZ'))
    expect(cellText(stock, 'stock-position-price')).toBe('61.05')
    expect(cellText(stock, 'stock-position-quote-time')).toBe('11-23 09:59')
  })

  it('shows Finnhub as the source on hover', async () => {
    startFakeServer({ positions: [P2], status: STATUS_OK })
    await renderPage()
    await userEvent.hover(within(rowOf('IXQZ 2026-12-18 60 C')).getByTestId('position-underlying-price'))
    expect(await screen.findByText('来源 Finnhub')).toBeInTheDocument()
  })

  it('shows Yahoo quotes like CBOE ones: LAST mark as is, no source hover (FEAT-052)', async () => {
    const yahoo = position({
      symbol: 'QRTV',
      strike: '45',
      optionType: 'PUT',
      underlyingQuote: underlying({ ...QRTV_QUOTE, price: '50.30', automaticPrice: '50.30', source: 'YAHOO' }),
      optionQuote: optionQuote({
        ...P1_QUOTE,
        bid: '0.00',
        ask: '0.00',
        mark: '1.10',
        automaticMark: '1.10',
        markBasis: 'LAST',
        source: 'YAHOO',
        quoteTimeLocal: '2026-11-20T16:00:00',
      }),
    })
    startFakeServer({ positions: [yahoo], status: { ...STATUS_OK, optionSource: 'YAHOO' } })
    await renderPage()

    const row = rowOf('QRTV 2026-12-18 45 P')
    expect(cellText(row, 'position-mark')).toBe('1.10')
    expect(cellText(row, 'position-bid-ask')).toBe('0.00 / 0.00')
    expect(cellText(row, 'position-quote-time')).toBe('11-20 16:00')
    const price = within(row).getByTestId('position-underlying-price')
    expect(price).toHaveTextContent('50.30')
    expect(price).toHaveAttribute('data-source', 'YAHOO')
    await userEvent.hover(price)
    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(screen.queryByText(/^来源/)).toBeNull()
  })

  it('shows the source-missing and stale labels verbatim (acceptance 5 / 6)', async () => {
    const missing = position({
      symbol: 'QRTV',
      strike: '50',
      optionType: 'PUT',
      underlyingQuote: QRTV_QUOTE,
      optionQuote: {
        ...P1_QUOTE,
        contractStatus: 'MISSING_FROM_SOURCE',
        contractStatusLabel: '数据源已无该合约（最后报价：11-23 09:45）',
      },
    })
    const failed = position({
      symbol: 'IXQZ',
      strike: '60',
      optionType: 'CALL',
      underlyingQuote: underlying({
        ...IXQZ_FINNHUB,
        price: '61.10',
        automaticPrice: '61.10',
        source: 'CBOE',
        fetchFailed: true,
        staleLabel: '行情未更新（上次：11-23 10:00）',
      }),
      optionQuote: optionQuote({
        bid: '2.10',
        ask: '2.30',
        mark: '2.20',
        automaticMark: '2.20',
        contractStatus: 'QUOTED',
        fetchFailed: true,
        staleLabel: '行情未更新（上次：11-23 10:00）',
      }),
    })
    startFakeServer({ positions: [missing, failed], status: STATUS_OK })
    await renderPage()

    const p1 = rowOf('QRTV 2026-12-18 50 P')
    expect(cellText(p1, 'position-mark')).toBe('1.25')
    expect(cellText(p1, 'position-mark-status')).toBe('数据源已无该合约（最后报价：11-23 09:45）')
    const p2 = rowOf('IXQZ 2026-12-18 60 C')
    expect(cellText(p2, 'position-mark')).toBe('2.20')
    expect(cellText(p2, 'position-underlying-price')).toBe('61.10')
    expect(cellText(p2, 'position-mark-stale')).toBe('行情未更新（上次：11-23 10:00）')
    expect(cellText(p2, 'position-underlying-price-stale')).toBe('行情未更新（上次：11-23 10:00）')
    expect(screen.queryByTestId('market-data-stale-warning')).toBeNull()
  })

  it('shows 无报价 for rows without quote fields (older responses)', async () => {
    startFakeServer({
      positions: [position({ symbol: 'QRTV', strike: '50', optionType: 'PUT' })],
      status: STATUS_OK,
    })
    await renderPage()
    const row = rowOf('QRTV 2026-12-18 50 P')
    expect(cellText(row, 'position-underlying-price')).toBe('无报价')
    expect(cellText(row, 'position-mark')).toBe('无报价')
  })
})

describe('market data top bar (FEAT-016)', () => {
  it('shows the backend warning verbatim while stale, without an account header', async () => {
    const requests = startFakeServer({
      positions: [P1],
      status: { ...STATUS_OK, stale: true, warning: '行情未更新（上次：2026-11-23 10:00 美东）' },
    })
    await renderPage()
    expect(await screen.findByTestId('market-data-stale-warning')).toHaveTextContent(
      '行情未更新（上次：2026-11-23 10:00 美东）',
    )
    expect(screen.getByTestId('market-data-last-success')).toHaveTextContent(
      '行情更新于 2026-11-23 10:00（美东）',
    )
    const status = requests.find((r) => r.url === '/api/market-data/status')
    expect(status?.account).toBeNull()
  })

  it('shows the never-succeeded warning (acceptance 10)', async () => {
    startFakeServer({
      positions: [P4],
      status: {
        ...STATUS_OK,
        stale: true,
        warning: '行情未更新（从未成功获取）',
        lastSuccessAt: null,
        lastSuccessAtLocal: null,
      },
    })
    await renderPage()
    expect(await screen.findByTestId('market-data-stale-warning')).toHaveTextContent(
      '行情未更新（从未成功获取）',
    )
    expect(screen.getByTestId('market-data-last-success')).toHaveTextContent('行情尚未成功获取')
  })

  it('keeps the page and the refresh button when the status request fails', async () => {
    startFakeServer({ positions: [P1], status: STATUS_OK, statusFails: true })
    await renderPage()
    expect(screen.queryByTestId('market-data-stale-warning')).toBeNull()
    expect(screen.getByTestId('market-data-refresh')).toBeEnabled()
  })

  it('refreshes, reloads the positions and disables the button during the cooldown (acceptance 9)', async () => {
    const server: FakeServer = { positions: [P1], status: STATUS_OK }
    const cooling: MarketDataStatus = {
      ...STATUS_OK,
      lastSuccessAt: '2026-11-23T15:02:00Z',
      lastSuccessAtLocal: '2026-11-23T10:02:00',
      lastRefreshAt: '2026-11-23T15:02:00Z',
      lastRefreshAtLocal: '2026-11-23T10:02:00',
      refreshAvailableAt: '2026-11-23T15:03:00Z',
      refreshAllowed: false,
    }
    server.onRefresh = () => {
      server.positions = [
        { ...P1, optionQuote: { ...P1_QUOTE, bid: '1.30', ask: '1.40', mark: '1.35' } },
      ]
      server.status = cooling
      const response: MarketDataRefreshResponse = {
        performed: true,
        reason: null,
        results: [
          {
            symbol: 'QRTV',
            result: 'SUCCESS',
            optionSource: 'CBOE',
            yahooResult: null,
            cboeResult: 'SUCCESS',
            finnhubResult: null,
            underlyingPriceSource: 'CBOE',
            failureDetail: null,
          },
        ],
        status: cooling,
      }
      return jsonResponse(response)
    }
    const requests = startFakeServer(server)
    await renderPage()

    const button = screen.getByTestId('market-data-refresh')
    await waitFor(() => {
      expect(button).toBeEnabled()
    })
    await userEvent.click(button)

    await waitFor(() => {
      expect(cellText(rowOf('QRTV 2026-12-18 50 P'), 'position-mark')).toBe('1.35')
    })
    expect(screen.getByTestId('market-data-refresh')).toBeDisabled()
    expect(screen.getByTestId('market-data-refresh-result')).toHaveTextContent('已刷新 1 个标的')
    const refresh = requests.find((r) => r.url === '/api/market-data/refresh')
    expect(refresh).toMatchObject({ method: 'POST', account: null })
  })

  it('explains the cooldown on hover and when the backend did not fetch', async () => {
    const cooling: MarketDataStatus = {
      ...STATUS_OK,
      refreshAllowed: false,
      refreshAvailableAt: '2026-11-23T15:03:00Z',
    }
    const server: FakeServer = { positions: [P1], status: STATUS_OK }
    server.onRefresh = () => {
      // Another tab refreshed meanwhile: the backend is in its cooldown.
      server.status = cooling
      return jsonResponse({ performed: false, reason: 'COOLDOWN', results: [], status: cooling })
    }
    startFakeServer(server)
    await renderPage()
    await userEvent.hover(screen.getByTestId('market-data-refresh-wrapper'))
    expect(await screen.findByText('1 分钟内只能刷新一次')).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('market-data-refresh'))
    expect(await screen.findByTestId('market-data-refresh-result')).toHaveTextContent(
      '1 分钟内只能刷新一次，本次未重新获取',
    )
    expect(screen.getByTestId('market-data-refresh-result')).toHaveAttribute('data-kind', 'cooldown')
    await waitFor(() => {
      expect(screen.getByTestId('market-data-refresh')).toBeDisabled()
    })
  })

  it('starts disabled when the backend says refreshing is not allowed', async () => {
    startFakeServer({
      positions: [P1],
      status: { ...STATUS_OK, refreshAllowed: false, refreshAvailableAt: '2026-11-23T15:03:00Z' },
    })
    await renderPage()
    await waitFor(() => {
      expect(screen.getByTestId('market-data-refresh')).toBeDisabled()
    })
  })
})

describe('manual overrides (FEAT-016 rule 8)', () => {
  it('sets a contract mark and shows the manual tag (acceptance 8)', async () => {
    const server: FakeServer = { positions: [P1], status: STATUS_OK }
    const manual = {
      ...P1_QUOTE,
      mark: '1.40',
      manual: true,
      manualEnteredAt: '2026-11-23T15:05:00Z',
      manualEnteredAtLocal: '2026-11-23T10:05:00',
    }
    server.onPut = () => {
      server.positions = [{ ...P1, optionQuote: manual }]
      return jsonResponse(manual)
    }
    const requests = startFakeServer(server)
    await renderPage()

    const row = rowOf('QRTV 2026-12-18 50 P')
    await userEvent.click(within(row).getByTestId('position-mark-set'))
    const modal = await screen.findByTestId('manual-quote-modal')
    expect(within(modal).getByTestId('manual-quote-target')).toHaveTextContent('QRTV 2026-12-18 50 P')
    expect(within(modal).getByTestId('manual-quote-current')).toHaveTextContent('1.25')

    // Front-end format check first (the backend checks again).
    await userEvent.click(within(modal).getByTestId('manual-quote-submit'))
    expect(await within(modal).findByText('请输入价格')).toBeInTheDocument()
    await userEvent.type(within(modal).getByTestId('manual-quote-price'), '1.40')
    await userEvent.click(within(modal).getByTestId('manual-quote-submit'))

    await waitFor(() => {
      expect(cellText(rowOf('QRTV 2026-12-18 50 P'), 'position-mark')).toBe('1.40')
    })
    expect(cellText(rowOf('QRTV 2026-12-18 50 P'), 'position-mark-manual')).toBe('手动 11-23 10:05')
    const put = requests.find((r) => r.method === 'PUT')
    expect(put).toMatchObject({
      url: '/api/market-data/overrides/options',
      account: null,
      body: {
        symbol: 'QRTV',
        expirationDate: '2026-12-18',
        strike: '50',
        optionType: 'PUT',
        mark: '1.40',
      },
    })
    await waitFor(() => {
      expect(screen.queryByTestId('manual-quote-modal')).toBeNull()
    })
  })

  it('sets the underlying price', async () => {
    const server: FakeServer = { positions: [P1], status: STATUS_OK }
    server.onPut = () => jsonResponse({ ...QRTV_QUOTE, price: '52.00', manual: true })
    const requests = startFakeServer(server)
    await renderPage()

    await userEvent.click(
      within(rowOf('QRTV 2026-12-18 50 P')).getByTestId('position-underlying-price-set'),
    )
    const modal = await screen.findByTestId('manual-quote-modal')
    await userEvent.type(within(modal).getByTestId('manual-quote-price'), '52.00')
    await userEvent.click(within(modal).getByTestId('manual-quote-submit'))
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'PUT')).toBe(true)
    })
    expect(requests.find((r) => r.method === 'PUT')).toMatchObject({
      url: '/api/market-data/overrides/underlyings/QRTV',
      body: { price: '52.00' },
    })
  })

  it('shows backend errors: field errors under the input, 422 verbatim', async () => {
    let call = 0
    const server: FakeServer = {
      positions: [P1],
      status: STATUS_OK,
      onPut: () => {
        call += 1
        if (call === 1) {
          return jsonResponse(
            {
              code: 'VALIDATION_ERROR',
              message: 'Request validation failed',
              status: 400,
              path: '/api/market-data/overrides/options',
              timestamp: '2026-11-23T15:05:00.123Z',
              details: [{ field: 'mark', message: '价格过大（后端）' }],
            },
            400,
          )
        }
        return jsonResponse(
          {
            code: 'QUOTE_TARGET_NOT_HELD',
            message: '当前没有 QRTV 2026-12-18 50 P 的持仓，不能设置手动价格',
            status: 422,
            path: '/api/market-data/overrides/options',
            timestamp: '2026-11-23T15:05:00.123Z',
            details: [],
          },
          422,
        )
      },
    }
    startFakeServer(server)
    await renderPage()

    await userEvent.click(within(rowOf('QRTV 2026-12-18 50 P')).getByTestId('position-mark-set'))
    const modal = await screen.findByTestId('manual-quote-modal')
    await userEvent.type(within(modal).getByTestId('manual-quote-price'), '1.40')
    await userEvent.click(within(modal).getByTestId('manual-quote-submit'))
    expect(await within(modal).findByText('价格过大（后端）')).toBeInTheDocument()

    await userEvent.click(within(modal).getByTestId('manual-quote-submit'))
    const error = await within(modal).findByTestId('manual-quote-error')
    expect(error).toHaveTextContent('当前没有 QRTV 2026-12-18 50 P 的持仓，不能设置手动价格')
    expect(error).toHaveAttribute('data-error-code', 'QUOTE_TARGET_NOT_HELD')
    expect(within(modal).getByTestId('manual-quote-price')).toHaveValue('1.40')
  })

  it('clears manual values on option and stock rows', async () => {
    const manualUnderlying = underlying({
      ...QRTV_QUOTE,
      price: '52.00',
      manual: true,
      manualEnteredAt: '2026-11-23T15:05:00Z',
      manualEnteredAtLocal: '2026-11-23T10:05:00',
    })
    const server: FakeServer = {
      positions: [
        {
          ...P1,
          underlyingQuote: manualUnderlying,
          optionQuote: {
            ...P1_QUOTE,
            mark: '1.40',
            manual: true,
            manualEnteredAtLocal: '2026-11-23T10:05:00',
          },
        },
      ],
      stocks: {
        positionCount: 1,
        positions: [
          {
            symbol: 'QRTV',
            shares: 100,
            totalCost: '5000.00',
            averageCost: '50.0000',
            lotCount: 1,
            lots: [],
            quote: manualUnderlying,
          },
        ],
      },
      status: STATUS_OK,
      onDelete: () => new Response(null, { status: 204 }),
    }
    const requests = startFakeServer(server)
    await renderPage()

    const row = rowOf('QRTV 2026-12-18 50 P')
    expect(cellText(row, 'position-underlying-price')).toBe('52.00')
    expect(cellText(row, 'position-underlying-price-manual')).toBe('手动 11-23 10:05')
    const stock = await waitFor(() => stockRowOf('QRTV'))
    expect(cellText(stock, 'stock-position-price-manual')).toBe('手动 11-23 10:05')

    await userEvent.click(within(row).getByTestId('position-mark-clear'))
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'DELETE')).toBe(true)
    })
    expect(requests.find((r) => r.method === 'DELETE')?.url).toBe(
      '/api/market-data/overrides/options?symbol=QRTV&expirationDate=2026-12-18&strike=50&optionType=PUT',
    )
    await userEvent.click(within(stock).getByTestId('stock-position-price-clear'))
    await waitFor(() => {
      expect(
        requests.some(
          (r) => r.method === 'DELETE' && r.url === '/api/market-data/overrides/underlyings/QRTV',
        ),
      ).toBe(true)
    })
    // Positions reload after clearing.
    await waitFor(() => {
      expect(requests.filter((r) => r.url === '/api/option-positions').length).toBeGreaterThan(1)
    })
  })
})

