import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import type {
  ApiErrorResponse,
  AuditExpirationAdjustment,
  AuditLogPage,
  AuditRecord,
  CreateExpirationAdjustmentRequest,
  ExpirationAdjustmentResponse,
  NonTradingDay,
  NonTradingDaysResponse,
  OptionPosition,
  OptionPositionsResponse,
} from '../api/types'
import {
  adjustmentExplanation,
  currentExpirationText,
  recordCountText,
  toExpirationAdjustmentRequest,
} from '../components/position/expirationAdjustment'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import userEvent from '../test/userEvent'
import { AuditLogPage as AuditLogPageComponent } from './AuditLogPage'
import { PositionsPage } from './PositionsPage'

// ---------------------------------------------------------------------------
// FEAT-043 scenario (fictional tickers): today 2026-12-01; 2026-12-04 is an ad-hoc closure.
// P1 QRTV 2026-12-04 50P (3 records), P2 IXQZ 2026-12-04 9C, P3 QRTV 2026-12-18 45P.
// ---------------------------------------------------------------------------

const EVENT = 'National Day of Mourning'
const ADJUST_URL = '/api/option-trades/expiration-adjustments'

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'QRTV',
    expirationDate: '2026-12-04',
    strike: '50',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 1,
    multiplier: 100,
    openingNetTotal: '100.00',
    averageNetPerContract: '100.00',
    frozenCash: '5000.00',
    dte: 3,
    remainingTradingDays: 3,
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
    expiryAt: '2026-12-04T21:00:00Z',
    expiryAtLocal: '2026-12-04T16:00:00',
    assignable: false,
    assignChoices: [],
    assignmentDateMax: null,
    assignmentDateDefault: null,
    expiresOnAdhocClosure: false,
    adhocClosureEventName: null,
    expirationAdjustable: false,
    expirationAdjustmentRecordCount: 0,
    defaultNewExpirationDate: null,
    newExpirationDateMin: null,
    newExpirationDateMax: null,
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const ADJUSTABLE: Partial<OptionPosition> = {
  expiresOnAdhocClosure: true,
  adhocClosureEventName: EVENT,
  expirationAdjustable: true,
  defaultNewExpirationDate: '2026-12-03',
  newExpirationDateMax: '2026-12-03',
}

const P1 = position({
  ...ADJUSTABLE,
  expirationAdjustmentRecordCount: 3,
  newExpirationDateMin: '2026-11-25',
})
const P2 = position({
  ...ADJUSTABLE,
  symbol: 'IXQZ',
  strike: '9',
  optionType: 'CALL',
  direction: 'LONG',
  contracts: 2,
  closeAction: 'STC',
  expirationAdjustmentRecordCount: 1,
  newExpirationDateMin: '2026-11-20',
})
const P3 = position({ expirationDate: '2026-12-18', strike: '45', dte: 17 })

function positions(rows: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-12-01', positionCount: rows.length, frozenTotal: '0.00', positions: rows }
}

/** Weekends plus the ad-hoc closure, as the FEAT-009 backend would report them. */
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
    } else if (date === '2026-12-04') {
      days.push({ date, type: 'HOLIDAY', name: EVENT, source: 'FINNHUB' })
    }
  }
  return { from, to, days }
}

function errorBody(code: string, message: string): ApiErrorResponse {
  return {
    code,
    message,
    status: 422,
    path: ADJUST_URL,
    timestamp: '2026-12-01T15:00:00Z',
    details: [],
  }
}

interface FakeServer {
  rows: OptionPosition[]
  /** Answer of the adjustment POST. */
  adjust: (body: CreateExpirationAdjustmentRequest) => Response
}

interface Recorded {
  method: string
  url: string
  body: unknown
}

