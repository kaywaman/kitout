import { test } from "node:test";
import assert from "node:assert/strict";
import { lineLevel } from "./logLines.ts";

test("classifies Quarkus and Maven lines", () => {
  assert.equal(lineLevel("=== kitout: start (JVM) 2026-10-28T09:00:00Z ==="), "kitout");
  assert.equal(lineLevel("2026-10-28 09:00:01,123 ERROR [io.qua.run] Failed to start"), "error");
  assert.equal(lineLevel("java.lang.IllegalStateException: boom"), "error");
  assert.equal(lineLevel("[ERROR] BUILD FAILURE"), "error");
  assert.equal(lineLevel("2026-10-28 09:00:01,123 WARN  [io.qua.config] Unrecognized key"), "warn");
  assert.equal(lineLevel("2026-10-28 09:00:01,123 INFO  [io.quarkus] started in 1.2s"), undefined);
  assert.equal(lineLevel("ERRORS: none"), undefined, "a whole word only");
});
