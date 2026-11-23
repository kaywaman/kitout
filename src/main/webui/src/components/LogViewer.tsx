import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import InputAdornment from "@mui/material/InputAdornment";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import Tooltip from "@mui/material/Tooltip";
import ClearAllRounded from "@mui/icons-material/ClearAllRounded";
import SearchRounded from "@mui/icons-material/SearchRounded";
import VerticalAlignBottomRounded from "@mui/icons-material/VerticalAlignBottomRounded";
import WarningAmberRounded from "@mui/icons-material/WarningAmberRounded";
import WrapTextRounded from "@mui/icons-material/WrapTextRounded";
import { followLines, type ApiError } from "../api/client.ts";
import { ActionIcon, CopyButton, StatusChip } from "./ui.tsx";
import { MONO } from "../theme.ts";
import { lineLevel } from "../lib/logLines.ts";

const MAX_LINES = 10000;

type StreamState = "connecting" | "live" | "ended" | "error";

/**
 * A live log: service, build or kitout's own. New lines arrive over server-sent events; the view
 * keeps the newest 10 000 lines and follows the end until you scroll up.
 *
 * The log area is dark in both colour schemes: it is a terminal, and ERROR/WARN colours read
 * the same everywhere.
 */
export function LogViewer({ stream, title, height, actions }: {
  /** Path of the SSE stream, from logStreams in api/client.ts. */
  stream: string;
  title: ReactNode;
  /** CSS height of the whole viewer, e.g. "100vh" in a log window. */
  height: string;
  actions?: ReactNode;
}) {
  const [lines, setLines] = useState<string[]>([]);
  const [state, setState] = useState<StreamState>("connecting");
  const [error, setError] = useState<string>();
  const [filter, setFilter] = useState("");
  const [problemsOnly, setProblemsOnly] = useState(false);
  const [follow, setFollow] = useState(true);
  const [wrap, setWrap] = useState(false);
  const pending = useRef<string[]>([]);
  const box = useRef<HTMLPreElement>(null);

  useEffect(() => {
    setLines([]);
    setState("connecting");
    // lines arrive in bursts (a stack trace is hundreds); render them once per frame
    let frame = 0;
    const flush = () => {
      frame = 0;
      const batch = pending.current;
      pending.current = [];
      setLines((current) => {
        const next = current.concat(batch);
        return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next;
      });
    };
    const stop = followLines(stream, (line) => {
      setState("live");
      pending.current.push(line);
      if (!frame) {
        frame = requestAnimationFrame(flush);
      }
    }, (err?: ApiError) => {
      setState(err ? "error" : "ended");
      setError(err?.message);
    });
    // an empty log sends nothing: once connected without error, it is live
    const connected = window.setTimeout(() => setState((s) => (s === "connecting" ? "live" : s)), 1500);
    return () => {
      stop();
      cancelAnimationFrame(frame);
      window.clearTimeout(connected);
    };
  }, [stream]);

  const shown = useMemo(() => {
    const needle = filter.toLowerCase();
    return lines.filter((l) => (!problemsOnly || lineLevel(l) === "error" || lineLevel(l) === "warn")
      && (!needle || l.toLowerCase().includes(needle)));
  }, [lines, filter, problemsOnly]);

  useEffect(() => {
    if (follow && box.current) {
      box.current.scrollTop = box.current.scrollHeight;
    }
  }, [shown, follow]);

  const onScroll = () => {
    const el = box.current;
    if (el) {
      const atEnd = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
      if (atEnd !== follow) {
        setFollow(atEnd);
      }
    }
  };

  const status = {
    connecting: { label: "Connecting", color: "info" as const, busy: true },
    live: { label: "Live", color: "success" as const },
    ended: { label: "Stream ended", color: "default" as const },
    error: { label: "Not connected", color: "error" as const },
  }[state];

  return (
    <Paper variant="outlined" sx={{ height, display: "flex", flexDirection: "column", overflow: "hidden" }}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ px: 1.5, py: 1, alignItems: "center", flexWrap: "wrap", borderBottom: 1, borderColor: "divider" }}>
        <Box sx={{ minWidth: 0, mr: 1 }}>{title}</Box>
        <StatusChip look={status} title={state === "error" ? error : undefined} />
        <Chip variant="outlined" label={filter || problemsOnly ? `${shown.length} of ${lines.length} lines` : `${lines.length} lines`} />
        <Box sx={{ flexGrow: 1 }} />
        <TextField type="search" placeholder="Filter lines" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ width: 220 }}
          slotProps={{
            input: { startAdornment: <InputAdornment position="start"><SearchRounded fontSize="small" /></InputAdornment> },
            htmlInput: { "aria-label": "Filter lines" },
          }} />
        <Tooltip title="Only errors and warnings">
          <ToggleButton value="problems" size="small" selected={problemsOnly} onChange={() => setProblemsOnly(!problemsOnly)}
            aria-label="Only errors and warnings">
            <WarningAmberRounded fontSize="small" />
          </ToggleButton>
        </Tooltip>
        <Tooltip title="Wrap long lines">
          <ToggleButton value="wrap" size="small" selected={wrap} onChange={() => setWrap(!wrap)} aria-label="Wrap long lines">
            <WrapTextRounded fontSize="small" />
          </ToggleButton>
        </Tooltip>
        <CopyButton text={shown.join("\n")} label="Copy the shown lines" />
        <ActionIcon label="Clear the view (the log file stays)" onClick={() => setLines([])}>
          <ClearAllRounded fontSize="small" />
        </ActionIcon>
        {actions}
      </Stack>
      <Box sx={{ position: "relative", flexGrow: 1, minHeight: 0 }}>
        <Box component="pre" ref={box} onScroll={onScroll} tabIndex={0} aria-label="Log output"
          sx={{
            m: 0, height: "100%", overflow: "auto", p: 1.5,
            fontFamily: MONO, fontSize: 12.5, lineHeight: 1.55,
            bgcolor: "#0d1117", color: "#c9d1d9",
            whiteSpace: wrap ? "pre-wrap" : "pre", overflowWrap: wrap ? "anywhere" : "normal",
            "& > div:hover": { bgcolor: "rgba(110, 118, 129, 0.15)" },
            "& .ln-kitout": { color: "#a5b4fc", fontWeight: 600 },
            "& .ln-error": { color: "#ff7b72" },
            "& .ln-warn": { color: "#e3b341" },
            "&:focus-visible": { outline: "2px solid", outlineColor: "primary.main", outlineOffset: -2 },
          }}>
          {shown.length === 0 && (
            <Box component="div" sx={{ color: "#8b949e" }}>
              {state === "connecting" ? "Connecting…" : lines.length === 0 ? "No lines yet. New lines appear here as they are written." : "No line matches the filter."}
            </Box>
          )}
          {shown.map((l, i) => {
            const level = lineLevel(l);
            return <div key={i} className={level ? `ln-${level}` : undefined}>{l || " "}</div>;
          })}
        </Box>
        {!follow && (
          <Button variant="contained" size="small" startIcon={<VerticalAlignBottomRounded />} onClick={() => setFollow(true)}
            sx={{ position: "absolute", right: 24, bottom: 16 }}>
            Jump to newest
          </Button>
        )}
      </Box>
    </Paper>
  );
}
