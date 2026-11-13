import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import type {
  ApiErrorResponse,
  AuditLogPage,
  CashLedgerPage,
  StockPositionsResponse,
  StockTrade,
  StockTradePage,
} from '../api/types'
import { App } from '../App'
import { AuditTable } from '../components/audit/AuditTable'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { S1, S2, S4, SCENE_S, SCENE_S_POSITIONS, stockTrade } from '../test/stockFixtures'
import userEvent from '../test/userEvent'

// ---------------------------------------------------------------------------
// Fake backend for the FEAT-011 endpoints
// ---------------------------------------------------------------------------

interface RecordedRequest {
  method: string
  path: string
  query: URLSearchParams
  body: unknown
}

interface FakeServer {
  trades: StockTrade[]
  positions?: StockPositionsResponse
  onPost?: (body: unknown) => Response
  onPut?: (id: number, body: unknown) => Response
  onDelete?: (id: number) => Response
}

function filterTrades(trades: StockTrade[], query: URLSearchParams): StockTrade[] {
  const symbol = query.get('symbol')?.trim().toUpperCase()
  const types = query.getAll('type')
  const start = query.get('startDate')
  const end = query.get('endDate')
  return trades.filter((t) => {
    const day = t.tradeDate ?? t.tradedAtLocal.slice(0, 10)
    return (
      (!symbol || t.symbol === symbol) &&
      (types.length === 0 || types.includes(t.type)) &&
      (!start || day >= start) &&
      (!end || day <= end)
    )
  })
}

