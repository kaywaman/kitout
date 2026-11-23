import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Box from "@mui/material/Box";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
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
import Typography from "@mui/material/Typography";
import CloudDownloadOutlined from "@mui/icons-material/CloudDownloadOutlined";
import { api } from "../api/client.ts";
import type { CloneCandidateView, CloneJobView } from "../api/types.ts";
import { EmptyState, Mono, PageHeader, Section, StatusChip, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { plural } from "../lib/format.ts";
import { pageHref } from "../lib/route.ts";
import type { Look } from "../lib/status.ts";

const LAST_GROUP = "kitout.cloneGroup";

const ITEM_STATUS: Record<string, Look> = {
  QUEUED: { label: "Queued", color: "default" },
  CLONING: { label: "Cloning", color: "info", busy: true },
  CLONED: { label: "Cloned", color: "success" },
  SKIPPED: { label: "Skipped", color: "default" },
  FAILED: { label: "Failed", color: "error" },
};

/**
 * Clones a GitLab group or a GitHub organization (or user) into the workspace, folders mirroring the path.
 * Light clones are enough for services that only run from registry images.
 */
export function ClonePage({ workspace }: { workspace: Workspace }) {
  const [groupPath, setGroupPath] = useState(() => readLast());
  const [candidates, setCandidates] = useState<CloneCandidateView[] | null>(null);
  const [selected, setSelected] = useState<Record<string, "full" | "light">>({});
  const [job, setJob] = useState<CloneJobView | null>(null);
  const { busy, run } = useAction();

  useEffect(() => {
    if (!job || job.finished) {
      return;
    }
    const timer = window.setTimeout(async () => {
      const next = await api.cloneJob(job.id).catch(() => null);
      if (next) {
        setJob(next);
        if (next.finished) {
          void workspace.refreshServices();
          void workspace.refreshGroups();
        }
      }
    }, 1000);
    return () => window.clearTimeout(timer);
  }, [job, workspace]);

  const list = async () => {
    setJob(null);
    remember(groupPath);
    const result = await run("list", () => api.cloneCandidates(groupPath.trim()));
    if (result) {
      setCandidates(result);
      const initial: Record<string, "full" | "light"> = {};
      result.filter((c) => !c.alreadyLocal).forEach((c) => (initial[c.fullPath] = "full"));
      setSelected(initial);
    }
  };

  const clone = async () => {
    const items = Object.entries(selected).map(([fullPath, kind]) => ({ fullPath, light: kind === "light" }));
    const started = await run("clone", () => api.clone(items));
    if (started) {
      setJob(started);
    }
  };

  const toggle = (c: CloneCandidateView) => setSelected((s) => {
    const copy = { ...s };
    if (copy[c.fullPath]) {
      delete copy[c.fullPath];
    } else {
      copy[c.fullPath] = "full";
    }
    return copy;
  });

  const selectable = candidates?.filter((c) => !c.alreadyLocal) ?? [];
  const count = Object.keys(selected).length;
  const setAllKinds = (kind: "full" | "light") =>
    setSelected((cur) => Object.fromEntries(Object.keys(cur).map((path) => [path, kind])));
  const toggleAll = () => setSelected(count === selectable.length
    ? {}
    : Object.fromEntries(selectable.map((c) => [c.fullPath, selected[c.fullPath] ?? "full"])));

  return (
    <>
      <PageHeader title="Clone a group"
        subtitle={<Typography variant="body2" sx={{ color: "text.secondary" }}>A GitLab group or a GitHub organization or user. Folders mirror the path below your workspace folder. On other git servers, name the repositories in the team file.</Typography>} />
      <Paper variant="outlined" component="form" onSubmit={(e) => { e.preventDefault(); void list(); }}
        sx={{ p: 2, mb: 2.5, display: "flex", gap: 1.5, alignItems: "center", flexWrap: "wrap" }}>
        <TextField label="Group or organization" value={groupPath} onChange={(e) => setGroupPath(e.target.value)} placeholder="acme/shop" required
          sx={{ flexGrow: 1, minWidth: 240 }} slotProps={{ htmlInput: { spellCheck: false } }} />
        <Button variant="contained" type="submit" loading={busy === "list"}>List projects</Button>
      </Paper>

      {candidates && candidates.length === 0 && (
        <EmptyState title="No projects in this group" icon={<CloudDownloadOutlined />}>
          Check the group path and that your token can read it.
        </EmptyState>
      )}

      {candidates && candidates.length > 0 && !job && (
        <Section title={`${plural(candidates.length, "project")} in ${groupPath}`} dense
          action={
            <Button variant="contained" startIcon={<CloudDownloadOutlined />} disabled={count === 0} loading={busy === "clone"}
              onClick={() => void clone()}>
              Clone {plural(count, "project")}
            </Button>
          }>
          <Stack direction="row" spacing={2} useFlexGap sx={{ px: 2, py: 0.75, alignItems: "center", flexWrap: "wrap", borderBottom: 1, borderColor: "divider" }}>
            <FormControlLabel
              control={<Checkbox indeterminate={count > 0 && count < selectable.length} checked={selectable.length > 0 && count === selectable.length}
                disabled={selectable.length === 0} onChange={toggleAll} />}
              label={selectable.length === 0 ? "All projects are cloned already" : `Select all (${selectable.length} not cloned yet)`} />
            <Box sx={{ flexGrow: 1 }} />
            {count > 0 && (
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography variant="body2" sx={{ color: "text.secondary" }}>Clone the selected as</Typography>
                <Button color="inherit" onClick={() => setAllKinds("full")}>Full</Button>
                <Button color="inherit" onClick={() => setAllKinds("light")}>Light</Button>
              </Stack>
            )}
          </Stack>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox">
                    <Checkbox indeterminate={count > 0 && count < selectable.length} checked={selectable.length > 0 && count === selectable.length}
                      disabled={selectable.length === 0} onChange={toggleAll} slotProps={{ input: { "aria-label": "Select all" } }} />
                  </TableCell>
                  <TableCell>Project</TableCell>
                  <TableCell sx={{ width: 300 }}>Clone as</TableCell>
                  <TableCell>Folder</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {candidates.map((c) => (
                  <TableRow key={c.fullPath} hover sx={c.alreadyLocal ? { opacity: 0.6 } : undefined}>
                    <TableCell padding="checkbox">
                      <Checkbox disabled={c.alreadyLocal} checked={!!selected[c.fullPath]} onChange={() => toggle(c)}
                        slotProps={{ input: { "aria-label": `Clone ${c.name}` } }} />
                    </TableCell>
                    <TableCell>
                      <Typography sx={{ fontWeight: 650 }}>{c.name}</Typography>
                      <Mono muted>{c.fullPath}</Mono>
                    </TableCell>
                    <TableCell>
                      {c.alreadyLocal ? <Tag label="Already cloned" /> : (
                        <TextField select fullWidth value={selected[c.fullPath] ?? "full"} disabled={!selected[c.fullPath]}
                          onChange={(e) => setSelected((s) => ({ ...s, [c.fullPath]: e.target.value as "full" | "light" }))}
                          slotProps={{ htmlInput: { "aria-label": `Clone type for ${c.name}` } }}>
                          <MenuItem value="full">Full clone (to change or build it)</MenuItem>
                          <MenuItem value="light">Light clone (runs from its image)</MenuItem>
                        </TextField>
                      )}
                    </TableCell>
                    <TableCell><Mono muted>{c.target}</Mono></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Section>
      )}

      {job && (
        <Section title={job.finished ? "Clone finished" : "Cloning"}>
          <List dense disablePadding aria-live="polite">
            {job.items.map((i) => (
              <ListItem key={i.fullPath} divider disableGutters sx={{ gap: 1.5 }}>
                <StatusChip look={ITEM_STATUS[i.status] ?? { label: i.status, color: "default" }} />
                <ListItemText primary={<Mono>{i.fullPath}</Mono>} secondary={i.message || undefined} />
              </ListItem>
            ))}
          </List>
          {job.finished && (
            <Alert severity="success" sx={{ mt: 2 }}>
              Done. The new services are on the <Link href={pageHref("services")}>Services</Link> page, with a suggested group.
            </Alert>
          )}
        </Section>
      )}
    </>
  );
}

function readLast(): string {
  try {
    return localStorage.getItem(LAST_GROUP) ?? "";
  } catch {
    return "";
  }
}

function remember(value: string): void {
  try {
    localStorage.setItem(LAST_GROUP, value);
  } catch {
    // not remembered; fine
  }
}
