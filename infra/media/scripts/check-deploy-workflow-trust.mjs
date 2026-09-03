import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

function fail(message) {
  throw new Error(message);
}

function jobBlock(workflow, jobName, nextJobName) {
  const startMarker = `  ${jobName}:\n`;
  const start = workflow.indexOf(startMarker);
  if (start < 0) {
    fail(`Missing ${jobName} job.`);
  }
  if (!nextJobName) {
    return workflow.slice(start);
  }
  const end = workflow.indexOf(`  ${nextJobName}:\n`, start + startMarker.length);
  if (end < 0) {
    fail(`Missing ${nextJobName} job.`);
  }
  return workflow.slice(start, end);
}

function occurrenceCount(value, pattern) {
  return value.split(pattern).length - 1;
}

export function validateDeployWorkflowTrust(rawWorkflow) {
  const workflow = rawWorkflow.replaceAll("\r\n", "\n");
  const build = jobBlock(workflow, "build", "deploy");
  const deploy = jobBlock(workflow, "deploy");

  if (build.includes("id-token:")) {
    fail("Build job must not request an OIDC token.");
  }
  if (!build.includes("artifact_name: ${{ steps.package_metadata.outputs.artifact_name }}")) {
    fail("Build job must export the immutable artifact name.");
  }
  if (!build.includes("name: ${{ steps.package_metadata.outputs.artifact_name }}")) {
    fail("Artifact upload must use the exported artifact name.");
  }
  if (!build.includes("retention-days: 7")) {
    fail("Production handoff artifact must be retained for seven days.");
  }
  if (!build.includes("node scripts/verify-package.mjs artifacts/package")) {
    fail("Build artifact must pass the Linux runtime smoke test before handoff.");
  }
  if (
    !build.includes('[[ "${PRODUCTION_CONFIRMATION}" == "prepare-prod-artifact" ]]') ||
    !build.includes('[[ "${DEPLOYMENT_REF}" == "refs/heads/main" ]]')
  ) {
    fail("Production artifact build must require the reviewed main ref and confirmation.");
  }

  if (!deploy.includes("if: inputs.target == 'dev'")) {
    fail("AWS deploy job must be restricted to the dev target.");
  }
  if (!deploy.includes("id-token: write")) {
    fail("Only the dev deploy job should request the OIDC token.");
  }
  if (!deploy.includes("name: ${{ needs.build.outputs.artifact_name }}")) {
    fail("Dev deploy must download the artifact name exported by the build job.");
  }
  if (!deploy.includes("node worker/image-transform/scripts/verify-package.mjs")) {
    fail("Restored package must pass the Linux runtime smoke test before AWS access.");
  }
  const smokeIndex = deploy.indexOf("node worker/image-transform/scripts/verify-package.mjs");
  const credentialsIndex = deploy.indexOf("aws-actions/configure-aws-credentials@");
  if (credentialsIndex < 0 || smokeIndex < 0 || smokeIndex > credentialsIndex) {
    fail("Package smoke test must run before AWS credentials are requested.");
  }

  if (workflow.includes("MEDIA_PROD_") || workflow.includes("Configure short-lived prod AWS")) {
    fail("Production AWS configuration cannot be present in the GitHub workflow.");
  }
  if (workflow.includes("--no-execute-changeset")) {
    fail("GitHub workflow cannot create a production CloudFormation change set.");
  }
  if (occurrenceCount(workflow, "aws-actions/configure-aws-credentials@") !== 1) {
    fail("Workflow must contain exactly one dev-only AWS credential step.");
  }
}

const isCli =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isCli) {
  const workflowPath = process.argv[2] ?? ".github/workflows/deploy-image-pipeline.yml";
  validateDeployWorkflowTrust(fs.readFileSync(workflowPath, "utf8"));
  console.log("Deploy workflow keeps production outside the GitHub AWS trust boundary.");
}
