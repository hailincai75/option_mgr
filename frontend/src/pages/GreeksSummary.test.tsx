import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  Account,
  GreeksSummaryGroup,
  GreeksSummaryPortfolio,
  GreeksSummaryResponse,
  OptionPositionsResponse,
  UnrealizedSummaryResponse,
} from '../api/types'
import { App } from '../App'
import { refreshQuotes } from '../components/quote/marketDataRefresh'
import { refreshAfterChange } from '../components/trade/tradeFormSupport'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// FEAT-019 scenario (today 2026-11-23, QRTV 52.40, IXQZ 61.10; fictional tickers), exactly as the
// contract examples in docs/api/FEAT-019.md return it. The page only formats these values.

const BASE_GROUP: GreeksSummaryGroup = {
  symbol: 'QRTV',
  optionDelta: null,
  stockDelta: '0.00',
  netDelta: null,
  gamma: null,
  theta: null,
  vega: null,
  underlyingPrice: '52.40',
  underlyingPriceManual: false,
  underlyingPriceLabel: null,
  dollarExposure: null,
  exposureStatus: 'NO_DELTA',
  exposureLabel: null,
  manualPriceNote: null,
  optionRowCount: 0,
  includedCount: 0,
  noQuoteCount: 0,
  expiredPendingCount: 0,
  deltaMissingCount: 0,
  gammaMissingCount: 0,
  thetaMissingCount: 0,
  vegaMissingCount: 0,
  excludedLabel: null,
  missingLabel: null,
}

const group = (values: Partial<GreeksSummaryGroup>): GreeksSummaryGroup => ({
  ...BASE_GROUP,
  ...values,
})

const BASE_PORTFOLIO: GreeksSummaryPortfolio = {
  dollarExposure: '0.00',
  theta: '0.00',
  vega: '0.00',
  noQuoteCount: 0,
  expiredPendingCount: 0,
  noUnderlyingPriceCount: 0,
  deltaMissingCount: 0,
  thetaMissingCount: 0,
  vegaMissingCount: 0,
  excludedLabel: null,
  noUnderlyingPriceLabel: null,
  missingLabel: null,
}

function summary(
  groups: GreeksSummaryGroup[],
  portfolio: Partial<GreeksSummaryPortfolio>,
): GreeksSummaryResponse {
  return { asOfDate: '2026-11-23', groups, portfolio: { ...BASE_PORTFOLIO, ...portfolio } }
}

