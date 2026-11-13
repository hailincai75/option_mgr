import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  ApiErrorResponse,
  CashSummary,
  OptionPosition,
  OptionPositionsResponse,
  OptionTrade,
  OptionTradePage,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'
import { TradeEntryPage } from './TradeEntryPage'

// ---------------------------------------------------------------------------
// FEAT-004 scenario A: L1 = STO 2 @698.70 (11-03 10:30), L2 = STO 1 @360.00 (11-04 11:00)
// ---------------------------------------------------------------------------

const SUMMARY_A: CashSummary = {
  totalCash: '81058.70',
  frozenCash: '60000.00',
  availableCash: '21058.70',
}
const SUMMARY_AFTER_AC1: CashSummary = {
  totalCash: '80817.40',
  frozenCash: '20000.00',
  availableCash: '60817.40',
}

const L1 = {
  tradeId: 1,
  action: 'STO',
  tradedAt: '2026-11-03T15:30:00Z',
  tradedAtLocal: '2026-11-03T10:30:00',
}
const L2 = {
  tradeId: 2,
  action: 'STO',
  tradedAt: '2026-11-04T16:00:00Z',
  tradedAtLocal: '2026-11-04T11:00:00',
}

const AAPL_POSITION: OptionPosition = {
  symbol: 'AAPL',
  expirationDate: '2026-12-18',
  strike: '200',
  optionType: 'PUT',
  contractLabel: 'AAPL 2026-12-18 200 P',
  direction: 'SHORT',
  contracts: 3,
  multiplier: 100,
  openingNetTotal: '1058.70',
  averageNetPerContract: '352.90',
  frozenCash: '60000.00',
  dte: 37,
  expirationStatus: 'ACTIVE',
  daysPastExpiration: 0,
  lotCount: 2,
  closeAction: 'BTC',
  allowedCloseContracts: [2, 3],
  closable: true,
  expiryPending: false,
  expirable: false,
  expiryContracts: 3,
  expiryAt: '2026-12-18T21:00:00Z',
  expiryAtLocal: '2026-12-18T16:00:00',
  assignable: false,
  assignChoices: [],
  assignmentDateMax: null,
  assignmentDateDefault: null,
  lots: [
    {
      ...L1,
      originalContracts: 2,
      remainingContracts: 2,
      originalNetAmount: '698.70',
      remainingNetAmount: '698.70',
      originalFrozen: '40000.00',
      remainingFrozen: '40000.00',
      note: null,
    },
    {
      ...L2,
      originalContracts: 1,
      remainingContracts: 1,
      originalNetAmount: '360.00',
      remainingNetAmount: '360.00',
      originalFrozen: '20000.00',
      remainingFrozen: '20000.00',
      note: null,
    },
  ],
}

const POSITIONS_A: OptionPositionsResponse = {
  asOfDate: '2026-11-11',
  positionCount: 1,
  frozenTotal: '60000.00',
  positions: [AAPL_POSITION],
}

const POSITIONS_AFTER_AC1: OptionPositionsResponse = {
  asOfDate: '2026-11-11',
  positionCount: 1,
  frozenTotal: '20000.00',
  positions: [
    {
      ...AAPL_POSITION,
      contracts: 1,
      openingNetTotal: '360.00',
      averageNetPerContract: '360.00',
      frozenCash: '20000.00',
      lotCount: 1,
      allowedCloseContracts: [1],
      lots: AAPL_POSITION.lots.slice(1),
    },
  ],
}

const CLOSE_AC1: OptionTrade = {
  id: 3,
  symbol: 'AAPL',
  expirationDate: '2026-12-18',
  strike: '200',
  optionType: 'PUT',
  action: 'BTC',
  contracts: 2,
  multiplier: 100,
  netAmount: '241.30',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  cashChange: '-241.30',
  frozenChange: '-40000.00',
  contractLabel: 'AAPL 2026-12-18 200 P',
  note: null,
  ledgerEntryId: 4,
  createdAt: '2026-11-11T15:00:00Z',
  coveredLots: [{ ...L1, contracts: 2, netAmount: '698.70', frozen: '40000.00' }],
  realizedPnl: '457.40',
  hasAuditLog: true,
  expiredDirection: null,
  assignment: null,
}

