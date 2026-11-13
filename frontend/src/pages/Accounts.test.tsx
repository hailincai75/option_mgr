import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { useLocation } from 'react-router-dom'
import { ACCOUNT_STORAGE_KEY } from '../account/accountStorage'
import type {
  Account,
  ApiErrorResponse,
  AuditLogPage,
  AuditRecord,
  CashSummary,
  OptionTrade,
  OptionTradePage,
} from '../api/types'
import { App } from '../App'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import userEvent from '../test/userEvent'

// ---------------------------------------------------------------------------
// FEAT-033: two accounts with different data, served by account header
// ---------------------------------------------------------------------------

const DEFAULT: Account = {
  id: 1,
  name: '默认账户',
  brokerNote: null,
  createdAt: '2026-10-26T14:00:00Z',
}
const SMALL: Account = {
  id: 2,
  name: '小账户',
  brokerNote: '券商 B',
  createdAt: '2026-11-12T15:05:00Z',
}

const SUMMARIES: Record<string, CashSummary> = {
  '1': { totalCash: '99603.05', frozenCash: '44000.00', availableCash: '55603.05' },
  '2': { totalCash: '30349.35', frozenCash: '20000.00', availableCash: '10349.35' },
}

function trade(id: number, symbol: string): OptionTrade {
  return {
    id,
    symbol,
    expirationDate: '2026-12-18',
    strike: '200',
    optionType: 'PUT',
    action: 'STO',
    contracts: 1,
    multiplier: 100,
    netAmount: '349.35',
    tradedAt: '2026-11-03T15:30:00Z',
    tradedAtLocal: '2026-11-03T10:30:00',
    cashChange: '349.35',
    frozenChange: '20000.00',
    contractLabel: `${symbol} 2026-12-18 200 P`,
    note: null,
    ledgerEntryId: id + 100,
    createdAt: '2026-11-12T15:00:00Z',
    coveredLots: null,
    realizedPnl: null,
  }
}

const TRADES: Record<string, OptionTrade[]> = {
  '1': [trade(1, 'AAPL'), trade(2, 'MSFT')],
  '2': [trade(17, 'AAPL')],
}

interface Recorded {
  method: string
  url: string
  account: string | undefined
  body: unknown
}

interface FakeServer {
  accounts: Account[]
  audit: AuditRecord[]
  onCreate?: (body: unknown) => Response
  onUpdate?: (id: number, body: unknown) => Response
  accountsFail?: boolean
  /** Account ids the backend no longer knows (→ 404 ACCOUNT_NOT_FOUND). */
  missingAccounts?: string[]
  /** Account-scoped endpoints unreachable (backend down). */
  scopedFail?: boolean
}

function error(
  code: string,
  status: number,
  message: string,
  details: ApiErrorResponse['details'] = [],
) {
  const body: ApiErrorResponse = {
    code,
    message,
    status,
    path: '/api/accounts',
    timestamp: '2026-11-12T15:00:00Z',
    details,
  }
  return jsonResponse(body, status)
}

