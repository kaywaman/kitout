import { test } from "node:test";
import assert from "node:assert/strict";
import { missingFields, splitGitLabLink } from "./teamSource.ts";

test("a GitLab file link splits into host, repository, branch and path", () => {
  assert.deepEqual(splitGitLabLink("https://gitlab.example.com/acme/platform/infra/-/blob/main/kitout/kitout-team.json"), {
    host: "gitlab.example.com", repo: "acme/platform/infra", ref: "main", path: "kitout/kitout-team.json",
  });
  assert.deepEqual(splitGitLabLink("gitlab.example.com/acme/infra/-/raw/release/kitout-team.json?ref_type=heads"), {
    host: "gitlab.example.com", repo: "acme/infra", ref: "release", path: "kitout-team.json",
  });
});

test("a GitLab link without host keeps the host field as it is", () => {
  assert.deepEqual(splitGitLabLink("acme/platform/infra/-/blob/main/kitout/kitout-team.json"), {
    repo: "acme/platform/infra", ref: "main", path: "kitout/kitout-team.json",
  });
});

test("GitHub file links split too", () => {
  const expected = { host: "github.com", repo: "acme/infra", ref: "main", path: "kitout/kitout-team.json" };
  assert.deepEqual(splitGitLabLink("https://github.com/acme/infra/blob/main/kitout/kitout-team.json"), expected);
  assert.deepEqual(splitGitLabLink("https://raw.githubusercontent.com/acme/infra/main/kitout/kitout-team.json"), expected);
  assert.deepEqual(splitGitLabLink("github.com/acme/infra/blob/main/kitout/kitout-team.json?plain=1"), expected);
});

test("a project link gives host and repository; plain text is no link", () => {
  assert.deepEqual(splitGitLabLink("https://gitlab.example.com/acme/platform/kitout-team.git"), { host: "gitlab.example.com", repo: "acme/platform/kitout-team" });
  assert.deepEqual(splitGitLabLink("https://github.com/acme/infra"), { host: "github.com", repo: "acme/infra" });
  assert.equal(splitGitLabLink("acme/infra"), null);
  assert.equal(splitGitLabLink("kitout/kitout-team.json"), null);
});

test("host and a group/project repository are needed", () => {
  assert.deepEqual(missingFields({ host: "", repo: "infra", path: "", ref: "" }), ["git host", "repository (group/project)"]);
  assert.deepEqual(missingFields({ host: "github.com", repo: "acme/infra", path: "", ref: "" }), []);
});
