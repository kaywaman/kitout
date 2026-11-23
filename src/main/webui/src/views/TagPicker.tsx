import { useEffect, useMemo, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import InputAdornment from "@mui/material/InputAdornment";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItemButton from "@mui/material/ListItemButton";
import ListItemText from "@mui/material/ListItemText";
import Paper from "@mui/material/Paper";
import TextField from "@mui/material/TextField";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import SearchRounded from "@mui/icons-material/SearchRounded";
import { api } from "../api/client.ts";
import type { ImageTagsView, ServiceView } from "../api/types.ts";
import { AppDialog, Mono, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { pageHref } from "../lib/route.ts";
import { MONO } from "../theme.ts";

/** Docker's tag grammar, as the backend checks it. */
const VALID_TAG = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/;

/**
 * Choose the image tag a service runs as "Registry image": a merge request, branch or release tag
 * from the registry. `latest` is never offered. A tag can also be typed, e.g. one the pipeline is
 * still building.
 */
export function TagPicker({ service, onClose, onChosen }: {
  service: ServiceView | null;
  onClose: () => void;
  onChosen: () => void;
}) {
  const [list, setList] = useState<ImageTagsView | null>(null);
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState("");
  const { busy, run } = useAction();

  const [editingRepo, setEditingRepo] = useState(false);
  const [repo, setRepo] = useState("");

  const load = (id: string) => {
    setList(null);
    setError(undefined);
    setLoading(true);
    api.imageTags(id)
      .then((l) => { setList(l); setRepo(l.repository); })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!service) {
      return;
    }
    setFilter("");
    setEditingRepo(false);
    setRepo(service.imageRepository ?? "");
    load(service.id);
  }, [service]);

  /** A repository for this service only, when it does not follow the registry's image path pattern; "" goes back to the pattern. */
  const saveRepo = async (value: string) => {
    if (!service) {
      return;
    }
    const done = await run("repo", () => api.updateService(service.id, { image: value }),
      value ? `${service.name} uses ${value}` : `${service.name} uses the image path from Settings again`);
    if (done) {
      setEditingRepo(false);
      onChosen();
      load(service.id);
    }
  };

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    return (list?.tags ?? []).filter((t) => !needle || t.toLowerCase().includes(needle));
  }, [list, filter]);

  const typed = filter.trim();
  const canUseTyped = typed !== "" && VALID_TAG.test(typed) && typed.toLowerCase() !== "latest" && !(list?.tags ?? []).includes(typed);
  const running = service !== null && (service.state === "RUNNING" || service.state === "STARTING");

  const choose = async (tag: string) => {
    if (!service) {
      return;
    }
    const done = await run("tag", () => api.chooseTag(service.id, tag),
      `${service.name} runs ${tag}${running ? " after a restart" : " from its next start"}`);
    if (done) {
      onChosen();
      onClose();
    }
  };

  const notLoggedIn = error !== undefined && /log in/i.test(error);

  return (
    <AppDialog
      open={service !== null}
      maxWidth="sm"
      title={`Image tag for ${service?.name ?? ""}`}
      onClose={onClose}
      actions={<Button color="inherit" onClick={onClose}>Cancel</Button>}
    >
      {!editingRepo ? (
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <Typography variant="body2" sx={{ color: "text.secondary" }}>Repository</Typography>
          <Mono>{list?.repository ?? service?.imageRepository ?? "unknown"}</Mono>
          <Button size="small" color="inherit" onClick={() => setEditingRepo(true)}>Change</Button>
        </Stack>
      ) : (
        <Stack component="form" direction="row" spacing={1} sx={{ alignItems: "flex-start" }}
          onSubmit={(e) => { e.preventDefault(); void saveRepo(repo.trim()); }}>
          <TextField fullWidth label="Repository for this service" value={repo} onChange={(e) => setRepo(e.target.value)} autoFocus
            helperText="Without tag, e.g. images.example.com/orders. Only for services that do not follow the image path in Settings."
            slotProps={{ htmlInput: { spellCheck: false, style: { fontFamily: MONO } } }} />
          <Button type="submit" variant="outlined" loading={busy === "repo"} sx={{ mt: 0.25 }}>Save</Button>
          <Button color="inherit" sx={{ mt: 0.25, whiteSpace: "nowrap" }} onClick={() => void saveRepo("")}>Use default</Button>
        </Stack>
      )}
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Pick the tag that fits your task, e.g. the merge request or branch you test against. kitout pulls it on every start, so a
        tag the pipeline updates stays current. <code>latest</code> is not offered.
      </Typography>
      {loading && <LinearProgress />}
      {error && (
        <Alert severity={notLoggedIn ? "warning" : "error"}>
          {error}
          {notLoggedIn && <> <Link href={pageHref("settings")} onClick={onClose}>Open Settings</Link></>}
        </Alert>
      )}
      <TextField
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        placeholder={list ? `Filter ${list.tags.length} tags, or type a tag` : "Type a tag"}
        autoFocus
        onKeyDown={(e) => {
          if (e.key === "Enter" && (canUseTyped || shown.length === 1)) {
            e.preventDefault();
            void choose(canUseTyped ? typed : shown[0]!);
          }
        }}
        slotProps={{
          input: { startAdornment: <InputAdornment position="start"><SearchRounded fontSize="small" /></InputAdornment> },
          htmlInput: { spellCheck: false, "aria-label": "Filter or type a tag", style: { fontFamily: MONO } },
        }}
      />
      {canUseTyped && (
        <Box>
          <Button variant="outlined" loading={busy === "tag"} onClick={() => void choose(typed)}>
            Use “{typed}”
          </Button>
          <Typography variant="caption" sx={{ ml: 1, color: "text.secondary" }}>not in the registry list (yet)</Typography>
        </Box>
      )}
      {list && (
        <Paper variant="outlined" sx={{ maxHeight: 340, overflow: "auto" }}>
          <List dense disablePadding>
            {shown.length === 0 && <Box sx={{ p: 2, color: "text.secondary" }}>{list.tags.length === 0 ? "The repository has no tags yet." : "No tag matches."}</Box>}
            {shown.slice(0, 300).map((t) => (
              <ListItemButton key={t} selected={t === list.current} disabled={busy === "tag"} onClick={() => void choose(t)}>
                <ListItemText primary={<Mono>{t}</Mono>} />
                {t === list.current && <Tag label="current" color="primary" />}
              </ListItemButton>
            ))}
          </List>
        </Paper>
      )}
      {list && shown.length > 300 && (
        <Typography variant="caption" sx={{ color: "text.secondary" }}>Showing 300 of {shown.length}; filter to find others.</Typography>
      )}
    </AppDialog>
  );
}
