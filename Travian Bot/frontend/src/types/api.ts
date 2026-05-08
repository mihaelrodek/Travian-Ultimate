/**
 * Mirrors the backend's {@code common.error.ApiError} record.
 */
export type ApiError = {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  details: string[];
};

export class ApiException extends Error {
  readonly apiError: ApiError | null;
  readonly status: number;

  constructor(status: number, apiError: ApiError | null, fallback: string) {
    super(apiError?.message ?? fallback);
    this.name = 'ApiException';
    this.status = status;
    this.apiError = apiError;
  }
}
