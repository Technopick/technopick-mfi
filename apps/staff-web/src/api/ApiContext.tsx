import { createContext, useContext, useMemo, type ReactNode } from "react";
import { createApi, type Api } from "./client";

const ApiContext = createContext<Api | null>(null);

export function ApiProvider({ baseUrl, token, children }: { baseUrl: string; token: string | null; children: ReactNode }) {
  const api = useMemo(() => createApi(baseUrl, () => token), [baseUrl, token]);
  return <ApiContext.Provider value={api}>{children}</ApiContext.Provider>;
}

/** For tests: supply any Api implementation. */
export const ApiTestProvider = ApiContext.Provider;

export function useApi(): Api {
  const api = useContext(ApiContext);
  if (!api) throw new Error("useApi outside ApiProvider");
  return api;
}
