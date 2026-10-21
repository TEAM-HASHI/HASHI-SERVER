import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { renderRuntimeBoundaries } from "./check-dev-runtime-boundaries.mjs";

const read = (file) => fs.readFileSync(file, "utf8").replaceAll("\r\n", "\n");
const roles = JSON.parse(read("infra/media/bootstrap/dev-deployment-roles.json"));
const runtime = JSON.parse(read("infra/media/bootstrap/dev-runtime-boundaries.json"));
const execution = roles.Resources.ExecutionRole.Properties;
const deploy = roles.Resources.DeployRole.Properties;
const executionStatements = execution.Policies[0].PolicyDocument.Statement;
const deployStatements = deploy.Policies[0].PolicyDocument.Statement;
const find = (list, sid) => list.find((s) => s.Sid === sid);
const actions = (statement) => [statement.Action].flat();

// Only the reviewed single-line !Sub name declarations are supported here.
function devResourceName(template, logicalId, property) {
  const header = `  ${logicalId}:\n`;
  const start = template.indexOf(header);
  assert.ok(start >= 0, `Missing ${logicalId}`);
  const remainder = template.slice(start + header.length);
  const end = remainder.search(/^(?:  [A-Za-z0-9]+:|\S)/m);
  const block = end < 0 ? remainder : remainder.slice(0, end);
  const names = [...block.matchAll(new RegExp(`^      ${property}: !Sub "([^"\\n]+)"$`, "gm"))];
  assert.equal(names.length, 1, `Expected one !Sub ${logicalId}.${property}`);
  const name = names[0][1].replaceAll("${EnvironmentName}", "dev");
  assert.ok(!name.includes("${"), `Unsupported substitution in ${logicalId}.${property}`);
  return name;
}

function assertDevResourceBoundaryNames(template) {
  const account = "123456789012";
  const region = "ap-northeast-2";
  const boundaries = renderRuntimeBoundaries(account, region, "hashi-dev-delivery-example");
  const resource = (boundary, sid) => find(boundaries[boundary].document.Statement, sid).Resource;
  const requestArn = `arn:aws:sqs:${region}:${account}:${devResourceName(template, "TransformRequestQueue", "QueueName")}`;
  const resultArn = `arn:aws:sqs:${region}:${account}:${devResourceName(template, "TransformResultQueue", "QueueName")}`;
  const logGroup = devResourceName(template, "ImageTransformFunctionLogGroup", "LogGroupName");
  assert.equal(resource("WorkerRuntimeBoundary", "ConsumeRequests"), requestArn, "Worker request boundary mismatch");
  assert.equal(resource("SpringRuntimeBoundary", "PublishRequests"), requestArn, "Spring request boundary mismatch");
  assert.equal(resource("WorkerRuntimeBoundary", "PublishResults"), resultArn, "Worker result boundary mismatch");
  assert.equal(resource("SpringRuntimeBoundary", "ConsumeResults"), resultArn, "Spring result boundary mismatch");
  assert.equal(resource("WorkerRuntimeBoundary", "WriteWorkerLogs"),
    `arn:aws:logs:${region}:${account}:log-group:${logGroup}:*`, "Worker log boundary mismatch");
  assert.equal(logGroup, `/aws/lambda/${devResourceName(template, "ImageTransformFunction", "FunctionName")}`,
    "Function and log group mismatch");
}

test("DEV queue and log names match both runtime boundaries", () => {
  assertDevResourceBoundaryNames(read("infra/media/template.yaml"));
});

for (const declaration of [
  'QueueName: !Sub "hashi-${EnvironmentName}-media-transform-request"',
  'QueueName: !Sub "hashi-${EnvironmentName}-media-transform-result"',
  'LogGroupName: !Sub "/aws/lambda/hashi-${EnvironmentName}-image-transform"',
  'FunctionName: !Sub "hashi-${EnvironmentName}-image-transform"',
]) {
  test(`rejects template name drift: ${declaration}`, () => {
    const template = read("infra/media/template.yaml");
    assert.ok(template.includes(declaration));
    const changed = template.replace(declaration, `${declaration.slice(0, -1)}-renamed"`);
    assert.throws(() => assertDevResourceBoundaryNames(changed), /mismatch/);
  });
}

test("bootstrap reuses one same-account OIDC provider and exact develop subject", () => {
  assert.ok(Object.values(roles.Resources).every((r) => r.Type !== "AWS::IAM::OIDCProvider"));
  const trust = deploy.AssumeRolePolicyDocument.Statement;
  assert.equal(trust.length, 1);
  assert.deepEqual(trust[0].Principal.Federated, {
    "Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:oidc-provider/${GitHubOidcProviderHost}",
  });
  assert.deepEqual(trust[0].Condition.StringEquals, {
    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
    "token.actions.githubusercontent.com:sub": "repo:TEAM-HASHI/HASHI-SERVER:ref:refs/heads/develop",
  });
  assert.deepEqual(roles.Parameters.GitHubOidcProviderHost.AllowedValues, ["token.actions.githubusercontent.com"]);
});

