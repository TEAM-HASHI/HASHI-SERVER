# 이미지 변환 AWS 인프라

이 디렉터리는 HASHI 이미지 변환 v1의 SAM/CloudFormation source of truth다. 현재 repository에는
template과 배포 경로만 있으며, 이 문서만으로 실제 dev 또는 prod stack이 생성됐다고 간주하지
않는다. 실제 적용 여부는 CloudFormation stack과 배포 workflow 실행 결과로 별도 확인한다.

## 소유 경계

| 구분 | 이 stack의 책임 |
| --- | --- |
| private original S3 | 생성·정책 관리. 삭제·교체 시에도 bucket과 원본은 `Retain`한다. |
| request/result SQS와 DLQ | 생성·redrive·암호화·가시성 timeout 관리 |
| image transform Lambda | Node.js 24.x, x86_64, 1536MB, 60초, batch size 1로 관리 |
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
- 브라우저 CORS는 `MEDIA_UPLOAD_ALLOWED_ORIGINS`의 exact origin만 허용한다. wildcard는 배포 workflow가 거부한다.
- GitHub Actions는 장기 AWS access key를 사용하지 않고 environment별 OIDC role을 assume한다.
- OIDC deploy role과 CloudFormation execution role을 분리한다.
- AWS 계정 전역 OIDC provider와 bootstrap role은 기존 계정 리소스와 충돌할 수 있어 이 feature stack이 소유하지 않는다.
- 실제 account ID, role ARN, bucket 이름, distribution ID와 credential은 repository, 이슈와 PR에 기록하지 않는다.

## GitHub Environment 준비

`media-dev`와 `media-prod` environment에 아래 variables를 설정한다. 값은 repository 파일에 복사하지
않는다.

| variable | 의미 |
| --- | --- |
| `AWS_ACCOUNT_ID` | OIDC가 접근할 수 있는 계정 allowlist |
| `AWS_REGION` | stack과 리소스 region |
| `AWS_DEPLOY_ROLE_ARN` | GitHub OIDC가 assume할 최소 권한 deploy role |
| `AWS_CLOUDFORMATION_EXECUTION_ROLE_ARN` | CloudFormation이 리소스를 적용할 execution role |
| `AWS_SAM_ARTIFACT_BUCKET` | SAM package 전용 기존 private bucket |
| `MEDIA_PIPELINE_STACK_NAME` | 환경별 CloudFormation stack 이름 |
| `MEDIA_DELIVERY_BUCKET_NAME` | 기존 private delivery bucket 이름 |
| `MEDIA_CLOUDFRONT_DISTRIBUTION_ID` | 기존 distribution ID |
| `MEDIA_SPRING_APPLICATION_ROLE_NAME` | 기존 Spring EC2 instance role 이름 |
| `MEDIA_UPLOAD_ALLOWED_ORIGINS` | 콤마로 구분한 exact client origin |
| `MEDIA_ALARM_NOTIFICATION_TOPIC_ARN` | 기존 alarm SNS topic. prod에서는 필수 |
| `MEDIA_WORKER_EVENT_SOURCE_ENABLED` | `true` 또는 `false`. 생략 시 `true` |

`media-prod` environment에는 required reviewer를 설정한다. workflow도 prod에서
`production_confirmation=deploy-prod`와 alarm topic을 추가로 요구하지만, 이 문자열은 required
reviewer를 대체하지 않는다.

## 검증과 배포 순서

PR에서는 다음 작업이 자동 실행된다.

1. worker exact dependency 설치와 fixture test
2. Linux x64 production package 생성
3. `sam validate --lint`
4. `sam build`

실제 배포는 `Deploy Image Pipeline` workflow를 수동 실행한다.

1. 먼저 `dev`를 선택한다.
2. change set과 stack event가 정상 완료됐는지 AWS에서 확인한다.
3. stack output의 original bucket, request queue와 result queue를 EC2 런타임 환경에 반영한다.
4. Spring SQS 연동 배포 전까지 DB의 `media_pipeline_config.issuance_enabled`는 `false`로 유지한다.
5. 업로드부터 WebP 생성, READY 반영과 기존 CloudFront 전달까지 dev E2E를 통과한다.
6. prod 적용은 dev E2E와 별도 운영 승인을 받은 뒤에만 실행한다.

Spring에는 stack output을 다음 환경변수로 전달한다.

- `AWS_MEDIA_ORIGINAL_BUCKET`
- `AWS_MEDIA_REQUEST_QUEUE_URL`
- `AWS_MEDIA_RESULT_QUEUE_URL`
- `AWS_MEDIA_QUEUE_ENABLED` — dev E2E 전에는 `false`

정체 복구는 기본적으로 처리 시작 10분 뒤부터 같은 결정적 job ID를 15분 간격, 최대 3회
재발행한다. 값은 `AWS_MEDIA_PROCESSING_STALE_AGE`,
`AWS_MEDIA_PROCESSING_RETRY_INTERVAL`, `AWS_MEDIA_PROCESSING_MAX_ATTEMPTS`로 조정할 수
있지만, dev 관측 없이 prod 기본값을 바꾸지 않는다. `AWS_MEDIA_RECOVERY_ENABLED=false`는
정체 job과 EPR 자동 재제출만 중지하며 기존 result consumer를 중지하지 않는다.

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
- 이미 request queue에 들어간 작업까지 멈춰야 하면 environment의
  `MEDIA_WORKER_EVENT_SOURCE_ENABLED=false`로 같은 stack을 다시 배포한다.
- 배포 실패는 CloudFormation rollback을 사용하고 콘솔에서 리소스를 임의 수정하지 않는다.
- DLQ 메시지는 원인을 분류하고 현재 job identity를 확인한 뒤 redrive한다. 원문 body를 로그나 이슈에 복사하지 않는다.
- stack 삭제나 교체 후에도 original bucket은 retain된다. 수동으로 비우거나 삭제하지 않는다.
- 파생본은 deterministic key와 checksum을 검증하므로 같은 job 재시도에서 덮어쓰지 않는다.

prod에서 alarm topic 없이 배포할 수 없으며, dev에서도 topic을 연결하지 않으면 alarm은 생성되지만
외부 알림은 발송되지 않는다.
