import { useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import FormControlLabel from "@mui/material/FormControlLabel";
import Link from "@mui/material/Link";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import PlayArrowRounded from "@mui/icons-material/PlayArrowRounded";
import StopRounded from "@mui/icons-material/StopRounded";
import LinkOffRounded from "@mui/icons-material/LinkOffRounded";
import NoteAddOutlined from "@mui/icons-material/NoteAddOutlined";
import ViewInArOutlined from "@mui/icons-material/ViewInArOutlined";
import { api, logStreams } from "../api/client.ts";
import type { ComposeEnvironmentView, ContainerView } from "../api/types.ts";
import { LogViewer } from "../components/LogViewer.tsx";
import { ActionIcon, EmptyState, ErrorAlert, Mono, PageHeader, Section, StatusChip, Tag } from "../components/ui.tsx";
import { useAction } from "../hooks/useAction.ts";
import { usePolling } from "../hooks/usePolling.ts";
import { ago } from "../lib/format.ts";
import { publishedPorts } from "../lib/ports.ts";
import { pageHref } from "../lib/route.ts";
import { TeamRepositories } from "./TeamRepositories.tsx";
import { ComposeFilePicker } from "./ComposeFilePicker.tsx";
import { composeJobLook, containerLook, environmentLook } from "../lib/status.ts";

/**
 * The containers the services need. Each docker-compose-<environment>.yml in the folders from
 * Settings, and each compose file the developer picked by hand, is one environment with Up and
 * Down; below, what `docker ps` shows on this machine.
 */
export function DockerPage() {
  const [showStopped, setShowStopped] = useState(false);
  const overview = usePolling(() => api.docker(showStopped), 3000, [showStopped]);
  const [picking, setPicking] = useState(false);
  const [outputOf, setOutputOf] = useState<string | null>(null);   // compose job whose output is shown
  const { busy, run } = useAction();
  const data = overview.data;

  const act = async (env: ComposeEnvironmentView, up: boolean) => {
    const job = await run(`${env.id}-${up ? "up" : "down"}`, () => (up ? api.composeUp(env.id) : api.composeDown(env.id)),
      `docker compose ${up ? "up" : "down"} started for ${env.id}`);
    if (job) {
      setOutputOf(job.id);
      await overview.refresh();
    }
  };

  const shownJob = data?.environments.map((e) => e.lastJob).find((j) => j && j.id === outputOf) ?? null;
  const missing = data?.folders.filter((f) => !f.exists) ?? [];
  const gone = data?.pickedFiles.filter((f) => !f.exists) ?? [];
  const forget = (path: string, name: string) =>
    void run(`forget-${path}`, () => api.forgetComposeFile(path), `Forgot ${name}; the file is unchanged`).then(() => overview.refresh());

  return (
    <>
      <PageHeader title="Docker"
        subtitle={<Typography variant="body2" sx={{ color: "text.secondary" }}>
          Databases, brokers and tools your services need, from your compose files. Your microservices do not belong here:
          kitout starts them from the <Link href={pageHref("services")}>Services</Link> page (as "Registry image" with the tag you
          choose), and their containers show up below as <b>kitout · name</b>.
        </Typography>}>
        <Button variant="outlined" startIcon={<NoteAddOutlined />} onClick={() => setPicking(true)}>Pick compose file…</Button>
      </PageHeader>
      <ErrorAlert message={overview.error?.message} />
      <TeamRepositories onChanged={() => void overview.refresh()} />
      {data && !data.available && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Docker is not available: {data.problem}. Start Docker (<code>systemctl start docker</code>) and check that your user may
          use it (member of the <code>docker</code> group).
        </Alert>
      )}
      {missing.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {missing.length === 1 ? "This compose folder does not exist" : "These compose folders do not exist"}:{" "}
          {missing.map((f) => <Mono key={f.path}>{f.path} </Mono>)}. Fix it in <Link href={pageHref("settings")}>Settings</Link>.
        </Alert>
      )}

      {gone.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {gone.length === 1 ? "A picked compose file no longer exists" : "Some picked compose files no longer exist"}:
          <Stack spacing={0.5} sx={{ mt: 1 }}>
            {gone.map((f) => (
              <Stack key={f.path} direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Mono>{f.path}</Mono>
                <Button size="small" color="inherit" loading={busy === `forget-${f.path}`} onClick={() => forget(f.path, f.path)}>Forget</Button>
              </Stack>
            ))}
          </Stack>
        </Alert>
      )}

      {data && data.environments.length === 0 && (
        <Box sx={{ mb: 2.5 }}>
          <EmptyState title="No compose environments yet" icon={<ViewInArOutlined />}>
            Pick any compose file you work with, whatever its name. Or add the folder with your team's files in{" "}
            <Link href={pageHref("settings")}>Settings, Docker</Link>: there, <code>docker-compose-dev.yml</code> becomes the
            environment <code>dev</code>, <code>docker-compose-test.yml</code> the environment <code>test</code>.
            <Box sx={{ mt: 2 }}>
              <Button variant="contained" startIcon={<NoteAddOutlined />} onClick={() => setPicking(true)}>Pick compose file…</Button>
            </Box>
          </EmptyState>
        </Box>
      )}

      {data && data.environments.length > 0 && (
        <Section title="Environments" dense
          description={<>Up runs <code>docker compose -f &lt;file&gt; up -d</code>; Down runs <code>down</code>, and the volumes (your data) stay.</>}>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={{ width: 160 }}>Environment</TableCell>
                  <TableCell sx={{ width: 140 }}>Containers</TableCell>
                  <TableCell>Compose file</TableCell>
                  <TableCell sx={{ width: 180 }}>Last action</TableCell>
                  <TableCell align="right" sx={{ width: 240 }}><Box component="span" sx={{ position: "absolute", left: -9999 }}>Actions</Box></TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.environments.map((e) => {
                  const jobRunning = e.lastJob?.status === "RUNNING";
                  return (
                    <TableRow key={e.id} hover selected={!!e.lastJob && e.lastJob.id === outputOf}>
                      <TableCell>
                        <Stack direction="row" spacing={0.75} sx={{ alignItems: "center" }}>
                          <Typography sx={{ fontWeight: 650 }}>{e.id}</Typography>
                          {e.picked && <Tag label="picked" color="primary" title="You picked this file; it is not from a compose folder" />}
                          {e.team && <Tag label="team" color="success" title="Defined in the team file: the same files for everyone, wherever the repository is cloned" />}
                          {e.downloaded && (
                            <Tag label="from the repository" color="info"
                              title="The repository is not cloned: kitout read the compose file from the git server and keeps a copy. It is read again every hour and on Read again in Settings, Team setup" />
                          )}
                          {e.autoStart && <Tag label="starts automatically" title="kitout runs docker compose up -d for it when kitout starts and before a service starts" />}
                        </Stack>
                        {e.description && <Typography variant="caption" sx={{ color: "text.secondary" }}>{e.description}</Typography>}
                      </TableCell>
                      <TableCell><StatusChip look={environmentLook(e.running, e.total)} /></TableCell>
                      <TableCell sx={{ maxWidth: 420 }}>
                        {e.files.map((f, i) => (
                          <Box key={f} sx={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }} title={f}>
                            {i > 0 && <Typography component="span" variant="caption" sx={{ color: "text.secondary", mr: 0.5 }}>+</Typography>}
                            <Mono muted>{f}</Mono>
                          </Box>
                        ))}
                        {e.profiles.length > 0 && (
                          <Typography variant="caption" sx={{ color: "text.secondary" }}>profiles: {e.profiles.join(", ")}</Typography>
                        )}
                        {e.missing.length > 0 && (
                          <Typography variant="body2" sx={{ color: "warning.main" }}>
                            {e.missing.join("; ")}.
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        {e.lastJob ? (
                          <Stack spacing={0.25} sx={{ alignItems: "flex-start" }}>
                            <StatusChip look={composeJobLook(e.lastJob.action, e.lastJob.status)} />
                            <Typography variant="caption" sx={{ color: "text.secondary" }}>{ago(e.lastJob.startedAt)}</Typography>
                          </Stack>
                        ) : <Typography variant="body2" sx={{ color: "text.secondary" }}>None yet</Typography>}
                      </TableCell>
                      <TableCell align="right">
                        <Stack direction="row" spacing={0.5} sx={{ justifyContent: "flex-end", alignItems: "center" }}>
                          <Button variant="contained" startIcon={<PlayArrowRounded />} disabled={!data.available || jobRunning || e.missing.length > 0}
                            loading={busy === `${e.id}-up`} loadingPosition="start" onClick={() => void act(e, true)}>
                            Up
                          </Button>
                          <Button variant="outlined" color="inherit" startIcon={<StopRounded />} disabled={!data.available || jobRunning || e.total === 0}
                            loading={busy === `${e.id}-down`} loadingPosition="start" onClick={() => void act(e, false)}>
                            Down
                          </Button>
                          <ActionIcon label={`Output of the last action for ${e.id}`} why="No action yet" disabled={!e.lastJob}
                            onClick={() => setOutputOf(e.lastJob && e.lastJob.id !== outputOf ? e.lastJob.id : null)}>
                            <ArticleOutlined fontSize="small" />
                          </ActionIcon>
                          {e.picked && (
                            <ActionIcon label={`Forget ${e.id} (the file stays on disk)`} disabled={e.running > 0 || jobRunning}
                              why={`Run Down first, so ${e.id}'s containers do not run on unnoticed`} onClick={() => forget(e.file, e.id)}>
                              <LinkOffRounded fontSize="small" />
                            </ActionIcon>
                          )}
                        </Stack>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        </Section>
      )}

      {shownJob && (
        <Box sx={{ mb: 2.5 }}>
          <LogViewer
            stream={logStreams.compose(shownJob.id)}
            height="360px"
            title={
              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                <Typography variant="h3" component="h2">docker compose {shownJob.action.toLowerCase()} · {shownJob.environment}</Typography>
                <StatusChip look={composeJobLook(shownJob.action, shownJob.status)} />
              </Stack>
            }
            actions={<Button color="inherit" onClick={() => setOutputOf(null)}>Close</Button>}
          />
        </Box>
      )}

      <ComposeFilePicker open={picking} onClose={() => setPicking(false)} onPicked={() => void overview.refresh()} />

      {data && (
        <Section title="Containers" dense description="docker ps on this machine"
          action={<FormControlLabel control={<Switch checked={showStopped} onChange={(e) => setShowStopped(e.target.checked)} />} label="Show stopped" />}>
          {data.containers.length === 0
            ? <Typography sx={{ p: 2, color: "text.secondary" }}>{showStopped ? "No containers." : "No running containers."}</Typography>
            : <ContainerTable containers={data.containers} />}
        </Section>
      )}
    </>
  );
}

function ContainerTable({ containers }: { containers: ContainerView[] }) {
  return (
    <TableContainer>
      <Table size="small" aria-label="Containers">
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: 130 }}>State</TableCell>
            <TableCell>Name</TableCell>
            <TableCell>Image</TableCell>
            <TableCell>Ports</TableCell>
            <TableCell>From</TableCell>
            <TableCell>Status</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {containers.map((c) => (
            <TableRow key={c.id} hover>
              <TableCell><StatusChip look={containerLook(c.state, c.status)} title={c.status} /></TableCell>
              <TableCell><Mono>{c.name}</Mono></TableCell>
              <TableCell sx={{ maxWidth: 300 }}>
                <Box sx={{ whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }} title={c.image}><Mono muted>{c.image}</Mono></Box>
              </TableCell>
              <TableCell sx={{ maxWidth: 260 }}>
                <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
                  {publishedPorts(c.ports).map((p) => <Chip key={p} variant="outlined" label={p} sx={{ fontFamily: "monospace" }} />)}
                </Stack>
              </TableCell>
              <TableCell>
                {c.kitoutService ? <Tag label={`kitout · ${c.kitoutService.split(".").pop()}`} color="primary" title={`Service ${c.kitoutService}, started by kitout from the Services page`} />
                  : c.environment ? <Tag label={`compose · ${c.environment}`} />
                  : c.composeProject ? <Tooltip title="A compose project outside kitout's folders"><span><Tag label={c.composeProject} /></span></Tooltip>
                  : null}
              </TableCell>
              <TableCell><Typography variant="body2" sx={{ color: "text.secondary" }}>{c.status}</Typography></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
