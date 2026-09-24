# 이미지 변환 AWS 인프라

이 디렉터리는 HASHI 이미지 변환 v1의 SAM/CloudFormation source of truth다. 현재 repository에는
template과 배포 경로만 있으며, 이 문서만으로 실제 dev 또는 prod stack이 생성됐다고 간주하지
않는다. 실제 적용 여부는 CloudFormation stack, dev workflow와 prod 운영 기록으로 별도 확인한다.

## 소유 경계

| 구분 | 이 stack의 책임 |
| --- | --- |
| private original S3 | 생성·정책 관리. 삭제·교체 시에도 bucket, bucket policy와 원본은 `Retain`한다. |
| request/result SQS와 DLQ | 생성·redrive·암호화·가시성 timeout 관리 |
| image transform Lambda | Node.js 24.x, x86_64, 1536MB, 60초, batch size 1, `live` alias로 관리 |
| Lambda와 Spring media IAM | worker 최소 권한 policy와 기존 EC2 role의 media policy 관리 |
| CloudWatch | Lambda log 보존과 queue, DLQ, invocation error, record failure, throttle alarm 관리 |
| 기존 delivery S3 | 이름만 parameter로 참조한다. 이 stack은 bucket을 생성·수정·삭제하지 않는다. |
| 기존 CloudFront | distribution ID만 환경 binding으로 기록한다. 이 stack은 배포 설정을 수정하지 않는다. |

파생 object key는 immutable하므로 정상 규격 변경이나 배포에서 CloudFront invalidation을 하지 않는다.
기존 distribution의 OAC와 bucket policy가 `media/renditions/*`를 전달할 수 있는지는 dev E2E 전에
읽기 전용으로 확인한다.

## 보안 기준

- original bucket은 Block Public Access, BucketOwnerEnforced, versioning, SSE-S3와 TLS 강제를 사용한다.
- original bucket은 CloudFront origin이 아니며 public URL을 제공하지 않는다.
- 브라우저 CORS는 `UploadAllowedOrigins`의 exact origin만 허용한다. wildcard는 dev workflow와 prod
  운영 검증에서 거부한다.
- GitHub Actions는 dev 배포에만 OIDC role을 사용하며 장기 AWS access key를 저장하지 않는다.
- dev OIDC deploy role과 dev CloudFormation execution role은 분리하며 같은 ARN일 수 없다.
- prod artifact build job은 AWS credential을 요청하지 않는다. prod package 업로드, change set 생성과 실행은
  검토된 commit을 확인한 별도 AWS 운영자 세션에서만 수행한다.
- 권한이 있는 deployment workflow의 action은 검증한 full commit SHA로 고정한다.
- worker 실행 role은 SAM이 자동 생성하지 않고 stack에서 직접 정의한다. request queue, 전용 log group,
  original/rendition prefix와 result queue 외의 resource에는 접근할 수 없다.
- worker test·package, Sharp·handler·manifest smoke와 SAM 검증은 `id-token` 권한이 없는 build job에서
  끝낸다. build job이 artifact 이름과 build ZIP SHA-256을 output으로 전달하므로 실패한 dev deploy
  job만 재실행해도 같은 immutable artifact를 복원한다. OIDC 권한이 있는 deploy job은 digest, 안전한
  ZIP 경로와 필수 파일 구조만 확인하고 artifact의 JavaScript나 native module을 실행하지 않는다.
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
required reviewer와 protected branch를 배포 안전장치로 가정하지 않는다. dev 설정만 `MEDIA_DEV_*`
repository variable에 저장한다. prod AWS 값은 GitHub에 저장하지 않고 별도 운영 절차에서 주입한다.
값은 repository 파일, 이슈와 PR에 복사하지 않는다. 향후
GitHub plan을 올리면 protected branch와 environment 승인을 추가 방어선으로 붙일 수 있지만 현재
절차의 대체 조건은 아니다.

현재 plan에서는 repository write와 workflow 실행 권한을 가진 구성원을 dev 배포자로 신뢰한다.
`develop` ref 검사와 OIDC subject exact match는 다른 branch의 실행을 막지만, 권한 보유자의 direct push
자체를 막지는 못한다. dev는 prod와 다른 AWS account를 우선 사용하고, 같은 account를 써야 한다면
deploy/execution role, stack resource와 data를 prod에서 분리해 dev role이 prod resource를 변경하거나
읽지 못하게 한다. prod build job에는 AWS 권한 자체가 없으므로 이 신뢰를 prod AWS 권한으로 확대하지
않는다.