function startFakeServer(server: FakeServer) {
  const requests: Recorded[] = []
  mockFetch({ accounts: false }).mockImplementation((input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const method = init?.method ?? 'GET'
    const headers = (init?.headers ?? {}) as Record<string, string>
    const account = headers['X-Account-Id']
    const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    requests.push({ method, url, account, body })
    const path = new URL(url, 'http://localhost').pathname

    if (path === '/api/accounts' && method === 'GET') {
      if (server.accountsFail) return Promise.reject(new TypeError('Failed to fetch'))
      return Promise.resolve(jsonResponse(server.accounts))
    }
    if (path === '/api/accounts' && method === 'POST' && server.onCreate) {
      return Promise.resolve(server.onCreate(body))
    }
    if (path.startsWith('/api/accounts/') && method === 'PUT' && server.onUpdate) {
      return Promise.resolve(server.onUpdate(Number(path.split('/').at(-1)), body))
    }
    if (path === '/api/health') {
      return Promise.resolve(
        jsonResponse({ status: 'UP', version: 'dev', time: '2026-11-12T15:00:00Z' }),
      )
    }
    // Account-scoped endpoints: the header is required, like the backend.
    if (account === undefined) {
      return Promise.resolve(
        error('ACCOUNT_REQUIRED', 400, 'Account required', [
          { field: 'X-Account-Id', message: '请指定账户' },
        ]),
      )
    }
    if (server.scopedFail) return Promise.reject(new TypeError('Failed to fetch'))
    if (server.missingAccounts?.includes(account)) {
      return Promise.resolve(error('ACCOUNT_NOT_FOUND', 404, `Account ${account} not found`))
    }
    if (path === '/api/cash/summary') {
      return Promise.resolve(jsonResponse(SUMMARIES[account]))
    }
    if (path === '/api/option-trades' && method === 'GET') {
      const symbol = new URL(url, 'http://localhost').searchParams.get('symbol')
      const items = (TRADES[account] ?? []).filter(
        (t) => !symbol || t.symbol === symbol.toUpperCase(),
      )
      const page: OptionTradePage = {
        items,
        page: 1,
        size: 20,
        totalItems: items.length,
        totalPages: 1,
      }
      return Promise.resolve(jsonResponse(page))
    }
    if (path === '/api/option-trades' && method === 'POST') {
      return Promise.resolve(jsonResponse(trade(18, 'AAPL'), 201))
    }
    if (path === '/api/audit-log') {
      const items = server.audit
      const page: AuditLogPage = {
        items,
        page: 1,
        size: 20,
        totalItems: items.length,
        totalPages: 1,
      }
      return Promise.resolve(jsonResponse(page))
    }
    if (path === '/api/dashboard') {
      return Promise.resolve(
        jsonResponse({
          asOfDate: '2026-11-12',
          currentMonth: '2026-11',
          cash: SUMMARIES[account],
          realizedPnl: { closedCount: 0, total: '0.00' },
          currentMonthRealizedPnl: { closedCount: 0, total: '0.00' },
          positions: {
            positionCount: 0,
            frozenTotal: '0.00',
            expiringWithin7DaysCount: 0,
            expiredUnprocessedCount: 0,
          },
        }),
      )
    }
    return Promise.reject(new Error(`Unexpected request ${method} ${url}`))
  })
  return requests
}

function Probe() {
  const location = useLocation()
  return <span data-testid="location">{location.pathname + location.search}</span>
}

function renderApp(route: string) {
  return renderWithProviders(
    <>
      <App />
      <Probe />
    </>,
    { route },
  )
}

const byTestId = (id: string) => screen.getByTestId(id)

async function switchTo(accountId: number) {
  await userEvent.click(byTestId('account-switcher'))
  await userEvent.click(await screen.findByTestId(`account-option-${String(accountId)}`))
}

function tradeRowIds(): string[] {
  return screen.queryAllByTestId('trade-row').map((row) => row.getAttribute('data-trade-id') ?? '')
}

// ---------------------------------------------------------------------------

