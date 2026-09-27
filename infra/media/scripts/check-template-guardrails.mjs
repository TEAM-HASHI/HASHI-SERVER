import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

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

export function validateTemplateGuardrails(rawTemplate, rawBoundaryTemplate, rawProdParameters) {
  const template = rawTemplate.replaceAll("\r\n", "\n");
  const boundary = rawBoundaryTemplate.replaceAll("\r\n", "\n");
  validateCleanupGuardrails(template);
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
