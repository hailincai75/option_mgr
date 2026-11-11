import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { jsonResponse, mockFetch, renderWithProviders } from './test/render'

describe('App routing', () => {
  it('renders the home page at /', async () => {
    mockFetch().mockResolvedValue(
      jsonResponse({ status: 'UP', version: 'dev', time: '2026-11-10T22:09:08Z' }),
    )

    renderWithProviders(<App />, { route: '/' })

    expect(screen.getByTestId('health-card')).toBeInTheDocument()
    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
  })

  it('renders a not-found page for unknown routes', () => {
    renderWithProviders(<App />, { route: '/no-such-page' })

    expect(screen.getByText('Page not found')).toBeInTheDocument()
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
      return Promise.resolve(jsonResponse({ status: 'UP', version: 'dev', time: '2026-11-10T22:09:08Z' }))
    })

    renderWithProviders(<App />, { route: '/' })
    await userEvent.click(screen.getByTestId('nav-cash'))

    expect(screen.getByTestId('cash-page')).toBeInTheDocument()
    expect(await screen.findByTestId('cash-ledger-empty')).toHaveTextContent('暂无流水')
  })
})
