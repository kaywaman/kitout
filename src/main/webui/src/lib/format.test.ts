import { test } from "node:test";
import assert from "node:assert/strict";
import { ago, bytes, label, plural, seconds, since } from "./format.ts";

const NOW = new Date("2026-10-28T10:00:00Z");

test("formats time since", () => {
  assert.equal(since("2026-10-28T09:59:55Z", NOW), "just now");
  assert.equal(since("2026-10-28T09:59:30Z", NOW), "30 s");
  assert.equal(since("2026-10-28T09:56:00Z", NOW), "4 min");
  assert.equal(since("2026-10-28T07:55:00Z", NOW), "2 h 5 min");
  assert.equal(since(null, NOW), "");
});

test("turns constants into labels", () => {
  assert.equal(label("RUNNING"), "Running");
  assert.equal(label("GIT_CREDENTIAL_HELPER"), "Git credential helper");
});

test("formats start times", () => {
  assert.equal(seconds(12400), "12.4 s");
  assert.equal(seconds(95000), "1 min 35 s");
  assert.equal(seconds(null), "");
});

test("pluralises", () => {
  assert.equal(plural(1, "error"), "1 error");
  assert.equal(plural(3, "error"), "3 errors");
});

test("ago reads naturally for very recent times", () => {
  const now = new Date("2026-10-29T10:00:00Z");
  assert.equal(ago("2026-10-29T09:59:58Z", now), "just now");
  assert.equal(ago("2026-10-29T09:56:00Z", now), "4 min ago");
  assert.equal(ago(null, now), "");
});

test("bytes reads like a file manager", () => {
  assert.equal(bytes(0), "0 B");
  assert.equal(bytes(1536), "1.5 KB");
  assert.equal(bytes(5_300_000), "5.1 MB");
  assert.equal(bytes(250 * 1024 * 1024), "250 MB");
});
