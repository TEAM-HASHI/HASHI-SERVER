import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import { validateTemplateGuardrails } from "./check-template-guardrails.mjs";

const template = fs.readFileSync("infra/media/template.yaml", "utf8");
const boundary = fs.readFileSync(
  "infra/media/bootstrap/cloudformation-execution-boundary.yaml",
  "utf8",
);
const prodParameters = fs.readFileSync("infra/media/parameters/prod.example.json", "utf8");

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
