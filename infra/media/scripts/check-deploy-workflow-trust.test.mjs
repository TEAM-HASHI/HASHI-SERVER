import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";

import { validateDeployWorkflowTrust } from "./check-deploy-workflow-trust.mjs";

const workflow = fs
  .readFileSync(".github/workflows/deploy-image-pipeline.yml", "utf8")
  .replaceAll("\r\n", "\n");

test("accepts the reviewed dev-only AWS trust boundary", () => {
  assert.doesNotThrow(() => validateDeployWorkflowTrust(workflow));
});

// Run the workflow's actual pre-checkout guard, not a second implementation of its rules.
const guard = workflow.split("          set -euo pipefail\n")[1]
  .split("\n      - name: Checkout source code")[0]
  .split("\n").map((line) => line.replace(/^          /, "")).join("\n");
const bash = process.platform === "win32"
  ? path.resolve(path.dirname(execFileSync("where.exe", ["git"], {encoding:"utf8"}).trim().split(/\r?\n/)[0]), "../bin/bash.exe")
  : "bash";

for (const [target, ref, confirmation, allowed] of [
  ["dev", "refs/heads/develop", "", true],
  ["dev", "refs/heads/release/1.3.0", "prepare-prod-artifact", false],
  ["dev", "refs/heads/main", "", false],
  ["prod", "refs/heads/main", "prepare-prod-artifact", true],
  ["prod", "refs/heads/release/1.3.0", "prepare-prod-artifact", true],
  ["prod", "refs/heads/release/1.3.0", "", false],
  ["prod", "refs/heads/develop", "prepare-prod-artifact", false],
  ["prod", "refs/tags/release/1.3.0", "prepare-prod-artifact", false],
  ["prod", "refs/pull/123/merge", "prepare-prod-artifact", false],
  ["prod", "refs/heads/release/1.3.0/unsafe", "prepare-prod-artifact", false],
  ["prod", "refs/heads/release/1x3x0", "prepare-prod-artifact", false],
  ["prod", "refs/heads/release/next", "prepare-prod-artifact", false],
  ["other", "refs/heads/main", "prepare-prod-artifact", false],
]) {
  test(`operation guard ${allowed ? "allows" : "rejects"} ${target} ${ref} ${confirmation || "no confirmation"}`, () => {
    const result = spawnSync(bash, ["-c", `set -euo pipefail\n${guard}`], {
      env: {...process.env, TARGET:target, DEPLOYMENT_REF:ref, PRODUCTION_CONFIRMATION:confirmation},
      encoding:"utf8", windowsHide:true,
    });
    assert.ifError(result.error);
    assert.equal(result.status, allowed ? 0 : 1, result.stdout + result.stderr);
  });
}

test("rejects broadening the production artifact branch guard", () => {
  const changed = workflow.replace("^refs/heads/release/[0-9]+\\.[0-9]+\\.[0-9]+$", "^refs/heads/.*$");
  assert.throws(() => validateDeployWorkflowTrust(changed), /versioned release ref/);
});

test("rejects OIDC permission in the build job", () => {
  const changed = workflow.replace(
    "    permissions:\n      contents: read\n    outputs:",
    "    permissions:\n      contents: read\n      id-token: write\n    outputs:",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /Build job must not request/);
});

test("rejects a production AWS repository variable", () => {
  const changed = workflow.replace(
    "          MEDIA_DEV_AWS_ACCOUNT_ID:",
    "          MEDIA_PROD_AWS_ACCOUNT_ID: unsafe\n          MEDIA_DEV_AWS_ACCOUNT_ID:",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /Production AWS configuration/);
});

test("rejects recomputing the artifact name in the deploy job", () => {
  const changed = workflow.replace(
    "name: ${{ needs.build.outputs.artifact_name }}",
    "name: image-transform-${{ github.sha }}-${{ github.run_attempt }}",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /exported by the build job/);
});

test("rejects handing off a production artifact without a package smoke test", () => {
  const changed = workflow.replace("          node scripts/verify-package.mjs artifacts/package\n", "");

  assert.throws(() => validateDeployWorkflowTrust(changed), /before handoff/);
});

test("rejects executing worker artifact code in the OIDC-capable deploy job", () => {
  const changed = workflow.replace(
    "          test -f worker/image-transform/artifacts/package/package.json\n",
    "          node worker/image-transform/scripts/verify-package.mjs worker/image-transform/artifacts/package\n",
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /cannot execute worker artifact code/);
});

test("rejects creating a production change set in GitHub Actions", () => {
  const changed = workflow.replace(
    '          sam deploy "${deploy_args[@]}"',
    '          sam deploy "${deploy_args[@]}" --no-execute-changeset',
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /cannot create a production/);
});