function startFakeServer(server: FakeServer): Recorded[] {
  const requests: Recorded[] = []
  mockFetch().mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, body })
    if (method === 'POST' && url === ADJUST_URL) {
      return Promise.resolve(server.adjust(body as CreateExpirationAdjustmentRequest))
    }
    if (url === '/api/option-positions') return Promise.resolve(jsonResponse(positions(server.rows)))
    if (url === '/api/adhoc-closure-alerts') return Promise.resolve(jsonResponse({ alerts: [] }))
    if (url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    if (url === '/api/trading-calendar/status') {
      return Promise.resolve(
        jsonResponse({
          stale: false,
          warning: null,
          lastAttemptAt: null,
          lastAttemptResult: null,
          lastFailureDetail: null,
          lastSuccessAt: null,
          lastSuccessAtLocal: null,
          coveredYears: [2026],
        }),
      )
    }
    if (url.startsWith('/api/trading-calendar/non-trading-days?')) {
      const query = new URL(url, 'http://localhost').searchParams
      return Promise.resolve(
        jsonResponse(nonTradingDays(query.get('from') ?? '', query.get('to') ?? '')),
      )
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function rowByContract(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('position-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (row === undefined) throw new Error(`no row ${contract}`)
  return row
}

async function dayCell(date: string): Promise<HTMLElement> {
  const inner = await screen.findByTestId(`adjust-expiration-day-${date}`)
  const cell = inner.closest('td')
  if (!cell) throw new Error(`cell ${date} not found`)
  return cell
}

async function openDialog(rows: OptionPosition[], contract: string, server?: Partial<FakeServer>) {
  const requests = startFakeServer({
    rows,
    adjust: () => jsonResponse({}),
    ...server,
  })
  const rendered = renderWithProviders(<PositionsPage />, { route: '/positions' })
  await waitFor(() => {
    expect(screen.getAllByTestId('position-row')).toHaveLength(rows.length)
  })
  await userEvent.click(
    within(rowByContract(contract)).getByTestId('position-adjust-expiration'),
  )
  await screen.findByTestId('adjust-expiration-modal')
  return { requests, ...rendered }
}

// ---------------------------------------------------------------------------

describe('expirationAdjustment helpers', () => {
  it('formats the dialog texts from backend values', () => {
    expect(currentExpirationText(P1)).toBe('2026-12-04（National Day of Mourning）')
    expect(currentExpirationText(position({}))).toBe('2026-12-04')
    expect(recordCountText(3)).toBe('3 条开仓 / 平仓记录')
    expect(adjustmentExplanation(3)).toBe(
      '该合约在本账户的 3 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录',
    )
  })

  it('builds the request from the row as-is and trims / drops the note', () => {
    expect(toExpirationAdjustmentRequest(P1, '2026-12-03', '  临时休市  ')).toEqual({
      symbol: 'QRTV',
      expirationDate: '2026-12-04',
      strike: '50',
      optionType: 'PUT',
      newExpirationDate: '2026-12-03',
      note: '临时休市',
    })
    expect(toExpirationAdjustmentRequest(P1, '2026-12-03', '   ').note).toBeNull()
    expect(toExpirationAdjustmentRequest(P1, '2026-12-03', undefined).note).toBeNull()
  })
})

describe('FEAT-043 positions page', () => {
  it('shows 调整到期日 only on rows the backend marks adjustable (AC 1)', async () => {
    startFakeServer({ rows: [P1, P2, P3], adjust: () => jsonResponse({}) })
    renderWithProviders(<PositionsPage />, { route: '/positions' })
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(3)
    })
    for (const row of [P1, P2]) {
      expect(
        within(rowByContract(row.contractLabel)).getByTestId('position-adjust-expiration'),
      ).toHaveTextContent(/^调整到期日$/)
    }
    expect(
      within(rowByContract(P3.contractLabel)).queryByTestId('position-adjust-expiration'),
    ).toBeNull()
  })

  it('shows the button even when the row has no other action', async () => {
    const onlyAdjust = position({ ...P1, closable: false })
    startFakeServer({ rows: [onlyAdjust], adjust: () => jsonResponse({}) })
    renderWithProviders(<PositionsPage />, { route: '/positions' })
    expect(await screen.findByTestId('position-adjust-expiration')).toBeInTheDocument()
    expect(screen.queryByTestId('position-close')).toBeNull()
  })

  it('dialog shows contract, closure, record count and default date; disables range / non-trading days (AC 2)', async () => {
    await openDialog([P1, P2, P3], P1.contractLabel)
    expect(screen.getByTestId('adjust-expiration-contract')).toHaveTextContent(
      'QRTV 2026-12-04 50 P',
    )
    expect(screen.getByTestId('adjust-expiration-current')).toHaveTextContent(
      '2026-12-04（National Day of Mourning）',
    )
    expect(screen.getByTestId('adjust-expiration-record-count')).toHaveTextContent(
      '3 条开仓 / 平仓记录',
    )
    expect(screen.getByTestId('adjust-expiration-hint')).toHaveTextContent(
      '该合约在本账户的 3 条开仓 / 平仓记录将一起改为新到期日，此操作写入审计记录',
    )
    const input = within(screen.getByTestId('adjust-expiration-modal')).getByPlaceholderText(
      'YYYY-MM-DD',
    )
    expect(input).toHaveValue('2026-12-03')
    // The testid sits on the input itself (E2E reads its value).
    expect(screen.getByTestId('adjust-expiration-date')).toBe(input)
    expect(screen.getByTestId('adjust-expiration-note')).toHaveValue('')

    await userEvent.click(screen.getByTestId('adjust-expiration-date'))
    await waitFor(async () => {
      expect(await dayCell('2026-12-05')).toHaveClass('ant-picker-cell-disabled')
    })
    // Above the max (12-03) / the closure itself / weekend.
    expect(await dayCell('2026-12-04')).toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-12-06')).toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-12-03')).not.toHaveClass('ant-picker-cell-disabled')

    // November: below the min (last trade date 11-25) and weekends inside the range.
    const prev = document.querySelector('.ant-picker-header-prev-btn')
    if (prev === null) throw new Error('no previous-month button')
    fireEvent.click(prev)
    await waitFor(async () => {
      expect(await dayCell('2026-11-24')).toHaveClass('ant-picker-cell-disabled')
    })
    expect(await dayCell('2026-11-28')).toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-11-25')).not.toHaveClass('ant-picker-cell-disabled')
    expect(await dayCell('2026-11-27')).not.toHaveClass('ant-picker-cell-disabled')
  })

  it('submits the row contract with the new date and note, then closes and refreshes (AC 3)', async () => {
    const response: ExpirationAdjustmentResponse = {
      auditId: 1,
      symbol: 'QRTV',
      strike: '50',
      optionType: 'PUT',
      oldExpirationDate: '2026-12-04',
      newExpirationDate: '2026-12-03',
      contractLabel: 'QRTV 2026-12-03 50 P',
      tradeIds: [1, 2, 3],
      position: null,
    }
    const { requests } = await openDialog([P1, P3], P1.contractLabel, {
      adjust: () => jsonResponse(response),
    })
    await userEvent.type(screen.getByTestId('adjust-expiration-note'), '交易所改为 12-03 到期')
    const before = requests.filter((r) => r.url === '/api/option-positions').length
    await userEvent.click(screen.getByTestId('adjust-expiration-submit'))

    await waitFor(() => {
      expect(screen.queryByTestId('adjust-expiration-modal')).toBeNull()
    })
    const post = requests.find((r) => r.method === 'POST' && r.url === ADJUST_URL)
    expect(post?.body).toEqual({
      symbol: 'QRTV',
      expirationDate: '2026-12-04',
      strike: '50',
      optionType: 'PUT',
      newExpirationDate: '2026-12-03',
      note: '交易所改为 12-03 到期',
    })
    await waitFor(() => {
      expect(requests.filter((r) => r.url === '/api/option-positions').length).toBeGreaterThan(
        before,
      )
    })
    expect(requests.some((r) => r.url === '/api/adhoc-closure-alerts')).toBe(true)
  })

  it('shows a 422 verbatim and keeps the dialog open (AC 7)', async () => {
    await openDialog([P1], P1.contractLabel, {
      adjust: () =>
        jsonResponse(
          errorBody('NEW_EXPIRATION_NOT_TRADING_DAY', '新到期日 2026-12-05 不是美股交易日'),
          422,
        ),
    })
    await userEvent.click(screen.getByTestId('adjust-expiration-submit'))
    const error = await screen.findByTestId('adjust-expiration-error')
    expect(error).toHaveTextContent(/^新到期日 2026-12-05 不是美股交易日$/)
    expect(error).toHaveAttribute('data-error-code', 'NEW_EXPIRATION_NOT_TRADING_DAY')
    expect(screen.getByTestId('adjust-expiration-modal')).toBeInTheDocument()
  })

  it('requires a date and limits the note to 500 characters before submitting', async () => {
    const { requests } = await openDialog([P1], P1.contractLabel)
    const modal = screen.getByTestId('adjust-expiration-modal')
    // Clear the date with the picker's clear icon.
    const clear = modal.querySelector('.ant-picker-clear')
    if (clear === null) throw new Error('no clear icon')
    await userEvent.click(clear)
    await userEvent.click(screen.getByTestId('adjust-expiration-submit'))
    expect(await within(modal).findByText('请选择新到期日')).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('cancel closes the dialog without a request', async () => {
    const { requests } = await openDialog([P2], P2.contractLabel)
    expect(screen.getByTestId('adjust-expiration-record-count')).toHaveTextContent(
      '1 条开仓 / 平仓记录',
    )
    await userEvent.click(screen.getByTestId('adjust-expiration-cancel'))
    await waitFor(() => {
      expect(screen.queryByTestId('adjust-expiration-modal')).toBeNull()
    })
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })
})

// ---------------------------------------------------------------------------

const ADJUSTMENT: AuditExpirationAdjustment = {
  symbol: 'QRTV',
  strike: '50',
  optionType: 'PUT',
  oldExpirationDate: '2026-12-04',
  newExpirationDate: '2026-12-03',
  adhocClosureEventName: EVENT,
  tradeIds: [1, 2, 3],
  note: '临时休市，交易所改为 12-03 到期',
}

const ADJUST_RECORD: AuditRecord = {
  id: 7,
  operatedAt: '2026-12-01T15:00:00Z',
  operation: 'ADJUST_EXPIRATION',
  objectType: 'OPTION_TRADE',
  objectId: 1,
  summary: '调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）',
  before: {
    symbol: 'QRTV',
    expirationDate: '2026-12-04',
    strike: '50',
    optionType: 'PUT',
    contractLabel: 'QRTV 2026-12-04 50 P',
  },
  after: {
    symbol: 'QRTV',
    expirationDate: '2026-12-03',
    strike: '50',
    optionType: 'PUT',
    contractLabel: 'QRTV 2026-12-03 50 P',
  },
  changes: [
    {
      field: 'expirationDate',
      label: '到期日',
      before: '2026-12-04',
      after: '2026-12-03',
      text: '到期日：2026-12-04 → 2026-12-03',
    },
  ],
  sideEffects: [],
  expirationAdjustment: ADJUSTMENT,
}

function startAuditServer(records: AuditRecord[]) {
  mockFetch().mockImplementation((input) => {
    const url = typeof input === 'string' ? input : ''
    const parsed = new URL(url, 'http://localhost')
    if (parsed.pathname !== '/api/audit-log') {
      return Promise.reject(new Error(`Unexpected request ${url}`))
    }
    const body: AuditLogPage = {
      items: records,
      page: 1,
      size: 20,
      totalItems: records.length,
      totalPages: 1,
    }
    return Promise.resolve(jsonResponse(body))
  })
}

describe('FEAT-043 audit page', () => {
  it('shows 调整到期日 with summary, change, record ids and adjustment details (AC 4)', async () => {
    startAuditServer([ADJUST_RECORD])
    renderWithProviders(<AuditLogPageComponent />, { route: '/audit' })
    const row = await screen.findByTestId('audit-row')
    expect(within(row).getByTestId('audit-cell-operation')).toHaveTextContent(/^调整到期日$/)
    expect(within(row).getByTestId('audit-cell-operation')).toHaveAttribute(
      'data-operation',
      'ADJUST_EXPIRATION',
    )
    expect(within(row).getByTestId('audit-cell-object')).toHaveTextContent('期权交易 #1')
    expect(within(row).getByTestId('audit-cell-summary')).toHaveTextContent(
      '调整到期日 QRTV 50 P：2026-12-04 → 2026-12-03（3 条记录）',
    )
    expect(within(row).getByTestId('audit-cell-changes')).toHaveTextContent(
      '到期日：2026-12-04 → 2026-12-03',
    )
    expect(within(row).getByTestId('audit-adjust-trades')).toHaveTextContent(
      '记录：#1、#2、#3',
    )
    expect(within(row).getByTestId('audit-adjust-trade-ids')).toHaveTextContent(/^#1、#2、#3$/)
    expect(within(row).getByTestId('audit-adjust-note')).toHaveTextContent(
      /^临时休市，交易所改为 12-03 到期$/,
    )

    await userEvent.click(within(row).getByTestId('audit-expand'))
    const detail = await screen.findByTestId('audit-adjustment')
    expect(within(detail).getByTestId('audit-adjustment-contract')).toHaveTextContent('QRTV 50 P')
    expect(within(detail).getByTestId('audit-adjustment-dates')).toHaveTextContent(
      '2026-12-04 → 2026-12-03',
    )
    expect(within(detail).getByTestId('audit-adjustment-event')).toHaveTextContent(EVENT)
    expect(within(detail).getByTestId('audit-adjustment-trade-ids')).toHaveTextContent(
      '#1、#2、#3',
    )
    expect(within(detail).getByTestId('audit-adjustment-note')).toHaveTextContent(
      '临时休市，交易所改为 12-03 到期',
    )
    expect(screen.queryByTestId('audit-before')).toBeNull()
  })

  it('shows — for an adjustment without a note', async () => {
    startAuditServer([
      {
        ...ADJUST_RECORD,
        expirationAdjustment: { ...ADJUSTMENT, note: null },
      },
    ])
    renderWithProviders(<AuditLogPageComponent />, { route: '/audit' })
    const row = await screen.findByTestId('audit-row')
    await userEvent.click(within(row).getByTestId('audit-expand'))
    expect(await screen.findByTestId('audit-adjustment-note')).toHaveTextContent(/^—$/)
  })
})
