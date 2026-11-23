import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Breadcrumbs from "@mui/material/Breadcrumbs";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import ArrowUpwardRounded from "@mui/icons-material/ArrowUpwardRounded";
import FolderOutlined from "@mui/icons-material/FolderOutlined";
import HomeOutlined from "@mui/icons-material/HomeOutlined";
import { api } from "../api/client.ts";
import type { FolderListingView, FolderPreviewView } from "../api/types.ts";
import { AppDialog, ErrorAlert, Mono, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { plural } from "../lib/format.ts";
import { breadcrumbs } from "../lib/paths.ts";
import { MONO } from "../theme.ts";

/**
 * Browse the home folder, open the folder that holds your projects, and add every Quarkus
 * project in it. Shows what will be added before anything is saved.
 */
export function FolderPicker({ open, start, onClose, onAdded }: {
  open: boolean;
  start?: string;
  onClose: () => void;
  onAdded: (count: number) => void;
}) {
  const [listing, setListing] = useState<FolderListingView | null>(null);
  const [typed, setTyped] = useState("");
  const [preview, setPreview] = useState<FolderPreviewView | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  const go = async (path: string) => {
    setError(undefined);
    try {
      const next = await api.folders(path);
      setListing(next);
      setTyped(next.path);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    if (open) {
      setPreview(null);
      void go(start ?? "~");
    }
  }, [open, start]);

  // what would be added: looked up for every folder except home itself (too large to search)
  useEffect(() => {
    if (!listing || listing.path === listing.home) {
      setPreview(null);
      return;
    }
    let cancelled = false;
    setPreviewing(true);
    api.folderPreview(listing.path)
      .then((p) => !cancelled && setPreview(p))
      .catch(() => !cancelled && setPreview(null))
      .finally(() => !cancelled && setPreviewing(false));
    return () => {
      cancelled = true;
    };
  }, [listing]);

  const add = async () => {
    if (!listing) {
      return;
    }
    const result = await run("add", () => api.scan([listing.path]),
      (r) => (r.added.length > 0 ? `Added ${plural(r.added.length, "project")}` : "No new projects in this folder"));
    if (result) {
      onAdded(result.added.length);
      onClose();
    }
  };

  const count = preview?.projects.length ?? 0;
  const crumbs = listing ? breadcrumbs(listing.path, listing.home) : [];
  const atHome = listing !== null && listing.path === listing.home;

  return (
    <AppDialog
      open={open}
      maxWidth="md"
      title="Choose the folder with your projects"
      onClose={onClose}
      actions={
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          <Button variant="contained" loading={busy === "add"} disabled={!listing || count === 0} onClick={() => void add()}>
            {count > 0 ? `Add ${plural(count, "project")}` : "Add projects"}
          </Button>
        </>
      }
    >
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

          <Paper variant="outlined" sx={{ maxHeight: 300, overflow: "auto" }}>
            <List dense disablePadding>
              {listing.parent && (
                <ListItemButton onClick={() => void go(listing.parent!)} divider>
                  <ListItemIcon><ArrowUpwardRounded fontSize="small" /></ListItemIcon>
                  <ListItemText primary="Up one level" />
                </ListItemButton>
              )}
              {listing.entries.length === 0 && (
                <Box sx={{ p: 2, color: "text.secondary" }}>No folders here.</Box>
              )}
              {listing.entries.map((e) => (
                <ListItemButton key={e.path} onClick={() => void go(e.path)}>
                  <ListItemIcon><FolderOutlined fontSize="small" color={e.quarkusProject ? "primary" : "inherit"} /></ListItemIcon>
                  <ListItemText primary={e.name} />
                  {e.quarkusProject && <Tag label="Project" color="primary" />}
                  {!e.quarkusProject && e.gitRepository && <Tag label="git" />}
                </ListItemButton>
              ))}
            </List>
          </Paper>
          {listing.truncated && (
            <Typography variant="body2" sx={{ color: "text.secondary" }}>Only the first 500 folders are shown. Type a path above to go further.</Typography>
          )}

          <Box aria-live="polite">
            {atHome && <Alert severity="info">Open the folder that holds your projects, for example <code>work</code>.</Alert>}
            {!atHome && previewing && (
              <Box>
                <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>Looking for projects in this folder…</Typography>
                <LinearProgress />
              </Box>
            )}
            {!atHome && !previewing && preview && (
              count === 0
                ? <Alert severity="warning">No new projects in this folder or below it.</Alert>
                : (
                  <Alert severity="success" icon={false}>
                    <Typography sx={{ fontWeight: 600, mb: 1 }}>{plural(count, "Quarkus project")} in this folder will be added:</Typography>
                    <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
                      {preview.projects.map((p) => (
                        <Chip key={p.path} variant="outlined" label={<>{p.name} <Mono muted>:{p.port}</Mono></>} title={p.path} />
                      ))}
                    </Stack>
                  </Alert>
                )
            )}
            {preview && preview.hiddenPaths.length > 0 && (
              <Typography variant="body2" sx={{ color: "text.secondary", mt: 1 }}>
                {plural(preview.hiddenPaths.length, "project")} you removed earlier stay hidden. Show them again in Settings.
              </Typography>
            )}
            {preview && (preview.outsideTeam ?? []).length > 0 && (
              <Typography variant="body2" sx={{ color: "text.secondary", mt: 1 }}>
                {plural(preview.outsideTeam.length, "project")} here {preview.outsideTeam.length === 1 ? "is" : "are"} not in the team file and stay{preview.outsideTeam.length === 1 ? "s" : ""} out of kitout.
              </Typography>
            )}
          </Box>
        </>
      )}
    </AppDialog>
  );
}