describe('account switcher (FEAT-033)', () => {
  it('starts with the lowest id, shows the current account and sends its header (AC 3, 9)', async () => {
    const requests = startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/cash')

    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
    expect(byTestId('account-switcher')).toHaveTextContent('当前账户：默认账户')
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('99,603.05')
    })
    const scoped = requests.filter((r) => r.url.startsWith('/api/cash'))
    expect(scoped.length).toBeGreaterThan(0)
    expect(scoped.every((r) => r.account === '1')).toBe(true)
    expect(requests.find((r) => r.url === '/api/accounts')?.account).toBeUndefined()
  })

  it('switches immediately, keeps URL filters and remembers the choice (AC 9)', async () => {
    const requests = startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/trades?symbol=AAPL')

    await waitFor(() => {
      expect(tradeRowIds()).toEqual(['1'])
    })
    await switchTo(2)

    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('小账户')
    })
    await waitFor(() => {
      expect(tradeRowIds()).toEqual(['17'])
    })
    expect(byTestId('location')).toHaveTextContent('/trades?symbol=AAPL')
    expect(byTestId('trade-filter-symbol')).toHaveValue('AAPL')
    const lastList = requests.filter((r) => r.url.startsWith('/api/option-trades?')).at(-1)
    expect(lastList?.account).toBe('2')
    expect(lastList?.url).toContain('symbol=AAPL')
    expect(window.localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('2')
  })

  it('restores the remembered account after a reload (AC 9)', async () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, '2')
    const requests = startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/cash')

    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('小账户')
    })
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('30,349.35')
    })
    expect(
      requests.filter((r) => r.url === '/api/cash/summary').every((r) => r.account === '2'),
    ).toBe(true)
  })

  it('falls back to the lowest id when the remembered account no longer exists (Q55)', async () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, '99')
    startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/cash')
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
  })

  it('falls back to the lowest id when browser storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    startFakeServer({ accounts: [SMALL, DEFAULT], audit: [] })
    renderApp('/cash')
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
  })

  it('shows the switcher with a single account (AC 3)', async () => {
    startFakeServer({ accounts: [DEFAULT], audit: [] })
    renderApp('/')
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
  })

  it('closes an open edit dialog when the account changes', async () => {
    startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/trades')
    await waitFor(() => {
      expect(tradeRowIds()).toEqual(['1', '2'])
    })
    const firstRow = screen.getAllByTestId('trade-row')[0] as HTMLElement
    await userEvent.click(within(firstRow).getByTestId('trade-edit'))
    expect(await screen.findByTestId('trade-edit-modal')).toBeInTheDocument()

    await switchTo(2)
    await waitFor(() => {
      expect(screen.queryByTestId('trade-edit-form')).not.toBeInTheDocument()
    })
    await waitFor(() => {
      expect(tradeRowIds()).toEqual(['17'])
    })
  })

  it('writes a new trade to the selected account (AC 9)', async () => {
    const requests = startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/trades/new')
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
    await switchTo(2)
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('小账户')
    })

    const user = userEvent.setup()
    await user.click(await screen.findByTestId('open-trade-action-sto'))
    await user.click(byTestId('open-trade-type-put'))
    const set = (id: string, value: string) => {
      fireEvent.change(byTestId(id), { target: { value } })
    }
    set('open-trade-symbol', 'AAPL')
    set('open-trade-expiration', '2026-12-18')
    set('open-trade-strike', '200')
    set('open-trade-contracts', '1')
    set('open-trade-net-amount', '349.35')
    set('open-trade-time', '2026-11-03T10:30')
    await user.click(byTestId('open-trade-submit'))

    expect(await screen.findByTestId('open-trade-confirmation')).toBeInTheDocument()
    const post = requests.find((r) => r.method === 'POST' && r.url === '/api/option-trades')
    expect(post?.account).toBe('2')
  })

  it('shows an account-list error with retry and still renders the page', async () => {
    const server: FakeServer = { accounts: [DEFAULT], audit: [], accountsFail: true }
    startFakeServer(server)
    renderApp('/')

    expect(await screen.findByTestId('account-error')).toHaveTextContent('NETWORK_ERROR')
    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')

    server.accountsFail = false
    await userEvent.click(byTestId('account-retry'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
    expect(screen.queryByTestId('account-error')).not.toBeInTheDocument()
  })
})

describe('account errors and recovery (FEAT-033 follow-up)', () => {
  it('backend down at load: one click on the page 重试 recovers, no request without the header', async () => {
    const server: FakeServer = {
      accounts: [DEFAULT],
      audit: [],
      accountsFail: true,
      scopedFail: true,
    }
    const requests = startFakeServer(server)
    renderApp('/trades?symbol=AAPL')

    expect(await screen.findByTestId('trade-list-error')).toHaveTextContent('账户未就绪，请重试')
    expect(screen.queryByText(/Account required/)).not.toBeInTheDocument()

    server.accountsFail = false
    server.scopedFail = false
    await userEvent.click(byTestId('trade-list-retry'))

    await waitFor(() => {
      expect(tradeRowIds()).toEqual(['1'])
    })
    expect(byTestId('account-current')).toHaveTextContent('默认账户')
    expect(screen.queryByTestId('account-error')).not.toBeInTheDocument()
    const scoped = requests.filter((r) => r.url.startsWith('/api/option-trades'))
    expect(scoped.length).toBeGreaterThan(0)
    expect(scoped.every((r) => r.account === '1')).toBe(true)
    expect(
      requests.some((r) => r.account === undefined && r.url.startsWith('/api/option-trades')),
    ).toBe(false)
  })

  it('ACCOUNT_NOT_FOUND falls back to the lowest-id account with a Chinese message', async () => {
    window.localStorage.setItem(ACCOUNT_STORAGE_KEY, '2')
    const server: FakeServer = { accounts: [DEFAULT, SMALL], audit: [] }
    startFakeServer(server)
    renderApp('/cash')
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('30,349.35')
    })

    // Account 2 disappears on the backend (e.g. reset); the next request reports it.
    server.accounts = [DEFAULT]
    server.missingAccounts = ['2']
    await userEvent.click(byTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-manage-link'))
    await userEvent.click(byTestId('nav-cash'))

    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
    await waitFor(() => {
      expect(byTestId('cash-summary-total')).toHaveTextContent('99,603.05')
    })
    expect(screen.queryByText(/Account 2 not found/)).not.toBeInTheDocument()
  })
})