function startFakeServer(server: FakeServer): RecordedRequest[] {
  const requests: RecordedRequest[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const parsed = new URL(url, 'http://localhost')
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, path: parsed.pathname, query: parsed.searchParams, body })
    const idMatch = /^\/api\/stock-trades\/(\d+)$/.exec(parsed.pathname)
    if (method === 'GET' && parsed.pathname === '/api/stock-trades') {
      const page = Number(parsed.searchParams.get('page'))
      const size = Number(parsed.searchParams.get('size'))
      const all = filterTrades(server.trades, parsed.searchParams)
      const result: StockTradePage = {
        items: all.slice((page - 1) * size, page * size),
        page,
        size,
        totalItems: all.length,
        totalPages: Math.ceil(all.length / size),
      }
      return Promise.resolve(jsonResponse(result))
    }
    if (method === 'GET' && parsed.pathname === '/api/stock-positions') {
      return Promise.resolve(jsonResponse(server.positions ?? { positionCount: 0, positions: [] }))
    }
    if (method === 'GET' && parsed.pathname === '/api/option-positions') {
      return Promise.resolve(
        jsonResponse({ asOfDate: '2026-11-13', positionCount: 0, frozenTotal: '0.00', positions: [] }),
      )
    }
    if (method === 'GET' && parsed.pathname === '/api/cash/summary') {
      return Promise.resolve(
        jsonResponse({ totalCash: '9099.35', frozenCash: '0.00', availableCash: '9099.35' }),
      )
    }
    if (method === 'POST' && parsed.pathname === '/api/stock-trades' && server.onPost) {
      return Promise.resolve(server.onPost(body))
    }
    if (method === 'PUT' && idMatch && server.onPut) {
      return Promise.resolve(server.onPut(Number(idMatch[1]), body))
    }
    if (method === 'DELETE' && idMatch && server.onDelete) {
      return Promise.resolve(server.onDelete(Number(idMatch[1])))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function error422(code: string, message: string): Response {
  const body: ApiErrorResponse = {
    code,
    message,
    status: 422,
    path: '/api/stock-trades',
    timestamp: '2026-11-13T15:00:00Z',
    details: [],
  }
  return jsonResponse(body, 422)
}

function validationError(details: ApiErrorResponse['details']): Response {
  const body: ApiErrorResponse = {
    code: 'VALIDATION_ERROR',
    message: 'Request validation failed',
    status: 400,
    path: '/api/stock-trades',
    timestamp: '2026-11-13T15:00:00Z',
    details,
  }
  return jsonResponse(body, 400)
}

function LocationProbe() {
  const location = useLocation()
  return <span data-testid="location">{location.pathname + location.search}</span>
}

function renderApp(route: string) {
  return renderWithProviders(
    <>
      <App />
      <LocationProbe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

function rowOf(id: number): HTMLElement {
  const row = screen
    .getAllByTestId('stock-row')
    .find((element) => element.getAttribute('data-stock-trade-id') === String(id))
  if (!row) throw new Error(`row ${String(id)} not found`)
  return row
}

function rowTexts(row: HTMLElement): (string | null)[] {
  const cell = (id: string) => within(row).getByTestId(id).textContent
  return [
    cell('stock-cell-time'),
    cell('stock-cell-type'),
    cell('stock-cell-symbol'),
    cell('stock-cell-shares'),
    cell('stock-cell-amount'),
    cell('stock-cell-price'),
    cell('stock-cell-pnl'),
  ]
}

function listRequests(requests: RecordedRequest[]): URLSearchParams[] {
  return requests
    .filter((r) => r.method === 'GET' && r.path === '/api/stock-trades')
    .map((r) => r.query)
}

// ---------------------------------------------------------------------------
// 股票 page: list
// ---------------------------------------------------------------------------

describe('股票 page list (FEAT-011 AC 2)', () => {
  it('shows scene S newest first with signed amounts, per-share prices and realized P&L', async () => {
    startFakeServer({ trades: SCENE_S })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    expect(byTestId('nav-stocks')).toHaveAttribute('href', '/stocks')
    expect(screen.getAllByTestId('stock-row').map(rowTexts)).toEqual([
      ['2026-11-06 10:00', '卖出', 'XYZ', '100', '+400.00', '每股 4.0000', '+66.67'],
      ['2026-11-05 10:00', '卖出', 'IXQZ', '150', '+1,200.00', '每股 8.0000', '+74.84'],
      ['2026-11-04 10:00', '买入', 'XYZ', '300', '−1,000.00', '每股 3.3333', '—'],
      ['2026-11-03 10:00', '买入', 'IXQZ', '200', '−1,500.65', '每股 7.5033', '—'],
      ['2026-10-01', '转入', 'IXQZ', '100', '总成本 750.00', '每股 7.5000', '—'],
    ])
    expect(within(rowOf(4)).getByTestId('stock-cell-amount')).toHaveStyle({ color: '#389e0d' })
    expect(within(rowOf(2)).getByTestId('stock-cell-amount')).toHaveStyle({ color: '#cf1322' })
    expect(within(rowOf(4)).getByTestId('stock-cell-pnl')).toHaveStyle({ color: '#389e0d' })
    expect(within(rowOf(1)).getByTestId('stock-cell-note')).toHaveTextContent('上线前持有')
    expect(within(rowOf(2)).getByTestId('stock-cell-note')).toHaveTextContent('—')
    // Only sales can be expanded; nobody has 历史 yet.
    expect(screen.getAllByTestId('stock-expand')).toHaveLength(2)
    expect(within(rowOf(1)).queryByTestId('stock-expand')).not.toBeInTheDocument()
    expect(screen.queryByTestId('stock-history')).not.toBeInTheDocument()
    expect(byTestId('stock-list-total')).toHaveTextContent('共 5 条')
    expect(byTestId('stock-list-note')).toHaveTextContent(
      '金额均已含手续费；每股价格 = 金额 ÷ 股数，仅供参考；股票已实现盈亏同时计入已平仓页、盈亏汇总和首页',
    )
  })

  it('expands a sale into its sold lots and the sold cost', async () => {
    startFakeServer({ trades: SCENE_S })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(4)).getByTestId('stock-expand'))
    const detail = await screen.findByTestId('stock-sold-detail')
    const lots = within(detail)
      .getAllByTestId('sold-lot-row')
      .map((row) =>
        ['sold-lot-source', 'sold-lot-time', 'sold-lot-shares', 'sold-lot-cost'].map(
          (id) => within(row).getByTestId(id).textContent,
        ),
      )
    expect(lots).toEqual([
      ['转入 #1', '2026-10-01', '100 股', '750.00'],
      ['买入 #2', '2026-11-03 10:00', '50 股', '375.16'],
    ])
    expect(within(detail).getByTestId('stock-sold-cost')).toHaveTextContent('1,125.16')
    expect(within(detail).getByTestId('stock-sold-pnl')).toHaveTextContent('+74.84')
  })

  it('shows 历史 only for edited records, linking to the filtered audit log', async () => {
    startFakeServer({ trades: [S4, { ...S2, hasAuditLog: true }, S1] })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    expect(screen.getAllByTestId('stock-history')).toHaveLength(1)
    expect(within(rowOf(2)).getByTestId('stock-history')).toHaveAttribute(
      'href',
      '/audit?objectType=STOCK_TRADE&objectId=2',
    )
  })

  it('shows thousands separators for large share counts and a loss in red', async () => {
    startFakeServer({
      trades: [
        stockTrade({
          id: 9,
          type: 'SELL',
          symbol: 'XYZ',
          shares: 1000,
          amount: '300.00',
          cashChange: '300.00',
          realizedPnl: '-33.33',
          soldCost: '333.33',
          soldLots: [],
        }),
      ],
    })
    renderApp('/stocks')
    const row = (await screen.findAllByTestId('stock-row'))[0]
    if (!row) throw new Error('row missing')
    expect(within(row).getByTestId('stock-cell-shares')).toHaveTextContent('1,000')
    expect(within(row).getByTestId('stock-cell-pnl')).toHaveTextContent('−33.33')
    expect(within(row).getByTestId('stock-cell-pnl')).toHaveStyle({ color: '#cf1322' })
  })

  it('shows 暂无股票交易 when there are no records', async () => {
    startFakeServer({ trades: [] })
    renderApp('/stocks')
    expect(await screen.findByTestId('stock-list-empty')).toHaveTextContent('暂无股票交易')
    expect(byTestId('stock-list-new')).toHaveAttribute('href', '/stocks/new')
  })
})

// ---------------------------------------------------------------------------
// 股票 page: filters in the URL (AC 3)
// ---------------------------------------------------------------------------

describe('股票 page filters (FEAT-011 AC 3)', () => {
  it('applies symbol and type filters through the URL and sends repeated type params', async () => {
    const requests = startFakeServer({ trades: SCENE_S })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.type(byTestId('stock-filter-symbol'), 'ixqz')
    await userEvent.click(byTestId('stock-filter-type-buy'))
    await userEvent.click(byTestId('stock-filter-type-transfer-in'))
    await userEvent.click(byTestId('stock-filter-submit'))
    await waitFor(() => {
      expect(byTestId('location')).toHaveTextContent(
        '/stocks?symbol=ixqz&type=BUY%2CTRANSFER_IN',
      )
    })
    await waitFor(() => {
      expect(screen.getAllByTestId('stock-row').map((r) => r.dataset.stockTradeId)).toEqual([
        '2',
        '1',
      ])
    })
    const last = listRequests(requests).at(-1)
    expect(last?.get('symbol')).toBe('ixqz')
    expect(last?.getAll('type')).toEqual(['BUY', 'TRANSFER_IN'])
  })

  it('filters by end date only (transfers by their date)', async () => {
    startFakeServer({ trades: SCENE_S })
    renderApp('/stocks?endDate=2026-10-31')
    await waitFor(() => {
      expect(screen.getAllByTestId('stock-row').map((r) => r.dataset.stockTradeId)).toEqual(['1'])
    })
    expect(byTestId('stock-filter-end-date')).toHaveValue('2026-10-31')
  })

  it('shows an invalid symbol under the field and does not query', async () => {
    const requests = startFakeServer({ trades: SCENE_S })
    renderApp('/stocks?symbol=%24IXQZ')
    expect(await screen.findByTestId('stock-list-invalid')).toBeInTheDocument()
    expect(screen.getByText('标的只能包含字母、数字和点')).toBeInTheDocument()
    expect(byTestId('stock-filter-symbol')).toHaveValue('$IXQZ')
    expect(listRequests(requests)).toHaveLength(0)
  })

  it('shows 没有符合条件的股票交易 with 清除筛选, which clears the URL', async () => {
    startFakeServer({ trades: SCENE_S })
    renderApp('/stocks?symbol=QRTV')
    expect(await screen.findByTestId('stock-list-no-match')).toHaveTextContent(
      '没有符合条件的股票交易',
    )
    await userEvent.click(byTestId('stock-list-no-match-clear'))
    await waitFor(() => {
      expect(byTestId('location')).toHaveTextContent(/^\/stocks$/)
    })
    expect(await screen.findAllByTestId('stock-row')).toHaveLength(5)
  })

  it('clicking a symbol filters by it', async () => {
    startFakeServer({ trades: SCENE_S })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(5)).getByTestId('stock-cell-symbol'))
    await waitFor(() => {
      expect(byTestId('location')).toHaveTextContent('/stocks?symbol=XYZ')
    })
  })
})

