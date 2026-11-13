import { screen } from '@testing-library/react'
import userEvent from './test/userEvent'
import { App } from './App'
import { homeResponse } from './test/homeFixtures'
import { jsonResponse, mockFetch, renderWithProviders } from './test/render'

describe('App routing', () => {
  it('renders the home page at /', async () => {
    mockFetch().mockImplementation((input) => homeResponse(typeof input === 'string' ? input : ''))

    renderWithProviders(<App />, { route: '/' })

    // Pages render once the account list (FEAT-033) has loaded.
    expect(await screen.findByTestId('health-card')).toBeInTheDocument()
    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
  })

  it('renders a not-found page for unknown routes', async () => {
    mockFetch()
    renderWithProviders(<App />, { route: '/no-such-page' })

    expect(await screen.findByText('Page not found')).toBeInTheDocument()
  })
})

describe('cash route', () => {
  it('navigates to the cash page from the nav link', async () => {
    mockFetch().mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ''
      if (url === '/api/cash/summary') {
        return Promise.resolve(
          jsonResponse({ totalCash: '0.00', frozenCash: '0.00', availableCash: '0.00' }),
        )
      }
      if (url.startsWith('/api/cash/ledger')) {
        return Promise.resolve(
          jsonResponse({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }),
        )
      }
      return homeResponse(url)
    })

    renderWithProviders(<App />, { route: '/' })
    await userEvent.click(screen.getByTestId('nav-cash'))

    expect(screen.getByTestId('cash-page')).toBeInTheDocument()
    expect(await screen.findByTestId('cash-ledger-empty')).toHaveTextContent('暂无流水')
  })
})

describe('open trade route', () => {
  it('navigates to the open-trade page from the nav link', async () => {
    mockFetch().mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ''
      if (url === '/api/cash/summary') {
        return Promise.resolve(
          jsonResponse({ totalCash: '0.00', frozenCash: '0.00', availableCash: '0.00' }),
        )
      }
      return homeResponse(url)
    })

    renderWithProviders(<App />, { route: '/' })
    await userEvent.click(screen.getByTestId('nav-open-trade'))

    expect(screen.getByTestId('open-trade-page')).toBeInTheDocument()
    expect(screen.getByTestId('open-trade-form')).toBeInTheDocument()
    expect(await screen.findByTestId('cash-summary-total')).toHaveTextContent('0.00')
  })
})

describe('trades route', () => {
  it('navigates to the trade list from the nav link and highlights only 交易', async () => {
    mockFetch().mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ''
      if (url.startsWith('/api/option-trades')) {
        return Promise.resolve(
          jsonResponse({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }),
        )
      }
      return homeResponse(url)
    })

    renderWithProviders(<App />, { route: '/' })
    await userEvent.click(screen.getByTestId('nav-trades'))

    expect(screen.getByTestId('trade-list-page')).toBeInTheDocument()
    expect(await screen.findByTestId('trade-list-empty')).toHaveTextContent('暂无交易')
    const selected = document.querySelectorAll('.ant-menu-item-selected')
    expect(selected).toHaveLength(1)
    expect(selected[0]).toHaveTextContent('交易')
  })
})

describe('positions route', () => {
  it('navigates to the positions page from the nav link', async () => {
    mockFetch().mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ''
      if (url === '/api/option-positions') {
        return Promise.resolve(
          jsonResponse({
            asOfDate: '2026-11-11',
            positionCount: 0,
            frozenTotal: '0.00',
            positions: [],
          }),
        )
      }
      return homeResponse(url)
    })

    renderWithProviders(<App />, { route: '/' })
    await userEvent.click(screen.getByTestId('nav-positions'))

    expect(screen.getByTestId('positions-page')).toBeInTheDocument()
    expect(await screen.findByTestId('positions-empty')).toHaveTextContent('暂无持仓')
  })
})