dev workflow에는 아래 repository variable이 필요하다.

| repository variable | 의미 |
| --- | --- |
| `MEDIA_DEV_AWS_ACCOUNT_ID` | OIDC가 접근할 수 있는 dev 계정 allowlist |
| `MEDIA_DEV_AWS_REGION` | dev stack과 리소스 region |
| `MEDIA_DEV_AWS_DEPLOY_ROLE_ARN` | GitHub OIDC가 assume할 최소 권한 dev deploy role |
| `MEDIA_DEV_AWS_CLOUDFORMATION_EXECUTION_ROLE_ARN` | CloudFormation이 dev 리소스를 적용할 execution role |
| `MEDIA_DEV_AWS_SAM_ARTIFACT_BUCKET` | SAM package 전용 기존 private bucket |
| `MEDIA_DEV_DELIVERY_BUCKET_NAME` | 기존 private delivery bucket 이름 |
| `MEDIA_DEV_CLOUDFRONT_DISTRIBUTION_ID` | 기존 dev distribution ID |
| `MEDIA_DEV_SPRING_APPLICATION_ROLE_NAME` | 기존 Spring dev EC2 instance role 이름 |
| `MEDIA_DEV_UPLOAD_ALLOWED_ORIGINS` | 콤마로 구분한 exact dev client origin |
| `MEDIA_DEV_ALARM_NOTIFICATION_TOPIC_ARN` | 기존 dev alarm SNS topic |
| `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED` | `true` 또는 `false`. 생략 시 안전하게 `false` |
| `MEDIA_DEV_BACKFILL_ACCESS_ENABLED` | 승인된 legacy backfill용 임시 S3 권한. 생략 시 안전하게 `false` |
| `MEDIA_DEV_CLEANUP_ACCESS_ENABLED` | 승인된 asset 정리용 S3 권한. 생략 시 안전하게 `false` |

stack 이름은 variable로 받지 않고 `hashi-dev-media-pipeline`, `hashi-prod-media-pipeline`으로 고정한다.
dev workflow와 prod 운영자는 기존 stack의 `EnvironmentName` parameter와 `Project`, `Component`,
`Environment` tag가 선택한 target과 모두 일치하는지도 change set 생성 전에 검사한다.

dev OIDC role trust policy의 `sub`는 이 repository의 `refs/heads/develop`만 exact match로 허용한다.
repository 전체나 모든 ref를 허용하는 wildcard subject를 사용하지 않는다. dev deploy role은 정확한
dev CloudFormation execution role 하나에만 `iam:PassRole`을 허용하고
`iam:PassedToService=cloudformation.amazonaws.com` 조건을 붙인다.
role을 받는 CloudFormation create/update/change-set 권한에도 `cloudformation:RoleARN`이 그 execution
role과 일치하는 조건을 둔다. dev deploy role에는 기존 의존성 읽기, SAM artifact 업로드,
CloudFormation 배포와 termination protection에 필요한 권한만 부여한다. 배포 전 유효 권한 확인을 위해
정확한 dev execution role에 대한 `iam:GetRole`, boundary의 `iam:GetPolicy`와
`iam:GetPolicyVersion`, `iam:SimulatePrincipalPolicy`도 읽기 전용으로 허용한다.

CloudFormation execution role은 `cloudformation.amazonaws.com`만 신뢰하고 GitHub OIDC provider를
신뢰하지 않는다. 실제 resource 생성·수정 권한은 이 role이 담당한다. 이 role은 자신이 관리하는 정확한
worker role `hashi-{environment}-media-image-transform-lambda`만 Lambda에 전달할 수 있어야 한다.
identity policy에는 이 exact grant만 두고, 추가 policy가 범위를 넓히지 못하도록 version-controlled
permissions boundary를 상한으로 붙인다. boundary의 source of truth는
`infra/media/bootstrap/cloudformation-execution-boundary.yaml`이다. 이 별도 bootstrap stack을 관리자
권한으로 먼저 생성하고, 출력된 boundary ARN을 해당 환경의 CloudFormation execution role에
`put-role-permissions-boundary`로 연결한다. boundary는 다른 일반 권한을 부여하지 않으며,
`iam:PassRole`만 정확한 worker role과 `lambda.amazonaws.com` 조합으로 제한한다.

