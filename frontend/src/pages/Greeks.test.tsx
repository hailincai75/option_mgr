import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  Account,
  OptionGreeks,
  OptionPosition,
  GreeksSummaryResponse,
  OptionPositionsResponse,
  UnrealizedSummaryResponse,
} from '../api/types'
import { App } from '../App'
import { SHOW_GREEKS_STORAGE_KEY } from '../components/greeks/greeksLabels'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// FEAT-018 scenario (today 2026-11-23, 10:00 fetch OK, quote time 09:45; fictional tickers),
// exactly as the contract examples in docs/api/FEAT-018.md return it.

const NO_GREEKS: OptionGreeks = {
  status: 'OK',
  label: null,
  ivPercent: null,
  deltaPerContract: null,
  deltaPosition: null,
  gammaPerContract: null,
  gammaPosition: null,
  thetaPerContract: null,
  thetaPosition: null,
  vegaPerContract: null,
  vegaPosition: null,
  quoteTime: '2026-11-23T14:45:00Z',
  quoteTimeLocal: '2026-11-23T09:45:00',
  manualMarkNote: null,
}

function greeks(values: Partial<OptionGreeks>): OptionGreeks {
  return { ...NO_GREEKS, ...values }
}

function option(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'QRTV',
    expirationDate: '2026-12-18',
    strike: '50',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '0.00',
    averageNetPerContract: '0.00',
    frozenCash: '0.00',
    dte: 25,
    expirationStatus: 'ACTIVE',
    daysPastExpiration: 0,
    lotCount: 0,
    closeAction: partial.direction === 'LONG' ? 'STC' : 'BTC',
    allowedCloseContracts: [partial.contracts ?? 1],
    closable: true,
    expiryPending: false,
    expirable: false,
    expiryContracts: partial.contracts ?? 1,
    expiryAt: '2026-12-18T21:00:00Z',
    expiryAtLocal: '2026-12-18T16:00:00',
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    lots: [],
    marketValue: '-125.00',
    unrealizedPnl: '-6.30',
    unrealizedPnlPercent: '-5.31',
    unrealizedStatus: 'OK',
    unrealizedLabel: null,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const P1_GREEKS = greeks({
  ivPercent: '35.12',
  deltaPerContract: '-0.3021',
  deltaPosition: '30.21',
  gammaPerContract: '0.0512',
  gammaPosition: '-5.12',
  thetaPerContract: '-0.0456',
  thetaPosition: '4.56',
  vegaPerContract: '0.0711',
  vegaPosition: '-7.11',
})
const P1 = option({ greeks: P1_GREEKS })
const P2 = option({
  strike: '55',
  optionType: 'CALL',
  direction: 'LONG',
  contracts: 2,
  greeks: greeks({
    ivPercent: '33.20',
    deltaPerContract: '0.2850',
    deltaPosition: '57.00',
    gammaPerContract: '0.0480',
    gammaPosition: '9.60',
    thetaPerContract: '-0.0390',
    thetaPosition: '-7.80',
    vegaPerContract: '0.0650',
    vegaPosition: '13.00',
  }),
})
const P3 = option({
  symbol: 'IXQZ',
  strike: '65',
  optionType: 'CALL',
  greeks: greeks({
    ivPercent: '29.00',
    deltaPerContract: '0.1835',
    deltaPosition: '-18.35',
    gammaPerContract: '0.0313',
    gammaPosition: '-3.13',
    thetaPerContract: '-0.0247',
    thetaPosition: '2.47',
    vegaPerContract: '0.0503',
    vegaPosition: '-5.03',
  }),
})
const P4 = option({
  strike: '47.5',
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'NO_QUOTE',
  unrealizedLabel: '无报价',
  greeks: { ...NO_GREEKS, status: 'NO_QUOTE', label: '无报价', quoteTime: null, quoteTimeLocal: null },
})
/** AC 9: expired, not processed (the backend has a last delta but does not show it). */
const P6 = option({
  expirationDate: '2026-11-20',
  strike: '45',
  dte: -3,
  expirationStatus: 'EXPIRED',
  daysPastExpiration: 3,
  closable: false,
  expirable: true,
  marketValue: null,
  unrealizedPnl: null,
  unrealizedPnlPercent: null,
  unrealizedStatus: 'EXPIRED_PENDING',
  unrealizedLabel: '已到期，待处理',
  greeks: { ...NO_GREEKS, status: 'EXPIRED_PENDING', label: '已到期，待处理' },
})
/** AC 9: expires today → shown normally. */
const P7 = option({
  expirationDate: '2026-11-23',
  strike: '52',
  dte: 0,
  expirationStatus: 'EXPIRES_TODAY',
  greeks: greeks({ ivPercent: '40.00', deltaPerContract: '-0.4500', deltaPosition: '45.00' }),
})
/** AC 5: account B, STO 3 QRTV 50P. */
const P5 = option({
  contracts: 3,
  greeks: greeks({
    ivPercent: '35.12',
    deltaPerContract: '-0.3021',
    deltaPosition: '90.63',
    gammaPerContract: '0.0512',
    gammaPosition: '-15.36',
    thetaPerContract: '-0.0456',
    thetaPosition: '13.68',
    vegaPerContract: '0.0711',
    vegaPosition: '-21.33',
  }),
})

const ACCOUNTS: Account[] = [
  { id: 1, name: '账户A', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
  { id: 2, name: '账户B', brokerNote: null, createdAt: '2026-11-12T15:00:00Z' },
]

const SUMMARY: UnrealizedSummaryResponse = {
  asOfDate: '2026-11-23',
  option: { marketValue: '0.00', unrealizedPnl: '0.00', includedCount: 0, noQuoteCount: 0, expiredPendingCount: 0 },
  stock: { marketValue: '0.00', unrealizedPnl: '0.00', includedCount: 0, noQuoteCount: 0, expiredPendingCount: 0 },
  total: { marketValue: '0.00', unrealizedPnl: '0.00', includedCount: 0, noQuoteCount: 0, expiredPendingCount: 0 },
  excludedLabel: null,
}

/** FEAT-019 summary shown once 显示 Greeks is on (contents not asserted here). */
const GREEKS_SUMMARY: GreeksSummaryResponse = {
  asOfDate: '2026-11-23',
  groups: [],
  portfolio: {
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
  },
}

function optionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: positions.length, frozenTotal: '0.00', positions }
}

