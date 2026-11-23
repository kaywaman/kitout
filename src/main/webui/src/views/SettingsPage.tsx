import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import FormControlLabel from "@mui/material/FormControlLabel";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import FolderOpenOutlined from "@mui/icons-material/FolderOpenOutlined";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import RestartAltRounded from "@mui/icons-material/RestartAltRounded";
import VisibilityOutlined from "@mui/icons-material/VisibilityOutlined";
import { api } from "../api/client.ts";
import type { CredentialStatusView, EnvVarView, GroupView, HiddenProjectView, KitoutInfoView, RegistryStatusView, SettingsView, TeamStatusView, TeamValueView } from "../api/types.ts";
import { EnvEditor } from "../components/EnvEditor.tsx";
import { PROFILE } from "../components/BuildOptions.tsx";
import { imagePath } from "../lib/env.ts";
import { defaultRegistry, GIT_HOST_LABEL, gitHostKind, tokenPlaceholder, type GitHostKind } from "../lib/gitHost.ts";
import { MONO } from "../theme.ts";
import { ActionIcon, CopyButton, ErrorAlert, Mono, PageHeader, Section, StatusChip, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { GroupEditor } from "./GroupEditor.tsx";
import { ResetDialog } from "./ResetDialog.tsx";
import { FolderPicker } from "./FolderPicker.tsx";
import { pageHref } from "../lib/route.ts";
import { TeamSection } from "./TeamSection.tsx";

/** Git host, workspace, build and doctor settings; the token source; groups; removed projects; kitout's files. */
export function SettingsPage({ workspace, onTeamChanged }: { workspace: Workspace; onTeamChanged?: () => void }) {
  const [settings, setSettings] = useState<SettingsView | null>(null);
  const [team, setTeam] = useState<TeamStatusView | null>(null);
  const [roots, setRoots] = useState("");
  const [composeDirs, setComposeDirs] = useState("");
  const [error, setError] = useState<string>();
  const [picking, setPicking] = useState(false);
  const { busy, run } = useAction();

  const load = () => api.settings().then((s) => {
    setSettings(s);
    setRoots((s.workspaceRoots ?? []).join("\n"));
    setComposeDirs((s.docker?.composeDirs ?? []).join("\n"));
  }).catch((e: Error) => setError(e.message));

  const loadTeam = () => api.team().then(setTeam).catch(() => setTeam(null));
  useEffect(() => {
    void load();
    void loadTeam();
  }, []);

  /** Fields the team file sets: shown, not editable here. */
  const byTeam = team?.valid === true;
  const teamHelp = "Set by the team file (Team setup, above)";

  if (!settings) {
    return <><PageHeader title="Settings" /><ErrorAlert message={error} /></>;
  }

  const gitlab = settings.gitlab;
  const kind = gitHostKind(gitlab.provider, gitlab.host);
  const setGitlab = (change: Partial<SettingsView["gitlab"]>) => setSettings({ ...settings, gitlab: { ...gitlab, ...change } });
  const lines = (text: string) => text.split("\n").map((r) => r.trim()).filter(Boolean);
  const rootList = lines(roots);

  const save = async () => {
    const saved = await run("save", () => api.saveSettings({ ...settings, workspaceRoots: rootList, docker: { ...settings.docker, composeDirs: lines(composeDirs) } }), "Settings saved");
    if (saved) {
      setSettings(saved);
      void workspace.refreshServices();
    }
  };

  return (
    <>
      <PageHeader title="Settings">
        <Button variant="contained" loading={busy === "save"} onClick={() => void save()}>Save settings</Button>
      </PageHeader>

      <TeamSection onChanged={() => {
        void load();
        void loadTeam();
        void workspace.refreshServices();
        void workspace.refreshGroups();
        onTeamChanged?.();
      }} />

      <Box component="form" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Section title="Git" description="Where the team's repositories are. Any git server works; GitLab and GitHub add listing groups and checking the token.">
          <Stack spacing={2.5} sx={{ maxWidth: 640 }}>
            <TextField label="Git host" value={gitlab.host ?? ""} onChange={(e) => setGitlab({ host: e.target.value })}
              disabled={byTeam} helperText={byTeam ? teamHelp : undefined}
              placeholder="github.com, gitlab.example.com, git.example.com" slotProps={{ htmlInput: { spellCheck: false } }} />
            <Box>
              <Typography variant="body2" id="git-kind" sx={{ mb: 0.75, fontWeight: 600 }}>Kind of server</Typography>
              <ToggleButtonGroup exclusive size="small" color="primary" aria-labelledby="git-kind" disabled={byTeam}
                value={gitlab.provider || "auto"}
                onChange={(_, value: string | null) => value && setGitlab({ provider: value === "auto" ? "" : value })}>
                <ToggleButton value="auto" sx={{ px: 2 }}>From the name ({GIT_HOST_LABEL[gitHostKind("", gitlab.host)]})</ToggleButton>
                <ToggleButton value="gitlab" sx={{ px: 2 }}>GitLab</ToggleButton>
                <ToggleButton value="github" sx={{ px: 2 }}>GitHub</ToggleButton>
                <ToggleButton value="git" sx={{ px: 2 }}>Other git</ToggleButton>
              </ToggleButtonGroup>
              <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.75 }}>
                {kind === "GIT"
                  ? "Cloning, pulling and reading the team file work with git and your git login. Listing a group's repositories needs GitLab or GitHub."
                  : `Set it when the name does not say it, e.g. a GitLab at git.example.com.`}
              </Typography>
            </Box>
            <Box>
              <Typography variant="body2" id="clone-protocol" sx={{ mb: 0.75, fontWeight: 600 }}>Clone with</Typography>
              <ToggleButtonGroup exclusive size="small" color="primary" aria-labelledby="clone-protocol"
                value={gitlab.cloneProtocol === "ssh" ? "ssh" : "https"}
                onChange={(_, value: "https" | "ssh" | null) => value && setGitlab({ cloneProtocol: value })}>
                <ToggleButton value="https" sx={{ px: 2 }}>HTTPS (team standard)</ToggleButton>
                <ToggleButton value="ssh" sx={{ px: 2 }}>SSH</ToggleButton>
              </ToggleButtonGroup>
              <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.75 }}>
                {kind === "GITLAB"
                  ? "With HTTPS, one token serves git, VS Code and kitout. SSH users also need a token with read_api for listing groups."
                  : "With HTTPS, git's credential helper signs you in (GitHub: gh auth setup-git). With SSH, your SSH key does."}
              </Typography>
            </Box>
            <TextField label="Clone into" value={gitlab.workspaceRoot ?? ""} onChange={(e) => setGitlab({ workspaceRoot: e.target.value })}
              placeholder="~/work" helperText="The repository path is mirrored below this folder, e.g. ~/work/acme/shop/orders." />
            <TextField label="Container registry" value={gitlab.registryHost ?? ""} onChange={(e) => setGitlab({ registryHost: e.target.value })}
              placeholder={defaultRegistry(kind, gitlab.host ?? "") || "registry.example.com"} disabled={byTeam}
              helperText={byTeam ? teamHelp : `Where the service images come from, optionally with a path prefix (images.example.com/team). ${defaultRegistry(kind, gitlab.host ?? "") ? `Empty means ${defaultRegistry(kind, gitlab.host ?? "")}.` : "Set it: a plain git server has no registry of its own."} Log in to it below.`} />
            <Box>
              <TextField label="Image path in the registry" fullWidth value={gitlab.imagePath ?? "{namespace}/{project}"}
                onChange={(e) => setGitlab({ imagePath: e.target.value })} disabled={byTeam}
                slotProps={{ htmlInput: { spellCheck: false, style: { fontFamily: MONO } } }}
                helperText={<>For the project <code>{SAMPLE_PROJECT}</code>: <code>{gitlab.registryHost || "registry"}/{imagePath(gitlab.imagePath || "{namespace}/{project}", SAMPLE_PROJECT)}</code>.
                  {" "}Placeholders: {"{namespace}"} (acme/shop), {"{group}"} (acme), {"{project}"} (orders), {"{path}"} (acme/shop/orders).</>} />
              {!byTeam && <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                <Chip variant={gitlab.imagePath === "{namespace}/{project}" ? "filled" : "outlined"} label="With group or owner"
                  onClick={() => setGitlab({ imagePath: "{namespace}/{project}" })} />
                <Chip variant={gitlab.imagePath === "{project}" ? "filled" : "outlined"} label="Project only"
                  onClick={() => setGitlab({ imagePath: "{project}" })} />
              </Stack>}
            </Box>
          </Stack>
        </Section>

        <Section title="Workspace" description="The folders kitout searches for projects: Quarkus, Spring Boot, Java apps and Node frontends.">
          <Stack spacing={2} sx={{ maxWidth: 640 }}>
            <TextField label="Folders with your projects" multiline minRows={2} value={roots} onChange={(e) => setRoots(e.target.value)}
              placeholder="~/work" helperText="One per line." slotProps={{ htmlInput: { spellCheck: false } }} />
            <Stack direction="row" spacing={1}>
              <Button variant="outlined" startIcon={<FolderOpenOutlined />} onClick={() => setPicking(true)}>Add folder…</Button>
              <Button color="inherit" startIcon={<RefreshRounded />} loading={busy === "scan"}
                onClick={() => void run("scan", () => api.scan(rootList), (r) => `Found ${r.found.length} projects, ${r.added.length} new`)
                  .then(() => workspace.refreshServices())}>
                Find projects again
              </Button>
            </Stack>
          </Stack>
          <FolderPicker open={picking} onClose={() => setPicking(false)} onAdded={() => {
            void load();
            void workspace.refreshServices();
            void workspace.refreshGroups();
          }} />
        </Section>

        <Section title="Docker" description={<>Where your compose files are. Each <code>docker-compose-&lt;environment&gt;.yml</code> becomes an environment on the <Link href={pageHref("docker")}>Docker</Link> page.</>}>
          <TextField label="Folders with compose files" multiline minRows={2} value={composeDirs} onChange={(e) => setComposeDirs(e.target.value)}
            placeholder="~/work/acme/infrastructure" helperText="One per line. Only the folder itself is searched, not its subfolders. Files with other names: pick them on the Docker page."
            sx={{ maxWidth: 640, width: "100%" }} slotProps={{ htmlInput: { spellCheck: false } }} />
          <Box sx={{ mt: 2.5 }}>
            <Typography variant="body2" id="keep-images" sx={{ mb: 0.75, fontWeight: 600 }}>Service image versions kept on this machine</Typography>
            <ToggleButtonGroup exclusive size="small" color="primary" aria-labelledby="keep-images"
              value={settings.docker.keepImageVersions ?? 3}
              onChange={(_, value: number | null) => value && setSettings({ ...settings, docker: { ...settings.docker, keepImageVersions: value } })}>
              {[1, 2, 3, 4, 5].map((n) => <ToggleButton key={n} value={n} sx={{ px: 2 }}>{n}</ToggleButton>)}
            </ToggleButtonGroup>
            <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.75 }}>
              After each pull, older versions of that service's image are removed, so images do not fill the disk. Images a container
              still uses are kept. Default 3, at most 5.
            </Typography>
          </Box>
        </Section>

        <Section title="Builds">
          <Stack spacing={2} sx={{ maxWidth: 640 }}>
            <ProfileList profiles={settings.build.profiles ?? []}
              onChange={(profiles) => setSettings({ ...settings, build: { ...settings.build, profiles } })} />
            <FormControlLabel
              control={<Switch checked={settings.build.nativeContainerBuild}
                onChange={(e) => setSettings({ ...settings, build: { ...settings.build, nativeContainerBuild: e.target.checked } })} />}
              label="Build native images in a container (no local GraalVM needed)" />
            <TextField label="Free memory a native build needs (MB)" type="number" value={settings.build.nativeMinFreeMemoryMb}
              sx={{ maxWidth: 280 }} slotProps={{ htmlInput: { min: 1024, step: 512 } }}
              onChange={(e) => setSettings({ ...settings, build: { ...settings.build, nativeMinFreeMemoryMb: Number(e.target.value) } })} />
          </Stack>
        </Section>

        <Section title="Doctor">
          <FormControlLabel
            control={<Switch checked={settings.doctor.embeddedCredentialsAsError}
              onChange={(e) => setSettings({ ...settings, doctor: { embeddedCredentialsAsError: e.target.checked } })} />}
            label="A token in a remote URL blocks a group start (pilot, from week 2)" />
        </Section>
        <button type="submit" hidden aria-hidden="true" tabIndex={-1} />
      </Box>

      {gitlab.host && <TokenSection kind={kind} />}
      {gitlab.registryHost && <RegistrySection />}
      <SharedEnvSection team={team?.variables.concat(team.secrets).filter((v) => v.scope === "shared") ?? []} />
      <GroupsSection workspace={workspace} />
      <HiddenProjectsSection workspace={workspace} />
      <FilesSection />
      <StartOverSection workspace={workspace} />
    </>
  );
}

