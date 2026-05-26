export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export class UnauthorizedError extends ApiError {
  constructor(message: string) {
    super(401, message)
  }
}

export class NotFoundError extends ApiError {
  constructor(message: string) {
    super(404, message)
  }
}

export class ConflictError extends ApiError {
  constructor(message: string) {
    super(409, message)
  }
}

export class BadRequestError extends ApiError {
  constructor(message: string) {
    super(400, message)
  }
}

export class ServiceUnavailableError extends ApiError {
  constructor(message: string) {
    super(503, message)
  }
}

export interface Api {
  get<T = unknown>(path: string): Promise<T>
  post<T = unknown>(path: string, body?: unknown): Promise<T>
  del<T = unknown>(path: string): Promise<T>
}

export function createApi(token: string): Api {
  async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const headers: Record<string, string> = {
      'Authorization': `Bearer ${token}`,
      ...((options?.headers as Record<string, string>) || {}),
    }

    const response = await fetch(path, { ...options, headers })

    if (!response.ok) {
      const body = await response.json().catch(() => ({ error: 'Unknown error' }))
      const message = (body as { error?: string }).error ?? `HTTP ${response.status}`

      switch (response.status) {
        case 401: throw new UnauthorizedError(message)
        case 404: throw new NotFoundError(message)
        case 409: throw new ConflictError(message)
        case 400: throw new BadRequestError(message)
        case 503: throw new ServiceUnavailableError(message)
        default: throw new ApiError(response.status, message)
      }
    }

    return response.json() as Promise<T>
  }

  return {
    get: <T>(path: string) => request<T>(path),
    post: <T>(path: string, body?: unknown) =>
      request<T>(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body !== undefined ? JSON.stringify(body) : undefined,
      }),
    del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  }
}
