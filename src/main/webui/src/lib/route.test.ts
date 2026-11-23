import { test } from "node:test";
import assert from "node:assert/strict";
import { parseRoute, serviceLogHref } from "./route.ts";

test("defaults to the services page", () => {
  assert.deepEqual(parseRoute(""), { kind: "page", page: "services" });
  assert.deepEqual(parseRoute("#/nonsense"), { kind: "page", page: "services" });
});

test("parses pages and log windows", () => {
  assert.deepEqual(parseRoute("#/doctor"), { kind: "page", page: "doctor" });
  assert.deepEqual(parseRoute("#/log"), { kind: "page", page: "log" });
  assert.deepEqual(parseRoute("#/docker"), { kind: "page", page: "docker" });
  assert.deepEqual(parseRoute("#/logs/acme.shop.orders"), { kind: "service-log", serviceId: "acme.shop.orders" });
  assert.deepEqual(parseRoute("#/build-log/20261028-1"), { kind: "build-log", buildId: "20261028-1" });
});

test("round-trips service ids", () => {
  assert.deepEqual(parseRoute(serviceLogHref("a b/c")), { kind: "service-log", serviceId: "a b/c" });
});
