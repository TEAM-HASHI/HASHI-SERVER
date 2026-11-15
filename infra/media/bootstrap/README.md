# DEV media bootstrap

이 절차는 DEV 전용이다. prod, backfill, cleanup과 파일 삭제를 승인하지 않는다.
reconciliation(DB 미참조 파일 대조)의 조회 권한은 아래 후속 점검 절차로만 추가한다.
권한 배포만으로 `DRY_RUN`이나 삭제가 시작되지는 않는다.
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
backfill용 version 읽기/tagging, `sts:AssumeRole`, IAM 변경은 runtime 상한에 없다.

reconciliation은 삭제하지 않는 `DRY_RUN`에서도 `s3:GetBucketVersioning`과
`s3:ListBucketVersions`를 사용한다. Spring runtime boundary는 두 DEV bucket의 상태 조회와
`media/originals/*`, `media/renditions/*`의 버전 목록 조회만 상한으로 허용한다. boundary 자체는
권한을 부여하지 않으며, 별도 `ReconciliationReadAccessEnabled=true` 정책이 있어야 사용할 수 있다.
배포 전 검사는 여전히 `CleanupAccessEnabled=true`와 backfill을 거부한다.
초기 배포에서는 조회 flag와 `AWS_MEDIA_RECONCILIATION_ENABLED` 모두 `false`를 유지한다.
환경변수만 켜거나 권한 검사를 건너뛰지 않는다. 자세한 실행 조건은
[파일 대조 runbook](../../../docs/media/object-reconciliation-runbook.md)을 따른다.

### 삭제 없는 파일 대조를 준비할 때

소스 머지만으로 AWS 권한이나 Spring 설정이 바뀌지는 않는다. 새 boundary를 적용한 뒤에는
이전 소스의 배포 검사가 불일치로 중단되므로, 갱신된 소스를 사용하는 배포로 이어서 진행한다.
조회 점검 준비가 끝나도 일반 issuance를 바로 켜지 않는다. 정리 기능과 실패 복구의 별도 검증이 필요하다.

1. 검토된 commit과 DEV 전용 bucket·DB·role 소유권을 다시 확인한다. 서로 다른 DB가 동일한
   media 경로를 공유하면 중단한다. 일반 issuance와 두 정리 scheduler는 OFF로 유지한다.
2. 운영자가 `hashi-dev-media-runtime-boundaries` stack의 변경 계획을 확인하고 먼저 갱신한다.
   Spring boundary에 위 조회 상한만 추가되는지, worker와 기존 정책은 그대로인지 확인한다.
   기존 boundary를 삭제하거나 다른 policy로 바꾸지 않는다.
3. `MEDIA_DEV_RECONCILIATION_READ_ACCESS_ENABLED=true`로 검토된 develop 배포를 실행한다.
   `MEDIA_DEV_CLEANUP_ACCESS_ENABLED=false`, `MEDIA_DEV_BACKFILL_ACCESS_ENABLED=false`는 유지한다.
   preflight는 갱신된 boundary 문서와 attachment를 exact 비교하므로 순서가 다르면 배포를 중단한다.
4. 새 `SpringApplicationReconciliationReadPolicy`가 원본·delivery의 상태 조회와 media prefix의
   버전 목록만 허용하는지 확인한다. 이 정책은 삭제·객체 내용 읽기·versioning 변경 권한을 주지 않는다.
   기존 legacy delivery `DeleteObject` 권한은 보존되므로 role 전체가 읽기 전용이라는 뜻은 아니다.
5. 두 bucket의 versioning이 실제로 `Enabled`이고 전파가 끝났는지 별도로 확인한다.
   이번 template은 기존 delivery bucket의 versioning을 켜지 않는다. 이후 승인된 시험에서만
   Spring을 `AWS_MEDIA_RECONCILIATION_ENABLED=true`, `AWS_MEDIA_RECONCILIATION_MODE=DRY_RUN`으로
   설정한다. `AWS_MEDIA_CLEANUP_ENABLED=false`와 일반 issuance OFF는 유지한다.
6. 중단할 때는 Spring scheduler를 끄고 진행 중 실행 종료를 확인한 뒤 조회 flag를 false로 재배포한다.
   이 stack의 조회 정책만 회수되며 기존 정책·boundary는 제거하지 않는다. 실제 삭제 권한과 DELETE
   실행은 별도 작업·승인 대상이다.

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
   `MEDIA_DEV_CLEANUP_ACCESS_ENABLED=false`, `MEDIA_DEV_RECONCILIATION_READ_ACCESS_ENABLED=false`를
   유지한다. alarm topic을 지정한다면 먼저
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

### 기존 worker 역할에 boundary가 없는 경우

이전 배포에서 남은 worker 역할이 있더라도 boundary가 없으면 배포 전 검사는 중단한다.
운영자는 다음 순서로 확인하고 복구한다.

1. 대상 account·region과 역할이 DEV worker 전용인지, 소유 CloudFormation stack과 실제 사용처를
   확인한다. 다른 stack 소유이거나 소유권이 불명확하면 중단한다. 역할만 남았고 해당 stack이 없다면
   boundary 부착만으로 최초 생성 시 이름 충돌이 해결되지는 않는다. 삭제·import로 우회하지 않고
   역할 소유권 정리 방법을 별도로 검토한다.
2. trust policy, inline·attached policy 전체와 현재 유효 권한을 조회하고 worker runtime boundary와
   비교한다. 현재 기능에 필요한 권한이 제한되지 않는지 확인한다. 다른 boundary가 이미 있다면
   자동으로 교체하지 않고 별도로 검토한다.
3. 검토와 승인이 끝난 경우에만 운영자 세션으로 해당 DEV 역할에
   `hashi-dev-media-worker-runtime-boundary`를 부착한다. 배포 역할의 권한을 넓히지 않는다.
4. 부착 ARN과 정책 내용이 source와 같은지 확인한 뒤 배포 전 검사부터 다시 실행한다.
   CloudFormation이 관리하는 역할이면 template의 boundary 선언과 실제 설정도 일치하는지 확인한다.

이 절차는 복구 방법을 설명할 뿐 역할의 권한을 자동으로 변경하거나 worker 실행을 활성화하지 않는다.

## 상한과 배포 권한

- SAM template을 변환할 때 GitHub 배포 역할뿐 아니라 CloudFormation 실행 역할에도
  `cloudformation:CreateChangeSet`이 필요하다. 두 역할 모두 현재 리전의 AWS 관리
  `Serverless-2016-10-31` transform만 허용한다. 실행 역할에 다른 stack의 변경 권한을
  추가하는 것은 아니다. 기존 bootstrap을 적용했다면 역할 stack을 먼저 갱신한 뒤 재배포한다.
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

template 검사는 이 저장소의 명시적인 block-style YAML만 지원한다. Spring의 기본·backfill·조회·정리
정책과 각 조건을 함께 확인하고, 별도 IAM 정책/role이나 우회 attachment가 추가되면 중단한다.
alias·anchor·merge key·flow-style resource 등 지원하지 않는 표현도 건너뛰지 않고 거부한다.
새 리소스 종류나 정책 구성이 필요하면 검사 기준과 테스트를 함께 검토해야 한다. 일반 YAML 파서나
실제 계정 전체의 유효 권한 분석을 대신하지 않으므로 AWS 적용 전 정책·boundary 확인은 여전히 필요하다.

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
