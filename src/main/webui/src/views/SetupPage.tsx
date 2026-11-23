import { useEffect, useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import FormControlLabel from "@mui/material/FormControlLabel";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import CheckCircleRounded from "@mui/icons-material/CheckCircleRounded";
import CloudDownloadOutlined from "@mui/icons-material/CloudDownloadOutlined";
import ErrorRounded from "@mui/icons-material/ErrorRounded";
import FolderOpenOutlined from "@mui/icons-material/FolderOpenOutlined";
import RadioButtonUncheckedRounded from "@mui/icons-material/RadioButtonUncheckedRounded";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import WarningRounded from "@mui/icons-material/WarningRounded";
import { api } from "../api/client.ts";
import type { CloneJobView, RequirementView, SetupView, TeamSourceView, TeamValueView } from "../api/types.ts";
import { TeamSourceForm } from "../components/TeamSourceForm.tsx";
import { ErrorAlert, Mono, PageHeader, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { ago } from "../lib/format.ts";
import { foundText, ordered } from "../lib/requirements.ts";
import { pageHref } from "../lib/route.ts";
import { kindLabel, nextStep, todo } from "../lib/setup.ts";
import { RequirementCard } from "./RequirementsScreen.tsx";
import { CreateTeamForm } from "./CreateTeamForm.tsx";

type Mark = "done" | "problem" | "warning" | "todo";

/**
 * The first call: everything kitout checks, at once and in order (team file, machine, projects,
 * secrets), and the two actions that follow a passed machine check: load the team's projects
 * already on this machine, or clone the missing ones. Nothing outside the team file is loaded.
 */
export function SetupPage({ workspace, onChanged }: { workspace: Workspace; onChanged: () => void }) {
  const [setup, setSetup] = useState<SetupView | null>(null);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  const load = () => api.setup().then((s) => { setSetup(s); setError(undefined); }).catch((e: Error) => setError(e.message));
  useEffect(() => {
    void load();
  }, []);

  const changed = (s?: SetupView) => {
    if (s) {
      setSetup(s);
    } else {
      void load();
    }
    onChanged();
    void workspace.refreshServices();
    void workspace.refreshGroups();
  };

  if (!setup) {
    return <><PageHeader title="Set up kitout" /><ErrorAlert message={error} /></>;
  }

  const step = nextStep(setup);
  const team = setup.team;
  const fileOk = team.configured && team.valid && setup.fileChecks.every((c) => c.ok);
  const failed = setup.requirements.results.filter((r) => r.outcome === "FAIL").length;
  const warned = setup.requirements.results.filter((r) => r.outcome === "WARN" && !r.id.startsWith("secret:")).length;
  const services = setup.projects.filter((p) => p.kind === "service");
  const loaded = services.filter((p) => p.state === "CONFIGURED").length;

  return (
    <>
      <PageHeader title="Set up kitout"
        subtitle={<Typography variant="body2" sx={{ color: "text.secondary" }}>
          kitout checks everything your team's file asks for. When the machine check passes, load or clone the team's projects.
        </Typography>}>
        <Button variant="outlined" startIcon={<RefreshRounded />} loading={busy === "check"} disabled={!team.configured}
          onClick={() => void run("check", () => api.setupCheck(), "Checked everything again").then(changed)}>Check again</Button>
      </PageHeader>
      <ErrorAlert message={error} />
      {step !== "done" && (
        <Alert severity={setup.requirements.blocked || !team.valid ? "error" : "warning"} sx={{ mb: 2.5 }}>
          <Box sx={{ fontWeight: 650, mb: 0.5 }}>Not ready yet. What to do:</Box>
          <Box component="ol" sx={{ m: 0, pl: 2.5 }}>{todo(setup).map((t) => <li key={t}>{t}</li>)}</Box>
        </Alert>
      )}
      {step === "done" && (
        <Alert severity="success" sx={{ mb: 2.5 }}
          action={<Button color="inherit" href={pageHref("services")}>Go to services</Button>}>
          Setup complete: {loaded} services of {team.team} are ready.
        </Alert>
      )}

      <Stack spacing={2}>
        <Step n={1} title="Team file" active={step === "team"}
          mark={!team.configured ? "todo" : fileOk ? "done" : team.valid ? "warning" : "problem"}
          summary={team.configured ? <><Mono>{team.location}</Mono>{team.loadedAt && <> · read {ago(team.loadedAt)}</>}</> : "Where your team keeps kitout-team.json"}>
          {!team.configured ? <NoTeamYet setup={setup} onDone={() => changed()} /> : (
            <>
              <Stack spacing={0.5}>
                {setup.fileChecks.map((c) => (
                  <Box key={c.id}>
                    <CheckLine mark={c.ok ? "done" : "problem"} text={c.name} />
                    {c.problems.map((p) => <Typography key={p} variant="body2" sx={{ ml: 4, color: "error.main" }}>{p}</Typography>)}
                  </Box>
                ))}
              </Stack>
              {team.warnings.length > 0 && (
                <Alert severity="info" sx={{ mt: 1.5 }}>
                  Works, but worth fixing in the team file:
                  <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>{team.warnings.map((w) => <li key={w}>{w}</li>)}</Box>
                </Alert>
              )}
              {!fileOk && (
                <Typography variant="body2" sx={{ mt: 1.5 }}>
                  {team.valid ? "kitout keeps using the last good copy. " : ""}Ask whoever maintains the team file to fix this, then <b>Check again</b>.
                  {" "}Wrong file? Change it in <a href={pageHref("settings")}>Settings, Team setup</a>.
                </Typography>
              )}
              {team.valid && (
                <Typography variant="body2" sx={{ color: "text.secondary", mt: 1 }}>
                  {team.team}: {team.services} services, groups {team.groups.join(", ") || "none"}, compose environments {team.composeEnvironments.join(", ") || "none"}.
                </Typography>
              )}
            </>
          )}
        </Step>

        <Step n={2} title="This machine" active={step === "machine"} disabled={!team.valid}
          mark={!team.valid ? "todo" : failed > 0 ? "problem" : warned > 0 ? "warning" : "done"}
          summary={!team.valid ? "Checked once the team file is valid"
            : failed > 0 ? `${failed} requirement${failed === 1 ? "" : "s"} not met: kitout builds and starts nothing until fixed`
              : `All requirements met${warned ? `, ${warned} warning${warned === 1 ? "" : "s"}` : ""} · checked ${ago(setup.requirements.checkedAt)}`}>
          <MachineChecks results={setup.requirements.results.filter((r) => !r.id.startsWith("secret:"))} />
        </Step>

        <Step n={3} title="Projects" active={step === "projects"} disabled={!setup.ready}
          mark={!setup.ready ? "todo" : step === "projects" ? "todo" : setup.projects.some((p) => p.state === "NOT_LOADABLE") ? "warning" : "done"}
          summary={!setup.ready ? "After the machine check passes" : `${loaded} of ${services.length} services loaded · clone folder ${setup.workspaceRoot}`}>
          <Projects setup={setup} onChanged={changed} />
        </Step>

        <Step n={4} title="Your secrets" active={step === "secrets"} disabled={!team.valid}
          mark={!team.valid ? "todo" : setup.missingSecrets.length > 0 ? "warning" : "done"}
          summary={team.secrets.length === 0 ? "The team file asks for none"
            : setup.missingSecrets.length > 0 ? `${setup.missingSecrets.length} to enter; services that need them do not start until then` : "All entered"}>
          <Stack spacing={1.5}>
            {team.secrets.map((s) => <SecretRow key={`${s.scope}/${s.name}`} secret={s} onSaved={() => changed()} />)}
          </Stack>
        </Step>
      </Stack>
    </>
  );
}

function Step({ n, title, mark, summary, active, disabled, children }: {
  n: number; title: string; mark: Mark; summary: ReactNode; active: boolean; disabled?: boolean; children: ReactNode;
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2, borderColor: active ? "primary.main" : "divider", borderWidth: active ? 2 : 1, opacity: disabled ? 0.6 : 1 }}
      aria-label={`Step ${n}: ${title}`} component="section">
      <Stack direction="row" spacing={1.25} sx={{ alignItems: "center" }}>
        <MarkIcon mark={mark} />
        <Typography sx={{ fontWeight: 700 }}>{n}. {title}</Typography>
        <Typography variant="body2" sx={{ color: "text.secondary", minWidth: 0 }}>{summary}</Typography>
      </Stack>
      {!disabled && <Box sx={{ mt: 1.5, ml: 4 }}>{children}</Box>}
    </Paper>
  );
}

