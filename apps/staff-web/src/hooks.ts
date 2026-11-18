import { useCallback, useEffect, useState } from "react";
import { useApi } from "./api/ApiContext";
import { ApiError, type Problem } from "./api/client";

export function toProblem(e: unknown): Problem {
  if (e instanceof ApiError) return e.problem;
  return { status: 0, title: "The server could not be reached", detail: e instanceof Error ? e.message : String(e) };
}

/** Loads a GET resource; `reload` refetches after an action. */
export function useLoad<T>(path: string | null) {
  const api = useApi();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Problem | null>(null);
  const [loading, setLoading] = useState(path !== null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (path === null) return;
    let active = true;
    setLoading(true);
    api.get<T>(path)
      .then((d) => {
        if (active) {
          setData(d);
          setError(null);
        }
      })
      .catch((e: unknown) => active && setError(toProblem(e)))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [api, path, tick]);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, reload };
}

/** Runs a write with a busy flag and problem-details error; never runs twice concurrently. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Problem | null>(null);
  const run = useCallback(async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await fn();
    } catch (e) {
      setError(toProblem(e));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, error, run, clearError: () => setError(null) };
}
