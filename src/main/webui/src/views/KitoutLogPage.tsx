import { useEffect, useState } from "react";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { api, logStreams } from "../api/client.ts";
import type { KitoutInfoView } from "../api/types.ts";
import { LogViewer } from "../components/LogViewer.tsx";
import { CopyButton, Mono, PageHeader } from "../components/ui.tsx";

/**
 * kitout's own log, live: what the daemon did (starts, stops, health changes, errors) without a
 * terminal. The same file the CLI writes its warnings to.
 */
export function KitoutLogPage() {
  const [info, setInfo] = useState<KitoutInfoView | null>(null);
  useEffect(() => {
    api.kitoutInfo().then(setInfo).catch(() => setInfo(null));
  }, []);

  return (
    <>
      <PageHeader
        title="kitout log"
        subtitle={info && (
          <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
            <Typography variant="body2" sx={{ color: "text.secondary" }}>File:</Typography>
            <Mono muted>{info.logFile}</Mono>
            <CopyButton text={info.logFile} label="Copy the path" />
          </Stack>
        )}
      />
      <LogViewer
        stream={logStreams.kitout()}
        height="calc(100vh - 210px)"
        title={<Typography variant="h3" component="h2">What kitout did</Typography>}
      />
    </>
  );
}