// ---------------------------------------------------------------------------
// Edit / delete (AC 8, 9)
// ---------------------------------------------------------------------------

describe('股票 page edit and delete (FEAT-011 AC 8, 9)', () => {
  it('edits a buy: symbol and type read-only, every editable field sent, list refreshed', async () => {
    let trades = SCENE_S
    const requests = startFakeServer({
      trades,
      onPut: (id) => {
        const updated = { ...S2, id, amount: '1500.00', cashChange: '-1500.00', hasAuditLog: true }
        trades = trades.map((t) => (t.id === id ? updated : t))
        return jsonResponse(updated)
      },
    })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(2)).getByTestId('stock-edit'))
    const modal = await screen.findByTestId('stock-edit-modal')
    expect(within(modal).getByTestId('stock-edit-type')).toHaveTextContent('买入')
    expect(within(modal).getByTestId('stock-edit-symbol')).toHaveTextContent('IXQZ')
    expect(within(modal).getByTestId('stock-edit-identity-hint')).toHaveTextContent(
      '标的或类型录错请删除后重新录入',
    )
    expect(within(modal).getByTestId('stock-edit-amount-label')).toHaveTextContent(
      '实付总额（含手续费）',
    )
    expect(within(modal).getByTestId('stock-edit-shares')).toHaveValue('200')
    expect(within(modal).getByTestId('stock-edit-time')).toHaveValue('2026-11-03T10:00')
    expect(within(modal).queryByTestId('stock-edit-date')).not.toBeInTheDocument()
    fireEvent.change(within(modal).getByTestId('stock-edit-amount'), {
      target: { value: '1,500.00' },
    })
    await userEvent.click(within(modal).getByTestId('stock-edit-submit'))
    await waitFor(() => {
      expect(screen.queryByTestId('stock-edit-modal')).not.toBeInTheDocument()
    })
    const put = requests.find((r) => r.method === 'PUT')
    expect(put?.path).toBe('/api/stock-trades/2')
    expect(put?.body).toEqual({
      shares: 200,
      amount: '1500.00',
      tradedAtLocal: '2026-11-03T10:00',
      note: null,
    })
    // The list refreshes after the edit (refreshAfterChange).
    await waitFor(() => {
      expect(listRequests(requests).length).toBeGreaterThanOrEqual(2)
    })
  })

  it('edits a transfer by date and keeps the dialog open with the backend message on rejection', async () => {
    const requests = startFakeServer({
      trades: SCENE_S,
      onPut: () =>
        error422(
          'SHARES_INSUFFICIENT',
          '修改后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 140 股',
        ),
    })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(1)).getByTestId('stock-edit'))
    const modal = await screen.findByTestId('stock-edit-modal')
    expect(within(modal).getByTestId('stock-edit-amount-label')).toHaveTextContent('总成本')
    expect(within(modal).getByTestId('stock-edit-date')).toHaveValue('2026-10-01')
    expect(within(modal).queryByTestId('stock-edit-time')).not.toBeInTheDocument()
    fireEvent.change(within(modal).getByTestId('stock-edit-shares'), { target: { value: '40' } })
    fireEvent.change(within(modal).getByTestId('stock-edit-date'), {
      target: { value: '2026-11-04' },
    })
    await userEvent.click(within(modal).getByTestId('stock-edit-submit'))
    const error = await within(modal).findByTestId('stock-edit-error')
    expect(error).toHaveTextContent('修改后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 140 股')
    expect(error).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    expect(within(modal).getByTestId('stock-edit-shares')).toHaveValue('40')
    expect(requests.find((r) => r.method === 'PUT')?.body).toEqual({
      shares: 40,
      amount: '750.00',
      tradeDate: '2026-11-04',
      note: '上线前持有',
    })
  })

  it('shows field errors from the backend under the field', async () => {
    startFakeServer({
      trades: SCENE_S,
      onPut: () => validationError([{ field: 'tradeDate', message: '日期不能晚于今天' }]),
    })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(1)).getByTestId('stock-edit'))
    const modal = await screen.findByTestId('stock-edit-modal')
    await userEvent.click(within(modal).getByTestId('stock-edit-submit'))
    expect(await within(modal).findByText('日期不能晚于今天')).toBeInTheDocument()
    expect(within(modal).queryByTestId('stock-edit-error')).not.toBeInTheDocument()
  })

  it('confirms a delete with the summary; a rejection keeps the dialog open', async () => {
    startFakeServer({
      trades: SCENE_S,
      onDelete: () =>
        error422(
          'SHARES_INSUFFICIENT',
          '删除后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股',
        ),
    })
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(2)).getByTestId('stock-delete'))
    const modal = await screen.findByTestId('stock-delete-modal')
    expect(within(modal).getByTestId('stock-delete-summary')).toHaveTextContent(
      '买入 200 股 IXQZ，2026-11-03 10:00，−1,500.65',
    )
    expect(within(modal).getByTestId('stock-delete-warning')).toHaveTextContent(
      '删除后不可恢复，会写入审计记录',
    )
    await userEvent.click(within(modal).getByTestId('stock-delete-confirm'))
    expect(await within(modal).findByTestId('stock-delete-error')).toHaveTextContent(
      '删除后 2026-11-05 10:00 的卖出 IXQZ 150 股将超过当时持股 100 股',
    )
    expect(screen.getByTestId('stock-delete-modal')).toBeInTheDocument()
  })

  it('deletes a transfer and refreshes the list', async () => {
    let trades = SCENE_S
    const server: FakeServer = {
      trades,
      onDelete: (id) => {
        trades = trades.filter((t) => t.id !== id)
        server.trades = trades
        return new Response(null, { status: 204 })
      },
    }
    const requests = startFakeServer(server)
    renderApp('/stocks')
    await screen.findAllByTestId('stock-row')
    await userEvent.click(within(rowOf(1)).getByTestId('stock-delete'))
    const modal = await screen.findByTestId('stock-delete-modal')
    expect(within(modal).getByTestId('stock-delete-summary')).toHaveTextContent(
      '转入 100 股 IXQZ，2026-10-01，总成本 750.00',
    )
    await userEvent.click(within(modal).getByTestId('stock-delete-confirm'))
    await waitFor(() => {
      expect(screen.queryByTestId('stock-delete-modal')).not.toBeInTheDocument()
    })
    expect(requests.find((r) => r.method === 'DELETE')?.path).toBe('/api/stock-trades/1')
    await waitFor(() => {
      expect(screen.getAllByTestId('stock-row')).toHaveLength(4)
    })
  })
})

