import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const EXPECTED_STATEMENTS = Object.freeze({
  WriteFunctionLogs: {
    actions: ["logs:CreateLogStream", "logs:PutLogEvents"],
    resource: "Resource: !GetAtt ImageTransformFunctionLogGroup.Arn",
  },
  ConsumeTransformRequests: {
    actions: ["sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ReceiveMessage"],
    resource: "Resource: !GetAtt TransformRequestQueue.Arn",
  },
  ReadFixedOriginalVersion: {
    actions: ["s3:GetObjectVersion"],
    resource: 'Resource: !Sub "${OriginalImageBucket.Arn}/media/originals/*"',
  },
  CreateAndVerifyRenditions: {
    actions: ["s3:GetObject", "s3:PutObject"],
    resource:
      'Resource: !Sub "arn:${AWS::Partition}:s3:::${DeliveryBucketName}/media/renditions/*"',
  },
  PublishTransformResult: {
    actions: ["sqs:SendMessage"],
    resource: "Resource: !GetAtt TransformResultQueue.Arn",
  },
});

function fail(message) {
  throw new Error(message);
}

function resourceBlock(template, logicalId) {
  const header = `  ${logicalId}:`;
  const start = template.indexOf(`${header}\n`);
  if (start < 0) {
    fail(`Missing ${logicalId} resource.`);
  }

  const remainder = template.slice(start + header.length + 1);
  const nextResource = remainder.search(/^  [A-Za-z0-9]+:\s*$/m);
  return nextResource < 0
    ? template.slice(start)
    : template.slice(start, start + header.length + 1 + nextResource);
}

function statementBlock(roleBlock, sid) {
  const marker = `              - Sid: ${sid}`;
  const start = roleBlock.indexOf(`${marker}\n`);
  if (start < 0) {
    fail(`Missing ${sid} worker IAM statement.`);
  }

  const remainder = roleBlock.slice(start + marker.length + 1);
  const nextStatement = remainder.search(/^              - Sid: /m);
  return nextStatement < 0
    ? roleBlock.slice(start)
    : roleBlock.slice(start, start + marker.length + 1 + nextStatement);
}

function actionSet(statement, sid) {
  const actionStart = statement.indexOf("                Action:\n");
  const resourceStart = statement.indexOf("                Resource:");
  if (actionStart < 0 || resourceStart < actionStart) {
    fail(`${sid} must contain an Action list followed by one exact Resource.`);
  }

  return new Set(
    statement
      .slice(actionStart, resourceStart)
      .split("\n")
      .map((line) => line.match(/^                  - ([A-Za-z0-9:*]+)$/)?.[1])
      .filter(Boolean),
  );
}

function assertExactActions(actual, expected, sid) {
  if (actual.size !== expected.length || expected.some((action) => !actual.has(action))) {
    fail(`${sid} worker IAM actions must match the reviewed least-privilege set.`);
  }
}

export function validateWorkerIamTemplate(rawTemplate) {
  const template = rawTemplate.replaceAll("\r\n", "\n");
  const functionBlock = resourceBlock(template, "ImageTransformFunction");
  const roleBlock = resourceBlock(template, "ImageTransformFunctionRole");

  if (!roleBlock.includes("    Type: AWS::IAM::Role\n")) {
    fail("ImageTransformFunctionRole must be an explicit AWS::IAM::Role.");
  }
  if (!functionBlock.includes("      Role: !GetAtt ImageTransformFunctionRole.Arn\n")) {
    fail("ImageTransformFunction must use the explicit runtime role.");
  }
  if (/^      Policies:/m.test(functionBlock)) {
    fail("ImageTransformFunction cannot use SAM-generated function policies.");
  }
  if (/AWSLambdaSQSQueueExecutionRole|ManagedPolicyArns:/.test(roleBlock)) {
    fail("Worker IAM cannot attach broad AWS managed policies.");
  }
  if (/Resource:\s*["']?\*["']?\s*$/m.test(roleBlock)) {
    fail("Worker IAM cannot use wildcard resources.");
  }
  const trustedServices = [
    ...roleBlock
      .slice(0, roleBlock.indexOf("      Policies:\n"))
      .matchAll(/^\s+- ([A-Za-z0-9.-]+\.amazonaws\.com)$/gm),
  ].map((match) => match[1]);
  if (trustedServices.length !== 1 || trustedServices[0] !== "lambda.amazonaws.com") {
    fail("Worker runtime role must trust only the Lambda service.");
  }

  const actualSids = [...roleBlock.matchAll(/^              - Sid: ([A-Za-z0-9]+)$/gm)].map(
    (match) => match[1],
  );
  const expectedSids = Object.keys(EXPECTED_STATEMENTS);
  if (
    actualSids.length !== expectedSids.length ||
    expectedSids.some((sid) => !actualSids.includes(sid))
  ) {
    fail("Worker runtime role must contain only the reviewed IAM statements.");
  }

  for (const [sid, expected] of Object.entries(EXPECTED_STATEMENTS)) {
    const statement = statementBlock(roleBlock, sid);
    assertExactActions(actionSet(statement, sid), expected.actions, sid);
    const resourceLines = statement.match(/^                Resource:.*$/gm) ?? [];
    if (resourceLines.length !== 1 || resourceLines[0].trim() !== expected.resource) {
      fail(`${sid} must use its exact reviewed resource boundary.`);
    }
  }
}

const isCli =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isCli) {
  const templatePath = process.argv[2] ?? "infra/media/template.yaml";
  validateWorkerIamTemplate(fs.readFileSync(templatePath, "utf8"));
  console.log("Worker IAM boundary is explicit and least-privilege.");
}