// ---------------------------------------------------------------------------

interface FakeServer {
  summary: CashSummary
  positions: OptionPositionsResponse
  trades: OptionTrade[]
  onClose?: (body: unknown) => Response
  onOpen?: (body: unknown) => Response
}

interface Recorded {
  method: string
  url: string
  body: unknown
}

function startFakeServer(server: FakeServer) {
  const requests: Recorded[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, body })
    if (method === 'GET' && url === '/api/cash/summary') {
      return Promise.resolve(jsonResponse(server.summary))
    }
    if (method === 'GET' && url === '/api/option-positions') {
      return Promise.resolve(jsonResponse(server.positions))
    }
    if (method === 'GET' && url.startsWith('/api/option-trades?')) {
      const page: OptionTradePage = {
        items: server.trades,
        page: 1,
        size: 20,
        totalItems: server.trades.length,
        totalPages: server.trades.length > 0 ? 1 : 0,
      }
      return Promise.resolve(jsonResponse(page))
    }
    if (method === 'POST' && url === '/api/option-trades/closes' && server.onClose) {
      return Promise.resolve(server.onClose(body))
    }
    if (method === 'POST' && url === '/api/option-trades' && server.onOpen) {
      return Promise.resolve(server.onOpen(body))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function reject422(code: string, message: string, path = '/api/option-trades/closes'): Response {
  const body: ApiErrorResponse = {
    code,
    message,
    status: 422,
    path,
    timestamp: '2026-11-11T15:00:00Z',
    details: [],
  }
  return jsonResponse(body, 422)
}

function scenarioA(overrides: Partial<FakeServer> = {}): FakeServer {
  return { summary: SUMMARY_A, positions: POSITIONS_A, trades: [], ...overrides }
}

/** Server that accepts the AC 1 close and switches to the post-close state. */
function acceptingAc1(server: FakeServer): FakeServer {
  server.onClose = () => {
    server.summary = SUMMARY_AFTER_AC1
    server.positions = POSITIONS_AFTER_AC1
    server.trades = [CLOSE_AC1]
    return jsonResponse(CLOSE_AC1, 201)
  }
  return server
}

const byTestId = (id: string) => screen.getByTestId(id)

function setText(testId: string, value: string) {
  fireEvent.change(byTestId(testId), { target: { value } })
}

function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

function posts(requests: Recorded[]) {
  return requests.filter((r) => r.method === 'POST')
}

afterEach(() => {
  vi.useRealTimers()
})

// ---------------------------------------------------------------------------
// Entry 1: 平仓 button on the positions page
// ---------------------------------------------------------------------------

describe('平仓 from the positions page', () => {
  it('prefills contract, BTC, allowed counts (default the smallest) and the paid label (AC 9)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-11T15:00:00Z'))
    startFakeServer(scenarioA())
    renderWithProviders(<PositionsPage />)

    await userEvent.click(await screen.findByTestId('position-close'))
    const modal = await screen.findByTestId('close-trade-form')
    expect(byTestId('close-trade-contract')).toHaveTextContent('AAPL 2026-12-18 200 P')
    expect(byTestId('close-trade-action')).toHaveTextContent('BTC（买入平仓）')
    expect(byTestId('close-trade-multiplier')).toHaveTextContent('100')
    expect(byTestId('close-trade-contracts-2')).toBeChecked()
    expect(byTestId('close-trade-contracts-3')).not.toBeChecked()
    expect(within(modal).queryByTestId('close-trade-contracts-1')).not.toBeInTheDocument()
    expect(byTestId('close-trade-net-amount-label')).toHaveTextContent('实付总额（含手续费）')
    expect(byTestId('close-trade-time')).toHaveValue('2026-11-11T10:00')
    expect(screen.getByText('必须整笔平掉开仓批次（FIFO，从最早的一笔起）')).toBeInTheDocument()
    // Contract and direction are not editable inputs.
    expect(within(modal).queryByTestId('open-trade-symbol')).not.toBeInTheDocument()
  })

  it('submits the close, shows P&L and covered lots, and refreshes positions and cash (AC 1, 9)', async () => {
    const requests = startFakeServer(acceptingAc1(scenarioA()))
    renderWithProviders(<PositionsPage />)

    await userEvent.click(await screen.findByTestId('position-close'))
    setText('close-trade-net-amount', '241.30')
    setText('close-trade-time', '2026-11-05T10:00')
    await userEvent.click(byTestId('close-trade-submit'))

    const confirmation = await screen.findByTestId('close-trade-confirmation')
    const c = (id: string) => within(confirmation).getByTestId(`close-trade-confirmation-${id}`)
    expect(c('contract')).toHaveTextContent('AAPL 2026-12-18 200 P')
    expect(c('action')).toHaveTextContent('BTC（买入平仓）')
    expect(c('contracts')).toHaveTextContent('2 张')
    expect(c('net-amount')).toHaveTextContent('241.30')
    expect(c('time')).toHaveTextContent('2026-11-05 10:00（美东）')
    expect(c('cash-change')).toHaveTextContent('−241.30')
    expect(c('frozen-change')).toHaveTextContent('−40,000.00')
    expect(c('realized-pnl')).toHaveTextContent('+457.40')
    expect(c('realized-pnl')).toHaveStyle({ color: '#389e0d' })
    const lots = within(confirmation).getAllByTestId('covered-lot-row')
    expect(lots).toHaveLength(1)
    const lot = within(lots[0] as HTMLElement)
    expect(lot.getByTestId('covered-lot-time')).toHaveTextContent('2026-11-03 10:30')
    expect(lot.getByTestId('covered-lot-action')).toHaveTextContent('STO')
    expect(lot.getByTestId('covered-lot-contracts')).toHaveTextContent('2 张')
    expect(lot.getByTestId('covered-lot-net-amount')).toHaveTextContent('+698.70')

    expect(posts(requests)[0]?.body).toEqual({
      symbol: 'AAPL',
      expirationDate: '2026-12-18',
      strike: '200',
      optionType: 'PUT',
      action: 'BTC',
      contracts: 2,
      netAmount: '241.30',
      tradedAtLocal: '2026-11-05T10:00',
      note: null,
    })

    // Cash summary in the dialog and the positions table refresh without reload.
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('80,817.40')
    })
    expect(byTestId('cash-summary-frozen')).toHaveTextContent('20,000.00')
    expect(byTestId('cash-summary-available')).toHaveTextContent('60,817.40')
    await waitFor(() => {
      expect(byTestId('positions-frozen-total')).toHaveTextContent('冻结现金合计 20,000.00')
    })
    expect(byTestId('position-cell-contracts')).toHaveTextContent('1')

    await userEvent.click(byTestId('close-trade-done'))
    await waitFor(() => {
      expect(screen.queryByTestId('close-trade-confirmation')).not.toBeInTheDocument()
    })
    // The refreshed row now only offers 1 contract.
    await userEvent.click(byTestId('position-close'))
    expect(await screen.findByTestId('close-trade-contracts-1')).toBeChecked()
    expect(screen.queryByTestId('close-trade-contracts-2')).not.toBeInTheDocument()
  })

  it.each([
    ['CLOSE_NOT_WHOLE_LOTS', '必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张'],
    [
      'CASH_INSUFFICIENT',
      '可用现金不足：需要 20,349.36，可用 349.35 + 释放冻结 20,000.00 = 20,349.35',
    ],
    [
      'NO_POSITION_TO_CLOSE',
      '补录后该合约（AAPL 2026-12-18 200 P）在 2026-11-06 10:00 的 BTC 1 张将没有持仓可平',
    ],
  ])(
    'shows 422 %s verbatim in the dialog and keeps the input (AC 2, 6, 10)',
    async (code, message) => {
      const requests = startFakeServer(scenarioA({ onClose: () => reject422(code, message) }))
      renderWithProviders(<PositionsPage />)

      await userEvent.click(await screen.findByTestId('position-close'))
      await userEvent.click(byTestId('close-trade-contracts-3'))
      setText('close-trade-net-amount', '400.00')
      setText('close-trade-time', '2026-11-05T10:00')
      setText('close-trade-note', '试平')
      await userEvent.click(byTestId('close-trade-submit'))

      const error = await screen.findByTestId('close-trade-error')
      expect(error).toHaveTextContent(message)
      expect(error).toHaveAttribute('data-error-code', code)
      expect(byTestId('close-trade-contracts-3')).toBeChecked()
      expect(byTestId('close-trade-net-amount')).toHaveValue('400.00')
      expect(byTestId('close-trade-time')).toHaveValue('2026-11-05T10:00')
      expect(byTestId('close-trade-note')).toHaveValue('试平')
      expect(screen.queryByTestId('close-trade-confirmation')).not.toBeInTheDocument()
      expect(requests.filter((r) => r.url === '/api/option-positions')).toHaveLength(1)
    },
  )

  it('shows a backend time error under the trade time field (AC 8)', async () => {
    startFakeServer(
      scenarioA({
        onClose: () =>
          jsonResponse(
            {
              code: 'VALIDATION_ERROR',
              message: 'Request validation failed',
              status: 400,
              path: '/api/option-trades/closes',
              timestamp: '2026-11-11T15:00:00Z',
              details: [{ field: 'tradedAtLocal', message: '到期日（2026-12-18）之后不能平仓' }],
            },
            400,
          ),
      }),
    )
    renderWithProviders(<PositionsPage />)
    await userEvent.click(await screen.findByTestId('position-close'))
    setText('close-trade-net-amount', '1.00')
    await userEvent.click(byTestId('close-trade-submit'))

    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('close-trade-time'))).getByText(
          '到期日（2026-12-18）之后不能平仓',
        ),
      ).toBeInTheDocument()
    })
    expect(screen.queryByTestId('close-trade-error')).not.toBeInTheDocument()
  })

  it('validates the net amount on the client without submitting (AC 11)', async () => {
    const requests = startFakeServer(scenarioA())
    renderWithProviders(<PositionsPage />)
    await userEvent.click(await screen.findByTestId('position-close'))

    for (const [value, message] of [
      ['0', '净额必须大于 0'],
      ['-10.00', '净额必须是大于 0 的数字'],
      ['241.305', '净额最多 2 位小数'],
    ] as const) {
      setText('close-trade-net-amount', value)
      await userEvent.click(byTestId('close-trade-submit'))
      await waitFor(() => {
        expect(
          within(formItemOf(byTestId('close-trade-net-amount'))).getByText(message),
        ).toBeInTheDocument()
      })
    }
    expect(posts(requests)).toHaveLength(0)
  })

  it('hides 平仓 for expired positions and prefills STC for a long expiring today (AC 8, 9)', async () => {
    const expired: OptionPosition = {
      ...AAPL_POSITION,
      symbol: 'TSLA',
      expirationDate: '2026-11-06',
      strike: '250',
      contractLabel: 'TSLA 2026-11-06 250 P',
      dte: -5,
      expirationStatus: 'EXPIRED',
      daysPastExpiration: 5,
      closable: false,
      allowedCloseContracts: [1],
    }
    const today: OptionPosition = {
      ...AAPL_POSITION,
      symbol: 'SPY',
      expirationDate: '2026-11-11',
      strike: '600',
      optionType: 'CALL',
      contractLabel: 'SPY 2026-11-11 600 C',
      direction: 'LONG',
      closeAction: 'STC',
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
      closable: true,
      allowedCloseContracts: [1],
    }
    startFakeServer(
      scenarioA({ positions: { ...POSITIONS_A, positionCount: 2, positions: [expired, today] } }),
    )
    renderWithProviders(<PositionsPage />)

    const rows = await screen.findAllByTestId('position-row')
    expect(within(rows[0] as HTMLElement).queryByTestId('position-close')).not.toBeInTheDocument()
    await userEvent.click(within(rows[1] as HTMLElement).getByTestId('position-close'))
    expect(await screen.findByTestId('close-trade-action')).toHaveTextContent('STC（卖出平仓）')
    expect(byTestId('close-trade-net-amount-label')).toHaveTextContent('实收净额（已扣手续费）')
    expect(byTestId('close-trade-contracts-1')).toBeChecked()
  })

  it('cancels without saving', async () => {
    const requests = startFakeServer(scenarioA())
    renderWithProviders(<PositionsPage />)
    await userEvent.click(await screen.findByTestId('position-close'))
    await userEvent.click(await screen.findByTestId('close-trade-cancel'))
    await waitFor(() => {
      expect(screen.queryByTestId('close-trade-form')).not.toBeInTheDocument()
    })
    expect(posts(requests)).toHaveLength(0)
  })
})

