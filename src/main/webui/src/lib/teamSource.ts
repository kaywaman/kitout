/**
 * The team file's place in a git repository, named like a team compose file: the repository and the
 * file's path in it, plus host and branch. People often paste the link their git server shows
 * instead; this splits it.
 */
export interface TeamSourceFields {
  host: string;
  repo: string;
  path: string;
  ref: string;
}

/** GitLab marks where the repository ends with `/-/`: `<repo>/-/blob/<ref>/<path>`. */
const GITLAB_MARKER = /\/-\/(?:blob|raw|tree)\//;

/**
 * Splits a link to the file into the fields, or null if the text is no link.
 *
 * Accepted:
 * - GitLab: `https://gitlab.example.com/acme/platform/infra/-/blob/main/kitout/kitout-team.json`, also without
 *   `https://`, and `acme/platform/infra/-/blob/main/...` (no host)
 * - GitHub: `https://github.com/acme/infra/blob/main/kitout/kitout-team.json` and
 *   `https://raw.githubusercontent.com/acme/infra/main/kitout/kitout-team.json`
 * - a project link: `https://github.com/acme/infra` (host and repository)
 *
 * The branch ends at the first `/` after `blob/`, as in the servers' own links. `?` and `#` parts are dropped.
 */
export function splitGitLabLink(text: string): Partial<TeamSourceFields> | null {
  let t = text.trim().replace(/[?#].*$/, "");
  const hadScheme = /^https?:\/\//i.test(t);
  t = t.replace(/^https?:\/\//i, "").replace(/\/+$/, "");

  const parts = t.split("/");
  if (parts[0]?.toLowerCase() === "raw.githubusercontent.com" && parts.length >= 5) {
    return { host: "github.com", repo: `${parts[1]}/${parts[2]}`, ref: parts[3], path: decodeURIComponent(parts.slice(4).join("/")) };
  }

  const m = GITLAB_MARKER.exec(t);
  if (!m) {
    // GitHub: <host>/<owner>/<repo>/blob/<ref>/<path>; repositories there are always owner/repo
    if (parts.length >= 5 && parts[0]?.includes(".") && ["blob", "raw", "tree"].includes(parts[3] ?? "")) {
      const result: Partial<TeamSourceFields> = { host: parts[0], repo: `${parts[1]}/${parts[2]}`, ref: parts[4] };
      if (parts.length > 5) {
        result.path = decodeURIComponent(parts.slice(5).join("/"));
      }
      return result;
    }
    if (!hadScheme) {
      return null;
    }
    // a project link: https://host/group/project
    const slash = t.indexOf("/");
    if (slash < 0 || t.indexOf("/", slash + 1) < 0) {
      return null;
    }
    return { host: t.slice(0, slash), repo: t.slice(slash + 1).replace(/\.git$/, "") };
  }
  let before = t.slice(0, m.index);
  const after = t.slice(m.index + m[0].length);
  const sep = after.indexOf("/");
  const ref = sep < 0 ? after : after.slice(0, sep);
  const path = sep < 0 ? "" : decodeURIComponent(after.slice(sep + 1));
  let host: string | undefined;
  // a host has a dot and comes first; "acme/infra" alone has none
  const first = before.split("/")[0] ?? "";
  if (hadScheme || (first.includes(".") && before.split("/").length >= 3)) {
    host = first;
    before = before.slice(first.length + 1);
  }
  const result: Partial<TeamSourceFields> = { repo: before, ref };
  if (path) {
    result.path = path;
  }
  if (host) {
    result.host = host;
  }
  return result;
}

/** What is still missing before the form can be sent; empty when complete. */
export function missingFields(f: TeamSourceFields): string[] {
  const missing: string[] = [];
  if (!f.host.trim()) {
    missing.push("git host");
  }
  if (!/^[^/\s]+(\/[^/\s]+)+$/.test(f.repo.trim().replace(/^\/+|\/+$/g, ""))) {
    missing.push("repository (group/project)");
  }
  return missing;
}
