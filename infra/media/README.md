# 이미지 변환 AWS 인프라

이 디렉터리는 HASHI 이미지 변환 v1의 SAM/CloudFormation source of truth다. 현재 repository에는
template과 배포 경로만 있으며, 이 문서만으로 실제 dev 또는 prod stack이 생성됐다고 간주하지
않는다. 실제 적용 여부는 CloudFormation stack과 배포 workflow 실행 결과로 별도 확인한다.

## 소유 경계

| 구분 | 이 stack의 책임 |
| --- | --- |
| private original S3 | 생성·정책 관리. 삭제·교체 시에도 bucket, bucket policy와 원본은 `Retain`한다. |
| request/result SQS와 DLQ | 생성·redrive·암호화·가시성 timeout 관리 |
| image transform Lambda | Node.js 24.x, x86_64, 1536MB, 60초, batch size 1, `live` alias로 관리 |
| Lambda와 Spring media IAM | worker 최소 권한 policy와 기존 EC2 role의 media policy 관리 |
| CloudWatch | Lambda log 보존과 queue, DLQ, error, throttle alarm 관리 |
| 기존 delivery S3 | 이름만 parameter로 참조한다. 이 stack은 bucket을 생성·수정·삭제하지 않는다. |
| 기존 CloudFront | distribution ID만 환경 binding으로 기록한다. 이 stack은 배포 설정을 수정하지 않는다. |

파생 object key는 immutable하므로 정상 규격 변경이나 배포에서 CloudFront invalidation을 하지 않는다.
기존 distribution의 OAC와 bucket policy가 `media/renditions/*`를 전달할 수 있는지는 dev E2E 전에
읽기 전용으로 확인한다.

## 보안 기준

- original bucket은 Block Public Access, BucketOwnerEnforced, versioning, SSE-S3와 TLS 강제를 사용한다.
- original bucket은 CloudFront origin이 아니며 public URL을 제공하지 않는다.
- 브라우저 CORS는 `MEDIA_{ENV}_UPLOAD_ALLOWED_ORIGINS`의 exact origin만 허용한다. wildcard는 배포 workflow가 거부한다.
- GitHub Actions는 장기 AWS access key를 사용하지 않고 dev/prod별 OIDC role을 assume한다.
- OIDC deploy role과 CloudFormation execution role을 분리한다. 두 role은 같은 ARN일 수 없다.
- prod OIDC role은 change set을 만들 수 있지만 실행할 수 없다. 실제 실행은 별도 AWS 운영 권한으로 수행한다.
- 권한이 있는 deployment workflow의 action은 검증한 full commit SHA로 고정한다.
- worker 실행 role은 SAM이 자동 생성하지 않고 stack에서 직접 정의한다. request queue, 전용 log group,
  original/rendition prefix와 result queue 외의 resource에는 접근할 수 없다.
- worker test·package와 SAM 검증은 `id-token` 권한이 없는 build job에서 끝낸다. 같은 workflow run의
  immutable artifact로 ZIP을 전달하고 SHA-256을 다시 확인한 뒤, 별도 deploy job만 OIDC token을 요청한다.
- AWS 계정 전역 OIDC provider와 bootstrap role은 기존 계정 리소스와 충돌할 수 있어 이 feature stack이 소유하지 않는다.
- 실제 account ID, role ARN, bucket 이름, distribution ID와 credential은 repository, 이슈와 PR에 기록하지 않는다.

## 기존 리소스 전제 조건

배포 workflow는 stack을 변경하기 전에 다음 조건을 읽기 전용으로 확인하고, 하나라도 맞지 않으면
실패한다.

- SAM artifact bucket과 기존 delivery bucket은 선택한 AWS region에 있다.
- 두 bucket은 Block Public Access 네 항목이 모두 활성화돼 있다.
- delivery bucket의 enabled lifecycle rule이 `media/renditions/*`를 만료하거나 다른 storage class로
  전환하지 않는다. worker는 rendition에 object tag를 붙이지 않으므로 tag 조건만 있는 rule은 적용되지 않는다.
- 기존 Spring EC2 instance role이 존재한다.
- 기존 CloudFront distribution은 `Deployed` 및 enabled 상태다.
- CloudFront는 delivery bucket의 정확한 S3 endpoint를 origin으로 사용한다.
- OAC는 S3 origin을 SigV4로 항상 서명한다.
- alarm SNS topic을 지정했다면 topic이 존재하고 confirmed subscription이 하나 이상 있다.

기존 delivery bucket policy가 OAC에 `media/renditions/*` 읽기를 허용하는지는 정적 이름 확인만으로
완전히 증명할 수 없다. 실제 object를 생성한 뒤 CloudFront GET을 수행하는 dev E2E를 출시 gate로
유지한다.

