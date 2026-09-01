# 이미지 변환 AWS 인프라

이 디렉터리는 HASHI 이미지 변환 v1의 SAM/CloudFormation source of truth다. 현재 repository에는
template과 배포 경로만 있으며, 이 문서만으로 실제 dev 또는 prod stack이 생성됐다고 간주하지
않는다. 실제 적용 여부는 CloudFormation stack과 배포 workflow 실행 결과로 별도 확인한다.

## 소유 경계

| 구분 | 이 stack의 책임 |
| --- | --- |
| private original S3 | 생성·정책 관리. 삭제·교체 시에도 bucket과 원본은 `Retain`한다. |
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
- 브라우저 CORS는 `MEDIA_UPLOAD_ALLOWED_ORIGINS`의 exact origin만 허용한다. wildcard는 배포 workflow가 거부한다.
- GitHub Actions는 장기 AWS access key를 사용하지 않고 environment별 OIDC role을 assume한다.
- OIDC deploy role과 CloudFormation execution role을 분리한다.
- 권한이 있는 deployment workflow의 action은 검증한 full commit SHA로 고정한다.
- AWS 계정 전역 OIDC provider와 bootstrap role은 기존 계정 리소스와 충돌할 수 있어 이 feature stack이 소유하지 않는다.
- 실제 account ID, role ARN, bucket 이름, distribution ID와 credential은 repository, 이슈와 PR에 기록하지 않는다.

## 기존 리소스 전제 조건

배포 workflow는 stack을 변경하기 전에 다음 조건을 읽기 전용으로 확인하고, 하나라도 맞지 않으면
실패한다.

- SAM artifact bucket과 기존 delivery bucket은 선택한 AWS region에 있다.
- 두 bucket은 Block Public Access 네 항목이 모두 활성화돼 있다.
- 기존 Spring EC2 instance role이 존재한다.
- 기존 CloudFront distribution은 `Deployed` 및 enabled 상태다.
- CloudFront는 delivery bucket을 OAC가 설정된 S3 origin으로 사용한다.

기존 delivery bucket policy가 OAC에 `media/renditions/*` 읽기를 허용하는지는 정적 이름 확인만으로
완전히 증명할 수 없다. 실제 object를 생성한 뒤 CloudFront GET을 수행하는 dev E2E를 출시 gate로
유지한다.

## GitHub Environment 준비

`media-dev`와 `media-prod` environment를 먼저 명시적으로 생성한 뒤 아래 variables를 설정한다.
environment가 없는 상태에서 workflow를 먼저 실행해 보호 규칙 없는 environment가 자동 생성되게
하지 않는다. 값은 repository 파일에 복사하지 않는다.

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
| `MEDIA_WORKER_EVENT_SOURCE_ENABLED` | `true` 또는 `false`. 생략 시 안전하게 `false` |

environment deployment branch는 `media-dev=develop`, `media-prod=main`만 허용한다.
`media-prod`에는 required reviewer를 설정한다. workflow도 같은 branch를 다시 검사하고 prod에서
`production_confirmation=deploy-prod`와 alarm topic을 추가로 요구하지만, 이 문자열은 required
reviewer를 대체하지 않는다.

OIDC role trust policy의 `sub`는 해당 repository와 `media-dev` 또는 `media-prod` environment를
exact match로 제한한다. repository 전체나 모든 environment를 허용하는 wildcard subject를 사용하지
않는다. deploy role에는 기존 의존성 읽기, SAM artifact 업로드, change set 실행, execution role 전달과
stack termination protection 활성화에 필요한 권한만 부여한다. 실제 리소스 생성·수정 권한은
CloudFormation execution role이 담당한다.

## 검증과 배포 순서

PR에서는 다음 작업이 자동 실행된다.

1. worker exact dependency 설치와 fixture test
2. Linux x64 production package 생성
3. `sam validate --lint`
4. `sam build`

실제 배포는 `Deploy Image Pipeline` workflow를 수동 실행한다.

1. `media-dev` 보호 규칙과 OIDC trust, 기존 리소스 전제 조건을 먼저 확인한다.
2. `dev`와 `MEDIA_WORKER_EVENT_SOURCE_ENABLED=false`를 선택해 최초 stack을 배포한다.
3. change set과 stack event, termination protection이 정상 적용됐는지 AWS에서 확인한다.
4. stack output의 original bucket, request queue와 result queue를 EC2 런타임 환경에 반영한다.
5. Spring result consumer와 상태 연동을 배포하고 alarm 수신 경로를 확인한다. 이때 DB의
   `media_pipeline_config.issuance_enabled`는 `false`로 유지한다.
6. worker compatibility를 확인한 뒤 `MEDIA_WORKER_EVENT_SOURCE_ENABLED=true`로 dev stack을 다시
   배포한다. 값이 없으면 worker를 켜지 않는다.
7. 승인된 dev 절차로 upload, request, WebP 생성, result consume, READY 반영과 기존 CloudFront
   전달까지 E2E를 통과한다.
8. prod stack 적용과 prod issuance 활성화는 dev E2E와 별도 운영 승인을 받은 뒤에만 실행한다.

현재 Spring이 사용하는 값은 `AWS_MEDIA_ORIGINAL_BUCKET`이다. request/result queue URL 환경변수는
Spring 상태 연동 이슈에서 추가하며, 그 전에는 stack output만 안전하게 보관한다.

## 장애와 rollback

- 신규 job 발급 중지는 DB의 `issuance_enabled=false`로 처리한다.
- 이미 request queue에 들어간 작업까지 멈춰야 하면 environment의
  `MEDIA_WORKER_EVENT_SOURCE_ENABLED=false`로 같은 stack을 다시 배포한다.
- 배포 실패는 CloudFormation rollback을 사용하고 콘솔에서 리소스를 임의 수정하지 않는다.
- 배포 workflow는 stack 생성·갱신 뒤 termination protection을 활성화한다. 의도적인 stack 삭제는
  별도 승인으로 protection을 해제한 뒤 수행한다.
- DLQ 메시지는 원인을 분류하고 현재 job identity를 확인한 뒤 redrive한다. 원문 body를 로그나 이슈에 복사하지 않는다.
- stack 삭제나 교체 후에도 original bucket은 retain된다. 수동으로 비우거나 삭제하지 않는다.
- 파생본은 deterministic key와 checksum을 검증하므로 같은 job 재시도에서 덮어쓰지 않는다.

prod에서 alarm topic 없이 배포할 수 없으며, dev에서도 topic을 연결하지 않으면 alarm은 생성되지만
외부 알림은 발송되지 않는다.