const A_IXQZ = group({
  symbol: 'IXQZ',
  optionDelta: '-18.35',
  stockDelta: '100.00',
  netDelta: '81.65',
  gamma: '-3.13',
  theta: '2.47',
  vega: '-5.03',
  underlyingPrice: '61.10',
  dollarExposure: '4988.82',
  exposureStatus: 'OK',
  optionRowCount: 1,
  includedCount: 1,
})
const A_QRTV = group({
  optionDelta: '87.21',
  netDelta: '87.21',
  gamma: '4.48',
  theta: '-3.24',
  vega: '5.89',
  dollarExposure: '4569.80',
  exposureStatus: 'OK',
  optionRowCount: 3,
  includedCount: 2,
  noQuoteCount: 1,
  excludedLabel: '1 个持仓无报价，未计入',
})
/** AC 1–3: account A. */
const ACCOUNT_A = summary([A_IXQZ, A_QRTV], {
  dollarExposure: '9558.62',
  theta: '-0.77',
  vega: '0.86',
  noQuoteCount: 1,
  excludedLabel: '1 个持仓无报价，未计入',
})
/** AC 4: account B. */
const ACCOUNT_B = summary(
  [
    group({
      optionDelta: '90.63',
      stockDelta: '100.00',
      netDelta: '190.63',
      gamma: '-15.36',
      theta: '13.68',
      vega: '-21.33',
      dollarExposure: '9989.01',
      exposureStatus: 'OK',
      optionRowCount: 1,
      includedCount: 1,
    }),
  ],
  { dollarExposure: '9989.01', theta: '13.68', vega: '-21.33' },
)
/** AC 5: QRTV underlying price set manually to 52.00 (account A). */
const ACCOUNT_A_MANUAL = summary(
  [
    A_IXQZ,
    {
      ...A_QRTV,
      underlyingPrice: '52.00',
      underlyingPriceManual: true,
      dollarExposure: '4534.92',
      manualPriceNote: '按手动标的价',
    },
  ],
  { ...ACCOUNT_A.portfolio, dollarExposure: '9523.74' },
)
/** AC 6: P2 gamma and vega null. */
const ACCOUNT_A_MISSING = summary(
  [
    A_IXQZ,
    {
      ...A_QRTV,
      gamma: '-5.12',
      vega: '-7.11',
      gammaMissingCount: 1,
      vegaMissingCount: 1,
      missingLabel: 'Gamma、Vega：1 个持仓缺值，未计入',
    },
  ],
  {
    ...ACCOUNT_A.portfolio,
    vega: '-12.14',
    vegaMissingCount: 1,
    missingLabel: 'Vega：1 个持仓缺值，未计入',
  },
)
/** AC 8: account C, only IXQZ stock, no underlying price. */
const ACCOUNT_C = summary(
  [
    group({
      symbol: 'IXQZ',
      stockDelta: '100.00',
      netDelta: '100.00',
      underlyingPrice: null,
      underlyingPriceLabel: '无标的价',
      exposureStatus: 'NO_UNDERLYING_PRICE',
      exposureLabel: '无标的价',
    }),
  ],
  { noUnderlyingPriceCount: 1, noUnderlyingPriceLabel: '1 个标的无标的价，美元敞口未计入' },
)
/** AC 9: after STC 2 P2, BTC P3 and selling every IXQZ share. */
const ACCOUNT_A_AFTER_CLOSE = summary(
  [
    group({
      optionDelta: '30.21',
      netDelta: '30.21',
      gamma: '-5.12',
      theta: '4.56',
      vega: '-7.11',
      dollarExposure: '1583.00',
      exposureStatus: 'OK',
      optionRowCount: 2,
      includedCount: 1,
      noQuoteCount: 1,
      excludedLabel: '1 个持仓无报价，未计入',
    }),
  ],
  {
    dollarExposure: '1583.00',
    theta: '4.56',
    vega: '-7.11',
    noQuoteCount: 1,
    excludedLabel: '1 个持仓无报价，未计入',
  },
)

const UNREALIZED: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: { marketValue: '0.00', unrealizedPnl: '0.00', includedCount: 0, noQuoteCount: 0, expiredPendingCount: 0 },
  stock: { marketValue: '0.00', unrealizedPnl: '0.00', includedCount: 0, noQuoteCount: 0, expiredPendingCount: 0 },
  total: { marketValue: '0.00', unrealizedPnl: '0.00', includedCount: 0, noQuoteCount: 0, expiredPendingCount: 0 },
  excludedLabel: null,
}

const NO_OPTIONS: OptionPositionsResponse = {
  asOfDate: '2026-11-23',
  positionCount: 0,
  frozenTotal: '0.00',
  positions: [],
}

