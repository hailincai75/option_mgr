import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {
  ApiErrorResponse,
  AuditLogPage,
  AuditRecord,
  CashLedgerEntry,
  CashLedgerPage,
  CashSummary,
  OptionTrade,
  OptionTradePage,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { CashPage } from './CashPage'
import { TradeListPage } from './TradeListPage'

// ---------------------------------------------------------------------------
// FEAT-005 scenario A: D1 deposit, L1 / L2 STO AAPL 200 P, C1 BTC 2 (closes L1)
// ---------------------------------------------------------------------------

function trade(partial: Partial<OptionTrade> & Pick<OptionTrade, 'id'>): OptionTrade {
  return {
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
    contractLabel: 'AAPL 2026-12-18 200 P',
    note: null,
    ledgerEntryId: partial.id + 1,
    createdAt: '2026-11-11T15:00:00Z',
    coveredLots: null,
    realizedPnl: null,
    ...partial,
  }
}

const L1 = trade({
  id: 1,
  contracts: 2,
  netAmount: '698.70',
  cashChange: '698.70',
  frozenChange: '40000.00',
})
const L2 = trade({
  id: 2,
  netAmount: '360.00',
  cashChange: '360.00',
  frozenChange: '20000.00',
  tradedAt: '2026-11-04T16:00:00Z',
  tradedAtLocal: '2026-11-04T11:00:00',
})
const C1 = trade({
  id: 3,
  action: 'BTC',
  contracts: 2,
  netAmount: '241.30',
  cashChange: '-241.30',
  frozenChange: '-40000.00',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  coveredLots: [
    {
      tradeId: 1,
      action: 'STO',
      tradedAt: L1.tradedAt,
      tradedAtLocal: L1.tradedAtLocal,
      contracts: 2,
      netAmount: '698.70',
      frozen: '40000.00',
    },
  ],
  realizedPnl: '457.40',
})

const SUMMARY_A: CashSummary = {
  totalCash: '80817.40',
  frozenCash: '20000.00',
  availableCash: '60817.40',
}

function ledgerEntry(
  partial: Partial<CashLedgerEntry> & Pick<CashLedgerEntry, 'id'>,
): CashLedgerEntry {
  return {
    occurredAt: '2026-10-26T14:00:00Z',
    sourceType: 'DEPOSIT',
    sourceId: 1,
    amount: '80000.00',
    balanceAfter: '80000.00',
    frozenChange: '0.00',
    description: null,
    remark: null,
    ...partial,
  }
}

const D1 = ledgerEntry({ id: 1, remark: '初始资金' })
const L2_ENTRY = ledgerEntry({
  id: 3,
  occurredAt: L2.tradedAt,
  sourceType: 'OPTION_OPEN',
  sourceId: 2,
  amount: '360.00',
  balanceAfter: '81058.70',
  frozenChange: '20000.00',
  description: 'STO 1 张 AAPL 2026-12-18 200 P',
})

const AUDIT_EDIT_L2: AuditRecord = {
  id: 1,
  operatedAt: '2026-11-11T15:00:05Z',
  operation: 'UPDATE',
  objectType: 'OPTION_TRADE',
  objectId: 2,
  summary: 'STO AAPL 2026-12-18 200 P',
  before: {
    id: 2,
    contractLabel: 'AAPL 2026-12-18 200 P',
    action: 'STO',
    contracts: 1,
    multiplier: 100,
    netAmount: '360.00',
    tradedAtLocal: '2026-11-04T11:00:00',
    note: null,
    cashChange: '360.00',
    frozenChange: '20000.00',
  },
  after: {
    id: 2,
    contractLabel: 'AAPL 2026-12-18 200 P',
    action: 'STO',
    contracts: 1,
    multiplier: 100,
    netAmount: '365.00',
    tradedAtLocal: '2026-11-04T11:00:00',
    note: null,
    cashChange: '365.00',
    frozenChange: '20000.00',
  },
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

// ---------------------------------------------------------------------------

interface FakeServer {
  trades: OptionTrade[]
  summary: CashSummary
  ledger: CashLedgerEntry[]
  audit: AuditRecord[]
  onPutTrade?: (id: number, body: unknown) => Response
  onDeleteTrade?: (id: number) => Response
  onPutTransfer?: (id: number, body: unknown) => Response
  onDeleteTransfer?: (id: number) => Response
}

interface Recorded {
  method: string
  url: string
  body: unknown
}

function page<T>(items: T[]) {
  return {
    items,
    page: 1,
    size: 20,
    totalItems: items.length,
    totalPages: items.length > 0 ? 1 : 0,
  }
}

function startFakeServer(server: FakeServer) {
  const requests: Recorded[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, body })
    const path = new URL(url, 'http://localhost').pathname
    const idMatch = /\/(\d+)$/.exec(path)
    const id = idMatch ? Number(idMatch[1]) : NaN
    if (method === 'GET' && path === '/api/option-trades') {
      const result: OptionTradePage = page(server.trades)
      return Promise.resolve(jsonResponse(result))
    }
    if (method === 'GET' && path === '/api/cash/summary') {
      return Promise.resolve(jsonResponse(server.summary))
    }
    if (method === 'GET' && path === '/api/cash/ledger') {
      const result: CashLedgerPage = page(server.ledger)
      return Promise.resolve(jsonResponse(result))
    }
    if (method === 'GET' && path === '/api/audit-log') {
      const query = new URL(url, 'http://localhost').searchParams
      const items = server.audit.filter(
        (record) =>
          (!query.get('objectType') || record.objectType === query.get('objectType')) &&
          (!query.get('objectId') || record.objectId === Number(query.get('objectId'))),
      )
      const result: AuditLogPage = page(items)
      return Promise.resolve(jsonResponse(result))
    }
    if (method === 'GET' && path === '/api/option-positions') {
      return Promise.resolve(
        jsonResponse({
          asOfDate: '2026-11-11',
          positionCount: 0,
          frozenTotal: '0.00',
          positions: [],
        }),
      )
    }
    if (method === 'PUT' && path.startsWith('/api/option-trades/') && server.onPutTrade) {
      return Promise.resolve(server.onPutTrade(id, body))
    }
    if (method === 'DELETE' && path.startsWith('/api/option-trades/') && server.onDeleteTrade) {
      return Promise.resolve(server.onDeleteTrade(id))
    }
    if (method === 'PUT' && path.startsWith('/api/cash/transfers/') && server.onPutTransfer) {
      return Promise.resolve(server.onPutTransfer(id, body))
    }
    if (method === 'DELETE' && path.startsWith('/api/cash/transfers/') && server.onDeleteTransfer) {
      return Promise.resolve(server.onDeleteTransfer(id))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function scenarioA(overrides: Partial<FakeServer> = {}): FakeServer {
  return {
    trades: [C1, L2, L1],
    summary: SUMMARY_A,
    ledger: [D1],
    audit: [],
    ...overrides,
  }
}

function error422(code: string, message: string): Response {
  const body: ApiErrorResponse = {
    code,
    message,
    status: 422,
    path: '/api/option-trades/1',
    timestamp: '2026-11-11T15:00:00Z',
    details: [],
  }
  return jsonResponse(body, 422)
}

function noContent(): Response {
  return new Response(null, { status: 204 })
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

async function tradeRow(id: number): Promise<HTMLElement> {
  await screen.findAllByTestId('trade-row')
  const row = screen
    .getAllByTestId('trade-row')
    .find((element) => element.getAttribute('data-trade-id') === String(id))
  if (!row) throw new Error(`trade row ${String(id)} not found`)
  return row
}

function writes(requests: Recorded[]) {
  return requests.filter((r) => r.method === 'PUT' || r.method === 'DELETE')
}

// ---------------------------------------------------------------------------
// Trade list: 修改
// ---------------------------------------------------------------------------

describe('trade list: 修改', () => {
  it('prefills editable fields; contract and direction are read-only (AC 11)', async () => {
    startFakeServer(scenarioA())
    renderWithProviders(<TradeListPage />)

    await userEvent.click(within(await tradeRow(2)).getByTestId('trade-edit'))
    expect(await screen.findByTestId('trade-edit-contract')).toHaveTextContent(
      'AAPL 2026-12-18 200 P',
    )
    expect(byTestId('trade-edit-action')).toHaveTextContent('STO（卖出开仓）')
    expect(byTestId('trade-edit-contracts')).toHaveValue('1')
    expect(byTestId('trade-edit-net-amount')).toHaveValue('360.00')
    expect(byTestId('trade-edit-net-amount-label')).toHaveTextContent('实收净额（已扣手续费）')
    expect(byTestId('trade-edit-time')).toHaveValue('2026-11-04T11:00')
    expect(byTestId('trade-edit-multiplier')).toHaveValue('100')
    expect(byTestId('trade-edit-note')).toHaveValue('')
    expect(screen.getByText('合约或方向录错请删除后重新录入。')).toBeInTheDocument()
    const form = byTestId('trade-edit-form')
    // No editable inputs for symbol / expiry / strike / type / action.
    expect(
      within(form)
        .getAllByRole('textbox')
        .map((el) => el.getAttribute('data-testid')),
    ).toEqual([
      'trade-edit-contracts',
      'trade-edit-net-amount',
      'trade-edit-multiplier',
      'trade-edit-note',
    ])
  })

  it('saves an edited open with every editable field and refreshes the list (AC 1, 13)', async () => {
    const server = scenarioA()
    server.onPutTrade = () => {
      const edited = { ...L2, netAmount: '365.00', cashChange: '365.00' }
      server.trades = [C1, edited, L1]
      return jsonResponse(edited)
    }
    const requests = startFakeServer(server)
    renderWithProviders(<TradeListPage />)

    await userEvent.click(within(await tradeRow(2)).getByTestId('trade-edit'))
    setText('trade-edit-net-amount', '365.00')
    await userEvent.click(byTestId('trade-edit-submit'))

    await waitFor(() => {
      expect(screen.queryByTestId('trade-edit-modal')).not.toBeInTheDocument()
    })
    expect(writes(requests)).toEqual([
      {
        method: 'PUT',
        url: '/api/option-trades/2',
        body: {
          contracts: 1,
          netAmount: '365.00',
          tradedAtLocal: '2026-11-04T11:00',
          multiplier: 100,
          note: null,
        },
      },
    ])
    await waitFor(async () => {
      expect(within(await tradeRow(2)).getByTestId('trade-cell-net-amount')).toHaveTextContent(
        '+365.00',
      )
    })
  })

  it('edits a close without multiplier and with the paid label (AC 2, 11)', async () => {
    const server = scenarioA()
    server.onPutTrade = () => jsonResponse({ ...C1, netAmount: '250.00', cashChange: '-250.00' })
    const requests = startFakeServer(server)
    renderWithProviders(<TradeListPage />)

    await userEvent.click(within(await tradeRow(3)).getByTestId('trade-edit'))
    expect(await screen.findByTestId('trade-edit-action')).toHaveTextContent('BTC（买入平仓）')
    expect(byTestId('trade-edit-net-amount-label')).toHaveTextContent('实付总额（含手续费）')
    expect(screen.queryByTestId('trade-edit-multiplier')).not.toBeInTheDocument()
    expect(byTestId('trade-edit-multiplier-readonly')).toHaveTextContent('100')
    setText('trade-edit-net-amount', '250.00')
    await userEvent.click(byTestId('trade-edit-submit'))

    await waitFor(() => {
      expect(writes(requests)).toHaveLength(1)
    })
    expect(writes(requests)[0]?.body).toEqual({
      contracts: 2,
      netAmount: '250.00',
      tradedAtLocal: '2026-11-05T10:00',
      note: null,
    })
  })

  it.each([
    ['CLOSE_NOT_WHOLE_LOTS', '必须一次平掉整笔开仓：最早一笔是 2 张，可平 2 张或 3 张'],
    [
      'CLOSE_NOT_WHOLE_LOTS',
      '修改后该合约（AAPL 2026-12-18 200 P）在 2026-11-05 10:00 的 BTC 2 张将不是整笔开仓（最早一笔是 3 张，可平 3 张或 4 张）',
    ],
    ['NO_CHANGES', '没有修改'],
    ['CASH_INSUFFICIENT', '可用现金不足：修改后 2026-11-04 11:00 时可用现金将变为 −41.30'],
  ])('shows 422 %s verbatim and keeps the input (AC 3, 4, 11)', async (code, message) => {
    startFakeServer(scenarioA({ onPutTrade: () => error422(code, message) }))
    renderWithProviders(<TradeListPage />)

    await userEvent.click(within(await tradeRow(3)).getByTestId('trade-edit'))
    setText('trade-edit-contracts', '1')
    setText('trade-edit-note', '试改')
    await userEvent.click(byTestId('trade-edit-submit'))

    const error = await screen.findByTestId('trade-edit-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', code)
    expect(byTestId('trade-edit-contracts')).toHaveValue('1')
    expect(byTestId('trade-edit-note')).toHaveValue('试改')
    expect(byTestId('trade-edit-modal')).toBeInTheDocument()
  })

  it('shows backend field errors under the field (AC 5)', async () => {
    startFakeServer(
      scenarioA({
        onPutTrade: () =>
          jsonResponse(
            {
              code: 'VALIDATION_ERROR',
              message: 'Request validation failed',
              status: 400,
              path: '/api/option-trades/3',
              timestamp: '2026-11-11T15:00:00Z',
              details: [{ field: 'tradedAtLocal', message: '成交时间不能晚于当前时间' }],
            },
            400,
          ),
      }),
    )
    renderWithProviders(<TradeListPage />)
    await userEvent.click(within(await tradeRow(3)).getByTestId('trade-edit'))
    await userEvent.click(byTestId('trade-edit-submit'))

    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('trade-edit-time'))).getByText('成交时间不能晚于当前时间'),
      ).toBeInTheDocument()
    })
    expect(screen.queryByTestId('trade-edit-error')).not.toBeInTheDocument()
  })

  it('validates fields on the client without saving (AC 11)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-11T15:00:00Z'))
    const requests = startFakeServer(scenarioA())
    renderWithProviders(<TradeListPage />)
    await userEvent.click(within(await tradeRow(2)).getByTestId('trade-edit'))

    setText('trade-edit-contracts', '1.5')
    setText('trade-edit-net-amount', '360.001')
    setText('trade-edit-time', '2026-11-11T11:00')
    setText('trade-edit-multiplier', '0')
    setText('trade-edit-note', 'x'.repeat(501))
    await userEvent.click(byTestId('trade-edit-submit'))

    const under = (testId: string, text: string) =>
      waitFor(() => {
        expect(within(formItemOf(byTestId(testId))).getByText(text)).toBeInTheDocument()
      })
    await under('trade-edit-contracts', '张数必须是正整数')
    await under('trade-edit-net-amount', '净额最多 2 位小数')
    await under('trade-edit-time', '成交时间不能晚于当前时间')
    await under('trade-edit-multiplier', 'multiplier 必须是正整数')
    await under('trade-edit-note', '备注最多 500 个字符')
    expect(writes(requests)).toHaveLength(0)
    vi.useRealTimers()
  })
})