// ---------------------------------------------------------------------------
// Account management page (AC 4)
// ---------------------------------------------------------------------------

describe('AccountsPage (FEAT-033 AC 4)', () => {
  it('opens from the switcher and lists accounts without delete buttons', async () => {
    startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/')
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('默认账户')
    })
    await userEvent.click(byTestId('account-switcher'))
    await userEvent.click(await screen.findByTestId('account-manage-link'))

    expect(await screen.findByTestId('accounts-page')).toBeInTheDocument()
    expect(byTestId('location')).toHaveTextContent('/accounts')
    const rows = screen.getAllByTestId('account-row')
    const cells = rows.map((row) => [
      within(row).getByTestId('account-cell-name').textContent,
      within(row).getByTestId('account-cell-broker-note').textContent,
      within(row).getByTestId('account-cell-created').textContent,
    ])
    expect(cells).toEqual([
      ['默认账户', '—', '2026-10-26 10:00'],
      ['小账户', '券商 B', '2026-11-12 10:05'],
    ])
    expect(within(rows[0] as HTMLElement).getByTestId('account-cell-current')).toBeInTheDocument()
    expect(screen.queryByText('删除')).not.toBeInTheDocument()
  })

  it('creates an account; it appears in the switcher without switching (AC 4)', async () => {
    const server: FakeServer = { accounts: [DEFAULT], audit: [] }
    server.onCreate = () => {
      server.accounts = [DEFAULT, SMALL]
      return jsonResponse(SMALL, 201)
    }
    const requests = startFakeServer(server)
    renderApp('/accounts')

    await userEvent.click(await screen.findByTestId('account-create'))
    expect(await screen.findByText('请勿填写账号。名称和备注只用于区分账户。')).toBeInTheDocument()
    fireEvent.change(byTestId('account-form-name'), { target: { value: ' 小账户 ' } })
    fireEvent.change(byTestId('account-form-broker-note'), { target: { value: '券商 B' } })
    await userEvent.click(byTestId('account-form-submit'))

    await waitFor(() => {
      expect(screen.getAllByTestId('account-row')).toHaveLength(2)
    })
    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({
      name: ' 小账户 ',
      brokerNote: '券商 B',
    })
    expect(byTestId('account-current')).toHaveTextContent('默认账户')
    await userEvent.click(byTestId('account-switcher'))
    expect(await screen.findByTestId('account-option-2')).toHaveTextContent('小账户')
  })

  it.each([
    ['', '账户名称不能为空'],
    ['   ', '账户名称不能为空'],
    ['x'.repeat(31), '账户名称最多 30 个字符'],
  ])('rejects name %j on the client (AC 4)', async (name, message) => {
    const requests = startFakeServer({ accounts: [DEFAULT], audit: [] })
    renderApp('/accounts')
    await userEvent.click(await screen.findByTestId('account-create'))
    fireEvent.change(byTestId('account-form-name'), { target: { value: name } })
    await userEvent.click(byTestId('account-form-submit'))

    const item = byTestId('account-form-name').closest<HTMLElement>('.ant-form-item')
    if (!item) throw new Error('form item')
    expect(await within(item).findByText(message)).toBeInTheDocument()
    expect(requests.some((r) => r.method === 'POST')).toBe(false)
  })

  it('shows a duplicate name from the backend under the name field (AC 4)', async () => {
    startFakeServer({
      accounts: [DEFAULT],
      audit: [],
      onCreate: () =>
        error('VALIDATION_ERROR', 400, 'Request validation failed', [
          { field: 'name', message: '账户名称已存在' },
        ]),
    })
    renderApp('/accounts')
    await userEvent.click(await screen.findByTestId('account-create'))
    fireEvent.change(byTestId('account-form-name'), { target: { value: '默认账户' } })
    await userEvent.click(byTestId('account-form-submit'))

    const item = byTestId('account-form-name').closest<HTMLElement>('.ant-form-item')
    if (!item) throw new Error('form item')
    expect(await within(item).findByText('账户名称已存在')).toBeInTheDocument()
    expect(byTestId('account-form-name')).toHaveValue('默认账户')
  })

  it('renames an account; the switcher shows the new name (AC 4)', async () => {
    const server: FakeServer = { accounts: [DEFAULT], audit: [] }
    server.onUpdate = (id, body) => {
      const renamed = { ...DEFAULT, id, name: '主账户' }
      server.accounts = [renamed]
      expect(body).toEqual({ name: '主账户', brokerNote: null })
      return jsonResponse(renamed)
    }
    const requests = startFakeServer(server)
    renderApp('/accounts')

    await userEvent.click(await screen.findByTestId('account-edit'))
    expect(byTestId('account-form-name')).toHaveValue('默认账户')
    fireEvent.change(byTestId('account-form-name'), { target: { value: '主账户' } })
    await userEvent.click(byTestId('account-form-submit'))

    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('主账户')
    })
    expect(requests.find((r) => r.method === 'PUT')?.url).toBe('/api/accounts/1')
  })

  it('shows 没有修改 verbatim and keeps the dialog open', async () => {
    startFakeServer({
      accounts: [DEFAULT],
      audit: [],
      onUpdate: () => error('NO_CHANGES', 422, '没有修改'),
    })
    renderApp('/accounts')
    await userEvent.click(await screen.findByTestId('account-edit'))
    await userEvent.click(byTestId('account-form-submit'))
    expect(await screen.findByTestId('account-form-error')).toHaveTextContent('没有修改')
    expect(byTestId('account-form-modal')).toBeInTheDocument()
  })

  it('switches to another account from its row', async () => {
    startFakeServer({ accounts: [DEFAULT, SMALL], audit: [] })
    renderApp('/accounts')
    const rows = await screen.findAllByTestId('account-row')
    await userEvent.click(within(rows[1] as HTMLElement).getByTestId('account-select'))
    await waitFor(() => {
      expect(byTestId('account-current')).toHaveTextContent('小账户')
    })
  })
})

