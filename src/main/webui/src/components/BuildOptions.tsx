import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import ToggleButton from "@mui/material/ToggleButton";
import ToggleButtonGroup from "@mui/material/ToggleButtonGroup";
import Typography from "@mui/material/Typography";
import { api } from "../api/client.ts";
import { pageHref } from "../lib/route.ts";

export interface BuildOptionValues {
  profile: string;
  mode: "JVM" | "NATIVE";
  skipTests: boolean;
}

/** Mirrors BuildRequest's rule; the backend stays the judge. */
export const PROFILE = /^[A-Za-z0-9_-]{1,32}$/;
const OTHER = "\u0000other";

export const DEFAULT_BUILD_OPTIONS: BuildOptionValues = { profile: "dev", mode: "JVM", skipTests: false };

/**
 * Profile, mode and skip-tests for local builds: the same controls in the build dialog of a
 * service and on the Builds page. The profiles come from Settings; "Other" takes any profile.
 * The profile is a Quarkus build setting: Spring Boot and plain Java projects choose theirs when they run.
 */
export function BuildOptions({ value, onChange, layout = "column", count = 1 }: {
  value: BuildOptionValues;
  onChange: (next: BuildOptionValues) => void;
  layout?: "column" | "row";
  /** How many services the options apply to; shapes the native note. */
  count?: number;
}) {
  const [profiles, setProfiles] = useState<string[]>(["dev", "qa", "prod"]);
  const [other, setOther] = useState(false);
  useEffect(() => {
    api.settings().then((s) => s.build.profiles?.length && setProfiles(s.build.profiles)).catch(() => {});
  }, []);
  const set = (patch: Partial<BuildOptionValues>) => onChange({ ...value, ...patch });

  return (
    <Stack spacing={1.5}>
      <Stack direction={layout === "row" ? { xs: "column", md: "row" } : "column"} spacing={layout === "row" ? 3 : 1.5}
        sx={{ alignItems: layout === "row" ? { md: "center" } : "flex-start" }}>
        <Stack spacing={0.5}>
          <Typography variant="overline" component="h3" id="build-profile" sx={{ color: "text.secondary", lineHeight: 1.5 }}>Profile</Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <ToggleButtonGroup exclusive size="small" color="primary" value={other ? OTHER : value.profile} aria-labelledby="build-profile"
              onChange={(_, v: string | null) => {
                if (v === OTHER) {
                  setOther(true);
                  set({ profile: "" });
                } else if (v) {
                  setOther(false);
                  set({ profile: v });
                }
              }}>
              {profiles.map((p) => <ToggleButton key={p} value={p} sx={{ px: 2 }}>{p}</ToggleButton>)}
              <ToggleButton value={OTHER} sx={{ px: 2 }}>Other…</ToggleButton>
            </ToggleButtonGroup>
            {other && (
              <TextField autoFocus placeholder="e.g. staging" value={value.profile} sx={{ width: 160 }}
                error={value.profile !== "" && !PROFILE.test(value.profile)}
                onChange={(e) => set({ profile: e.target.value.trim() })}
                slotProps={{ htmlInput: { "aria-label": "Build profile", spellCheck: false, maxLength: 32 } }} />
            )}
          </Stack>
        </Stack>
        <Stack spacing={0.5}>
          <Typography variant="overline" component="h3" id="build-mode" sx={{ color: "text.secondary", lineHeight: 1.5 }}>Mode</Typography>
          <ToggleButtonGroup exclusive size="small" color="primary" value={value.mode} aria-labelledby="build-mode"
            onChange={(_, v: "JVM" | "NATIVE" | null) => v && set({ mode: v })}>
            <ToggleButton value="JVM" sx={{ px: 2 }}>JVM</ToggleButton>
            <ToggleButton value="NATIVE" sx={{ px: 2 }}>Native</ToggleButton>
          </ToggleButtonGroup>
        </Stack>
        <FormControlLabel sx={{ mt: layout === "row" ? { md: 2.5 } : 0 }}
          control={<Checkbox checked={value.skipTests} onChange={(e) => set({ skipTests: e.target.checked })} />} label="Skip tests" />
      </Stack>
      {other && value.profile !== "" && !PROFILE.test(value.profile) && (
        <Alert severity="error">A profile is up to 32 letters, digits, "-" or "_".</Alert>
      )}
      {value.profile === "prod" && (
        <Alert severity="warning">
          The prod profile builds with production settings. That is allowed locally, but the result may try to reach production
          systems if you run it.
        </Alert>
      )}
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        {value.mode === "NATIVE"
          ? <>Native builds run one after another and need about 6 GB of free memory each{count > 1 ? "; the others wait in the queue below" : ""}. </>
          : count > 1 ? "JVM builds all start at once. " : ""}
        {value.mode === "NATIVE" && count <= 1 && <>Others wait in the <Link href={pageHref("builds")}>queue</Link>. </>}
        The profile and native mode apply to Quarkus projects; Spring Boot and plain Java projects are built as they are (JVM) and
        get their profile when they run.
      </Typography>
    </Stack>
  );
}
