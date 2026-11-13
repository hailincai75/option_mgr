import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  ApiErrorResponse,
  AuditRecord,
  CashLedgerEntry,
  ClosedOptionRecord,
  CoveredLot,
  OptionPosition,
  OptionPositionsResponse,
  OptionTrade,
  OptionTradePage,
} from '../api/types'
import { AuditTable } from '../components/audit/AuditTable'
import { CashLedgerTable } from '../components/cash/CashLedgerTable'
import { ClosedTradeTable } from '../components/closed/ClosedTradeTable'
import { CLOSE_ACTIONS } from '../components/closed/closedListFilters'
import { StockTradeTable } from '../components/stock/StockTradeTable'
import { TradeFilterForm } from '../components/trade/TradeFilterForm'
import { EMPTY_TRADE_FILTERS } from '../components/trade/tradeListFilters'
import { TradeTable } from '../components/trade/TradeTable'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { stockTrade } from '../test/stockFixtures'
import { PositionsPage } from './PositionsPage'
import { TradeListPage } from './TradeListPage'

// FEAT-013 卖出 put 被指派. Fictional tickers (XYZ, QRTV, IXQZ) from the requirement's scenario P;
// every value below is what the backend would return (the frontend calculates nothing).

const noop = () => undefined

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
    assignable: true,
    assignChoices: [
      { contracts: 2, shares: 200 },
      { contracts: 3, shares: 300 },
    ],
    assignmentDateMax: '2026-11-20',
    assignmentDateDefault: '2026-11-20',
    ...partial,
  }
  const cp = merged.optionType === 'CALL' ? 'C' : 'P'
  return {
    ...merged,
    contractLabel: `${merged.symbol} ${merged.expirationDate} ${merged.strike} ${cp}`,
  }
}

const NOT_ASSIGNABLE = {
  assignable: false,
  assignChoices: [],
  assignmentDateMax: null,
  assignmentDateDefault: null,
}

const XYZ_45_P = position({})

function positionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return { asOfDate: '2026-11-23', positionCount: positions.length, frozenTotal: '0.00', positions }
}

const P1_LOT: CoveredLot = {
  tradeId: 1,
  action: 'STO',
  tradedAt: '2026-11-03T15:00:00Z',
  tradedAtLocal: '2026-11-03T10:00:00',
  contracts: 2,
  netAmount: '240.00',
  frozen: '9000.00',
}
const P2_LOT: CoveredLot = {
  tradeId: 2,
  action: 'STO',
  tradedAt: '2026-11-05T15:00:00Z',
  tradedAtLocal: '2026-11-05T10:00:00',
  contracts: 1,
  netAmount: '130.00',
  frozen: '4500.00',
}

/** Contract example: AC 2 (3 contracts assigned on 2026-11-20, fee 0.00). */
const ASSIGNMENT: OptionTrade = {
  id: 6,
  symbol: 'XYZ',
  expirationDate: '2026-11-20',
  strike: '45',
  optionType: 'PUT',
  action: 'ASG',
  contracts: 3,
  multiplier: 100,
  netAmount: '0.00',
  tradedAt: '2026-11-20T21:00:00Z',
  tradedAtLocal: '2026-11-20T16:00:00',
  cashChange: '-13500.00',
  frozenChange: '-13500.00',
  contractLabel: 'XYZ 2026-11-20 45 P',
  note: null,
  ledgerEntryId: 7,
  createdAt: '2026-11-23T15:00:00Z',
  coveredLots: [P1_LOT, P2_LOT],
  realizedPnl: '0.00',
  hasAuditLog: false,
  expiredDirection: null,
  assignment: {
    assignmentDate: '2026-11-20',
    assignmentFee: '0.00',
    premium: '370.00',
    shares: 300,
    purchaseAmount: '13500.00',
    stockCost: '13130.00',
    stockCostPerShare: '43.7667',
    stockTradeId: 1,
  },
}

