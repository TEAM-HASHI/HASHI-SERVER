import assert from "node:assert/strict";
import test from "node:test";

import {
  validatePassRoleBoundaryDocument,
  verifyCloudFormationPassRole,
} from "./check-cloudformation-pass-role.mjs";

const executionRoleArn = "arn:aws:iam::123456789012:role/hashi-dev-media-cloudformation";
const boundaryArn =
  "arn:aws:iam::123456789012:policy/hashi-dev-media-cloudformation-execution-boundary";
const workerRoleArn =
  "arn:aws:iam::123456789012:role/hashi-dev-media-image-transform-lambda";

function exactBoundary(resource = workerRoleArn) {
  return {
    Version: "2012-10-17",
    Statement: [
      {
        Sid: "PreserveIdentityPermissionsExceptPassRole",
        Effect: "Allow",
        NotAction: "iam:PassRole",
        Resource: "*",
      },
      {
        Sid: "AllowOnlyExactImageWorkerPassRole",
        Effect: "Allow",
        Action: "iam:PassRole",
        Resource: resource,
        Condition: {
          StringEquals: {
            "iam:PassedToService": "lambda.amazonaws.com",
          },
        },
      },
    ],
  };
}

function successfulAwsJson() {
  let invocation = 0;
  return () => {
    invocation += 1;
    if (invocation === 1) {
      return { Role: { PermissionsBoundary: { PermissionsBoundaryArn: boundaryArn } } };
    }
    if (invocation === 2) {
      return { Policy: { DefaultVersionId: "v3" } };
    }
    if (invocation === 3) {
      return { PolicyVersion: { Document: exactBoundary() } };
    }
    return { EvaluationResults: [{ EvalDecision: "allowed" }] };
  };
}

test("accepts the exact version-controlled PassRole boundary and effective grant", () => {
  assert.doesNotThrow(() =>
    verifyCloudFormationPassRole(
      executionRoleArn,
      boundaryArn,
      workerRoleArn,
      successfulAwsJson(),
    ),
  );
});

test("rejects a broad PassRole resource in the permissions boundary", () => {
  assert.throws(
    () => validatePassRoleBoundaryDocument(exactBoundary("*"), workerRoleArn),
    /only the exact worker role/,
  );
});

test("rejects a permissions boundary attached under a different ARN", () => {
  const awsJson = () => ({
    Role: { PermissionsBoundary: { PermissionsBoundaryArn: `${boundaryArn}-other` } },
  });

  assert.throws(
    () => verifyCloudFormationPassRole(executionRoleArn, boundaryArn, workerRoleArn, awsJson),
    /does not use the reviewed permissions boundary/,
  );
});

test("fails closed when any AWS policy probe errors", () => {
  const awsJson = () => {
    throw new Error("AWS policy probe failed: stubbed outage");
  };

  assert.throws(
    () => verifyCloudFormationPassRole(executionRoleArn, boundaryArn, workerRoleArn, awsJson),
    /stubbed outage/,
  );
});

test("rejects an exact boundary when the role has no effective PassRole grant", () => {
  const awsJson = successfulAwsJson();
  let invocation = 0;
  const deniedSimulation = (args) => {
    invocation += 1;
    const result = awsJson(args);
    return invocation === 4
      ? { EvaluationResults: [{ EvalDecision: "implicitDeny" }] }
      : result;
  };

  assert.throws(
    () =>
      verifyCloudFormationPassRole(
        executionRoleArn,
        boundaryArn,
        workerRoleArn,
        deniedSimulation,
      ),
    /cannot pass the exact worker role/,
  );
});