test("role creation and boundary writes require the exact immutable worker ceiling", () => {
  for (const statement of executionStatements.filter((s) => actions(s).some((a) =>
    ["iam:CreateRole", "iam:PutRolePermissionsBoundary"].includes(a)))) {
    assert.deepEqual(statement.Condition, { ArnEquals: { "iam:PermissionsBoundary": {
      "Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:policy/hashi-dev-media-worker-runtime-boundary",
    } } });
    assert.match(statement.Resource["Fn::Sub"], /:role\/hashi-dev-media-image-transform-lambda$/);
  }
  assert.ok(executionStatements.some((s) => actions(s).includes("iam:CreateRole")));
  assert.ok(executionStatements.every((s) => !actions(s).includes("iam:DeleteRolePermissionsBoundary")));
  const managed = find(executionStatements, "ManageGeneratedMediaPolicies");
  assert.equal(managed.Resource["Fn::Sub"], "arn:${AWS::Partition}:iam::${AWS::AccountId}:policy/hashi-dev-media-pipeline-*");
  assert.ok(Object.values(runtime.Resources).every((r) => !r.Properties.ManagedPolicyName.startsWith("hashi-dev-media-pipeline-")));
});

test("bootstrap cannot replace the existing Spring boundary", () => {
  for (const s of executionStatements.filter((s) => JSON.stringify(s.Resource).includes("SpringRoleName"))) {
    assert.ok(actions(s).every((a) => ["iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:GetRole", "iam:ListAttachedRolePolicies"].includes(a)));
  }
  assert.deepEqual(roles.Parameters.SpringRoleName.AllowedValues, ["hashi-dev-ec2-role"]);
  for (const template of [roles, runtime]) {
    const pattern = new RegExp(template.Parameters.DeliveryBucket.AllowedPattern);
    assert.ok(pattern.test("hashi-dev-delivery-example"));
    assert.ok(!pattern.test("hashi-prod-delivery-example"));
  }
});

test("PassRole execution boundary contract remains separate and exact", () => {
  assert.equal(execution.PermissionsBoundary["Fn::Sub"], "arn:${AWS::Partition}:iam::${AWS::AccountId}:policy/hashi-dev-media-cloudformation-execution-boundary");
  const pass = executionStatements.filter((s) => actions(s).includes("iam:PassRole"));
  assert.equal(pass.length, 1);
  assert.deepEqual(pass[0].Condition, { StringEquals: { "iam:PassedToService": "lambda.amazonaws.com" } });
});

test("CloudFormation tags only its exact DEV stack; mapping tagging requires DEV metadata", () => {
  const stack = find(deployStatements, "TagExactDevStack");
  assert.deepEqual(stack.Action, ["cloudformation:TagResource", "cloudformation:UntagResource"]);
  assert.match(stack.Resource["Fn::Sub"], /:stack\/hashi-dev-media-pipeline\/\*$/);
  assert.deepEqual(find(executionStatements, "TagDevEventSource").Condition.StringEquals,
    { "aws:RequestTag/Project": "HASHI", "aws:RequestTag/Environment": "dev", "aws:RequestTag/Component": "media" });
  assert.deepEqual(find(executionStatements, "UntagDevEventSource").Condition.StringEquals,
    { "aws:ResourceTag/Project": "HASHI", "aws:ResourceTag/Environment": "dev", "aws:ResourceTag/Component": "media" });
  assert.deepEqual(find(executionStatements, "TagExistingDevEventSource").Condition.StringEquals,
    { "aws:ResourceTag/Project": "HASHI", "aws:ResourceTag/Environment": "dev", "aws:ResourceTag/Component": "media" });
  assert.deepEqual(find(executionStatements, "TagExistingDevEventSource").Condition.StringEqualsIfExists,
    { "aws:RequestTag/Project": "HASHI", "aws:RequestTag/Environment": "dev", "aws:RequestTag/Component": "media" });
});

test("worker runtime ceiling is DEV-only and preflight runs before sam deploy", () => {
  const template = read("infra/media/template.yaml");
  assert.ok(template.includes("  IsDev: !Equals\n    - !Ref EnvironmentName\n    - dev\n"));
  assert.ok(template.includes('      PermissionsBoundary: !If\n        - IsDev\n        - !Sub "arn:${AWS::Partition}:iam::${AWS::AccountId}:policy/hashi-dev-media-worker-runtime-boundary"\n        - !Ref AWS::NoValue\n'));
  const workflow = read(".github/workflows/deploy-image-pipeline.yml");
  const preflight = workflow.indexOf("          node infra/media/scripts/check-dev-runtime-boundaries.mjs\n");
  assert.ok(preflight > workflow.indexOf("      - name: Verify AWS identity"));
  assert.ok(preflight < workflow.indexOf('          sam deploy "${deploy_args[@]}"'));
});
