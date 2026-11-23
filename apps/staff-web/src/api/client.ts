import type { components } from "./schema";

export type Schemas = components["schemas"];

/** RFC 9457 problem details returned by the API. */
export interface Problem {
  status: number;
  title: string;
  detail?: string;
  code?: string;
}

export class ApiError extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.detail ?? problem.title);
  }
}

export interface Api {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body?: unknown): Promise<T>;
  put<T>(path: string, body?: unknown): Promise<T>;
}

/** Minimal JSON client: bearer token, problem-details errors, no retries (writes are idempotent by key). */
export function createApi(baseUrl: string, token: () => string | null, fetchFn: typeof fetch = fetch): Api {
  async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const t = token();
    if (t) headers.Authorization = `Bearer ${t}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetchFn(baseUrl + path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    const data: unknown = text ? JSON.parse(text) : undefined;
    if (!response.ok) {
      const p = (data ?? {}) as Partial<Problem>;
      throw new ApiError({
        status: response.status,
        title: p.title ?? (response.status === 403 ? "Not allowed" : response.statusText || "Request failed"),
        detail: p.detail,
        code: p.code,
      });
    }
    return data as T;
  }
  return {
    get: (path) => call("GET", path),
    post: (path, body) => call("POST", path, body),
    put: (path, body) => call("PUT", path, body),
  };
}

/** A fresh idempotency key per user action, so a double click never creates two records. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
