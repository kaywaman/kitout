import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { api } from "../api/client.ts";
import type { ResetOutcomeView, ResetPreviewView, ResetRequest } from "../api/types.ts";
import { AppDialog, ErrorAlert, Mono } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { bytes, plural } from "../lib/format.ts";

const DEFAULTS: ResetRequest = { settings: true, logs: true, builds: true, images: true, buildOutput: false };

/**
 * Start kitout over. Shows what each part would remove before anything happens; build output inside
 * the projects is off by default, because the IDE may be using it.
 */
export function ResetDialog({ open, onClose, onDone }: { open: boolean; onClose: () => void; onDone: (settingsReset: boolean) => void }) {
  const [preview, setPreview] = useState<ResetPreviewView | null>(null);
  const [choice, setChoice] = useState<ResetRequest>(DEFAULTS);
  const [confirmed, setConfirmed] = useState(false);
  const [outcome, setOutcome] = useState<ResetOutcomeView | null>(null);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  useEffect(() => {
    if (!open) {
      return;
    }
    setPreview(null);
    setOutcome(null);
    setConfirmed(false);
    setChoice(DEFAULTS);
    setError(undefined);
    api.resetPreview().then(setPreview).catch((e: Error) => setError(e.message));
  }, [open]);

  const any = choice.settings || choice.logs || choice.builds || choice.images || choice.buildOutput;
  const reset = async () => {
    const result = await run("reset", () => api.reset(choice), "kitout was reset");
    if (result) {
      setOutcome(result);
      onDone(choice.settings);
    }
  };

  const part = (key: keyof ResetRequest, label: string, detail: string) => (
    <Box key={key}>
      <FormControlLabel control={<Checkbox checked={choice[key]} onChange={(e) => setChoice({ ...choice, [key]: e.target.checked })} />}
        label={<Typography sx={{ fontWeight: 600 }}>{label}</Typography>} />
      <Typography variant="body2" sx={{ color: "text.secondary", ml: 4, mt: -0.5 }}>{detail}</Typography>
    </Box>
  );

  return (
    <AppDialog
      open={open}
      maxWidth="sm"
      title="Reset kitout"
      onClose={onClose}
      actions={outcome ? <Button variant="contained" onClick={onClose}>Done</Button> : (
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          <Button variant="contained" color="error" disabled={!preview || !any || !confirmed} loading={busy === "reset"} onClick={() => void reset()}>
            Reset
          </Button>
        </>
      )}
    >
      <ErrorAlert message={error} />
      {!preview && !error && <LinearProgress />}
      {preview && !outcome && (
        <>
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            Never touched: your repositories, compose files and the data of compose environments, your docker and git logins.
          </Typography>
          {preview.runningServices.length > 0 && (
            <Alert severity="warning">First stops {plural(preview.runningServices.length, "service")} kitout runs, and cancels running builds.</Alert>
          )}
          <Stack spacing={1}>
            {part("settings", "Settings", `Services, groups, folders, git and Docker settings (${preview.configFile}), run state, and ${plural(preview.secrets, "secret")} kitout keeps in the keyring.`)}
            {part("logs", "Logs", `${plural(preview.logFiles, "file")}, ${bytes(preview.logBytes)}: service logs, compose output, kitout's own log.`)}
            {part("builds", "Builds", `${plural(preview.builds, "build")} in the list and ${plural(preview.buildLogs, "build log")}.`)}
            {part("images", "Local images", preview.images.length === 0
              ? "No service images on this machine."
              : `${plural(preview.images.length, "service image")} kitout pulled, and leftover kitout containers.`)}
            {part("buildOutput", "Build output in the projects", preview.buildOutputs.length === 0
              ? "No target/ or build/ folders."
              : `target/ or build/ in ${plural(preview.buildOutputs.length, "project")}. Off by default: your IDE may be using it.`)}
          </Stack>
          {choice.images && preview.images.length > 0 && (
            <Box sx={{ maxHeight: 120, overflow: "auto" }}>{preview.images.map((i) => <Box key={i}><Mono muted>{i}</Mono></Box>)}</Box>
          )}
          <FormControlLabel control={<Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />}
            label="I understand that the chosen parts are removed and cannot be restored" />
        </>
      )}
      {outcome && (
        <>
          <Alert severity={outcome.problems.length ? "warning" : "success"}>
            {outcome.done.map((d) => <Box key={d}>{d}</Box>)}
          </Alert>
          {outcome.problems.length > 0 && (
            <Alert severity="info">
              Not removed:
              {outcome.problems.map((p) => <Box key={p}><Mono muted>{p}</Mono></Box>)}
            </Alert>
          )}
        </>
      )}
    </AppDialog>
  );
}
