import assert from "node:assert/strict";
import test from "node:test";
import { renderRuntimeBoundaries, validateDevBindings, validateRuntimeBoundaryDocument,
  verifyDevRuntimeBoundaries } from "./check-dev-runtime-boundaries.mjs";

const accountId = "123456789012";
const config = { accountId, region: "ap-northeast-2", deliveryBucket: "hashi-dev-delivery-example",
  springRoleName: "hashi-dev-ec2-role", artifactBucket: `hashi-dev-media-artifacts-${accountId}`,
  executionRoleArn: `arn:aws:iam::${accountId}:role/hashi-dev-media-cloudformation-execution`,
  deployRoleArn: `arn:aws:iam::${accountId}:role/hashi-dev-media-github-deploy`,
  backfillEnabled: "false", cleanupEnabled: "false" };
const boundaries = renderRuntimeBoundaries(config.accountId, config.region, config.deliveryBucket);

function probe(options = {}) {
  return (args) => {
    const expected = Object.values(boundaries).find((item) => args.includes(item.arn) || args.includes(item.roleName));
    assert.ok(expected, "Unexpected AWS target");
    if (args[1] === "get-policy") return { Policy: { DefaultVersionId: "v1" } };
    if (args[1] === "get-policy-version") {
      const document = structuredClone(expected.document);
      options.modify?.(document);
      return { PolicyVersion: { Document: document } };
    }
    if (args[1] === "get-role") {
      if (options.missingWorker && expected === boundaries.WorkerRuntimeBoundary) {
        throw Object.assign(new Error("worker lookup"), { code: options.missingWorker });
      }
      if (options.missingSpring && expected === boundaries.SpringRuntimeBoundary) {
        throw Object.assign(new Error("spring lookup"), { code: "NoSuchEntity" });
      }
      return { Role: { Arn: `arn:aws:iam::${accountId}:role/${expected.roleName}`,
        PermissionsBoundary: { PermissionsBoundaryArn: options.wrongBoundary ? "wrong" : expected.arn } } };
    }
    assert.fail("Unexpected AWS operation");
  };
}

test("accepts bounded DEV roles and the first deployment with no worker yet", () => {
  verifyDevRuntimeBoundaries(config, probe());
  verifyDevRuntimeBoundaries({ ...config, reconciliationReadEnabled: "true" }, probe());
  verifyDevRuntimeBoundaries(config, probe({ missingWorker: "NoSuchEntity" }));
});

test("read-only opt-in does not permit cleanup, backfill or malformed flags", () => {
  for (const value of ["", "TRUE", "true\n", true]) {
    assert.throws(() => validateDevBindings({ ...config, reconciliationReadEnabled: value }), /read access/);
  }
  for (const key of ["cleanupEnabled", "backfillEnabled"]) {
    assert.throws(() => validateDevBindings({ ...config, reconciliationReadEnabled: "true", [key]: "true" }), /does not authorize/);
  }
});

test("Spring discovery ceiling has exact actions, buckets and media prefixes", () => {
  const statements = boundaries.SpringRuntimeBoundary.document.Statement;
  const original = "arn:aws:s3:::hashi-dev-media-pipeline-originalimagebucket-*";
  const delivery = `arn:aws:s3:::${config.deliveryBucket}`;
  const expected = [
    ["InspectOriginalBucketVersioning", "s3:GetBucketVersioning", original, undefined],
    ["InspectDeliveryBucketVersioning", "s3:GetBucketVersioning", delivery, undefined],
    ["ListOriginalVersions", "s3:ListBucketVersions", original, { StringLike: { "s3:prefix": "media/originals/*" } }],
    ["ListRenditionVersions", "s3:ListBucketVersions", delivery, { StringLike: { "s3:prefix": "media/renditions/*" } }],
  ];
  for (const [sid, action, resource, condition] of expected) {
    const actual = statements.find((s) => s.Sid === sid);
    assert.deepEqual(actual, { Sid: sid, Effect: "Allow", Action: [action], Resource: resource, ...(condition ? { Condition: condition } : {}) });
  }
  assert.equal(statements.filter((s) => s.Action.some((a) => ["s3:GetBucketVersioning", "s3:ListBucketVersions"].includes(a))).length, 4);
  for (const modify of [
    (s) => { s.Resource = "*"; },
    (s) => { s.Condition.StringLike["s3:prefix"] = "*"; },
    (s) => { s.Action.push("s3:DeleteObjectVersion"); },
  ]) {
    const changed = structuredClone(boundaries.SpringRuntimeBoundary.document);
    modify(changed.Statement.find((s) => s.Sid === "ListRenditionVersions"));
    assert.throws(() => validateRuntimeBoundaryDocument(changed, boundaries.SpringRuntimeBoundary.document), /reviewed DEV ceiling/);
  }
});

