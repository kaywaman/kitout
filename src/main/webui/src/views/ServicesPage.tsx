import { useState } from "react";
import Alert from "@mui/material/Alert";
import Badge from "@mui/material/Badge";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import FormControlLabel from "@mui/material/FormControlLabel";
import Link from "@mui/material/Link";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import TuneOutlined from "@mui/icons-material/TuneOutlined";
import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import BuildOutlined from "@mui/icons-material/BuildOutlined";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import FolderOpenOutlined from "@mui/icons-material/FolderOpenOutlined";
import LocalOfferOutlined from "@mui/icons-material/LocalOfferOutlined";
import PlayArrowRounded from "@mui/icons-material/PlayArrowRounded";
import RestartAltRounded from "@mui/icons-material/RestartAltRounded";
import StopRounded from "@mui/icons-material/StopRounded";
import SettingsOutlined from "@mui/icons-material/SettingsOutlined";
import SyncRounded from "@mui/icons-material/SyncRounded";
import { api } from "../api/client.ts";
import { RUN_MODES, runModesOf, type RunMode, type ServiceView } from "../api/types.ts";
import { ActionIcon, AppDialog, ErrorAlert, HealthChip, Mono, PageHeader, Section, StateChip, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { plural, seconds, since } from "../lib/format.ts";
import { pageHref, serviceLogHref } from "../lib/route.ts";
import { blocksRemoval, canChangeMode, canRestart, canStart, canStop, healthLook, managedByKitout, needsTag, stateLook, canBuild } from "../lib/status.ts";
import { BuildDialog } from "./BuildDialog.tsx";
import { FolderPicker } from "./FolderPicker.tsx";
import { PullDialog } from "./PullDialog.tsx";
import { TagPicker } from "./TagPicker.tsx";
import { EnvDialog } from "./EnvDialog.tsx";
import { ServiceOptionsDialog } from "./ServiceOptionsDialog.tsx";
import { MONO } from "../theme.ts";

const modeLabel = (mode: string) => RUN_MODES.find((m) => m.value === mode)?.label ?? mode;
const openLog = (s: ServiceView) => window.open(serviceLogHref(s.id), `kitout-log-${s.id}`, "width=1100,height=720");

/**
 * The stack at a glance. With a group selected, services are shown in start order:
 * the services that start together, then the BFFs, which start once they are up.
 */
export function ServicesPage({ workspace }: { workspace: Workspace }) {
  const { services, servicesLoaded, servicesError, group, refreshServices } = workspace;
  const [showAll, setShowAll] = useState(false);
  const [buildFor, setBuildFor] = useState<ServiceView | null>(null);
  const [removeFor, setRemoveFor] = useState<ServiceView | null>(null);
  const [removeAll, setRemoveAll] = useState(false);
  const [picking, setPicking] = useState(false);
  const [pulling, setPulling] = useState(false);
  const [tagFor, setTagFor] = useState<ServiceView | null>(null);
  const [envFor, setEnvFor] = useState<ServiceView | null>(null);
  const [optionsFor, setOptionsFor] = useState<ServiceView | null>(null);
  const changed = async () => {
    await refreshServices();
    await workspace.refreshGroups();
  };

  if (!servicesLoaded) {
    return <ErrorAlert message={servicesError?.message} />;
  }
  if (services.length === 0) {
    return <GettingStarted onAdded={changed} />;
  }

  const byId = new Map(services.map((s) => [s.id, s]));
  const members = group ? group.members.map((id) => byId.get(id)).filter((s): s is ServiceView => !!s) : [];
  const others = services.filter((s) => !group?.members.includes(s.id));
  const bffIds = group?.bffs ?? [];
  const bffs = members.filter((s) => bffIds.includes(s.id));
  const tierOne = members.filter((s) => !bffIds.includes(s.id));
  const shown = group ? [...members, ...(showAll ? others : [])] : services;
  const rowProps = { onBuild: setBuildFor, onRemove: setRemoveFor, onTag: setTagFor, onEnv: setEnvFor, onOptions: setOptionsFor,
    onChanged: refreshServices, bffIds };

  return (
    <>
      <PageHeader title={group ? group.name : "All services"} subtitle={<Summary services={shown} sequential={!!group?.sequentialStart} />}>
        {group && others.length > 0 && (
          <Button color="inherit" onClick={() => setShowAll(!showAll)}>
            {showAll ? "Hide other services" : `Show ${others.length} other services`}
          </Button>
        )}
        <Button variant="contained" startIcon={<SyncRounded />} onClick={() => setPulling(true)}>Pull</Button>
        <Button variant="outlined" startIcon={<AddRounded />} onClick={() => setPicking(true)}>Add projects</Button>
        <Button color="error" startIcon={<DeleteOutlined />} onClick={() => setRemoveAll(true)}>Remove all</Button>
      </PageHeader>
      <ErrorAlert message={servicesError?.message} />
      <RunModeBar services={shown} onChanged={refreshServices} />

      {group ? (
        <>
          <ServiceTable title={bffs.length ? "Starts first" : "Services"} services={tierOne} {...rowProps} />
          {bffs.length > 0 && (
            <ServiceTable title={bffs.length === 1 ? "Starts when those are up" : "Start together when those are up"} services={bffs} {...rowProps} />
          )}
          {showAll && <ServiceTable title="Not in this group" services={others} {...rowProps} />}
        </>
      ) : (
        <ServiceTable title="Services" services={services} {...rowProps} />
      )}

      <BuildDialog service={buildFor} onClose={() => setBuildFor(null)} />
      <EnvDialog service={envFor} onClose={() => setEnvFor(null)} onSaved={() => void refreshServices()} />
      <ServiceOptionsDialog service={optionsFor} onClose={() => setOptionsFor(null)} onSaved={() => void refreshServices()} />
      <TagPicker service={tagFor} onClose={() => setTagFor(null)} onChosen={() => void refreshServices()} />
      <PullDialog open={pulling} services={shown} onClose={() => setPulling(false)} onPulled={() => void changed()} />
      <FolderPicker open={picking} onClose={() => setPicking(false)} onAdded={() => void changed()} />
      <RemoveDialog service={removeFor ? byId.get(removeFor.id) ?? removeFor : null} onClose={() => setRemoveFor(null)}
        onRemoved={changed} onStopped={refreshServices} />
      <RemoveAllDialog open={removeAll} services={services} groups={workspace.groups.filter((g) => !g.suggested).length}
        onClose={() => setRemoveAll(false)} onRemoved={changed} onStopped={refreshServices} />
    </>
  );
}

/** "3 running · 1 failed · 2 stopped": what needs attention, before reading any row. */
function Summary({ services, sequential }: { services: ServiceView[]; sequential: boolean }) {
  const count = (state: string) => services.filter((s) => managedByKitout(s) && s.state === state).length;
  const ide = services.filter((s) => !managedByKitout(s)).length;
  const unhealthy = services.filter((s) => s.state === "RUNNING" && s.health === "UNHEALTHY").length;
  const untagged = services.filter((s) => needsTag(s)).length;
  const items: { label: string; color: "success" | "error" | "warning" | "info" | "default" }[] = [
    { label: `${count("RUNNING")} running`, color: "success" },
    ...(unhealthy ? [{ label: `${unhealthy} unhealthy`, color: "warning" as const }] : []),
    ...(count("FAILED") ? [{ label: `${count("FAILED")} failed`, color: "error" as const }] : []),
    ...(count("STARTING") ? [{ label: `${count("STARTING")} starting`, color: "info" as const }] : []),
    { label: `${count("STOPPED")} stopped`, color: "default" },
    ...(untagged ? [{ label: `${untagged} without image tag`, color: "warning" as const }] : []),
    ...(ide ? [{ label: `${ide} in your IDE`, color: "default" as const }] : []),
  ];
  return (
    <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
      {items.map((i) => <Chip key={i.label} variant="soft" color={i.color} label={i.label} />)}
      {sequential && <Typography variant="body2" sx={{ color: "text.secondary", ml: 0.5 }}>Services in this group start one after another.</Typography>}
    </Stack>
  );
}

/** One choice for how every service on this page starts. Running services change at their next start. */
function RunModeBar({ services, onChanged }: { services: ServiceView[]; onChanged: () => Promise<void> }) {
  const [mode, setMode] = useState<RunMode>("IMAGE");
  const { busy, run } = useAction();
  if (services.length < 2) {
    return null;
  }
  const apply = () => void run("mode", () => api.setRunMode(mode, services.map((s) => s.id)),
    (r) => `${plural(r.changed, "service")} now start from: ${modeLabel(mode)}.`
      + (r.running > 0 ? ` ${plural(r.running, "running service")} keep their current process until restarted.` : "")
      + (r.skipped?.length ? ` Kept: ${r.skipped.join("; ")}.` : ""))
    .then(() => onChanged());
  return (
    <Paper variant="outlined" component="form" onSubmit={(e) => { e.preventDefault(); apply(); }}
      sx={{ px: 2, py: 1.25, mb: 2.5, display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Start all {plural(services.length, "service")} on this page from
      </Typography>
      <TextField select value={mode} onChange={(e) => setMode(e.target.value as RunMode)} sx={{ minWidth: 200 }}
        slotProps={{ htmlInput: { "aria-label": "Run mode for all services on this page" } }}>
        {RUN_MODES.map((m) => <MenuItem key={m.value} value={m.value}>{m.label}</MenuItem>)}
      </TextField>
      <Button type="submit" variant="outlined" loading={busy === "mode"}>Apply to all</Button>
    </Paper>
  );
}

function ServiceTable({ title, services, bffIds, onBuild, onRemove, onTag, onEnv, onOptions, onChanged }: {
  title: string;
  services: ServiceView[];
  bffIds: string[];
  onOptions: (s: ServiceView) => void;
  onBuild: (s: ServiceView) => void;
  onTag: (s: ServiceView) => void;
  onEnv: (s: ServiceView) => void;
  onRemove: (s: ServiceView) => void;
  onChanged: () => Promise<void>;
}) {
  if (services.length === 0) {
    return null;
  }
  return (
    <Section title={title} dense>
      <TableContainer>
        <Table size="small" aria-label={title}>
          <TableHead>
            <TableRow>
              <TableCell>Service</TableCell>
              <TableCell sx={{ width: 80 }}>Port</TableCell>
              <TableCell sx={{ width: 200 }}>Runs from</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right" sx={{ width: 250 }}><Box component="span" sx={visuallyHidden}>Actions</Box></TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {services.map((s) => (
              <ServiceRow key={s.id} service={s} isBff={bffIds.includes(s.id)} onBuild={onBuild} onRemove={onRemove} onTag={onTag} onEnv={onEnv}
                onOptions={onOptions} onChanged={onChanged} />
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Section>
  );
}

function ServiceRow({ service: s, isBff, onBuild, onRemove, onTag, onEnv, onOptions, onChanged }: {
  service: ServiceView;
  isBff: boolean;
  onOptions: (s: ServiceView) => void;
  onBuild: (s: ServiceView) => void;
  onTag: (s: ServiceView) => void;
  onEnv: (s: ServiceView) => void;
  onRemove: (s: ServiceView) => void;
  onChanged: () => Promise<void>;
}) {
  const { busy, run } = useAction();
  const managed = managedByKitout(s);
  const failed = s.state === "FAILED";

  const act = (key: string, call: () => Promise<unknown>, done: string) =>
    void run(key, call, done).then(() => onChanged());
  const stop = () => act("stop", () => api.stopService(s.id), `Stopped ${s.name}`);
  const start = () => act("start", () => api.startService(s.id), `Starting ${s.name}`);
  const restart = () => act("restart", () => api.restartService(s.id), `Restarting ${s.name}`);
  const changeMode = (mode: RunMode) =>
    act("mode", () => api.updateService(s.id, { mode }), `${s.name} now runs from: ${modeLabel(mode)}`);

  return (
    <TableRow hover sx={{ "& > td": { verticalAlign: "top" } }}>
      <TableCell sx={(t) => ({ maxWidth: 340, boxShadow: failed ? `inset 3px 0 0 ${(t.vars ?? t).palette.error.main}` : undefined })}>
        <Stack direction="row" spacing={0.75} sx={{ alignItems: "center" }}>
          <Typography sx={{ fontWeight: 650 }}>{s.name}</Typography>
          {s.version && <Mono muted title="Project version, from its build file">{s.version}</Mono>}
          {s.type !== "quarkus" && <Tag label={s.typeLabel} title={TYPE_HINT[s.type] ?? s.typeLabel} />}
          {isBff && <Tag label="BFF" color="primary" title="Entry point of the group: starts once the others are up" />}
          {!s.healthCheck && <Tag label="no health check" title="Counts as started when its port answers; the readiness check is not asked (Options)" />}
        </Stack>
        <Box sx={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }} title={s.path}>
          <Mono muted>{s.fullPath ?? s.path}</Mono>
        </Box>
      </TableCell>
      <TableCell>
        <Mono title={!s.hasPort ? "No port: it counts as running while its process lives. Set one under Options."
          : s.portOverride ? "Set under Options" : s.portAdvisory ? "Read from the config files or guessed; it may differ (set the real one under Options)" : undefined}>
          {s.hasPort ? `:${s.port}${s.portAdvisory && !s.portOverride ? "?" : ""}` : "—"}
        </Mono>
      </TableCell>
      <TableCell>
        <Tooltip title={canChangeMode(s) ? "" : "Stop the service to change where it runs from"}>
          <TextField select fullWidth value={s.mode} disabled={!canChangeMode(s)} onChange={(e) => changeMode(e.target.value as RunMode)}
            slotProps={{ htmlInput: { "aria-label": `Run ${s.name} from` } }}>
            {runModesOf(s).map((m) => <MenuItem key={m.value} value={m.value}>{m.label}</MenuItem>)}
          </TextField>
        </Tooltip>
        {s.mode === "IMAGE" && (
          <Box sx={{ mt: 0.75 }}>
            {s.imageTag || s.image ? (
              <Tooltip title={`${s.image ?? ""} · click to choose another tag`}>
                <Chip variant="soft" color="primary" icon={<LocalOfferOutlined />} onClick={() => onTag(s)}
                  label={
                    <>
                      <Box component="span" sx={{ fontFamily: "inherit", fontSize: "0.7rem", fontWeight: 600, letterSpacing: "0.04em",
                        textTransform: "uppercase", opacity: 0.7, mr: 0.75 }}>Tag</Box>
                      <Box component="span" sx={{ fontFamily: MONO, fontWeight: 600 }}>{s.imageTag ?? "custom image"}</Box>
                    </>
                  }
                  sx={{ maxWidth: "100%", height: 26, borderRadius: 1.5, border: 1, borderColor: "rgba(var(--mui-palette-primary-mainChannel) / 0.28)",
                    "& .MuiChip-label": { overflow: "hidden", textOverflow: "ellipsis" } }}
                  aria-label={`Image tag of ${s.name}: ${s.imageTag ?? "custom image"}. Choose another`} />
              </Tooltip>
            ) : (
              <Chip variant="soft" color="warning" icon={<LocalOfferOutlined />} label="Choose tag" onClick={() => onTag(s)}
                aria-label={`Choose an image tag for ${s.name}`} />
            )}
          </Box>
        )}
      </TableCell>
      <TableCell>
        <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center", mb: 0.5 }}>
          <StateChip state={s.state}
            title={s.state === "STARTING" ? `Waiting for ${s.healthPath} on port ${s.healthPort} (up to ${s.startTimeoutSeconds} s)` : undefined} />
          <HealthChip service={s} title={`Readiness check: ${s.healthPath} on port ${s.healthPort}`} />
        </Stack>
        {detailText(s) && (
          <Typography variant="body2" sx={{
            color: failed ? "error.main" : s.health === "UNHEALTHY" && s.state === "RUNNING" ? "warning.main" : "text.primary",
          }}>
            {detailText(s)}
          </Typography>
        )}
        {s.state === "RUNNING" && (s.startupMillis !== null || s.startedAt) && (
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            {[s.startupMillis !== null ? `started in ${seconds(s.startupMillis)}` : "", s.startedAt ? `up for ${since(s.startedAt)}` : ""]
              .filter(Boolean).join(" · ")}
          </Typography>
        )}
        {s.mode === "IMAGE" && s.image && (
          <Box sx={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", maxWidth: 360 }} title={s.image}>
            <Mono muted>{s.image}</Mono>
          </Box>
        )}
      </TableCell>
      <TableCell align="right">
        <Stack direction="row" spacing={0.25} sx={{ justifyContent: "flex-end", alignItems: "center" }}>
          {canStart(s) && (
            <Tooltip title={needsTag(s) ? "Choose an image tag first" : ""}>
              <span>
                <Button variant="contained" startIcon={<PlayArrowRounded />} loading={busy === "start"} loadingPosition="start" onClick={start}
                  disabled={needsTag(s)} sx={{ mr: 0.5 }}>
                  Start
                </Button>
              </span>
            </Tooltip>
          )}
          {canStop(s) && (
            <Button variant="outlined" color={failed ? "error" : "inherit"} startIcon={<StopRounded />} loading={busy === "stop"}
              loadingPosition="start" onClick={stop} sx={{ mr: 0.5 }}
              title={failed ? "Stops what is left of the failed start, so you can start it again or remove it" : undefined}>
              Stop
            </Button>
          )}
          {managed && s.state === "STOPPING" && (
            <Button variant="outlined" color="inherit" loading loadingPosition="start" startIcon={<StopRounded />} sx={{ mr: 0.5 }}>
              Stopping
            </Button>
          )}
          {!managed && <Tag label="IDE" title="Runs in your IDE; kitout does not start or stop it" />}
          {managed && (
            <ActionIcon label={failed ? `Start ${s.name} again` : `Restart ${s.name}`} disabled={!canRestart(s) || busy !== null}
              why="Restart applies to a running or failed service" onClick={restart}>
              <RestartAltRounded fontSize="small" />
            </ActionIcon>
          )}
          {managed && (
            <ActionIcon label={`Log of ${s.name} (new window)`} onClick={() => openLog(s)}>
              <ArticleOutlined fontSize="small" />
            </ActionIcon>
          )}
          <ActionIcon label={`Environment variables of ${s.name}${s.envCount ? ` (${s.envCount})` : ""}`} onClick={() => onEnv(s)}>
            <Badge badgeContent={s.envCount} color="primary" max={99} slotProps={{ badge: { sx: { height: 16, minWidth: 16, fontSize: 10 } } }}>
              <TuneOutlined fontSize="small" />
            </Badge>
          </ActionIcon>
          <ActionIcon label={`Options of ${s.name}: folders, docker options, health check${optionCount(s) ? ` (${optionCount(s)})` : ""}`}
            onClick={() => onOptions(s)}>
            <Badge badgeContent={optionCount(s)} color="primary" max={99} slotProps={{ badge: { sx: { height: 16, minWidth: 16, fontSize: 10 } } }}>
              <SettingsOutlined fontSize="small" />
            </Badge>
          </ActionIcon>
          <ActionIcon label={`Build ${s.name}`} disabled={!canBuild(s)}
            why={`${s.name} is in IDE mode: kitout leaves it and its build to your IDE`} onClick={() => onBuild(s)}>
            <BuildOutlined fontSize="small" />
          </ActionIcon>
          <ActionIcon label={`Remove ${s.name} from kitout`} color="error" disabled={blocksRemoval(s)}
            why={`Stop ${s.name} before removing it`} onClick={() => onRemove(s)}>
            <DeleteOutlined fontSize="small" />
          </ActionIcon>
        </Stack>
      </TableCell>
    </TableRow>
  );
}

/** What each type's tag says on hover. */
const TYPE_HINT: Record<string, string> = {
  "spring-boot": "Spring Boot: profile via SPRING_PROFILES_ACTIVE, health from /actuator, the runnable jar for local runs",
  java: "A plain Java app: its runnable jar; port and health check only if you set a port under Options",
  node: "A Node project: started with its npm script (npm install first if needed); not built by kitout",
  image: "Image only: named in the team file, runs from the registry; no checkout on this machine",
};

/** How many container options a service has: its folders, its own docker options, a switched-off health check. */
function optionCount(s: ServiceView): number {
  return s.volumes.length + (s.dockerArgs ? 1 : 0) + (s.healthCheck ? 0 : 1);
}

/**
 * The status sentence under the chips, when it adds something: "Healthy" next to a Healthy chip
 * does not, a DOWN check or a timeout does.
 */
function detailText(s: ServiceView): string {
  if (!managedByKitout(s) && s.state === "STOPPED") {
    return "Start it in your IDE; kitout shows its state.";
  }
  const shown = [stateLook(s.state).label, healthLook(s)?.label].filter(Boolean).map((l) => l!.toLowerCase());
  return s.detail && !shown.includes(s.detail.toLowerCase()) ? s.detail : "";
}

function RemoveDialog({ service, onClose, onRemoved, onStopped }: {
  service: ServiceView | null;
  onClose: () => void;
  onRemoved: () => Promise<void>;
  onStopped: () => Promise<void>;
}) {
  const { busy, run } = useAction();
  const running = service !== null && blocksRemoval(service);
  const remove = async () => {
    if (!service) {
      return;
    }
    const ok = await run("remove", async () => { await api.removeService(service.id); return true; }, `Removed ${service.name}`);
    if (ok) {
      onClose();
      await onRemoved();
    }
  };
  const stop = async () => {
    if (service) {
      await run("stop", () => api.stopService(service.id), `Stopped ${service.name}`);
      await onStopped();
    }
  };
  return (
    <AppDialog
      open={service !== null}
      title={`Remove ${service?.name ?? ""} from kitout?`}
      onClose={onClose}
      actions={
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          {running && <Button variant="outlined" startIcon={<StopRounded />} loading={busy === "stop"} onClick={() => void stop()}>Stop {service?.name}</Button>}
          <Button variant="contained" color="error" disabled={running} loading={busy === "remove"} onClick={() => void remove()}>
            Remove {service?.name}
          </Button>
        </>
      }
    >
      {running && <Alert severity="warning">{service?.name} is {service?.state.toLowerCase()}. Stop it first, then remove it.</Alert>}
      <Typography>Use this for libraries, archived projects and anything that is not a service you run.</Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.5, "& li": { mb: 0.5 } }}>
        <li>It disappears from this list and from your groups.</li>
        <li>Future scans skip its folder. You can show it again in <Link href={pageHref("settings")}>Settings</Link>.</li>
        <li>Nothing on disk is changed or deleted.</li>
      </Box>
    </AppDialog>
  );
}

function RemoveAllDialog({ open, services, groups, onClose, onRemoved, onStopped }: {
  open: boolean;
  services: ServiceView[];
  groups: number;
  onClose: () => void;
  onRemoved: () => Promise<void>;
  onStopped: () => Promise<void>;
}) {
  const [confirmed, setConfirmed] = useState(false);
  const { busy, run } = useAction();
  const running = services.filter(blocksRemoval);
  const total = services.length;

  const close = () => {
    setConfirmed(false);
    onClose();
  };
  const remove = async () => {
    const result = await run("all", () => api.removeAllServices(), (r) => `Removed ${plural(r.removed, "service")}`);
    if (result) {
      close();
      await onRemoved();
    }
  };
  const stopAll = async () => {
    await run("stop", async () => {
      for (const s of running) {
        await api.stopService(s.id);
      }
    }, `Stopped ${plural(running.length, "service")}`);
    await onStopped();
  };

  return (
    <AppDialog
      open={open}
      title={`Remove all ${plural(total, "service")}?`}
      onClose={close}
      actions={
        <>
          <Button color="inherit" onClick={close}>Cancel</Button>
          {running.length > 0 && (
            <Button variant="outlined" startIcon={<StopRounded />} loading={busy === "stop"} onClick={() => void stopAll()}>
              Stop {plural(running.length, "service")}
            </Button>
          )}
          <Button variant="contained" color="error" disabled={!confirmed || running.length > 0} loading={busy === "all"} onClick={() => void remove()}>
            Remove all {plural(total, "service")}
          </Button>
        </>
      }
    >
      {running.length > 0 ? (
        <Alert severity="warning">
          kitout still runs {plural(running.length, "service")}. Stop {running.length === 1 ? "it" : "them"} first, so no process
          is left behind that kitout no longer shows.
          <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap", mt: 1 }}>
            {running.map((s) => <Chip key={s.id} variant="outlined" label={s.name} />)}
          </Stack>
        </Alert>
      ) : (
        <Alert severity="error" variant="outlined">This resets kitout's list of services. You start again by choosing a folder.</Alert>
      )}
      <Box component="ul" sx={{ m: 0, pl: 2.5, "& li": { mb: 0.5 } }}>
        <li>All {plural(total, "service")}{groups > 0 ? `, your ${plural(groups, "saved group")}` : ""} and the workspace folders are forgotten.</li>
        <li>Projects you removed one by one are no longer hidden.</li>
        <li>Your git, build and doctor settings stay. Nothing on disk is changed or deleted.</li>
      </Box>
      <FormControlLabel
        control={<Checkbox checked={confirmed} disabled={running.length > 0} onChange={(e) => setConfirmed(e.target.checked)} />}
        label="I understand that all services are removed from kitout"
      />
    </AppDialog>
  );
}

function GettingStarted({ onAdded }: { onAdded: () => Promise<void> }) {
  const [picking, setPicking] = useState(false);
  const steps = [
    {
      title: "Tell kitout your git host",
      body: <>Add the git host (GitHub, GitLab or any git server) in <Link href={pageHref("settings")}>Settings</Link>. kitout needs it to clone projects and find registry images.</>,
    },
    {
      title: "Get the projects",
      body: (
        <>
          Choose the folder that holds the projects you already have. kitout finds every Quarkus project in it and sets them up.
          Or <Link href={pageHref("clone")}>clone a group</Link>.
          <Box sx={{ mt: 1.5 }}>
            <Button variant="contained" startIcon={<FolderOpenOutlined />} onClick={() => setPicking(true)}>Choose folder</Button>
          </Box>
        </>
      ),
    },
    {
      title: "Check your machine",
      body: <>The <Link href={pageHref("doctor")}>doctor</Link> checks Docker, the registry login, your JDK and git access, and names the fix for anything missing.</>,
    },
  ];
  return (
    <>
      <PageHeader title="Set up your workspace" subtitle={<Typography sx={{ color: "text.secondary" }}>Three steps, a few minutes.</Typography>} />
      <Stack spacing={1.5} sx={{ maxWidth: 760 }}>
        {steps.map((step, i) => (
          <Paper key={step.title} variant="outlined" sx={{ p: 2, display: "flex", gap: 2 }}>
            <Box sx={{
              width: 28, height: 28, flexShrink: 0, borderRadius: "50%", display: "grid", placeItems: "center",
              bgcolor: "primary.main", color: "primary.contrastText", fontWeight: 700, fontSize: 14,
            }}>
              {i + 1}
            </Box>
            <Box>
              <Typography variant="h2" component="h2" sx={{ mb: 0.5 }}>{step.title}</Typography>
              <Typography component="div" variant="body2" sx={{ color: "text.secondary" }}>{step.body}</Typography>
            </Box>
          </Paper>
        ))}
      </Stack>
      <FolderPicker open={picking} start="~" onClose={() => setPicking(false)} onAdded={() => void onAdded()} />
    </>
  );
}

const visuallyHidden = {
  border: 0, clip: "rect(0 0 0 0)", height: "1px", margin: -1, overflow: "hidden", padding: 0, position: "absolute", width: "1px", whiteSpace: "nowrap",
} as const;
