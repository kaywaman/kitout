import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import InputAdornment from "@mui/material/InputAdornment";
import MenuItem from "@mui/material/MenuItem";
import TableSortLabel from "@mui/material/TableSortLabel";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import SearchRounded from "@mui/icons-material/SearchRounded";
import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import ConstructionOutlined from "@mui/icons-material/ConstructionOutlined";
import { api } from "../api/client.ts";
import { BuildOptions, DEFAULT_BUILD_OPTIONS, type BuildOptionValues, PROFILE } from "../components/BuildOptions.tsx";
import { useToasts } from "../components/Toasts.tsx";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { plural } from "../lib/format.ts";
import type { BuildView, CurrentBuildView } from "../api/types.ts";
import { ActionIcon, AppDialog, BuildChip, EmptyState, ErrorAlert, Mono, PageHeader, Section, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { usePolling } from "../hooks/usePolling.ts";
import { ago, bytes, duration } from "../lib/format.ts";
import { canBuild } from "../lib/status.ts";
import { filterBuilds, NO_FILTER, sortBuilds, type BuildFilter, type BuildSortKey, type SortDirection } from "../lib/builds.ts";
import { buildLogHref } from "../lib/route.ts";

const openLog = (b: BuildView) => window.open(buildLogHref(b.id), `kitout-build-${b.id}`, "width=1100,height=720");

/** Recent builds and the native build queue. */
export function BuildsPage({ workspace }: { workspace: Workspace }) {
  const builds = usePolling(api.builds, 1500);
  const list = builds.data ?? [];

  return (
    <>
      <PageHeader title="Builds"
        subtitle={<Typography variant="body2" sx={{ color: "text.secondary" }}>Native builds run one at a time; the others wait here in line.</Typography>} />
      <ErrorAlert message={builds.error?.message} />
      <BuildServicesSection workspace={workspace} active={list.filter((b) => b.status === "QUEUED" || b.status === "RUNNING")}
        onStarted={() => void builds.refresh()} />
      {builds.data && list.length === 0 && (
        <EmptyState title="No builds yet" icon={<ConstructionOutlined />}>
          Choose services above, or use the build button next to a service.
        </EmptyState>
      )}
      <CurrentBuildsSection />
      {list.length > 0 && <RecentBuilds list={list} onChanged={() => void builds.refresh()} />}
    </>
  );
}

/**
 * What can run now: the build output on disk, one per service and mode. Deleting it frees disk
 * space; output a running service uses, or a build is writing, stays.
 */
function CurrentBuildsSection() {
  const current = usePolling(api.currentBuilds, 5000);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirm, setConfirm] = useState(false);
  const { busy, run } = useAction();
  const toasts = useToasts();
  const list = current.data ?? [];
  const key = (c: CurrentBuildView) => `${c.serviceId}|${c.mode}`;
  const removable = list.filter((c) => c.removable);
  const chosen = removable.filter((c) => selected.has(key(c)));
  const allChecked = removable.length > 0 && removable.every((c) => selected.has(key(c)));
  const toggle = (k: string) => setSelected((cur) => {
    const next = new Set(cur);
    if (!next.delete(k)) {
      next.add(k);
    }
    return next;
  });

  const remove = async () => {
    const r = await run("delete", () => api.removeCurrentBuilds(chosen.map((c) => ({ serviceId: c.serviceId, mode: c.mode }))),
      (x) => (x.removed.length ? `Deleted the build output of ${x.removed.join(", ")}` : "Nothing deleted"));
    setConfirm(false);
    if (r) {
      r.kept.forEach((why) => toasts.error(why));
      setSelected(new Set());
      void current.refresh();
    }
  };

  return (
    <Section title="Current builds" dense
      description="What can run now: the build output in each project, one per service and mode. Every build overwrites it."
      action={
        <Button color="error" variant="outlined" startIcon={<DeleteOutlined />} disabled={chosen.length === 0} onClick={() => setConfirm(true)}>
          {chosen.length ? `Delete ${plural(chosen.length, "build")}` : "Delete"}
        </Button>
      }>
      <ErrorAlert message={current.error?.message} />
      {current.data && list.length === 0 && (
        <Typography variant="body2" sx={{ color: "text.secondary", p: 2 }}>No build output in your projects yet. Build a service above.</Typography>
      )}
      {list.length > 0 && (
        <TableContainer>
          <Table size="small" aria-label="Current builds">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox">
                  <Checkbox checked={allChecked} indeterminate={chosen.length > 0 && !allChecked} disabled={removable.length === 0}
                    onChange={() => setSelected(allChecked ? new Set() : new Set(removable.map(key)))}
                    slotProps={{ input: { "aria-label": "Select all current builds that can be deleted" } }} />
                </TableCell>
                <TableCell>Service</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell>Version</TableCell>
                <TableCell>Built</TableCell>
                <TableCell>Size</TableCell>
                <TableCell>Built by</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {list.map((c) => (
                <TableRow key={key(c)} hover selected={selected.has(key(c))}>
                  <TableCell padding="checkbox">
                    <Tooltip title={c.removable ? "" : `Cannot be deleted: ${c.usedBy}`}>
                      <span>
                        <Checkbox checked={selected.has(key(c)) && c.removable} disabled={!c.removable} onChange={() => toggle(key(c))}
                          slotProps={{ input: { "aria-label": `Delete the ${c.mode === "NATIVE" ? "native" : "JVM"} build of ${c.serviceName}` } }} />
                      </span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Typography sx={{ fontWeight: 650 }}>{c.serviceName}</Typography>
                    <Mono muted title={c.path}>{c.path}</Mono>
                  </TableCell>
                  <TableCell>
                    <Tag label={c.mode === "NATIVE" ? "Native" : "JVM"} />
                    {c.usedBy && <Box sx={{ mt: 0.5 }}><Tag label="in use" color="success" title={c.usedBy} /></Box>}
                  </TableCell>
                  <TableCell><Mono>{c.version ?? "–"}</Mono></TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}><Typography variant="body2">{ago(c.builtAt)}</Typography></TableCell>
                  <TableCell sx={{ whiteSpace: "nowrap" }}><Mono>{bytes(c.sizeBytes)}</Mono></TableCell>
                  <TableCell>
                    {c.attemptId ? <Mono muted>kitout · {c.attemptId}</Mono>
                      : <Tag label="outside kitout" title="Built in the IDE or a terminal, or before kitout kept a list: no log here" />}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <AppDialog open={confirm} title={`Delete ${plural(chosen.length, "build")}?`} onClose={() => setConfirm(false)}
        actions={<>
          <Button color="inherit" onClick={() => setConfirm(false)}>Keep them</Button>
          <Button color="error" variant="contained" loading={busy === "delete"} onClick={() => void remove()}>Delete</Button>
        </>}>
        <Typography variant="body2">
          Deletes the build output of {chosen.map((c) => `${c.serviceName} (${c.mode === "NATIVE" ? "native" : "JVM"})`).join(", ")} from the
          projects (target/quarkus-app or the native runner). Sources and other files stay. To run these services from a local build again,
          build them first.
        </Typography>
      </AppDialog>
    </Section>
  );
}

const COLUMNS: { key: BuildSortKey; label: string }[] = [
  { key: "service", label: "Service" },
  { key: "version", label: "Version" },
  { key: "build", label: "Build" },
  { key: "status", label: "Status" },
  { key: "started", label: "Started" },
  { key: "time", label: "Time" },
];

/**
 * The builds of this kitout session: sortable and filterable, and removable (with their logs) one by
 * one or all at once. Queued and running builds are cancelled first; the newest build of a running
 * service stays.
 */
function RecentBuilds({ list, onChanged }: { list: BuildView[]; onChanged: () => void }) {
  const [filter, setFilter] = useState<BuildFilter>(NO_FILTER);
  const [sort, setSort] = useState<{ key: BuildSortKey; dir: SortDirection }>({ key: "started", dir: "desc" });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const { busy, run } = useAction();
  const toasts = useToasts();

  const shown = sortBuilds(filterBuilds(list, filter), sort.key, sort.dir);
  const removable = shown.filter((b) => b.removable);
  const chosen = [...selected].filter((id) => list.some((b) => b.id === id && b.removable));
  const allChecked = removable.length > 0 && removable.every((b) => selected.has(b.id));
  const someChecked = removable.some((b) => selected.has(b.id));

  const toggle = (id: string) => setSelected((cur) => {
    const next = new Set(cur);
    if (!next.delete(id)) {
      next.add(id);
    }
    return next;
  });
  const toggleAll = () => setSelected((cur) => {
    const next = new Set(cur);
    removable.forEach((b) => (allChecked ? next.delete(b.id) : next.add(b.id)));
    return next;
  });
  const sortBy = (key: BuildSortKey) => setSort((cur) => ({ key, dir: cur.key === key && cur.dir === "asc" ? "desc" : "asc" }));

  const remove = async () => {
    const r = await run("remove", () => api.removeBuilds(chosen), (x) => `Removed ${plural(x.removed.length, "attempt")} and their logs`);
    if (r) {
      r.kept.forEach((why) => toasts.error(why));
      setSelected(new Set());
      onChanged();
    }
  };

  return (
    <Section title="Build attempts" dense
      description="The last 10 builds per service, with their logs. Each build overwrites the output of the one before, so only the newest succeeded attempt is a current build."
      action={
        <Button color="error" variant="outlined" startIcon={<DeleteOutlined />} disabled={chosen.length === 0} loading={busy === "remove"}
          onClick={() => void remove()}>
          {chosen.length ? `Remove ${plural(chosen.length, "attempt")}` : "Remove"}
        </Button>
      }>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ px: 2, pt: 2, pb: 1 }}>
        <TextField size="small" label="Filter" placeholder="Service, version, profile" value={filter.text}
          onChange={(e) => setFilter({ ...filter, text: e.target.value })} sx={{ minWidth: 240 }}
          slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchRounded fontSize="small" /></InputAdornment> } }} />
        <TextField select size="small" label="Status" value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })} sx={{ minWidth: 160 }}>
          <MenuItem value="">All</MenuItem>
          {["SUCCEEDED", "FAILED", "CANCELLED", "RUNNING", "QUEUED"].map((st) => <MenuItem key={st} value={st}>{st.charAt(0) + st.slice(1).toLowerCase()}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Mode" value={filter.mode} onChange={(e) => setFilter({ ...filter, mode: e.target.value })} sx={{ minWidth: 130 }}>
          <MenuItem value="">All</MenuItem>
          <MenuItem value="JVM">JVM</MenuItem>
          <MenuItem value="NATIVE">Native</MenuItem>
        </TextField>
        {(filter.text || filter.status || filter.mode) && (
          <Button color="inherit" onClick={() => setFilter(NO_FILTER)}>Clear ({shown.length} of {list.length})</Button>
        )}
      </Stack>
      <TableContainer>
        <Table size="small" aria-label="Build attempts">
          <TableHead>
            <TableRow>
              <TableCell padding="checkbox">
                <Tooltip title={removable.length ? "Select every finished attempt shown" : "Nothing here can be removed now"}>
                  <span>
                    <Checkbox checked={allChecked} indeterminate={someChecked && !allChecked} disabled={removable.length === 0} onChange={toggleAll}
                      slotProps={{ input: { "aria-label": "Select all removable attempts" } }} />
                  </span>
                </Tooltip>
              </TableCell>
              {COLUMNS.map((c) => (
                <TableCell key={c.key} sortDirection={sort.key === c.key ? sort.dir : false}>
                  <TableSortLabel active={sort.key === c.key} direction={sort.key === c.key ? sort.dir : "asc"} onClick={() => sortBy(c.key)}>
                    {c.label}
                  </TableSortLabel>
                </TableCell>
              ))}
              <TableCell align="right"><Box component="span" sx={{ position: "absolute", left: -9999 }}>Actions</Box></TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.length === 0 && (
              <TableRow><TableCell colSpan={8} sx={{ color: "text.secondary" }}>No build matches the filter.</TableCell></TableRow>
            )}
            {shown.map((b) => (
              <TableRow key={b.id} hover selected={selected.has(b.id)} sx={{ "& > td": { verticalAlign: "top" } }}>
                <TableCell padding="checkbox">
                  <Tooltip title={b.removable ? "" : `Cannot be removed: ${b.notRemovable}`}>
                    <span>
                      <Checkbox checked={selected.has(b.id) && b.removable} disabled={!b.removable} onChange={() => toggle(b.id)}
                        slotProps={{ input: { "aria-label": `Remove the attempt ${b.id} of ${b.serviceName}` } }} />
                    </span>
                  </Tooltip>
                </TableCell>
                <TableCell>
                  <Typography sx={{ fontWeight: 650 }}>{b.serviceName}</Typography>
                  <Mono muted>{b.id}</Mono>
                </TableCell>
                <TableCell><Mono>{b.version ?? "–"}</Mono></TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
                    <Tag label={b.mode === "NATIVE" ? "Native" : "JVM"} />
                    <Tag label={b.profile} color={b.prodProfile ? "warning" : "default"}
                      title={b.prodProfile ? "Production settings: the result may reach production systems if you run it" : undefined} />
                    {b.skipTests && <Tag label="tests skipped" />}
                  </Stack>
                </TableCell>
                <TableCell>
                  <BuildChip status={b.status} />
                  {b.outputNote && <Box sx={{ mt: 0.5 }}><Tag label="output replaced" title={b.outputNote} /></Box>}
                  {b.status === "QUEUED" && (
                    <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.5 }}>{queueText(b)}</Typography>
                  )}
                  {b.explanation && (
                    <Box sx={{ mt: 0.75, maxWidth: 520 }}>
                      <Typography variant="body2">{b.explanation.cause}</Typography>
                      <Typography variant="body2" sx={{ color: "text.secondary" }}>
                        <Box component="strong" sx={{ color: "text.primary" }}>Fix: </Box>{b.explanation.fix}
                      </Typography>
                    </Box>
                  )}
                </TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }}><Typography variant="body2">{ago(b.startedAt ?? b.createdAt)}</Typography></TableCell>
                <TableCell sx={{ whiteSpace: "nowrap" }}><Mono>{duration(b.startedAt, b.finishedAt)}</Mono></TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={0.5} sx={{ justifyContent: "flex-end", alignItems: "center" }}>
                    {(b.status === "QUEUED" || b.status === "RUNNING") && (
                      <Button color="error" loading={busy === b.id}
                        onClick={() => void run(b.id, () => api.cancelBuild(b.id), `Cancelled the build of ${b.serviceName}`).then(onChanged)}>
                        Cancel
                      </Button>
                    )}
                    <ActionIcon label="Build log (new window)" onClick={() => openLog(b)}>
                      <ArticleOutlined fontSize="small" />
                    </ActionIcon>
                  </Stack>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Section>
  );
}