dev workflow와 prod 운영자는 실제 배포 전에 execution role에 정확한 boundary ARN과 승인된 최신 policy
document가 연결돼 있는지 확인하고, exact worker role에 대한 유효 grant도 확인한다. AWS 조회나 simulation
중 하나라도 실패하면 검사를 통과하지 않는다.

```bash
node infra/media/scripts/check-cloudformation-pass-role.mjs \
  "${AWS_CLOUDFORMATION_EXECUTION_ROLE_ARN}" \
  "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/hashi-${TARGET}-media-cloudformation-execution-boundary" \
  "arn:aws:iam::${AWS_ACCOUNT_ID}:role/hashi-${TARGET}-media-image-transform-lambda"
```

prod 운영자 세션은 prod CloudFormation execution role을 CloudFormation에 전달할 exact
`iam:PassRole`도 가져야 한다. prod change set은 GitHub 실행자와 분리된 이 운영자가 artifact, 내용과
execution role을 확인한 뒤 같은 신뢰 경계에서 생성·실행한다.

## 검증과 배포 순서

인프라 관련 변경 PR에서는 다음 작업이 자동 실행된다.

1. Node.js 24, JDK 21과 SAM CLI `1.165.0` 설치 및 버전 확인
2. backfill·cleanup IAM 범위와 기본 비활성화 계약 test
3. delivery lifecycle과 worker IAM 검사기의 반례 test
4. worker exact dependency 설치. fixture test는 별도 worker CI에서 실행한다.
5. Linux x64 production package 생성
6. `sam validate --lint`와 `sam build`

worker와 규격 파일이 바뀌어도 인프라 CI를 실행한다. SAM의 `CodeUri`가 worker package를 직접
참조하므로 새 코드로 배포 묶음을 만들 수 있는지 함께 확인해야 한다. worker 테스트는 worker CI가
담당하고, 인프라 CI는 필요한 package 생성과 smoke test, SAM 검증을 유지한다. ZIP 빌드 일부는
의도적으로 겹친다. 다른 workflow의 artifact를 전달받도록 연결하는 복잡성은 현재 추가하지 않는다.

dev 배포와 prod artifact 생성은 `Build or Deploy Image Pipeline` workflow를 수동 실행한다.

1. dev repository variable, OIDC trust, dev/prod 격리와 두 IAM role의 실제 policy를 먼저 확인한다.
2. `dev`와 `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED=false`로 최초 stack을 배포한다.
3. 최초 stack이 original bucket의 versioning을 처음 활성화했다면 첫 PUT 또는 DELETE 전에 15분을
   기다린다. 기존 stack update에는 이 대기를 반복하지 않는다.
4. stack event와 termination protection을 확인하고, output의 original bucket, request queue와 result
   queue를 EC2 런타임 환경에 반영한다.
5. Spring 상태 연동 migration 전 `issuance_enabled=false`와 `target_processing_status='PROCESSING'`인
   asset이 0건인지 확인한다. 이 조건을 어기면 migration은 전체 `ALTER TABLE`을 실패시켜 부분 적용을
   막는다. 현재 단일 EC2의 기존 Spring 컨테이너를 완전히 교체하며, 이 migration 이후에는 상태 연동을
   포함하지 않은 과거 이미지 바이너리로 rollback하지 않는다.
6. Spring result consumer와 상태 연동을 배포한다. alarm topic의 confirmed subscription,
   CloudWatch publish 및 필요 시 KMS key policy를 확인하고 실제 시험 알림을 수신한다.
7. PENDING_UPLOAD, EXPIRED, UNBOUND, FAILED와 DB 미참조 S3 version cleanup 및 reconciliation을
   구현·배포한다. 24시간/7일 보존 기간, 실행 주기, 실패 metric·alarm과 재실행 절차를 확인한다.
8. 4~7단계 동안 DB의 `media_pipeline_config.issuance_enabled`는 `false`로 유지한다.
9. worker compatibility를 확인한 뒤 `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED=true`로 dev stack을 다시
   배포한다. 값이 없으면 worker를 켜지 않는다.