// ---------------------------------------------------------------------------
// 录入股票交易 (AC 7, 11, 14)
// ---------------------------------------------------------------------------

describe('录入股票交易 (FEAT-011)', () => {
  it('records a transfer with a date and total cost; hints shown; no trade time sent', async () => {
    const saved = { ...S1 }
    const requests = startFakeServer({ trades: [], onPost: () => jsonResponse(saved, 201) })
    renderApp('/stocks/new')
    await screen.findByTestId('stock-entry-form')
    expect(byTestId('stock-entry-amount-label')).toHaveTextContent('金额')
    expect(screen.queryByTestId('stock-entry-transfer-hint')).not.toBeInTheDocument()
    await userEvent.click(byTestId('stock-entry-type-transfer-in'))
    expect(byTestId('stock-entry-transfer-hint')).toHaveTextContent(
      '用于系统上线前已持有的股票（例如之前被指派得到的股票），不会从现金中扣款',
    )
    expect(byTestId('stock-entry-date-hint')).toHaveTextContent(
      '期初持仓：记在该日开始（美东 00:00），不影响现金',
    )
    expect(byTestId('stock-entry-amount-label')).toHaveTextContent('总成本')
    expect(screen.queryByTestId('stock-entry-time')).not.toBeInTheDocument()
    expect(byTestId('cash-summary')).toBeInTheDocument()
    fireEvent.change(byTestId('stock-entry-symbol'), { target: { value: ' ixqz ' } })
    fireEvent.change(byTestId('stock-entry-shares'), { target: { value: '100' } })
    fireEvent.change(byTestId('stock-entry-amount'), { target: { value: '750.00' } })
    fireEvent.change(byTestId('stock-entry-date'), { target: { value: '2026-10-01' } })
    fireEvent.change(byTestId('stock-entry-note'), { target: { value: '上线前持有' } })
    await userEvent.click(byTestId('stock-entry-submit'))
    const confirmation = await screen.findByTestId('stock-entry-confirmation')
    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({
      type: 'TRANSFER_IN',
      symbol: ' ixqz ',
      shares: 100,
      amount: '750.00',
      tradeDate: '2026-10-01',
      note: '上线前持有',
    })
    expect(within(confirmation).getByTestId('stock-entry-confirmation-type')).toHaveTextContent(
      '转入',
    )
    expect(within(confirmation).getByTestId('stock-entry-confirmation-price')).toHaveTextContent(
      '7.5000',
    )
    expect(
      within(confirmation).getByTestId('stock-entry-confirmation-cash-change'),
    ).toHaveTextContent('0.00')
    // Form cleared after success.
    expect(byTestId('stock-entry-symbol')).toHaveValue('')
  })

  it('opens prefilled for a sale (positions page 卖出) and shows sold lots and P&L', async () => {
    const saved = stockTrade({
      id: 6,
      type: 'SELL',
      shares: 50,
      amount: '400.00',
      cashChange: '400.00',
      pricePerShare: '8.0000',
      tradedAt: '2026-11-12T15:00:00Z',
      tradedAtLocal: '2026-11-12T10:00:00',
      soldCost: '375.16',
      realizedPnl: '24.84',
      soldLots: [
        {
          tradeId: 2,
          type: 'BUY',
          tradedAt: '2026-11-03T15:00:00Z',
          tradedAtLocal: '2026-11-03T10:00:00',
          tradeDate: null,
          shares: 50,
          cost: '375.16',
        },
      ],
    })
    const requests = startFakeServer({ trades: [], onPost: () => jsonResponse(saved, 201) })
    renderApp('/stocks/new?type=SELL&symbol=IXQZ')
    await screen.findByTestId('stock-entry-form')
    expect(byTestId('stock-entry-symbol')).toHaveValue('IXQZ')
    await waitFor(() => {
      expect(byTestId('stock-entry-amount-label')).toHaveTextContent('实收净额（已扣手续费）')
    })
    expect(byTestId('stock-entry-time')).toBeInTheDocument()
    fireEvent.change(byTestId('stock-entry-shares'), { target: { value: '50' } })
    fireEvent.change(byTestId('stock-entry-amount'), { target: { value: '400' } })
    fireEvent.change(byTestId('stock-entry-time'), { target: { value: '2026-11-12T10:00' } })
    await userEvent.click(byTestId('stock-entry-submit'))
    const confirmation = await screen.findByTestId('stock-entry-confirmation')
    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({
      type: 'SELL',
      symbol: 'IXQZ',
      shares: 50,
      amount: '400',
      tradedAtLocal: '2026-11-12T10:00',
      note: null,
    })
    expect(
      within(confirmation).getByTestId('stock-entry-confirmation-realized-pnl'),
    ).toHaveTextContent('+24.84')
    expect(within(confirmation).getByTestId('stock-entry-confirmation-sold-cost')).toHaveTextContent(
      '375.16',
    )
    expect(within(confirmation).getAllByTestId('sold-lot-row')).toHaveLength(1)
  })

  it('shows a 422 verbatim above the button and keeps the input', async () => {
    startFakeServer({
      trades: [],
      onPost: () =>
        error422('SHARES_INSUFFICIENT', '持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股'),
    })
    renderApp('/stocks/new?type=SELL&symbol=IXQZ')
    await screen.findByTestId('stock-entry-form')
    fireEvent.change(byTestId('stock-entry-shares'), { target: { value: '151' } })
    fireEvent.change(byTestId('stock-entry-amount'), { target: { value: '1200.00' } })
    fireEvent.change(byTestId('stock-entry-time'), { target: { value: '2026-11-12T09:00' } })
    await userEvent.click(byTestId('stock-entry-submit'))
    const error = await screen.findByTestId('stock-entry-error')
    expect(error).toHaveTextContent('持股不足：2026-11-13 09:00 卖出 IXQZ 151 股，当时持有 150 股')
    expect(error).toHaveAttribute('data-error-code', 'SHARES_INSUFFICIENT')
    expect(byTestId('stock-entry-shares')).toHaveValue('151')
    expect(screen.queryByTestId('stock-entry-confirmation')).not.toBeInTheDocument()
  })

  it('checks formats before sending (AC 11)', async () => {
    const requests = startFakeServer({ trades: [] })
    renderApp('/stocks/new')
    await screen.findByTestId('stock-entry-form')
    fireEvent.change(byTestId('stock-entry-symbol'), { target: { value: '$IXQZ' } })
    fireEvent.change(byTestId('stock-entry-shares'), { target: { value: '1.5' } })
    fireEvent.change(byTestId('stock-entry-amount'), { target: { value: '1.234' } })
    await userEvent.click(byTestId('stock-entry-submit'))
    expect(await screen.findByText('请选择类型')).toBeInTheDocument()
    expect(screen.getByText('标的只能包含字母、数字和点')).toBeInTheDocument()
    expect(screen.getByText('股数必须是正整数')).toBeInTheDocument()
    expect(screen.getByText('金额最多 2 位小数')).toBeInTheDocument()
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0)
  })

  it('puts backend field errors under their fields', async () => {
    startFakeServer({
      trades: [],
      onPost: () => validationError([{ field: 'tradedAtLocal', message: '成交时间不能晚于当前时间' }]),
    })
    renderApp('/stocks/new?type=SELL&symbol=IXQZ')
    await screen.findByTestId('stock-entry-form')
    fireEvent.change(byTestId('stock-entry-shares'), { target: { value: '1' } })
    fireEvent.change(byTestId('stock-entry-amount'), { target: { value: '8' } })
    await userEvent.click(byTestId('stock-entry-submit'))
    expect(await screen.findByText('成交时间不能晚于当前时间')).toBeInTheDocument()
    expect(screen.queryByTestId('stock-entry-error')).not.toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// 持仓页 股票持仓 (AC 1, 14)
// ---------------------------------------------------------------------------

describe('持仓页 股票持仓 (FEAT-011 AC 1, 14)', () => {
  it('lists stock positions below the options with count, totals and averages', async () => {
    startFakeServer({ trades: [], positions: SCENE_S_POSITIONS })
    renderApp('/positions')
    const section = await screen.findByTestId('stock-positions')
    const rows = await within(section).findAllByTestId('stock-position-row')
    expect(within(section).getByTestId('stock-positions-count')).toHaveTextContent('共 2 个标的')
    expect(
      rows.map((row) =>
        [
          'stock-position-symbol',
          'stock-position-shares',
          'stock-position-total-cost',
          'stock-position-average-cost',
        ].map((id) => within(row).getByTestId(id).textContent),
      ),
    ).toEqual([
      ['IXQZ', '150', '1,125.49', '7.5033'],
      ['XYZ', '200', '666.67', '3.3334'],
    ])
    expect(within(section).getByText(/暂不检查 covered call 的股票覆盖/)).toBeInTheDocument()
    // Option part unchanged (empty state).
    expect(screen.getByTestId('positions-empty')).toBeInTheDocument()
  })

  it('expands lots in FIFO order', async () => {
    startFakeServer({ trades: [], positions: SCENE_S_POSITIONS })
    renderApp('/positions')
    const rows = await screen.findAllByTestId('stock-position-row')
    const xyz = rows[1]
    if (!xyz) throw new Error('row missing')
    await userEvent.click(within(xyz).getByTestId('stock-position-expand'))
    const lotRow = await screen.findByTestId('stock-lot-row')
    expect(
      [
        'stock-lot-source',
        'stock-lot-time',
        'stock-lot-original-shares',
        'stock-lot-remaining-shares',
        'stock-lot-remaining-cost',
        'stock-lot-note',
      ].map((id) => within(lotRow).getByTestId(id).textContent),
    ).toEqual(['买入 #3', '2026-11-04 10:00', '300', '200', '666.67', '分批买入'])
  })

  it('links the symbol to the filtered stock list and 卖出 to the prefilled form', async () => {
    startFakeServer({ trades: [], positions: SCENE_S_POSITIONS })
    renderApp('/positions')
    const rows = await screen.findAllByTestId('stock-position-row')
    const ixqz = rows[0]
    if (!ixqz) throw new Error('row missing')
    expect(within(ixqz).getByTestId('stock-position-symbol')).toHaveAttribute(
      'href',
      '/stocks?symbol=IXQZ',
    )
    await userEvent.click(within(ixqz).getByTestId('stock-position-sell'))
    await waitFor(() => {
      expect(byTestId('location')).toHaveTextContent('/stocks/new?type=SELL&symbol=IXQZ')
    })
    expect(await screen.findByTestId('stock-entry-symbol')).toHaveValue('IXQZ')
  })

  it('shows 暂无股票持仓 without positions', async () => {
    startFakeServer({ trades: [] })
    renderApp('/positions')
    expect(await screen.findByTestId('stock-positions-empty')).toHaveTextContent('暂无股票持仓')
    expect(byTestId('stock-positions-count')).toHaveTextContent('共 0 个标的')
  })
})

// ---------------------------------------------------------------------------
// 资金流水 and 审计记录
// ---------------------------------------------------------------------------

describe('资金流水 stock rows (FEAT-011)', () => {
  it('labels stock rows and links them to the 股票 page without edit buttons', async () => {
    const ledger: CashLedgerPage = {
      items: [
        {
          id: 2,
          occurredAt: '2026-11-03T15:00:00Z',
          sourceType: 'STOCK_BUY',
          sourceId: 2,
          amount: '-1500.65',
          balanceAfter: '8499.35',
          frozenChange: '0.00',
          description: '买入 200 股 IXQZ',
          remark: null,
          hasAuditLog: false,
        },
        {
          id: 1,
          occurredAt: '2026-11-02T15:00:00Z',
          sourceType: 'STOCK_SELL',
          sourceId: 4,
          amount: '1200.00',
          balanceAfter: '10000.00',
          frozenChange: '0.00',
          description: '卖出 150 股 IXQZ',
          remark: null,
          hasAuditLog: false,
        },
      ],
      page: 1,
      size: 20,
      totalItems: 2,
      totalPages: 1,
    }
    mockFetch().mockImplementation(() => Promise.resolve(jsonResponse(ledger)))
    renderWithProviders(<CashLedgerTable page={1} onPageChange={() => undefined} />)
    const rows = await screen.findAllByTestId('cash-ledger-row')
    const [buy, sell] = rows
    if (!buy || !sell) throw new Error('rows missing')
    expect(within(buy).getByTestId('cash-ledger-type')).toHaveTextContent('股票买入')
    expect(within(buy).getByTestId('cash-ledger-amount')).toHaveTextContent('−1,500.65')
    expect(within(buy).getByTestId('cash-ledger-note')).toHaveTextContent('买入 200 股 IXQZ')
    expect(within(sell).getByTestId('cash-ledger-type')).toHaveTextContent('股票卖出')
    for (const row of rows) {
      expect(within(row).getByTestId('cash-ledger-stocks-link')).toHaveAttribute('href', '/stocks')
      expect(within(row).getByTestId('cash-ledger-stocks-link')).toHaveTextContent(
        '在股票交易中修改',
      )
      expect(within(row).queryByTestId('cash-ledger-edit')).not.toBeInTheDocument()
      expect(within(row).queryByTestId('cash-ledger-delete')).not.toBeInTheDocument()
    }
  })
})

describe('审计记录 stock trades (FEAT-011)', () => {
  const page: AuditLogPage = {
    items: [
      {
        id: 7,
        operatedAt: '2026-11-13T15:00:00Z',
        operation: 'DELETE',
        objectType: 'STOCK_TRADE',
        objectId: 1,
        summary: '转入 IXQZ 100 股',
        before: {
          id: 1,
          type: 'TRANSFER_IN',
          symbol: 'IXQZ',
          shares: 100,
          amount: '750.00',
          tradedAt: '2026-10-01T04:00:00Z',
          tradedAtLocal: '2026-10-01T00:00:00',
          tradeDate: '2026-10-01',
          note: null,
          cashChange: '0.00',
        },
        after: null,
        changes: [],
        sideEffects: [],
      },
      {
        id: 6,
        operatedAt: '2026-11-13T14:00:00Z',
        operation: 'UPDATE',
        objectType: 'STOCK_TRADE',
        objectId: 2,
        summary: '买入 IXQZ 200 股',
        before: {
          id: 2,
          type: 'BUY',
          symbol: 'IXQZ',
          shares: 200,
          amount: '1500.65',
          tradedAt: '2026-11-03T15:00:00Z',
          tradedAtLocal: '2026-11-03T10:00:00',
          tradeDate: null,
          note: null,
          cashChange: '-1500.65',
        },
        after: {
          id: 2,
          type: 'BUY',
          symbol: 'IXQZ',
          shares: 200,
          amount: '1500.00',
          tradedAt: '2026-11-03T15:00:00Z',
          tradedAtLocal: '2026-11-03T10:00:00',
          tradeDate: null,
          note: null,
          cashChange: '-1500.00',
        },
        changes: [
          {
            field: 'amount',
            label: '金额',
            before: '1,500.65',
            after: '1,500.00',
            text: '金额：1,500.65 → 1,500.00',
          },
        ],
        sideEffects: [],
      },
    ],
    page: 1,
    size: 20,
    totalItems: 2,
    totalPages: 1,
  }

  it('shows 股票交易 #id, the summary, changes and full before / after', async () => {
    renderWithProviders(<AuditTable records={page.items} loading={false} empty={null} />)
    const rows = screen.getAllByTestId('audit-row')
    const [deleted, updated] = rows
    if (!deleted || !updated) throw new Error('rows missing')
    expect(within(deleted).getByTestId('audit-cell-object')).toHaveTextContent('股票交易 #1')
    expect(within(deleted).getByTestId('audit-cell-changes')).toHaveTextContent('已删除')
    expect(within(updated).getByTestId('audit-cell-object')).toHaveTextContent('股票交易 #2')
    expect(within(updated).getByTestId('audit-cell-summary')).toHaveTextContent('买入 IXQZ 200 股')
    expect(within(updated).getByTestId('audit-cell-changes')).toHaveTextContent(
      '金额：1,500.65 → 1,500.00',
    )

    await userEvent.click(within(deleted).getByTestId('audit-expand'))
    const before = await screen.findByTestId('audit-before')
    expect(within(before).getByText('总成本')).toBeInTheDocument()
    expect(within(before).getByText('日期')).toBeInTheDocument()
    expect(within(before).getByTestId('audit-before-type')).toHaveTextContent('转入')
    expect(within(before).getByTestId('audit-before-amount')).toHaveTextContent('750.00')
    expect(within(before).getByTestId('audit-before-tradeDate')).toHaveTextContent('2026-10-01')
    expect(within(before).getByTestId('audit-before-cashChange')).toHaveTextContent('0.00')

    await userEvent.click(within(updated).getByTestId('audit-expand'))
    await waitFor(() => {
      expect(screen.getAllByTestId('audit-after')).toHaveLength(2)
    })
    const after = screen.getAllByTestId('audit-after')[1]
    if (!after) throw new Error('after missing')
    expect(within(after).getByTestId('audit-after-tradedAtLocal')).toHaveTextContent(
      '2026-11-03 10:00',
    )
    expect(within(after).getByTestId('audit-after-cashChange')).toHaveTextContent('−1,500.00')
  })

  it('filters the audit page by 股票交易', async () => {
    const urls: string[] = []
    mockFetch().mockImplementation((input) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      urls.push(url)
      return Promise.resolve(jsonResponse(page))
    })
    renderApp('/audit')
    await screen.findAllByTestId('audit-row')
    await userEvent.click(byTestId('audit-filter-type-stock'))
    await userEvent.click(byTestId('audit-filter-submit'))
    await waitFor(() => {
      expect(byTestId('location')).toHaveTextContent('/audit?objectType=STOCK_TRADE')
    })
    await waitFor(() => {
      expect(urls.some((url) => url.includes('objectType=STOCK_TRADE'))).toBe(true)
    })
  })
})
