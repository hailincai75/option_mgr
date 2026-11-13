import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type { ApiErrorResponse, CashSummary, OptionTrade } from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { TradeEntryPage } from './TradeEntryPage'

interface RecordedRequest {
  method: string
  url: string
  body: unknown
}

interface FakeServer {
  summary: CashSummary
  onPost?: (body: unknown) => Response
}

function startFakeServer(server: FakeServer) {
  const requests: RecordedRequest[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, body })
    if (method === 'GET' && url === '/api/cash/summary') {
      return Promise.resolve(jsonResponse(server.summary))
    }
    if (method === 'POST' && url === '/api/option-trades' && server.onPost) {
      return Promise.resolve(server.onPost(body))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
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
    path: '/api/option-trades',
    timestamp: '2026-11-12T15:00:00Z',
    details,
  }
}

const SUMMARY_30000: CashSummary = {
  totalCash: '30000.00',
  frozenCash: '0.00',
  availableCash: '30000.00',
}

const savedTrade: OptionTrade = {
  id: 1,
  symbol: 'AAPL',
  expirationDate: '2026-12-18',
  strike: '200',
  optionType: 'PUT',
  action: 'STO',
  contracts: 1,
  multiplier: 100,
  netAmount: '349.35',
  tradedAt: '2026-11-10T15:30:00Z',
  tradedAtLocal: '2026-11-10T10:30:00',
  cashChange: '349.35',
  frozenChange: '20000.00',
  contractLabel: 'AAPL 2026-12-18 200 P',
  note: '第一笔 CSP',
  ledgerEntryId: 2,
  createdAt: '2026-11-10T15:45:00.123456Z',
  coveredLots: null,
  realizedPnl: null,
  hasAuditLog: false,
}

const byTestId = (id: string) => screen.getByTestId(id)

function formItemOf(element: HTMLElement): HTMLElement {
  const item = element.closest<HTMLElement>('.ant-form-item')
  if (!item) throw new Error('form item not found')
  return item
}

interface TradeInput {
  action?: 'STO' | 'BTO'
  symbol?: string
  expiration?: string
  strike?: string
  type?: 'call' | 'put'
  contracts?: string
  netAmount?: string
  time?: string
  multiplier?: string
  note?: string
}

/** Fills the form; text fields via change events (fast), radios via clicks. */
async function fillTrade(values: TradeInput) {
  const user = userEvent.setup()
  if (values.action) await user.click(byTestId(`open-trade-action-${values.action.toLowerCase()}`))
  if (values.type) await user.click(byTestId(`open-trade-type-${values.type}`))
  const text: [keyof TradeInput, string][] = [
    ['symbol', 'open-trade-symbol'],
    ['expiration', 'open-trade-expiration'],
    ['strike', 'open-trade-strike'],
    ['contracts', 'open-trade-contracts'],
    ['netAmount', 'open-trade-net-amount'],
    ['time', 'open-trade-time'],
    ['multiplier', 'open-trade-multiplier'],
    ['note', 'open-trade-note'],
  ]
  for (const [key, testId] of text) {
    const value = values[key]
    if (value !== undefined) fireEvent.change(byTestId(testId), { target: { value } })
  }
  return user
}

const AC1_TRADE: TradeInput = {
  action: 'STO',
  symbol: 'AAPL',
  expiration: '2026-12-18',
  strike: '200',
  type: 'put',
  contracts: '1',
  netAmount: '349.35',
  time: '2026-11-10T10:30',
  note: '第一笔 CSP',
}