// ---------------------------------------------------------------------------
// Trade list: 删除 / 历史
// ---------------------------------------------------------------------------

describe('trade list: 删除 and 历史', () => {
  it('confirms with a summary, deletes and refreshes (AC 6)', async () => {
    const server = scenarioA()
    server.onDeleteTrade = () => {
      server.trades = [C1, L1]
      return noContent()
    }
    const requests = startFakeServer(server)
    renderWithProviders(<TradeListPage />)

    await userEvent.click(within(await tradeRow(2)).getByTestId('trade-delete'))
    expect(await screen.findByTestId('trade-delete-summary')).toHaveTextContent(
      'STO 1 张 AAPL 2026-12-18 200 P，2026-11-04 11:00，+360.00',
    )
    expect(screen.getByText('删除后不可恢复，会写入审计记录。')).toBeInTheDocument()
    await userEvent.click(byTestId('trade-delete-confirm'))

    await waitFor(() => {
      expect(screen.queryByTestId('trade-delete-modal')).not.toBeInTheDocument()
    })
    expect(writes(requests)).toEqual([
      { method: 'DELETE', url: '/api/option-trades/2', body: undefined },
    ])
    await waitFor(() => {
      expect(
        screen.getAllByTestId('trade-row').map((row) => row.getAttribute('data-trade-id')),
      ).toEqual(['3', '1'])
    })
  })

  it('shows a rejected delete verbatim and keeps the dialog open (AC 6, 7)', async () => {
    const message =
      '该开仓被 2026-11-05 10:00 的 BTC 2 张平掉（删除后那笔平仓将超过当时持仓 1 张），请先修改或删除那笔平仓'
    startFakeServer(scenarioA({ onDeleteTrade: () => error422('CLOSE_EXCEEDS_POSITION', message) }))
    renderWithProviders(<TradeListPage />)

    await userEvent.click(within(await tradeRow(1)).getByTestId('trade-delete'))
    await userEvent.click(await screen.findByTestId('trade-delete-confirm'))

    const error = await screen.findByTestId('trade-delete-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', 'CLOSE_EXCEEDS_POSITION')
    expect(byTestId('trade-delete-modal')).toBeInTheDocument()
    expect(screen.getAllByTestId('trade-row')).toHaveLength(3)
  })

  it('cancel does not delete', async () => {
    const requests = startFakeServer(scenarioA())
    renderWithProviders(<TradeListPage />)
    await userEvent.click(within(await tradeRow(2)).getByTestId('trade-delete'))
    await userEvent.click(await screen.findByTestId('trade-delete-cancel'))
    await waitFor(() => {
      expect(screen.queryByTestId('trade-delete-modal')).not.toBeInTheDocument()
    })
    expect(writes(requests)).toHaveLength(0)
  })

  it('历史 links to the audit page filtered by the trade (AC 12)', async () => {
    startFakeServer(scenarioA())
    renderWithProviders(<TradeListPage />)
    expect(within(await tradeRow(3)).getByTestId('trade-history')).toHaveAttribute(
      'href',
      '/audit?objectType=OPTION_TRADE&objectId=3',
    )
  })
})

