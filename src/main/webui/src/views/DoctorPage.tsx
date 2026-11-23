import { useCallback, useEffect, useState } from "react";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import LinearProgress from "@mui/material/LinearProgress";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import CleaningServicesOutlined from "@mui/icons-material/CleaningServicesOutlined";
import ExpandMoreRounded from "@mui/icons-material/ExpandMoreRounded";
import RefreshRounded from "@mui/icons-material/RefreshRounded";
import { api } from "../api/client.ts";
import type { CheckView, DoctorReportView } from "../api/types.ts";
import { ErrorAlert, Mono, PageHeader, SeverityChip, Tag } from "../components/ui.tsx";
import type { Workspace } from "../hooks/useWorkspace.ts";
import { plural } from "../lib/format.ts";
import { CleanRemotesDialog } from "./CleanRemotesDialog.tsx";

const ORDER: Record<string, number> = { ERROR: 0, WARNING: 1, INFO: 2, OK: 3 };

/** Runs the doctor for the selected group. Problems first, each with its fix. */
export function DoctorPage({ workspace }: { workspace: Workspace }) {
  const groupName = workspace.group?.name;
  const [report, setReport] = useState<DoctorReportView | null>(null);
  const [error, setError] = useState<string>();
  const [running, setRunning] = useState(false);
  const [cleaning, setCleaning] = useState(false);

  const runDoctor = useCallback(async () => {
    setRunning(true);
    setError(undefined);
    try {
      setReport(await api.doctor(groupName));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  }, [groupName]);

  useEffect(() => {
    void runDoctor();
  }, [runDoctor]);

  const results = [...(report?.results ?? [])].sort((a, b) => (ORDER[a.severity] ?? 9) - (ORDER[b.severity] ?? 9));
  const problems = results.filter((r) => r.severity === "ERROR" || r.severity === "WARNING");
  const fine = results.filter((r) => r.severity === "OK" || r.severity === "INFO");

  return (
    <>
      <PageHeader title={groupName ? `Doctor for ${groupName}` : "Doctor"}
        subtitle={<Typography variant="body2" sx={{ color: "text.secondary" }}>Docker, registry login, JDK, git access, ports and remotes.</Typography>}>
        <Button variant="contained" startIcon={<RefreshRounded />} loading={running} loadingPosition="start" onClick={() => void runDoctor()}>
          {running ? "Checking" : "Check again"}
        </Button>
      </PageHeader>
      {running && <LinearProgress sx={{ mb: 2, borderRadius: 1 }} />}
      <ErrorAlert message={error} />
      {report && (
        problems.length === 0
          ? <Alert severity="success" sx={{ mb: 2 }}>Everything kitout checks is in order.</Alert>
          : (
            <Alert severity={report.errors > 0 ? "error" : "warning"} sx={{ mb: 2 }}>
              {plural(report.errors, "error")} and {plural(report.warnings, "warning")}.
              {report.errors > 0 && " Errors stop a group start unless you start it anyway."}
            </Alert>
          )
      )}
      <Stack spacing={1.5}>
        {problems.map((r, i) => <Check key={`p${i}`} result={r} onAction={() => setCleaning(true)} />)}
      </Stack>
      {fine.length > 0 && (
        <Accordion variant="outlined" disableGutters defaultExpanded={problems.length === 0} key={problems.length === 0 ? "open" : "closed"}
          sx={{ mt: 2, borderRadius: 2, "&::before": { display: "none" } }}>
          <AccordionSummary expandIcon={<ExpandMoreRounded />}>
            <Typography sx={{ fontWeight: 600 }}>{plural(fine.length, "check")} passed</Typography>
          </AccordionSummary>
          <AccordionDetails>
            <Stack spacing={1}>
              {fine.map((r, i) => <Check key={`o${i}`} result={r} onAction={() => setCleaning(true)} quiet />)}
            </Stack>
          </AccordionDetails>
        </Accordion>
      )}
      <CleanRemotesDialog open={cleaning} group={groupName} onClose={() => { setCleaning(false); void runDoctor(); }} />
    </>
  );
}

function Check({ result: r, onAction, quiet }: { result: CheckView; onAction: () => void; quiet?: boolean }) {
  const accent = r.severity === "ERROR" ? "error.main" : r.severity === "WARNING" ? "warning.main" : "divider";
  return (
    <Paper variant="outlined" sx={{ p: quiet ? 1.25 : 2, borderLeft: 3, borderLeftColor: accent }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: "flex-start" }}>
        <Box sx={{ pt: 0.25, minWidth: 76 }}><SeverityChip severity={r.severity} /></Box>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography sx={{ fontWeight: 650 }}>{r.title}</Typography>
            {r.advisory && <Tag label="From files" title="Based on the files in the repository; runtime settings may differ" />}
          </Stack>
          <Typography variant="body2" sx={{ mt: 0.25 }}>{r.detail}</Typography>
          {r.items.length > 0 && (
            <Box component="ul" sx={{ m: 0, mt: 0.75, pl: 2.5 }}>
              {r.items.map((item, i) => <li key={i}><Mono muted>{item}</Mono></li>)}
            </Box>
          )}
          {r.fix && (
            <Typography variant="body2" sx={{ mt: 1, color: "text.secondary" }}>
              <Box component="strong" sx={{ color: "text.primary" }}>Fix: </Box>{r.fix}
            </Typography>
          )}
          {r.action === "clean-remotes" && (
            <Button variant="contained" startIcon={<CleaningServicesOutlined />} onClick={onAction} sx={{ mt: 1.25 }}>
              Clean remotes
            </Button>
          )}
        </Box>
      </Stack>
    </Paper>
  );
}
