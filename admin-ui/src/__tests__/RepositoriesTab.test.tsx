import { describe, it, expect, vi, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, mockApi } from './test-utils'
import RepositoriesTab from '../components/RepositoriesTab'

describe('RepositoriesTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders repository list', async () => {
    vi.mocked(mockApi.get).mockResolvedValue([
      { name: 'backend-api', url: 'git@github.com:org/backend.git', branch: 'main',
        fileCount: 150, lastIndexedSha: 'abc123', lastIndexedAt: '2026-05-25T12:00:00Z', status: 'ready' },
    ])

    renderWithProviders(<RepositoriesTab />)

    await waitFor(() => {
      expect(screen.getByText('backend-api')).toBeInTheDocument()
      expect(screen.getByText('ready')).toBeInTheDocument()
      expect(screen.getByText('150')).toBeInTheDocument()
    })
  })

  it('shows empty state when no repos', async () => {
    vi.mocked(mockApi.get).mockResolvedValue([])

    renderWithProviders(<RepositoriesTab />)

    await waitFor(() => {
      expect(screen.getByText(/No repositories/)).toBeInTheDocument()
    })
  })

  it('opens add repo dialog', async () => {
    vi.mocked(mockApi.get).mockResolvedValue([])

    renderWithProviders(<RepositoriesTab />)

    await waitFor(() => screen.getByText('Add Repository'))
    await userEvent.click(screen.getByText('Add Repository'))

    expect(screen.getByPlaceholderText('git@github.com:org/repo.git')).toBeInTheDocument()
  })
})
