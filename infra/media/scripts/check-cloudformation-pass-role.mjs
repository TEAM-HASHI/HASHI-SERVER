import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

function fail(message) {
  throw new Error(message);
}

function exactKeys(value, expected, label) {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    fail(`${label} contains fields outside the reviewed contract.`);
  }
}

function policyDocument(value) {
  if (typeof value === "string") {
    try {
      return JSON.parse(decodeURIComponent(value));
    } catch {
      fail("Permissions boundary policy document is not valid JSON.");
    }
  }
  return value;
}

export function validatePassRoleBoundaryDocument(rawDocument, workerRoleArn) {
  const document = policyDocument(rawDocument);
  if (!document || typeof document !== "object" || Array.isArray(document)) {
    fail("Permissions boundary policy document must be an object.");
  }
  exactKeys(document, ["Statement", "Version"], "Permissions boundary document");
  if (document.Version !== "2012-10-17" || !Array.isArray(document.Statement)) {
    fail("Permissions boundary policy version or statements are invalid.");
  }
  if (document.Statement.length !== 2) {
    fail("Permissions boundary must contain only the two reviewed statements.");
  }

  const statements = new Map(document.Statement.map((statement) => [statement.Sid, statement]));
  const nonPassRole = statements.get("PreserveIdentityPermissionsExceptPassRole");
  const exactPassRole = statements.get("AllowOnlyExactImageWorkerPassRole");
  if (!nonPassRole || !exactPassRole || statements.size !== 2) {
    fail("Permissions boundary statement IDs do not match the reviewed contract.");
  }

  exactKeys(nonPassRole, ["Effect", "NotAction", "Resource", "Sid"], nonPassRole.Sid);
  if (
    nonPassRole.Effect !== "Allow" ||
    nonPassRole.NotAction !== "iam:PassRole" ||
    nonPassRole.Resource !== "*"
  ) {
    fail("Permissions boundary must exclude iam:PassRole from the general allowance.");
  }

  exactKeys(
    exactPassRole,
    ["Action", "Condition", "Effect", "Resource", "Sid"],
    exactPassRole.Sid,
  );
  const condition = exactPassRole.Condition;
  if (!condition || typeof condition !== "object" || Array.isArray(condition)) {
    fail("Permissions boundary PassRole condition is invalid.");
  }
  exactKeys(condition, ["StringEquals"], "Permissions boundary PassRole condition");
  const stringEquals = condition.StringEquals;
  if (!stringEquals || typeof stringEquals !== "object" || Array.isArray(stringEquals)) {
    fail("Permissions boundary PassRole StringEquals condition is invalid.");
  }
  exactKeys(
    stringEquals,
    ["iam:PassedToService"],
    "Permissions boundary PassRole StringEquals condition",
  );
  if (
    exactPassRole.Effect !== "Allow" ||
    exactPassRole.Action !== "iam:PassRole" ||
    exactPassRole.Resource !== workerRoleArn ||
    stringEquals["iam:PassedToService"] !== "lambda.amazonaws.com"
  ) {
    fail("Permissions boundary must allow only the exact worker role to Lambda.");
  }
}

function defaultAwsJson(args) {
  const result = spawnSync("aws", args, { encoding: "utf8", windowsHide: true });
  if (result.error || result.status !== 0) {
    fail(`AWS policy probe failed: aws ${args[0]} ${args[1]}.`);
  }
  try {
    return JSON.parse(result.stdout);
  } catch {
    fail(`AWS policy probe returned invalid JSON: aws ${args[0]} ${args[1]}.`);
  }
}

export function verifyCloudFormationPassRole(
  executionRoleArn,
  expectedBoundaryArn,
  workerRoleArn,
  awsJson = defaultAwsJson,
) {
  const executionRoleMatch = executionRoleArn.match(
    /^arn:[a-z0-9-]+:iam::[0-9]{12}:role\/(?:[A-Za-z0-9+=,.@_-]+\/)*([A-Za-z0-9+=,.@_-]+)$/,
  );
  const executionRoleName = executionRoleMatch?.[1];
  if (!executionRoleName) {
    fail("CloudFormation execution role ARN is invalid.");
  }

  const role = awsJson(["iam", "get-role", "--role-name", executionRoleName, "--output", "json"]);
  if (role?.Role?.PermissionsBoundary?.PermissionsBoundaryArn !== expectedBoundaryArn) {
    fail("CloudFormation execution role does not use the reviewed permissions boundary.");
  }

  const policy = awsJson([
    "iam",
    "get-policy",
    "--policy-arn",
    expectedBoundaryArn,
    "--output",
    "json",
  ]);
  const versionId = policy?.Policy?.DefaultVersionId;
  if (typeof versionId !== "string" || !/^v[1-9][0-9]*$/.test(versionId)) {
    fail("Permissions boundary default version is missing or invalid.");
  }

  const version = awsJson([
    "iam",
    "get-policy-version",
    "--policy-arn",
    expectedBoundaryArn,
    "--version-id",
    versionId,
    "--output",
    "json",
  ]);
  validatePassRoleBoundaryDocument(version?.PolicyVersion?.Document, workerRoleArn);

  const simulation = awsJson([
    "iam",
    "simulate-principal-policy",
    "--policy-source-arn",
    executionRoleArn,
    "--action-names",
    "iam:PassRole",
    "--resource-arns",
    workerRoleArn,
    "--context-entries",
    "ContextKeyName=iam:PassedToService,ContextKeyType=string,ContextKeyValues=lambda.amazonaws.com",
    "--output",
    "json",
  ]);
  const results = simulation?.EvaluationResults;
  if (!Array.isArray(results) || results.length !== 1 || results[0]?.EvalDecision !== "allowed") {
    fail("CloudFormation execution role cannot pass the exact worker role to Lambda.");
  }
}

const isCli =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isCli) {
  if (process.argv.length !== 5) {
    console.error(
      "Usage: node check-cloudformation-pass-role.mjs <execution-role-arn> <boundary-arn> <worker-role-arn>",
    );
    process.exit(2);
  }
  verifyCloudFormationPassRole(process.argv[2], process.argv[3], process.argv[4]);
  console.log("CloudFormation PassRole is capped by the reviewed permissions boundary.");
}
