/**
 * What a service, check or build status looks like, and which actions it allows.
 * One table per kind, so every chip and button in the UI agrees (Principle of Least Astonishment):
 * green always means "running and fine", amber "needs a look", red "broken".
 *
 * Pure functions: tested in status.test.ts without React or MUI.
 */
export type ChipColor = "default" | "primary" | "success" | "warning" | "error" | "info";

export interface Look {
  label: string;
  color: ChipColor;
  /** A spinner instead of a dot: the state changes by itself. */
  busy?: boolean;
}

/** Fields of a service the rules need; ServiceView has them all. */
export interface ServiceLike {
  state: string;
  mode: string;
  health: string;
}

const STATES: Record<string, Look> = {
  STOPPED: { label: "Stopped", color: "default" },
  STARTING: { label: "Starting", color: "info", busy: true },
  RUNNING: { label: "Running", color: "success" },
  STOPPING: { label: "Stopping", color: "info", busy: true },
  FAILED: { label: "Failed", color: "error" },
};

export function stateLook(state: string): Look {
  return STATES[state] ?? { label: state, color: "default" };
}

/** Readiness is shown only for a running service; the state chip says enough otherwise. */
export function healthLook(s: ServiceLike): Look | null {
  if (s.state !== "RUNNING") {
    return null;
  }
  switch (s.health) {
    case "HEALTHY":
      return { label: "Healthy", color: "success" };
    case "UNHEALTHY":
      return { label: "Unhealthy", color: "warning" };
    case "NO_HEALTH_CHECK":
      return { label: "No health check", color: "default" };
    default:
      return null;
  }
}

const SEVERITIES: Record<string, Look> = {
  ERROR: { label: "Error", color: "error" },
  WARNING: { label: "Warning", color: "warning" },
  INFO: { label: "Note", color: "info" },
  OK: { label: "OK", color: "success" },
};

export function severityLook(severity: string): Look {
  return SEVERITIES[severity] ?? { label: severity, color: "default" };
}

const BUILDS: Record<string, Look> = {
  QUEUED: { label: "Queued", color: "default" },
  RUNNING: { label: "Building", color: "info", busy: true },
  SUCCEEDED: { label: "Succeeded", color: "success" },
  FAILED: { label: "Failed", color: "error" },
  CANCELLED: { label: "Cancelled", color: "default" },
};

export function buildLook(status: string): Look {
  return BUILDS[status] ?? { label: status, color: "default" };
}

/** kitout starts and stops a service only when it is not run from the IDE. */
export function managedByKitout(s: ServiceLike): boolean {
  return s.mode !== "IDE";
}

/** Stop is offered while kitout has a process for it, including a failed start that may still hold one. */
export function canStop(s: ServiceLike): boolean {
  return managedByKitout(s) && (s.state === "RUNNING" || s.state === "STARTING" || s.state === "FAILED");
}

export function canStart(s: ServiceLike): boolean {
  return managedByKitout(s) && s.state === "STOPPED";
}

/**
 * A service in "Registry image" mode runs the tag the developer chose; there is no default.
 * `image` is the full reference the backend would run, null while no tag is chosen.
 */
export function needsTag(s: { mode: string; image: string | null }): boolean {
  return s.mode === "IMAGE" && !s.image;
}

/** Restart covers "start again" for a failed service: the backend cleans up first. */
export function canRestart(s: ServiceLike): boolean {
  return managedByKitout(s) && (s.state === "RUNNING" || s.state === "FAILED");
}

/** Mirrors ServiceAdminService.blocksRemoval: the backend refuses the same cases with 409. */
export function blocksRemoval(s: ServiceLike): boolean {
  return managedByKitout(s) && s.state !== "STOPPED";
}

/**
 * A service in IDE mode belongs to the IDE: kitout neither builds it nor touches its build output.
 * Node and image-only services are not built by kitout at all.
 */