function TokenSection({ kind }: { kind: GitHostKind }) {
  const [status, setStatus] = useState<CredentialStatusView | null>(null);
  const [token, setToken] = useState("");
  const { busy, run } = useAction();

  useEffect(() => {
    api.credentials().then(setStatus).catch(() => setStatus(null));
  }, []);

  const sources = {
    GIT_CREDENTIAL_HELPER: { chip: { label: "git credential helper", color: "success" as const }, text: "git, VS Code and kitout share this one token." },
    KITOUT_KEYRING: { chip: { label: "kitout keyring", color: "warning" as const }, text: `Stored in ${status?.fallbackLocation}. Set up git's credential helper so every tool shares one token.` },
    NONE: { chip: { label: "No token", color: "error" as const }, text: "Run git fetch once in any repository and enter the token as the password, or store it below." },
  };
  const source = sources[(status?.source ?? "NONE") as keyof typeof sources] ?? sources.NONE;

  return (
    <Section title="Git token" description={status ? `For ${status.host}` : undefined}>
      {status && (
        <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 2 }}>
          <StatusChip look={source.chip} />
          <Typography variant="body2">{source.text}</Typography>
        </Stack>
      )}
      <Stack component="form" direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "flex-start", maxWidth: 760 }}
        onSubmit={(e) => {
          e.preventDefault();
          void run("token", () => api.storeToken(token), "Token stored").then((s) => { if (s) { setStatus(s); setToken(""); } });
        }}>
        <TextField label="Store a token for kitout only" type="password" autoComplete="off" value={token}
          onChange={(e) => setToken(e.target.value)} placeholder={tokenPlaceholder(kind)} sx={{ flexGrow: 1, minWidth: 260 }}
          helperText="Written to the keyring and never shown again, not even here." />
        <Button type="submit" variant="outlined" disabled={!token} loading={busy === "token"} sx={{ mt: 0.25 }}>Store token</Button>
        {status?.hasFallbackToken && (
          <Button color="error" loading={busy === "delete"} sx={{ mt: 0.25 }}
            onClick={() => void run("delete", () => api.deleteToken(), "Token removed").then((s) => s && setStatus(s))}>
            Remove kitout's token
          </Button>
        )}
      </Stack>
    </Section>
  );
}