function MarkIcon({ mark }: { mark: Mark }) {
  switch (mark) {
    case "done": return <CheckCircleRounded color="success" aria-label="done" />;
    case "problem": return <ErrorRounded color="error" aria-label="needs fixing" />;
    case "warning": return <WarningRounded color="warning" aria-label="warning" />;
    default: return <RadioButtonUncheckedRounded color="disabled" aria-label="to do" />;
  }
}

function CheckLine({ mark, text, detail }: { mark: Mark; text: ReactNode; detail?: ReactNode }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
      <Box sx={{ display: "flex", "& svg": { fontSize: 18 } }}><MarkIcon mark={mark} /></Box>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>{text}</Typography>
      {detail && <Typography variant="body2" component="span" sx={{ color: "text.secondary" }}>{detail}</Typography>}
    </Stack>
  );
}

/** First call without a team file: use the team's, or (when allowed) build one from scratch. */
function NoTeamYet({ setup, onDone }: { setup: SetupView; onDone: () => void }) {
  const [creating, setCreating] = useState(false);
  if (creating) {
    return <CreateTeamForm onDone={onDone} onCancel={() => setCreating(false)} />;
  }
  return (
    <Stack spacing={2}>
      {setup.presetProblem && <Alert severity="warning">{setup.presetProblem}</Alert>}
      <FollowTeam suggested={setup.suggested} onDone={onDone} />
      {setup.canCreate && (
        <Box sx={{ borderTop: 1, borderColor: "divider", pt: 2 }}>
          <Typography variant="body2" sx={{ mb: 1 }}>
            <b>No team file yet?</b> For example, you are starting a team with an infrastructure compose file (database, Kafka, tools)
            and no services or CI/CD yet.
          </Typography>
          <Button variant="outlined" onClick={() => setCreating(true)}>Build a team setup</Button>
        </Box>
      )}
    </Stack>
  );
}

