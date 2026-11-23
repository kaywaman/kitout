import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import { api } from "../api/client.ts";
import type { PullOutcomeView, ServiceView, SyncStatusView } from "../api/types.ts";
import { AppDialog, ErrorAlert, Mono, StatusChip, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { plural } from "../lib/format.ts";
import { pullLook, syncLook } from "../lib/status.ts";

/**
 * Bring the checkouts on the page up to date with the git server. Opens with a check (git fetch, no file
 * changes), preselects what can be pulled safely, then pulls fast-forward only.
 */
export function PullDialog({ open, services, onClose, onPulled }: {
  open: boolean;
  services: ServiceView[];
  onClose: () => void;
  onPulled: () => void;
}) {
  const [statuses, setStatuses] = useState<SyncStatusView[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [outcomes, setOutcomes] = useState<PullOutcomeView[] | null>(null);
  const [error, setError] = useState<string>();
  const [checking, setChecking] = useState(false);
  const { busy, run } = useAction();

  const check = async () => {
    setChecking(true);
    setError(undefined);
    setOutcomes(null);
    try {
      const result = await api.gitCheck(services.map((s) => s.id));
      setStatuses(result);
      setSelected(new Set(result.filter((r) => r.pullable).map((r) => r.serviceId)));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setChecking(false);
    }
  };

  useEffect(() => {
    if (open) {
      setStatuses(null);
      void check();
    }
    // once per opening: the service list is polled every 2 s and must not restart the check
  }, [open]);

  const pull = async () => {
    const result = await run("pull", () => api.gitPull([...selected]),
      (r) => `${plural(r.filter((o) => o.status === "UPDATED").length, "checkout")} updated`);
    if (result) {
      setOutcomes(result);
      onPulled();
    }
  };

  const behindCount = statuses?.filter((s) => s.behind > 0).length ?? 0;
  const selectable = statuses?.filter((s) => !s.problem && s.behind > 0) ?? [];
  const allChecked = selectable.length > 0 && selectable.every((s) => selected.has(s.serviceId));
  const toggle = (id: string) => setSelected((cur) => {
    const next = new Set(cur);
    if (!next.delete(id)) {
      next.add(id);
    }
    return next;
  });
  const toggleAll = () => setSelected(allChecked ? new Set() : new Set(selectable.map((s) => s.serviceId)));
  const withChanges = statuses?.filter((s) => selected.has(s.serviceId) && s.localChanges).length ?? 0;

  return (
    <AppDialog
      open={open}
      maxWidth="md"
      title="Pull"
      onClose={onClose}
      actions={
        outcomes ? (
          <>
            <Button color="inherit" startIcon={<RefreshRounded />} onClick={() => void check()}>Check again</Button>
            <Button variant="contained" onClick={onClose}>Done</Button>
          </>
        ) : (
          <>
            <Button color="inherit" onClick={onClose}>Cancel</Button>
            <Button color="inherit" startIcon={<RefreshRounded />} loading={checking} onClick={() => void check()}>Check again</Button>
            <Button variant="contained" disabled={selected.size === 0 || checking} loading={busy === "pull"} onClick={() => void pull()}>
              Pull {plural(selected.size, "checkout")}
            </Button>
          </>
        )
      }
    >
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Fast-forward only (<code>git pull --ff-only</code>): kitout never merges, rebases or touches uncommitted changes.
        A checkout with local changes, without an upstream branch or diverged from origin is left for you to handle in your IDE.
      </Typography>
      <ErrorAlert message={error} />
      {checking && (
        <Box>
          <Typography variant="body2" sx={{ mb: 1 }}>Fetching {services.length} {services.length === 1 ? "repository" : "repositories"}…</Typography>
          <LinearProgress />
        </Box>
      )}

      {statuses && !outcomes && !checking && (
        <>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <FormControlLabel
              control={<Checkbox checked={allChecked} indeterminate={selected.size > 0 && !allChecked} disabled={selectable.length === 0} onChange={toggleAll} />}
              label={`Select all with new commits (${selectable.length})`} />
            <Box sx={{ flexGrow: 1 }} />
            <Typography variant="body2" sx={{ color: "text.secondary" }}>
              {behindCount === 0 ? "Everything is up to date." : `${plural(behindCount, "checkout")} behind origin`}
            </Typography>
          </Stack>
          <TableContainer sx={{ maxHeight: 420 }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox" />
                  <TableCell>Service</TableCell>
                  <TableCell>Branch</TableCell>
                  <TableCell>On the server</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {statuses.map((s) => (
                  <TableRow key={s.serviceId} hover>
                    <TableCell padding="checkbox">
                      <Checkbox checked={selected.has(s.serviceId)} disabled={!!s.problem || s.behind === 0}
                        onChange={() => toggle(s.serviceId)} slotProps={{ input: { "aria-label": `Pull ${s.serviceName}` } }} />
                    </TableCell>
                    <TableCell><Typography sx={{ fontWeight: 650 }}>{s.serviceName}</Typography></TableCell>
                    <TableCell>
                      <Mono>{s.branch || "?"}</Mono>
                      {s.ahead > 0 && <Box component="span" sx={{ ml: 1 }}><Tag label={`${s.ahead} to push`} title="Local commits not on the server yet" /></Box>}
                    </TableCell>
                    <TableCell>
                      <StatusChip look={syncLook(s)} title={s.problem ?? undefined} />
                      {s.problem && <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.5 }}>{s.problem}</Typography>}
                      {!s.problem && s.localChanges && s.behind > 0 && (
                        <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.5 }}>Uncommitted changes: a pull skips it until you commit or stash.</Typography>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          {withChanges > 0 && (
            <Alert severity="warning">{plural(withChanges, "selected checkout")} with uncommitted changes will be skipped.</Alert>
          )}
        </>
      )}

      {outcomes && (
        <>
          <TableContainer sx={{ maxHeight: 420 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Service</TableCell>
                  <TableCell>Result</TableCell>
                  <TableCell>Detail</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {outcomes.map((o) => (
                  <TableRow key={o.serviceId}>
                    <TableCell><Typography sx={{ fontWeight: 650 }}>{o.serviceName}</Typography></TableCell>
                    <TableCell><StatusChip look={pullLook(o.status, o.message)} /></TableCell>
                    <TableCell><Typography variant="body2">{o.message}</Typography></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          {outcomes.some((o) => o.status === "UPDATED") && (
            <Alert severity="info">
              Services that run from a local JVM or native build use the new code after a build and restart. Services that run
              from a registry image get it when the pipeline has published a new image.
            </Alert>
          )}
        </>
      )}
    </AppDialog>
  );
}
