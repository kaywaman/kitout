import type { BuildView } from "../api/types.ts";

export type BuildSortKey = "service" | "version" | "build" | "status" | "started" | "time";
export type SortDirection = "asc" | "desc";

export interface BuildFilter {
  text: string;      // service name, version, profile or id
  status: string;    // "" for all, else a BuildStatus
  mode: string;      // "" for all, "JVM" or "NATIVE"
}

export const NO_FILTER: BuildFilter = { text: "", status: "", mode: "" };

/** The fields sorting and filtering use. The backend sends null for a version it could not read. */
type BuildLike = Pick<BuildView, "id" | "serviceName" | "profile" | "mode" | "status" | "createdAt" | "startedAt" | "finishedAt">
  & { version: string | null };

export function filterBuilds<T extends BuildLike>(builds: T[], f: BuildFilter): T[] {
  const needle = f.text.trim().toLowerCase();
  return builds.filter((b) =>
    (!f.status || b.status === f.status)
    && (!f.mode || b.mode === f.mode)
    && (!needle || [b.serviceName, b.version ?? "", b.profile, b.id].some((v) => v.toLowerCase().includes(needle))));
}

/** Milliseconds a build took (or is taking); for sorting by time. */
function took(b: BuildLike, now: number): number {
  if (!b.startedAt) {
    return -1;
  }
  return (b.finishedAt ? Date.parse(b.finishedAt) : now) - Date.parse(b.startedAt);
}

/** Version strings compared by their numbers: 1.10.0 after 1.9.0. */
export function compareVersions(a: string, b: string): number {
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" });
}

export function sortBuilds<T extends BuildLike>(builds: T[], key: BuildSortKey, dir: SortDirection, now = Date.now()): T[] {
  const value = (b: T): string | number => {
    switch (key) {
      case "service": return b.serviceName.toLowerCase();
      case "version": return b.version ?? "";
      case "build": return `${b.mode} ${b.profile}`;
      case "status": return b.status;
      case "started": return Date.parse(b.startedAt ?? b.createdAt);
      case "time": return took(b, now);
    }
  };
  const sign = dir === "asc" ? 1 : -1;
  return [...builds].sort((a, b) => {
    const va = value(a);
    const vb = value(b);
    const c = typeof va === "number" && typeof vb === "number" ? va - vb
      : key === "version" ? compareVersions(String(va), String(vb)) : String(va).localeCompare(String(vb));
    return c !== 0 ? sign * c : Date.parse(b.createdAt) - Date.parse(a.createdAt);   // ties: newest first
  });
}
