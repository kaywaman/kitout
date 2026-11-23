import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Snackbar from "@mui/material/Snackbar";

type Tone = "info" | "error";
interface Toast {
  id: number;
  tone: Tone;
  text: string;
}

interface ToastApi {
  info: (text: string) => void;
  error: (text: string) => void;
}

const ToastContext = createContext<ToastApi>({ info: () => {}, error: () => {} });

/**
 * Short confirmations ("Started orders") and errors with the backend's own sentence.
 * One at a time, as Material Design asks; the next waits until the current one closes.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [queue, setQueue] = useState<Toast[]>([]);
  const [open, setOpen] = useState(true);
  const current = queue[0];

  const push = useCallback((tone: Tone, text: string) => {
    // "open" is already true whenever the queue is empty (it is reset after each toast leaves)
    setQueue((q) => [...q.slice(-4), { id: Date.now() + Math.random(), tone, text }]);
  }, []);

  const api = useMemo<ToastApi>(() => ({
    info: (text) => push("info", text),
    error: (text) => push("error", text),
  }), [push]);

  const close = (_: unknown, reason?: string) => {
    if (reason !== "clickaway") {
      setOpen(false);
    }
  };

  return (
    <ToastContext.Provider value={api}>
      {children}
      {current && (
        <Snackbar
          key={current.id}
          open={open}
          autoHideDuration={current.tone === "error" ? 9000 : 3500}
          onClose={close}
          anchorOrigin={{ vertical: "bottom", horizontal: "left" }}
          slotProps={{ transition: { onExited: () => { setQueue((q) => q.slice(1)); setOpen(true); } } }}
        >
          <Alert variant="filled" severity={current.tone === "error" ? "error" : "success"} onClose={() => setOpen(false)}
            sx={{ minWidth: 300, maxWidth: 560 }}>
            {current.text}
          </Alert>
        </Snackbar>
      )}
    </ToastContext.Provider>
  );
}

export function useToasts(): ToastApi {
  return useContext(ToastContext);
}
