import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { renderWithProviders, mockApi } from './test-utils'
import DashboardTab from '../components/DashboardTab'

describe('DashboardTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders summary cards with health data', async () => {
    vi.mocked(mockApi.get).mockResolvedValue({
      repositories: [
        { repo_name: 'my-repo', last_indexed_sha: 'abc1234', pending_events: 0, failed_events: 2 },
      ],
      totalPendingEvents: 3,
      totalFailedEvents: 2,
      recentFailures: [],
    })

    renderWithProviders(<DashboardTab />)

    await waitFor(() => {
      expect(screen.getByText('3')).toBeInTheDocument()  // pending
      // '2' appears multiple times (card + badge in table row) — just assert at least one
      expect(screen.getAllByText('2').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('renders repository status table', async () => {
    vi.mocked(mockApi.get).mockResolvedValue({
      repositories: [
        { repo_name: 'backend-api', last_indexed_sha: 'def5678', pending_events: 1, failed_events: 0 },
      ],
      totalPendingEvents: 1,
      totalFailedEvents: 0,
      recentFailures: [],
    })

    renderWithProviders(<DashboardTab />)

    await waitFor(() => {
      expect(screen.getByText('backend-api')).toBeInTheDocument()
      expect(screen.getByText('def5678')).toBeInTheDocument()
    })
  })

  it('renders recent failures when present', async () => {
    vi.mocked(mockApi.get).mockResolvedValue({
      repositories: [],
      totalPendingEvents: 0,
      totalFailedEvents: 1,
      recentFailures: [
        { repo_name: 'my-repo', error_message: 'Git fetch failed', created_at: '2026-05-25T12:00:00Z' },
      ],
    })

    renderWithProviders(<DashboardTab />)

    await waitFor(() => {
      expect(screen.getByText('Git fetch failed')).toBeInTheDocument()
    })
  })
})
