import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import PlayArrowRounded from "@mui/icons-material/PlayArrowRounded";
import StopRounded from "@mui/icons-material/StopRounded";
import { api, type ApiError } from "../api/client.ts";
import type { DoctorReportView, GroupStartView } from "../api/types.ts";
import { AppDialog, SeverityChip } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { useToasts } from "../components/Toasts.tsx";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { pageHref } from "../lib/route.ts";
import { ON_NAVBAR } from "../theme.ts";

/** On the navy top bar: a light outline that brightens on hover and focus. */
const SELECT_ON_NAVBAR = {
  minWidth: { xs: 130, sm: 220 },
  "& .MuiInputLabel-root": { color: ON_NAVBAR.muted },
  "& .MuiInputLabel-root.Mui-focused": { color: ON_NAVBAR.text },
  "& .MuiOutlinedInput-root": {
    color: ON_NAVBAR.text, height: 40, bgcolor: ON_NAVBAR.field,
    "& fieldset": { borderColor: ON_NAVBAR.outline },
    "&:hover fieldset": { borderColor: ON_NAVBAR.outlineHover },
    "&.Mui-focused fieldset": { borderColor: ON_NAVBAR.text, borderWidth: 1 },
  },
  "& .MuiSelect-icon": { color: ON_NAVBAR.muted },
} as const;

/** The main action: light blue on navy, the strongest thing in the bar. */
const START_ON_NAVBAR = {
  height: 40, px: 2, minWidth: 0, bgcolor: ON_NAVBAR.action, color: "surface.navbar",
  "&:hover": { bgcolor: ON_NAVBAR.actionHover },
  "& .MuiButton-startIcon": { mr: { xs: 0, sm: 1 }, ml: { xs: 0, sm: -0.5 } },
  "& .MuiCircularProgress-root": { color: "surface.navbar" },
} as const;

const STOP_ON_NAVBAR = {
  height: 40, px: 2, minWidth: 0, color: ON_NAVBAR.text, borderColor: ON_NAVBAR.outline,
  "& .MuiButton-startIcon": { mr: { xs: 0, sm: 1 }, ml: { xs: 0, sm: -0.5 } },
  "&:hover": { borderColor: ON_NAVBAR.outlineHover, bgcolor: ON_NAVBAR.hover },
} as const;

/** A button's text, hidden on phones (the icon stays, the aria-label names the action). */
const label = (text: string) => <Box component="span" sx={{ display: { xs: "none", sm: "inline" } }}>{text}</Box>;

/** Group picker and the two group actions, centred in the top bar and always visible. */
export function GroupBar({ workspace }: { workspace: Workspace }) {
  const { groups, group, selectGroup, refreshServices } = workspace;
  const { busy, run } = useAction();
  const toasts = useToasts();
  const [blocked, setBlocked] = useState<DoctorReportView | null>(null);
  const [progress, setProgress] = useState<GroupStartView | null>(null);

  // follow a running group start until it finishes
  useEffect(() => {
    if (!progress || progress.finished) {
      return;
    }
    const timer = window.setTimeout(async () => {
      try {
        setProgress(await api.groupStartProgress(progress.group));
        void refreshServices();
      } catch {
        setProgress(null);
      }
    }, 1000);
    return () => window.clearTimeout(timer);
  }, [progress, refreshServices]);

  const start = async (force: boolean) => {
    if (!group) {
      return;
    }
    setBlocked(null);
    try {
      setProgress(await api.startGroup(group.name, force));
      void refreshServices();
    } catch (e) {
      const err = e as ApiError;
      if (err.blockedByDoctor) {
        setBlocked(err.body as DoctorReportView);
      } else {
        toasts.error(err.message);
      }
    }
  };

  if (groups.length === 0) {
    return <Typography variant="body2" sx={{ color: ON_NAVBAR.muted }}>No groups yet</Typography>;
  }

  const starting = !!progress && !progress.finished;
  const lastMessage = progress?.messages[progress.messages.length - 1];

  return (
    <Stack direction="row" spacing={1.25} sx={{ alignItems: "center", minWidth: 0 }}>
      <TextField select label="Group" value={group?.name ?? ""} onChange={(e) => selectGroup(e.target.value)} sx={SELECT_ON_NAVBAR}>
        {groups.map((g) => (
          <MenuItem key={g.name} value={g.name}>
            {g.name}
            {g.suggested && <Chip variant="soft" label="suggested" sx={{ ml: 1, height: 18 }} />}
          </MenuItem>
        ))}
      </TextField>
      <Button variant="contained" startIcon={<PlayArrowRounded />} loading={starting} loadingPosition="start" onClick={() => void start(false)}
        aria-label={`Start group ${group?.name ?? ""}`} sx={START_ON_NAVBAR}>
        {label("Start group")}
      </Button>
      <Button variant="outlined" startIcon={<StopRounded />} loading={busy === "stop"} loadingPosition="start" sx={STOP_ON_NAVBAR}
        aria-label={`Stop group ${group?.name ?? ""}`}
        onClick={() => group && void run("stop", () => api.stopGroup(group.name), `Stopped ${group.name}`).then(() => refreshServices())}>
        {label("Stop group")}
      </Button>
      {progress && lastMessage && (
        <Tooltip title={progress.messages.join("\n")} slotProps={{ tooltip: { sx: { whiteSpace: "pre-line" } } }}>
          <Chip role="status" aria-live="polite" variant="soft" color={progress.finished ? "success" : "info"}
            icon={starting ? <CircularProgress size={10} thickness={6} color="inherit" /> : undefined}
            label={lastMessage}
            onDelete={progress.finished ? () => setProgress(null) : undefined}
            sx={{ maxWidth: 360, display: { xs: "none", xl: "inline-flex" }, bgcolor: ON_NAVBAR.chip, color: ON_NAVBAR.text,
              "& .MuiChip-icon": { color: "inherit", ml: "6px" }, "& .MuiChip-deleteIcon": { color: ON_NAVBAR.muted } }} />
        </Tooltip>
      )}

      <AppDialog
        open={blocked !== null}
        title={`The doctor found problems in ${group?.name ?? "this group"}`}
        onClose={() => setBlocked(null)}
        actions={
          <>
            <Button color="inherit" onClick={() => setBlocked(null)}>Cancel</Button>
            <Button variant="outlined" href={pageHref("doctor")} onClick={() => setBlocked(null)}>Open the doctor</Button>
            <Button variant="contained" color="error" onClick={() => void start(true)}>Start anyway</Button>
          </>
        }
      >
        <Typography>These errors usually make services fail to start. Fix them, or start anyway if you know why they appear.</Typography>
        <List dense disablePadding>
          {blocked?.results.filter((r) => r.severity === "ERROR").map((r, i) => (
            <ListItem key={i} disableGutters sx={{ display: "block" }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <SeverityChip severity={r.severity} />
                <Typography sx={{ fontWeight: 600 }}>{r.title}</Typography>
              </Stack>
              <Typography variant="body2" sx={{ mt: 0.5 }}>{r.detail}</Typography>
              {r.fix && <Typography variant="body2" sx={{ color: "text.secondary" }}>Fix: {r.fix}</Typography>}
            </ListItem>
          ))}
        </List>
      </AppDialog>
    </Stack>
  );
}
