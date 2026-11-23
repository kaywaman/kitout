/** Which kind of git server, as kitout's backend decides it (GitHost.java): as set, or guessed from the host name. */
export type GitHostKind = "GITLAB" | "GITHUB" | "GIT";

export function gitHostKind(provider: string | null | undefined, host: string | null | undefined): GitHostKind {
  const p = (provider ?? "").trim().toLowerCase();
  if (p === "gitlab") return "GITLAB";
  if (p === "github") return "GITHUB";
  if (p === "git") return "GIT";
  const h = (host ?? "").trim().toLowerCase();
  if (h.includes("github")) return "GITHUB";
  if (h.includes("gitlab")) return "GITLAB";
  return "GIT";
}

export const GIT_HOST_LABEL: Record<GitHostKind, string> = { GITLAB: "GitLab", GITHUB: "GitHub", GIT: "Git server" };

/** The registry a team on this host usually uses; empty when there is no convention. */
export function defaultRegistry(kind: GitHostKind, host: string): string {
  const h = host.trim().replace(/^https?:\/\//, "").replace(/\/+$/, "");
  if (!h) return "";
  if (kind === "GITLAB") return `registry.${h}`;
  if (kind === "GITHUB") return h.toLowerCase() === "github.com" ? "ghcr.io" : `containers.${h}`;
  return "";
}

/** What a token for this kind of server looks like, for the token field. */
export function tokenPlaceholder(kind: GitHostKind): string {
  return kind === "GITLAB" ? "glpat-…" : kind === "GITHUB" ? "github_pat_… or ghp_…" : "token or app password";
}
