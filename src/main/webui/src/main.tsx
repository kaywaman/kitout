import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import { App } from "./App.tsx";
import { ToastProvider } from "./components/Toasts.tsx";
import { captureToken } from "./lib/session.ts";
import { theme } from "./theme.ts";

captureToken();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider theme={theme} defaultMode="system">
      <CssBaseline enableColorScheme />
      <ToastProvider>
        <App />
      </ToastProvider>
    </ThemeProvider>
  </StrictMode>,
);
