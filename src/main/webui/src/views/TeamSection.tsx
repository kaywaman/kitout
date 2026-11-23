import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import { api } from "../api/client.ts";
import type { RequirementsView, TeamStatusView } from "../api/types.ts";
import { TeamSourceForm } from "../components/TeamSourceForm.tsx";
import { ErrorAlert, Mono, Section, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { ago } from "../lib/format.ts";
import { pageHref } from "../lib/route.ts";

/**
 * The team file kitout follows: where it comes from and whether it is valid. Machine, secrets and
 * projects are on the Setup page.
 */
export function TeamSection({ onChanged }: { onChanged: () => void }) {
  const [status, setStatus] = useState<TeamStatusView | null>(null);
  const [requirements, setRequirements] = useState<RequirementsView | null>(null);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  const load = () => {
    api.team().then(setStatus).catch((e: Error) => setError(e.message));
    api.requirements().then(setRequirements).catch(() => setRequirements(null));
  };
  useEffect(load, []);

  const changed = (s: TeamStatusView | undefined) => {
    if (s) {
      setStatus(s);
      api.requirements().then(setRequirements).catch(() => setRequirements(null));
      onChanged();
    }
  };

  if (!status) {
    return <Section title="Team setup"><ErrorAlert message={error} /></Section>;
  }

  if (!status.configured) {
    return (
      <Section title="Team setup"
        description="Your team keeps its setup in one file in a git repository: services, groups, compose environments, variables and the tools every machine needs. Follow it, and kitout sets itself up.">
        <TeamSourceForm submitLabel="Follow" busy={busy === "use"}
          onSubmit={(source) => void run("use", () => api.useTeam(source), "Following the team file").then(changed)} />
      </Section>
    );
  }

  const failed = requirements?.results.filter((r) => r.outcome === "FAIL").length ?? 0;
  const warned = requirements?.results.filter((r) => r.outcome === "WARN").length ?? 0;

  return (
    <Section title="Team setup"
      description={<>kitout follows the team file. Values it sets show as read-only; your own settings stay yours and win where you change them.</>}
      action={
        <Stack direction="row" spacing={1}>
          <Button color="inherit" startIcon={<RefreshRounded />} loading={busy === "refresh"}
            onClick={() => void run("refresh", () => api.refreshTeam(), "Read the team file again").then(changed)}>Read again</Button>
          <Button color="inherit" loading={busy === "forget"}
            onClick={() => void run("forget", () => api.forgetTeam(), "kitout no longer follows the team file").then(changed)}>Stop following</Button>
          {status.location.endsWith("kitout-team.local.json") && (
            <Button variant="outlined" loading={busy === "export"} onClick={() => void run("export", () => api.exportTeam(),
              "Downloaded kitout-team.json: commit it to a repository your team shares").then((json) => json && download(json))}>
              Export kitout-team.json
            </Button>
          )}
        </Stack>
      }>
      <Stack spacing={2}>
        <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: "center", flexWrap: "wrap" }}>
          <Mono>{status.location}</Mono>
          {status.version && <Tag label={/^[0-9a-f]{40}$/.test(status.version) ? status.version.slice(0, 8) : "local file"} title={status.version} />}
          {status.valid ? <Tag label={status.team || "valid"} color="success" /> : <Tag label="not usable" color="error" />}
          {status.loadedAt && <Typography variant="body2" sx={{ color: "text.secondary" }}>read {ago(status.loadedAt)}</Typography>}
        </Stack>

        {status.problems.length > 0 && (
          <Alert severity="error">
            {status.valid ? "The newest team file cannot be used; kitout keeps the last good copy. Tell whoever maintains it:" : "The team file cannot be used yet:"}
            <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>{status.problems.map((p) => <li key={p}>{p}</li>)}</Box>
          </Alert>
        )}
        {status.warnings.length > 0 && (
          <Alert severity="info">
            Works, but worth fixing in the team file:
            <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>{status.warnings.map((w) => <li key={w}>{w}</li>)}</Box>
          </Alert>
        )}

        {status.valid && (
          <Typography variant="body2">
            {status.services} services, {status.groups.length} group{status.groups.length === 1 ? "" : "s"}
            {status.groups.length > 0 && <> ({status.groups.join(", ")})</>}, {status.composeEnvironments.length} compose
            environment{status.composeEnvironments.length === 1 ? "" : "s"} on the <Link href={pageHref("docker")}>Docker</Link> page,
            {" "}{status.variables.length} variable{status.variables.length === 1 ? "" : "s"} under Environment for all services.
          </Typography>
        )}

        <Typography variant="body2">
          {failed > 0 ? <><Tag label={`${failed} requirement${failed === 1 ? "" : "s"} not met`} color="error" />{" "}</> : null}
          {warned > 0 ? <><Tag label={`${warned} warning${warned === 1 ? "" : "s"}`} color="warning" />{" "}</> : null}
          The machine check, your secrets and the team's projects are on the <Link href={pageHref("setup")}>Setup</Link> page.
          kitout checks the machine by itself on every call.
        </Typography>

        {status.location.endsWith("kitout-team.local.json") && (
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            This team setup was built on this machine. To share it: <b>Export kitout-team.json</b>, commit it to a repository your team
            shares (e.g. next to the compose files), and everyone, you included, follows it with that repository's address.
          </Typography>
        )}

        {status.presets.length > 0 && (
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            Presets in the team file: {status.presets.join(", ")}. Starting a preset with one click is planned; for now start its group.
          </Typography>
        )}
      </Stack>
    </Section>
  );
}

function download(json: unknown) {
  const blob = new Blob([JSON.stringify(json, null, 2) + "\n"], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "kitout-team.json";
  a.click();
  URL.revokeObjectURL(a.href);
}