## GitHub Actions 준비

현재 저장소는 비공개 GitHub Free이므로 private repository에서 사용할 수 없는 environment variable,
required reviewer와 protected branch를 배포 안전장치로 가정하지 않는다. dev/prod 설정은 repository
variable에 서로 다른 prefix로 저장한다. 값은 repository 파일, 이슈와 PR에 복사하지 않는다. 향후
GitHub plan을 올리면 protected branch와 environment 승인을 추가 방어선으로 붙일 수 있지만 현재
절차의 대체 조건은 아니다.

현재 plan에서는 repository write와 workflow 실행 권한을 가진 구성원을 dev 배포자로 신뢰한다.
`develop` ref 검사와 OIDC subject exact match는 다른 branch의 실행을 막지만, 권한 보유자의 direct push
자체를 막지는 못한다. dev는 prod와 다른 AWS account를 우선 사용하고, 같은 account를 써야 한다면
deploy/execution role, stack resource와 data를 prod에서 분리해 dev role이 prod resource를 변경하거나
읽지 못하게 한다. prod workflow에는 적용 권한을 주지 않으므로 이 신뢰를 prod 실행 권한으로 확대하지
않는다.

아래 표의 `{ENV}`에는 `DEV` 또는 `PROD`를 넣는다.

| repository variable | 의미 |
| --- | --- |
| `MEDIA_{ENV}_AWS_ACCOUNT_ID` | OIDC가 접근할 수 있는 계정 allowlist |
| `MEDIA_{ENV}_AWS_REGION` | stack과 리소스 region |
| `MEDIA_{ENV}_AWS_DEPLOY_ROLE_ARN` | GitHub OIDC가 assume할 최소 권한 deploy role |
| `MEDIA_{ENV}_AWS_CLOUDFORMATION_EXECUTION_ROLE_ARN` | CloudFormation이 리소스를 적용할 execution role |
| `MEDIA_{ENV}_AWS_SAM_ARTIFACT_BUCKET` | SAM package 전용 기존 private bucket |
| `MEDIA_{ENV}_DELIVERY_BUCKET_NAME` | 기존 private delivery bucket 이름 |
| `MEDIA_{ENV}_CLOUDFRONT_DISTRIBUTION_ID` | 기존 distribution ID |
| `MEDIA_{ENV}_SPRING_APPLICATION_ROLE_NAME` | 기존 Spring EC2 instance role 이름 |
| `MEDIA_{ENV}_UPLOAD_ALLOWED_ORIGINS` | 콤마로 구분한 exact client origin |
| `MEDIA_{ENV}_ALARM_NOTIFICATION_TOPIC_ARN` | 기존 alarm SNS topic. prod에서는 필수 |
| `MEDIA_{ENV}_WORKER_EVENT_SOURCE_ENABLED` | `true` 또는 `false`. 생략 시 안전하게 `false` |

stack 이름은 variable로 받지 않고 `hashi-dev-media-pipeline`, `hashi-prod-media-pipeline`으로 고정한다.
workflow는 기존 stack의 `EnvironmentName` parameter와 `Project`, `Component`, `Environment` tag가 선택한
target과 모두 일치하는지도 change set 생성 전에 검사한다.

OIDC role trust policy의 `sub`는 dev role은 이 repository의 `refs/heads/develop`, prod role은
`refs/heads/main`만 exact match로 허용한다. repository 전체나 모든 ref를 허용하는 wildcard subject를
사용하지 않는다. dev와 prod deploy role은 각각 자기 환경의 정확한 CloudFormation execution role
하나에만 `iam:PassRole`을 허용하고 `iam:PassedToService=cloudformation.amazonaws.com` 조건을 붙인다.
role을 받는 CloudFormation create/update/change-set 권한에도 `cloudformation:RoleARN`이 그 execution
role과 일치하는 조건을 둔다. dev deploy role에는 기존 의존성 읽기, SAM artifact 업로드,
CloudFormation 배포와 termination protection에 필요한 권한만 부여한다. prod deploy role은 dependency
확인, artifact 업로드와 change set 생성·조회까지만 허용하고 `cloudformation:ExecuteChangeSet`은
허용하지 않는다.

CloudFormation execution role은 `cloudformation.amazonaws.com`만 신뢰하고 GitHub OIDC provider를
신뢰하지 않는다. 실제 resource 생성·수정 권한은 이 role이 담당한다. prod change set은 GitHub 실행자와
분리된 AWS 운영자가 내용과 execution role을 확인한 뒤 별도 세션에서 실행한다.

