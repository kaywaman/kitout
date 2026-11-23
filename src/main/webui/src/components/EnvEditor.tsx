import { useEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import Chip from "@mui/material/Chip";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import UploadFileOutlined from "@mui/icons-material/UploadFileOutlined";
import type { EnvVarView, TeamValueView } from "../api/types.ts";
import { ActionIcon, Mono, Tag } from "./ui.tsx";
import { COMMON_VARIABLES, envName, looksSecret, type EnvUse } from "../lib/env.ts";
import { MONO } from "../theme.ts";

/** When a variable applies, as the editor offers it. */
const USES: { value: EnvUse; label: string; hint: string }[] = [
  { value: "RUN", label: "Run", hint: "When it starts" },
  { value: "BUILD", label: "Build", hint: "When kitout builds it" },
  { value: "BOTH", label: "Run and build", hint: "Both" },
];

/** One row while editing. `value` is null for a stored secret that is kept unchanged. */
interface Row {
  key: number;
  name: string;
  value: string | null;
  secret: boolean;
  stored: boolean;
  use: EnvUse;
}

let nextKey = 1;
const toRows = (vars: EnvVarView[]): Row[] =>
  vars.map((v) => ({ key: nextKey++, name: v.name, value: v.secret ? null : v.value, secret: v.secret, stored: v.secret && v.set,
    use: (v.use as EnvUse | undefined) ?? "RUN" }));

/**
 * Edits the environment variables of one scope (shared, or one service). Names may be typed as
 * Quarkus properties; the variable name Quarkus reads is shown next to them. A secret's value is
 * never shown: leave it empty to keep the stored one. Each variable says when it applies: when the
 * service runs, when kitout builds it, or both.
 */
export function EnvEditor({ initial, saving, onSave, hint, team = [], onImport, importing = false }: {
  initial: EnvVarView[] | null;
  saving: boolean;
  onSave: (vars: EnvVarView[]) => void;
  hint?: string;
  /** What the team file sets for this scope: shown read-only; a row here with the same name wins. */
  team?: TeamValueView[];
  /** Takes over a .env file; the editor reloads from `initial` afterwards. */
  onImport?: (text: string) => void;
  importing?: boolean;
}) {
  const [rows, setRows] = useState<Row[]>([]);
  const [dirty, setDirty] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (initial) {
      setRows(toRows(initial));
      setDirty(false);
    }
  }, [initial]);

  const change = (key: number, patch: Partial<Row>) => {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...patch } : r)));
    setDirty(true);
  };
  const add = (name = "", secret = looksSecret(name), use: EnvUse = "RUN") => {
    setRows((rs) => [...rs, { key: nextKey++, name, value: "", secret, stored: false, use }]);
    setDirty(true);
  };
  const remove = (key: number) => {
    setRows((rs) => rs.filter((r) => r.key !== key));
    setDirty(true);
  };

  const names = rows.map((r) => envName(r.name)).filter(Boolean);
  const duplicate = names.find((n, i) => names.indexOf(n) !== i);
  const missingSecret = rows.find((r) => r.secret && !r.stored && !r.value && r.name.trim());
  const present = new Set(names);
  team.forEach((t) => present.add(t.name));

  const save = () => onSave(rows.filter((r) => r.name.trim()).map((r) => ({
    name: r.name.trim(),
    value: r.secret ? (r.value ? r.value : null) : (r.value ?? ""),   // empty secret = keep the stored value
    secret: r.secret,
    set: r.stored,
    use: r.use,
  }) as EnvVarView));

  return (
    <Stack spacing={1.5}>
      {hint && <Typography variant="body2" sx={{ color: "text.secondary" }}>{hint}</Typography>}
      {team.length > 0 && (
        <Box sx={{ border: 1, borderColor: "divider", borderRadius: 1.5, px: 1.5, py: 1 }}>
          <Typography variant="caption" sx={{ color: "text.secondary", display: "block", mb: 0.5 }}>
            From the team file (read-only). Add a variable with the same name below to use your own value.
          </Typography>
          <Stack spacing={0.5}>
            {team.map((t) => {
              const overridden = names.includes(t.name);
              return (
                <Stack key={t.name} direction="row" spacing={1} sx={{ alignItems: "center", opacity: overridden ? 0.55 : 1 }}>
                  <Mono>{t.name}</Mono>
                  {t.secret
                    ? <Tag label={t.set ? "your secret is stored" : "enter yours in Settings, Team setup"} color={t.set ? "success" : "warning"} />
                    : <Mono muted>{t.value}</Mono>}
                  {overridden && <Tag label="you override it" />}
                </Stack>
              );
            })}
          </Stack>
        </Box>
      )}
      {rows.length === 0 && <Typography variant="body2" sx={{ color: "text.secondary" }}>No variables yet.</Typography>}
      {rows.map((r) => {
        const converted = envName(r.name);
        return (
          <Stack key={r.key} direction="row" spacing={1} sx={{ alignItems: "flex-start" }}>
            <TextField label="Name" value={r.name} onChange={(e) => change(r.key, { name: e.target.value })} sx={{ flex: "1 1 40%" }}
              helperText={r.name.trim() && converted !== r.name.trim() ? `→ ${converted}` : " "}
              slotProps={{ htmlInput: { spellCheck: false, style: { fontFamily: MONO } } }} />
            <TextField label="Value" type={r.secret ? "password" : "text"} value={r.value ?? ""} autoComplete="off"
              onChange={(e) => change(r.key, { value: e.target.value })} sx={{ flex: "1 1 50%" }}
              placeholder={r.secret && r.stored ? "•••• stored, leave empty to keep" : ""}
              helperText={r.secret ? "Kept in the keyring, never shown" : " "}
              slotProps={{ htmlInput: { spellCheck: false, style: { fontFamily: MONO } } }} />
            <TextField select label="Used for" value={r.use} onChange={(e) => change(r.key, { use: e.target.value as EnvUse })}
              sx={{ flex: "0 0 150px" }} helperText={USES.find((u) => u.value === r.use)?.hint ?? " "}
              slotProps={{ htmlInput: { "aria-label": `When ${r.name || "the variable"} applies` } }}>
              {USES.map((u) => <MenuItem key={u.value} value={u.value}>{u.label}</MenuItem>)}
            </TextField>
            <Tooltip title="Secret: kept in the keyring, never shown again">
              <Box sx={{ pt: 0.5 }}>
                <Checkbox checked={r.secret} onChange={(e) => change(r.key, { secret: e.target.checked, value: "", stored: false })}
                  slotProps={{ input: { "aria-label": `${r.name || "Variable"} is secret` } }} />
              </Box>
            </Tooltip>
            <Box sx={{ pt: 0.75 }}>
              <ActionIcon label={`Remove ${r.name || "variable"}`} color="error" onClick={() => remove(r.key)}>
                <DeleteOutlined fontSize="small" />
              </ActionIcon>
            </Box>
          </Stack>
        );
      })}
      {duplicate && <Alert severity="warning">{duplicate} is listed twice.</Alert>}
      {missingSecret && <Alert severity="info">Enter a value for the secret {envName(missingSecret.name)}.</Alert>}
      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap", alignItems: "center" }}>
        <Button startIcon={<AddRounded />} onClick={() => add()}>Add variable</Button>
        {COMMON_VARIABLES.filter((c) => !present.has(c.name)).map((c) => (
          <Chip key={c.name} variant="outlined" label={`+ ${c.label}`} onClick={() => add(c.name, c.secret, c.use)} title={c.name} />
        ))}
        <Box sx={{ flexGrow: 1 }} />
        {onImport && (
          <>
            <input ref={fileInput} type="file" hidden accept=".env,text/plain,*" aria-label="Choose a .env file"
              onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                if (file) {
                  void file.text().then(onImport);
                }
              }} />
            <Tooltip title={dirty ? "Save or discard your changes first" : "Take over a .env file, e.g. the one you use with docker compose. Passwords and keys go to the keyring."}>
              <span>
                <Button color="inherit" startIcon={<UploadFileOutlined />} disabled={dirty} loading={importing}
                  onClick={() => fileInput.current?.click()}>Import .env</Button>
              </span>
            </Tooltip>
          </>
        )}
        <Button variant="contained" disabled={!dirty || !!duplicate || !!missingSecret} loading={saving} onClick={save}>Save variables</Button>
      </Stack>
    </Stack>
  );
}