10. 승인된 dev 절차로 upload, request, WebP 생성, result consume, READY 반영, CloudFront 전달과 DLQ
   redrive까지 E2E를 통과한 뒤에만 dev issuance를 활성화한다.
11. prod는 검토된 `main` commit에서 `target=prod`,
    `production_confirmation=prepare-prod-artifact`로 workflow를 실행한다. build job은 7일 보존 GitHub
    artifact, source commit과 build ZIP SHA-256만 만들며 AWS credential을 요청하거나 production
    resource를 변경하지 않는다.
12. 별도 AWS 운영자는 workflow run의 `github.sha`가 검토한 `main` commit과 같은지 확인하고, 그 run에
    기록된 정확한 artifact를 내려받는다. AWS credential이 없는 검증 환경에서 ZIP SHA-256과 package
    smoke test를 다시 확인한다. 이후 검토된 commit의 clean checkout과 별도 AWS 운영 세션에서는
    artifact 코드를 다시 실행하지 않고 기존 의존성, permissions boundary와 두 단계 `iam:PassRole`을
    검증한다. `CAPABILITY_NAMED_IAM`, commit과 digest가 포함된 고유 S3 prefix,
    `SourceCommitSha`, `WorkerBuildArtifactSha256`을 사용해 `sam deploy --no-execute-changeset`을 실행한다.
    change set, CloudFormation execution role과 전체 parameter를 검토한 뒤 같은 운영 신뢰 경계에서
    실행하고 termination protection을 활성화한다. 최초 bucket이면 3단계 대기를 동일하게 적용한다.
13. prod에서도 5~10단계와 별도 운영 승인을 통과한 뒤에만 event source와 issuance를 활성화한다.

Spring에는 stack output을 다음 환경변수로 전달한다.

- `AWS_MEDIA_ORIGINAL_BUCKET`
- `AWS_MEDIA_REQUEST_QUEUE_URL`
- `AWS_MEDIA_RESULT_QUEUE_URL`
- `AWS_MEDIA_QUEUE_ENABLED` — dev E2E 전에는 `false`

### Legacy backfill의 추가 권한

`BackfillAccessEnabled=false`가 기본값이다. 승인 후 dev repository variable의
`MEDIA_DEV_BACKFILL_ACCESS_ENABLED=true`로 배포한 경우에만 별도
`SpringApplicationBackfillPolicy`를 기존 EC2 role에 연결한다.

- legacy source는 지정한 delivery bucket 안에서 `GetObject`와 `GetObjectVersion`만 허용한다.
  기존 key가 현재 upload prefix 규칙을 따른다고 가정하지 않는다. purpose는 소유 테이블과
  슬롯으로 결정하며, adapter는 `media/` 하위 객체를 source로 사용하지 않는다.
- private original은 `media/originals/*` prefix에 한해 `ListBucketVersions`를 허용해
  응답이 유실된 copy의 version을 재발견한다. 목적지 PUT과 exact-version HEAD는 기존 media policy를 사용하고,
  빈 tag로 교체하기 위한 `PutObjectTagging`만 같은 prefix의 임시 backfill policy에서 허용한다.
- 원본 삭제, ACL 변경, bucket 공개나 기존 delivery bucket 설정 변경 권한은 추가하지 않는다.
- 실제 적용 전 delivery bucket의 데이터 범위와 EC2 role의 기존 권한을 확인한다. 이 flag는
  이 stack의 추가 policy만 제어하며 기존의 더 넓은 policy를 회수하지 않는다.

IAM 허용만으로 backfill이 실행되지는 않는다. 별도로 Spring의
`AWS_MEDIA_BACKFILL_ENABLED=true`가 필요하며 기본값은 `false`다. 새 예약·변환 job을
발급하려면 DB의 `issuance_enabled`도 활성화되어야 한다. dry-run과 실행·연결의 경계는
[legacy backfill runbook](../../docs/media/legacy-backfill-runbook.md)을 따른다.
실행 종료 후에는 Spring opt-in과 추가 IAM flag를 모두 끄는 운영 변경을 별도 승인·적용한다.

### Asset 정리의 추가 권한

