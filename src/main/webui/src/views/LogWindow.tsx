import { useEffect } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { logStreams } from "../api/client.ts";
import { LogViewer } from "../components/LogViewer.tsx";
import { Mono } from "../components/ui.tsx";

/** A separate browser window with the live log of one service or build. */
export function LogWindow({ kind, id }: { kind: "service" | "build"; id: string }) {
  const name = kind === "service" ? id : `Build ${id}`;
  useEffect(() => {
    document.title = `${name} log - kitout`;
  }, [name]);
  return (
    <Box sx={{ p: 1, bgcolor: "background.default" }}>
      <LogViewer
        stream={kind === "service" ? logStreams.service(id) : logStreams.build(id)}
        height="calc(100vh - 16px)"
        title={
          <>
            <Typography variant="overline" sx={{ color: "text.secondary", display: "block", lineHeight: 1.4 }}>
              {kind === "service" ? "Service log" : "Build log"}
            </Typography>
            <Mono>{name}</Mono>
          </>
        }
      />
    </Box>
  );
}
