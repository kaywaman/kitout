import { test } from "node:test";
import assert from "node:assert/strict";
import { SseParser } from "./sse.ts";

test("parses events split across chunks", () => {
  const p = new SseParser();
  assert.deepEqual(p.push("data: first li"), []);
  assert.deepEqual(p.push("ne\n\ndata:second\n\n"), ["first line", "second"]);
});

test("joins multi-line data and handles CRLF", () => {
  const p = new SseParser();
  assert.deepEqual(p.push("data: a\r\ndata: b\r\n\r\n"), ["a\nb"]);
});

test("ignores comments and other fields", () => {
  const p = new SseParser();
  assert.deepEqual(p.push(": keep-alive\nid: 3\ndata: x\n\n"), ["x"]);
});

test("keeps indentation beyond the first space", () => {
  const p = new SseParser();
  assert.deepEqual(p.push("data:   at org.acme.Foo\n\n"), ["  at org.acme.Foo"]);
});
