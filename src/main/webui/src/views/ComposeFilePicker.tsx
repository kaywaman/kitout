import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Breadcrumbs from "@mui/material/Breadcrumbs";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import ListSubheader from "@mui/material/ListSubheader";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import ArrowUpwardRounded from "@mui/icons-material/ArrowUpwardRounded";
import DescriptionOutlined from "@mui/icons-material/DescriptionOutlined";
import FolderOutlined from "@mui/icons-material/FolderOutlined";
import HomeOutlined from "@mui/icons-material/HomeOutlined";
import { api } from "../api/client.ts";
import type { ComposeBrowseView, ComposeCandidateView } from "../api/types.ts";
import { AppDialog, ErrorAlert, Mono, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { breadcrumbs } from "../lib/paths.ts";
import { MONO } from "../theme.ts";

const LAST_FOLDER = "kitout.composeFolder";

/**
 * Pick any compose file inside the home folder, whatever its name: browse folders, see the YAML
 * files, choose one that is a compose file (has a top-level `services:`). It becomes an environment.
 */
export function ComposeFilePicker({ open, onClose, onPicked }: { open: boolean; onClose: () => void; onPicked: () => void }) {
  const [listing, setListing] = useState<ComposeBrowseView | null>(null);
  const [typed, setTyped] = useState("");
  const [chosen, setChosen] = useState<ComposeCandidateView | null>(null);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  const go = async (path: string) => {
    setError(undefined);
    setChosen(null);
    try {
      const next = await api.composeBrowse(path);
      setListing(next);
      setTyped(next.path);
      remember(next.path);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    if (open) {
      void go(readLast());
    }
  }, [open]);

  const pick = async (file: ComposeCandidateView | null = chosen) => {
    if (!file || file.picked) {
      return;
    }
    const picked = await run("pick", () => api.pickComposeFile(file.path), (p) => `${file.name} is now the environment ${p.environment}`);
    if (picked) {
      onPicked();
      onClose();
    }
  };

  const crumbs = listing ? breadcrumbs(listing.path, listing.home) : [];
  const composeCount = listing?.files.filter((f) => f.compose).length ?? 0;

  return (
    <AppDialog
      open={open}
      maxWidth="md"
      title="Pick a compose file"
      onClose={onClose}
      actions={
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          <Button variant="contained" disabled={!chosen || chosen.picked} loading={busy === "pick"} onClick={() => void pick()}>
            {chosen ? (chosen.picked ? "Already picked" : `Use ${chosen.name}`) : "Use this file"}
          </Button>
        </>
      }
    >
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Any compose file inside your home folder, whatever its name, e.g. your own <code>kafka-debug.yml</code>. kitout runs it with
        {" "}<code>docker compose -f &lt;file&gt; up -d</code> from the file's folder, so relative paths and <code>.env</code> work as usual.
      </Typography>
      <Stack component="form" direction="row" spacing={1} onSubmit={(e) => { e.preventDefault(); void go(typed); }}>
        <TextField fullWidth value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="~/work"
          slotProps={{ htmlInput: { spellCheck: false, "aria-label": "Folder path", style: { fontFamily: MONO } } }} />
        <Button type="submit" variant="outlined">Open</Button>
      </Stack>
      <ErrorAlert message={error} />

      {listing && (
        <>
          <Breadcrumbs aria-label="Folder path" maxItems={6}>
            {crumbs.map((c, i) => i === crumbs.length - 1
              ? (
                <Typography key={c.path} aria-current="location" sx={{ fontWeight: 600, display: "flex", alignItems: "center", gap: 0.5 }}>
                  {i === 0 && <HomeOutlined fontSize="small" />}{c.name}
                </Typography>
              )
              : (
                <Link key={c.path} component="button" type="button" underline="hover" color="inherit" onClick={() => void go(c.path)}
                  sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
                  {i === 0 && <HomeOutlined fontSize="small" />}{c.name}
                </Link>
              ))}
          </Breadcrumbs>

          <Paper variant="outlined" sx={{ maxHeight: 360, overflow: "auto" }}>
            <List dense disablePadding>
              {listing.files.length > 0 && (
                <ListSubheader disableSticky>{composeCount > 0 ? "YAML files: pick a compose file" : "YAML files (none is a compose file)"}</ListSubheader>
              )}
              {listing.files.map((f) => (
                <ListItemButton key={f.path} disabled={!f.compose} selected={chosen?.path === f.path} onClick={() => setChosen(f)}
                  onDoubleClick={() => void pick(f)}>
                  <ListItemIcon><DescriptionOutlined fontSize="small" color={f.compose ? "primary" : "inherit"} /></ListItemIcon>
                  <ListItemText primary={<Mono>{f.name}</Mono>}
                    secondary={f.compose ? `Environment "${f.environment}"` : "Not a compose file (no top-level services:)"} />
                  {f.picked && <Tag label="picked" color="primary" />}
                </ListItemButton>
              ))}
              <ListSubheader disableSticky>Folders</ListSubheader>
              {listing.parent && (
                <ListItemButton onClick={() => void go(listing.parent!)}>
                  <ListItemIcon><ArrowUpwardRounded fontSize="small" /></ListItemIcon>
                  <ListItemText primary="Up one level" />
                </ListItemButton>
              )}
              {listing.folders.length === 0 && <Box sx={{ px: 2, py: 1, color: "text.secondary" }}>No folders here.</Box>}
              {listing.folders.map((d) => (
                <ListItemButton key={d.path} onClick={() => void go(d.path)}>
                  <ListItemIcon><FolderOutlined fontSize="small" /></ListItemIcon>
                  <ListItemText primary={d.name} />
                  {d.gitRepository && <Tag label="git" />}
                </ListItemButton>
              ))}
            </List>
          </Paper>
        </>
      )}
    </AppDialog>
  );
}

function readLast(): string {
  try {
    return localStorage.getItem(LAST_FOLDER) ?? "~";
  } catch {
    return "~";
  }
}

function remember(path: string): void {
  try {
    localStorage.setItem(LAST_FOLDER, path);
  } catch {
    // not remembered; fine
  }
}
