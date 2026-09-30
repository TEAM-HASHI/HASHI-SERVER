import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import { validateTemplateGuardrails } from "./check-template-guardrails.mjs";

const template = fs.readFileSync("infra/media/template.yaml", "utf8").replaceAll("\r\n", "\n");
const boundary = fs.readFileSync(
  "infra/media/bootstrap/cloudformation-execution-boundary.yaml",
  "utf8",
);
const prodParameters = fs.readFileSync("infra/media/parameters/prod.example.json", "utf8");

function changeCleanupPolicy(change) {
  const start = template.indexOf("  SpringApplicationCleanupPolicy:");
  const end = template.indexOf("  TransformRequestAgeAlarm:", start);
  assert.ok(start >= 0 && end > start);
  return template.slice(0, start) + change(template.slice(start, end)) + template.slice(end);
}

test("accepts the reviewed production alarm and PassRole guardrails", () => {
  assert.doesNotThrow(() => validateTemplateGuardrails(template, boundary, prodParameters));
});

test("rejects a template that does not require the production alarm topic", () => {
  const changed = template.replace("  ProductionAlarmTopicRequired:", "  OptionalProductionAlarmTopic:");

  assert.throws(
    () => validateTemplateGuardrails(changed, boundary, prodParameters),
    /reject an empty production alarm topic/,
  );
});

test("rejects a boundary that allows PassRole to every role", () => {
  const changed = boundary.replace(
    '            Resource: !Sub "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/hashi-${EnvironmentName}-media-image-transform-lambda"',
    '            Resource: "*"',
  );

  assert.throws(
    () => validateTemplateGuardrails(template, changed, prodParameters),
    /exact Lambda worker role/,
  );
});

test("rejects an empty production alarm topic example", () => {
  const changed = prodParameters.replace(
    '"ParameterKey": "AlarmNotificationTopicArn", "ParameterValue": "replace-with-existing-prod-alarm-topic-arn"',
    '"ParameterKey": "AlarmNotificationTopicArn", "ParameterValue": ""',
  );

  assert.throws(
    () => validateTemplateGuardrails(template, boundary, changed),
    /explicit alarm topic replacement/,
  );
});

test("rejects cleanup access enabled by default", () => {
  const changed = template.replace(
    '  CleanupAccessEnabled:\n    Type: String\n    Default: "false"',
    '  CleanupAccessEnabled:\n    Type: String\n    Default: "true"',
  );
  assert.notEqual(changed, template);
  assert.throws(() => validateTemplateGuardrails(changed, boundary, prodParameters), /Cleanup guardrail/);
});

test("rejects a cleanup condition unrelated to its permission flag", () => {
  const changed = template.replace(
    "  IsCleanupAccessEnabled: !Equals\n    - !Ref CleanupAccessEnabled",
    "  IsCleanupAccessEnabled: !Equals\n    - !Ref BackfillAccessEnabled",
  );
  assert.notEqual(changed, template);
  assert.throws(() => validateTemplateGuardrails(changed, boundary, prodParameters), /Cleanup guardrail/);
});

for (const [name, change] of [
  ["unconditional cleanup access", (policy) => policy.replace("    Condition: IsCleanupAccessEnabled\n", "")],
  ["worker cleanup access", (policy) => policy.replace(
    "!Ref SpringApplicationRoleName", "!Ref ImageTransformFunctionRole")],
  ["retained cleanup access", (policy) => policy.replace(
    "    Properties:", "    DeletionPolicy: Retain\n    Properties:")],
  ["unscoped listing", (policy) => policy.replace('s3:prefix: "media/originals/*"', 's3:prefix: "*"')],
  ["legacy listing", (policy) => policy.replace('s3:prefix: "media/renditions/*"', 's3:prefix: "legacy/*"')],
  ["bucket-wide deletion", (policy) => policy.replace("/media/renditions/*", "/*")],
  ["broad deletion action", (policy) => policy.replace("- s3:DeleteObjectVersion", "- s3:*")],
  ["missing version deletion", (policy) => policy.replace("              - s3:DeleteObjectVersion\n", "")],
  ["missing versioning inspection", (policy) => policy.replace("              - s3:GetBucketVersioning\n", "")],
  ["versioning mutation", (policy) => policy.replace("s3:GetBucketVersioning", "s3:PutBucketVersioning")],
  ["lock bypass", (policy) => policy.replace(
    "- s3:DeleteObject\n", "- s3:DeleteObject\n              - s3:BypassGovernanceRetention\n")],
  ["extra statement", (policy) => policy + "          - Sid: Unexpected\n            Effect: Allow\n"
    + "            Action: s3:*\n            Resource: '*'\n"],
]) {
  test(`rejects ${name}`, () => {
    const changed = changeCleanupPolicy(change);
    assert.notEqual(changed, template);
    assert.throws(() => validateTemplateGuardrails(changed, boundary, prodParameters), /Cleanup guardrail/);
  });
}

for (const name of ["CleanupAccessEnabled", "IsCleanupAccessEnabled", "SpringApplicationCleanupPolicy"]) {
  test(`rejects a duplicate ${name} block`, () => {
    const changed = template.replace(`  ${name}:`, `  ${name}: {}\n  ${name}:`);
    assert.throws(() => validateTemplateGuardrails(changed, boundary, prodParameters), /requires one/);
  });
}

for (const name of ["Parameters", "Conditions", "Resources"]) {
  test(`rejects a duplicate ${name} section`, () => {
    const changed = template.replace(`${name}:`, `${name}:\n${name}:`);
    assert.throws(() => validateTemplateGuardrails(changed, boundary, prodParameters), /requires one/);
  });
}

for (const [name, change] of [
  ["enabled", (parameters) => parameters.map((parameter) => parameter.ParameterKey === "CleanupAccessEnabled"
    ? { ...parameter, ParameterValue: "true" } : parameter)],
  ["missing", (parameters) => parameters.filter((parameter) => parameter.ParameterKey !== "CleanupAccessEnabled")],
  ["duplicate", (parameters) => [...parameters, { ParameterKey: "CleanupAccessEnabled", ParameterValue: "false" }]],
]) {
  test(`rejects ${name} production cleanup access example`, () => {
    const changed = JSON.stringify(change(JSON.parse(prodParameters)));
    assert.throws(() => validateTemplateGuardrails(template, boundary, changed), /keep cleanup access disabled/);
  });
}
