import { useEffect, useState } from "react";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { api } from "../api/client.ts";
import type { EnvVarView, ServiceView, TeamValueView } from "../api/types.ts";
import { EnvEditor } from "../components/EnvEditor.tsx";
import { AppDialog, ErrorAlert, Mono } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { pageHref } from "../lib/route.ts";

/** The environment variables of one service; shared ones come on top unless the service sets the same name. */
export function EnvDialog({ service, onClose, onSaved }: { service: ServiceView | null; onClose: () => void; onSaved: () => void }) {
  const [vars, setVars] = useState<EnvVarView[] | null>(null);
  const [shared, setShared] = useState<EnvVarView[]>([]);
  const [team, setTeam] = useState<TeamValueView[]>([]);
  const [error, setError] = useState<string>();
  const { busy, run } = useAction();

  useEffect(() => {
    if (!service) {
      return;
    }
    setVars(null);
    setError(undefined);
    api.serviceEnv(service.id).then(setVars).catch((e: Error) => setError(e.message));
    api.envShared().then(setShared).catch(() => setShared([]));
    api.team().then((t) => setTeam([...t.variables, ...t.secrets].filter((v) => v.scope === service.id))).catch(() => setTeam([]));
  }, [service]);

  const importFile = (text: string) => {
    if (!service) {
      return;
    }
    void run("import", () => api.importServiceEnv(service.id, text),
      (r) => `Imported ${r.added.length + r.secrets.length} variable${r.added.length + r.secrets.length === 1 ? "" : "s"}`
        + (r.skipped.length ? `; skipped ${r.skipped.join("; ")}` : ""))
      .then(() => api.serviceEnv(service.id).then(setVars));
  };

  const save = async (next: EnvVarView[]) => {
    if (!service) {
      return;
    }
    const running = service.state === "RUNNING" || service.state === "STARTING";
    const saved = await run("save", () => api.saveServiceEnv(service.id, next),
      `Saved the variables of ${service.name}${running ? "; restart it to use them" : ""}`);
    if (saved) {
      setVars(saved);
      onSaved();
    }
  };

  return (
    <AppDialog open={service !== null} maxWidth="md" title={`Environment of ${service?.name ?? ""}`} onClose={onClose}>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        <b>Run</b> variables are passed to {service?.name} whenever kitout starts it, in every mode: as <code>docker run -e NAME</code> for a
        registry image (the value never appears on the command line), in the process environment for a local build. <b>Build</b> variables
        go to <code>./mvnw</code> or <code>./gradlew</code> when kitout builds it (e.g. <code>MAVEN_OPTS</code>, a token for a private
        repository). Changes apply from the next start or build.
      </Typography>
      {shared.length > 0 && (
        <Typography variant="body2">
          Also gets the shared variables from <Link href={pageHref("settings")} onClick={onClose}>Settings</Link>:{" "}
          {shared.map((v) => <Mono key={v.name}>{v.name} </Mono>)}. A variable here with the same name wins.
        </Typography>
      )}
      <ErrorAlert message={error} />
      <EnvEditor initial={vars} saving={busy === "save"} onSave={(v) => void save(v)} team={team}
        onImport={importFile} importing={busy === "import"}
        hint="Type a variable name (MONGO_HOST) or a Quarkus property (quarkus.log.level)." />
    </AppDialog>
  );
}
