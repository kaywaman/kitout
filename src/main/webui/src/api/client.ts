/**
 * The only place that talks HTTP. Every call sends the session token; every error becomes an
 * {@link ApiError} carrying the backend's sentence, so views can show it as is.
 */
import { currentToken, forgetToken } from "../lib/session.ts";
import { SseParser } from "../lib/sse.ts";
import type {
  BuildRequestView, BuildView, CleanupOutcomeView, CloneCandidateView, CloneItemRequest, CloneJobView,
  CredentialStatusView, DoctorReportView, FolderListingView, FolderPreviewView, GroupStartView, GroupView,
  HiddenProjectView, MessagesView, PortView, KitoutInfoView, SyncStatusView, PullOutcomeView, DockerOverviewView, ComposeJobView, ComposeBrowseView, PickedComposeFileView, ImageTagsView, RegistryStatusView, EnvVarView, BuildBatchView, ResetPreviewView, ResetRequest, ResetOutcomeView, RequirementsView, TeamStatusView, TeamSourceRequest, EnvImportView, SetupView, SetupProjectView, CurrentBuildView, TeamSuggestionsView, TeamDraftRequest, AddedServicesView, AddedLibrariesView, LibraryKind, RemoteFindingView, RemoveAllView, RunMode, RunModeResultView,
  ScanView, ServiceSettingsView, ServiceView, SettingsView,
} from "./types.ts";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /** The full response body, e.g. the doctor report when a group start is blocked. */
  readonly body: unknown;

  constructor(status: number, code: string, message: string, body: unknown) {
    super(message);
    this.status = status;
    this.code = code;
    this.body = body;
  }

  get blockedByDoctor(): boolean {
    return this.status === 409 && typeof this.body === "object" && this.body !== null && "results" in this.body;
  }
}

function headers(json: boolean): HeadersInit {
  const h: Record<string, string> = { "X-Kitout-Token": currentToken() ?? "" };
  if (json) {
    h["Content-Type"] = "application/json";
  }
  return h;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers: headers(body !== undefined),
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "offline", "kitout is not reachable. Is 'kitout serve' still running?", null);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const parsed: unknown = text ? safeJson(text) : null;
  if (!response.ok) {
    const err = parsed as { error?: string; message?: string } | null;
    if (response.status === 403 && err?.error === "invalid-token") {
      forgetToken();
      window.dispatchEvent(new Event("kitout-session-lost"));
    }
    throw new ApiError(response.status, err?.error ?? "http-" + response.status,
      err?.message ?? `kitout answered ${response.status}`, parsed);
  }
  return parsed as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

const enc = encodeURIComponent;