// ---------------------------------------------------------------------------
// Entry 2: 录入交易 page with BTC / STC
// ---------------------------------------------------------------------------

async function fillClose(action: 'btc' | 'stc', values: Record<string, string>) {
  const user = userEvent.setup()
  await user.click(byTestId(`open-trade-action-${action}`))
  await user.click(
    byTestId(values.type === 'call' ? 'open-trade-type-call' : 'open-trade-type-put'),
  )
  setText('open-trade-symbol', values.symbol ?? 'AAPL')
  setText('open-trade-expiration', values.expiration ?? '2026-12-18')
  setText('open-trade-strike', values.strike ?? '200')
  setText('open-trade-contracts', values.contracts ?? '2')
  setText('open-trade-net-amount', values.netAmount ?? '241.30')
  setText('open-trade-time', values.time ?? '2026-11-05T10:00')
  return user
}

describe('录入交易 page with closes', () => {
  it('offers all four actions, switches the label and hides multiplier for closes', async () => {
    startFakeServer(scenarioA())
    renderWithProviders(<TradeEntryPage />)
    const user = userEvent.setup()
    const label = byTestId('open-trade-net-amount-label')

    await user.click(byTestId('open-trade-action-btc'))
    expect(label).toHaveTextContent('实付总额（含手续费）')
    expect(screen.queryByTestId('open-trade-multiplier')).not.toBeInTheDocument()

    await user.click(byTestId('open-trade-action-stc'))
    expect(label).toHaveTextContent('实收净额（已扣手续费）')
    expect(screen.queryByTestId('open-trade-multiplier')).not.toBeInTheDocument()

    await user.click(byTestId('open-trade-action-sto'))
    expect(label).toHaveTextContent('实收净额（已扣手续费）')
    expect(byTestId('open-trade-multiplier')).toHaveValue('100')

    await user.click(byTestId('open-trade-action-bto'))
    expect(label).toHaveTextContent('实付总额（含手续费）')
  })

  it('posts BTC to the closes endpoint without multiplier and shows the close result (AC 1)', async () => {
    const requests = startFakeServer(acceptingAc1(scenarioA()))
    renderWithProviders(<TradeEntryPage />)
    expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('81,058.70')

    const user = await fillClose('btc', {})
    await user.click(byTestId('open-trade-submit'))

    const confirmation = await screen.findByTestId('open-trade-confirmation')
    expect(confirmation).toHaveTextContent('平仓交易已保存')
    expect(
      within(confirmation).getByTestId('open-trade-confirmation-realized-pnl'),
    ).toHaveTextContent('+457.40')
    expect(within(confirmation).getAllByTestId('covered-lot-row')).toHaveLength(1)
    const post = posts(requests)[0]
    expect(post?.url).toBe('/api/option-trades/closes')
    expect(post?.body).not.toHaveProperty('multiplier')
    expect(post?.body).toMatchObject({ action: 'BTC', contracts: 2, netAmount: '241.30' })
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('80,817.40')
    })
  })

  it('shows a covering-two-lots close and a negative P&L in red (AC 3, 6)', async () => {
    const close: OptionTrade = {
      ...CLOSE_AC1,
      contracts: 3,
      netAmount: '20349.35',
      cashChange: '-20349.35',
      frozenChange: '-60000.00',
      coveredLots: [
        { ...L1, contracts: 2, netAmount: '698.70', frozen: '40000.00' },
        { ...L2, contracts: 1, netAmount: '360.00', frozen: '20000.00' },
      ],
      realizedPnl: '-19290.65',
    }
    startFakeServer(scenarioA({ onClose: () => jsonResponse(close, 201) }))
    renderWithProviders(<TradeEntryPage />)
    const user = await fillClose('btc', { contracts: '3', netAmount: '20349.35' })
    await user.click(byTestId('open-trade-submit'))

    const pnl = await screen.findByTestId('open-trade-confirmation-realized-pnl')
    expect(pnl).toHaveTextContent('−19,290.65')
    expect(pnl).toHaveStyle({ color: '#cf1322' })
    const lots = screen.getAllByTestId('covered-lot-row')
    expect(lots.map((row) => row.getAttribute('data-trade-id'))).toEqual(['1', '2'])
    expect(byTestId('open-trade-confirmation-frozen-change')).toHaveTextContent('−60,000.00')
  })

  it('posts STC and shows "—" for a zero frozen change (AC 7)', async () => {
    const stc: OptionTrade = {
      ...CLOSE_AC1,
      strike: '210',
      optionType: 'CALL',
      action: 'STC',
      contracts: 1,
      netAmount: '830.00',
      cashChange: '830.00',
      frozenChange: '0.00',
      contractLabel: 'AAPL 2026-12-18 210 C',
      coveredLots: [{ ...L1, action: 'BTO', contracts: 1, netAmount: '-512.65', frozen: '0.00' }],
      realizedPnl: '317.35',
    }
    const requests = startFakeServer(scenarioA({ onClose: () => jsonResponse(stc, 201) }))
    renderWithProviders(<TradeEntryPage />)
    const user = await fillClose('stc', {
      type: 'call',
      strike: '210',
      contracts: '1',
      netAmount: '830.00',
    })
    await user.click(byTestId('open-trade-submit'))

    expect(await screen.findByTestId('open-trade-confirmation-frozen-change')).toHaveTextContent(
      '—',
    )
    expect(byTestId('open-trade-confirmation-cash-change')).toHaveTextContent('+830.00')
    expect(byTestId('open-trade-confirmation-realized-pnl')).toHaveTextContent('+317.35')
    expect(byTestId('covered-lot-net-amount')).toHaveTextContent('−512.65')
    expect(posts(requests)[0]?.body).toMatchObject({ action: 'STC', optionType: 'CALL' })
  })

  it.each([
    ['CLOSE_EXCEEDS_POSITION', '平仓 4 张超过当时持仓 3 张'],
    [
      'POSITION_DIRECTION_CONFLICT',
      '该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（3 张），应使用 Buy to Close（BTC）',
    ],
    ['NO_POSITION_TO_CLOSE', '该合约（AAPL 2026-12-18 205 P）在成交时没有持仓，无法平仓'],
    ['CLOSE_NOT_WHOLE_LOTS', '必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张'],
  ])('shows 422 %s verbatim and keeps every input (AC 2, 4)', async (code, message) => {
    startFakeServer(scenarioA({ onClose: () => reject422(code, message) }))
    renderWithProviders(<TradeEntryPage />)
    const user = await fillClose('btc', { contracts: '4' })
    await user.click(byTestId('open-trade-submit'))

    const error = await screen.findByTestId('open-trade-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', code)
    expect(byTestId('open-trade-action-btc')).toBeChecked()
    expect(byTestId('open-trade-symbol')).toHaveValue('AAPL')
    expect(byTestId('open-trade-contracts')).toHaveValue('4')
    expect(byTestId('open-trade-net-amount')).toHaveValue('241.30')
    expect(byTestId('open-trade-time')).toHaveValue('2026-11-05T10:00')
  })

  it('shows "到期日之后不能平仓" under the trade time and does not check the date on the client (AC 8)', async () => {
    const requests = startFakeServer(
      scenarioA({
        onClose: () =>
          jsonResponse(
            {
              code: 'VALIDATION_ERROR',
              message: 'Request validation failed',
              status: 400,
              path: '/api/option-trades/closes',
              timestamp: '2026-11-11T15:00:00Z',
              details: [{ field: 'tradedAtLocal', message: '到期日（2026-11-06）之后不能平仓' }],
            },
            400,
          ),
      }),
    )
    renderWithProviders(<TradeEntryPage />)
    const user = await fillClose('btc', {
      symbol: 'TSLA',
      expiration: '2026-11-06',
      strike: '250',
      contracts: '1',
      netAmount: '10.00',
      time: '2026-11-09T10:00',
    })
    await user.click(byTestId('open-trade-submit'))

    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('open-trade-time'))).getByText(
          '到期日（2026-11-06）之后不能平仓',
        ),
      ).toBeInTheDocument()
    })
    expect(posts(requests)).toHaveLength(1)
  })

  it('still posts opens to the open endpoint with multiplier', async () => {
    const requests = startFakeServer(
      scenarioA({
        onOpen: () => reject422('CASH_INSUFFICIENT', '可用现金不足', '/api/option-trades'),
      }),
    )
    renderWithProviders(<TradeEntryPage />)
    const user = userEvent.setup()
    await user.click(byTestId('open-trade-action-bto'))
    await user.click(byTestId('open-trade-type-put'))
    setText('open-trade-symbol', 'AAPL')
    setText('open-trade-expiration', '2026-12-18')
    setText('open-trade-strike', '200')
    setText('open-trade-contracts', '1')
    setText('open-trade-net-amount', '300.00')
    setText('open-trade-time', '2026-11-06T11:00')
    await user.click(byTestId('open-trade-submit'))

    await screen.findByTestId('open-trade-error')
    expect(posts(requests)[0]?.url).toBe('/api/option-trades')
    expect(posts(requests)[0]?.body).toMatchObject({ action: 'BTO', multiplier: 100 })
  })
})

describe('closes elsewhere', () => {
  it('nav says 录入交易 and the trade list shows the close (AC 1)', async () => {
    startFakeServer(scenarioA({ trades: [CLOSE_AC1] }))
    renderWithProviders(<App />, { route: '/trades' })

    expect(byTestId('nav-open-trade')).toHaveTextContent('录入交易')
    const row = (await screen.findAllByTestId('trade-row'))[0] as HTMLElement
    const cell = (id: string) => within(row).getByTestId(id)
    expect(cell('trade-cell-time')).toHaveTextContent('2026-11-05 10:00')
    expect(cell('trade-cell-action')).toHaveTextContent('BTC（买入平仓）')
    expect(cell('trade-cell-contract')).toHaveTextContent('AAPL 2026-12-18 200 P')
    expect(cell('trade-cell-contracts')).toHaveTextContent('2')
    expect(cell('trade-cell-net-amount')).toHaveTextContent('−241.30')
    expect(cell('trade-cell-frozen')).toHaveTextContent('−40,000.00')
  })
})
