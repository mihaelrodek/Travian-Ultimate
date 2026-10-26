import { ApiException, type ApiError } from '../types/api';

/**
 * Tiny fetch wrapper that:
 * - prefixes /api automatically
 * - serializes JSON
 * - decodes {@link ApiError} on non-2xx responses into {@link ApiException}
 */
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'include',
  });

  if (!res.ok) {
    let apiError: ApiError | null = null;
    try {
      apiError = (await res.json()) as ApiError;
    } catch {
      // response wasn't JSON — fall through with null
    }
    throw new ApiException(res.status, apiError, `HTTP ${res.status}`);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
};