export const api = {
  services: () => request<ServiceView[]>("GET", "/api/services"),
  startService: (id: string) => request<ServiceView>("POST", `/api/services/${enc(id)}/start`),
  stopService: (id: string) => request<ServiceView>("POST", `/api/services/${enc(id)}/stop`),
  restartService: (id: string) => request<ServiceView>("POST", `/api/services/${enc(id)}/restart`),
  updateService: (id: string, change: Partial<ServiceSettingsView>) =>
    request<ServiceView>("PUT", `/api/services/${enc(id)}/settings`, change),
  scan: (roots: string[]) => request<ScanView>("POST", "/api/scan", { roots }),
  removeService: (id: string) => request<void>("DELETE", `/api/services/${enc(id)}`),
  removeAllServices: () => request<RemoveAllView>("DELETE", "/api/services"),
  setRunMode: (mode: RunMode, serviceIds: string[]) =>
    request<RunModeResultView>("PUT", "/api/services/run-mode", { mode, serviceIds }),
  hiddenProjects: () => request<HiddenProjectView[]>("GET", "/api/hidden-projects"),
  restoreProject: (path: string) => request<void>("DELETE", `/api/hidden-projects?path=${enc(path)}`),
  folders: (path: string) => request<FolderListingView>("GET", `/api/folders?path=${enc(path)}`),
  folderPreview: (path: string) => request<FolderPreviewView>("GET", `/api/folders/preview?path=${enc(path)}`),

  groups: () => request<GroupView[]>("GET", "/api/groups"),
  saveGroup: (group: GroupView) => request<GroupView>("PUT", `/api/groups/${enc(group.name)}`, group),
  deleteGroup: (name: string) => request<void>("DELETE", `/api/groups/${enc(name)}`),
  startGroup: (name: string, force: boolean) =>
    request<GroupStartView>("POST", `/api/groups/${enc(name)}/start?force=${force}`),
  groupStartProgress: (name: string) => request<GroupStartView>("GET", `/api/groups/${enc(name)}/start`),
  stopGroup: (name: string) => request<MessagesView>("POST", `/api/groups/${enc(name)}/stop`),
  ports: (group?: string) => request<PortView[]>("GET", "/api/ports" + (group ? `?group=${enc(group)}` : "")),

  doctor: (group?: string) => request<DoctorReportView>("GET", "/api/doctor" + (group ? `?group=${enc(group)}` : "")),
  remoteCredentials: (group?: string) =>
    request<RemoteFindingView[]>("GET", "/api/git/remote-credentials" + (group ? `?group=${enc(group)}` : "")),
  cleanRemotes: (group?: string) =>
    request<CleanupOutcomeView[]>("POST", "/api/git/clean-remotes" + (group ? `?group=${enc(group)}` : "")),

  build: (serviceId: string, request_: BuildRequestView) =>
    request<BuildView>("POST", `/api/services/${enc(serviceId)}/build`, request_),
  builds: () => request<BuildView[]>("GET", "/api/builds"),
  /** JVM builds all start at once; native builds queue one at a time. */
  buildMany: (serviceIds: string[], options: BuildRequestView) =>
    request<BuildBatchView>("POST", "/api/builds", { serviceIds, ...options }),
  resetPreview: () => request<ResetPreviewView>("GET", "/api/reset"),
  reset: (options: ResetRequest) => request<ResetOutcomeView>("POST", "/api/reset", options),
  cancelBuild: (id: string) => request<BuildView>("DELETE", `/api/builds/${enc(id)}`),

  cloneCandidates: (group: string) => request<CloneCandidateView[]>("GET", `/api/gitlab/projects?group=${enc(group)}`),
  clone: (items: CloneItemRequest[]) => request<CloneJobView>("POST", "/api/clone", { items }),
  cloneJob: (id: string) => request<CloneJobView>("GET", `/api/clone/${enc(id)}`),

  settings: () => request<SettingsView>("GET", "/api/settings"),
  saveSettings: (settings: SettingsView) => request<SettingsView>("PUT", "/api/settings", settings),
  credentials: () => request<CredentialStatusView>("GET", "/api/credentials"),
  storeToken: (token: string) => request<CredentialStatusView>("PUT", "/api/credentials/token", { token }),
  deleteToken: () => request<CredentialStatusView>("DELETE", "/api/credentials/token"),

  kitoutInfo: () => request<KitoutInfoView>("GET", "/api/kitout"),

  registry: () => request<RegistryStatusView>("GET", "/api/registry"),
  /** The password goes to `docker login` on stdin; kitout keeps no copy. */
  registryLogin: (username: string, password: string) =>
    request<RegistryStatusView>("PUT", "/api/registry/login", { username, password }),
  registryLogout: () => request<RegistryStatusView>("DELETE", "/api/registry/login"),
  imageTags: (serviceId: string) => request<ImageTagsView>("GET", `/api/services/${enc(serviceId)}/image-tags`),
  envShared: () => request<EnvVarView[]>("GET", "/api/env/shared"),
  saveEnvShared: (variables: EnvVarView[]) => request<EnvVarView[]>("PUT", "/api/env/shared", { variables }),
  serviceEnv: (serviceId: string) => request<EnvVarView[]>("GET", `/api/services/${enc(serviceId)}/env`),
  saveServiceEnv: (serviceId: string, variables: EnvVarView[]) =>
    request<EnvVarView[]>("PUT", `/api/services/${enc(serviceId)}/env`, { variables }),
  importEnvShared: (text: string) => request<EnvImportView>("POST", "/api/env/shared/import", { text }),
  importServiceEnv: (serviceId: string, text: string) => request<EnvImportView>("POST", `/api/services/${enc(serviceId)}/env/import`, { text }),

  /** The machine against the team's requirements; `checkRequirements` reads every tool again. */
  requirements: () => request<RequirementsView>("GET", "/api/requirements"),
  checkRequirements: () => request<RequirementsView>("POST", "/api/requirements/check"),
  team: () => request<TeamStatusView>("GET", "/api/team"),
  /** Repository and path on the git host (like a team compose file), or a file on this machine. */
  useTeam: (source: TeamSourceRequest) => request<TeamStatusView>("PUT", "/api/team", source),
  forgetTeam: () => request<TeamStatusView>("DELETE", "/api/team"),
  refreshTeam: () => request<TeamStatusView>("POST", "/api/team/refresh"),
  /** Goes to the keyring; never sent back. */
  setTeamSecret: (name: string, scope: string, value: string) =>
    request<TeamStatusView>("PUT", `/api/team/secrets/${enc(name)}`, { scope, value }),
  cloneTeamRepositories: (light: boolean) => request<CloneJobView>("POST", `/api/team/clone?light=${light}`),

  /** The first-call walk-through: team file checks, machine, secrets, projects. */
  setup: () => request<SetupView>("GET", "/api/setup"),
  setupCheck: () => request<SetupView>("POST", "/api/setup/check"),
  /** Loads the team's projects already on this machine; nothing else. */
  setupLoadLocal: () => request<ScanView>("POST", "/api/setup/local"),
  /** What can run now: the build output on disk, one per service and mode. */
  currentBuilds: () => request<CurrentBuildView[]>("GET", "/api/builds/current"),
  /** Deletes build output (target/quarkus-app, the native runner); output a running service uses stays. */
  removeCurrentBuilds: (items: { serviceId: string; mode: string }[]) =>
    request<{ removed: string[]; kept: string[] }>("POST", "/api/builds/current/remove", { items }),
  /** Takes finished build attempts off the list and deletes their logs; the build output stays. */
  removeBuilds: (ids: string[]) => request<{ removed: string[]; kept: string[] }>("POST", "/api/builds/remove", { ids }),

  /** Building a team setup from scratch: what kitout found, create, add services, export. */
  teamSuggestions: () => request<TeamSuggestionsView>("GET", "/api/team/create"),
  createTeam: (draft: TeamDraftRequest) => request<TeamStatusView>("POST", "/api/team/create", draft),
  addTeamServices: (folders: string[]) => request<AddedServicesView>("POST", "/api/team/services", { folders }),
  addTeamLibraries: (folders: string[], kind: LibraryKind) =>
    request<AddedLibrariesView>("POST", "/api/team/libraries", { folders, kind }),
  exportTeam: () => request<unknown>("GET", "/api/team/export"),

  /** The team's repositories that are not services, e.g. the compose files. */
  teamRepositories: () => request<SetupProjectView[]>("GET", "/api/team/repositories"),
  cloneTeamRepository: (repo: string) => request<CloneJobView>("POST", `/api/team/repositories/clone?repo=${enc(repo)}`),
  pullTeamRepository: (repo: string) => request<PullOutcomeView>("POST", `/api/team/repositories/pull?repo=${enc(repo)}`),
  setupClone: (light: boolean) => request<CloneJobView>("POST", `/api/setup/clone?light=${light}`),

  chooseTag: (serviceId: string, tag: string) => request<ServiceView>("PUT", `/api/services/${enc(serviceId)}/image-tag`, { tag }),

  /** Fetches only; empty ids mean all services. */
  gitCheck: (serviceIds: string[]) => request<SyncStatusView[]>("POST", "/api/git/check", { serviceIds }),
  gitPull: (serviceIds: string[]) => request<PullOutcomeView[]>("POST", "/api/git/pull", { serviceIds }),

  docker: (includeStopped: boolean) => request<DockerOverviewView>("GET", `/api/docker?all=${includeStopped}`),
  composeUp: (environment: string) => request<ComposeJobView>("POST", `/api/docker/environments/${enc(environment)}/up`),
  composeDown: (environment: string) => request<ComposeJobView>("POST", `/api/docker/environments/${enc(environment)}/down`),
  composeBrowse: (path: string) => request<ComposeBrowseView>("GET", `/api/docker/browse?path=${enc(path)}`),
  pickComposeFile: (path: string) => request<PickedComposeFileView>("POST", "/api/docker/files", { path }),
  forgetComposeFile: (path: string) => request<void>("DELETE", `/api/docker/files?path=${enc(path)}`),
};

