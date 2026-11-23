import { test } from "node:test";
import assert from "node:assert/strict";
import { publishedPorts } from "./ports.ts";

test("published ports once each, unpublished ones left out", () => {
  assert.deepEqual(publishedPorts("0.0.0.0:5432->5432/tcp, :::5432->5432/tcp"), ["5432→5432"]);
  assert.deepEqual(publishedPorts("0.0.0.0:9092->9092/tcp, 2181/tcp, 127.0.0.1:8025->8025/tcp"), ["9092→9092", "8025→8025"]);
  assert.deepEqual(publishedPorts(""), []);
});