for (const [key, value] of Object.entries({ deliveryBucket: "hashi-prod-delivery-example",
  springRoleName: "hashi-prod-ec2-role", artifactBucket: "hashi-prod-artifacts-example",
  executionRoleArn: `arn:aws:iam::${accountId}:role/other`, deployRoleArn: `arn:aws:iam::${accountId}:role/other`,
  accountId: "not-an-account", region: "not-a-region", backfillEnabled: "true", cleanupEnabled: "true" })) {
  test(`rejects unreviewed DEV binding: ${key}`, () => {
    assert.throws(() => validateDevBindings({ ...config, [key]: value }));
  });
}

test("rejects missing or wrong runtime boundaries", () => {
  assert.throws(() => verifyDevRuntimeBoundaries(config, probe({ wrongBoundary: true })), /permissions boundary/);
  assert.throws(() => verifyDevRuntimeBoundaries(config, probe({ missingSpring: true })), /spring lookup/);
  assert.throws(() => verifyDevRuntimeBoundaries(config, () => { throw new Error("boundary missing"); }), /boundary missing/);
});

test("worker AccessDenied is not mistaken for first deployment", () => {
  assert.throws(() => verifyDevRuntimeBoundaries(config, probe({ missingWorker: "AccessDenied" })), /worker lookup/);
});

test("rejects modified boundary statements, prod resources and role assumption", () => {
  for (const modify of [
    (doc) => { doc.Statement[0].Resource = "*"; },
    (doc) => { doc.Statement[0].Action.push("sts:AssumeRole"); },
    (doc) => { doc.Statement[0].Resource = "arn:aws:s3:::hashi-prod-example/*"; },
    (doc) => { doc.Statement.push({ Effect: "Allow", Action: "*", Resource: "*" }); },
  ]) assert.throws(() => verifyDevRuntimeBoundaries(config, probe({ modify })), /reviewed DEV ceiling/);
});

test("compares policy content despite object-key order or URL encoding", () => {
  const doc = boundaries.WorkerRuntimeBoundary.document;
  validateRuntimeBoundaryDocument({ Statement: doc.Statement, Version: doc.Version }, doc);
  validateRuntimeBoundaryDocument(encodeURIComponent(JSON.stringify(doc)), doc);
  assert.throws(() => validateRuntimeBoundaryDocument("invalid", doc), /valid JSON/);
});

test("worker ceiling has no deletion, role assumption or wildcard authority", () => {
  const statements = boundaries.WorkerRuntimeBoundary.document.Statement;
  assert.deepEqual(statements.flatMap((s) => s.Action).sort(), ["logs:CreateLogStream", "logs:PutLogEvents",
    "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "s3:GetObjectVersion",
    "s3:GetObject", "s3:PutObject", "sqs:SendMessage"].sort());
  for (const s of statements) assert.match(s.Resource, /hashi-dev-/);
});

test("Spring ceiling preserves existing DEV delivery actions but not new object deletion", () => {
  const statements = boundaries.SpringRuntimeBoundary.document.Statement;
  const legacy = statements.find((s) => s.Sid === "PreserveExistingDevDeliveryAccess");
  assert.deepEqual(legacy.Action, ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]);
  assert.equal(legacy.Resource, `arn:aws:s3:::${config.deliveryBucket}/*`);
  assert.equal(statements.filter((s) => s.Action.some((a) => a.startsWith("s3:Delete"))).length, 1);
  assert.ok(statements.every((s) => !s.Action.some((a) => /^(iam|sts):|\*/.test(a))));
  for (const s of statements) assert.match(s.Resource, /hashi-dev-/);
});
