# DEV media bootstrap

이 절차는 DEV 전용이다. prod, backfill, cleanup, 운영 데이터 삭제를 승인하지 않는다.
예제에는 실제 계정 ID, bucket 이름, distribution ID와 credential을 저장하지 않는다.
`dev-runtime-boundaries.json`과 `dev-deployment-roles.json`은 별도 운영자 stack이며
GitHub media deploy 역할에 이 stack들의 변경 권한을 주지 않는다.

## 적용 전 읽기 전용 확인

운영자는 별도 승인된 DEV 세션에서 다음 항목을 확인하고 결과를 비공개 운영 기록에 남긴다.

1. STS caller account와 리전, 기존 DEV delivery bucket의 소유 계정, private 설정,
   lifecycle, CloudFront distribution/OAC binding을 확인한다. 이름이 `hashi-dev-`로
   시작한다는 사실만으로 DEV 소유권이 증명되지는 않는다. prod bucket/distribution을
   재사용하지 않는다. artifact bucket은 `hashi-dev-media-artifacts-<DEV 계정 ID>`의
   기존 private SSE-S3 bucket을 사용한다. 이 변경은 bucket을 생성하지 않는다.
2. 기존 `hashi-dev-ec2-role`의 trust policy, permissions boundary, inline policy 전체,
   attached managed policy 전체와 각 default version을 조회한다. 이 역할이 실제 DEV
   EC2 instance profile에만 연결돼 있는지 확인한다. prod와 공유하면 중단한다.
3. Spring runtime boundary를 실제 DEV account/region/delivery parameter로 렌더링하여
   기존 유효 권한과 **읽기 전용으로 비교한다**. 현재 지원하는 기존 권한은 선택한 DEV
   delivery bucket의 `/*`에 대한 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`뿐이다.
   역할의 다른 AWS 권한, resource policy 기반 권한, 기존 boundary 또는 조건이 발견되면
   자동 교체하지 않고 중단한다. 모든 기존 policy/condition과 SCP 등 적용 상한을 함께
   확인하여 기존 기능이 제한되지 않는다는 근거를 남긴 후 운영자가 부착을 승인한다.
4. 기존 IAM GitHub OIDC provider의 account, URL `https://token.actions.githubusercontent.com`,
   client ID `sts.amazonaws.com`을 확인한다. `GitHubOidcProviderHost=token.actions.githubusercontent.com`으로
   같은 account의 provider를 **재사용**한다. ARN은 현재 AWS account에서 구성한다.
   없으면 운영자가 별도 승인 절차로 account-global provider를 한 번 생성한다. 이
   bootstrap은 provider 생성/삭제를 소유하지 않아 기존 provider와 충돌하지 않는다.

정책 비교의 목적은 기존 권한을 그대로 보존하면서 새 media 권한의 최대 범위를 검증하는 것이다.
boundary는 자체적으로 권한을 부여하지 않는다. 기존 delivery `DeleteObject` 상한을 보존하는 것은
새 삭제 권한 부여나 삭제 실행 승인이 아니다. original 삭제, delivery `DeleteObjectVersion`,
backfill용 version 목록/읽기/tagging, `sts:AssumeRole`, IAM 변경은 runtime 상한에 없다.

## 운영자 적용 순서

변수 값은 승인된 운영 세션에만 두고 저장소/PR/로그에 credential이나 계정 설정을 복사하지 않는다.
아래는 운영자 수동 절차이며 CI가 실행하지 않는다. 각 change set 내용을 확인한 뒤 실행한다.

1. 기존 `cloudformation-execution-boundary.yaml`을 `EnvironmentName=dev`로 별도 stack에
   적용한다. 이미 존재하면 ARN과 document가 source와 일치하는지만 확인한다. 기존 exact
   PassRole 계약은 바뀌지 않는다. 이 boundary는 runtime 상한을 대신하지 않는다.
2. `dev-runtime-boundaries.json`을 `hashi-dev-media-runtime-boundaries` stack에
   `DeliveryBucket=<확인한 DEV delivery bucket>`으로 적용한다. 두 managed policy는 Retain한다.
   이 stack은 Spring role 자체나 현재 정책/attachment를 변경하지 않는다.
3. 위 유효 권한 비교와 운영자 승인이 완료된 뒤에만 기존 DEV Spring role에 새
   `hashi-dev-media-spring-runtime-boundary`를 부착한다. 기존 boundary가 있다면 덮어쓰지 말고
   중단한다. 기존 attached/inline policy는 그대로 둔다. 부착 직후 role ARN, boundary ARN,
   default document와 기존 legacy upload/read 동작을 재확인한다.
