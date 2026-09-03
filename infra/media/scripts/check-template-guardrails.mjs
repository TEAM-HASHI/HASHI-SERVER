import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

function fail(message) {
  throw new Error(message);
}

export function validateTemplateGuardrails(rawTemplate, rawBoundaryTemplate, rawProdParameters) {
  const template = rawTemplate.replaceAll("\r\n", "\n");
  const boundary = rawBoundaryTemplate.replaceAll("\r\n", "\n");
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
  console.log("Production alarm and CloudFormation PassRole guardrails are explicit.");
}