function expectInputsKept(values: TradeInput) {
  expect(byTestId('open-trade-symbol')).toHaveValue(values.symbol)
  expect(byTestId('open-trade-expiration')).toHaveValue(values.expiration)
  expect(byTestId('open-trade-strike')).toHaveValue(values.strike)
  expect(byTestId('open-trade-contracts')).toHaveValue(values.contracts)
  expect(byTestId('open-trade-net-amount')).toHaveValue(values.netAmount)
  expect(byTestId('open-trade-time')).toHaveValue(values.time)
  expect(byTestId('open-trade-multiplier')).toHaveValue(values.multiplier ?? '100')
  if (values.action) {
    expect(byTestId(`open-trade-action-${values.action.toLowerCase()}`)).toBeChecked()
  }
  if (values.type) {
    expect(byTestId(`open-trade-type-${values.type}`)).toBeChecked()
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('OpenTradePage', () => {
  it('shows the cash summary and defaults the trade time to New York now (AC 11)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-10T14:45:00Z'))
    startFakeServer({ summary: SUMMARY_30000 })

    renderWithProviders(<TradeEntryPage />)

    expect(byTestId('open-trade-time')).toHaveValue('2026-11-10T09:45')
    expect(screen.getByText('成交时间（美东时间）')).toBeInTheDocument()
    expect(byTestId('open-trade-multiplier')).toHaveValue('100')
    expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('30,000.00')
    expect(byTestId('cash-summary-available')).toHaveTextContent('30,000.00')
  })

  it('defaults to New York daylight time in summer', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-07-15T14:30:00Z'))
    startFakeServer({ summary: SUMMARY_30000 })

    renderWithProviders(<TradeEntryPage />)

    expect(byTestId('open-trade-time')).toHaveValue('2026-07-15T10:30')
  })

  it('switches the net amount label with the action', async () => {
    startFakeServer({ summary: SUMMARY_30000 })
    renderWithProviders(<TradeEntryPage />)
    const label = byTestId('open-trade-net-amount-label')
    expect(label).toHaveTextContent('净额（含手续费）')

    const user = userEvent.setup()
    await user.click(byTestId('open-trade-action-sto'))
    expect(label).toHaveTextContent('实收净额（已扣手续费）')
    await user.click(byTestId('open-trade-action-bto'))
    expect(label).toHaveTextContent('实付总额（含手续费）')
  })

  it('saves an STO put, shows the confirmation and refreshes the cash summary (AC 1, 16)', async () => {
    const server: FakeServer = { summary: SUMMARY_30000 }
    server.onPost = () => {
      server.summary = {
        totalCash: '30349.35',
        frozenCash: '20000.00',
        availableCash: '10349.35',
      }
      return jsonResponse(savedTrade, 201)
    }
    const requests = startFakeServer(server)

    renderWithProviders(<TradeEntryPage />)
    expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('30,000.00')

    const user = await fillTrade({ ...AC1_TRADE, symbol: ' aapl ' })
    await user.click(byTestId('open-trade-submit'))

    const confirmation = await screen.findByTestId('open-trade-confirmation')
    const c = within(confirmation)
    expect(c.getByTestId('open-trade-confirmation-contract')).toHaveTextContent(
      'AAPL 2026-12-18 200 P',
    )
    expect(c.getByTestId('open-trade-confirmation-action')).toHaveTextContent('STO')
    expect(c.getByTestId('open-trade-confirmation-contracts')).toHaveTextContent('1 张')
    expect(c.getByTestId('open-trade-confirmation-net-amount')).toHaveTextContent('349.35')
    expect(c.getByTestId('open-trade-confirmation-time')).toHaveTextContent(
      '2026-11-10 10:30（美东）',
    )
    expect(c.getByTestId('open-trade-confirmation-cash-change')).toHaveTextContent('+349.35')
    expect(c.getByTestId('open-trade-confirmation-frozen-change')).toHaveTextContent('+20,000.00')
    expect(c.queryByTestId('open-trade-confirmation-multiplier')).not.toBeInTheDocument()

    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('30,349.35')
    })
    expect(byTestId('cash-summary-frozen')).toHaveTextContent('20,000.00')
    expect(byTestId('cash-summary-available')).toHaveTextContent('10,349.35')

    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({
      symbol: ' aapl ',
      expirationDate: '2026-12-18',
      strike: '200',
      optionType: 'PUT',
      action: 'STO',
      contracts: 1,
      netAmount: '349.35',
      tradedAtLocal: '2026-11-10T10:30',
      multiplier: 100,
      note: '第一笔 CSP',
    })

    // Form cleared for the next entry (multiplier back to 100).
    expect(byTestId('open-trade-symbol')).toHaveValue('')
    expect(byTestId('open-trade-net-amount')).toHaveValue('')
    expect(byTestId('open-trade-multiplier')).toHaveValue('100')
    expect(byTestId('open-trade-action-sto')).not.toBeChecked()
  })

  it('shows the multiplier in the confirmation when it is not 100', async () => {
    startFakeServer({
      summary: SUMMARY_30000,
      onPost: () =>
        jsonResponse(
          { ...savedTrade, symbol: 'XYZ', multiplier: 10, contractLabel: 'XYZ 2026-12-18 200 P' },
          201,
        ),
    })
    renderWithProviders(<TradeEntryPage />)
    const user = await fillTrade({ ...AC1_TRADE, symbol: 'XYZ', multiplier: '10' })
    await user.click(byTestId('open-trade-submit'))

    expect(await screen.findByTestId('open-trade-confirmation-multiplier')).toHaveTextContent(
      'multiplier 10',
    )
  })

  it.each([
    [
      'CASH_INSUFFICIENT',
      '可用现金不足：需要 512.65，可用 500.00',
      { action: 'BTO', type: 'call', strike: '210', netAmount: '512.65' },
    ],
    [
      'CASH_INSUFFICIENT',
      '可用现金不足以担保卖出 put：需要 20,000.00；2026-11-05 10:00 时可用现金将变为 −150.65',
      {},
    ],
    [
      'POSITION_DIRECTION_CONFLICT',
      '该合约（AAPL 2026-12-18 200 P）在成交时为空头持仓（1 张），不能买入开仓；如要平仓请使用 Buy to Close（BTC）',
      { action: 'BTO', netAmount: '300.00' },
    ],
    // FEAT-038: SELL_CALL_NOT_SUPPORTED no longer exists (STO call is allowed).
    [
      'POSITION_DIRECTION_CONFLICT',
      '该合约（AAPL 2026-12-18 210 C）在成交时为多头持仓（1 张），不能卖出开仓；如要平仓请使用 Sell to Close（STC）',
      { type: 'call', strike: '210', netAmount: '120.00' },
    ],
    [
      'MULTIPLIER_MISMATCH',
      '该合约（XYZ 2026-12-18 200 P）已有未平仓持仓，multiplier 为 10，新开仓的 multiplier 必须相同',
      { symbol: 'XYZ' },
    ],
  ] as const)(
    'shows 422 %s verbatim and keeps every input (AC 4, 8, 15, 16)',
    async (code, message, overrides) => {
      const requests = startFakeServer({
        summary: SUMMARY_30000,
        onPost: () => jsonResponse(errorBody(code, 422, message), 422),
      })
      const input: TradeInput = { ...AC1_TRADE, ...overrides }

      renderWithProviders(<TradeEntryPage />)
      expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('30,000.00')
      const user = await fillTrade(input)
      await user.click(byTestId('open-trade-submit'))

      const error = await screen.findByTestId('open-trade-error')
      expect(error).toHaveTextContent(message)
      expect(error).toHaveAttribute('data-error-code', code)
      expectInputsKept(input)
      expect(byTestId('open-trade-note')).toHaveValue('第一笔 CSP')
      expect(screen.queryByTestId('open-trade-confirmation')).not.toBeInTheDocument()
      // No refresh after a rejection.
      expect(requests.filter((r) => r.url === '/api/cash/summary')).toHaveLength(1)
    },
  )

  it('maps backend field errors under the matching fields and keeps the input (AC 12)', async () => {
    startFakeServer({
      summary: SUMMARY_30000,
      onPost: () =>
        jsonResponse(
          errorBody('VALIDATION_ERROR', 400, 'Request validation failed', [
            { field: 'expirationDate', message: '到期日不能早于成交日（2026-11-10）' },
            { field: 'tradedAtLocal', message: '成交时间不能晚于当前时间' },
            { field: 'symbol', message: '标的只能包含字母、数字和点' },
          ]),
          400,
        ),
    })

    renderWithProviders(<TradeEntryPage />)
    const user = await fillTrade(AC1_TRADE)
    await user.click(byTestId('open-trade-submit'))

    expect(
      await within(formItemOf(byTestId('open-trade-expiration'))).findByText(
        '到期日不能早于成交日（2026-11-10）',
      ),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-time'))).getByText('成交时间不能晚于当前时间'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-symbol'))).getByText('标的只能包含字母、数字和点'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('open-trade-error')).not.toBeInTheDocument()
    expectInputsKept(AC1_TRADE)
  })

  it('shows unmapped detail fields in the general error', async () => {
    startFakeServer({
      summary: SUMMARY_30000,
      onPost: () =>
        jsonResponse(
          errorBody('BAD_REQUEST', 400, 'Malformed request body', [
            { field: 'somethingElse', message: 'bad' },
          ]),
          400,
        ),
    })
    renderWithProviders(<TradeEntryPage />)
    const user = await fillTrade(AC1_TRADE)
    await user.click(byTestId('open-trade-submit'))

    expect(await screen.findByTestId('open-trade-error')).toHaveTextContent(
      'Malformed request body（somethingElse: bad）',
    )
  })

  it('validates required fields on the client and does not submit', async () => {
    const requests = startFakeServer({ summary: SUMMARY_30000 })
    renderWithProviders(<TradeEntryPage />)
    await userEvent.click(byTestId('open-trade-submit'))

    expect(await screen.findByText('请选择方向')).toBeInTheDocument()
    expect(screen.getByText('请选择 Call 或 Put')).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-symbol'))).getByText('标的不能为空'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-expiration'))).getByText('到期日不能为空'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-strike'))).getByText('行权价不能为空'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-contracts'))).getByText('张数不能为空'),
    ).toBeInTheDocument()
    expect(
      within(formItemOf(byTestId('open-trade-net-amount'))).getByText('净额不能为空'),
    ).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('validates formats on the client (positive, decimals, integers) and does not submit', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-10T14:45:00Z'))
    const requests = startFakeServer({ summary: SUMMARY_30000 })
    renderWithProviders(<TradeEntryPage />)

    const user = await fillTrade({
      ...AC1_TRADE,
      symbol: '$AAPL',
      strike: '200.1234',
      contracts: '1.5',
      netAmount: '349.355',
      multiplier: '0',
      time: '2026-11-10T10:00',
      expiration: '2026-11-09',
      note: 'x'.repeat(501),
    })
    await user.click(byTestId('open-trade-submit'))

    const errorUnder = (testId: string, text: string) =>
      within(formItemOf(byTestId(testId))).findByText(text)
    expect(await errorUnder('open-trade-symbol', '标的只能包含字母、数字和点')).toBeInTheDocument()
    expect(await errorUnder('open-trade-strike', '行权价最多 3 位小数')).toBeInTheDocument()
    expect(await errorUnder('open-trade-contracts', '张数必须是正整数')).toBeInTheDocument()
    expect(await errorUnder('open-trade-net-amount', '净额最多 2 位小数')).toBeInTheDocument()
    expect(await errorUnder('open-trade-multiplier', 'multiplier 必须是正整数')).toBeInTheDocument()
    expect(await errorUnder('open-trade-time', '成交时间不能晚于当前时间')).toBeInTheDocument()
    expect(
      await errorUnder('open-trade-expiration', '到期日不能早于成交日（2026-11-10）'),
    ).toBeInTheDocument()
    expect(await errorUnder('open-trade-note', '备注最多 500 个字符')).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('clears the form with the clear button', async () => {
    startFakeServer({ summary: SUMMARY_30000 })
    renderWithProviders(<TradeEntryPage />)
    const user = await fillTrade(AC1_TRADE)
    await user.click(byTestId('open-trade-clear'))

    expect(byTestId('open-trade-symbol')).toHaveValue('')
    expect(byTestId('open-trade-strike')).toHaveValue('')
    expect(byTestId('open-trade-multiplier')).toHaveValue('100')
    expect(byTestId('open-trade-time')).not.toHaveValue('')
  })

  describe('STO call (FEAT-038, Q61)', () => {
    const HINT =
      '卖出 call 不冻结现金，请自行确保账户有足够股票覆盖（每张 = multiplier 股）；股票持仓功能（FEAT-011）上线后将自动检查。'

    it('shows the hint only for STO + Call', async () => {
      startFakeServer({ summary: SUMMARY_30000 })
      renderWithProviders(<TradeEntryPage />)
      const user = userEvent.setup()
      await screen.findByTestId('cash-summary-total')
      expect(screen.queryByTestId('open-trade-short-call-hint')).not.toBeInTheDocument()

      await user.click(byTestId('open-trade-action-sto'))
      await user.click(byTestId('open-trade-type-put'))
      expect(screen.queryByTestId('open-trade-short-call-hint')).not.toBeInTheDocument()

      await user.click(byTestId('open-trade-type-call'))
      expect(byTestId('open-trade-short-call-hint')).toHaveTextContent(HINT)

      await user.click(byTestId('open-trade-action-bto'))
      expect(screen.queryByTestId('open-trade-short-call-hint')).not.toBeInTheDocument()
      for (const closing of ['btc', 'stc']) {
        await user.click(byTestId(`open-trade-action-${closing}`))
        expect(screen.queryByTestId('open-trade-short-call-hint')).not.toBeInTheDocument()
      }
    })

    it('does not block submitting an STO call; frozen change shows — (AC 1)', async () => {
      const ixqz = {
        ...savedTrade,
        id: 1,
        symbol: 'IXQZ',
        expirationDate: '2026-11-20',
        strike: '7.5',
        optionType: 'CALL' as const,
        netAmount: '14.34',
        cashChange: '14.34',
        frozenChange: '0.00',
        contractLabel: 'IXQZ 2026-11-20 7.5 C',
        tradedAt: '2026-11-09T18:40:00Z',
        tradedAtLocal: '2026-11-09T13:40:00',
        note: null,
      }
      const requests = startFakeServer({
        summary: SUMMARY_30000,
        onPost: () => jsonResponse(ixqz, 201),
      })
      renderWithProviders(<TradeEntryPage />)
      const user = await fillTrade({
        ...AC1_TRADE,
        symbol: 'IXQZ',
        expiration: '2026-11-20',
        strike: '7.5',
        type: 'call',
        netAmount: '14.34',
        time: '2026-11-09T13:40',
        note: '',
      })
      expect(byTestId('open-trade-short-call-hint')).toBeInTheDocument()
      await user.click(byTestId('open-trade-submit'))

      const confirmation = await screen.findByTestId('open-trade-confirmation')
      expect(
        within(confirmation).getByTestId('open-trade-confirmation-cash-change'),
      ).toHaveTextContent('+14.34')
      expect(
        within(confirmation).getByTestId('open-trade-confirmation-frozen-change'),
      ).toHaveTextContent('—')
      expect(requests.find((r) => r.method === 'POST')?.body).toMatchObject({
        action: 'STO',
        optionType: 'CALL',
        strike: '7.5',
        netAmount: '14.34',
      })
    })
  })
})