/** AC 4: early assignment of 2 contracts with a 5.00 fee. */
const EARLY_ASSIGNMENT: OptionTrade = {
  ...ASSIGNMENT,
  contracts: 2,
  tradedAt: '2026-11-18T21:00:00Z',
  tradedAtLocal: '2026-11-18T16:00:00',
  cashChange: '-9005.00',
  frozenChange: '-9000.00',
  coveredLots: [P1_LOT],
  assignment: {
    assignmentDate: '2026-11-18',
    assignmentFee: '5.00',
    premium: '240.00',
    shares: 200,
    purchaseAmount: '9000.00',
    stockCost: '8765.00',
    stockCostPerShare: '43.8250',
    stockTradeId: 1,
  },
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

/** Fake backend: the first handler that returns a Response answers. */
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
    if (method === 'GET' && url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
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

async function openAssignDialog(positions: OptionPosition[], post: Handler): Promise<Recorded[]> {
  const requests = startFakeServer(({ method, url }) => {
    if (method === 'GET' && url === '/api/option-positions') {
      return jsonResponse(positionsResponse(positions))
    }
    return undefined
  }, post)
  renderWithProviders(<PositionsPage />)
  await screen.findAllByTestId('position-row')
  await userEvent.click(within(rowOf('XYZ 2026-11-20 45 P')).getByTestId('position-assign'))
  await screen.findByTestId('assign-modal')
  return requests
}

const postsTo = (requests: Recorded[], url: string) =>
  requests.filter((r) => r.method === 'POST' && r.url === url)

// ---------------------------------------------------------------------------

describe('FEAT-013 positions page 被指派 button (AC 1, Q95)', () => {
  it('shows 被指派 by `assignable` next to 平仓 / 作废, and alone while 到期待确认', async () => {
    const active = position({
      symbol: 'AAA',
      dte: 4,
      expirationStatus: 'ACTIVE',
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
    const longCall = position({
      symbol: 'QRTV',
      strike: '25',
      optionType: 'CALL',
      direction: 'LONG',
      ...NOT_ASSIGNABLE,
    })
    const shortCallPending = position({
      symbol: 'IXQZ',
      strike: '8',
      optionType: 'CALL',
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY',
      daysPastExpiration: 0,
      expiryPending: true,
      expirable: false,
      ...NOT_ASSIGNABLE,
    })
    startFakeServer(({ url }) =>
      url === '/api/option-positions'
        ? jsonResponse(positionsResponse([active, pending, XYZ_45_P, longCall, shortCallPending]))
        : undefined,
    )
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')

    // Plain DOM query: role queries walk the whole antd tree and are slow in jsdom.
    const buttons = (contract: string) =>
      Array.from(rowOf(contract).querySelectorAll('button'))
        .map((button) => button.getAttribute('data-testid'))
        .filter((id) => id !== 'position-expand')

    expect(buttons('AAA 2026-11-20 45 P')).toEqual(['position-close', 'position-assign'])
    expect(buttons('BBB 2026-11-20 45 P')).toEqual(['position-assign'])
    expect(within(rowOf('BBB 2026-11-20 45 P')).getByTestId('position-expiry-pending')).toBeVisible()
    expect(buttons('XYZ 2026-11-20 45 P')).toEqual(['position-expire', 'position-assign'])
    expect(buttons('QRTV 2026-11-20 25 C')).toEqual(['position-expire'])
    expect(buttons('IXQZ 2026-11-20 8 C')).toEqual([])
    expect(within(rowOf('XYZ 2026-11-20 45 P')).getByTestId('position-assign')).toHaveTextContent(
      '被指派',
    )
  })
})

describe('FEAT-013 记录被指派 dialog', () => {
  it('prefills the backend choices / dates and shows the backend result (AC 1, 2)', async () => {
    let assigned = false
    const requests = startFakeServer(({ method, url }) => {
      if (method === 'GET' && url === '/api/option-positions') {
        return jsonResponse(positionsResponse(assigned ? [] : [XYZ_45_P]))
      }
      if (method === 'POST' && url === '/api/option-trades/assignments') {
        assigned = true
        return jsonResponse(ASSIGNMENT, 201)
      }
      return undefined
    })
    renderWithProviders(<PositionsPage />)
    await screen.findAllByTestId('position-row')
    await userEvent.click(within(rowOf('XYZ 2026-11-20 45 P')).getByTestId('position-assign'))
    const modal = await screen.findByTestId('assign-modal')

    expect(within(modal).getByTestId('assign-contract')).toHaveTextContent('XYZ 2026-11-20 45 P')
    expect(within(modal).getByTestId('assign-direction')).toHaveTextContent('空头')
    expect(within(modal).getByTestId('assign-strike')).toHaveTextContent('45')
    expect(within(modal).getByTestId('assign-multiplier')).toHaveTextContent('100')
    expect(within(modal).getByTestId('assign-position-contracts')).toHaveTextContent('3 张')
    expect(within(modal).getByTestId('assign-frozen')).toHaveTextContent('13,500.00')
    // Default = the last (largest) choice.
    expect(within(modal).getByTestId('assign-contracts')).toHaveTextContent('3 张（300 股）')
    const date = within(modal).getByTestId('assign-date')
    expect(date).toHaveValue('2026-11-20')
    expect(date).toHaveAttribute('max', '2026-11-20')
    expect(within(modal).getByTestId('assign-fee')).toHaveValue('0.00')
    expect(within(modal).getByTestId('assign-explanation')).toHaveTextContent(
      '期权一侧已实现盈亏记 0.00',
    )

    // Both choices are offered with their share counts.
    fireEvent.mouseDown(within(modal).getByRole('combobox'))
    const option2 = (await screen.findAllByTestId('assign-contracts-option-2')).at(-1)
    expect(option2).toHaveTextContent('2 张（200 股）')
    expect(screen.getAllByTestId('assign-contracts-option-3').length).toBeGreaterThan(0)
    await userEvent.click(screen.getAllByTestId('assign-contracts-option-3').at(-1) as HTMLElement)

    await userEvent.click(within(modal).getByTestId('assign-submit'))

    expect(await within(modal).findByTestId('assign-result')).toBeInTheDocument()
    const text = (id: string) => within(modal).getByTestId(id).textContent
    expect(text('assign-result-time')).toBe('2026-11-20 16:00（美东）')
    expect(text('assign-result-contracts')).toBe('3 张')
    expect(text('assign-result-premium')).toBe('370.00')
    expect(text('assign-result-shares')).toBe('300 股 XYZ')
    expect(text('assign-result-purchase')).toBe('13,500.00')
    expect(text('assign-result-fee')).toBe('0.00')
    expect(text('assign-result-stock-cost')).toBe('13,130.00')
    expect(text('assign-result-cost-per-share')).toBe('43.7667')
    expect(text('assign-result-cash-change')).toBe('−13,500.00')
    expect(text('assign-result-frozen-change')).toBe('−13,500.00')
    expect(text('assign-result-pnl')).toBe('0.00')
    expect(
      within(modal)
        .getAllByTestId('covered-lot-row')
        .map((row) => row.textContent),
    ).toEqual([
      '2026-11-03 10:00STO（卖出开仓）2 张+240.00',
      '2026-11-05 10:00STO（卖出开仓）1 张+130.00',
    ])

    expect(postsTo(requests, '/api/option-trades/assignments')[0]?.body).toEqual({
      symbol: 'XYZ',
      expirationDate: '2026-11-20',
      strike: '45',
      optionType: 'PUT',
      contracts: 3,
      assignmentDate: '2026-11-20',
      assignmentFee: '0.00',
      note: null,
    })

    // Positions (options and stocks) refresh without reload.
    await waitFor(() => {
      expect(screen.queryAllByTestId('position-row')).toHaveLength(0)
    })
    expect(
      requests.filter((r) => r.method === 'GET' && r.url === '/api/stock-positions').length,
    ).toBeGreaterThanOrEqual(2)

    await userEvent.click(within(modal).getByTestId('assign-done'))
    await waitFor(() => {
      expect(screen.queryByTestId('assign-modal')).not.toBeInTheDocument()
    })
  })

  it('sends a chosen count, an earlier date, a fee and a note (AC 4)', async () => {
    const requests = await openAssignDialog(
      [position({ assignmentDateMax: '2026-11-18', assignmentDateDefault: '2026-11-18' })],
      ({ method, url }) =>
        method === 'POST' && url === '/api/option-trades/assignments'
          ? jsonResponse(EARLY_ASSIGNMENT, 201)
          : undefined,
    )
    const modal = screen.getByTestId('assign-modal')
    expect(within(modal).getByTestId('assign-date')).toHaveValue('2026-11-18')

    fireEvent.mouseDown(within(modal).getByRole('combobox'))
    await userEvent.click(
      (await screen.findAllByTestId('assign-contracts-option-2')).at(-1) as HTMLElement,
    )
    const fee = within(modal).getByTestId('assign-fee')
    await userEvent.clear(fee)
    await userEvent.type(fee, '5')
    await userEvent.type(within(modal).getByTestId('assign-note'), '提前指派')
    await userEvent.click(within(modal).getByTestId('assign-submit'))

    await within(modal).findByTestId('assign-result')
    expect(postsTo(requests, '/api/option-trades/assignments')[0]?.body).toEqual({
      symbol: 'XYZ',
      expirationDate: '2026-11-20',
      strike: '45',
      optionType: 'PUT',
      contracts: 2,
      assignmentDate: '2026-11-18',
      assignmentFee: '5',
      note: '提前指派',
    })
    expect(within(modal).getByTestId('assign-result-time')).toHaveTextContent(
      '2026-11-18 16:00（美东）',
    )
    expect(within(modal).getByTestId('assign-result-fee')).toHaveTextContent('5.00')
    expect(within(modal).getByTestId('assign-result-stock-cost')).toHaveTextContent('8,765.00')
    expect(within(modal).getByTestId('assign-result-cost-per-share')).toHaveTextContent('43.8250')
    expect(within(modal).getByTestId('assign-result-cash-change')).toHaveTextContent('−9,005.00')
    expect(within(modal).getByTestId('assign-result-frozen-change')).toHaveTextContent('−9,000.00')
  })

  it.each([
    ['ASSIGNMENT_DATE_INVALID', '指派日期为今天时，美东 16:00 之后才能记录'],
    ['CLOSE_NOT_WHOLE_LOTS', '必须一次被指派整笔开仓：最早一笔是 2 张，可为 2 张或 3 张'],
    ['CASH_INSUFFICIENT', '可用现金不足：需要 5.00；2026-11-20 16:00 时可用现金将变为 −5.00'],
  ])('shows a %s rejection verbatim and keeps the values (AC 4, 8)', async (code, message) => {
    await openAssignDialog([XYZ_45_P], ({ method, url }) =>
      method === 'POST' && url === '/api/option-trades/assignments'
        ? jsonResponse(errorBody(code, 422, message, url), 422)
        : undefined,
    )
    const modal = screen.getByTestId('assign-modal')
    const fee = within(modal).getByTestId('assign-fee')
    await userEvent.clear(fee)
    await userEvent.type(fee, '5.00')
    await userEvent.click(within(modal).getByTestId('assign-submit'))

    const error = await within(modal).findByTestId('assign-error')
    expect(error).toHaveTextContent(message)
    expect(error).toHaveAttribute('data-error-code', code)
    expect(within(modal).queryByTestId('assign-result')).not.toBeInTheDocument()
    expect(within(modal).getByTestId('assign-fee')).toHaveValue('5.00')
    expect(within(modal).getByTestId('assign-date')).toHaveValue('2026-11-20')
  })

  it('checks formats before sending and puts backend field errors under the fields (AC 10)', async () => {
    const requests = await openAssignDialog([XYZ_45_P], ({ method, url }) =>
      method === 'POST' && url === '/api/option-trades/assignments'
        ? jsonResponse(
            errorBody('VALIDATION_ERROR', 400, 'Request validation failed', url, [
              { field: 'contracts', message: '张数必须是正整数' },
            ]),
            400,
          )
        : undefined,
    )
    const modal = screen.getByTestId('assign-modal')
    const fee = within(modal).getByTestId('assign-fee')

    for (const [input, message] of [
      ['−1.00', '指派费不能为负数'],
      ['1.234', '指派费最多 2 位小数'],
      ['10,000,000,000,000.00', '指派费过大'],
    ] as const) {
      await userEvent.clear(fee)
      await userEvent.type(fee, input)
      await userEvent.click(within(modal).getByTestId('assign-submit'))
      expect(await within(modal).findByText(message)).toBeInTheDocument()
    }
    await userEvent.clear(fee)
    fireEvent.change(within(modal).getByTestId('assign-date'), { target: { value: '' } })
    fireEvent.change(within(modal).getByTestId('assign-note'), {
      target: { value: 'x'.repeat(501) },
    })
    await userEvent.click(within(modal).getByTestId('assign-submit'))
    expect(await within(modal).findByText('指派日期不能为空')).toBeInTheDocument()
    expect(await within(modal).findByText('备注最多 500 个字符')).toBeInTheDocument()
    expect(postsTo(requests, '/api/option-trades/assignments')).toHaveLength(0)

    // Valid again (empty fee = 0.00 → sent as null): the backend's field error goes under 张数.
    fireEvent.change(within(modal).getByTestId('assign-date'), { target: { value: '2026-11-20' } })
    fireEvent.change(within(modal).getByTestId('assign-note'), { target: { value: '' } })
    await userEvent.click(within(modal).getByTestId('assign-submit'))
    expect(await within(modal).findByText('张数必须是正整数')).toBeInTheDocument()
    expect(within(modal).queryByTestId('assign-error')).not.toBeInTheDocument()
    expect(postsTo(requests, '/api/option-trades/assignments')[0]?.body).toMatchObject({
      assignmentFee: null,
      note: null,
    })
  })
})

describe('FEAT-013 trade list', () => {
  const STO = { ...ASSIGNMENT, id: 2, action: 'STO', assignment: null, coveredLots: null }

  it('shows an ASG row: 被指派 tag, net 0.00 uncolored, small text, delete only (AC 2)', () => {
    renderWithProviders(
      <TradeTable
        trades={[ASSIGNMENT, { ...EARLY_ASSIGNMENT, id: 8 }, STO]}
        loading={false}
        empty={null}
        onContractClick={noop}
        onEdit={noop}
        onDelete={noop}
      />,
    )
    const [row, early, open] = screen.getAllByTestId('trade-row') as [
      HTMLElement,
      HTMLElement,
      HTMLElement,
    ]
    const cell = (id: string) => within(row).getByTestId(id)
    expect(cell('trade-cell-time')).toHaveTextContent('2026-11-20 16:00')
    expect(cell('trade-cell-action')).toHaveTextContent('被指派')
    expect(cell('trade-cell-contract')).toHaveTextContent('XYZ 2026-11-20 45 P')
    expect(cell('trade-cell-contracts')).toHaveTextContent('3')
    expect(cell('trade-cell-net-amount')).toHaveTextContent(/^0\.00$/)
    expect(cell('trade-cell-net-amount').style.color).toBe('')
    expect(cell('trade-cell-frozen')).toHaveTextContent('−13,500.00')
    expect(cell('trade-cell-assignment')).toHaveTextContent('买入 300 股 XYZ，现金 −13,500.00')
    expect(within(row).queryByTestId('trade-edit')).not.toBeInTheDocument()
    expect(within(row).getByTestId('trade-delete')).toBeInTheDocument()
    expect(within(row).queryByTestId('trade-history')).not.toBeInTheDocument()

    expect(within(early).getByTestId('trade-cell-assignment')).toHaveTextContent(
      '买入 200 股 XYZ，现金 −9,005.00，指派费 5.00',
    )
    expect(within(open).queryByTestId('trade-cell-assignment')).not.toBeInTheDocument()
    expect(within(open).getByTestId('trade-edit')).toBeInTheDocument()
  })

  it('offers the 被指派 filter and explains what deleting an ASG removes (AC 2, 6)', async () => {
    const requests = startFakeServer(({ method, url }) => {
      if (method === 'GET' && url.startsWith('/api/option-trades?')) {
        const page: OptionTradePage = {
          items: [ASSIGNMENT],
          page: 1,
          size: 20,
          totalItems: 1,
          totalPages: 1,
        }
        return jsonResponse(page)
      }
      if (method === 'DELETE' && url === '/api/option-trades/6') {
        return jsonResponse(
          errorBody(
            'SHARES_INSUFFICIENT',
            422,
            '删除后 2026-11-20 16:00 的卖出 XYZ 100 股将超过当时持股 0 股',
            url,
          ),
          422,
        )
      }
      return undefined
    })
    renderWithProviders(<TradeListPage />, { route: '/trades?action=ASG' })
    const row = await screen.findByTestId('trade-row')
    expect(screen.getByTestId('trade-filter-action-asg').closest('label')).toHaveTextContent(
      '被指派',
    )
    expect(screen.getByTestId('trade-filter-action-asg')).toBeChecked()
    expect(
      requests.some((r) => r.method === 'GET' && r.url.includes('action=ASG')),
    ).toBe(true)

    await userEvent.click(within(row).getByTestId('trade-delete'))
    const modal = await screen.findByTestId('trade-delete-modal')
    expect(screen.getByText('删除被指派 #6')).toBeInTheDocument()
    expect(within(modal).getByTestId('trade-delete-summary')).toHaveTextContent(
      '被指派 3 张 XYZ 2026-11-20 45 P，2026-11-20 16:00，买入 300 股 XYZ',
    )
    expect(within(modal).getByTestId('trade-delete-warning')).toHaveTextContent(
      '删除后期权持仓、冻结、现金恢复，生成的股票记录一起删除，会写入审计记录。',
    )
    await userEvent.click(within(modal).getByTestId('trade-delete-confirm'))
    expect(await within(modal).findByTestId('trade-delete-error')).toHaveTextContent(
      '删除后 2026-11-20 16:00 的卖出 XYZ 100 股将超过当时持股 0 股',
    )
    expect(screen.getByTestId('trade-delete-modal')).toBeInTheDocument()
  })
})

describe('FEAT-013 closed trades', () => {
  const CLOSED_ASG: ClosedOptionRecord = {
    type: 'OPTION',
    id: 6,
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    symbol: 'XYZ',
    expirationDate: '2026-11-20',
    strike: '45',
    optionType: 'PUT',
    contractLabel: 'XYZ 2026-11-20 45 P',
    action: 'ASG',
    closedDirection: 'SHORT',
    contracts: 3,
    multiplier: 100,
    shares: null,
    openingNetTotal: '370.00',
    openingNetPerContract: '123.33',
    costPerShare: null,
    closeNetAmount: '0.00',
    closeNetPerContract: '0.00',
    pricePerShare: null,
    realizedPnl: '0.00',
    note: null,
    coveredLots: [P1_LOT, P2_LOT],
    soldLots: null,
    assignment: { shares: 300, stockCost: '13130.00', stockTradeId: 1 },
    hasAuditLog: false,
  }

  it('shows the ASG row with P&L 0.00 and the stock cost note and link (AC 2)', () => {
    renderWithProviders(<ClosedTradeTable trades={[CLOSED_ASG]} loading={false} empty={null} />)
    const row = screen.getByTestId('closed-row')
    const cell = (id: string) => within(row).getByTestId(id)
    expect(cell('closed-cell-type')).toHaveTextContent('期权')
    expect(cell('closed-cell-action')).toHaveTextContent('被指派')
    expect(cell('closed-cell-direction')).toHaveTextContent('空头')
    expect(cell('closed-cell-contracts')).toHaveTextContent('3')
    expect(cell('closed-cell-opening-net')).toHaveTextContent('+370.00')
    expect(cell('closed-cell-opening-net-per')).toHaveTextContent('每张 +123.33')
    expect(cell('closed-cell-close-net')).toHaveTextContent('0.00')
    expect(cell('closed-cell-pnl')).toHaveTextContent(/^0\.00$/)
    expect(cell('closed-cell-pnl').style.color).toBe('')
    expect(cell('closed-cell-assignment')).toHaveTextContent(
      '权利金已计入股票成本（300 股 XYZ，成本 13,130.00）',
    )
    const link = cell('closed-assignment-stock-link')
    expect(link).toHaveTextContent('股票记录 #1')
    expect(link).toHaveAttribute(
      'href',
      '/stocks?symbol=XYZ&type=ASSIGN_BUY&startDate=2026-11-20&endDate=2026-11-20',
    )
    expect(within(row).queryByTestId('closed-history')).not.toBeInTheDocument()
  })

  it('offers 被指派 in the closed-trade direction filter', () => {
    renderWithProviders(
      <TradeFilterForm
        initialValues={EMPTY_TRADE_FILTERS}
        errors={{}}
        onApply={noop}
        onClear={noop}
        actions={CLOSE_ACTIONS}
        testIdPrefix="closed-filter"
      />,
    )
    expect(screen.getByTestId('closed-filter-action-asg').closest('label')).toHaveTextContent(
      '被指派',
    )
  })
})

describe('FEAT-013 股票 page', () => {
  const ASSIGN_BUY = stockTrade({
    id: 1,
    type: 'ASSIGN_BUY',
    symbol: 'XYZ',
    shares: 300,
    amount: '13130.00',
    cashChange: '0.00',
    pricePerShare: '43.7667',
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    ledgerEntryId: null,
    assignmentTradeId: 6,
    editable: false,
    deletable: false,
  })

  it('shows 指派买入 with its cost, a link to the assignment and no 修改 / 删除 / 历史 (AC 2)', () => {
    renderWithProviders(
      <StockTradeTable
        trades={[ASSIGN_BUY, stockTrade({ id: 2, symbol: 'XYZ', amount: '100.00' })]}
        loading={false}
        empty={null}
        onSymbolClick={noop}
        onEdit={noop}
        onDelete={noop}
      />,
    )
    const [row, buy] = screen.getAllByTestId('stock-row') as [HTMLElement, HTMLElement]
    const cell = (id: string) => within(row).getByTestId(id)
    expect(cell('stock-cell-time')).toHaveTextContent('2026-11-20 16:00')
    expect(cell('stock-cell-type')).toHaveTextContent('指派买入')
    expect(cell('stock-cell-shares')).toHaveTextContent('300')
    expect(cell('stock-cell-amount')).toHaveTextContent('成本 13,130.00')
    expect(cell('stock-cell-amount').style.color).toBe('')
    expect(cell('stock-cell-price')).toHaveTextContent('每股 43.7667')
    expect(cell('stock-cell-pnl')).toHaveTextContent('—')
    const link = cell('stock-assignment-link')
    expect(link).toHaveTextContent('来自被指派 #6')
    expect(link).toHaveAttribute(
      'href',
      '/trades?symbol=XYZ&startDate=2026-11-20&endDate=2026-11-20&action=ASG',
    )
    expect(within(row).queryByTestId('stock-edit')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('stock-delete')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('stock-history')).not.toBeInTheDocument()
    expect(cell('stock-assign-buy-hint')).toHaveTextContent('由被指派生成，在交易列表删除')

    expect(within(buy).getByTestId('stock-edit')).toBeInTheDocument()
    expect(within(buy).getByTestId('stock-delete')).toBeInTheDocument()
    expect(within(buy).queryByTestId('stock-assignment-link')).not.toBeInTheDocument()
  })
})

describe('FEAT-013 cash ledger and audit', () => {
  it('labels OPTION_ASSIGN rows 被指派 with a link to the trade list', async () => {
    const entry: CashLedgerEntry = {
      id: 7,
      occurredAt: '2026-11-20T21:00:00Z',
      sourceType: 'OPTION_ASSIGN',
      sourceId: 6,
      amount: '-13500.00',
      balanceAfter: '6937.00',
      frozenChange: '-13500.00',
      description: '被指派 3 张 XYZ 2026-11-20 45 P，买入 300 股 XYZ',
      remark: null,
      hasAuditLog: false,
    }
    startFakeServer(({ url }) =>
      url.startsWith('/api/cash/ledger')
        ? jsonResponse({ items: [entry], page: 1, size: 20, totalItems: 1, totalPages: 1 })
        : undefined,
    )
    renderWithProviders(<CashLedgerTable page={1} onPageChange={noop} />)
    const row = await screen.findByTestId('cash-ledger-row')
    expect(within(row).getByTestId('cash-ledger-type')).toHaveTextContent('被指派')
    expect(within(row).queryByTestId('cash-ledger-edit')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('cash-ledger-delete')).not.toBeInTheDocument()
    expect(within(row).getByTestId('cash-ledger-trades-link')).toBeInTheDocument()
  })

  it('shows the assignment fields of a deleted ASG (AC 6)', async () => {
    const record: AuditRecord = {
      id: 9,
      operatedAt: '2026-11-23T15:10:00Z',
      operation: 'DELETE',
      objectType: 'OPTION_TRADE',
      objectId: 6,
      summary: '被指派 XYZ 2026-11-20 45 P 3 张（买入 300 股 XYZ）',
      before: {
        id: 6,
        symbol: 'XYZ',
        expirationDate: '2026-11-20',
        strike: '45',
        optionType: 'PUT',
        contractLabel: 'XYZ 2026-11-20 45 P',
        action: 'ASG',
        contracts: 3,
        multiplier: 100,
        netAmount: '0.00',
        tradedAt: '2026-11-20T21:00:00Z',
        tradedAtLocal: '2026-11-20T16:00:00',
        note: null,
        cashChange: '-13500.00',
        frozenChange: '-13500.00',
        assignmentDate: '2026-11-20',
        assignmentFee: '0.00',
        shares: 300,
        purchaseAmount: '13500.00',
        stockCost: '13130.00',
        stockTradeId: 1,
      },
      after: null,
      changes: [],
      sideEffects: [],
    }
    renderWithProviders(<AuditTable records={[record]} loading={false} empty={null} />)
    await userEvent.click(screen.getByTestId('audit-expand'))
    const detail = await screen.findByTestId('audit-detail')
    const value = (key: string) => within(detail).getByTestId(`audit-before-${key}`).textContent
    expect(value('action')).toBe('被指派')
    expect(value('contracts')).toBe('3')
    expect(value('multiplier')).toBe('100')
    expect(value('assignmentDate')).toBe('2026-11-20')
    expect(value('tradedAtLocal')).toBe('2026-11-20 16:00')
    expect(value('assignmentFee')).toBe('0.00')
    expect(value('netAmount')).toBe('0.00')
    expect(value('shares')).toBe('300')
    expect(value('purchaseAmount')).toBe('13,500.00')
    expect(value('stockCost')).toBe('13,130.00')
    expect(value('stockTradeId')).toBe('1')
    expect(value('cashChange')).toBe('−13,500.00')
    expect(value('frozenChange')).toBe('−13,500.00')
  })
})
