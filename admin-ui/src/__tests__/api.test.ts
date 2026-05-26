import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createApi, UnauthorizedError, ServiceUnavailableError } from '../api'

describe('api', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('attaches bearer token to requests', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ data: 'test' }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const api = createApi('my-secret-token')
    await api.get('/admin/health')

    expect(mockFetch).toHaveBeenCalledWith('/admin/health', expect.objectContaining({
      headers: expect.objectContaining({
        'Authorization': 'Bearer my-secret-token',
      }),
    }))
  })

  it('throws UnauthorizedError on 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ error: 'Invalid token' }),
    }))

    const api = createApi('bad-token')
    await expect(api.get('/admin/health')).rejects.toThrow(UnauthorizedError)
  })

  it('throws ServiceUnavailableError on 503', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: () => Promise.resolve({ error: 'Admin API disabled' }),
    }))

    const api = createApi('token')
    await expect(api.get('/admin/health')).rejects.toThrow(ServiceUnavailableError)
  })

  it('sends JSON body on post', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 202,
      json: () => Promise.resolve({ name: 'repo', status: 'cloning' }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const api = createApi('token')
    await api.post('/admin/repos', { url: 'git@github.com:org/repo.git' })

    expect(mockFetch).toHaveBeenCalledWith('/admin/repos', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({
        'Content-Type': 'application/json',
      }),
      body: JSON.stringify({ url: 'git@github.com:org/repo.git' }),
    }))
  })
})
