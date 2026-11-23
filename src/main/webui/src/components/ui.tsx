/**
 * Shared building blocks on top of MUI. Views compose these; they hold no data logic.
 * Status rules live in lib/status.ts, colours in theme.ts.
 */
import { useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import CheckRounded from "@mui/icons-material/CheckRounded";
import ContentCopyRounded from "@mui/icons-material/ContentCopyRounded";
import FiberManualRecord from "@mui/icons-material/FiberManualRecord";
import { MONO } from "../theme.ts";
import { buildLook, healthLook, severityLook, stateLook, type Look, type ServiceLike } from "../lib/status.ts";

/** A status as a chip: a dot (or a spinner while it changes by itself) plus the word. Colour is never the only signal. */
export function StatusChip({ look, title }: { look: Look; title?: string }) {
  const icon = look.busy
    ? <CircularProgress size={10} thickness={6} color="inherit" />
    : <FiberManualRecord sx={{ fontSize: "10px !important" }} />;
  const chip = <Chip variant="soft" color={look.color} icon={icon} label={look.label} sx={{ "& .MuiChip-icon": { color: "inherit", ml: "6px" } }} />;
  return title ? <Tooltip title={title}><span>{chip}</span></Tooltip> : chip;
}

export function StateChip({ state, title }: { state: string; title?: string }) {
  return <StatusChip look={stateLook(state)} title={title} />;
}

/** Readiness of a running service, with the endpoint kitout asks in the tooltip. */
export function HealthChip({ service, title }: { service: ServiceLike; title: string }) {
  const look = healthLook(service);
  return look ? <StatusChip look={look} title={title} /> : null;
}

export function SeverityChip({ severity }: { severity: string }) {
  return <StatusChip look={severityLook(severity)} />;
}

export function BuildChip({ status, title }: { status: string; title?: string }) {
  return <StatusChip look={buildLook(status)} title={title} />;
}

/** A small label chip: "BFF", "prod", "Quarkus project", "From files". */
export function Tag({ label, color = "default", title }: { label: string; color?: Look["color"]; title?: string }) {
  const chip = <Chip variant="outlined" color={color} label={label} sx={{ height: 20, fontSize: "0.6875rem" }} />;
  return title ? <Tooltip title={title}><span>{chip}</span></Tooltip> : chip;
}

/** Text a developer copies: ids, ports, paths, image names. */
export function Mono({ children, muted, title }: { children: ReactNode; muted?: boolean; title?: string }) {
  return (
    <Typography component="span" title={title}
      sx={{ fontFamily: MONO, fontSize: "0.8125rem", color: muted ? "text.secondary" : "text.primary", overflowWrap: "anywhere" }}>
      {children}
    </Typography>
  );
}

export function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard blocked: the text is on screen to select by hand
    }
  };
  return (
    <Tooltip title={copied ? "Copied" : label}>
      <IconButton aria-label={label} onClick={() => void copy()}>
        {copied ? <CheckRounded fontSize="small" /> : <ContentCopyRounded fontSize="small" />}
      </IconButton>
    </Tooltip>
  );
}

/**
 * An icon button with its name in a tooltip. When disabled, the tooltip says why
 * (a disabled button gets no mouse events, hence the wrapping span).
 */
export function ActionIcon({ label, why, onClick, disabled, color, children }: {
  label: string;
  /** Shown instead of the label while disabled: what to do first. */
  why?: string;
  onClick: () => void;
  disabled?: boolean;
  color?: "default" | "error" | "primary";
  children: ReactNode;
}) {
  return (
    <Tooltip title={disabled && why ? why : label}>
      <span>
        <IconButton aria-label={label} onClick={onClick} disabled={disabled} color={color}>{children}</IconButton>
      </span>
    </Tooltip>
  );
}

export function AppDialog({ open, title, onClose, children, actions, maxWidth = "sm" }: {
  open: boolean;
  title: ReactNode;
  onClose: () => void;
  children: ReactNode;
  actions?: ReactNode;
  maxWidth?: "xs" | "sm" | "md" | "lg";
}) {
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth={maxWidth} aria-labelledby="app-dialog-title"
      slotProps={{ paper: { variant: "outlined" } }}>
      <DialogTitle id="app-dialog-title">{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2}>{children}</Stack>
      </DialogContent>
      {actions && <DialogActions sx={{ px: 3, pb: 2 }}>{actions}</DialogActions>}
    </Dialog>
  );
}

export function PageHeader({ title, subtitle, children }: { title: ReactNode; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}
      sx={{ mb: 3, pb: 2.5, borderBottom: 1, borderColor: "divider", alignItems: { sm: "flex-end" } }}>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="h1">{title}</Typography>
        {subtitle && <Box sx={{ mt: 1, color: "text.secondary", maxWidth: 880 }}>{subtitle}</Box>}
      </Box>
      {children && <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap" }}>{children}</Stack>}
    </Stack>
  );
}

/** An outlined panel with a heading: one settings area, one tier of services. */
export function Section({ title, description, action, children, dense }: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  /** No inner padding, for a table that runs to the edges. */
  dense?: boolean;
}) {
  return (
    <Paper variant="outlined" component="section" sx={{ mb: 3, overflow: "hidden" }}>
      <Stack direction="row" spacing={1.5}
        sx={{ px: 2.5, py: 1.5, alignItems: "center", borderBottom: 1, borderColor: "divider", bgcolor: "action.hover" }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography variant="h2" component="h2">{title}</Typography>
          {description && <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.25 }}>{description}</Typography>}
        </Box>
        {action}
      </Stack>
      <Box sx={dense ? undefined : { p: 2.5 }}>{children}</Box>
    </Paper>
  );
}

export function EmptyState({ title, children, icon }: { title: string; children?: ReactNode; icon?: ReactNode }) {
  return (
    <Paper variant="outlined" sx={{ p: 4, textAlign: "center" }}>
      {icon && <Box sx={{ color: "text.secondary", mb: 1, "& svg": { fontSize: 36 } }}>{icon}</Box>}
      <Typography variant="h2" component="h2" gutterBottom>{title}</Typography>
      <Box sx={{ color: "text.secondary", maxWidth: 560, mx: "auto" }}>{children}</Box>
    </Paper>
  );
}

export function ErrorAlert({ message }: { message?: string }) {
  return message ? <Alert severity="error" sx={{ mb: 2 }}>{message}</Alert> : null;
}