/** Fake backend keyed by `X-Account-Id`; `positions` is a function so tests can change it. */
function startFakeServer(byAccount: Record<number, () => OptionPosition[]>) {
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const headers = (init?.headers ?? {}) as Record<string, string>
    const positions = byAccount[Number(headers['X-Account-Id'])]
    if (url === '/api/accounts') return Promise.resolve(jsonResponse(ACCOUNTS))
    if (url === '/api/option-positions' && positions !== undefined) {
      return Promise.resolve(jsonResponse(optionsResponse(positions())))
    }
    if (url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    if (url === '/api/positions/unrealized-summary') return Promise.resolve(jsonResponse(SUMMARY))
    if (url === '/api/positions/greeks-summary') return Promise.resolve(jsonResponse(GREEKS_SUMMARY))
    if (url === '/api/adhoc-closure-alerts') return Promise.resolve(jsonResponse({ alerts: [] }))
    return Promise.reject(new Error(`Unexpected request ${url}`))
  })
}

function rowOf(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`row ${contract} not found`)
  return row
}

/** The visible Greeks of a row: IV and, per Greek, `position（perContract）` or the cell text. */
function greeksTexts(contract: string) {
  const row = rowOf(contract)
  const text = (id: string) => within(row).queryByTestId(id)?.textContent ?? null
  const cell = (greek: string) => {
    const position = text(`position-greeks-${greek}-position`)
    const perContract = text(`position-greeks-${greek}-per-contract`)
    return position === null ? text(`position-greeks-${greek}`) : `${position}（${perContract ?? ''}）`
  }
  return {
    iv: text('position-greeks-iv'),
    delta: cell('delta'),
    gamma: cell('gamma'),
    theta: cell('theta'),
    vega: cell('vega'),
  }
}

