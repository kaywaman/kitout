import { test } from "node:test";
import assert from "node:assert/strict";
import {
  blocksRemoval, buildLook, canBuild, needsTag, canRestart, canStart, canStop, containerLook, environmentLook, healthLook, pullLook, stateLook, syncLook,
} from "./status.ts";

const svc = (state: string, mode = "JVM", health = "UNKNOWN") => ({ state, mode, health });

test("a failed service can be stopped and restarted, not started", () => {
  assert.equal(canStop(svc("FAILED")), true);
  assert.equal(canRestart(svc("FAILED")), true);
  assert.equal(canStart(svc("FAILED")), false);
});

test("a stopped service can only be started", () => {
  assert.equal(canStart(svc("STOPPED")), true);
  assert.equal(canStop(svc("STOPPED")), false);
  assert.equal(canRestart(svc("STOPPED")), false);
});

test("removal is blocked while kitout runs the service, never for IDE services", () => {
  for (const state of ["STARTING", "RUNNING", "STOPPING", "FAILED"]) {
    assert.equal(blocksRemoval(svc(state)), true, state);
    assert.equal(blocksRemoval(svc(state, "IDE")), false, `${state} in IDE mode`);
  }
  assert.equal(blocksRemoval(svc("STOPPED")), false);
});

test("kitout offers no start or stop for IDE services", () => {
  assert.equal(canStart(svc("STOPPED", "IDE")), false);
  assert.equal(canStop(svc("RUNNING", "IDE")), false);
});

test("health is shown for running services only", () => {
  assert.equal(healthLook(svc("STARTING", "JVM", "UNHEALTHY")), null);
  assert.deepEqual(healthLook(svc("RUNNING", "JVM", "UNHEALTHY")), { label: "Unhealthy", color: "warning" });
  assert.equal(healthLook(svc("RUNNING", "JVM", "UNKNOWN")), null);
});

test("unknown values still render", () => {
  assert.deepEqual(stateLook("PAUSED"), { label: "PAUSED", color: "default" });
  assert.equal(buildLook("RUNNING").busy, true);
});

test("containers: health words in docker's status decide the chip", () => {
  assert.equal(containerLook("running", "Up 3 minutes (healthy)").label, "Healthy");
  assert.equal(containerLook("running", "Up 3 minutes (unhealthy)").color, "warning");
  assert.equal(containerLook("running", "Up 5 seconds (health: starting)").busy, true);
  assert.equal(containerLook("running", "Up 2 hours").label, "Running");
  assert.deepEqual(containerLook("exited", "Exited (137) 2 hours ago"), { label: "Exited (137)", color: "error" });
  assert.deepEqual(containerLook("exited", "Exited (0) 1 minute ago"), { label: "Exited", color: "default" });
});

test("environments: all, some or none of their containers up", () => {
  assert.deepEqual(environmentLook(0, 0), { label: "Down", color: "default" });
  assert.deepEqual(environmentLook(3, 3), { label: "3/3 up", color: "success" });
  assert.deepEqual(environmentLook(2, 3), { label: "2/3 up", color: "warning" });
});

test("sync: what a pull would do", () => {
  const base = { behind: 0, ahead: 0, localChanges: false, problem: null };
  assert.equal(syncLook(base).label, "Up to date");
  assert.deepEqual(syncLook({ ...base, behind: 3 }), { label: "3 new commits", color: "info" });
  assert.equal(syncLook({ ...base, behind: 1, localChanges: true }).color, "warning");
  assert.equal(syncLook({ ...base, problem: "Fetch failed: GitLab is not reachable" }).color, "error");
  assert.equal(pullLook("SKIPPED").color, "warning");
});

test("registry image needs a chosen tag", () => {
  assert.equal(needsTag({ mode: "IMAGE", image: null }), true);
  assert.equal(needsTag({ mode: "IMAGE", image: "registry.example.com/acme/orders:mr-1" }), false);
  assert.equal(needsTag({ mode: "JVM", image: null }), false);
});

test("kitout does not build a service in IDE mode", () => {
  assert.equal(canBuild({ mode: "IDE" }), false);
  assert.equal(canBuild({ mode: "JVM" }), true);
  assert.equal(canBuild({ mode: "IMAGE" }), true);
  assert.equal(canBuild({ mode: "SCRIPT", buildable: false }), false, "Node: not built by kitout");
});

test("a service in IDE mode is not pulled by kitout, and says so calmly", () => {
  assert.deepEqual(syncLook({ behind: 2, ahead: 0, localChanges: false, problem: "In your IDE: pull it there" }), { label: "In your IDE", color: "default" });
  assert.deepEqual(pullLook("SKIPPED", "In your IDE: pull it there"), { label: "In your IDE", color: "default" });
  assert.equal(pullLook("SKIPPED", "Uncommitted changes").label, "Skipped");
});
