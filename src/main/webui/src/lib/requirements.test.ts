import { test } from "node:test";
import assert from "node:assert/strict";
import { foundText, ordered } from "./requirements.ts";

test("says what was found and what is required", () => {
  assert.equal(foundText({ found: "17.0.2", required: "21.*", outcome: "FAIL" }), "17.0.2, required 21.*");
  assert.equal(foundText({ found: "", required: ">=28.5.2", outcome: "FAIL" }), "not found, required >=28.5.2");
  assert.equal(foundText({ found: "v22.23.4", required: ">=22.23.4", outcome: "PASS" }), "v22.23.4");
});

test("puts failures first, then warnings", () => {
  const r = ordered([{ outcome: "PASS", n: 1 }, { outcome: "WARN", n: 2 }, { outcome: "FAIL", n: 3 }, { outcome: "PASS", n: 4 }]);
  assert.deepEqual(r.map((x) => x.n), [3, 2, 1, 4]);
});
