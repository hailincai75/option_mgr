import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '../test/userEvent'
import type {
  ApiErrorResponse,
  ExpirationBatchRejectedResponse,
  ExpirationBatchResponse,
  OptionPosition,
  OptionPositionsResponse,
  OptionTrade,
} from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { PositionsPage } from './PositionsPage'

// FEAT-015 批量作废. Fictional tickers (XYZ, QRTV, IXQZ) from the requirement's scenario S; every
// value below is what the backend would return (the frontend calculates nothing).

const BATCH_URL = '/api/option-trades/expirations/batch'
const byTestId = (id: string) => screen.getByTestId(id)

function position(partial: Partial<OptionPosition>): OptionPosition {
  const merged: OptionPosition = {
    symbol: 'XYZ',
    expirationDate: '2026-11-20',
    strike: '45',
    optionType: 'PUT',
    contractLabel: '',
    direction: 'SHORT',
    contracts: 2,
    multiplier: 100,
    openingNetTotal: '240.00',
    averageNetPerContract: '120.00',
    frozenCash: '9000.00',
    dte: -3,
    expirationStatus: 'EXPIRED',
    daysPastExpiration: 3,
    lotCount: 1,
    lots: [],
    closeAction: 'BTC',
    allowedCloseContracts: [2],
    closable: false,
    expiryPending: false,
    expirable: true,
    expiryContracts: 2,
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

// Table order (Q35): IXQZ 9 C, QRTV 30 C, XYZ 42 P, XYZ 45 P; then XYZ 2026-11-27 40 P (DTE 4).
const IXQZ_9_C = position({
  symbol: 'IXQZ',
  strike: '9',
  optionType: 'CALL',
  contracts: 1,
  openingNetTotal: '25.00',
  frozenCash: '0.00',
  expiryContracts: 1,
})
const QRTV_30_C = position({
  symbol: 'QRTV',
  strike: '30',
  optionType: 'CALL',
  direction: 'LONG',
  contracts: 1,
  openingNetTotal: '-60.00',
  frozenCash: '0.00',
  closeAction: 'STC',
  expiryContracts: 1,
})
const XYZ_42_P = position({
  strike: '42',
  contracts: 1,
  openingNetTotal: '80.00',
  frozenCash: '4200.00',
  expiryContracts: 1,
})
const XYZ_45_P = position({})
const XYZ_40_P_NEXT_WEEK = position({
  expirationDate: '2026-11-27',
  strike: '40',
  contracts: 1,
  openingNetTotal: '70.00',
  frozenCash: '4000.00',
  dte: 4,
  expirationStatus: 'ACTIVE',
  daysPastExpiration: 0,
  closable: true,
  expirable: false,
  expiryContracts: 1,
  expiryAt: '2026-11-27T21:00:00Z',
  expiryAtLocal: '2026-11-27T16:00:00',
})
const SCENARIO_S = [IXQZ_9_C, QRTV_30_C, XYZ_42_P, XYZ_45_P, XYZ_40_P_NEXT_WEEK]

function positionsResponse(positions: OptionPosition[]): OptionPositionsResponse {
  return {
    asOfDate: '2026-11-23',
    positionCount: positions.length,
    frozenTotal: '17200.00',
    positions,
  }
}

function expiration(p: OptionPosition, id: number, realizedPnl: string, frozenChange: string) {
  const record: OptionTrade = {
    id,
    symbol: p.symbol,
    expirationDate: p.expirationDate,
    strike: p.strike,
    optionType: p.optionType,
    action: 'EXP',
    contracts: p.expiryContracts,
    multiplier: 100,
    netAmount: '0.00',
    tradedAt: '2026-11-20T21:00:00Z',
    tradedAtLocal: '2026-11-20T16:00:00',
    cashChange: '0.00',
    frozenChange,
    contractLabel: p.contractLabel,
    note: null,
    ledgerEntryId: null,
    createdAt: '2026-11-23T15:00:00Z',
    coveredLots: [],
    realizedPnl,
    hasAuditLog: false,
    expiredDirection: p.direction,
    assignment: null,
  }
  return record
}

interface Recorded {
  method: string
  url: string
  body: unknown
}

type Handler = (request: Recorded) => Response | undefined

function startFakeServer(positions: () => OptionPosition[], ...handlers: Handler[]): Recorded[] {
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
    if (method === 'GET' && url === '/api/option-positions') {
      return Promise.resolve(jsonResponse(positionsResponse(positions())))
    }
    if (method === 'GET' && url === '/api/stock-positions') {
      return Promise.resolve(jsonResponse({ positionCount: 0, positions: [] }))
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function errorBody(code: string, status: number, message: string): ApiErrorResponse {
  return {
    code,
    message,
    status,
    path: BATCH_URL,
    timestamp: '2026-11-23T15:00:00Z',
    details: [],
  }
}

function dialogRow(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('batch-expire-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`dialog row ${contract} not found`)
  return row
}

function resultRow(contract: string): HTMLElement {
  const row = screen
    .getAllByTestId('batch-expire-result-row')
    .find((element) => element.getAttribute('data-contract') === contract)
  if (!row) throw new Error(`result row ${contract} not found`)
  return row
}

const batchPosts = (requests: Recorded[]) =>
  requests.filter((r) => r.method === 'POST' && r.url === BATCH_URL)

async function openDialog() {
  await userEvent.click(await screen.findByTestId('positions-batch-expire'))
  return screen.findByTestId('batch-expire-modal')
}

// ---------------------------------------------------------------------------

describe('FEAT-015 批量作废 button (rule 1, Q118)', () => {
  it('shows 批量作废（N） for 已过期未处理 counting only expirable rows', async () => {
    startFakeServer(() => SCENARIO_S)
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    const button = await screen.findByTestId('positions-batch-expire')
    expect(button).toHaveTextContent('批量作废（4）')
    expect(screen.getAllByTestId('position-row')).toHaveLength(4)
  })

  it('is absent for 全部 and N 天内', async () => {
    startFakeServer(() => SCENARIO_S)
    renderWithProviders(<PositionsPage />, { route: '/positions' })
    await screen.findAllByTestId('position-row')
    expect(screen.queryByTestId('positions-batch-expire')).not.toBeInTheDocument()

    await userEvent.click(byTestId('positions-expiry-filter-7'))
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(1)
    })
    expect(screen.queryByTestId('positions-batch-expire')).not.toBeInTheDocument()

    await userEvent.click(byTestId('positions-expiry-filter-expired'))
    expect(await screen.findByTestId('positions-batch-expire')).toHaveTextContent('批量作废（4）')
  })

  it('is absent on 今天到期 while every row is 到期待确认, present once expirable', async () => {
    const today = {
      dte: 0,
      expirationStatus: 'EXPIRES_TODAY' as const,
      daysPastExpiration: 0,
    }
    const pending = [IXQZ_9_C, XYZ_45_P].map((p) => ({
      ...p,
      ...today,
      expiryPending: true,
      expirable: false,
    }))
    let rows = pending
    startFakeServer(() => rows)
    const { queryClient } = renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=today',
    })
    await screen.findAllByTestId('position-row')
    expect(screen.queryByTestId('positions-batch-expire')).not.toBeInTheDocument()

    rows = pending.map((p) => ({
      ...p,
      expiryPending: false,
      expirable: true,
    }))
    await queryClient.invalidateQueries({ queryKey: ['option-positions'] })
    expect(await screen.findByTestId('positions-batch-expire')).toHaveTextContent('批量作废（2）')
  })
})

describe('FEAT-015 confirm dialog (rule 2, Q117)', () => {
  it('lists the rows in table order, all checked, with backend values', async () => {
    startFakeServer(() => SCENARIO_S)
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    const modal = await openDialog()

    const rows = within(modal).getAllByTestId('batch-expire-row')
    expect(rows.map((r) => r.getAttribute('data-contract'))).toEqual([
      'IXQZ 2026-11-20 9 C',
      'QRTV 2026-11-20 30 C',
      'XYZ 2026-11-20 42 P',
      'XYZ 2026-11-20 45 P',
    ])
    for (const checkbox of within(modal).getAllByTestId('batch-expire-row-checkbox')) {
      expect(checkbox).toBeChecked()
    }
    expect(byTestId('batch-expire-selected-count')).toHaveTextContent('已选 4 / 4 个')

    const xyz45 = dialogRow('XYZ 2026-11-20 45 P')
    expect(within(xyz45).getByTestId('batch-expire-row-contracts')).toHaveTextContent('2 张')
    expect(within(xyz45).getByTestId('batch-expire-row-direction')).toHaveTextContent('空头')
    expect(within(xyz45).getByTestId('batch-expire-row-opening-net')).toHaveTextContent('+240.00')
    expect(within(xyz45).getByTestId('batch-expire-row-frozen')).toHaveTextContent('9,000.00')

    const qrtv = dialogRow('QRTV 2026-11-20 30 C')
    expect(within(qrtv).getByTestId('batch-expire-row-direction')).toHaveTextContent('多头')
    expect(within(qrtv).getByTestId('batch-expire-row-opening-net')).toHaveTextContent('−60.00')
    expect(within(qrtv).getByTestId('batch-expire-row-frozen')).toHaveTextContent('—')

    expect(byTestId('batch-expire-explanation')).toHaveTextContent(
      '被指派或行权的持仓请取消勾选，在持仓行上单独处理；每条作废记在各自到期日 16:00，记错可在交易列表中逐条删除',
    )
  })

  it('全不选 disables 确认作废; 全选 and single checks update 已选 K / N 个', async () => {
    startFakeServer(() => SCENARIO_S)
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    await openDialog()

    await userEvent.click(
      within(dialogRow('XYZ 2026-11-20 45 P')).getByTestId('batch-expire-row-checkbox'),
    )
    expect(byTestId('batch-expire-selected-count')).toHaveTextContent('已选 3 / 4 个')

    await userEvent.click(byTestId('batch-expire-select-none'))
    expect(byTestId('batch-expire-selected-count')).toHaveTextContent('已选 0 / 4 个')
    expect(byTestId('batch-expire-submit')).toBeDisabled()

    await userEvent.click(byTestId('batch-expire-select-all'))
    expect(byTestId('batch-expire-selected-count')).toHaveTextContent('已选 4 / 4 个')
    expect(byTestId('batch-expire-submit')).toBeEnabled()
  })

  it('取消 closes without a request', async () => {
    const requests = startFakeServer(() => SCENARIO_S)
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    await openDialog()
    await userEvent.click(byTestId('batch-expire-cancel'))
    await waitFor(() => {
      expect(screen.queryByTestId('batch-expire-modal')).not.toBeInTheDocument()
    })
    expect(batchPosts(requests)).toHaveLength(0)
  })
})

describe('FEAT-015 submit (rules 3–5)', () => {
  it('sends the checked rows and shows the backend results matched by contract', async () => {
    let rows = SCENARIO_S
    const requests = startFakeServer(
      () => rows,
      ({ method, url }) => {
        if (method !== 'POST' || url !== BATCH_URL) return undefined
        rows = [XYZ_45_P, XYZ_40_P_NEXT_WEEK]
        // Deliberately not in dialog order: rows must be matched by contract.
        const response: ExpirationBatchResponse = {
          count: 3,
          realizedPnlTotal: '45.00',
          frozenChangeTotal: '-4200.00',
          expirations: [
            expiration(XYZ_42_P, 12, '80.00', '-4200.00'),
            expiration(IXQZ_9_C, 10, '25.00', '0.00'),
            expiration(QRTV_30_C, 11, '-60.00', '0.00'),
          ],
        }
        return jsonResponse(response, 201)
      },
    )
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    await openDialog()
    await userEvent.click(
      within(dialogRow('XYZ 2026-11-20 45 P')).getByTestId('batch-expire-row-checkbox'),
    )
    await userEvent.click(byTestId('batch-expire-submit'))

    expect(await screen.findByTestId('batch-expire-result-count')).toHaveTextContent('作废 3 个')
    expect(batchPosts(requests).map((r) => r.body)).toEqual([
      {
        items: [
          {
            symbol: 'IXQZ',
            expirationDate: '2026-11-20',
            strike: '9',
            optionType: 'CALL',
            contracts: 1,
          },
          {
            symbol: 'QRTV',
            expirationDate: '2026-11-20',
            strike: '30',
            optionType: 'CALL',
            contracts: 1,
          },
          {
            symbol: 'XYZ',
            expirationDate: '2026-11-20',
            strike: '42',
            optionType: 'PUT',
            contracts: 1,
          },
        ],
      },
    ])
    expect(byTestId('batch-expire-result-pnl-total')).toHaveTextContent('+45.00')
    expect(byTestId('batch-expire-result-frozen-total')).toHaveTextContent('−4,200.00')

    const results = screen.getAllByTestId('batch-expire-result-row')
    expect(results.map((r) => r.getAttribute('data-contract'))).toEqual([
      'IXQZ 2026-11-20 9 C',
      'QRTV 2026-11-20 30 C',
      'XYZ 2026-11-20 42 P',
    ])
    const xyz42 = resultRow('XYZ 2026-11-20 42 P')
    expect(within(xyz42).getByTestId('batch-expire-result-row-pnl')).toHaveTextContent('+80.00')
    expect(within(xyz42).getByTestId('batch-expire-result-row-frozen-change')).toHaveTextContent(
      '−4,200.00',
    )
    expect(within(xyz42).getByTestId('batch-expire-result-row-time')).toHaveTextContent(
      '2026-11-20 16:00',
    )
    const qrtv = resultRow('QRTV 2026-11-20 30 C')
    expect(within(qrtv).getByTestId('batch-expire-result-row-pnl')).toHaveTextContent('−60.00')
    expect(within(qrtv).getByTestId('batch-expire-result-row-frozen-change')).toHaveTextContent('—')

    // The positions page refreshes without a manual reload (rule 5).
    await waitFor(() => {
      expect(screen.getAllByTestId('position-row')).toHaveLength(1)
    })
    expect(byTestId('positions-batch-expire')).toHaveTextContent('批量作废（1）')

    await userEvent.click(byTestId('batch-expire-done'))
    await waitFor(() => {
      expect(screen.queryByTestId('batch-expire-modal')).not.toBeInTheDocument()
    })
  })

  it('shows every failure beside its row, keeps the dialog open, and retries without it', async () => {
    let attempt = 0
    const requests = startFakeServer(
      () => SCENARIO_S,
      ({ method, url }) => {
        if (method !== 'POST' || url !== BATCH_URL) return undefined
        attempt += 1
        if (attempt === 1) {
          const body: ExpirationBatchRejectedResponse = {
            ...errorBody('BATCH_EXPIRATION_REJECTED', 422, '有 2 个持仓不能作废，整批未保存'),
            failures: [
              {
                index: 1,
                symbol: 'QRTV',
                expirationDate: '2026-11-20',
                strike: '30',
                optionType: 'CALL',
                contractLabel: 'QRTV 2026-11-20 30 C',
                code: 'EXPIRY_BLOCKED_BY_LATE_TRADE',
                message:
                  '2026-11-20 16:10 的 STC 1 张晚于到期日美东 16:00，与到期作废冲突；请先把这笔交易的时间改为 16:00 或之前，或删除它',
              },
              {
                index: 2,
                symbol: 'XYZ',
                expirationDate: '2026-11-20',
                strike: '42',
                optionType: 'PUT',
                contractLabel: 'XYZ 2026-11-20 42 P',
                code: 'POSITION_CHANGED',
                message: '持仓已变化（当前 2 张），请刷新后重试',
              },
            ],
          }
          return jsonResponse(body, 422)
        }
        const response: ExpirationBatchResponse = {
          count: 2,
          realizedPnlTotal: '265.00',
          frozenChangeTotal: '-9000.00',
          expirations: [
            expiration(IXQZ_9_C, 10, '25.00', '0.00'),
            expiration(XYZ_45_P, 11, '240.00', '-9000.00'),
          ],
        }
        return jsonResponse(response, 201)
      },
    )
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    await openDialog()
    await userEvent.click(byTestId('batch-expire-submit'))

    expect(await screen.findByTestId('batch-expire-error')).toHaveTextContent(
      '有 2 个持仓不能作废，整批未保存',
    )
    expect(byTestId('batch-expire-error')).toHaveAttribute(
      'data-error-code',
      'BATCH_EXPIRATION_REJECTED',
    )
    const xyz42Error = within(dialogRow('XYZ 2026-11-20 42 P')).getByTestId(
      'batch-expire-row-error',
    )
    expect(xyz42Error).toHaveTextContent('持仓已变化（当前 2 张），请刷新后重试')
    expect(xyz42Error).toHaveAttribute('data-error-code', 'POSITION_CHANGED')
    expect(
      within(dialogRow('QRTV 2026-11-20 30 C')).getByTestId('batch-expire-row-error'),
    ).toHaveTextContent('2026-11-20 16:10 的 STC 1 张')
    expect(
      within(dialogRow('IXQZ 2026-11-20 9 C')).queryByTestId('batch-expire-row-error'),
    ).toBeNull()
    expect(
      within(dialogRow('XYZ 2026-11-20 45 P')).queryByTestId('batch-expire-row-error'),
    ).toBeNull()
    expect(screen.queryByTestId('batch-expire-unmatched-error')).not.toBeInTheDocument()
    expect(screen.queryByTestId('batch-expire-result')).not.toBeInTheDocument()

    await userEvent.click(
      within(dialogRow('QRTV 2026-11-20 30 C')).getByTestId('batch-expire-row-checkbox'),
    )
    await userEvent.click(
      within(dialogRow('XYZ 2026-11-20 42 P')).getByTestId('batch-expire-row-checkbox'),
    )
    await userEvent.click(byTestId('batch-expire-submit'))

    expect(await screen.findByTestId('batch-expire-result-count')).toHaveTextContent('作废 2 个')
    const posts = batchPosts(requests)
    expect(posts).toHaveLength(2)
    expect(posts[1]?.body).toEqual({
      items: [
        {
          symbol: 'IXQZ',
          expirationDate: '2026-11-20',
          strike: '9',
          optionType: 'CALL',
          contracts: 1,
        },
        {
          symbol: 'XYZ',
          expirationDate: '2026-11-20',
          strike: '45',
          optionType: 'PUT',
          contracts: 2,
        },
      ],
    })
    expect(byTestId('batch-expire-result-pnl-total')).toHaveTextContent('+265.00')
    expect(byTestId('batch-expire-result-frozen-total')).toHaveTextContent('−9,000.00')
  })

  it('shows other errors verbatim with their details', async () => {
    startFakeServer(
      () => SCENARIO_S,
      ({ method, url }) =>
        method === 'POST' && url === BATCH_URL
          ? jsonResponse(
              {
                ...errorBody('VALIDATION_ERROR', 400, 'Request validation failed'),
                details: [
                  {
                    field: 'items[1]',
                    message: '同一合约（XYZ 2026-11-20 45 P）不能出现两次',
                  },
                ],
              },
              400,
            )
          : undefined,
    )
    renderWithProviders(<PositionsPage />, {
      route: '/positions?expiry=expired',
    })
    await openDialog()
    await userEvent.click(byTestId('batch-expire-submit'))

    const error = await screen.findByTestId('batch-expire-error')
    expect(error).toHaveAttribute('data-error-code', 'VALIDATION_ERROR')
    expect(error).toHaveTextContent('同一合约（XYZ 2026-11-20 45 P）不能出现两次')
    expect(screen.queryByTestId('batch-expire-row-error')).not.toBeInTheDocument()
    expect(byTestId('batch-expire-modal')).toBeInTheDocument()
  })
})
