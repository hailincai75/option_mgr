import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ApiErrorResponse, HealthResponse } from '../api/types'
import { jsonResponse, mockFetch, renderWithProviders } from '../test/render'
import { HomePage } from './HomePage'

const health: HealthResponse = {
  status: 'UP',
  version: '0.0.1-SNAPSHOT',
  time: '2026-11-10T22:09:08.676Z',
}

const internalError: ApiErrorResponse = {
  code: 'INTERNAL_ERROR',
  message: 'Internal server error',
  status: 500,
  path: '/api/health',
  timestamp: '2026-11-10T22:09:09.171Z',
  details: [],
}

describe('HomePage', () => {
  it('shows a loading indicator while the health request is pending', () => {
    const fetchMock = mockFetch()
    fetchMock.mockReturnValue(new Promise<Response>(() => undefined))

    renderWithProviders(<HomePage />)

    expect(screen.getByTestId('health-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('health-status')).not.toBeInTheDocument()
    expect(screen.queryByTestId('health-error')).not.toBeInTheDocument()
  })

  it('shows status, version and server time in New York on success', async () => {
    const fetchMock = mockFetch()
    fetchMock.mockResolvedValue(jsonResponse(health))

    renderWithProviders(<HomePage />)

    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
    expect(screen.getByTestId('health-version')).toHaveTextContent('0.0.1-SNAPSHOT')
    expect(screen.getByTestId('health-time')).toHaveTextContent('2026-11-10 17:09:08 EST')
    expect(fetchMock).toHaveBeenCalledWith('/api/health', expect.objectContaining({ method: 'GET' }))
  })

  it('shows the backend error message and recovers after retry', async () => {
    const fetchMock = mockFetch()
    fetchMock
      .mockResolvedValueOnce(jsonResponse(internalError, 500))
      .mockResolvedValueOnce(jsonResponse(health))

    renderWithProviders(<HomePage />)

    const error = await screen.findByTestId('health-error')
    expect(error).toHaveTextContent('Internal server error')
    expect(error).toHaveTextContent('INTERNAL_ERROR')

    await userEvent.click(screen.getByTestId('health-retry'))

    expect(await screen.findByTestId('health-status')).toHaveTextContent('UP')
    expect(screen.queryByTestId('health-error')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('shows a network error when the backend cannot be reached', async () => {
    const fetchMock = mockFetch()
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    renderWithProviders(<HomePage />)

    await waitFor(() => {
      expect(screen.getByTestId('health-error')).toHaveTextContent(
        'Unable to reach the server (NETWORK_ERROR)',
      )
    })
    expect(screen.getByTestId('health-retry')).toBeEnabled()
  })
})
