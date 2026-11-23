import type { RequirementView } from "../api/types.ts";

/** Pages that stay open while the machine does not meet the team's requirements: they help to fix it. */
export const OPEN_WHEN_BLOCKED = ["setup", "settings", "doctor", "log"] as const;

/** "found 17.0.2, required 21.*", or "not found, required >=28.5.2". */
export function foundText(r: Pick<RequirementView, "found" | "required" | "outcome">): string {
  const found = r.found ? r.found : "not found";
  return r.outcome === "PASS" ? found : `${found}, required ${r.required}`;
}

/** Problems first (failed, then warnings), then what passed; stable within each. */
export function ordered<T extends { outcome: string }>(results: T[]): T[] {
  const rank: Record<string, number> = { FAIL: 0, WARN: 1, PASS: 2 };
  return results.map((r, i) => ({ r, i })).sort((a, b) => (rank[a.r.outcome] ?? 3) - (rank[b.r.outcome] ?? 3) || a.i - b.i).map((x) => x.r);
}
