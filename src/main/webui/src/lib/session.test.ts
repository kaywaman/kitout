import { test } from "node:test";
import assert from "node:assert/strict";
import { tokenFromHash } from "./session.ts";

test("reads the token from the fragment", () => {
  assert.equal(tokenFromHash("#token=abc_DEF-123"), "abc_DEF-123");
  assert.equal(tokenFromHash("#/logs/orders&token=xyz"), "xyz");
});

test("returns null without a token", () => {
  assert.equal(tokenFromHash(""), null);
  assert.equal(tokenFromHash("#/services"), null);
});
