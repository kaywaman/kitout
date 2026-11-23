import { useCallback, useEffect, useRef, useState } from "react";
import type { ApiError } from "../api/client.ts";

export interface Polled<T> {
  data: T | undefined;
  error: ApiError | undefined;
  /** Load again now, e.g. right after an action changed something. */
  refresh: () => Promise<void>;
}

/**
 * Loads data and reloads it every `intervalMs` while the tab is visible.
 * Hidden tabs do not poll, so an open kitout tab costs nothing in the background.
 */
export function usePolling<T>(load: () => Promise<T>, intervalMs: number, deps: unknown[] = []): Polled<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<ApiError>();
  const loadRef = useRef(load);
  loadRef.current = load;

  const refresh = useCallback(async () => {
    try {
      setData(await loadRef.current());
      setError(undefined);
    } catch (e) {
      setError(e as ApiError);
    }
  }, []);

  useEffect(() => {
    let timer: number | undefined;
    let cancelled = false;
    const tick = async () => {
      if (document.visibilityState === "visible") {
        await refresh();
      }
      if (!cancelled && intervalMs > 0) {
        timer = window.setTimeout(tick, intervalMs);
      }
    };
    void tick();
    const onVisible = () => {
      if (document.visibilityState === "visible") {
        void refresh();
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [refresh, intervalMs, ...deps]);

  return { data, error, refresh };
}
