import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import { api, type ApiError } from "../api/client.ts";
import type { ServiceView, VolumeView } from "../api/types.ts";
import { ActionIcon, AppDialog, ErrorAlert, Mono } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { blankVolume, cleanVolumes, volumeProblem } from "../lib/serviceOptions.ts";

/** Quarkus and Spring Boot read a profile; plain Java, Node and image-only services do not (as far as kitout knows). */
const usesProfile = (type: string) => type === "quarkus" || type === "spring-boot";

/**
 * Running and container options of one service: its profile, port and npm script, host folders mounted into its container, extra docker run
 * options, and whether kitout asks its readiness check. The backend checks everything again and
 * answers with the sentence to show.
 */
export function ServiceOptionsDialog({ service, onClose, onSaved }: { service: ServiceView | null; onClose: () => void; onSaved: () => void }) {
  const [volumes, setVolumes] = useState<VolumeView[]>([]);
  const [dockerArgs, setDockerArgs] = useState("");
  const [healthCheck, setHealthCheck] = useState(true);
  const [profile, setProfile] = useState("dev");
  const [port, setPort] = useState("");
  const [script, setScript] = useState("");
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  useEffect(() => {
    if (!service) {
      return;
    }
    setVolumes(service.volumes.length ? service.volumes.map((v) => ({ ...v })) : []);
    setDockerArgs(service.dockerArgs ?? "");
    setHealthCheck(service.healthCheck);
    setProfile(service.runtimeProfile);
    setPort(service.portOverride ? String(service.portOverride) : "");
    setScript(service.script ?? "");
    setError(undefined);
  }, [service]);

  if (!service) {
    return null;
  }

  const image = service.mode === "IMAGE";
  const running = service.state === "RUNNING" || service.state === "STARTING";
  const problem = volumes.map(volumeProblem).find((p) => p !== undefined)
    ?? (port !== "" && !(Number(port) >= 1 && Number(port) <= 65535) ? "A port is a number from 1 to 65535; leave it empty for the detected one." : undefined)
    ?? (usesProfile(service.type) && !/^[A-Za-z0-9_.-]+(,[A-Za-z0-9_.-]+)*$/.test(profile.trim())
      ? "A profile is letters, digits, '.', '-' and '_' (Spring Boot: several joined by commas)." : undefined);
  const change = (i: number, patch: Partial<VolumeView>) => setVolumes(volumes.map((v, j) => (j === i ? { ...v, ...patch } : v)));

  const save = async () => {
    setError(undefined);
    const saved = await run("save", async () => {
      try {
        return await api.updateService(service.id, {
          volumes: cleanVolumes(volumes), dockerArgs,
          healthCheck: healthCheck !== service.healthCheck ? healthCheck : undefined,   // unchanged: the team's choice stays in force
          runtimeProfile: usesProfile(service.type) ? profile.trim() : undefined,
          port: port === "" ? 0 : Number(port),
          script: service.type === "node" ? script : undefined,
        });
      } catch (e) {
        setError((e as ApiError).message);   // shown next to what was typed, too
        throw e;
      }
    }, `Saved the options of ${service.name}${running ? "; restart it to use them" : ""}`);
    if (saved) {
      onSaved();
      onClose();
    }
  };

  return (
    <AppDialog open maxWidth="md" title={`Options of ${service.name}`} onClose={onClose}
      actions={
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          <Button variant="contained" loading={busy === "save"} disabled={problem !== undefined} onClick={() => void save()}>Save options</Button>
        </>
      }>
      <ErrorAlert message={error} />

      <Box>
        <Typography variant="subtitle2" sx={{ fontWeight: 650, mb: 1 }}>Running</Typography>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>
          {usesProfile(service.type) && (
            <TextField label="Profile" value={profile} onChange={(e) => setProfile(e.target.value)} sx={{ width: 200 }}
              helperText={service.type === "spring-boot" ? "spring.profiles.active, e.g. dev or qa" : "quarkus.profile, e.g. dev or qa"}
              slotProps={{ htmlInput: { spellCheck: false } }} />
          )}
          <TextField label="Port" value={port} onChange={(e) => setPort(e.target.value.replace(/[^0-9]/g, ""))} sx={{ width: 200 }}
            placeholder={service.hasPort && !service.portOverride ? String(service.port) : "none"}
            helperText={service.portOverride ? "Yours; empty: the detected one" : service.hasPort ? `Detected: ${service.port}${service.portAdvisory ? " (a guess)" : ""}` : "No port detected"}
            slotProps={{ htmlInput: { inputMode: "numeric", "aria-label": `Port of ${service.name}` } }} />
          {service.type === "node" && (
            <TextField select label="npm script" value={script || service.script || ""} onChange={(e) => setScript(e.target.value)}
              sx={{ width: 200 }} helperText="npm run …">
              {service.scripts.map((sc) => <MenuItem key={sc} value={sc}>{sc}</MenuItem>)}
            </TextField>
          )}
        </Stack>
      </Box>

      <Box>
        <FormControlLabel disabled={!service.healthPath}
          control={<Switch checked={healthCheck && !!service.healthPath} onChange={(e) => setHealthCheck(e.target.checked)} />}
          label={service.healthPath
            ? <>Ask the readiness check <Mono muted>{service.healthPath}</Mono> on port {service.healthPort}</>
            : <>Readiness check: this {service.typeLabel} project has none, so kitout watches {service.hasPort ? `port ${service.port}` : "its process"}</>} />
        <Typography variant="body2" sx={{ color: "text.secondary", ml: 6 }}>
          {healthCheck
            ? "The service counts as started at the first healthy answer; afterwards a DOWN check shows as Unhealthy."
            : `Switched off: the service counts as started once port ${service.port} answers. For a BFF whose health endpoint is blocked.`}
        </Typography>
      </Box>

      <Box>
        <Typography variant="subtitle2" sx={{ fontWeight: 650 }}>Folders in the container</Typography>
        <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
          {image ? "Mounted when it runs as a registry image (docker run --mount type=bind). The folder must exist when the service starts."
            : "Used when it runs as a registry image; it now runs from a local build or your IDE."}
        </Typography>
        <Stack spacing={1}>
          {volumes.map((v, i) => (
            <Stack key={i} direction={{ xs: "column", sm: "row" }} spacing={1} sx={{ alignItems: { sm: "center" } }}>
              <TextField label="On this machine" placeholder="~/certs" value={v.hostPath} sx={{ flex: 1 }}
                onChange={(e) => change(i, { hostPath: e.target.value })} error={volumeProblem(v) !== undefined && v.hostPath === ""} />
              <TextField label="In the container" placeholder="/deployments/certs" value={v.containerPath} sx={{ flex: 1 }}
                onChange={(e) => change(i, { containerPath: e.target.value })}
                error={volumeProblem(v) !== undefined && v.containerPath !== "" && !v.containerPath.startsWith("/")} />
              <FormControlLabel sx={{ mr: 0 }} label="Read-only"
                control={<Checkbox size="small" checked={v.readOnly} onChange={(e) => change(i, { readOnly: e.target.checked })} />} />
              <ActionIcon label={`Remove the folder ${v.hostPath || i + 1}`} color="error" onClick={() => setVolumes(volumes.filter((_, j) => j !== i))}>
                <DeleteOutlined fontSize="small" />
              </ActionIcon>
            </Stack>
          ))}
          {problem && <Typography variant="body2" sx={{ color: "error.main" }}>{problem}</Typography>}
          <Box>
            <Button startIcon={<AddRounded />} onClick={() => setVolumes([...volumes, blankVolume()])}>Add folder</Button>
          </Box>
        </Stack>
      </Box>

      <Box>
        <Typography variant="subtitle2" sx={{ fontWeight: 650 }}>Extra docker run options</Typography>
        <TextField fullWidth multiline minRows={2} value={dockerArgs} onChange={(e) => setDockerArgs(e.target.value)}
          placeholder="--memory 1g --add-host db.local:10.0.0.5" sx={{ mt: 0.75 }}
          slotProps={{ htmlInput: { "aria-label": `Extra docker run options for ${service.name}`, spellCheck: false, style: { fontFamily: "monospace" } } }} />
        <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.5 }}>
          Options only, quoted as in a shell; kitout adds them before the image. It sets the container name, label, --network host and --rm
          itself, and environment variables belong under the service's environment, so those are refused with the reason.
        </Typography>
      </Box>
    </AppDialog>
  );
}
