import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import { validateDeployWorkflowTrust } from "./check-deploy-workflow-trust.mjs";

const workflow = fs.readFileSync(".github/workflows/deploy-image-pipeline.yml", "utf8");

test("accepts the reviewed dev-only AWS trust boundary", () => {
  assert.doesNotThrow(() => validateDeployWorkflowTrust(workflow));
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

test("rejects package smoke testing after AWS credentials", () => {
  const smoke = workflow.indexOf("      - name: Restore and smoke test Lambda package");
  const credentials = workflow.indexOf("      - name: Configure short-lived dev AWS credentials");
  const smokeBlock = workflow.slice(smoke, credentials);
  const changed = `${workflow.slice(0, smoke)}${workflow.slice(credentials).replace(
    "      - name: Verify AWS identity and existing dependencies",
    `${smokeBlock}      - name: Verify AWS identity and existing dependencies`,
  )}`;

  assert.throws(() => validateDeployWorkflowTrust(changed), /before AWS credentials/);
});

test("rejects creating a production change set in GitHub Actions", () => {
  const changed = workflow.replace(
    '          sam deploy "${deploy_args[@]}"',
    '          sam deploy "${deploy_args[@]}" --no-execute-changeset',
  );

  assert.throws(() => validateDeployWorkflowTrust(changed), /cannot create a production/);
});
