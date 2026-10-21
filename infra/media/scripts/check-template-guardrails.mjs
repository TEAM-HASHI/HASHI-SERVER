import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { validateWorkerIamTemplate } from "./check-worker-iam.mjs";

function fail(message) {
  throw new Error(message);
}

// A fail-closed check for these reviewed blocks, not a general YAML parser.
// The Java infrastructure test separately checks CloudFormation YAML nodes.
function requireReviewedBlock(template, section, name, expected) {
  const lines = template.split("\n");
  const roots = lines.flatMap((line, index) => (line === `${section}:` ? [index] : []));
  if (roots.length !== 1) {
    fail(`Cleanup guardrail requires one ${section} section.`);
  }
  const remaining = lines.slice(roots[0] + 1);
  const nextRoot = remaining.findIndex((line) => /^[^\s#]/.test(line));
  const content = nextRoot < 0 ? remaining : remaining.slice(0, nextRoot);
  const entries = content.flatMap((line, index) => (line.startsWith(`  ${name}:`) ? [index] : []));
  if (entries.length !== 1) {
    fail(`Cleanup guardrail requires one ${name} block.`);
  }
  const body = content.slice(entries[0]);
  const nextEntry = body.findIndex((line, index) => index > 0 && /^  \S/.test(line));
  const actual = (nextEntry < 0 ? body : body.slice(0, nextEntry)).join("\n").trimEnd();
  if (actual !== expected.trimEnd()) {
    fail(`Cleanup guardrail changed outside its reviewed boundary: ${name}.`);
  }
}

function validateCleanupGuardrails(template) {
  requireReviewedBlock(template, "Parameters", "CleanupAccessEnabled", `  CleanupAccessEnabled:
    Type: String
    Default: "false"
    AllowedValues:
      - "true"
      - "false"
    Description: Version-list and delete permissions for approved media cleanup only
`);
  requireReviewedBlock(template, "Conditions", "IsCleanupAccessEnabled", `  IsCleanupAccessEnabled: !Equals
    - !Ref CleanupAccessEnabled
    - "true"
`);
  requireReviewedBlock(template, "Resources", "SpringApplicationCleanupPolicy", `  SpringApplicationCleanupPolicy:
    Type: AWS::IAM::ManagedPolicy
    Condition: IsCleanupAccessEnabled
    Properties:
      Description: Scoped version discovery and deletion for HASHI media cleanup
      Roles:
        - !Ref SpringApplicationRoleName
      PolicyDocument:
        Version: "2012-10-17"
        Statement:
          - Sid: InspectPrivateOriginalBucketVersioningForCleanup
            Effect: Allow
            Action:
              - s3:GetBucketVersioning
            Resource: !GetAtt OriginalImageBucket.Arn
          - Sid: InspectDeliveryBucketVersioningForCleanup
            Effect: Allow
            Action:
              - s3:GetBucketVersioning
            Resource: !Sub "arn:\${AWS::Partition}:s3:::\${DeliveryBucketName}"
          - Sid: ListPrivateOriginalVersionsForCleanup
            Effect: Allow
            Action:
              - s3:ListBucketVersions
            Resource: !GetAtt OriginalImageBucket.Arn
            Condition:
              StringLike:
                s3:prefix: "media/originals/*"
          - Sid: ListRenditionVersionsForCleanup
            Effect: Allow
            Action:
              - s3:ListBucketVersions
            Resource: !Sub "arn:\${AWS::Partition}:s3:::\${DeliveryBucketName}"
            Condition:
              StringLike:
                s3:prefix: "media/renditions/*"
          - Sid: DeletePrivateOriginalVersionsForCleanup
            Effect: Allow
            Action:
              - s3:DeleteObject
              - s3:DeleteObjectVersion
            Resource: !Sub "\${OriginalImageBucket.Arn}/media/originals/*"
          - Sid: DeleteRenditionVersionsForCleanup
            Effect: Allow
            Action:
              - s3:DeleteObject
              - s3:DeleteObjectVersion
            Resource: !Sub "arn:\${AWS::Partition}:s3:::\${DeliveryBucketName}/media/renditions/*"
`);
}

// Only the explicit block-style resource declarations used by this template are
// supported. Reject unrecognized syntax/routes instead of treating them as safe.
// This is not a YAML parser or an evaluator of existing AWS/account policies.
function validatePolicyResourceInventory(template) {
  const rootNames = new Set(["AWSTemplateFormatVersion", "Transform", "Description", "Parameters",
    "Rules", "Conditions", "Resources", "Outputs"]);
  const lines = template.split("\n");
  const roots = new Set();
  for (const line of lines.filter((line) => /^\S/.test(line) && !line.startsWith("#"))) {
    const name = line.match(/^([A-Za-z][A-Za-z0-9]*):(?: |$)/)?.[1];
    if (!rootNames.has(name) || roots.has(name)) fail("Unsupported or duplicate template root.");
    roots.add(name);
  }
  if (lines.some((line) => /\t|(?:^|\s)[&*][A-Za-z0-9_-]+|(?:^|\s)<<:/.test(line))) {
    fail("YAML aliases, anchors, merge keys and tabs require separate review.");
  }
  const start = lines.indexOf("Resources:");
  const nextRoot = lines.slice(start + 1).findIndex((line) => /^\S/.test(line) && !line.startsWith("#"));
  const content = lines.slice(start + 1, nextRoot < 0 ? undefined : start + 1 + nextRoot);
  if (!lines.includes("Transform: AWS::Serverless-2016-10-31") || content.some((line) => {
    if (!line.trim() || line.trimStart().startsWith("#")) return false;
    const indent = line.length - line.trimStart().length;
    return indent < 2 || indent % 2 !== 0;
  })) fail("Unsupported transform or resource indentation.");
  const headers = content.flatMap((line, index) => /^  \S/.test(line) ? [index] : []);
  const knownIam = new Map([
    ["ImageTransformFunctionRole", "AWS::IAM::Role"],
    ["SpringApplicationMediaPolicy", "AWS::IAM::ManagedPolicy"],
    ["SpringApplicationBackfillPolicy", "AWS::IAM::ManagedPolicy"],
    ["SpringApplicationReconciliationReadPolicy", "AWS::IAM::ManagedPolicy"],
    ["SpringApplicationCleanupPolicy", "AWS::IAM::ManagedPolicy"],
  ]);
  const otherTypes = new Set(["AWS::S3::Bucket", "AWS::SQS::Queue", "AWS::Logs::LogGroup", "AWS::CloudWatch::Alarm"]);
  const resourceIds = new Set();
  for (const [i, index] of headers.entries()) {
    const id = content[index].match(/^  ([A-Za-z][A-Za-z0-9]*):$/)?.[1];
    if (!id || resourceIds.has(id)) fail("Unsupported or duplicate resource declaration.");
    resourceIds.add(id);
    const block = content.slice(index + 1, headers[i + 1]);
    const attributes = new Set();
    for (const line of block.filter((line) => /^    \S/.test(line))) {
      const key = line.match(/^    ([A-Za-z]+):(?: |$)/)?.[1];
      if (!["Type", "Condition", "Properties", "DeletionPolicy", "UpdateReplacePolicy", "DependsOn"].includes(key)
          || attributes.has(key)) fail("Unsupported or duplicate resource attribute.");
      attributes.add(key);
    }
    const types = block.flatMap((line) => line.match(/^    Type: (AWS::[A-Za-z0-9]+::[A-Za-z0-9]+)$/)?.slice(1) ?? []);
    if (types.length !== 1) fail("Resource Type must use the reviewed explicit block syntax.");
    const type = types[0];
    if (knownIam.has(id)) {
      if (type !== knownIam.get(id)) fail("Reviewed IAM resource type changed.");
    } else if (!otherTypes.has(type)
        && !(id === "OriginalImageBucketPolicy" && type === "AWS::S3::BucketPolicy")
        && !(id === "ImageTransformFunction" && type === "AWS::Serverless::Function")) {
      fail("Unreviewed IAM, resource-policy or generated policy route.");
    }
    if (id === "ImageTransformFunctionRole") {
      const propertyKeys = block.filter((line) => /^      \S/.test(line)).map((line) =>
        line.match(/^      ([A-Za-z]+):(?: |$)/)?.[1]);
      if (propertyKeys.length !== 5 || new Set(propertyKeys).size !== 5
          || !propertyKeys.every((key) => ["RoleName", "PermissionsBoundary", "AssumeRolePolicyDocument", "Policies", "Tags"].includes(key))) {
        fail("Worker role properties must use the reviewed unique keys.");
      }
    }
  }
  for (const id of knownIam.keys()) if (!resourceIds.has(id)) fail("Missing reviewed IAM resource.");
  validateWorkerIamTemplate(template);
}

function validateExistingSpringPolicies(template) {
  requireReviewedBlock(template, "Parameters", "BackfillAccessEnabled", `  BackfillAccessEnabled:
    Type: String
    Default: "false"
    AllowedValues:
      - "true"
      - "false"
    Description: Temporary read and version-list permissions for an approved legacy backfill
`);
  requireReviewedBlock(template, "Conditions", "IsBackfillAccessEnabled", `  IsBackfillAccessEnabled: !Equals
    - !Ref BackfillAccessEnabled
    - "true"
`);
  requireReviewedBlock(template, "Resources", "SpringApplicationMediaPolicy", `  SpringApplicationMediaPolicy:
    Type: AWS::IAM::ManagedPolicy
    Properties:
      Description: Least-privilege media upload and queue access for the HASHI Spring application
      Roles:
        - !Ref SpringApplicationRoleName
      PolicyDocument:
        Version: "2012-10-17"
        Statement:
          - Sid: CreatePrivateOriginals
            Effect: Allow
            Action:
              - s3:PutObject
            Resource: !Sub "\${OriginalImageBucket.Arn}/media/originals/*"
          - Sid: InspectPrivateOriginals
            Effect: Allow
            Action:
              - s3:GetObject
              - s3:GetObjectVersion
            Resource: !Sub "\${OriginalImageBucket.Arn}/media/originals/*"
          - Sid: PublishTransformRequests
            Effect: Allow
            Action:
              - sqs:GetQueueAttributes
              - sqs:GetQueueUrl
              - sqs:SendMessage
            Resource: !GetAtt TransformRequestQueue.Arn
          - Sid: ConsumeTransformResults
            Effect: Allow
            Action:
              - sqs:ChangeMessageVisibility
              - sqs:DeleteMessage
              - sqs:GetQueueAttributes
              - sqs:GetQueueUrl
              - sqs:ReceiveMessage
            Resource: !GetAtt TransformResultQueue.Arn
`);
  requireReviewedBlock(template, "Resources", "SpringApplicationBackfillPolicy", `  SpringApplicationBackfillPolicy:
    Type: AWS::IAM::ManagedPolicy
    Condition: IsBackfillAccessEnabled
    Properties:
      Description: Temporary legacy read and private copy discovery for HASHI backfill
      Roles:
        - !Ref SpringApplicationRoleName
      PolicyDocument:
        Version: "2012-10-17"
        Statement:
          - Sid: ReadLegacySourceVersions
            Effect: Allow
            Action:
              - s3:GetObject
              - s3:GetObjectVersion
            Resource: !Sub "arn:\${AWS::Partition}:s3:::\${DeliveryBucketName}/*"
          - Sid: FindExistingPrivateCopies
            Effect: Allow
            Action:
              - s3:ListBucketVersions
            Resource: !GetAtt OriginalImageBucket.Arn
            Condition:
              StringLike:
                s3:prefix: "media/originals/*"
          - Sid: ReplacePrivateCopyTags
            Effect: Allow
            Action:
              - s3:PutObjectTagging
            Resource: !Sub "\${OriginalImageBucket.Arn}/media/originals/*"
`);
  requireReviewedBlock(template, "Resources", "OriginalImageBucketPolicy", `  OriginalImageBucketPolicy:
    Type: AWS::S3::BucketPolicy
    DeletionPolicy: Retain
    UpdateReplacePolicy: Retain
    Properties:
      Bucket: !Ref OriginalImageBucket
      PolicyDocument:
        Version: "2012-10-17"
        Statement:
          - Sid: DenyInsecureTransport
            Effect: Deny
            Principal: "*"
            Action: s3:*
            Resource:
              - !GetAtt OriginalImageBucket.Arn
              - !Sub "\${OriginalImageBucket.Arn}/*"
            Condition:
              Bool:
                aws:SecureTransport: "false"
`);
}

export function validateTemplateGuardrails(rawTemplate, rawBoundaryTemplate, rawProdParameters) {
  const template = rawTemplate.replaceAll("\r\n", "\n");
  const boundary = rawBoundaryTemplate.replaceAll("\r\n", "\n");
  validateCleanupGuardrails(template);
  validateReconciliationReadGuardrails(template);
  validatePolicyResourceInventory(template);
  validateExistingSpringPolicies(template);
  const requiredAlarmRule = `Rules:
  ProductionAlarmTopicRequired:
    RuleCondition: !Equals
      - !Ref EnvironmentName
      - prod
    Assertions:
      - Assert: !Not
          - !Equals
            - !Ref AlarmNotificationTopicArn
            - ""
        AssertDescription: AlarmNotificationTopicArn is required for production
`;

  if (!template.includes(requiredAlarmRule)) {
    fail("Main template must reject an empty production alarm topic.");
  }
  if (
    !template.includes(
      '    AllowedPattern: "^$|^arn:[a-z0-9-]+:sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_+=,.@/-]+$"\n',
    )
  ) {
    fail("Alarm topic parameter must accept only empty or SNS ARN-shaped values.");
  }

  const exactWorkerRole =
    'arn:${AWS::Partition}:iam::${AWS::AccountId}:role/hashi-${EnvironmentName}-media-image-transform-lambda';
  if (
    !boundary.includes("            NotAction: iam:PassRole\n") ||
    !boundary.includes("            Action: iam:PassRole\n") ||
    !boundary.includes(`            Resource: !Sub "${exactWorkerRole}"\n`) ||
    !boundary.includes("                iam:PassedToService: lambda.amazonaws.com\n")
  ) {
    fail("Bootstrap boundary must cap PassRole to the exact Lambda worker role.");
  }
  if (
    (boundary.match(/Action: iam:PassRole/g) ?? []).length !== 2 ||
    (boundary.match(/iam:PassedToService:/g) ?? []).length !== 1
  ) {
    fail("Bootstrap boundary contains PassRole fields outside the reviewed contract.");
  }

  let prodParameters;
  try {
    prodParameters = JSON.parse(rawProdParameters);
  } catch {
    fail("Production parameter example must be valid JSON.");
  }
  if (!Array.isArray(prodParameters)) {
    fail("Production parameter example must be an array.");
  }
  const cleanup = prodParameters.filter((parameter) => parameter?.ParameterKey === "CleanupAccessEnabled");
  if (cleanup.length !== 1 || cleanup[0].ParameterValue !== "false") {
    fail("Production parameter example must keep cleanup access disabled exactly once.");
  }
  const alarm = prodParameters.find(
    (parameter) => parameter.ParameterKey === "AlarmNotificationTopicArn",
  );
  if (!alarm || typeof alarm.ParameterValue !== "string" || alarm.ParameterValue.length === 0) {
    fail("Production parameter example must require an explicit alarm topic replacement.");
  }
}

export function validateReconciliationReadGuardrails(template) {
  requireReviewedBlock(template, "Parameters", "ReconciliationReadAccessEnabled", `  ReconciliationReadAccessEnabled:
    Type: String
    Default: "false"
    AllowedValues:
      - "true"
      - "false"
    Description: DEV-only version discovery without deletion for approved reconciliation dry runs
`);
  requireReviewedBlock(template, "Conditions", "IsDev", `  IsDev: !Equals
    - !Ref EnvironmentName
    - dev
`);
  requireReviewedBlock(template, "Conditions", "IsDevReconciliationReadAccessEnabled", `  IsDevReconciliationReadAccessEnabled: !And
    - !Condition IsDev
    - !Equals
      - !Ref ReconciliationReadAccessEnabled
      - "true"
`);
  requireReviewedBlock(template, "Resources", "SpringApplicationReconciliationReadPolicy", `  SpringApplicationReconciliationReadPolicy:
    Type: AWS::IAM::ManagedPolicy
    Condition: IsDevReconciliationReadAccessEnabled
    Properties:
      Description: DEV-only version discovery for HASHI media reconciliation dry runs
      Roles:
        - !Ref SpringApplicationRoleName
      PolicyDocument:
        Version: "2012-10-17"
        Statement:
          - Sid: InspectPrivateOriginalBucketVersioning
            Effect: Allow
            Action:
              - s3:GetBucketVersioning
            Resource: !GetAtt OriginalImageBucket.Arn
          - Sid: InspectDeliveryBucketVersioning
            Effect: Allow
            Action:
              - s3:GetBucketVersioning
            Resource: !Sub "arn:\${AWS::Partition}:s3:::\${DeliveryBucketName}"
          - Sid: ListPrivateOriginalVersions
            Effect: Allow
            Action:
              - s3:ListBucketVersions
            Resource: !GetAtt OriginalImageBucket.Arn
            Condition:
              StringLike:
                s3:prefix: "media/originals/*"
          - Sid: ListRenditionVersions
            Effect: Allow
            Action:
              - s3:ListBucketVersions
            Resource: !Sub "arn:\${AWS::Partition}:s3:::\${DeliveryBucketName}"
            Condition:
              StringLike:
                s3:prefix: "media/renditions/*"
`);
}

const isCli =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isCli) {
  if (process.argv.length !== 5) {
    console.error(
      "Usage: node check-template-guardrails.mjs <template> <boundary-template> <prod-parameters>",
    );
    process.exit(2);
  }
  validateTemplateGuardrails(
    fs.readFileSync(process.argv[2], "utf8"),
    fs.readFileSync(process.argv[3], "utf8"),
    fs.readFileSync(process.argv[4], "utf8"),
  );
  console.log("Cleanup access, production alarm and CloudFormation PassRole guardrails are explicit.");
}
