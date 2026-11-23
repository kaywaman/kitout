import { useState } from "react";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { api } from "../api/client.ts";
import type { ServiceView } from "../api/types.ts";
import { BuildOptions, DEFAULT_BUILD_OPTIONS, type BuildOptionValues, PROFILE } from "../components/BuildOptions.tsx";
import { AppDialog } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { buildLogHref } from "../lib/route.ts";

/** Profile, mode and skip-tests for one local build. Native builds queue, one at a time. */
export function BuildDialog({ service, onClose }: { service: ServiceView | null; onClose: () => void }) {
  const [options, setOptions] = useState<BuildOptionValues>(DEFAULT_BUILD_OPTIONS);
  const { busy, run } = useAction();

  const submit = async () => {
    if (!service) {
      return;
    }
    const job = await run("build", () => api.build(service.id, options),
      (j) => (j.queuePosition > 1 ? `Queued: native build ${j.queuePosition} in line` : `Building ${service.name}`));
    if (job) {
      onClose();
      window.open(buildLogHref(job.id), `kitout-build-${job.id}`, "width=1100,height=720");
    }
  };

  return (
    <AppDialog
      open={service !== null}
      title={`Build ${service?.name ?? ""}`}
      onClose={onClose}
      actions={
        <>
          <Button color="inherit" onClick={onClose}>Cancel</Button>
          <Button variant="contained" loading={busy === "build"} disabled={!PROFILE.test(options.profile)} onClick={() => void submit()}>
            {options.mode === "NATIVE" ? "Queue native build" : "Build"}
          </Button>
        </>
      }
    >
      <BuildOptions value={options} onChange={setOptions} />
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Builds from scratch (<code>clean package</code>), so nothing from an earlier build is left over.
      </Typography>
    </AppDialog>
  );
}