const SAMPLE_PROJECT = "acme/shop/orders";

/** Variables every service gets when kitout starts it; a service's own variable of the same name wins. */
function SharedEnvSection({ team }: { team: TeamValueView[] }) {
  const [vars, setVars] = useState<EnvVarView[] | null>(null);
  const { busy, run } = useAction();
  const reload = () => api.envShared().then(setVars).catch(() => setVars([]));
  useEffect(() => {
    void reload();
  }, []);
  const importFile = (text: string) => void run("import", () => api.importEnvShared(text),
    (r) => `Imported ${r.added.length + r.secrets.length} variable${r.added.length + r.secrets.length === 1 ? "" : "s"}`
      + (r.secrets.length ? ` (${r.secrets.join(", ")} to the keyring)` : "") + (r.skipped.length ? `; skipped ${r.skipped.join("; ")}` : ""))
    .then(() => reload());
  return (
    <Section title="Environment for all services"
      description="Local settings every service needs when it runs (the Mongo host or user) or when kitout builds it (MAVEN_OPTS, a token for a private Maven repository). Set per-service values with the tune button next to a service.">
      <EnvEditor initial={vars} saving={busy === "env"} team={team} onImport={importFile} importing={busy === "import"}
        onSave={(next) => void run("env", () => api.saveEnvShared(next), "Saved the shared variables; they apply from the next start or build").then((r) => r && setVars(r))}
        hint="Type a variable name (MONGO_HOST) or a Quarkus property (quarkus.log.level). Passwords: tick the secret box." />
    </Section>
  );
}

