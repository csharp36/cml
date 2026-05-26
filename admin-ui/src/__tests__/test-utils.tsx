import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { vi } from 'vitest'
import type { Api } from '../api'

export const mockApi: Api = {
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}

// Mock the useApi hook since it uses React context
vi.mock('../App', () => ({
  useApi: () => mockApi,
}))

export function renderWithProviders(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  )
}
