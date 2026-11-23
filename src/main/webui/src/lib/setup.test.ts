import { test } from "node:test";
import assert from "node:assert/strict";
import { nextStep, passedMessage, startupPassed, todo } from "./setup.ts";

const base = {
  complete: false, ready: true, missingSecrets: [] as string[],
  team: { configured: true, valid: true }, requirements: { blocked: false },
  projects: [{ kind: "service", state: "CONFIGURED" }, { kind: "repository", state: "LOCAL" }],
};

test("walks through the team file, the machine, the projects, the secrets", () => {
  assert.equal(nextStep({ ...base, team: { configured: false, valid: false } }), "team");
  assert.equal(nextStep({ ...base, team: { configured: true, valid: false } }), "team");
  assert.equal(nextStep({ ...base, requirements: { blocked: true } }), "machine");
  assert.equal(nextStep({ ...base, projects: [{ kind: "service", state: "LOCAL" }] }), "projects");
  assert.equal(nextStep({ ...base, projects: [{ kind: "repository", state: "MISSING" }] }), "projects");
  assert.equal(nextStep({ ...base, missingSecrets: ["MONGO_PASSWORD"] }), "secrets");
  assert.equal(nextStep(base), "done");
});

test("a repository that cannot be loaded does not hold up the setup forever", () => {
  const stuck = { ...base, projects: [{ kind: "service", state: "CONFIGURED" }, { kind: "service", state: "NOT_LOADABLE" }] };
  assert.equal(nextStep(stuck), "done");
  assert.equal(nextStep({ ...base, projects: [{ kind: "service", state: "REMOVED" }] }), "done");
});

test("passes only when nothing is left to do", () => {
  assert.equal(startupPassed(base), true);
  assert.equal(startupPassed({ ...base, missingSecrets: ["MONGO_PASSWORD"] }), false);
  assert.equal(startupPassed({ ...base, requirements: { blocked: true } }), false);
});

test("says what was checked and what to do", () => {
  const reqs = { blocked: false, results: [{ outcome: "PASS", id: "java", name: "Java 21" }, { outcome: "PASS", id: "node", name: "Node.js" }] };
  assert.equal(passedMessage({ ...base, team: { configured: true, valid: true, team: "acme-shop" }, requirements: reqs }),
    "All checks passed for acme-shop: team file, 2 machine requirements, 1 service");
  assert.deepEqual(todo({ ...base, requirements: { blocked: true, results: [{ outcome: "FAIL", id: "java", name: "Java 21" }] } }),
    ["Fix Java 21 on this machine (step 2 says how), then Check again."]);
  assert.deepEqual(todo({ ...base, requirements: reqs, missingSecrets: ["MONGO_PASSWORD"],
    projects: [{ kind: "service", state: "LOCAL" }, { kind: "repository", state: "MISSING" }] }),
    ["Clone 1 missing repository (step 3).", "Load 1 project already on this machine (step 3).", "Enter your MONGO_PASSWORD (step 4)."]);
});