/** The docker login for the container registry. Docker keeps it; kitout only asks Docker. */
function RegistrySection() {
  const [status, setStatus] = useState<RegistryStatusView | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const { busy, run } = useAction();

  useEffect(() => {
    api.registry().then((r) => {
      setStatus(r);
      setUsername((u) => u || r.username || "");
    }).catch(() => setStatus(null));
  }, []);

  const login = async () => {
    const r = await run("login", () => api.registryLogin(username, password), `Logged in to ${status?.host ?? "the registry"}`);
    setPassword("");
    if (r) {
      setStatus(r);
    }
  };

  return (
    <Section title="Container registry" description={status ? `Where the service images come from: ${status.host}` : undefined}>
      {status && (
        <Stack direction="row" spacing={1} sx={{ alignItems: "center", mb: 2 }}>
          <StatusChip look={status.loggedIn
            ? { label: status.username ? `Logged in as ${status.username}` : "Logged in", color: status.username ? "success" : "warning" }
            : { label: "Not logged in", color: "error" }} />
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            {status.detail || (status.loggedIn ? "Docker keeps this login." : "Services in \"Registry image\" mode cannot start until you log in.")}
          </Typography>
        </Stack>
      )}
      <Stack component="form" direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "flex-start", maxWidth: 820 }}
        onSubmit={(e) => { e.preventDefault(); void login(); }}>
        <TextField label="Registry user" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username"
          sx={{ minWidth: 220 }} slotProps={{ htmlInput: { spellCheck: false } }} />
        <TextField label="Password or token" type="password" value={password} onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password" sx={{ flexGrow: 1, minWidth: 240 }}
          helperText="Your registry login, which may differ from your git token. Handed to docker login, not stored by kitout." />
        <Button type="submit" variant="outlined" disabled={!username || !password} loading={busy === "login"} sx={{ mt: 0.25 }}>
          {status?.loggedIn ? "Log in again" : "Log in"}
        </Button>
        {status?.loggedIn && (
          <Button color="error" loading={busy === "logout"} sx={{ mt: 0.25 }}
            onClick={() => void run("logout", () => api.registryLogout(), "Logged out").then((r) => r && setStatus(r))}>
            Log out
          </Button>
        )}
      </Stack>
    </Section>
  );
}

