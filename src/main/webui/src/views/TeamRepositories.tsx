import { useEffect, useState } from "react";
import Button from "@mui/material/Button";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import CloudDownloadOutlined from "@mui/icons-material/CloudDownloadOutlined";
import SyncRounded from "@mui/icons-material/SyncRounded";
import { api } from "../api/client.ts";
import type { CloneJobView, LibraryKind, SetupProjectView } from "../api/types.ts";
import { AppDialog, CopyButton, Mono, Section, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";

/** The three kinds of repositories that are not services, in the order they are shown. */
const KINDS = [
  { kind: "java-library", title: "Common Java libraries",
    description: "Java libraries the services build against. kitout clones and pulls them with the rest; it never starts them." },
  { kind: "ui-library", title: "Common UI libraries",
    description: "UI libraries the frontends build against. kitout clones and pulls them with the rest; it never starts them." },
  { kind: "repository", title: "Team repositories",
    description: "Repositories your team needs that are not services, such as the compose files. kitout clones and pulls them; it never starts them." },
] as const;

/**
 * The team's repositories that are not services: common Java and UI libraries, and others such as
 * the one with the compose files. Where they are on this machine, clone and pull (fast-forward
 * only). They are never listed or started as services. A team setup built on this machine can add libraries here.
 */
export function TeamRepositories({ onChanged }: { onChanged?: () => void }) {
  const [repos, setRepos] = useState<SetupProjectView[] | null>(null);
  const [editable, setEditable] = useState(false);
  const [adding, setAdding] = useState<LibraryKind | null>(null);
  const [job, setJob] = useState<CloneJobView | null>(null);
  const { busy, run } = useAction();

  const load = () => api.teamRepositories().then(setRepos).catch(() => setRepos([]));
  useEffect(() => {
    void load();
    api.setup().then((s) => setEditable(s.editable)).catch(() => setEditable(false));
  }, []);

  useEffect(() => {
    if (!job || job.finished) {
      return;
    }
    const t = window.setTimeout(() => {
      api.cloneJob(job.id).then((j) => {
        setJob(j);
        if (j.finished) {
          void load();
          onChanged?.();
        }
      }).catch(() => undefined);
    }, 1500);
    return () => window.clearTimeout(t);
  }, [job]);

  if (!repos || (repos.length === 0 && !editable)) {
    return null;
  }

  const row = (r: SetupProjectView) => {
    const cloning = job !== null && !job.finished && job.items.some((i) => i.fullPath === r.repo);
    return (
      <TableRow key={r.repo}>
        <TableCell>
          <Mono>{r.repo}</Mono>
          {r.description && <Typography variant="caption" sx={{ color: "text.secondary", display: "block" }}>{r.description}</Typography>}
        </TableCell>
        <TableCell>
          {r.state === "MISSING" ? <Tag label="not on this machine" color="warning" /> : (
            <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
              <Mono muted>{r.path}</Mono>
              <CopyButton text={r.path} label={`Copy the folder of ${r.repo}`} />
            </Stack>
          )}
        </TableCell>
        <TableCell align="right">
          {r.state === "MISSING" ? (
            <Button variant="contained" startIcon={<CloudDownloadOutlined />} loading={busy === `clone-${r.repo}` || cloning}
              onClick={() => void run(`clone-${r.repo}`, () => api.cloneTeamRepository(r.repo), `Cloning ${r.repo}`).then((j) => j && setJob(j))}>
              Clone
            </Button>
          ) : (
            <Button variant="outlined" color="inherit" startIcon={<SyncRounded />} loading={busy === `pull-${r.repo}`}
              onClick={() => void run(`pull-${r.repo}`, () => api.pullTeamRepository(r.repo), (o) => `${r.repo}: ${o.message}`)
                .then(() => onChanged?.())}>
              Pull
            </Button>
          )}
        </TableCell>
      </TableRow>
    );
  };

  return (
    <>
      {KINDS.map(({ kind, title, description }) => {
        const shown = repos.filter((r) => r.kind === kind);
        const canAdd = editable && kind !== "repository";
        if (shown.length === 0 && !canAdd) {
          return null;
        }
        return (
          <Section key={kind} title={title} dense description={description}
            action={canAdd ? <Button variant="outlined" startIcon={<AddRounded />} onClick={() => setAdding(kind as LibraryKind)}>Add library</Button> : undefined}>
            {shown.length === 0 ? (
              <Typography variant="body2" sx={{ color: "text.secondary" }}>None yet.</Typography>
            ) : (
              <Table size="small" aria-label={title}>
                <TableHead>
                  <TableRow>
                    <TableCell>Repository</TableCell>
                    <TableCell>Folder</TableCell>
                    <TableCell align="right"><span style={{ position: "absolute", left: -9999 }}>Actions</span></TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>{shown.map(row)}</TableBody>
              </Table>
            )}
          </Section>
        );
      })}
      <AddLibraryDialog kind={adding} onClose={() => setAdding(null)} onAdded={() => { void load(); onChanged?.(); }} />
    </>
  );
}

/** Adds a git checkout on this machine to the team setup kitout wrote, as a common Java or UI library. */
function AddLibraryDialog({ kind, onClose, onAdded }: { kind: LibraryKind | null; onClose: () => void; onAdded: () => void }) {
  const [folder, setFolder] = useState("");
  const [chosen, setChosen] = useState<LibraryKind>("java-library");
  const { busy, run } = useAction();

  useEffect(() => {
    if (kind) {
      setChosen(kind);
      setFolder("");
    }
  }, [kind]);

  if (!kind) {
    return null;
  }
  const add = async () => {
    const r = await run("add", () => api.addTeamLibraries([folder.trim()], chosen),
      (a) => `${a.added.join(", ")} is now a ${chosen === "ui-library" ? "UI" : "Java"} library of the team`);
    if (r) {
      onAdded();
      onClose();
    }
  };
  return (
    <AppDialog open title="Add a common library" onClose={onClose}
      actions={
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          <Button variant="contained" loading={busy === "add"} disabled={!folder.trim()} onClick={() => void add()}>Add library</Button>
        </>
      }>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        A git checkout below your home folder. kitout adds its repository (from the git origin) to the team setup on this machine,
        so everyone who follows it gets the library cloned. Share the setup with Export in Settings, Team setup.
      </Typography>
      <TextField label="Folder" placeholder="~/work/acme/common/java-commons" value={folder} autoFocus
        onChange={(e) => setFolder(e.target.value)} slotProps={{ htmlInput: { spellCheck: false } }} />
      <TextField select label="Kind" value={chosen} sx={{ maxWidth: 260 }} onChange={(e) => setChosen(e.target.value as LibraryKind)}>
        <MenuItem value="java-library">Java library</MenuItem>
        <MenuItem value="ui-library">UI library</MenuItem>
      </TextField>
    </AppDialog>
  );
}