`CleanupAccessEnabled=false`가 기본값이다. 승인된 dev 배포에서
`MEDIA_DEV_CLEANUP_ACCESS_ENABLED=true`를 명시한 경우에만 별도의
`SpringApplicationCleanupPolicy`를 기존 Spring EC2 role에 연결한다.

- original의 `media/originals/*`, delivery의 `media/renditions/*`에만 version 목록과 삭제를 허용한다.
  목록은 bucket ARN에 prefix 조건을 두고, 삭제는 해당 object ARN에 `DeleteObject`와
  `DeleteObjectVersion`을 부여한다. legacy 삭제, worker 권한과 bucket 설정 변경은 포함하지 않는다.
- 이 policy는 flag를 끌 때 회수할 수 있도록 Retain하지 않는다. 다른 경로로 이미 부여된 권한은
  회수하지 않으므로 활성화 전 EC2 role의 전체 유효 권한을 별도로 확인한다.
- Spring 실행은 별도 `AWS_MEDIA_CLEANUP_ENABLED=false`, `AWS_MEDIA_CLEANUP_MODE=DRY_RUN`이
  기본값이다. 양수인 업로드 만료 후 유예 시간도 운영 설정으로 명시해야 활성화할 수 있다.
- 특정 version 삭제는 영구 삭제다. stack의 bucket Retain은 애플리케이션에 의한 object 삭제를
  막거나 이미 삭제한 version을 복구해 주지 않는다.

기본 처리량은 프로세스당 한 실행에서 최대 50개 asset이다. 검증·중단·재시도·지표 해석은
[asset cleanup runbook](../../docs/media/asset-cleanup-runbook.md)을 따른다. 같은 IAM policy를 사용하는
DB 미참조 version과 폐기 spec 정리는 별도 Spring opt-in이며
[object reconciliation runbook](../../docs/media/object-reconciliation-runbook.md)을 따른다. 두 정리 작업과
dev E2E 전에는 issuance를 켜지 않는다.

정체 복구는 기본적으로 처리 시작 10분 뒤부터 같은 결정적 job ID를 15분 간격, 최대 3회
재발행한다. 값은 `AWS_MEDIA_PROCESSING_STALE_AGE`,
`AWS_MEDIA_PROCESSING_RETRY_INTERVAL`, `AWS_MEDIA_PROCESSING_MAX_ATTEMPTS`로 조정할 수
있지만, dev 관측 없이 prod 기본값을 바꾸지 않는다. `AWS_MEDIA_RECOVERY_ENABLED=false`는
정체 job과 EPR 자동 재제출만 중지하며 기존 result consumer를 중지하지 않는다.
EPR 복구는 한 주기마다 기본 50건까지만 listener에 다시 제출하며
`AWS_MEDIA_EPR_RESUBMIT_BATCH_SIZE`로 조정한다. Spring Modulith 1.4는 조회 단계에서 미완료 목록을
메모리에 적재하므로, backlog가 지속적으로 커지면 framework 업그레이드나 DB claim 방식 전환을
검토한다.

request/result queue 지연과 DLQ 수는 CloudWatch alarm으로 확인한다. Spring Prometheus에서는
`hashi.media.transform.*`, `hashi.media.processing.*`, `hashi.media.assets`,
`hashi.media.cleanup.candidates`, `hashi.media.issuance.available`을 확인한다. metric에는
asset ID, object key와 queue body를 tag로 넣지 않는다.

Lambda의 `AWS/Lambda Errors`는 invocation 또는 runtime 자체의 실패를 감지한다. partial batch
response로 반환한 개별 SQS record 실패는 정상 invocation으로 집계될 수 있으므로 worker가
EMF `HASHI/Media/ImageTransformRecordFailures`를 별도로 기록하고 environment별 alarm을 울린다.
record 실패 로그와 metric에는 원문 body, asset ID와 object key를 포함하지 않는다.

## 장애와 rollback

- 신규 job 발급 중지는 DB의 `issuance_enabled=false`로 처리한다.
- 상태 연동 migration 이후 과거 Spring 이미지 바이너리로 되돌려야 한다면 먼저 issuance와 worker event
  source를 중지하고 target processing, EPR, request/result queue와 DLQ가 모두 비었는지 확인한다. 이
  조건을 만족하지 않으면 과거 바이너리 rollback 대신 수정된 현재 계열 release를 배포한다.
