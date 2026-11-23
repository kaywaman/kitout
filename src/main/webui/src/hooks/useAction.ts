import { useCallback, useState } from "react";
import { useToasts } from "../components/Toasts.tsx";
import type { ApiError } from "../api/client.ts";

/**
 * Runs a user action: marks it busy (so the button can show it and cannot be pressed twice),
 * shows a confirmation or the backend's error sentence, and returns the result.
 */
export function useAction() {
  const toasts = useToasts();
  const [busy, setBusy] = useState<string | null>(null);

  const run = useCallback(
    async <T,>(key: string, action: () => Promise<T>, done?: string | ((result: T) => string)): Promise<T | undefined> => {
      setBusy(key);
      try {
        const result = await action();
        if (done) {
          toasts.info(typeof done === "function" ? done(result) : done);
        }
        return result;
      } catch (e) {
        toasts.error((e as ApiError).message ?? String(e));
        return undefined;
      } finally {
        setBusy((current) => (current === key ? null : current));
      }
    },
    [toasts],
  );

  return { busy, run };
}
