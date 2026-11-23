import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { ApiTestProvider } from "./api/ApiContext";
import type { Api } from "./api/client";
import { TestAuthContext } from "./auth/AuthContext";
import type { Session } from "./auth/session";
import { routes } from "./routes";

export function session(subject: string, ...roles: string[]): Session {
  return { subject, name: subject, tenantId: "t-1", roles, accessToken: "test-token" };
}

/** A fake API: routes "METHOD path" to a handler; records every call. */
export function fakeApi(handlers: Record<string, (body?: unknown) => unknown>, delayMs = 0) {
  const calls: { method: string; path: string; body?: unknown }[] = [];
  const handle = async (method: string, path: string, body?: unknown) => {
    calls.push({ method, path, body });
    if (delayMs > 0) await new Promise((r) => setTimeout(r, delayMs));
    const h = handlers[`${method} ${path}`];
    if (!h) throw new Error(`unexpected ${method} ${path}`);
    return h(body);
  };
  const api: Api = {
    get: <T,>(path: string) => handle("GET", path) as Promise<T>,
    post: <T,>(path: string, body?: unknown) => handle("POST", path, body) as Promise<T>,
    put: <T,>(path: string, body?: unknown) => handle("PUT", path, body) as Promise<T>,
  };
  return { api, calls };
}

export function renderAt(path: string, s: Session, api: Api) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(
    <TestAuthContext.Provider value={{ session: s, loading: false, error: null, signIn: async () => {}, signOut: async () => {} }}>
      <ApiTestProvider value={api}>
        <RouterProvider router={router} />
      </ApiTestProvider>
    </TestAuthContext.Provider>,
  );
}