// ---------------------------------------------------------------------------
// Audit log with account records (AC 4, 5)
// ---------------------------------------------------------------------------

describe('audit log with account records', () => {
  const created: AuditRecord = {
    id: 3,
    operatedAt: '2026-11-12T15:05:00Z',
    operation: 'CREATE',
    objectType: 'ACCOUNT',
    objectId: 2,
    summary: '账户 小账户',
    before: null,
    after: { id: 2, name: '小账户', brokerNote: '券商 B' },
    changes: [],
    sideEffects: [],
  }
  const renamed: AuditRecord = {
    id: 4,
    operatedAt: '2026-11-12T15:10:00Z',
    operation: 'UPDATE',
    objectType: 'ACCOUNT',
    objectId: 1,
    summary: '账户 默认账户',
    before: { id: 1, name: '默认账户', brokerNote: null },
    after: { id: 1, name: '主账户', brokerNote: null },
    changes: [
      {
        field: 'name',
        label: '名称',
        before: '默认账户',
        after: '主账户',
        text: '名称：默认账户 → 主账户',
      },
    ],
    sideEffects: [],
  }

  it('labels account create / update records and their snapshots', async () => {
    startFakeServer({ accounts: [DEFAULT, SMALL], audit: [renamed, created] })
    renderApp('/audit')

    const rows = await screen.findAllByTestId('audit-row')
    const texts = rows.map((row) => [
      within(row).getByTestId('audit-cell-operation').textContent,
      within(row).getByTestId('audit-cell-object').textContent,
      within(row).getByTestId('audit-cell-changes').textContent,
    ])
    expect(texts).toEqual([
      ['修改', '账户 #1', '名称：默认账户 → 主账户'],
      ['新增', '账户 #2', '已新增'],
    ])

    await userEvent.click(within(rows[1] as HTMLElement).getByTestId('audit-expand'))
    const detail = await screen.findByTestId('audit-detail')
    expect(within(detail).getByTestId('audit-before')).toHaveTextContent('无（新增）')
    expect(within(detail).getByTestId('audit-after-name')).toHaveTextContent('小账户')
    expect(within(detail).getByTestId('audit-after-brokerNote')).toHaveTextContent('券商 B')
  })

  it('filters by account objects', async () => {
    const requests = startFakeServer({ accounts: [DEFAULT], audit: [renamed] })
    renderApp('/audit')
    await screen.findAllByTestId('audit-row')
    await userEvent.click(byTestId('audit-filter-type-account'))
    fireEvent.change(byTestId('audit-filter-id'), { target: { value: '1' } })
    await userEvent.click(byTestId('audit-filter-submit'))

    await waitFor(() => {
      expect(requests.at(-1)?.url).toBe(
        '/api/audit-log?objectType=ACCOUNT&objectId=1&page=1&size=20',
      )
    })
    expect(requests.at(-1)?.account).toBe('1')
  })
})
