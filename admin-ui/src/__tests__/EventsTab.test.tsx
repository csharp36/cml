import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { renderWithProviders, mockApi } from './test-utils'
import EventsTab from '../components/EventsTab'

describe('EventsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders event list', async () => {
    vi.mocked(mockApi.get).mockResolvedValue([
      { id: 42, repoName: 'backend-api', eventType: 'post-commit', status: 'failed',
        errorMessage: 'Git fetch failed', createdAt: '2026-05-25T12:00:00Z' },
    ])

    renderWithProviders(<EventsTab />)

    await waitFor(() => {
      expect(screen.getByText('backend-api')).toBeInTheDocument()
      expect(screen.getByText('failed')).toBeInTheDocument()
      expect(screen.getByText('Retry')).toBeInTheDocument()
    })
  })

  it('shows empty state with no events', async () => {
    vi.mocked(mockApi.get).mockResolvedValue([])

    renderWithProviders(<EventsTab />)

    await waitFor(() => {
      expect(screen.getByText(/No events match/)).toBeInTheDocument()
    })
  })

  it('shows retry button only for failed events', async () => {
    vi.mocked(mockApi.get).mockResolvedValue([
      { id: 1, repoName: 'repo', eventType: 'post-commit', status: 'completed',
        errorMessage: null, createdAt: '2026-05-25T12:00:00Z' },
    ])

    renderWithProviders(<EventsTab />)

    await waitFor(() => {
      expect(screen.getByText('completed')).toBeInTheDocument()
      expect(screen.queryByText('Retry')).not.toBeInTheDocument()
    })
  })
})