// ---------------------------------------------------------------------------
// Cash ledger: 修改 / 删除 / 历史 for deposits and withdrawals only
// ---------------------------------------------------------------------------

describe('cash ledger: transfer actions', () => {
  it('offers actions only on deposit / withdrawal rows (AC 8)', async () => {
    startFakeServer(scenarioA({ ledger: [L2_ENTRY, D1] }))
    renderWithProviders(<CashPage />)

    const rows = await screen.findAllByTestId('cash-ledger-row')
    const option = within(rows[0] as HTMLElement)
    expect(option.queryByTestId('cash-ledger-edit')).not.toBeInTheDocument()
    expect(option.queryByTestId('cash-ledger-delete')).not.toBeInTheDocument()
    expect(option.getByTestId('cash-ledger-trades-link')).toHaveAttribute('href', '/trades')
    const deposit = within(rows[1] as HTMLElement)
    expect(deposit.getByTestId('cash-ledger-edit')).toBeInTheDocument()
    expect(deposit.getByTestId('cash-ledger-delete')).toBeInTheDocument()
    expect(deposit.getByTestId('cash-ledger-history')).toHaveAttribute(
      'href',
      '/audit?objectType=CASH_TRANSFER&objectId=1',
    )
  })

  it('prefills and saves an edited deposit (AC 8, 9)', async () => {
    const server = scenarioA()
    server.onPutTransfer = () => {
      server.summary = { totalCash: '70817.40', frozenCash: '20000.00', availableCash: '50817.40' }
      server.ledger = [{ ...D1, amount: '70000.00', balanceAfter: '70000.00' }]
      return jsonResponse({
        id: 1,
        type: 'DEPOSIT',
        amount: '70000.00',
        occurredAt: D1.occurredAt,
        remark: '初始资金',
        ledgerEntryId: 1,
        createdAt: '2026-11-11T15:00:00Z',
      })
    }
    const requests = startFakeServer(server)
    renderWithProviders(<CashPage />)

    await userEvent.click(await screen.findByTestId('cash-ledger-edit'))
    expect(await screen.findByTestId('transfer-edit-type-deposit')).toBeChecked()
    expect(byTestId('transfer-edit-amount')).toHaveValue('80000.00')
    expect(byTestId('transfer-edit-time')).toHaveValue('2026-10-26T10:00')
    expect(byTestId('transfer-edit-remark')).toHaveValue('初始资金')

    setText('transfer-edit-amount', '70,000.00')
    await userEvent.click(byTestId('transfer-edit-submit'))

    await waitFor(() => {
      expect(writes(requests)).toHaveLength(1)
    })
    expect(writes(requests)[0]).toEqual({
      method: 'PUT',
      url: '/api/cash/transfers/1',
      body: {
        type: 'DEPOSIT',
        amount: '70000.00',
        occurredAtLocal: '2026-10-26T10:00',
        remark: '初始资金',
      },
    })
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('70,817.40')
    })
    expect(byTestId('cash-ledger-amount')).toHaveTextContent('+70,000.00')
  })

  it('changes the type to withdrawal (AC 9)', async () => {
    const withdrawalLike = ledgerEntry({ id: 2, sourceId: 2, amount: '500.00', remark: null })
    const requests = startFakeServer(
      scenarioA({
        ledger: [withdrawalLike],
        onPutTransfer: () => error422('NO_CHANGES', '没有修改'),
      }),
    )
    renderWithProviders(<CashPage />)
    await userEvent.click(await screen.findByTestId('cash-ledger-edit'))
    await userEvent.click(await screen.findByTestId('transfer-edit-type-withdrawal'))
    await userEvent.click(byTestId('transfer-edit-submit'))

    await waitFor(() => {
      expect(writes(requests)).toHaveLength(1)
    })
    expect(writes(requests)[0]?.body).toMatchObject({
      type: 'WITHDRAWAL',
      amount: '500.00',
      remark: null,
    })
  })

  it('shows a rejected transfer edit verbatim (AC 8)', async () => {
    const message = '可用现金不足：修改后 2026-11-04 11:00 时可用现金将变为 −41.30'
    startFakeServer(scenarioA({ onPutTransfer: () => error422('CASH_INSUFFICIENT', message) }))
    renderWithProviders(<CashPage />)
    await userEvent.click(await screen.findByTestId('cash-ledger-edit'))
    setText('transfer-edit-amount', '58900.00')
    await userEvent.click(byTestId('transfer-edit-submit'))

    expect(await screen.findByTestId('transfer-edit-error')).toHaveTextContent(message)
    expect(byTestId('transfer-edit-amount')).toHaveValue('58900.00')
  })

  it('validates the amount on the client (AC 11)', async () => {
    const requests = startFakeServer(scenarioA())
    renderWithProviders(<CashPage />)
    await userEvent.click(await screen.findByTestId('cash-ledger-edit'))
    setText('transfer-edit-amount', '0')
    await userEvent.click(byTestId('transfer-edit-submit'))
    await waitFor(() => {
      expect(
        within(formItemOf(byTestId('transfer-edit-amount'))).getByText('金额必须大于 0'),
      ).toBeInTheDocument()
    })
    expect(writes(requests)).toHaveLength(0)
  })

  it('deletes a transfer after confirmation, or shows the rejection (AC 9)', async () => {
    const message = '可用现金不足：删除后 2026-11-03 10:30 时可用现金将变为 −39,301.30'
    let reject = true
    const server = scenarioA()
    server.onDeleteTransfer = () => {
      if (reject) return error422('CASH_INSUFFICIENT', message)
      server.ledger = []
      return noContent()
    }
    const requests = startFakeServer(server)
    renderWithProviders(<CashPage />)

    await userEvent.click(await screen.findByTestId('cash-ledger-delete'))
    expect(await screen.findByTestId('transfer-delete-summary')).toHaveTextContent(
      '入金 +80,000.00，2026-10-26 10:00',
    )
    await userEvent.click(byTestId('transfer-delete-confirm'))
    expect(await screen.findByTestId('transfer-delete-error')).toHaveTextContent(message)

    reject = false
    await userEvent.click(byTestId('transfer-delete-confirm'))
    await waitFor(() => {
      expect(screen.queryByTestId('transfer-delete-modal')).not.toBeInTheDocument()
    })
    expect(writes(requests).map((r) => `${r.method} ${r.url}`)).toEqual([
      'DELETE /api/cash/transfers/1',
      'DELETE /api/cash/transfers/1',
    ])
    expect(await screen.findByTestId('cash-ledger-empty')).toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// Refresh across pages (AC 13)
// ---------------------------------------------------------------------------

describe('refresh after an edit', () => {
  it('audit log and cash show the change without reload (AC 1, 13)', async () => {
    const server = scenarioA()
    server.onPutTrade = () => {
      const edited = { ...L2, netAmount: '365.00', cashChange: '365.00' }
      server.trades = [C1, edited, L1]
      server.summary = { totalCash: '80822.40', frozenCash: '20000.00', availableCash: '60822.40' }
      server.audit = [AUDIT_EDIT_L2]
      return jsonResponse(edited)
    }
    startFakeServer(server)
    renderWithProviders(<App />, { route: '/audit' })
    expect(await screen.findByTestId('audit-empty')).toHaveTextContent('暂无审计记录')

    const user = userEvent.setup()
    await user.click(byTestId('nav-cash'))
    expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('80,817.40')

    await user.click(byTestId('nav-trades'))
    await user.click(within(await tradeRow(2)).getByTestId('trade-edit'))
    setText('trade-edit-net-amount', '365.00')
    await user.click(byTestId('trade-edit-submit'))
    await waitFor(() => {
      expect(screen.queryByTestId('trade-edit-modal')).not.toBeInTheDocument()
    })

    await user.click(byTestId('nav-cash'))
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('80,822.40')
    })
    await user.click(byTestId('nav-audit'))
    expect(await screen.findByTestId('audit-cell-changes')).toHaveTextContent(
      '净额：360.00 → 365.00',
    )
  })
})
