import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { vi } from 'vitest'
import userEvent from '../test/userEvent'
import type {
  ApiErrorResponse,
  CashLedgerEntry,
  ClosedTrade,
  ClosedTradePage,
  CoveredLot,
  OptionPosition,
  OptionPositionsResponse,
  OptionTrade,
  OptionTradePage,
} from '../api/types'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { ClosedTradesPage } from './ClosedTradesPage'
import { PositionsPage } from './PositionsPage'
import { TradeEntryPage } from './TradeEntryPage'
import { TradeListPage } from './TradeListPage'

// FEAT-012 到期作废. Fictional tickers (XYZ, QRTV) from the requirement's scenario E;
// every value below is what the backend would return (the frontend calculates nothing).

const byTestId = (id: string) => screen.getByTestId(id)

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'XYZ',
    expirationDate: '2026-11-20',
    strike: '45',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 3,
    multiplier: 100,
    openingNetTotal: '370.00',
    averageNetPerContract: '123.33',
    frozenCash: '13500.00',
    dte: -3,
    expirationStatus: 'EXPIRED',
    daysPastExpiration: 3,
    lotCount: 2,
    lots: [],
    closeAction: 'BTC',
    allowedCloseContracts: [2, 3],
    closable: false,
    expiryPending: false,
    expirable: true,
    expiryContracts: 3,
    expiryAt: '2026-11-20T21:00:00Z',
    expiryAtLocal: '2026-11-20T16:00:00',
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

const XYZ_45_P = position({})
const QRTV_30_C = position({
  symbol: 'QRTV',
  strike: '30',
  optionType: 'CALL',
  direction: 'LONG',
  contracts: 1,
  openingNetTotal: '-85.65',
  averageNetPerContract: '-85.65',
  frozenCash: '0.00',
  lotCount: 1,
  closeAction: 'STC',
  allowedCloseContracts: [1],
  expiryContracts: 1,
})

function positionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return {
    asOfDate: '2026-11-23',
    positionCount: positions.length,
    frozenTotal: '13500.00',
    positions,
  }
}

const E1_LOT: CoveredLot = {
  tradeId: 1,
  action: 'STO',
  tradedAt: '2026-11-03T15:00:00Z',
  tradedAtLocal: '2026-11-03T10:00:00',
  contracts: 2,
  netAmount: '240.00',
  frozen: '9000.00',
}
const E3_LOT: CoveredLot = {
  tradeId: 3,
  action: 'STO',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  contracts: 1,
  netAmount: '130.00',
  frozen: '4500.00',
}

function expiry(partial: Partial<OptionTrade>): OptionTrade {
  return {
    id: 7,
    symbol: 'XYZ',
    expirationDate: '2026-11-20',
    strike: '45',
    optionType: 'PUT',
    action: 'EXP',
    contracts: 3,
    multiplier: 100,
    netAmount: '0.00',
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    cashChange: '0.00',
    frozenChange: '-13500.00',
    contractLabel: 'XYZ 2026-11-20 45 P',
    note: null,
    ledgerEntryId: 7,
    createdAt: '2026-11-23T15:00:00Z',
    coveredLots: [E1_LOT, E3_LOT],
    realizedPnl: '370.00',
    hasAuditLog: false,
    expiredDirection: 'SHORT',
    assignment: null,
    ...partial,
  }
}

const XYZ_EXPIRY = expiry({})
const QRTV_EXPIRY = expiry({
  id: 8,
  symbol: 'QRTV',
  strike: '30',
  optionType: 'CALL',
  contracts: 1,
  frozenChange: '0.00',
  contractLabel: 'QRTV 2026-11-20 30 C',
  ledgerEntryId: null,
  coveredLots: [
    {
      tradeId: 2,
      action: 'BTO',
      tradedAt: '2026-11-04T15:00:00Z',
      tradedAtLocal: '2026-11-04T10:00:00',
      contracts: 1,
      netAmount: '-85.65',
      frozen: '0.00',
    },
  ],
  realizedPnl: '-85.65',
  expiredDirection: 'LONG',
  assignment: null,
})

