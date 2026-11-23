import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import List from "@mui/material/List";
import ListItem from "@mui/material/ListItem";
import ListItemText from "@mui/material/ListItemText";
import Typography from "@mui/material/Typography";
import { api } from "../api/client.ts";
import type { CleanupOutcomeView, RemoteFindingView } from "../api/types.ts";
import { AppDialog, ErrorAlert, Mono, StatusChip } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { pageHref } from "../lib/route.ts";
import type { Look } from "../lib/status.ts";

const OUTCOMES: Record<string, Look> = {
  CLEANED: { label: "Cleaned", color: "success" },
  ROLLED_BACK: { label: "Rolled back", color: "warning" },
  FAILED: { label: "Failed", color: "error" },
};

/**
 * The guided cleanup of tokens in remote URLs: list (never showing the token), clean with a test
 * per repository and automatic rollback, then remind the developer to revoke the old token.
 */
export function CleanRemotesDialog({ open, group, onClose }: { open: boolean; group?: string; onClose: () => void }) {
  const [findings, setFindings] = useState<RemoteFindingView[] | null>(null);
  const [outcomes, setOutcomes] = useState<CleanupOutcomeView[] | null>(null);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  useEffect(() => {
    if (!open) {
      return;
    }
    setOutcomes(null);
    setError(undefined);
    api.remoteCredentials(group).then(setFindings).catch((e: Error) => setError(e.message));
  }, [open, group]);

  const clean = async () => {
    const result = await run("clean", () => api.cleanRemotes(group));
    if (result) {
      setOutcomes(result);
    }
  };

  const allClean = outcomes !== null && outcomes.every((o) => o.status === "CLEANED");

  return (
    <AppDialog
      open={open}
      maxWidth="md"
      title="Remove tokens from remote URLs"
      onClose={onClose}
      actions={
        outcomes ? (
          <Button variant="contained" onClick={onClose}>Done</Button>
        ) : (
          <>
            <Button color="inherit" onClick={onClose}>Cancel</Button>
            <Button variant="contained" loading={busy === "clean"} disabled={!findings?.length} onClick={() => void clean()}>
              Clean {findings?.length ?? ""} remote URLs
            </Button>
          </>
        )
      }
    >
      <ErrorAlert message={error} />
      {!outcomes && (
        <>
          <Typography>
            These remotes keep your git token in plain text in <code>.git/config</code>. kitout rewrites each one to the
            clean address below, tests access, and puts the old address back if the test fails.
          </Typography>
          <Alert severity="warning">
            Store your new token first, or the test fails and nothing changes: run <code>git fetch</code> once in any
            repository and enter the token as the password, or add it in <Link href={pageHref("settings")}>Settings</Link>.
          </Alert>
          <List dense disablePadding>
            {findings?.map((f, i) => (
              <ListItem key={i} divider disableGutters>
                <ListItemText primary={`${f.serviceName}, remote “${f.remote}” (${f.key})`} secondary={<Mono muted>{f.cleanUrl}</Mono>}
                  slotProps={{ primary: { sx: { fontWeight: 600 } }, secondary: { component: "div" } }} />
              </ListItem>
            ))}
          </List>
        </>
      )}
      {outcomes && (
        <>
          <List dense disablePadding>
            {outcomes.map((o, i) => (
              <ListItem key={i} divider disableGutters sx={{ gap: 1.5 }}>
                <StatusChip look={OUTCOMES[o.status] ?? { label: o.status, color: "default" }} />
                <ListItemText primary={o.serviceName} secondary={o.message} slotProps={{ primary: { sx: { fontWeight: 600 } } }} />
              </ListItem>
            ))}
          </List>
          <Alert severity={allClean ? "success" : "warning"}>
            {allClean
              ? "All clean. Now revoke the old token on the git server: a revoked token is harmless even where a copy remains."
              : "Some repositories were not changed. Fix what the messages say and run the cleanup again before you revoke the old token."}
          </Alert>
        </>
      )}
    </AppDialog>
  );
}
