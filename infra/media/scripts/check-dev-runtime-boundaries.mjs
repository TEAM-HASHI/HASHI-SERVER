import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const boundarySource = new URL("../bootstrap/dev-runtime-boundaries.json", import.meta.url);
const roleNames = {
  WorkerRuntimeBoundary: "hashi-dev-media-image-transform-lambda",
  SpringRuntimeBoundary: "hashi-dev-ec2-role",
};

function fail(message) { throw new Error(message); }

export function validateDevBindings({ accountId, region, deliveryBucket, springRoleName, artifactBucket,
  executionRoleArn, deployRoleArn, backfillEnabled, cleanupEnabled }) {
  if (!/^[0-9]{12}$/.test(accountId) || !/^[a-z]{2}(-[a-z]+)+-[0-9]+$/.test(region)) {
    fail("Invalid DEV account or region.");
  }
  if (!/^hashi-dev-[a-z0-9-]+$/.test(deliveryBucket) || springRoleName !== roleNames.SpringRuntimeBoundary) {
    fail("Only the reviewed DEV delivery bucket and EC2 role are supported.");
  }
  if (artifactBucket !== `hashi-dev-media-artifacts-${accountId}`) fail("Unexpected DEV artifact bucket.");
  const prefix = `arn:aws:iam::${accountId}:role/`;
  if (executionRoleArn !== `${prefix}hashi-dev-media-cloudformation-execution` ||
      deployRoleArn !== `${prefix}hashi-dev-media-github-deploy`) fail("Unexpected DEV deployment roles.");
  if (backfillEnabled !== "false" || cleanupEnabled !== "false") {
    fail("DEV bootstrap does not authorize backfill or cleanup.");
  }
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(
    Object.keys(value).sort().map((key) => [key, canonical(value[key])]),
  );
  return value;
}

function decodeDocument(value) {
  if (typeof value !== "string") return value;
  try { return JSON.parse(decodeURIComponent(value)); }
  catch { fail("Runtime boundary is not valid JSON."); }
}

export function renderRuntimeBoundaries(accountId, region, deliveryBucket) {
  const source = JSON.parse(fs.readFileSync(boundarySource, "utf8"));
  const values = { "AWS::Partition": "aws", "AWS::AccountId": accountId,
    "AWS::Region": region, DeliveryBucket: deliveryBucket };
  function render(value) {
    if (value && typeof value === "object" && Object.hasOwn(value, "Fn::Sub")) {
      return value["Fn::Sub"].replace(/\$\{([^}]+)\}/g, (_, key) => {
        if (!Object.hasOwn(values, key)) fail("Unknown runtime boundary substitution.");
        return values[key];
      });
    }
    if (Array.isArray(value)) return value.map(render);
    if (value && typeof value === "object") return Object.fromEntries(
      Object.entries(value).map(([key, child]) => [key, render(child)]),
    );
    return value;
  }
  return Object.fromEntries(Object.entries(roleNames).map(([id, roleName]) => {
    const properties = source.Resources[id].Properties;
    return [id, { roleName, arn: `arn:aws:iam::${accountId}:policy/${properties.ManagedPolicyName}`,
      document: render(properties.PolicyDocument) }];
  }));
}

export function validateRuntimeBoundaryDocument(actual, expected) {
  if (JSON.stringify(canonical(decodeDocument(actual))) !== JSON.stringify(canonical(expected))) {
    fail("Runtime boundary document differs from the reviewed DEV ceiling.");
  }
}

function defaultAwsJson(args) {
  const result = spawnSync("aws", args, { encoding: "utf8", windowsHide: true });
  if (result.error || result.status !== 0) {
    const error = new Error(`DEV boundary probe failed: aws ${args[0]} ${args[1]}.`);
    // Only a genuinely absent worker is allowed before the first deployment.
    error.code = /\(NoSuchEntity\)/.test(result.stderr ?? "") ? "NoSuchEntity" : "ProbeFailed";
    throw error;
  }
  try { return JSON.parse(result.stdout); }
  catch { fail("DEV boundary probe returned invalid JSON."); }
}

export function verifyDevRuntimeBoundaries(config, awsJson = defaultAwsJson) {
  validateDevBindings(config);
  const boundaries = renderRuntimeBoundaries(config.accountId, config.region, config.deliveryBucket);
  for (const [id, expected] of Object.entries(boundaries)) {
    const policy = awsJson(["iam", "get-policy", "--policy-arn", expected.arn, "--output", "json"]);
    const version = policy?.Policy?.DefaultVersionId;
    if (!/^v[1-9][0-9]*$/.test(version ?? "")) fail("Missing runtime boundary default version.");
    const document = awsJson(["iam", "get-policy-version", "--policy-arn", expected.arn,
      "--version-id", version, "--output", "json"]);
    validateRuntimeBoundaryDocument(document?.PolicyVersion?.Document, expected.document);
    let role;
    try {
      role = awsJson(["iam", "get-role", "--role-name", expected.roleName, "--output", "json"]);
    } catch (error) {
      if (id === "WorkerRuntimeBoundary" && error.code === "NoSuchEntity") continue;
      throw error;
    }
    if (role?.Role?.Arn !== `arn:aws:iam::${config.accountId}:role/${expected.roleName}` ||
        role?.Role?.PermissionsBoundary?.PermissionsBoundaryArn !== expected.arn) {
      fail("DEV runtime role is missing its exact reviewed permissions boundary.");
    }
  }
}

const isCli = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isCli) {
  const env = process.env;
  verifyDevRuntimeBoundaries({ accountId: env.AWS_ACCOUNT_ID, region: env.AWS_REGION,
    deliveryBucket: env.MEDIA_DELIVERY_BUCKET_NAME, springRoleName: env.MEDIA_SPRING_APPLICATION_ROLE_NAME,
    artifactBucket: env.AWS_SAM_ARTIFACT_BUCKET, executionRoleArn: env.AWS_CLOUDFORMATION_EXECUTION_ROLE_ARN,
    deployRoleArn: env.AWS_DEPLOY_ROLE_ARN, backfillEnabled: env.MEDIA_BACKFILL_ACCESS_ENABLED,
    cleanupEnabled: env.MEDIA_CLEANUP_ACCESS_ENABLED });
  console.log("DEV runtime boundaries and resource bindings match the reviewed bootstrap.");
}