function GroupsSection({ workspace }: { workspace: Workspace }) {
  const [editing, setEditing] = useState<GroupView | null>(null);
  const { busy, run } = useAction();
  const name = (id: string) => workspace.services.find((s) => s.id === id)?.name ?? id;

  return (
    <Section title="Groups" dense
      description="Suggested groups come from the repository paths. Saving one makes it yours; groups may span namespaces."
      action={
        <Button variant="outlined" startIcon={<AddRounded />}
          onClick={() => setEditing({ name: "", members: [], bffs: [], sequentialStart: false, suggested: false })}>
          New group
        </Button>
      }>
      <List disablePadding>
        {workspace.groups.length === 0 && <ListItem><ListItemText secondary="No groups yet." /></ListItem>}
        {workspace.groups.map((g) => (
          <ListItem key={g.name} divider
            secondaryAction={
              <Stack direction="row" spacing={0.25}>
                <ActionIcon label={`Edit ${g.name}`} onClick={() => setEditing(g)}><EditOutlined fontSize="small" /></ActionIcon>
                {!g.suggested && (
                  <ActionIcon label={`Delete ${g.name}`} color="error" disabled={busy === g.name}
                    onClick={() => void run(g.name, () => api.deleteGroup(g.name), `Deleted ${g.name}`).then(() => workspace.refreshGroups())}>
                    <DeleteOutlined fontSize="small" />
                  </ActionIcon>
                )}
              </Stack>
            }>
            <ListItemText
              primary={
                <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                  <span>{g.name}</span>
                  {g.suggested && <Tag label="suggested" />}
                  {g.sequentialStart && <Tag label="one after another" />}
                </Stack>
              }
              secondary={
                <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap", mt: 0.75, pr: 10 }}>
                  {g.members.map((id) => (
                    <Chip key={id} variant={g.bffs.includes(id) ? "soft" : "outlined"} color={g.bffs.includes(id) ? "primary" : "default"}
                      label={g.bffs.includes(id) ? `${name(id)} · BFF` : name(id)} />
                  ))}
                </Stack>
              }
              slotProps={{ primary: { component: "div", sx: { fontWeight: 650 } }, secondary: { component: "div" } }}
            />
          </ListItem>
        ))}
      </List>
      <GroupEditor group={editing} services={workspace.services} onClose={(saved) => {
        setEditing(null);
        if (saved) {
          void workspace.refreshGroups();
        }
      }} />
    </Section>
  );
}

/** Projects removed from kitout (libraries, archived projects); scans skip them until shown again. */
function HiddenProjectsSection({ workspace }: { workspace: Workspace }) {
  const [hidden, setHidden] = useState<HiddenProjectView[]>([]);
  const { busy, run } = useAction();
  const load = () => api.hiddenProjects().then(setHidden).catch(() => setHidden([]));

  useEffect(() => {
    void load();
  }, [workspace.services.length]);

  return (
    <Section title="Removed projects" dense
      description={hidden.length === 0
        ? "None. Projects you remove from the services list appear here, so you can show them again."
        : "Scans skip these folders. Showing one again adds it back to the services list."}>
      {hidden.length > 0 && (
        <List disablePadding>
          {hidden.map((h) => (
            <ListItem key={h.path} divider
              secondaryAction={
                <Button color="inherit" startIcon={<VisibilityOutlined />} loading={busy === h.path}
                  onClick={() => void run(h.path, () => api.restoreProject(h.path), `${h.name} is shown again`)
                    .then(() => load()).then(() => workspace.refreshServices())}>
                  Show again
                </Button>
              }>
              <ListItemText primary={h.name} secondary={<Mono muted>{h.path}</Mono>}
                slotProps={{ primary: { sx: { fontWeight: 650 } }, secondary: { component: "div" } }} />
            </ListItem>
          ))}
        </List>
      )}
    </Section>
  );
}