export function canBuild(s: { mode: string; buildable?: boolean }): boolean {
  return s.mode !== "IDE" && s.buildable !== false;
}

/** Where a service runs from can be changed only while kitout has no process for it. */
export function canChangeMode(s: ServiceLike): boolean {
  return !blocksRemoval(s);
}

// ---- Docker -------------------------------------------------------------------------------

/**
 * A container from `docker ps`: its state plus docker's health words in the status text,
 * e.g. "Up 3 minutes (healthy)" or "Exited (1) 2 hours ago".
 */
export function containerLook(state: string, status: string): Look {
  const s = status.toLowerCase();
  switch (state) {
    case "running":
      if (s.includes("(unhealthy)")) {
        return { label: "Unhealthy", color: "warning" };
      }
      if (s.includes("(health: starting)")) {
        return { label: "Starting", color: "info", busy: true };
      }
      return { label: s.includes("(healthy)") ? "Healthy" : "Running", color: "success" };
    case "restarting":
      return { label: "Restarting", color: "warning", busy: true };
    case "exited": {
      const code = /exited \((\d+)\)/.exec(s)?.[1];
      return code && code !== "0" ? { label: `Exited (${code})`, color: "error" } : { label: "Exited", color: "default" };
    }
    case "dead":
      return { label: "Dead", color: "error" };
    default:
      return { label: state ? state.charAt(0).toUpperCase() + state.slice(1) : "Unknown", color: "default" };
  }
}

/** An environment by its containers: all up, some up, or down. */
export function environmentLook(running: number, total: number): Look {
  if (total === 0 || running === 0) {
    return { label: total === 0 ? "Down" : `0/${total} up`, color: "default" };
  }
  return { label: `${running}/${total} up`, color: running === total ? "success" : "warning" };
}

export function composeJobLook(action: string, status: string): Look {
  const verb = action === "UP" ? "up" : "down";
  switch (status) {
    case "RUNNING":
      return { label: `Running ${verb}`, color: "info", busy: true };
    case "SUCCEEDED":
      return { label: `${verb === "up" ? "Up" : "Down"} done`, color: "success" };
    default:
      return { label: `${verb === "up" ? "Up" : "Down"} failed`, color: "error" };
  }
}

// ---- git sync -------------------------------------------------------------------------------

/** Fields of a checkout's sync status the rules need; SyncStatusView has them all. */
export interface SyncLike {
  behind: number;
  ahead: number;
  localChanges: boolean;
  problem: string | null;
}

/** One chip per checkout: what a pull would do. */
/** The backend's sentence for a service in IDE mode (GitSyncService.IN_IDE): kitout leaves its checkout to the IDE. */
export const IN_IDE = "In your IDE: pull it there";

export function syncLook(s: SyncLike): Look {
  if (s.problem === IN_IDE) {
    return { label: "In your IDE", color: "default" };
  }
  if (s.problem) {
    return { label: s.problem.startsWith("Fetch failed") ? "Fetch failed" : "Cannot pull", color: s.problem.startsWith("Fetch failed") ? "error" : "default" };
  }
  if (s.behind > 0 && s.localChanges) {
    return { label: `${s.behind} behind, local changes`, color: "warning" };
  }
  if (s.behind > 0) {
    return { label: `${s.behind} new commit${s.behind === 1 ? "" : "s"}`, color: "info" };
  }
  return { label: "Up to date", color: "success" };
}

const PULLS: Record<string, Look> = {
  UPDATED: { label: "Updated", color: "success" },
  UP_TO_DATE: { label: "Up to date", color: "default" },
  SKIPPED: { label: "Skipped", color: "warning" },
  FAILED: { label: "Failed", color: "error" },
};

export function pullLook(status: string, message?: string | null): Look {
  if (status === "SKIPPED" && message === IN_IDE) {
    return { label: "In your IDE", color: "default" };
  }
  return PULLS[status] ?? { label: status, color: "default" };
}
