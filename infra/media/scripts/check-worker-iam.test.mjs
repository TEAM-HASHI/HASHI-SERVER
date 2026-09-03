import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

import { validateWorkerIamTemplate } from "./check-worker-iam.mjs";

const template = fs.readFileSync("infra/media/template.yaml", "utf8");

test("accepts the reviewed explicit worker role", () => {
  assert.doesNotThrow(() => validateWorkerIamTemplate(template));
});

test("rejects a SAM-generated worker role", () => {
  const changed = template.replace(
    "      Role: !GetAtt ImageTransformFunctionRole.Arn\n",
    "",
  );

  assert.throws(
    () => validateWorkerIamTemplate(changed),
    /must use the explicit runtime role/,
  );
});

test("rejects a worker role without the deterministic reviewed name", () => {
  const changed = template.replace(
    '      RoleName: !Sub "hashi-${EnvironmentName}-media-image-transform-lambda"\n',
    "",
  );

  assert.throws(() => validateWorkerIamTemplate(changed), /deterministic reviewed role name/);
});

test("rejects a wildcard request queue resource", () => {
  const changed = template.replace(
    "                Resource: !GetAtt TransformRequestQueue.Arn",
    '                Resource: "*"',
  );

  assert.throws(() => validateWorkerIamTemplate(changed), /wildcard resources/);
});

test("rejects an extra SQS action", () => {
  const changed = template.replace(
    "                  - sqs:ReceiveMessage\n                Resource: !GetAtt TransformRequestQueue.Arn",
    "                  - sqs:ReceiveMessage\n                  - sqs:SendMessage\n                Resource: !GetAtt TransformRequestQueue.Arn",
  );

  assert.throws(() => validateWorkerIamTemplate(changed), /least-privilege set/);
});

test("rejects an AWS managed policy attachment", () => {
  const changed = template.replace(
    "      Policies:\n",
    "      ManagedPolicyArns:\n        - arn:aws:iam::aws:policy/service-role/AWSLambdaSQSQueueExecutionRole\n      Policies:\n",
  );

  assert.throws(() => validateWorkerIamTemplate(changed), /broad AWS managed policies/);
});

test("rejects an additional trusted service", () => {
  const changed = template.replace(
    "                - lambda.amazonaws.com\n",
    "                - lambda.amazonaws.com\n                - ec2.amazonaws.com\n",
  );

  assert.throws(() => validateWorkerIamTemplate(changed), /trust only the Lambda service/);
});

test("rejects an additional runtime statement", () => {
  const changed = template.replace(
    "              - Sid: PublishTransformResult\n",
    "              - Sid: InspectAnotherQueue\n                Effect: Allow\n                Action:\n                  - sqs:GetQueueAttributes\n                Resource: !GetAtt TransformResultQueue.Arn\n              - Sid: PublishTransformResult\n",
  );

  assert.throws(() => validateWorkerIamTemplate(changed), /only the reviewed IAM statements/);
});
