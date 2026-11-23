import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import LinearProgress from "@mui/material/LinearProgress";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { api } from "../api/client.ts";
import type { TeamSuggestionsView } from "../api/types.ts";
import { Mono } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { MONO } from "../theme.ts";

/**
 * Build a team setup from scratch: what a developer starting a team usually has is one compose file
 * with the infrastructure (database, Kafka, tools), maybe no services and no CI/CD publishing images
 * yet. Only the essentials, prefilled from what kitout found; the rest has sensible defaults and can
 * grow later (services are added as they come).
 */
export function CreateTeamForm({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const [found, setFound] = useState<TeamSuggestionsView | null>(null);
  const [name, setName] = useState("");
  const [compose, setCompose] = useState<string[]>([]);
  const [otherCompose, setOtherCompose] = useState("");
  const [envFile, setEnvFile] = useState("");
  const [gitlab, setGitlab] = useState("");
  const [cloneFolder, setCloneFolder] = useState("~/work");
  const [images, setImages] = useState(false);
  const [registry, setRegistry] = useState("");
  const [record, setRecord] = useState(true);
  const { busy, run } = useAction();

  useEffect(() => {
    api.teamSuggestions().then((s) => {
      setFound(s);
      setGitlab(s.gitlabHost);
      setCloneFolder(s.cloneFolder || "~/work");
      setCompose(s.composeFiles.slice(0, 1));
      const first = s.composeFiles[0];
      const env = first ? s.envFiles.find((e) => e.startsWith(first.slice(0, first.lastIndexOf("/") + 1))) : undefined;
      setEnvFile(env ?? "");
    }).catch(() => setFound({ gitlabHost: "", cloneFolder: "~/work", composeFiles: [], envFiles: [], requirements: [], allowed: true } as TeamSuggestionsView));
  }, []);

  const files = [...compose, ...otherCompose.split("\n").map((l) => l.trim()).filter(Boolean)];
  const toggle = (f: string) => setCompose((c) => (c.includes(f) ? c.filter((x) => x !== f) : [...c, f]));

  const create = () => void run("create", () => api.createTeam({
    team: name.trim(), gitlabHost: gitlab.trim(), cloneFolder: cloneFolder.trim(), registryHost: images ? registry.trim() : "",
    imagePath: "", composeFiles: files, envFile: envFile.trim(), recordRequirements: record,
  }), `Team setup for ${name.trim()} written and followed`).then((s) => s && onDone());

  if (!found) {
    return <LinearProgress />;
  }

  return (
    <Stack spacing={2.5} sx={{ maxWidth: 760 }}>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        No team file yet? Answer the essentials; kitout writes a team setup on this machine (<Mono>~/.config/kitout/kitout-team.local.json</Mono>)
        and works with it like with a team's file. Add services as they come, and export it for your team when you are ready.
      </Typography>

      <TextField label="Team name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus placeholder="acme-shop"
        sx={{ maxWidth: 360 }} />

      <Box>
        <Typography variant="body2" sx={{ fontWeight: 650, mb: 0.5 }}>Infrastructure: compose file(s)</Typography>
        <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
          The databases, Kafka and tools your services need. kitout starts them before your services. A file inside a git repository
          is stored by repository and path, so it works for everyone who clones it.
        </Typography>
        {found.composeFiles.length === 0 && <Typography variant="body2" sx={{ color: "text.secondary" }}>No compose files found in the usual folders.</Typography>}
        {found.composeFiles.map((f) => (
          <Box key={f}>
            <FormControlLabel control={<Checkbox checked={compose.includes(f)} onChange={() => toggle(f)} />}
              label={<Mono>{f}</Mono>} />
          </Box>
        ))}
        <TextField label="Other compose files" multiline minRows={1} value={otherCompose} onChange={(e) => setOtherCompose(e.target.value)}
          placeholder="~/infra/docker-compose-dev.yml" helperText="One per line, below your home folder. Optional."
          fullWidth sx={{ mt: 1 }} slotProps={{ htmlInput: { spellCheck: false, style: { fontFamily: MONO } } }} />
      </Box>

      <TextField label="Variables from a .env (optional)" value={envFile} onChange={(e) => setEnvFile(e.target.value)}
        placeholder={found.envFiles[0] ?? "~/infra/.env"}
        helperText={<>Plain values (e.g. MONGO_HOST) go into the team setup; passwords, secrets, tokens and keys become names only, and your
          values go to the keyring.{found.envFiles.length > 0 && <> Found: {found.envFiles.slice(0, 3).join(", ")}</>}</>}
        slotProps={{ htmlInput: { spellCheck: false, style: { fontFamily: MONO } } }} />

      <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
        <TextField label="Git host (optional)" value={gitlab} onChange={(e) => setGitlab(e.target.value)} sx={{ flex: 1 }}
          placeholder="github.com or gitlab.example.com" helperText="The git host where the projects live. Empty: only what is on this machine (no cloning or pulling)."
          slotProps={{ htmlInput: { spellCheck: false } }} />
        <TextField label="Projects folder" value={cloneFolder} onChange={(e) => setCloneFolder(e.target.value)} sx={{ flex: 1 }}
          helperText="Where the team's repositories are, or will be cloned." slotProps={{ htmlInput: { spellCheck: false } }} />
      </Stack>

      <Box>
        <FormControlLabel control={<Switch checked={images} onChange={(e) => setImages(e.target.checked)} />}
          label="Our CI/CD publishes service images to a registry" />
        {images ? (
          <TextField label="Container registry" value={registry} onChange={(e) => setRegistry(e.target.value)} fullWidth sx={{ mt: 1 }}
            placeholder={gitlab ? `registry.${gitlab}` : "registry.example.com"} slotProps={{ htmlInput: { spellCheck: false } }} />
        ) : (
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            Not yet: services run from local builds (JVM), or in your IDE. Switch to registry images later in the team setup.
          </Typography>
        )}
      </Box>

      <Box>
        <FormControlLabel control={<Switch checked={record} onChange={(e) => setRecord(e.target.checked)} />}
          label="Record this machine's tools as the team's requirements" />
        {record && found.requirements.length > 0 && (
          <Box sx={{ ml: 6 }}>
            {found.requirements.map((r) => (
              <Typography key={r.id} variant="body2" sx={{ color: "text.secondary" }}>{r.name}: <Mono>{r.require}</Mono></Typography>
            ))}
          </Box>
        )}
        {!record && (
          <Alert severity="warning" sx={{ mt: 1 }}>Without requirements kitout cannot tell a teammate that their Java, Maven or Docker differ from yours.</Alert>
        )}
      </Box>

      <Stack direction="row" spacing={1}>
        <Button variant="contained" disabled={!name.trim()} loading={busy === "create"} onClick={create}>Create team setup</Button>
        <Button color="inherit" onClick={onCancel}>Back</Button>
      </Stack>
    </Stack>
  );
}