4. `dev-deployment-roles.json`을 별도 `hashi-dev-media-deployment-roles` stack에 적용한다.
   필요한 parameter는 `ArtifactBucket`, `DeliveryBucket`, `DistributionId`,
   `OriginAccessControlId`, `SpringRoleName`, `GitHubOidcProviderHost`이다.
   기존 같은 이름의 역할이 다른 stack이나 운영 주체 소유이면 import/삭제로 우회하지 않고 중단한다.
   생성된 두 role과 모든 bootstrap stack의 보호/소유 정보를 기록하고 termination protection을 켠다.
5. DEV repository variables를 해당 output/확인된 dependency로 설정한다. 최초에는
   `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED=false`, `MEDIA_DEV_BACKFILL_ACCESS_ENABLED=false`,
   `MEDIA_DEV_CLEANUP_ACCESS_ENABLED=false`를 유지한다. alarm topic을 지정한다면 먼저
   confirmed subscription을 확보한다. 구독 없는 topic을 설정하면 기존 preflight가 실패한다.
6. 검토된 `develop` HEAD에서 workflow를 실행한다. preflight는 두 runtime boundary의 실제
   default policy document를 source 렌더링 결과와 비교하고 Spring/기존 worker의 부착 ARN까지
   확인한다. 최초 배포 시 worker `NoSuchEntity`만 허용하며 AccessDenied/조회 실패는 허용하지 않는다.
   worker는 main template에서 DEV인 경우에만 exact boundary를 붙여 생성한다. production template
   경로에는 이 DEV boundary가 적용되지 않는다.
7. stack/event/role attachment와 disabled event source를 확인한다. 이후 E2E, runtime env,
   DB issuance, queue activation은 [상위 runbook](../README.md)의 별도 승인 절차를 따른다.

Spring boundary가 없거나 modified policy이면 workflow는 stack을 변경하지 않는다. 해결을 위해
preflight를 건너뛰거나 deploy role에 `PutRolePermissionsBoundary`를 부여하지 않는다.
운영자가 boundary를 복구/변경할 때도 같은 비교·승인을 반복한다. 자동 boundary 제거 rollback은 없다.

## 상한과 배포 권한

- Execution role은 worker 생성 및 boundary 재설정 시 exact worker boundary ARN만 허용한다.
  boundary 삭제 권한은 없고, runtime boundary 정책의 ARN은 이 역할이 관리할 수 있는
  `hashi-dev-media-pipeline-*` policy 범위 밖이다. worker inline policy를 바꾸더라도 DEV
  logs/request/result/original/rendition 상한을 벗어날 수 없다.
- Spring managed media policy를 작성/부착할 수 있지만 기존 Spring boundary를 바꿀 수는 없다.
  Spring의 legacy delivery 권한과 media 권한 외의 prod 접근이나 role assumption은 상한 밖이다.
- GitHub OIDC `sub`는 exact repository의 `refs/heads/develop`이다. CloudFormation role 전달,
  stack 변경/태깅은 `hashi-dev-media-pipeline`에만 허용한다.
- SQS mapping UUID는 생성 전에 알 수 없어 event-source-mapping tagging ARN 범위는 account/region이다.
  TagResource 요청은 Project=HASHI, Environment=dev, Component=media를 요구하며, UntagResource는
  같은 existing resource tags를 요구한다. mapping 생성/변경/삭제는 DEV 함수 ARN 조건을 별도로 둔다.
  이미 DEV 태그가 붙은 mapping의 SourceCommit 등 일부 태그만 갱신하는 요청도 허용하되,
  Project/Environment/Component를 다른 값으로 바꾸는 요청은 허용하지 않는다.
- DEV bootstrap에서 backfill/cleanup=true는 거부한다. 향후 활성화는 별도 승인과 boundary/검증
  계약 변경이 필요하다. production의 기존 수동 배포/flag 계약은 변경하지 않는다.

## 로컬 검증

```bash
node --test infra/media/scripts/*.test.mjs
node infra/media/scripts/check-worker-iam.mjs infra/media/template.yaml
node infra/media/scripts/check-template-guardrails.mjs infra/media/template.yaml infra/media/bootstrap/cloudformation-execution-boundary.yaml infra/media/parameters/prod.example.json
sam validate --lint --region ap-northeast-2 --template-file infra/media/bootstrap/dev-runtime-boundaries.json
sam validate --lint --region ap-northeast-2 --template-file infra/media/bootstrap/dev-deployment-roles.json
```

테스트는 실제 AWS 권한/배포 성공의 증거가 아니다. 최초 실행 결과, Spring 기존 동작 및 worker
E2E는 별도로 확인한다. IAM 역할명과 DEV bucket prefix는 검토된 환경 계약이므로 다른 이름을 쓰려면
임의로 wildcard를 넓히지 않고 source·tests·운영 검토를 함께 변경한다.
