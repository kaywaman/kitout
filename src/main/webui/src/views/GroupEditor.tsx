import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import Chip from "@mui/material/Chip";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import CheckRounded from "@mui/icons-material/CheckRounded";
import { api } from "../api/client.ts";
import type { GroupView, ServiceView } from "../api/types.ts";
import { AppDialog, Mono } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";

/** Name, members, BFFs and start style of one group. */
export function GroupEditor({ group, services, onClose }: {
  group: GroupView | null;
  services: ServiceView[];
  onClose: (saved: boolean) => void;
}) {
  const [draft, setDraft] = useState<GroupView | null>(group);
  const { busy, run } = useAction();

  useEffect(() => setDraft(group), [group]);

  if (!draft) {
    return null;
  }

  const toggle = (id: string) => {
    const members = draft.members.includes(id) ? draft.members.filter((m) => m !== id) : [...draft.members, id];
    setDraft({ ...draft, members, bffs: draft.bffs.filter((b) => members.includes(b)) });
  };

  const toggleBff = (id: string) =>
    setDraft({ ...draft, bffs: draft.bffs.includes(id) ? draft.bffs.filter((b) => b !== id) : [...draft.bffs, id] });

  const save = async () => {
    const name = draft.name.trim();
    const renamed = group !== null && !group.suggested && group.name !== "" && group.name !== name;
    const saved = await run("save", async () => {
      const result = await api.saveGroup({ ...draft, name });
      if (renamed) {
        await api.deleteGroup(group.name);   // a rename replaces the old group instead of copying it
      }
      return result;
    }, `Saved ${name}`);
    if (saved) {
      onClose(true);
    }
  };

  return (
    <AppDialog
      open
      maxWidth="md"
      title={group?.name ? `Edit ${group.name}` : "New group"}
      onClose={() => onClose(false)}
      actions={
        <>
          <Button color="inherit" onClick={() => onClose(false)}>Cancel</Button>
          <Button variant="contained" loading={busy === "save"} disabled={!draft.name.trim() || draft.members.length === 0} onClick={() => void save()}>
            Save group
          </Button>
        </>
      }
    >
      <TextField label="Name" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="shop"
        sx={{ mt: 1, maxWidth: 360 }} autoFocus />
      <Box>
        <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.75 }}>
          Services <Box component="span" sx={{ color: "text.secondary", fontWeight: 400 }}>({draft.members.length} selected)</Box>
        </Typography>
        <Paper variant="outlined" sx={{ p: 1, maxHeight: 280, overflow: "auto", display: "grid", gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", md: "1fr 1fr 1fr" } }}>
          {services.map((s) => (
            <FormControlLabel key={s.id} sx={{ mr: 0 }}
              control={<Checkbox size="small" checked={draft.members.includes(s.id)} onChange={() => toggle(s.id)} />}
              label={<>{s.name} <Mono muted>:{s.port}</Mono></>} />
          ))}
        </Paper>
      </Box>
      {draft.members.length > 0 && (
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            Entry points (BFFs){" "}
            <Box component="span" sx={{ color: "text.secondary", fontWeight: 400 }}>
              start together, once the other services are up. {draft.bffs.length === 0 ? "None chosen." : `${draft.bffs.length} chosen.`}
            </Box>
          </Typography>
          <Stack direction="row" spacing={0.75} useFlexGap sx={{ flexWrap: "wrap", mt: 0.75 }}>
            {draft.members.map((id) => {
              const chosen = draft.bffs.includes(id);
              return (
                <Chip key={id} label={services.find((s) => s.id === id)?.name ?? id} onClick={() => toggleBff(id)}
                  variant={chosen ? "soft" : "outlined"} color={chosen ? "primary" : "default"}
                  icon={chosen ? <CheckRounded /> : undefined} aria-pressed={chosen}
                  aria-label={`${chosen ? "Remove" : "Make"} ${services.find((s) => s.id === id)?.name ?? id} ${chosen ? "as BFF" : "a BFF"}`} />
              );
            })}
          </Stack>
        </Box>
      )}
      <FormControlLabel
        control={<Checkbox checked={draft.sequentialStart} onChange={(e) => setDraft({ ...draft, sequentialStart: e.target.checked })} />}
        label="Start services one after another (avoids colliding database migrations)" />
    </AppDialog>
  );
}