/**
 * Build several services with one set of options. JVM builds all start at once; native builds
 * queue and run one after another (the backend enforces it, across UI and CLI).
 */
function BuildServicesSection({ workspace, active, onStarted }: {
  workspace: Workspace;
  active: BuildView[];
  onStarted: () => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [options, setOptions] = useState<BuildOptionValues>(DEFAULT_BUILD_OPTIONS);
  const { busy, run } = useAction();
  const toasts = useToasts();
  const services = workspace.services;
  const group = workspace.group;
  const activeFor = (id: string) => active.find((b) => b.serviceId === id);
  const buildable = services.filter(canBuild);
  const allChecked = buildable.length > 0 && buildable.every((s) => selected.has(s.id));

  const toggle = (id: string) => setSelected((cur) => {
    const next = new Set(cur);
    if (!next.delete(id)) {
      next.add(id);
    }
    return next;
  });

  const build = async () => {
    const result = await run("build", () => api.buildMany([...selected], options),
      (r) => r.started.length === 0 ? "Nothing new to build"
        : options.mode === "NATIVE" ? `Queued ${plural(r.started.length, "native build")}; they run one after another`
          : `Started ${plural(r.started.length, "build")}`);
    if (result) {
      result.skipped.forEach((why) => toasts.error(why));
      setSelected(new Set());
      onStarted();
    }
  };

  if (services.length === 0) {
    return null;
  }

  return (
    <Section title="Build services" dense
      description="Choose services and options. Every build starts from scratch (clean package). JVM builds run at the same time; native builds run one after another."
      action={
        <Button variant="contained" startIcon={<ConstructionOutlined />} disabled={selected.size === 0 || !PROFILE.test(options.profile)} loading={busy === "build"}
          onClick={() => void build()}>
          {options.mode === "NATIVE" ? `Queue ${plural(selected.size, "native build")}` : `Build ${plural(selected.size, "service")}`}
        </Button>
      }>
      <Box sx={{ px: 2, pt: 2, pb: 1 }}>
        <BuildOptions value={options} onChange={setOptions} layout="row" count={selected.size} />
      </Box>
      <Stack direction="row" spacing={1} sx={{ px: 2, py: 0.5, alignItems: "center", borderTop: 1, borderColor: "divider" }}>
        <FormControlLabel control={<Checkbox checked={allChecked} indeterminate={selected.size > 0 && !allChecked}
          onChange={() => setSelected(allChecked ? new Set() : new Set(buildable.map((s) => s.id)))} />}
          label={`Select all (${buildable.length})`} />
        {group && (
          <Button color="inherit" onClick={() => setSelected(new Set(group.members.filter((id) => buildable.some((s) => s.id === id))))}>
            Select the group {group.name}
          </Button>
        )}
      </Stack>
      <TableContainer sx={{ maxHeight: 360 }}>
        <Table size="small" stickyHeader aria-label="Services to build">
          <TableHead>
            <TableRow>
              <TableCell padding="checkbox" />
              <TableCell>Service</TableCell>
              <TableCell>Now</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {services.map((s) => {
              const running = activeFor(s.id);
              const ide = !canBuild(s);
              return (
                <TableRow key={s.id} hover onClick={() => !ide && toggle(s.id)} sx={{ cursor: ide ? "default" : "pointer", opacity: ide ? 0.6 : 1 }}>
                  <TableCell padding="checkbox">
                    <Tooltip title={ide ? `${s.name} is in IDE mode: kitout leaves it and its build to your IDE` : ""}>
                      <span>
                        <Checkbox checked={selected.has(s.id) && !ide} disabled={ide} onClick={(e) => e.stopPropagation()} onChange={() => toggle(s.id)}
                          slotProps={{ input: { "aria-label": `Build ${s.name}` } }} />
                      </span>
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.75} sx={{ alignItems: "baseline" }}>
                      <Typography sx={{ fontWeight: 650 }}>{s.name}</Typography>
                      {s.version && <Mono muted title="Project version, from its build file">{s.version}</Mono>}
                    </Stack>
                    <Mono muted>{s.fullPath ?? s.path}</Mono>
                  </TableCell>
                  <TableCell>{running ? <BuildChip status={running.status} /> : <Typography variant="body2" sx={{ color: "text.secondary" }}>Idle</Typography>}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
    </Section>
  );
}

function queueText(b: BuildView): string {
  if (b.waitingFor) {
    return `Waiting for ${b.waitingFor}`;
  }
  return b.queuePosition > 0 ? `In line, position ${b.queuePosition}` : "Queued";
}