## 검증과 배포 순서

PR에서는 다음 작업이 자동 실행된다.

1. Node.js 24와 SAM CLI `1.165.0` 설치 및 버전 확인
2. delivery lifecycle과 worker IAM 검사기의 반례 test
3. worker exact dependency 설치와 fixture test
4. Linux x64 production package 생성
5. `sam validate --lint`와 `sam build`

실제 배포는 `Deploy Image Pipeline` workflow를 수동 실행한다.

1. repository variable, branch별 OIDC trust, dev/prod 격리와 두 IAM role의 실제 policy를 먼저 확인한다.
2. `dev`와 `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED=false`로 최초 stack을 배포한다.
3. 최초 stack이 original bucket의 versioning을 처음 활성화했다면 첫 PUT 또는 DELETE 전에 15분을
   기다린다. 기존 stack update에는 이 대기를 반복하지 않는다.
4. stack event와 termination protection을 확인하고, output의 original bucket, request queue와 result
   queue를 EC2 런타임 환경에 반영한다.
5. Spring result consumer와 상태 연동을 배포한다. alarm topic의 confirmed subscription,
   CloudWatch publish 및 필요 시 KMS key policy를 확인하고 실제 시험 알림을 수신한다.
6. PENDING_UPLOAD, EXPIRED, UNBOUND, FAILED와 DB 미참조 S3 version cleanup 및 reconciliation을
   구현·배포한다. 24시간/7일 보존 기간, 실행 주기, 실패 metric·alarm과 재실행 절차를 확인한다.
7. 4~6단계 동안 DB의 `media_pipeline_config.issuance_enabled`는 `false`로 유지한다.
8. worker compatibility를 확인한 뒤 `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED=true`로 dev stack을 다시
   배포한다. 값이 없으면 worker를 켜지 않는다.
9. 승인된 dev 절차로 upload, request, WebP 생성, result consume, READY 반영, CloudFront 전달과 DLQ
   redrive까지 E2E를 통과한 뒤에만 dev issuance를 활성화한다.
10. prod는 검토된 `main` commit에서 `target=prod`, `production_confirmation=prepare-prod`로 workflow를
    실행한다. 이 실행은 CloudFormation change set만 만들며 production resource를 변경하지 않는다.
11. 별도 AWS 운영자가 workflow의 `github.sha`와 ZIP SHA-256이 검토한 commit 및 change set의
    `SourceCommitSha`, `WorkerArtifactSha256` parameter와 같은지 확인한다. change set, IAM execution
    role과 전체 parameter를 검토해 실행하고 termination protection을 활성화한다. 최초 bucket이면
    3단계 대기를 동일하게 적용한다.
12. prod에서도 5~9단계와 별도 운영 승인을 통과한 뒤에만 event source와 issuance를 활성화한다.

현재 Spring이 사용하는 값은 `AWS_MEDIA_ORIGINAL_BUCKET`이다. request/result queue URL 환경변수는
Spring 상태 연동 이슈에서 추가하며, 그 전에는 stack output만 안전하게 보관한다. 기존 AWS binding은
환경별 repository variable에서 관리하고, Spring runtime에 필요한 stack output은 EC2 환경 파일에
반영한다.

## 장애와 rollback

- 신규 job 발급 중지는 DB의 `issuance_enabled=false`로 처리한다.
- 이미 request queue에 들어간 작업까지 멈춰야 하면 해당 repository variable의
  `MEDIA_{ENV}_WORKER_EVENT_SOURCE_ENABLED=false`로 같은 stack을 다시 배포한다.
- 배포 실패는 CloudFormation rollback을 사용하고 콘솔에서 리소스를 임의 수정하지 않는다.
- dev 배포 workflow는 stack 생성·갱신 뒤 termination protection을 활성화한다. prod는 change set
  실행 후 별도 AWS 운영자가 활성화한다. 의도적인 stack 삭제는 별도 승인으로 protection을 해제한 뒤 수행한다.
- DLQ 메시지는 원인을 분류하고 현재 job identity를 확인한 뒤 redrive한다. 원문 body를 로그나 이슈에 복사하지 않는다.
- stack 삭제나 교체 후에도 original bucket과 TLS 강제 bucket policy는 retain된다. 수동으로 비우거나 삭제하지 않는다.
- 파생본은 deterministic key와 checksum을 검증하므로 같은 job 재시도에서 덮어쓰지 않는다.

prod에서 alarm topic 없이 배포할 수 없으며, dev에서도 topic을 연결하지 않으면 alarm은 생성되지만
외부 알림은 발송되지 않는다.