function FollowTeam({ suggested, onDone }: { suggested: TeamSourceView | null; onDone: () => void }) {
  const { busy, run } = useAction();
  return (
    <TeamSourceForm suggested={suggested} submitLabel="Check" busy={busy === "use"} autoFocus
      onSubmit={(source) => void run("use", () => api.useTeam(source), "Read the team file").then((s) => s && onDone())} />
  );
}

/** Every requirement, met or not: the developer sees what was checked, not only what failed. */
function MachineChecks({ results }: { results: RequirementView[] }) {
  if (results.length === 0) {
    return <Typography variant="body2" sx={{ color: "text.secondary" }}>The team file sets no requirements.</Typography>;
  }
  const list = ordered<RequirementView>(results);
  return (
    <Stack spacing={1}>
      {list.filter((r) => r.outcome !== "PASS").map((r) => <RequirementCard key={r.id} r={r} />)}
      <Stack spacing={0.5}>
        {list.filter((r) => r.outcome === "PASS").map((r) => (
          <CheckLine key={r.id} mark="done" text={r.name} detail={<Mono muted>{r.detail || foundText(r)}</Mono>} />
        ))}
      </Stack>
    </Stack>
  );
}

function Projects({ setup, onChanged }: { setup: SetupView; onChanged: () => void }) {
  const [light, setLight] = useState(false);
  const [job, setJob] = useState<CloneJobView | null>(null);
  const [outside, setOutside] = useState<string[]>([]);
  const { busy, run } = useAction();
  const missing = setup.projects.filter((p) => p.state === "MISSING").length;
  const local = setup.projects.filter((p) => p.kind === "service" && p.state === "LOCAL").length;

  const loadLocal = async () => {
    const r = await run("local", () => api.setupLoadLocal(),
      (x) => `Loaded ${x.found.length} of the team's projects${x.outsideTeam.length ? `; left out ${x.outsideTeam.length} not in the team file` : ""}`);
    if (r) {
      setOutside(r.outsideTeam);
      onChanged();
    }
  };

  const addToTeam = async (folders: string[], key = `add-${folders[0]}`) => {
    const r = await run(key, () => api.addTeamServices(folders),
      (x) => x.added.length ? `Added ${x.added.join(", ")} to the team setup and loaded ${x.loaded.length}` : "Already in the team setup");
    if (r) {
      setOutside((o) => o.filter((f) => !folders.includes(f)));
      onChanged();
    }
  };

  useEffect(() => {
    if (!job || job.finished) {
      return;
    }
    const t = window.setTimeout(() => {
      api.cloneJob(job.id).then((j) => {
        setJob(j);
        if (j.finished) {
          void loadLocal();   // cloned repositories become services right away
        }
      }).catch(() => undefined);
    }, 1500);
    return () => window.clearTimeout(t);
  }, [job]);

  const state = (s: string) => ({
    CONFIGURED: <Tag label="loaded" color="success" />,
    LOCAL: <Tag label="on this machine" color="primary" />,
    MISSING: <Tag label="not on this machine" color="warning" />,
    NOT_LOADABLE: <Tag label="cannot be loaded" color="error" />,
    REMOVED: <Tag label="removed by you" />,
  }[s] ?? <Tag label={s} />);

  return (
    <Stack spacing={1.5}>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Only the repositories in the team file are loaded or cloned. Other projects on this machine stay out of kitout.
      </Typography>
      <Table size="small" aria-label="The team's projects">
        <TableHead>
          <TableRow>
            <TableCell>Repository</TableCell>
            <TableCell>State</TableCell>
            <TableCell>Folder</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {setup.projects.map((p) => (
            <TableRow key={p.repo}>
              <TableCell>
                <Mono>{p.repo}</Mono>
                {p.kind === "service" && p.description && (
                  <Typography variant="caption" sx={{ color: p.state === "NOT_LOADABLE" ? "error.main" : "text.secondary", display: "block", maxWidth: 560 }}>
                    {p.description}
                  </Typography>
                )}
                {p.kind !== "service" && (
                  <Typography variant="caption" sx={{ color: "text.secondary", display: "block" }}>
                    {kindLabel(p.kind)}{p.description ? `: ${p.description}` : ""}
                  </Typography>
                )}
              </TableCell>
              <TableCell>{state(p.state)}</TableCell>
              <TableCell><Mono muted>{p.path}</Mono></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Button variant={missing === 0 ? "contained" : "outlined"} startIcon={<FolderOpenOutlined />} loading={busy === "local"}
          onClick={() => void loadLocal()}>
          Load local projects{local ? ` (${local})` : ""}
        </Button>
        {!setup.localOnly && <>
        <Button variant={missing > 0 ? "contained" : "outlined"} startIcon={<CloudDownloadOutlined />} disabled={missing === 0}
          loading={busy === "clone" || (job !== null && !job.finished)}
          onClick={() => void run("clone", () => api.setupClone(light), `Cloning ${missing} repositor${missing === 1 ? "y" : "ies"}`).then((j) => j && setJob(j))}>
          Clone missing{missing ? ` (${missing})` : ""}
        </Button>
        <FormControlLabel control={<Switch checked={light} onChange={(e) => setLight(e.target.checked)} />} label="Light clones (latest commit only)" />
        </>}
      </Stack>
      {job && (
        <Stack spacing={0.25}>
          {job.items.map((i) => (
            <Typography key={i.fullPath} variant="body2" sx={{ color: i.status === "FAILED" ? "error.main" : "text.secondary" }}>
              <Mono>{i.fullPath}</Mono> {i.status.toLowerCase()}{i.message ? `: ${i.message}` : ""}
            </Typography>
          ))}
        </Stack>
      )}
      {setup.projects.filter((p) => p.kind === "service").length === 0 && setup.editable && outside.length === 0 && (
        <Typography variant="body2" sx={{ color: "text.secondary" }}>
          Your team setup has no services yet. <b>Load local projects</b> lists the projects in your projects folder; add the
          ones that belong to the team.
        </Typography>
      )}
      {outside.length > 0 && (setup.editable ? (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>Projects found that are not in the team setup yet</Typography>
          {outside.map((o) => (
            <Stack key={o} direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Mono>{o}</Mono>
              <Button size="small" loading={busy === `add-${o}`} onClick={() => void addToTeam([o])}>Add to the team</Button>
            </Stack>
          ))}
          {outside.length > 1 && <Button size="small" variant="outlined" sx={{ mt: 1 }} loading={busy === "add-all"}
            onClick={() => void addToTeam(outside, "add-all")}>Add all {outside.length}</Button>}
        </Box>
      ) : (
        <Typography variant="body2" sx={{ color: "text.secondary" }}>
          Left out, not in the team file: {outside.map((o) => <Mono key={o}>{o} </Mono>)}
        </Typography>
      ))}
    </Stack>
  );
}

function SecretRow({ secret, onSaved }: { secret: TeamValueView; onSaved: () => void }) {
  const [value, setValue] = useState("");
  const { busy, run } = useAction();
  const label = secret.scope === "shared" ? secret.name : `${secret.name} (${secret.scope})`;
  return (
    <Stack component="form" direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "flex-start", maxWidth: 760 }}
      onSubmit={(e) => {
        e.preventDefault();
        void run("save", () => api.setTeamSecret(secret.name, secret.scope, value), `${secret.name} is in your keyring`)
          .then((s) => { if (s) { setValue(""); onSaved(); } });
      }}>
      <TextField label={label} type="password" autoComplete="off" value={value} onChange={(e) => setValue(e.target.value)}
        sx={{ flexGrow: 1, minWidth: 260 }} placeholder={secret.set ? "•••• stored; type to replace" : "Your value"}
        helperText={secret.set ? "Stored in your keyring, never shown again" : "Goes to your keyring; never shown or sent anywhere else"} />
      <Box sx={{ mt: 1 }}>{secret.set ? <Tag label="stored" color="success" /> : <Tag label="missing" color="warning" />}</Box>
      <Button type="submit" variant="outlined" disabled={!value} loading={busy === "save"} sx={{ mt: 0.25 }}>Save</Button>
    </Stack>
  );
}

