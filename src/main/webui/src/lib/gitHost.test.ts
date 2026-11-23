import { test } from "node:test";
import assert from "node:assert/strict";
import { defaultRegistry, gitHostKind } from "./gitHost.ts";

test("the kind is set, or guessed from the host like the backend does", () => {
  assert.equal(gitHostKind("", "github.com"), "GITHUB");
  assert.equal(gitHostKind(null, "gitlab.example.com"), "GITLAB");
  assert.equal(gitHostKind("", "git.example.com"), "GIT");
  assert.equal(gitHostKind("gitlab", "git.example.com"), "GITLAB");
});

test("each kind has its usual registry", () => {
  assert.equal(defaultRegistry("GITLAB", "gitlab.example.com"), "registry.gitlab.example.com");
  assert.equal(defaultRegistry("GITHUB", "github.com"), "ghcr.io");
  assert.equal(defaultRegistry("GIT", "git.example.com"), "");
  assert.equal(defaultRegistry("GITLAB", ""), "");
});
