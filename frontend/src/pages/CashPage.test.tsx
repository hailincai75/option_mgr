import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {
  ApiErrorResponse,
  CashLedgerEntry,
  CashLedgerPage,
  CashSummary,
  CashTransfer,
} from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { CashPage } from './CashPage'

const ZERO_SUMMARY: CashSummary = { totalCash: '0.00', frozenCash: '0.00', availableCash: '0.00' }

function ledgerPage(items: CashLedgerEntry[], page = 1, totalItems = items.length): CashLedgerPage {
  return { items, page, size: 20, totalItems, totalPages: Math.ceil(totalItems / 20) }
}

const depositEntry: CashLedgerEntry = {
  id: 1,
  occurredAt: '2026-11-02T15:00:00Z',
  sourceType: 'DEPOSIT',
  sourceId: 1,
  amount: '30000.00',
  balanceAfter: '30000.00',
  frozenChange: '0.00',
  description: null,
  remark: '初始资金',
}

const withdrawalEntry: CashLedgerEntry = {
  id: 2,
  occurredAt: '2026-11-03T14:30:00Z',
  sourceType: 'WITHDRAWAL',
  sourceId: 2,
  amount: '-2500.50',
  balanceAfter: '27499.50',
  frozenChange: '0.00',
  description: null,
  remark: null,
}

function errorBody(
  code: string,
  status: number,
  message: string,
  details: ApiErrorResponse['details'] = [],
): ApiErrorResponse {
  return {
    code,
    message,
    status,
    path: '/api/cash/transfers',
    timestamp: '2026-11-10T14:45:00Z',
    details,
  }
}

interface FakeServer {
  summary: CashSummary
  ledger: (page: number) => CashLedgerPage
  onPost?: (body: unknown) => Response
}

interface RecordedRequest {
  method: string
  url: string
  body: unknown
}

/** Routes mocked fetch calls like the backend would; `server` can be mutated between calls. */
function startFakeServer(server: FakeServer) {
  const requests: RecordedRequest[] = []
  const fetchMock = mockFetch()
  fetchMock.mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, body })
    if (method === 'GET' && url === '/api/cash/summary') {
      return Promise.resolve(jsonResponse(server.summary))
    }
    if (method === 'GET' && url.startsWith('/api/cash/ledger')) {
      const page = Number(new URL(url, 'http://localhost').searchParams.get('page'))
      return Promise.resolve(jsonResponse(server.ledger(page)))
    }
    if (method === 'POST' && url === '/api/cash/transfers' && server.onPost) {
      return Promise.resolve(server.onPost(body))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return { requests, fetchMock }
}

function amountInput() {
  return screen.getByTestId('cash-transfer-amount')
}
function timeInput() {
  return screen.getByTestId('cash-transfer-time')
}
function remarkInput() {
  return screen.getByTestId('cash-transfer-remark')
}
/** The antd Form.Item wrapper for a field, so errors can be checked under the right field. */
function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

async function fillForm(values: {
  type?: '入金' | '出金'
  amount?: string
  time?: string
  remark?: string
}) {
  const user = userEvent.setup()
  if (values.type) await user.click(screen.getByRole('radio', { name: values.type }))
  if (values.amount !== undefined) {
    await user.clear(amountInput())
    await user.type(amountInput(), values.amount)
  }
  if (values.time !== undefined) fireEvent.change(timeInput(), { target: { value: values.time } })
  if (values.remark !== undefined) {
    // fireEvent instead of typing: much faster for long values.
    fireEvent.change(remarkInput(), { target: { value: values.remark } })
  }
  return user
}

afterEach(() => {
  vi.useRealTimers()
})

