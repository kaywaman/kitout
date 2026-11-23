import { test } from "node:test";
import assert from "node:assert/strict";
import { breadcrumbs } from "./paths.ts";

test("breadcrumbs start at home", () => {
  assert.deepEqual(breadcrumbs("/home/dev", "/home/dev"), [{ name: "Home", path: "/home/dev" }]);
  assert.deepEqual(breadcrumbs("/home/dev/work/infra", "/home/dev").map((c) => c.path),
    ["/home/dev", "/home/dev/work", "/home/dev/work/infra"]);
  assert.deepEqual(breadcrumbs("/home/developer/x", "/home/dev").length, 1, "a sibling with the same prefix is not below home");
});