const headerTexts = () => [...document.querySelectorAll('th')].map((th) => th.textContent)

async function turnOnGreeks() {
  const toggle = await screen.findByTestId('positions-greeks-toggle')
  expect(toggle).toHaveAttribute('aria-checked', 'false')
  await userEvent.click(toggle)
  expect(toggle).toHaveAttribute('aria-checked', 'true')
}

describe('FEAT-018 Greeks on the positions page', () => {
  it('has no Greeks columns or note while 显示 Greeks is off (default, AC 10)', async () => {
    startFakeServer({ 1: () => [P3, P4, P1, P2] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    expect(screen.getByTestId('positions-greeks-toggle')).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('显示 Greeks')).toBeInTheDocument()
    const headers = headerTexts()
    for (const title of ['IV', 'Delta', 'Gamma', 'Theta', 'Vega', 'Rho']) {
      expect(headers.some((header) => header.startsWith(title))).toBe(false)
    }
    expect(screen.queryByTestId('position-greeks-iv')).toBeNull()
    expect(screen.queryByTestId('positions-greeks-note')).toBeNull()
  })

  it('shows IV and position / per-contract Greeks after 未实现盈亏 (AC 1–3)', async () => {
    startFakeServer({ 1: () => [P3, P4, P1, P2] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    const headers = headerTexts()
    const pnl = headers.indexOf('未实现盈亏')
    expect(headers.slice(pnl, pnl + 7)).toEqual([
      '未实现盈亏',
      'IV?',
      'Delta?',
      'Gamma?',
      'Theta?',
      'Vega?',
      '报价时间',
    ])
    expect(headers.some((header) => header.startsWith('Rho'))).toBe(false)

    expect(greeksTexts(P1.contractLabel)).toEqual({
      iv: '35.12%',
      delta: '+30.21（−0.3021）',
      gamma: '−5.12（0.0512）',
      theta: '+4.56（−0.0456）',
      vega: '−7.11（0.0711）',
    })
    expect(greeksTexts(P2.contractLabel)).toEqual({
      iv: '33.20%',
      delta: '+57.00（0.2850）',
      gamma: '+9.60（0.0480）',
      theta: '−7.80（−0.0390）',
      vega: '+13.00（0.0650）',
    })
    expect(greeksTexts(P3.contractLabel)).toEqual({
      iv: '29.00%',
      delta: '−18.35（0.1835）',
      gamma: '−3.13（0.0313）',
      theta: '+2.47（−0.0247）',
      vega: '−5.03（0.0503）',
    })
    expect(within(rowOf(P1.contractLabel)).getByTestId('position-greeks-delta')).toHaveAttribute(
      'data-status',
      'OK',
    )
    expect(screen.getByTestId('positions-greeks-note')).toHaveTextContent(
      'IV / Greeks 来自 CBOE 延迟报价，持仓值 = 每张 × 张数 × 100 × 方向（空头取反），仅供参考',
    )
  })

  it('shows 无报价 in all five columns for a never-quoted contract (AC 4)', async () => {
    startFakeServer({ 1: () => [P3, P4, P1, P2] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    expect(greeksTexts(P4.contractLabel)).toEqual({
      iv: '无报价',
      delta: '无报价',
      gamma: '无报价',
      theta: '无报价',
      vega: '无报价',
    })
    expect(within(rowOf(P4.contractLabel)).getByTestId('position-greeks-iv')).toHaveAttribute(
      'data-status',
      'NO_QUOTE',
    )
  })

  it('shows "—" only for the missing fields (AC 8)', async () => {
    const p2 = option({
      ...P2,
      greeks: { ...(P2.greeks ?? NO_GREEKS), gammaPerContract: null, gammaPosition: null, vegaPerContract: null, vegaPosition: null },
    })
    startFakeServer({ 1: () => [p2] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    expect(greeksTexts(p2.contractLabel)).toEqual({
      iv: '33.20%',
      delta: '+57.00（0.2850）',
      gamma: '—（—）',
      theta: '−7.80（−0.0390）',
      vega: '—（—）',
    })
  })

  it('shows 已到期，待处理 for expired rows and values on the expiration day (AC 9)', async () => {
    startFakeServer({ 1: () => [P6, P7] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    expect(greeksTexts(P6.contractLabel)).toEqual({
      iv: '已到期，待处理',
      delta: '已到期，待处理',
      gamma: '已到期，待处理',
      theta: '已到期，待处理',
      vega: '已到期，待处理',
    })
    expect(greeksTexts(P7.contractLabel).delta).toBe('+45.00（−0.4500）')
  })

  it('shows the manual-mark note under IV and drops it after a reload without it (AC 7)', async () => {
    const note = 'Greeks 按 CBOE 报价（11-23 09:45），未随手动 Mark 调整'
    let p1 = option({ greeks: { ...P1_GREEKS, manualMarkNote: note } })
    startFakeServer({ 1: () => [p1] })
    const { queryClient } = renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    const row = rowOf(P1.contractLabel)
    expect(within(row).getByTestId('position-greeks-manual-note')).toHaveTextContent(note)
    expect(greeksTexts(P1.contractLabel).delta).toBe('+30.21（−0.3021）')
    expect(within(row).getByTestId('position-greeks-iv')).toHaveTextContent(/^35\.12%$/)

    p1 = P1
    await queryClient.invalidateQueries()
    await waitFor(() => {
      expect(screen.queryByTestId('position-greeks-manual-note')).toBeNull()
    })
  })

  it('shows "—" in every Greeks column for an older response without greeks', async () => {
    const old = option({ greeks: undefined })
    startFakeServer({ 1: () => [old] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    expect(greeksTexts(old.contractLabel)).toEqual({
      iv: '—',
      delta: '—',
      gamma: '—',
      theta: '—',
      vega: '—',
    })
  })

  it('explains each column on hover of its ? (AC 10)', async () => {
    startFakeServer({ 1: () => [P1] })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()

    const [help] = screen.getAllByTestId('greeks-header-theta-help')
    if (help === undefined) throw new Error('no help icon')
    await userEvent.hover(help)
    expect(
      await screen.findByText(/持仓值 = 每天大约赚 \/ 亏多少美元/),
    ).toBeInTheDocument()
  })

  it('remembers the switch, also across an account switch (AC 5)', async () => {
    startFakeServer({ 1: () => [P3, P4, P1, P2], 2: () => [P5] })
    renderWithProviders(<App />, { route: '/positions' })
    await screen.findAllByTestId('position-row')
    await turnOnGreeks()
    expect(window.localStorage.getItem(SHOW_GREEKS_STORAGE_KEY)).toBe('true')

    await userEvent.click(screen.getByTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-option-2'))

    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(1)
    })
    expect(screen.getByTestId('positions-greeks-toggle')).toHaveAttribute('aria-checked', 'true')
    expect(greeksTexts(P5.contractLabel)).toEqual({
      iv: '35.12%',
      delta: '+90.63（−0.3021）',
      gamma: '−15.36（0.0512）',
      theta: '+13.68（−0.0456）',
      vega: '−21.33（0.0711）',
    })

    await userEvent.click(screen.getByTestId('positions-greeks-toggle'))
    expect(screen.queryByTestId('position-greeks-iv')).toBeNull()
    expect(window.localStorage.getItem(SHOW_GREEKS_STORAGE_KEY)).toBeNull()
  })
})