describe('CashPage', () => {
  it('shows 0.00 / 0.00 / 0.00 and "暂无流水" for an empty account (AC 1)', async () => {
    startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) })

    renderWithProviders(<CashPage />)

    expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('0.00')
    expect(screen.getByTestId('cash-summary-frozen')).toHaveTextContent('0.00')
    expect(screen.getByTestId('cash-summary-available')).toHaveTextContent('0.00')
    expect(await screen.findByTestId('cash-ledger-empty')).toHaveTextContent('暂无流水')
    expect(screen.queryByTestId('cash-ledger-row')).not.toBeInTheDocument()
    expect(screen.queryByTestId('cash-ledger-pagination')).not.toBeInTheDocument()
  })

  it('defaults the time to the current New York time (AC 8)', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-10T14:45:00Z'))
    startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) })

    renderWithProviders(<CashPage />)

    expect(timeInput()).toHaveValue('2026-11-10T09:45')
    expect(screen.getByText('时间（美东时间）')).toBeInTheDocument()
  })

  it('defaults to New York daylight time in summer', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-07-15T14:30:00Z'))
    startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) })

    renderWithProviders(<CashPage />)

    expect(timeInput()).toHaveValue('2026-07-15T10:30')
  })

  it('submits a deposit and refreshes summary and ledger without reload (AC 2, 12)', async () => {
    const server: FakeServer = { summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) }
    server.onPost = () => {
      server.summary = { totalCash: '30000.00', frozenCash: '0.00', availableCash: '30000.00' }
      server.ledger = () => ledgerPage([depositEntry])
      const created: CashTransfer = {
        id: 1,
        type: 'DEPOSIT',
        amount: '30000.00',
        occurredAt: '2026-11-02T15:00:00Z',
        remark: '初始资金',
        ledgerEntryId: 1,
        createdAt: '2026-11-10T14:45:00Z',
      }
      return jsonResponse(created, 201)
    }
    const { requests } = startFakeServer(server)

    renderWithProviders(<CashPage />)
    expect(await screen.findByTestId('cash-ledger-empty')).toBeInTheDocument()

    const user = await fillForm({
      type: '入金',
      amount: '30,000.00',
      time: '2026-11-02T10:00',
      remark: '初始资金',
    })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    await waitFor(() => {
      expect(screen.getByTestId('cash-summary-total')).toHaveTextContent('30,000.00')
    })
    expect(screen.getByTestId('cash-summary-frozen')).toHaveTextContent('0.00')
    expect(screen.getByTestId('cash-summary-available')).toHaveTextContent('30,000.00')

    const rows = await screen.findAllByTestId('cash-ledger-row')
    expect(rows).toHaveLength(1)
    const row = within(rows[0] as HTMLElement)
    expect(row.getByTestId('cash-ledger-time')).toHaveTextContent('2026-11-02 10:00')
    expect(row.getByTestId('cash-ledger-type')).toHaveTextContent('入金')
    expect(row.getByTestId('cash-ledger-amount')).toHaveTextContent('+30,000.00')
    expect(row.getByTestId('cash-ledger-balance')).toHaveTextContent('30,000.00')
    expect(row.getByTestId('cash-ledger-note')).toHaveTextContent('初始资金')

    const post = requests.find((request) => request.method === 'POST')
    expect(post?.body).toEqual({
      type: 'DEPOSIT',
      amount: '30000.00',
      occurredAtLocal: '2026-11-02T10:00',
      remark: '初始资金',
    })
    expect(await screen.findByText('入金已保存')).toBeInTheDocument()
    // Form is reset for the next entry.
    expect(amountInput()).toHaveValue('')
  })

  it('shows CASH_INSUFFICIENT as-is and keeps every input (AC 4, 12)', async () => {
    const summary: CashSummary = {
      totalCash: '27499.50',
      frozenCash: '0.00',
      availableCash: '27499.50',
    }
    const message = '可用现金不足：需要 27,499.51，可用 27,499.50'
    const { requests } = startFakeServer({
      summary,
      ledger: () => ledgerPage([withdrawalEntry, depositEntry]),
      onPost: () => jsonResponse(errorBody('CASH_INSUFFICIENT', 422, message), 422),
    })

    renderWithProviders(<CashPage />)
    expect(await screen.findAllByTestId('cash-ledger-row')).toHaveLength(2)

    const user = await fillForm({
      type: '出金',
      amount: '27499.51',
      time: '2026-11-04T10:00',
      remark: '转回银行',
    })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(await screen.findByTestId('cash-transfer-error')).toHaveTextContent(message)
    expect(screen.getByRole('radio', { name: '出金' })).toBeChecked()
    expect(amountInput()).toHaveValue('27499.51')
    expect(timeInput()).toHaveValue('2026-11-04T10:00')
    expect(remarkInput()).toHaveValue('转回银行')
    // Nothing refetched: summary unchanged.
    expect(screen.getByTestId('cash-summary-total')).toHaveTextContent('27,499.50')
    expect(requests.filter((r) => r.url === '/api/cash/summary')).toHaveLength(1)
  })

  it('maps backend field errors under the matching fields (AC 9)', async () => {
    startFakeServer({
      summary: ZERO_SUMMARY,
      ledger: () => ledgerPage([]),
      onPost: () =>
        jsonResponse(
          errorBody('VALIDATION_ERROR', 400, 'Request validation failed', [
            { field: 'amount', message: '金额最多 2 位小数' },
            { field: 'occurredAtLocal', message: '时间不能晚于当前时间' },
          ]),
          400,
        ),
    })

    renderWithProviders(<CashPage />)
    const user = await fillForm({ type: '入金', amount: '100', time: '2026-11-02T10:00' })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(await within(formItemOf(amountInput())).findByText('金额最多 2 位小数')).toBeInTheDocument()
    expect(within(formItemOf(timeInput())).getByText('时间不能晚于当前时间')).toBeInTheDocument()
    expect(screen.queryByTestId('cash-transfer-error')).not.toBeInTheDocument()
    expect(amountInput()).toHaveValue('100')
  })

  it('maps BAD_REQUEST details to the field too', async () => {
    startFakeServer({
      summary: ZERO_SUMMARY,
      ledger: () => ledgerPage([]),
      onPost: () =>
        jsonResponse(
          errorBody('BAD_REQUEST', 400, 'Malformed request body', [
            { field: 'occurredAtLocal', message: '时间格式不正确' },
          ]),
          400,
        ),
    })

    renderWithProviders(<CashPage />)
    const user = await fillForm({ type: '入金', amount: '1', time: '2026-11-02T10:00' })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(await within(formItemOf(timeInput())).findByText('时间格式不正确')).toBeInTheDocument()
  })

  it('shows a general error when the backend gives no field details', async () => {
    startFakeServer({
      summary: ZERO_SUMMARY,
      ledger: () => ledgerPage([]),
      onPost: () => jsonResponse(errorBody('INTERNAL_ERROR', 500, 'Internal server error'), 500),
    })

    renderWithProviders(<CashPage />)
    const user = await fillForm({ type: '入金', amount: '1', time: '2026-11-02T10:00' })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(await screen.findByTestId('cash-transfer-error')).toHaveTextContent(
      'Internal server error',
    )
  })

  it('validates required fields on the client and does not submit', async () => {
    const { requests } = startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) })

    renderWithProviders(<CashPage />)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(await screen.findByText('请选择类型')).toBeInTheDocument()
    expect(within(formItemOf(amountInput())).getByText('请输入金额')).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it.each([
    ['0', '金额必须大于 0'],
    ['-100.00', '金额必须是大于 0 的数字'],
    ['100.001', '金额最多 2 位小数'],
  ])('rejects amount %s on the client', async (amount, error) => {
    const { requests } = startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) })

    renderWithProviders(<CashPage />)
    const user = await fillForm({ type: '入金', amount })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(await within(formItemOf(amountInput())).findByText(error)).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('rejects a future time and a 501-character remark on the client', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-10T14:45:00Z'))
    const { requests } = startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([]) })

    renderWithProviders(<CashPage />)
    const user = await fillForm({
      type: '入金',
      amount: '1',
      time: '2026-11-10T10:00',
      remark: 'x'.repeat(501),
    })
    await user.click(screen.getByTestId('cash-transfer-submit'))

    expect(
      await within(formItemOf(timeInput())).findByText('时间不能晚于当前时间'),
    ).toBeInTheDocument()
    expect(within(formItemOf(remarkInput())).getByText('备注最多 500 个字符')).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('shows outflows in red, option-open entries with description and frozen change, unknown types as-is', async () => {
    const optionEntry: CashLedgerEntry = {
      ...depositEntry,
      id: 3,
      sourceType: 'OPTION_OPEN',
      amount: '350.00',
      balanceAfter: '27849.50',
      frozenChange: '20000.00',
      description: 'STO 1 张 AAPL 2026-12-18 200 P',
      remark: null,
    }
    const futureEntry: CashLedgerEntry = {
      ...depositEntry,
      id: 4,
      sourceType: 'SOMETHING_NEW',
      description: null,
      remark: null,
    }
    startFakeServer({
      summary: ZERO_SUMMARY,
      ledger: () => ledgerPage([futureEntry, optionEntry, withdrawalEntry, depositEntry]),
    })

    renderWithProviders(<CashPage />)
    const rows = await screen.findAllByTestId('cash-ledger-row')

    expect(within(rows[0] as HTMLElement).getByTestId('cash-ledger-type')).toHaveTextContent(
      'SOMETHING_NEW',
    )

    const option = within(rows[1] as HTMLElement)
    expect(option.getByTestId('cash-ledger-type')).toHaveTextContent('期权开仓')
    expect(option.getByTestId('cash-ledger-note')).toHaveTextContent(
      'STO 1 张 AAPL 2026-12-18 200 P',
    )
    expect(option.getByTestId('cash-ledger-frozen')).toHaveTextContent('+20,000.00')

    const withdrawal = within(rows[2] as HTMLElement)
    expect(withdrawal.getByTestId('cash-ledger-frozen')).toHaveTextContent('—')
    expect(withdrawal.getByTestId('cash-ledger-type')).toHaveTextContent('出金')
    expect(withdrawal.getByTestId('cash-ledger-amount')).toHaveTextContent('−2,500.50')
    expect(withdrawal.getByTestId('cash-ledger-amount')).toHaveStyle({ color: '#cf1322' })
    expect(withdrawal.getByTestId('cash-ledger-time')).toHaveTextContent('2026-11-03 09:30')
    expect(withdrawal.getByTestId('cash-ledger-note')).toHaveTextContent('—')

    const deposit = within(rows[3] as HTMLElement)
    expect(deposit.getByTestId('cash-ledger-amount')).toHaveStyle({ color: '#389e0d' })
  })

  it('shows OPTION_CLOSE as 期权平仓 with a negative frozen change (FEAT-004)', async () => {
    const closeEntry: CashLedgerEntry = {
      ...depositEntry,
      id: 5,
      sourceType: 'OPTION_CLOSE',
      amount: '-241.30',
      balanceAfter: '80817.40',
      frozenChange: '-40000.00',
      description: 'BTC 2 张 AAPL 2026-12-18 200 P',
      remark: null,
    }
    startFakeServer({ summary: ZERO_SUMMARY, ledger: () => ledgerPage([closeEntry]) })

    renderWithProviders(<CashPage />)
    const row = within((await screen.findAllByTestId('cash-ledger-row'))[0] as HTMLElement)
    expect(row.getByTestId('cash-ledger-type')).toHaveTextContent('期权平仓')
    expect(row.getByTestId('cash-ledger-amount')).toHaveTextContent('−241.30')
    expect(row.getByTestId('cash-ledger-frozen')).toHaveTextContent('−40,000.00')
    expect(row.getByTestId('cash-ledger-note')).toHaveTextContent('BTC 2 张 AAPL 2026-12-18 200 P')
  })

  it('pages through the ledger (AC 13)', async () => {
    const entryFor = (n: number): CashLedgerEntry => ({
      id: n,
      occurredAt: `2026-09-${String(n).padStart(2, '0')}T14:00:00Z`,
      sourceType: 'DEPOSIT',
      sourceId: n,
      amount: '1.00',
      balanceAfter: `${n}.00`,
      frozenChange: '0.00',
      description: null,
      remark: null,
    })
    // 60 entries, newest first; only ids 1..30 have valid September dates, which is fine here.
    const all = Array.from({ length: 60 }, (_, i) => entryFor(60 - i))
    const { requests } = startFakeServer({
      summary: ZERO_SUMMARY,
      ledger: (page) => ledgerPage(all.slice((page - 1) * 20, page * 20), page, 60),
    })

    renderWithProviders(<CashPage />)

    let rows = await screen.findAllByTestId('cash-ledger-row')
    expect(rows).toHaveLength(20)
    expect(within(rows[0] as HTMLElement).getByTestId('cash-ledger-balance')).toHaveTextContent(
      '60.00',
    )
    expect(screen.getByTestId('cash-ledger-total')).toHaveTextContent('共 60 条')

    const pagination = screen.getByTestId('cash-ledger-pagination')
    await userEvent.click(within(pagination).getByRole('button', { name: 'Page 3' }))

    await waitFor(() => {
      expect(requests.some((r) => r.url === '/api/cash/ledger?page=3&size=20')).toBe(true)
    })
    await waitFor(() => {
      rows = screen.getAllByTestId('cash-ledger-row')
      expect(
        within(rows[19] as HTMLElement).getByTestId('cash-ledger-balance').textContent,
      ).toBe('1.00')
    })
    expect(rows).toHaveLength(20)
    expect(within(rows[0] as HTMLElement).getByTestId('cash-ledger-balance').textContent).toBe(
      '20.00',
    )
  })

  it('shows a ledger error with retry', async () => {
    const fetchMock = mockFetch()
    let ledgerCalls = 0
    fetchMock.mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ''
      if (url === '/api/cash/summary') return Promise.resolve(jsonResponse(ZERO_SUMMARY))
      ledgerCalls += 1
      return Promise.resolve(
        ledgerCalls === 1
          ? jsonResponse(errorBody('INTERNAL_ERROR', 500, 'Internal server error'), 500)
          : jsonResponse(ledgerPage([depositEntry])),
      )
    })

    renderWithProviders(<CashPage />)

    expect(await screen.findByTestId('cash-ledger-error')).toHaveTextContent('INTERNAL_ERROR')
    await userEvent.click(screen.getByTestId('cash-ledger-retry'))
    expect(await screen.findAllByTestId('cash-ledger-row')).toHaveLength(1)
  })
})
