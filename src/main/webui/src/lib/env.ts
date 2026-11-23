/**
 * Mirrors of two backend rules, for live previews while typing. The backend stays the judge:
 * EnvNames.fromProperty and ImagePath.render.
 */

const VALID_ENV = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** `quarkus.datasource.username` -> `QUARKUS_DATASOURCE_USERNAME`; a valid variable name stays as typed. */
export function envName(typed: string): string {
  const n = typed.trim();
  return VALID_ENV.test(n) ? n : n.replace(/[^A-Za-z0-9_]/g, "_").toUpperCase();
}

/** `{namespace}/{project}` for acme/shop/orders -> `acme/shop/orders`; unknown placeholders stay visible. */
export function imagePath(pattern: string, fullPath: string): string {
  const full = fullPath.toLowerCase();
  const slash = full.lastIndexOf("/");
  const namespace = slash < 0 ? "" : full.slice(0, slash);
  const project = full.slice(slash + 1);
  const group = namespace.split("/")[0] ?? "";
  const values: Record<string, string> = { namespace, group, project, path: full };
  return pattern
    .replace(/\{([a-z]+)}/g, (whole, key: string) => values[key] ?? whole)
    .replace(/\/{2,}/g, "/")
    .replace(/^\/|\/$/g, "");
}

/** When an environment variable applies: when the service runs, when kitout builds it, or both. */
export type EnvUse = "RUN" | "BUILD" | "BOTH";

/** Variables the team's services read most often (MongoDB), and Maven's options for builds, offered as one-click additions. */
export const COMMON_VARIABLES: { label: string; name: string; secret: boolean; use: EnvUse }[] = [
  { label: "Mongo host", name: "MONGO_HOST", secret: false, use: "RUN" },
  { label: "Mongo port", name: "MONGO_PORT", secret: false, use: "RUN" },
  { label: "Mongo user", name: "MONGO_USERNAME", secret: false, use: "RUN" },
  { label: "Mongo password", name: "MONGO_PASSWORD", secret: true, use: "RUN" },
  { label: "Log level", name: "QUARKUS_LOG_LEVEL", secret: false, use: "RUN" },
  { label: "Maven options (build)", name: "MAVEN_OPTS", secret: false, use: "BUILD" },
];

/** Names that hold passwords and keys by convention; mirrors DotEnv.looksSecret, which decides on import. */
export function looksSecret(name: string): boolean {
  return /(PASSWORD|PASSWD|PWD|SECRET|TOKEN|API_?KEY|PRIVATE_?KEY|CREDENTIAL)/i.test(name);
}
