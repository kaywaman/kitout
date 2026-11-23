import { test } from "node:test";
import assert from "node:assert/strict";
import { envName, imagePath } from "./env.ts";

test("property names become variable names, as the backend does", () => {
  assert.equal(envName("quarkus.datasource.username"), "QUARKUS_DATASOURCE_USERNAME");
  assert.equal(envName("%dev.quarkus.http.port"), "_DEV_QUARKUS_HTTP_PORT");
  assert.equal(envName("DB_HOST"), "DB_HOST");
});

test("image path patterns, with or without the group", () => {
  assert.equal(imagePath("{namespace}/{project}", "Acme/Shop/Orders"), "acme/shop/orders");
  assert.equal(imagePath("{project}", "acme/shop/orders"), "orders");
  assert.equal(imagePath("{group}/{project}", "acme/shop/orders"), "acme/orders");
  assert.equal(imagePath("services/{project}", "acme/shop/orders"), "services/orders");
});
