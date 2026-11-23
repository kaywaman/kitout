import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanVolumes, volumeProblem } from "./serviceOptions.ts";

test("an empty row is fine and dropped; a half row is not", () => {
  assert.equal(volumeProblem({ hostPath: "", containerPath: " ", readOnly: false }), undefined);
  assert.match(volumeProblem({ hostPath: "~/certs", containerPath: "", readOnly: false }) ?? "", /both paths/);
  assert.deepEqual(cleanVolumes([{ hostPath: " ~/certs ", containerPath: "/certs", readOnly: true }, { hostPath: "", containerPath: "", readOnly: false }]),
    [{ hostPath: "~/certs", containerPath: "/certs", readOnly: true }]);
});

test("paths must be absolute (or start with ~ on this machine)", () => {
  assert.match(volumeProblem({ hostPath: "certs", containerPath: "/c", readOnly: false }) ?? "", /absolute/);
  assert.match(volumeProblem({ hostPath: "~/certs", containerPath: "c", readOnly: false }) ?? "", /in the container must be absolute/);
  assert.equal(volumeProblem({ hostPath: "/srv/x", containerPath: "/x", readOnly: false }), undefined);
});