- dev에서 이미 request queue에 들어간 작업까지 멈춰야 하면
  `MEDIA_DEV_WORKER_EVENT_SOURCE_ENABLED=false`로 같은 stack을 다시 배포한다. prod는 별도 AWS 운영
  절차에서 동일 parameter를 false로 적용한다.
- 배포 실패는 CloudFormation rollback을 사용하고 콘솔에서 리소스를 임의 수정하지 않는다.
- dev 배포 workflow는 stack 생성·갱신 뒤 termination protection을 활성화한다. prod는 change set
  실행 후 별도 AWS 운영자가 활성화한다. 의도적인 stack 삭제는 별도 승인으로 protection을 해제한 뒤 수행한다.
- DLQ 메시지는 원인을 분류하고 현재 job identity를 확인한 뒤 redrive한다. 원문 body를 로그나 이슈에 복사하지 않는다.
- stack 삭제나 교체 후에도 original bucket과 TLS 강제 bucket policy는 retain된다. 수동으로 비우거나 삭제하지 않는다.
- 파생본은 deterministic key와 checksum을 검증하므로 같은 job 재시도에서 덮어쓰지 않는다.

prod에서 alarm topic 없이 배포할 수 없으며, dev에서도 topic을 연결하지 않으면 alarm은 생성되지만
외부 알림은 발송되지 않는다.

## 오래된 Lambda 버전 보관과 정리

`live` alias는 현재 사용할 버전을 가리키는 이름이다. alias를 새 버전으로 옮기는 것만으로
예전 버전이 정리되지는 않는다. 아래 기준은 이미지 원본이나 파생본이 아닌 Lambda 코드 버전에만
적용한다. 이 PR에서는 자동 삭제 작업이나 배포 역할의 삭제 권한을 추가하지 않는다.
아래 수동 점검과 삭제 승인은 자동 정리 도입 전까지의 임시 절차다. 자동 정리는 별도 후속
작업에서 보호 대상 판별, 삭제 권한 제한, 배포와의 동시 실행 방지를 검증한 뒤 도입한다.
먼저 삭제 예정 목록만 확인하고, 검증 후 자동 실행을 활성화한다. 실행 위치와 권한은 기존
dev/prod 배포 신뢰 경계를 유지하도록 후속 작업에서 확정한다.

- dev/prod 각각 매월, 그리고 Lambda 코드 저장 용량 경고가 있으면 버전 목록과 계정·리전의
  사용 용량을 확인한다. 운영 담당자는 활성화 전에 점검 담당자와 일정을 운영 기록에 지정한다.
- 최신 게시 버전 5개와 게시 후 30일이 지나지 않은 버전은 보관한다. 5개는 무조건 지워서 맞추는
  상한이 아니라 최소 보관 수다. `$LATEST`는 삭제 후보에 포함하지 않는다.
- 모든 alias가 참조하는 버전(가중치 라우팅 대상 포함), 현재 CloudFormation stack이 관리하는 버전,
  rollback 대상으로 기록한 버전, event source나 다른 호출자가 직접 참조하는 버전은 개수·기간과
  관계없이 보관한다. 연결 여부를 확인하지 못하면 삭제하지 않는다.
- 배포·rollback·alias 변경과 정리를 동시에 진행하지 않는다. 담당자가 보호 대상을 제외한 후보를
  조회하고, 함수·환경·버전 번호와 보관 이유를 운영 기록에 남긴 뒤 별도 승인을 받는다.
- 승인 후에도 삭제 직전에 alias와 stack 참조를 다시 확인한다. CloudFormation 관리에서 벗어난
  버전만 별도 운영 권한으로 특정 버전 번호를 지정해 삭제한다. 함수 전체 삭제는 하지 않는다.
- 진행 중인 작업과 규격 호환성 확인 없이 오래된 버전을 rollback에 사용하지 않는다. 이전 버전
  보관과 그 버전으로 안전하게 되돌릴 수 있다는 판단은 별개다.

이 PR은 보관·점검 절차만 정하며 실제 버전 조회·삭제·자동 정리는 실행하지 않는다.

참고: [AWS Lambda 버전 관리](https://docs.aws.amazon.com/lambda/latest/dg/configuration-versions.html),
[특정 버전 삭제](https://docs.aws.amazon.com/lambda/latest/api/API_DeleteFunction.html)