const E1_TRADE: OptionTrade = {
  ...expiry({}),
  id: 1,
  action: 'STO',
  contracts: 2,
  netAmount: '240.00',
  tradedAt: '2026-11-03T15:00:00Z',
  tradedAtLocal: '2026-11-03T10:00:00',
  cashChange: '240.00',
  frozenChange: '9000.00',
  ledgerEntryId: 2,
  coveredLots: null,
  realizedPnl: null,
  expiredDirection: null,
  assignment: null,
}

function errorBody(
  code: string,
  status: number,
  message: string,
  path: string,
  details: ApiErrorResponse['details'] = [],
): ApiErrorResponse {
  return { code, message, status, path, timestamp: '2026-11-23T15:00:00Z', details }
}

interface Recorded {
  method: string
  url: string
  body: unknown
}

type Handler = (request: Recorded) => Response | undefined

/** Fake backend: the first handler that returns a Response answers; cash summary is always zero. */
function startFakeServer(...handlers: Handler[]): Recorded[] {
  const requests: Recorded[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    const request = { method, url, body }
    requests.push(request)
    for (const handler of handlers) {
      const response = handler(request)
      if (response) return Promise.resolve(response)
    }
    if (method === 'GET' && url === '/api/cash/summary') {
      return Promise.resolve(
        jsonResponse({ totalCash: '20266.69', frozenCash: '0.00', availableCash: '20266.69' }),
      )
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

const positionsGets = (requests: Recorded[]) =>
  requests.filter((r) => r.method === 'GET' && r.url === '/api/option-positions')

// ---------------------------------------------------------------------------

describe('FEAT-012 positions page buttons (Q10 / Q83)', () => {
  it('shows only the button the backend flags allow', async () => {
    const beforeCutoff = position({
      symbol: 'AAA',
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
      daysPastExpiration: 0,
      closable: true,
      expirable: false,
    })
    const pending = position({
      symbol: 'BBB',
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
      daysPastExpiration: 0,
      expiryPending: true,
      expirable: false,
    })
    const todayExpirable = position({
      symbol: 'CCC',
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
      daysPastExpiration: 0,
    })
    startFakeServer(({ url }) =>
      url === '/api/option-positions'
        ? jsonResponse(positionsResponse([beforeCutoff, pending, todayExpirable, XYZ_45_P]))
        : undefined,
    )
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    const before = rowOf('AAA 2026-11-20 45 P')
    expect(within(before).getByTestId('position-close')).toBeInTheDocument()
    expect(within(before).queryByTestId('position-expire')).not.toBeInTheDocument()
    expect(within(before).getByTestId('position-cell-dte')).toHaveTextContent('今天到期')
    expect(within(before).queryByTestId('position-expiry-pending')).not.toBeInTheDocument()

    const waiting = rowOf('BBB 2026-11-20 45 P')
    expect(within(waiting).queryByTestId('position-close')).not.toBeInTheDocument()
    expect(within(waiting).queryByTestId('position-expire')).not.toBeInTheDocument()
    expect(within(waiting).getByTestId('position-cell-dte')).toHaveTextContent('今天到期')
    const tag = within(waiting).getByTestId('position-expiry-pending')
    expect(tag).toHaveTextContent('到期待确认')
    await userEvent.hover(tag)
    expect(
      await screen.findByText('16:00 已停止交易；17:30 前仍可能被指派，17:30（美东）起可作废'),
    ).toBeInTheDocument()

    const today = rowOf('CCC 2026-11-20 45 P')
    expect(within(today).getByTestId('position-cell-dte')).toHaveTextContent('今天到期')
    expect(within(today).getByTestId('position-expire')).toBeInTheDocument()
    expect(within(today).queryByTestId('position-close')).not.toBeInTheDocument()

    const expired = rowOf('XYZ 2026-11-20 45 P')
    expect(within(expired).getByTestId('position-cell-dte')).toHaveTextContent('已过期 3 天')
    expect(within(expired).getByTestId('position-expire')).toBeInTheDocument()
    expect(within(expired).queryByTestId('position-close')).not.toBeInTheDocument()
  })
})

describe('FEAT-012 作废 dialog', () => {
  it('confirms a short put expiry and shows the backend result (AC 2, 13)', async () => {
    let expired = false
    const requests = startFakeServer(({ method, url }) => {
      if (method === 'GET' && url === '/api/option-positions') {
        return jsonResponse(positionsResponse(expired ? [QRTV_30_C] : [QRTV_30_C, XYZ_45_P]))
      }
      if (method === 'POST' && url === '/api/option-trades/expirations') {
        expired = true
        return jsonResponse(XYZ_EXPIRY, 201)
      }
      return undefined
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await userEvent.click(within(rowOf('XYZ 2026-11-20 45 P')).getByTestId('position-expire'))

    const modal = await screen.findByTestId('expire-modal')
    expect(within(modal).getByTestId('expire-contract')).toHaveTextContent('XYZ 2026-11-20 45 P')
    expect(within(modal).getByTestId('expire-direction')).toHaveTextContent('空头')
    expect(within(modal).getByTestId('expire-contracts')).toHaveTextContent('3 张（全部剩余）')
    expect(within(modal).getByTestId('expire-time')).toHaveTextContent('2026-11-20 16:00（美东）')
    expect(within(modal).getByTestId('expire-opening-net')).toHaveTextContent('+370.00')
    expect(within(modal).getByTestId('expire-frozen')).toHaveTextContent('13,500.00')
    expect(within(modal).getByTestId('expire-explanation')).toHaveTextContent(
      '到期作废记在到期日 16:00；空头保留全部权利金、多头亏掉全部开仓成本；记错可以在交易列表中删除',
    )
    // Nothing to fill in (Q84).
    expect(within(modal).queryByRole('textbox')).not.toBeInTheDocument()

    await userEvent.click(within(modal).getByTestId('expire-submit'))

    const pnl = await within(modal).findByTestId('expire-result-pnl')
    expect(pnl).toHaveTextContent('+370.00')
    expect(pnl).toHaveStyle({ color: '#389e0d' })
    expect(within(modal).getByTestId('expire-result-frozen-change')).toHaveTextContent(
      '−13,500.00',
    )
    const lots = within(modal).getAllByTestId('covered-lot-row')
    expect(lots.map((row) => row.textContent)).toEqual([
      '2026-11-03 10:00STO（卖出开仓）2 张+240.00',
      '2026-11-05 10:00STO（卖出开仓）1 张+130.00',
    ])

    const post = requests.find((r) => r.method === 'POST')
    expect(post?.url).toBe('/api/option-trades/expirations')
    expect(post?.body).toEqual({
      symbol: 'XYZ',
      expirationDate: '2026-11-20',
      strike: '45',
      optionType: 'PUT',
      contracts: 3,
    })

    // Positions refresh without reload; the XYZ row is gone.
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(1)
    })
    expect(positionsGets(requests).length).toBeGreaterThanOrEqual(2)

    await userEvent.click(within(modal).getByTestId('expire-done'))
    await waitFor(() => {
      expect(screen.queryByTestId('expire-modal')).not.toBeInTheDocument()
    })
  })

  it('shows "—" for frozen values of a long call and a red loss (AC 3)', async () => {
    startFakeServer(({ method, url }) => {
      if (method === 'GET' && url === '/api/option-positions') {
        return jsonResponse(positionsResponse([QRTV_30_C]))
      }
      if (method === 'POST' && url === '/api/option-trades/expirations') {
        return jsonResponse(QRTV_EXPIRY, 201)
      }
      return undefined
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await userEvent.click(within(rowOf('QRTV 2026-11-20 30 C')).getByTestId('position-expire'))

    const modal = await screen.findByTestId('expire-modal')
    expect(within(modal).getByTestId('expire-direction')).toHaveTextContent('多头')
    expect(within(modal).getByTestId('expire-opening-net')).toHaveTextContent('−85.65')
    expect(within(modal).getByTestId('expire-frozen')).toHaveTextContent('—')

    await userEvent.click(within(modal).getByTestId('expire-submit'))
    const pnl = await within(modal).findByTestId('expire-result-pnl')
    expect(pnl).toHaveTextContent('−85.65')
    expect(pnl).toHaveStyle({ color: '#cf1322' })
    expect(within(modal).getByTestId('expire-result-frozen-change')).toHaveTextContent('—')
  })

  it.each([
    ['POSITION_CHANGED', '持仓已变化（当前 4 张），请刷新后重试'],
    ['EXPIRY_TOO_EARLY', '到期日（2026-11-20）美东 17:30 起才能作废'],
    [
      'EXPIRY_BLOCKED_BY_LATE_TRADE',
      '2026-11-20 16:10 的 BTC 2 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它',
    ],
  ])('shows a %s rejection verbatim and keeps the dialog open (AC 12)', async (code, message) => {
    const requests = startFakeServer(({ method, url }) => {
      if (method === 'GET' && url === '/api/option-positions') {
        return jsonResponse(positionsResponse([XYZ_45_P]))
      }
      if (method === 'POST' && url === '/api/option-trades/expirations') {
        return jsonResponse(errorBody(code, 422, message, url), 422)
      }
      return undefined
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await userEvent.click(within(rowOf('XYZ 2026-11-20 45 P')).getByTestId('position-expire'))
    await userEvent.click(byTestId('expire-submit'))

    const error = await screen.findByTestId('expire-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', code)
    expect(byTestId('expire-modal')).toBeInTheDocument()
    expect(screen.queryByTestId('expire-result')).not.toBeInTheDocument()
    // The list behind the dialog reloads so a changed position shows its new count.
    await waitFor(() => {
      expect(positionsGets(requests)).toHaveLength(2)
    })
  })
})

describe('FEAT-012 trade list', () => {
  function tradeServer(onDelete?: () => Response): Recorded[] {
    return startFakeServer(({ method, url }) => {
      if (method === 'GET' && url.startsWith('/api/option-trades?')) {
        const query = new URLSearchParams(url.split('?')[1])
        const actions = query.getAll('action')
        const items = [XYZ_EXPIRY, E1_TRADE].filter(
          (trade) => actions.length === 0 || actions.includes(trade.action),
        )
        const page: OptionTradePage = {
          items,
          page: 1,
          size: 20,
          totalItems: items.length,
          totalPages: items.length > 0 ? 1 : 0,
        }
        return jsonResponse(page)
      }
      if (method === 'DELETE' && url === '/api/option-trades/7' && onDelete) return onDelete()
      return undefined
    })
  }

  async function expiryRow(): Promise<HTMLElement> {
    const rows = await screen.findAllByTestId('trade-row')
    const row = rows.find((element) => element.getAttribute('data-trade-id') === '7')
    if (!row) throw new Error('EXP row not found')
    return row
  }

  it('shows an EXP row with 删除 only (AC 2)', async () => {
    tradeServer()
    renderWithProviders(<TradeListPage />)
    const row = await expiryRow()
    const cell = (id: string) => within(row).getByTestId(id).textContent
    expect([
      cell('trade-cell-time'),
      cell('trade-cell-action'),
      cell('trade-cell-contract'),
      cell('trade-cell-contracts'),
      cell('trade-cell-net-amount'),
      cell('trade-cell-frozen'),
      cell('trade-cell-note'),
    ]).toEqual([
      '2026-11-20 16:00',
      '到期作废',
      'XYZ 2026-11-20 45 P',
      '3',
      '0.00',
      '−13,500.00',
      '—',
    ])
    expect(within(row).getByTestId('trade-delete')).toBeInTheDocument()
    expect(within(row).queryByTestId('trade-edit')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('trade-history')).not.toBeInTheDocument()
    // Other rows keep 修改.
    const open = screen
      .getAllByTestId('trade-row')
      .find((element) => element.getAttribute('data-trade-id') === '1')
    if (!open) throw new Error('row 1 not found')
    expect(within(open).getByTestId('trade-edit')).toBeInTheDocument()
  })

  it('filters by 到期作废 through the URL (AC 3)', async () => {
    const requests = tradeServer()
    renderWithProviders(<TradeListPage />, { route: '/trades' })
    await expiryRow()
    const checkbox = byTestId('trade-filter-action-exp')
    expect(checkbox.closest('label')).toHaveTextContent('到期作废')
    await userEvent.click(checkbox)
    await userEvent.click(byTestId('trade-filter-submit'))

    await waitFor(() => {
      expect(screen.getAllByTestId('trade-row')).toHaveLength(1)
    })
    expect(requests.some((r) => r.url.includes('action=EXP'))).toBe(true)
  })

  it('deletes an EXP with its own summary; a rejection stays open (AC 5, 7)', async () => {
    let attempt = 0
    const requests = tradeServer(() => {
      attempt += 1
      return attempt === 1
        ? jsonResponse(
            errorBody(
              'CASH_INSUFFICIENT',
              422,
              '可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −405.00',
              '/api/option-trades/7',
            ),
            422,
          )
        : new Response(null, { status: 204 })
    })
    renderWithProviders(<TradeListPage />)
    await userEvent.click(within(await expiryRow()).getByTestId('trade-delete'))

    expect(await screen.findByTestId('trade-delete-summary')).toHaveTextContent(
      '到期作废 3 张 XYZ 2026-11-20 45 P，2026-11-20 16:00，0.00',
    )
    expect(byTestId('trade-delete-warning')).toHaveTextContent(
      '删除后持仓恢复、冻结恢复，会写入审计记录。',
    )

    await userEvent.click(byTestId('trade-delete-confirm'))
    expect(await screen.findByTestId('trade-delete-error')).toHaveTextContent(
      '可用现金不足：删除后 2026-11-30 10:00 时可用现金将变为 −405.00',
    )
    expect(byTestId('trade-delete-modal')).toBeInTheDocument()

    await userEvent.click(byTestId('trade-delete-confirm'))
    await waitFor(() => {
      expect(screen.queryByTestId('trade-delete-modal')).not.toBeInTheDocument()
    })
    expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(2)
  })
})

describe('FEAT-012 closed trades', () => {
  const closedExpiry: ClosedTrade = {
    type: 'OPTION',
    id: 7,
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    symbol: 'XYZ',
    expirationDate: '2026-11-20',
    strike: '45',
    optionType: 'PUT',
    contractLabel: 'XYZ 2026-11-20 45 P',
    action: 'EXP',
    closedDirection: 'SHORT',
    contracts: 3,
    multiplier: 100,
    openingNetTotal: '370.00',
    openingNetPerContract: '123.33',
    closeNetAmount: '0.00',
    closeNetPerContract: '0.00',
    realizedPnl: '370.00',
    note: null,
    coveredLots: [E1_LOT, E3_LOT],
    hasAuditLog: false,
    shares: null,
    costPerShare: null,
    pricePerShare: null,
    soldLots: null,
    assignment: null,
  }

  it('shows an EXP row and offers the 到期作废 filter (AC 2, 3)', async () => {
    const requests = startFakeServer(({ method, url }) => {
      if (method === 'GET' && url.startsWith('/api/closed-trades')) {
        const page: ClosedTradePage = {
          items: [closedExpiry],
          page: 1,
          size: 20,
          totalItems: 1,
          totalPages: 1,
          filteredCount: 1,
          filteredRealizedPnlTotal: '370.00',
        }
        return jsonResponse(page)
      }
      return undefined
    })
    renderWithProviders(<ClosedTradesPage />, { route: '/closed?action=EXP' })

    const row = await screen.findByTestId('closed-row')
    expect(within(row).getByTestId('closed-cell-time')).toHaveTextContent('2026-11-20 16:00')
    expect(within(row).getByTestId('closed-cell-action')).toHaveTextContent('到期作废')
    expect(within(row).getByTestId('closed-cell-direction')).toHaveTextContent('空头')
    expect(within(row).getByTestId('closed-cell-opening-net')).toHaveTextContent('+370.00')
    expect(within(row).getByTestId('closed-cell-opening-net-per')).toHaveTextContent(
      '每张 +123.33',
    )
    expect(within(row).getByTestId('closed-cell-close-net')).toHaveTextContent('0.00')
    expect(within(row).getByTestId('closed-cell-close-net-per')).toHaveTextContent('每张 0.00')
    expect(within(row).getByTestId('closed-cell-pnl')).toHaveTextContent('+370.00')
    expect(within(row).queryByTestId('closed-history')).not.toBeInTheDocument()
    expect(byTestId('closed-expiry-note')).toHaveTextContent(
      '到期作废的平仓净额为 0.00，记在到期日 16:00（美东）',
    )
    expect(byTestId('closed-filter-action-exp').closest('label')).toHaveTextContent('到期作废')
    expect(byTestId('closed-filter-action-exp')).toBeChecked()
    expect(screen.queryByText(/方向只能是/)).not.toBeInTheDocument()
    expect(requests.some((r) => r.url.includes('action=EXP'))).toBe(true)
  })
})

describe('FEAT-012 cash ledger', () => {
  it('labels OPTION_EXPIRE rows 到期作废 without edit buttons (AC 2)', async () => {
    const entry: CashLedgerEntry = {
      id: 7,
      occurredAt: '2026-11-20T21:00:00Z',
      sourceType: 'OPTION_EXPIRE',
      sourceId: 7,
      amount: '0.00',
      balanceAfter: '20266.69',
      frozenChange: '-13500.00',
      description: '到期作废 3 张 XYZ 2026-11-20 45 P',
      remark: null,
      hasAuditLog: false,
    }
    startFakeServer(({ url }) =>
      url.startsWith('/api/cash/ledger')
        ? jsonResponse({ items: [entry], page: 1, size: 20, totalItems: 1, totalPages: 1 })
        : undefined,
    )
    renderWithProviders(<CashLedgerTable page={1} onPageChange={() => undefined} />)
    const row = await screen.findByTestId('cash-ledger-row')
    expect(within(row).getByTestId('cash-ledger-type')).toHaveTextContent('到期作废')
    expect(within(row).queryByTestId('cash-ledger-edit')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('cash-ledger-delete')).not.toBeInTheDocument()
    expect(within(row).getByTestId('cash-ledger-trades-link')).toBeInTheDocument()
  })
})

describe('FEAT-012 trade time after 16:00 (Q83)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the backend 16:00 error under the trade time field (AC 9)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-23T15:00:00Z'))
    const message = '成交时间不能晚于到期日（2026-11-20）美东 16:00'
    startFakeServer(({ method, url }) =>
      method === 'POST' && url === '/api/option-trades'
        ? jsonResponse(
            errorBody('VALIDATION_ERROR', 400, 'Request validation failed', url, [
              { field: 'tradedAtLocal', message },
            ]),
            400,
          )
        : undefined,
    )
    renderWithProviders(<TradeEntryPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-trade-action-sto'))
    await user.click(byTestId('open-trade-type-put'))
    const fields: [string, string][] = [
      ['open-trade-symbol', 'XYZ'],
      ['open-trade-expiration', '2026-11-20'],
      ['open-trade-strike', '45'],
      ['open-trade-contracts', '1'],
      ['open-trade-net-amount', '50.00'],
      ['open-trade-time', '2026-11-20T16:01'],
    ]
    for (const [testId, value] of fields) {
      fireEvent.change(byTestId(testId), { target: { value } })
    }
    await user.click(byTestId('open-trade-submit'))

    const timeItem = byTestId('open-trade-time').closest<HTMLElement>('.ant-form-item')
    if (!timeItem) throw new Error('form item not found')
    expect(await within(timeItem).findByText(message)).toBeInTheDocument()
    expect(screen.queryByTestId('open-trade-error')).not.toBeInTheDocument()
    expect(byTestId('open-trade-time')).toHaveValue('2026-11-20T16:01')
  })
})
