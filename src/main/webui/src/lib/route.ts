/**
 * Hash routes. The daemon serves one page, so the address after `#` decides the view:
 * `#/setup` (first call), `#/services`, `#/doctor`, `#/docker`, `#/builds`, `#/clone`, `#/log` (kitout's own log), `#/settings`,
 * and the stand-alone windows `#/logs/<serviceId>` and `#/build-log/<buildId>`.
 */
export type Route =
  | { kind: "page"; page: Page }
  | { kind: "service-log"; serviceId: string }
  | { kind: "build-log"; buildId: string };

export const PAGES = ["setup", "services", "doctor", "docker", "builds", "clone", "log", "settings"] as const;
export type Page = (typeof PAGES)[number];

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#\/?/, "").split("&")[0] ?? "";
  const [head, ...rest] = path.split("/");
  const arg = decodeURIComponent(rest.join("/"));
  if (head === "logs" && arg) {
    return { kind: "service-log", serviceId: arg };
  }
  if (head === "build-log" && arg) {
    return { kind: "build-log", buildId: arg };
  }
  const page = PAGES.find((p) => p === head);
  return { kind: "page", page: page ?? "services" };
}

export function pageHref(page: Page): string {
  return `#/${page}`;
}

export function serviceLogHref(serviceId: string): string {
  return `#/logs/${encodeURIComponent(serviceId)}`;
}

export function buildLogHref(buildId: string): string {
  return `#/build-log/${encodeURIComponent(buildId)}`;
}
