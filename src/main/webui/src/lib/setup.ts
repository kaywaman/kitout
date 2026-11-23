import type { SetupView } from "../api/types.ts";

/** Still something to do in step 3; NOT_LOADABLE and REMOVED are shown with advice but do not hold up the setup. */
export function isOpen(p: { kind: string; state: string }): boolean {
  return p.kind !== "service" ? p.state === "MISSING" : p.state === "LOCAL" || p.state === "MISSING";
}

/** How a repository that is not a service is named: the compose repository, a common Java or UI library. */
export function kindLabel(kind: string): string {
  return kind === "java-library" ? "Java library" : kind === "ui-library" ? "UI library" : "not a service";
}

export type SetupStep = "team" | "machine" | "projects" | "secrets" | "done";

type SetupLike = Pick<SetupView, "complete" | "ready" | "missingSecrets"> & {
  team: { configured: boolean; valid: boolean };
  requirements: { blocked: boolean };
  projects: { kind: string; state: string }[];
};

/** The step to work on: the team file, then the machine, then the projects, then the secrets. */
export function nextStep(s: SetupLike): SetupStep {
  if (!s.team.configured || !s.team.valid) {
    return "team";
  }
  if (s.requirements.blocked) {
    return "machine";
  }
  if (s.projects.some(isOpen)) {
    return "projects";
  }
  if (s.missingSecrets.length > 0) {
    return "secrets";
  }
  return "done";
}

/** After the start-up check: everything in place, so kitout goes straight to the services. */
export function startupPassed(s: SetupLike): boolean {
  return nextStep(s) === "done";
}

/** The success message after the start-up check. */
export function passedMessage(s: SetupLike & { team: { team?: string }; requirements: { results: unknown[] } }): string {
  const services = s.projects.filter((p) => p.kind === "service").length;
  const checks = s.requirements.results.length;
  return `All checks passed${s.team.team ? ` for ${s.team.team}` : ""}: team file, ${checks} machine requirement${checks === 1 ? "" : "s"}, ${services} service${services === 1 ? "" : "s"}`;
}

/** What to do next, one sentence per open step, for the top of the Setup page. */
export function todo(s: SetupLike & { team: { problems?: string[] }; requirements: { results: { outcome: string; id: string; name: string }[] } }): string[] {
  const items: string[] = [];
  if (!s.team.configured) {
    return ["Enter where your team's kitout-team.json is (step 1)."];
  }
  if (!s.team.valid) {
    return ["The team file cannot be used: ask whoever maintains it to fix the problems in step 1, then Check again."];
  }
  const failed = s.requirements.results.filter((r) => r.outcome === "FAIL" && !r.id.startsWith("secret:"));
  if (failed.length > 0) {
    items.push(`Fix ${failed.map((r) => r.name).join(", ")} on this machine (step 2 says how), then Check again.`);
    return items;
  }
  const missing = s.projects.filter((p) => p.state === "MISSING").length;
  const local = s.projects.filter((p) => p.kind === "service" && p.state === "LOCAL").length;
  if (missing > 0) {
    items.push(`Clone ${missing} missing repositor${missing === 1 ? "y" : "ies"} (step 3).`);
  }
  if (local > 0) {
    items.push(`Load ${local} project${local === 1 ? "" : "s"} already on this machine (step 3).`);
  }
  if (s.missingSecrets.length > 0) {
    items.push(`Enter your ${s.missingSecrets.join(", ")} (step 4).`);
  }
  if (items.length === 0 && s.projects.every((p) => p.kind !== "service")) {
    items.push("Your team setup has no services yet: Load local projects (step 3) and add the ones that belong to the team.");
  }
  const stuck = s.projects.filter((p) => p.state === "NOT_LOADABLE").length;
  if (stuck > 0 && items.length === 0) {
    items.push(`${stuck} repositor${stuck === 1 ? "y" : "ies"} of the team cannot be loaded as a service; step 3 says why.`);
  }
  return items;
}