/** Paths of the live log streams (server-sent events), for {@link followLines}. */
export const logStreams = {
  service: (id: string, lines = 1000) => `/api/services/${enc(id)}/logs?lines=${lines}`,
  build: (id: string) => `/api/builds/${enc(id)}/log`,
  kitout: (lines = 1000) => `/api/kitout/log?lines=${lines}`,
  compose: (jobId: string) => `/api/docker/jobs/${enc(jobId)}/log`,
};

/**
 * Follows a server-sent event stream of text lines (service, build and kitout logs).
 * Returns a function that stops following.
 */
export function followLines(path: string, onLine: (line: string) => void, onEnd: (error?: ApiError) => void): () => void {
  const controller = new AbortController();
  (async () => {
    try {
      const response = await fetch(path, { headers: headers(false), signal: controller.signal });
      if (!response.ok || !response.body) {
        const text = await response.text();
        const err = safeJson(text) as { error?: string; message?: string };
        onEnd(new ApiError(response.status, err.error ?? "stream", err.message ?? "The log stream could not be opened", err));
        return;
      }
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      const parser = new SseParser();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        parser.push(value).forEach(onLine);
      }
      onEnd();
    } catch (e) {
      if (!controller.signal.aborted) {
        onEnd(new ApiError(0, "offline", "The connection to kitout was lost", e));
      }
    }
  })();
  return () => controller.abort();
}