const ACCOUNTS: Account[] = [
  { id: 1, name: '账户A', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '账户B', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

const SUMMARY_URL = '/api/positions/greeks-summary'

/** Fake backend keyed by `X-Account-Id`; the summary is a function so tests can change it. */
function startFakeServer(byAccount: Record<number, () => GreeksSummaryResponse | Response>) {
  const requests: { url: string; account: string | undefined }[] = []
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    requests.push({ url, account })
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    if (url === '/api/option-positions') return Promise.resolve(jsonResponse(NO_OPTIONS))
    if (url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    if (url === '/api/positions/unrealized-summary') return Promise.resolve(jsonResponse(UNREALIZED))
    if (url === '/api/adhoc-closure-alerts') return Promise.resolve(jsonResponse({ alerts: [] }))
    const answer = byAccount[Number(account)]
    if (url === SUMMARY_URL && answer !== undefined) {
      const body = answer()
      return Promise.resolve(body instanceof Response ? body : jsonResponse(body))
    }
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
  return {
    summaryRequests: () => requests.filter((request) => request.url.startsWith(SUMMARY_URL)),
  }
}

function rowOf(symbol: string): HTMLElement {
  const row = screen
    .queryAllByTestId('greeks-summary-row')
    .find((element) => element.getAttribute('data-symbol') === symbol)
  if (!row) throw new Error(`row ${symbol} not found`)
  return row
}

/** Visible values of one symbol row. */
function rowTexts(symbol: string) {
  const row = rowOf(symbol)
  const text = (id: string) => within(row).getByTestId(`greeks-summary-${id}`).textContent
  return {
    optionDelta: text('option-delta'),
    stockDelta: text('stock-delta'),
    netDelta: text('net-delta'),
    price: text('underlying-price'),
    exposure: text('exposure'),
    gamma: text('gamma'),
    theta: text('theta'),
    vega: text('vega'),
  }
}

function rowNotes(symbol: string) {
  const row = rowOf(symbol)
  return ['manual-price-note', 'excluded-note', 'missing-note'].map(
    (id) => within(row).queryByTestId(`greeks-summary-${id}`)?.textContent ?? null,
  )
}

function portfolioTexts() {
  const text = (id: string) => screen.getByTestId(`greeks-summary-portfolio-${id}`).textContent
  return { exposure: text('exposure'), theta: text('theta'), vega: text('vega') }
}

function portfolioNotes() {
  return ['excluded', 'no-underlying-price', 'missing'].map(
    (id) => screen.queryByTestId(`greeks-summary-portfolio-${id}`)?.textContent ?? null,
  )
}

async function turnOnGreeks() {
  const toggle = await screen.findByTestId('positions-greeks-toggle')
  if (toggle.getAttribute('aria-checked') !== 'true') await userEvent.click(toggle)
  await screen.findAllByTestId('greeks-summary-row')
}

describe('FEAT-019 按标的 Greeks 汇总', () => {
  it('is absent and not requested while 显示 Greeks is off; explains 美元敞口 on hover (AC 10)', async () => {
    const server = startFakeServer({ 1: () => ACCOUNT_A })
    renderWithProviders(<PositionsPage />)
    await screen.findByTestId('positions-empty')

    expect(screen.queryByTestId('greeks-summary')).toBeNull()
    expect(screen.queryByText('按标的 Greeks 汇总')).toBeNull()
    expect(server.summaryRequests()).toEqual([])

    await turnOnGreeks()
    expect(screen.getByTestId('greeks-summary-title')).toHaveTextContent('按标的 Greeks 汇总')
    expect(screen.getByTestId('greeks-summary-note')).toHaveTextContent(
      '按标的汇总当前账户的期权与股票（股票每股 Delta 1）；美元敞口 = 净 Delta × 标的价，仅供参考',
    )
    expect(server.summaryRequests()).toEqual([{ url: SUMMARY_URL, account: '1' }])

    await userEvent.hover(screen.getByTestId('greeks-summary-header-exposure-help'))
    expect(
      await screen.findByText(/Delta 美元敞口 = 净 Delta 股数 × 标的价 ≈ 相当于持有多少美元的这只股票/),
    ).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('positions-greeks-toggle'))
    expect(screen.queryByTestId('greeks-summary')).toBeNull()
  })

  it('shows one row per symbol and the 全组合 row with explicit signs (AC 1–3)', async () => {
    startFakeServer({ 1: () => ACCOUNT_A })
    renderWithProviders(<PositionsPage />)
    await turnOnGreeks()

    const headers = [...screen.getByTestId('greeks-summary').querySelectorAll('th')].map(
      (th) => th.textContent,
    )
    expect(headers).toEqual([
      '标的',
      '期权 Delta',
      '股票 Delta?',
      '净 Delta?',
      '标的价',
      'Delta 美元敞口?',
      'Gamma',
      'Theta',
      'Vega',
    ])
    expect(
      screen.getAllByTestId('greeks-summary-row').map((row) => row.getAttribute('data-symbol')),
    ).toEqual(['IXQZ', 'QRTV'])

    expect(rowTexts('QRTV')).toEqual({
      optionDelta: '+87.21',
      stockDelta: '0.00',
      netDelta: '+87.21',
      price: '52.40',
      exposure: '+4,569.80',
      gamma: '+4.48',
      theta: '−3.24',
      vega: '+5.89',
    })
    expect(rowNotes('QRTV')).toEqual([null, '1 个持仓无报价，未计入', null])
    expect(rowTexts('IXQZ')).toEqual({
      optionDelta: '−18.35',
      stockDelta: '+100.00',
      netDelta: '+81.65',
      price: '61.10',
      exposure: '+4,988.82',
      gamma: '−3.13',
      theta: '+2.47',
      vega: '−5.03',
    })
    expect(rowNotes('IXQZ')).toEqual([null, null, null])

    const portfolio = screen.getByTestId('greeks-summary-portfolio')
    expect(portfolio.textContent).toBe('全组合+9,558.62−0.77+0.86')
    expect(portfolioTexts()).toEqual({ exposure: '+9,558.62', theta: '−0.77', vega: '+0.86' })
    expect(portfolioNotes()).toEqual(['1 个持仓无报价，未计入', null, null])
  })

  it('shows account B after switching accounts (AC 4)', async () => {
    const server = startFakeServer({ 1: () => ACCOUNT_A, 2: () => ACCOUNT_B })
    renderWithProviders(<App />, { route: '/positions' })
    await turnOnGreeks()

    await userEvent.click(screen.getByTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-2'))

    await waitFor(() => {
      expect(screen.getAllByTestId('greeks-summary-row')).toHaveLength(1)
    })
    expect(rowTexts('QRTV')).toEqual({
      optionDelta: '+90.63',
      stockDelta: '+100.00',
      netDelta: '+190.63',
      price: '52.40',
      exposure: '+9,989.01',
      gamma: '−15.36',
      theta: '+13.68',
      vega: '−21.33',
    })
    expect(portfolioTexts()).toEqual({ exposure: '+9,989.01', theta: '+13.68', vega: '−21.33' })
    expect(portfolioNotes()).toEqual([null, null, null])
    expect(server.summaryRequests().map((request) => request.account)).toEqual(['1', '2'])
  })

  it('marks the manual underlying price and follows quote refreshes (AC 5)', async () => {
    let current = ACCOUNT_A
    startFakeServer({ 1: () => current })
    const { queryClient } = renderWithProviders(<PositionsPage />)
    await turnOnGreeks()

    current = ACCOUNT_A_MANUAL
    await refreshQuotes(queryClient)
    await waitFor(() => {
      expect(rowTexts('QRTV').exposure).toBe('+4,534.92')
    })
    expect(rowTexts('QRTV').price).toBe('52.00')
    expect(
      within(rowOf('QRTV')).getByTestId('greeks-summary-underlying-price').parentElement,
    ).toHaveAttribute('data-manual', 'true')
    expect(rowNotes('QRTV')).toEqual(['按手动标的价', '1 个持仓无报价，未计入', null])
    expect(portfolioTexts().exposure).toBe('+9,523.74')

    current = ACCOUNT_A
    await refreshQuotes(queryClient)
    await waitFor(() => {
      expect(rowTexts('QRTV').exposure).toBe('+4,569.80')
    })
    expect(rowNotes('QRTV')).toEqual([null, '1 个持仓无报价，未计入', null])
    expect(
      within(rowOf('QRTV')).getByTestId('greeks-summary-underlying-price').parentElement,
    ).toHaveAttribute('data-manual', 'false')
  })

  it('shows the missing-Greek notes for the row and the portfolio (AC 6)', async () => {
    startFakeServer({ 1: () => ACCOUNT_A_MISSING })
    renderWithProviders(<PositionsPage />)
    await turnOnGreeks()

    expect(rowTexts('QRTV')).toMatchObject({ gamma: '−5.12', vega: '−7.11', exposure: '+4,569.80' })
    expect(rowNotes('QRTV')).toEqual([
      null,
      '1 个持仓无报价，未计入',
      'Gamma、Vega：1 个持仓缺值，未计入',
    ])
    expect(portfolioTexts()).toEqual({ exposure: '+9,558.62', theta: '−0.77', vega: '−12.14' })
    expect(portfolioNotes()).toEqual([
      '1 个持仓无报价，未计入',
      null,
      'Vega：1 个持仓缺值，未计入',
    ])
  })

  it('shows — for missing option parts and 无标的价 without a price (AC 8)', async () => {
    startFakeServer({ 1: () => ACCOUNT_C })
    renderWithProviders(<PositionsPage />)
    await turnOnGreeks()

    expect(rowTexts('IXQZ')).toEqual({
      optionDelta: '—',
      stockDelta: '+100.00',
      netDelta: '+100.00',
      price: '无标的价',
      exposure: '无标的价',
      gamma: '—',
      theta: '—',
      vega: '—',
    })
    expect(
      within(rowOf('IXQZ')).getByTestId('greeks-summary-exposure').parentElement,
    ).toHaveAttribute('data-status', 'NO_UNDERLYING_PRICE')
    expect(portfolioTexts()).toEqual({ exposure: '0.00', theta: '0.00', vega: '0.00' })
    expect(portfolioNotes()).toEqual([null, '1 个标的无标的价，美元敞口未计入', null])
  })

  it('shows — as the exposure of a symbol with only expired-pending options (NO_DELTA)', async () => {
    startFakeServer({
      1: () =>
        summary(
          [
            group({
              optionRowCount: 1,
              expiredPendingCount: 1,
              excludedLabel: '1 个持仓已到期待处理，未计入',
            }),
          ],
          { expiredPendingCount: 1, excludedLabel: '1 个持仓已到期待处理，未计入' },
        ),
    })
    renderWithProviders(<PositionsPage />)
    await turnOnGreeks()

    expect(rowTexts('QRTV')).toEqual({
      optionDelta: '—',
      stockDelta: '0.00',
      netDelta: '—',
      price: '52.40',
      exposure: '—',
      gamma: '—',
      theta: '—',
      vega: '—',
    })
    expect(rowNotes('QRTV')).toEqual([null, '1 个持仓已到期待处理，未计入', null])
  })

  it('reloads after a write and drops symbols without positions (AC 9)', async () => {
    let current = ACCOUNT_A
    startFakeServer({ 1: () => current })
    const { queryClient } = renderWithProviders(<PositionsPage />)
    await turnOnGreeks()

    current = ACCOUNT_A_AFTER_CLOSE
    await refreshAfterChange(queryClient)
    await waitFor(() => {
      expect(screen.getAllByTestId('greeks-summary-row')).toHaveLength(1)
    })
    expect(rowTexts('QRTV')).toMatchObject({ optionDelta: '+30.21', exposure: '+1,583.00' })
    expect(portfolioTexts()).toEqual({ exposure: '+1,583.00', theta: '+4.56', vega: '−7.11' })
  })

  it('is the same under the 今天到期 filter and sends no filter (AC 10)', async () => {
    const server = startFakeServer({ 1: () => ACCOUNT_A })
    renderWithProviders(<PositionsPage />, { route: '/positions?expiry=today' })
    await turnOnGreeks()

    expect(rowTexts('QRTV').exposure).toBe('+4,569.80')
    expect(portfolioTexts()).toEqual({ exposure: '+9,558.62', theta: '−0.77', vega: '+0.86' })
    expect(server.summaryRequests().map((request) => request.url)).toEqual([SUMMARY_URL])
  })

  it('shows 暂无持仓 and a zero 全组合 for an account without positions', async () => {
    startFakeServer({ 1: () => summary([], {}) })
    renderWithProviders(<PositionsPage />)
    await userEvent.click(await screen.findByTestId('positions-greeks-toggle'))

    expect(await screen.findByTestId('greeks-summary-empty')).toHaveTextContent('暂无持仓')
    expect(portfolioTexts()).toEqual({ exposure: '0.00', theta: '0.00', vega: '0.00' })
    expect(portfolioNotes()).toEqual([null, null, null])
  })

  it('shows the error with a retry button only in the block', async () => {
    let fail = true
    startFakeServer({
      1: () =>
        fail
          ? jsonResponse(
              {
                timestamp: '2026-11-23T15:00:00Z',
                status: 500,
                code: 'INTERNAL_ERROR',
                message: '服务器内部错误',
                details: [],
                path: SUMMARY_URL,
              },
              500,
            )
          : ACCOUNT_A,
    })
    renderWithProviders(<PositionsPage />)
    await userEvent.click(await screen.findByTestId('positions-greeks-toggle'))

    expect(await screen.findByTestId('greeks-summary-error')).toHaveTextContent('服务器内部错误')
    expect(screen.getByTestId('positions-empty')).toBeInTheDocument()

    fail = false
    await userEvent.click(screen.getByTestId('greeks-summary-retry'))
    expect(await screen.findAllByTestId('greeks-summary-row')).toHaveLength(2)
  })
})
