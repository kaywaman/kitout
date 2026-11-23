import { useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Collapse from "@mui/material/Collapse";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import CheckCircleRounded from "@mui/icons-material/CheckCircleRounded";
import ErrorRounded from "@mui/icons-material/ErrorRounded";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import WarningRounded from "@mui/icons-material/WarningRounded";
import type { RequirementView, RequirementsView } from "../api/types.ts";
import { CopyButton, Mono } from "../components/ui.tsx";
import { ago } from "../lib/format.ts";
import { foundText, ordered } from "../lib/requirements.ts";
import { pageHref } from "../lib/route.ts";

/**
 * Shown instead of the page while this machine does not meet the team's requirements. kitout builds
 * and starts nothing until then (the backend refuses too); settings, the doctor and the kitout log
 * stay open so the developer can fix things.
 */
export function RequirementsScreen({ report, checking, onCheck }: {
  report: RequirementsView;
  checking: boolean;
  onCheck: () => void;
}) {
  const [showPassed, setShowPassed] = useState(false);
  const list = ordered<RequirementView>(report.results);
  const problems = list.filter((r) => r.outcome !== "PASS");
  const passed = list.filter((r) => r.outcome === "PASS");
  const failed = list.filter((r) => r.outcome === "FAIL").length;

  return (
    <Box sx={{ maxWidth: 880 }}>
      <Typography variant="h1" gutterBottom>This machine does not meet the team's requirements</Typography>
      <Typography sx={{ color: "text.secondary", mb: 2.5 }}>
        {failed === 1 ? "One requirement" : `${failed} requirements`} of {report.team || "your team"} {failed === 1 ? "is" : "are"} not met.
        kitout builds and starts nothing until {failed === 1 ? "it is" : "they are"} fixed, so nobody chases bugs that only a different Java,
        Maven, Docker or Node.js causes. You can still use <Link href={pageHref("settings")}>Settings</Link>,
        the <Link href={pageHref("doctor")}>Doctor</Link> and the <Link href={pageHref("log")}>kitout log</Link>.
      </Typography>
      <Stack spacing={1.5}>
        {problems.map((r) => <RequirementCard key={r.id} r={r} />)}
      </Stack>
      <Stack direction="row" spacing={1.5} sx={{ mt: 2.5, alignItems: "center", flexWrap: "wrap" }} useFlexGap>
        <Button variant="contained" startIcon={<RefreshRounded />} loading={checking} onClick={onCheck}>Check again</Button>
        <Typography variant="body2" sx={{ color: "text.secondary" }}>
          Checked {ago(report.checkedAt)}. In a terminal: <Mono>kitout check</Mono>
        </Typography>
        <Box sx={{ flexGrow: 1 }} />
        {passed.length > 0 && (
          <Button color="inherit" onClick={() => setShowPassed((v) => !v)}>
            {showPassed ? "Hide" : "Show"} {passed.length} met
          </Button>
        )}
      </Stack>
      <Collapse in={showPassed}>
        <Paper variant="outlined" sx={{ mt: 1.5, p: 1.5 }}>
          <Stack spacing={0.75}>
            {passed.map((r) => (
              <Stack key={r.id} direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <CheckCircleRounded fontSize="small" color="success" aria-label="met" />
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.name}</Typography>
                <Mono muted>{r.detail || r.found}</Mono>
              </Stack>
            ))}
          </Stack>
        </Paper>
      </Collapse>
    </Box>
  );
}

/** One requirement that is not met: what was found, why it matters, how to fix it (copyable). */
export function RequirementCard({ r }: { r: RequirementView }) {
  const failed = r.outcome === "FAIL";
  const commands = r.fix.match(/'([^']+)'/g)?.map((c) => c.slice(1, -1)) ?? [];
  return (
    <Paper variant="outlined" sx={{ p: 2, borderColor: failed ? "error.main" : "warning.main", borderLeftWidth: 4 }}>
      <Stack direction="row" spacing={1.25} sx={{ alignItems: "flex-start" }}>
        {failed ? <ErrorRounded color="error" aria-label="not met" /> : <WarningRounded color="warning" aria-label="warning" />}
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
            <Typography sx={{ fontWeight: 650 }}>{r.name}</Typography>
            <Mono muted>{foundText(r)}</Mono>
          </Stack>
          {r.problem && <Typography variant="body2" sx={{ mt: 0.5 }}>{r.problem}</Typography>}
          {r.detail && r.detail !== r.found && <Typography variant="body2" sx={{ color: "text.secondary" }}>Found: {r.detail}</Typography>}
          {r.note && <Typography variant="body2" sx={{ color: "warning.main", mt: 0.5 }}>{r.note}</Typography>}
          <Typography variant="body2" sx={{ mt: 1 }}>
            <Box component="strong">How to fix: </Box>{r.fix}
          </Typography>
          {commands.length > 0 && (
            <Stack spacing={0.5} sx={{ mt: 1 }}>
              {commands.map((c) => (
                <Stack key={c} direction="row" spacing={0.5} sx={{ alignItems: "center", bgcolor: "action.hover", borderRadius: 1, px: 1, py: 0.25 }}>
                  <Mono>{c}</Mono>
                  <Box sx={{ flexGrow: 1 }} />
                  <CopyButton text={c} label={`Copy ${c}`} />
                </Stack>
              ))}
            </Stack>
          )}
        </Box>
      </Stack>
    </Paper>
  );
}

/** A slim notice above every page when requirements only warn (enforced from a later day, or warn-only teams). */
export function RequirementsNotice({ report }: { report: RequirementsView }) {
  const [open, setOpen] = useState(false);
  const warnings = ordered<RequirementView>(report.results).filter((r) => r.outcome === "WARN");
  if (warnings.length === 0) {
    return null;
  }
  return (
    <Alert severity="warning" sx={{ mb: 2 }}
      action={<Button color="inherit" size="small" onClick={() => setOpen((v) => !v)}>{open ? "Hide" : "Details"}</Button>}>
      {warnings.length === 1 ? (warnings[0]!.problem ?? warnings[0]!.name) : `${warnings.length} things to fix on this machine`}
      <Collapse in={open}>
        <Stack spacing={1} sx={{ mt: 1.5 }}>
          {warnings.map((r) => <RequirementCard key={r.id} r={r} />)}
        </Stack>
      </Collapse>
    </Alert>
  );
}
