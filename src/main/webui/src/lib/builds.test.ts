import { test } from "node:test";
import assert from "node:assert/strict";
import { compareVersions, filterBuilds, NO_FILTER, sortBuilds } from "./builds.ts";

const b = (id: string, serviceName: string, version: string | null, mode: string, status: string, started: string, mins: number) => ({
  id, serviceName, version, profile: "dev", mode, status, createdAt: started, startedAt: started,
  finishedAt: new Date(Date.parse(started) + mins * 60000).toISOString(),
});
const list = [
  b("1", "orders", "1.9.0", "JVM", "SUCCEEDED", "2026-11-01T10:00:00Z", 2),
  b("2", "billing", "1.10.0", "NATIVE", "FAILED", "2026-11-01T11:00:00Z", 9),
  b("3", "orders", "1.10.0", "JVM", "SUCCEEDED", "2026-11-01T12:00:00Z", 3),
];

test("filters by text, status and mode", () => {
  assert.deepEqual(filterBuilds(list, { ...NO_FILTER, text: "ORD" }).map((x) => x.id), ["1", "3"]);
  assert.deepEqual(filterBuilds(list, { ...NO_FILTER, text: "1.10" }).map((x) => x.id), ["2", "3"]);
  assert.deepEqual(filterBuilds(list, { ...NO_FILTER, status: "FAILED" }).map((x) => x.id), ["2"]);
  assert.deepEqual(filterBuilds(list, { ...NO_FILTER, mode: "JVM" }).map((x) => x.id), ["1", "3"]);
});

test("sorts by each column, versions by their numbers", () => {
  assert.deepEqual(sortBuilds(list, "service", "asc").map((x) => x.id), ["2", "3", "1"]);
  assert.deepEqual(sortBuilds(list, "version", "asc").map((x) => x.id), ["1", "3", "2"]);
  assert.deepEqual(sortBuilds(list, "started", "desc").map((x) => x.id), ["3", "2", "1"]);
  assert.deepEqual(sortBuilds(list, "time", "desc").map((x) => x.id), ["2", "3", "1"]);
  assert.ok(compareVersions("1.10.0", "1.9.0") > 0);
});
