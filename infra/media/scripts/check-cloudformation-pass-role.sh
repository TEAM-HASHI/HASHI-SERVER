#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  echo "Usage: $0 <cloudformation-execution-role-arn> <worker-role-arn> <unapproved-control-role-arn>" >&2
  exit 2
fi

execution_role_arn="$1"
worker_role_arn="$2"
unapproved_role_arn="$3"

pass_role_decision() {
  local resource_arn="$1"
  local passed_to_service="$2"
  aws iam simulate-principal-policy \
    --policy-source-arn "${execution_role_arn}" \
    --action-names iam:PassRole \
    --resource-arns "${resource_arn}" \
    --context-entries \
      "ContextKeyName=iam:PassedToService,ContextKeyType=string,ContextKeyValues=${passed_to_service}" \
    --query 'EvaluationResults[0].EvalDecision' \
    --output text
}

if [[ "$(pass_role_decision "${worker_role_arn}" lambda.amazonaws.com)" != "allowed" ]]; then
  echo "CloudFormation execution role cannot pass the exact worker role to Lambda." >&2
  exit 1
fi

if [[ "$(pass_role_decision "${worker_role_arn}" ec2.amazonaws.com)" == "allowed" ]]; then
  echo "CloudFormation execution role PassRole must be limited to Lambda." >&2
  exit 1
fi

if [[ "$(pass_role_decision "${unapproved_role_arn}" lambda.amazonaws.com)" == "allowed" ]]; then
  echo "CloudFormation execution role PassRole must be limited to the exact worker role." >&2
  exit 1
fi

echo "CloudFormation PassRole boundary allows only the exact Lambda worker role."