/** The way back to a clean kitout, for when things got tangled. */
function StartOverSection({ workspace }: { workspace: Workspace }) {
  const [open, setOpen] = useState(false);
  const [settingsReset, setSettingsReset] = useState(false);
  const close = () => {
    setOpen(false);
    if (settingsReset) {
      setSettingsReset(false);
      window.location.hash = pageHref("services");   // only after the developer has read the result
    }
  };
  return (
    <Section title="Start over" description="Remove kitout's settings, logs, builds and the service images it pulled. You choose which.">
      <Button color="error" variant="outlined" startIcon={<RestartAltRounded />} onClick={() => setOpen(true)}>Reset kitout…</Button>
      <ResetDialog open={open} onClose={close} onDone={(wasSettings) => {
        void workspace.refreshServices();
        void workspace.refreshGroups();
        setSettingsReset(wasSettings);
      }} />
    </Section>
  );
}

/** Where kitout keeps its files: for backups, bug reports, or a look with your own tools. */
function FilesSection() {
  const [info, setInfo] = useState<KitoutInfoView | null>(null);
  useEffect(() => {
    api.kitoutInfo().then(setInfo).catch(() => setInfo(null));
  }, []);
  if (!info) {
    return null;
  }
  const rows = [
    { label: "Settings", path: info.configFile, note: "This page, as JSON." },
    { label: "Run state and logs", path: info.stateDir, note: "Service logs in logs/, build logs in builds/." },
    { label: "kitout log", path: info.logFile, note: <>Also shown live under <Link href={pageHref("log")}>kitout log</Link>.</> },
  ];
  return (
    <Section title="kitout's files" dense description="Move them all with KITOUT_HOME, or with XDG_CONFIG_HOME and XDG_STATE_HOME.">
      <List disablePadding>
        {rows.map((r) => (
          <ListItem key={r.label} divider secondaryAction={<CopyButton text={r.path} label={`Copy the path of ${r.label.toLowerCase()}`} />}>
            <ListItemText primary={r.label}
              secondary={<><Mono muted>{r.path}</Mono><Box component="span" sx={{ display: "block" }}>{r.note}</Box></>}
              slotProps={{ primary: { sx: { fontWeight: 650 } }, secondary: { component: "div" } }} />
          </ListItem>
        ))}
      </List>
    </Section>
  );
}

/**
 * The build profiles offered in the build dialogs (Quarkus): add your own, remove the ones you do not
 * use. Any other profile can still be typed there ("Other"). Saved with the rest of the settings.
 */
function ProfileList({ profiles, onChange }: { profiles: string[]; onChange: (next: string[]) => void }) {
  const [draft, setDraft] = useState("");
  const name = draft.trim();
  const valid = PROFILE.test(name) && !profiles.includes(name);
  const add = () => {
    if (valid) {
      onChange([...profiles, name]);
      setDraft("");
    }
  };
  return (
    <Box>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>Build profiles</Typography>
      <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
        Offered when you build a Quarkus project. Others can still be typed in the build dialog.
      </Typography>
      <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
        {profiles.map((p) => (
          <Chip key={p} label={p} onDelete={profiles.length > 1 ? () => onChange(profiles.filter((x) => x !== p)) : undefined}
            sx={{ fontFamily: MONO }} aria-label={`Profile ${p}`} />
        ))}
        <TextField placeholder="Add a profile, e.g. staging" value={draft} sx={{ width: 220 }}
          error={name !== "" && !valid}
          helperText={name !== "" && !valid ? (profiles.includes(name) ? "Already in the list" : "Letters, digits, - and _") : " "}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              add();
            }
          }}
          slotProps={{ htmlInput: { "aria-label": "New build profile", spellCheck: false, maxLength: 32 } }} />
        <Button onClick={add} disabled={!valid}>Add</Button>
      </Stack>
    </Box>
  );
}
