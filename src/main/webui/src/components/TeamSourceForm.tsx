import { useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import type { TeamSourceRequest, TeamSourceView } from "../api/types.ts";
import { missingFields, splitGitLabLink, type TeamSourceFields } from "../lib/teamSource.ts";

const MONO = { htmlInput: { spellCheck: false, style: { fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" } } };

/**
 * Where the team file is: in a git repository by repository and path, the same way the team file names its
 * compose files, or a file on this machine. A GitLab or GitHub link pasted into any field is split into the fields.
 */
export function TeamSourceForm({ suggested, submitLabel, busy, autoFocus, onSubmit }: {
  suggested?: TeamSourceView | null;
  submitLabel: string;
  busy: boolean;
  autoFocus?: boolean;
  onSubmit: (source: TeamSourceRequest) => void;
}) {
  const [mode, setMode] = useState<"gitlab" | "file">(suggested?.file ? "file" : "gitlab");
  const [fields, setFields] = useState<TeamSourceFields>({
    host: suggested?.host ?? "",
    repo: suggested?.repo ?? "",
    path: suggested?.path ?? "",
    ref: suggested?.ref ?? "",
  });
  const [file, setFile] = useState(suggested?.file ?? "");
  const [split, setSplit] = useState(false);

  const change = (key: keyof TeamSourceFields, value: string) => {
    const link = splitGitLabLink(value);
    if (link) {
      setFields((f) => ({ ...f, path: "", ref: "", ...link }));
      setSplit(true);
      return;
    }
    setFields((f) => ({ ...f, [key]: value }));
  };

  const missing = mode === "gitlab" ? missingFields(fields) : file.trim() ? [] : ["file"];

  return (
    <Stack component="form" spacing={1.5} sx={{ maxWidth: 760 }}
      onSubmit={(e) => {
        e.preventDefault();
        if (missing.length > 0) {
          return;
        }
        // empty fields mean "not given" (the server reads blanks that way): empty path and branch are the defaults
        onSubmit(mode === "gitlab"
          ? { location: "", host: fields.host.trim(), repo: fields.repo.trim(), path: fields.path.trim(), ref: fields.ref.trim(), file: "" }
          : { location: "", host: "", repo: "", path: "", ref: "", file: file.trim() });
      }}>
      <ToggleButtonGroup exclusive size="small" color="primary" value={mode} aria-label="Where the team file is"
        onChange={(_, v) => v && setMode(v)} sx={{ alignSelf: "flex-start" }}>
        <ToggleButton value="gitlab">In a git repository</ToggleButton>
        <ToggleButton value="file">File on this machine</ToggleButton>
      </ToggleButtonGroup>

      {mode === "gitlab" ? (
        <>
          <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "minmax(0, 2fr) minmax(0, 3fr)" } }}>
            <TextField label="Git host" value={fields.host} onChange={(e) => change("host", e.target.value)} required
              placeholder="github.com or gitlab.example.com" slotProps={MONO} />
            <TextField label="Repository" value={fields.repo} onChange={(e) => change("repo", e.target.value)} required autoFocus={autoFocus}
              placeholder="acme/infrastructure" helperText="group/project (GitHub: owner/repo), as in the team file's compose entries (repo)" slotProps={MONO} />
            <TextField label="Branch" value={fields.ref} onChange={(e) => change("ref", e.target.value)}
              placeholder="main" helperText="Empty: main" slotProps={MONO} />
            <TextField label="Path in the repository" value={fields.path} onChange={(e) => change("path", e.target.value)}
              placeholder="kitout-team.json" helperText="From the repository root (path). Empty: kitout-team.json" slotProps={MONO} />
          </Box>
          <Typography variant="caption" sx={{ color: "text.secondary" }}>
            {split
              ? "The link was split into repository, branch and path."
              : "You can also paste the link your git server shows for the file (GitLab or GitHub) into any field; kitout splits it."}
          </Typography>
        </>
      ) : (
        <TextField label="Team file" value={file} onChange={(e) => setFile(e.target.value)} required autoFocus={autoFocus}
          placeholder="~/work/acme/infrastructure/kitout/kitout-team.json"
          helperText="A kitout-team.json on this machine, e.g. in a checkout. kitout reads it again when it changes." slotProps={MONO} />
      )}

      <Box>
        <Button type="submit" variant="contained" disabled={missing.length > 0} loading={busy}
          title={missing.length > 0 ? `Missing: ${missing.join(", ")}` : undefined}>{submitLabel}</Button>
      </Box>
    </Stack>
  );
}
