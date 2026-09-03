# Image Delivery Contract v1

> 효력: PR #181이 `develop`에 병합된 커밋부터 이미지 최적화 v1 구현 계약으로 사용한다.
>
> 범위: HASHI-SERVER의 업로드, 처리 상태, 응답, 호환성 계약. 클라이언트 구현과 실제 AWS
> 리소스 값은 이 문서의 범위가 아니다.

내부 구조와 대안 선택 근거는 [`ADR 0001`](../adr/0001-media-module-and-image-pipeline.md)에
정리한다.

## 1. 목표

현재 서버는 JPEG, PNG, WebP 원본용 presigned URL을 발급한다. 클라이언트가 S3에 직접
업로드한 뒤 domain API에 fileKey를 전달하며, 서버는 같은 원본 URL을 목록, 상세, 사진
상세보기에서 재사용한다. v1은 다음을 목표로 한다.

- 원본은 비공개로 보관한다.
- 화면 용도와 비율에 맞는 WebP 파생본을 미리 생성한다.
- 서버는 실제 너비와 높이를 포함한 이미지 후보를 반환한다.
- 신규 이미지의 처리 상태와 소유권을 서버가 관리한다.
- 기존 URL과 key 계약은 점진적으로 전환한다.
- 이미지 소속과 표시 순서는 기존 도메인 Aggregate가 계속 소유한다.

## 2. 범위와 제외 범위

### 2.1 v1 범위

- 프로필
- 식당 이미지와 메뉴 이미지
- 리뷰 이미지
- 기존 매거진 배너와 썸네일
- `image_asset`, `image_rendition` 상태 관리
- WebP 단일 포맷
- 기존 데이터 backfill과 legacy 호환 원칙

### 2.2 v1 제외 범위

- 아직 구현되지 않은 매거진 상세 이미지 기능
- AVIF 생성
- 구형 브라우저용 JPEG, PNG compatibility fallback
- 일반 콘텐츠 삭제 시 S3 물리 삭제 자동화
- 이미지 focal point 편집 UI와 smart crop
- 실제 AWS 리소스명, ARN, 계정 ID, secret

WebP 단일 제공은 합의된 최소 지원 환경인 Safari와 iOS 16.4 이상을 전제로 한다. 지원
범위를 낮추면 API 구조를 유지한 채 JPEG 또는 PNG source set을 추가한다.

### 2.3 v1 구현 기준

- 기존 Spring Boot 애플리케이션과 Docker, EC2 배포 구조는 유지한다.
- 변환 worker는 `nodejs24.x`, `x86_64`, Sharp 기반 Lambda ZIP으로 배포한다.
- worker용 EC2, ECS, ECR과 운영 Docker image를 추가하지 않는다.
- 초기 Lambda 설정은 memory 1536MB, timeout 60초, request batch size 1, reserved concurrency
  5다. 모든 function property 변경에 새 version을 만들고 `live` alias로 발행하며, 최초 request
  event source는 비활성화한다.
- private original bucket, SQS와 DLQ, Lambda, IAM, alarm은 AWS SAM/CloudFormation의 dev와
  prod stack으로 관리한다.
- CI와 배포는 SAM CLI `1.165.0`을 사용한다. GitHub Actions는 dev 배포에만 `develop` branch가
  고정된 OIDC role을 사용하고 장기 AWS access key를 저장하지 않는다. prod build job은 AWS
  credential을 요청하지 않으며 별도 AWS 운영자가 검토된 artifact를 배포한다.
- worker는 stack이 직접 정의한 execution role로 request queue, 전용 log group, original/rendition
  prefix와 result queue만 접근한다. SAM이 자동 부착하는 광범위 SQS managed policy는 사용하지 않는다.
- worker build와 검증은 OIDC 권한이 없는 job에서 수행한다. dev deploy job은 build job이 output으로
  넘긴 immutable artifact 이름과 build ZIP SHA-256을 확인하고 package smoke test를 통과한 뒤에만
  AWS 권한을 받는다.
- 현재 private GitHub Free 저장소에서 강제할 수 없는 required reviewer와 protected branch를 전제로
  하지 않는다. dev AWS 권한과 data를 prod에서 격리하고, dev workflow만 stack을 적용한다. prod
  workflow는 검토할 source commit과 artifact digest가 있는 GitHub artifact만 생성한다. 별도 AWS
  운영자가 exact commit과 artifact를 확인하고 prod change set을 생성·검토·실행한다.
- 기존 delivery bucket과 CloudFront는 새 stack이 소유하지 않고 parameter로 참조한다.
- Spring의 SQS 연동은 Spring Boot 3.5.x와 호환되는 Spring Cloud AWS 3.4.2를 사용한다.
- prod stack 적용과 `media_pipeline_config.issuance_enabled=true` 전환은 dev E2E 이후 별도
  운영 승인 대상으로 둔다.
- request event source는 Spring result consumer와 alarm 준비를 확인한 뒤 승인된 dev 배포에서만
  명시적으로 활성화한다. 설정이 누락되면 활성화하지 않는다.

## 3. 용어

| 용어 | 의미 |
| --- | --- |
| asset | 사용자 업로드 또는 legacy backfill로 생성한 원본 하나와 그 처리 상태 |
| rendition | asset에서 생성된 특정 role, 크기, 포맷의 파생 이미지 |
| purpose | 업로드할 때 선언하는 업무 목적과 권한 경계 |
| role | API가 이미지를 사용하는 화면 용도와 변환 규격 |
| legacy image | `assetId` 없이 기존 S3 key만 저장된 이미지 |
| specVersion | crop, 후보 폭, 품질 등 파생 규격의 버전 |

`purpose`와 `role`은 다르다. `REVIEW` purpose 하나는 `REVIEW_PREVIEW`와
`REVIEW_DETAIL` role을 생성한다.

## 4. 현재 계약

기존 `POST /api/v1/uploads/presigned-urls`는 다음 계약을 사용한다.

- 요청 필드 `usage`: `profile`, `review`, `restaurant`, `restaurant-menu`, `magazine`
- 형식: JPEG, PNG, WebP
- 파일당 최대 5MB
- 요청당 1개부터 10개
- 응답: `uploadUrl`, `fileKey`, `fileUrl`, `expiresInSeconds`, `uploadMethod`
- 클라이언트가 PUT을 완료한 뒤 domain API에 `fileKey`를 전달
- key: `uploads/{usage 디렉터리}/yyyy/MM/dd/{uuid}.{확장자}`

현재 서버는 발급 이후 object 존재, 실제 MIME, decode 가능 여부, 픽셀 크기, 발급자,
처리 상태를 확인하지 않는다. 응답 URL은 저장 key 앞에 CloudFront domain을 붙인 원본
URL이다. USER, ADMIN, ONBOARDING이 모두 이 API를 호출할 수 있고 usage별 권한을 구분하지
않는다.

현재 전달 계약의 기준은 다음과 같다.

- 식당 목록 `imageUrls`는 displayOrder 오름차순으로 최대 3개, summary는 전체를 반환한다.
- 첫 RestaurantImage를 대표 `thumbnailUrl`로 사용한다.
- 메뉴 목록과 상세는 같은 `imageKey`에서 만든 원본 URL을 반환한다.
- 리뷰 목록 `previewImageUrls`는 이름과 달리 저장된 이미지 전체를 반환하며 생성 상한은
  10장이다.
- 리뷰 `imageCount`는 저장된 ReviewImage 전체 수다.
- 공개 리뷰 사진 상세 endpoint는 없고 본인 리뷰 상세만 전체 원본 URL을 반환한다.
- profile 변경 API는 없고 현재 profile 이미지 입력은 온보딩에 있다.
- 기존 이미지 응답에는 asset ID, 실제 width와 height, MIME, 처리 상태가 없다.
- 과거 데이터 key 경로는 현재 생성 규칙과 다른 값도 있으므로 path만 보고 role을 추측하지
  않는다.

기존 계약은 전환 기간 동안 유지하되 신규 `media` 계약과 섞어 구현하지 않는다.

## 5. 업로드 purpose와 권한

| purpose | 허용 actor | 생성 role |
| --- | --- | --- |
| `PROFILE` | USER, ONBOARDING | `PROFILE_AVATAR` |
| `REVIEW` | USER | `REVIEW_PREVIEW`, `REVIEW_DETAIL` |
| `RESTAURANT` | ADMIN | `RESTAURANT_THUMBNAIL`, `RESTAURANT_CARD`, `RESTAURANT_HERO` |
| `RESTAURANT_MENU` | ADMIN | `MENU_LIST`, `MENU_DETAIL` |
| `MAGAZINE_BANNER` | ADMIN | `MAGAZINE_BANNER` |
| `MAGAZINE_THUMBNAIL` | ADMIN | `MAGAZINE_THUMBNAIL` |

현재 SecurityConfig는 일반 `/api/v1/**`를 USER 중심으로 제한하므로 media 구현 PR에서
`/api/v1/media/**`를 USER, ADMIN, ONBOARDING 중 하나로 인증된 actor에게 열어야 한다. Security
filter는 actor 유형만 확인하고 위 purpose별 세부 허용, 소유권과 상태 검증은 media Service가
강제한다. 인증되지 않은 요청은 기존 401 계약을 유지한다.

서버는 현재 인증 actor의 유형과 식별자를 기록한다. USER와 ADMIN의 숫자 ID가 같더라도
같은 소유자로 취급하지 않는다. ONBOARDING의 내부 식별자는 API 응답과 로그에 노출하지
않는다.

legacy backfill은 인증 사용자의 업로드가 아니므로 임의의 ADMIN을 발급자처럼 기록하지
않는다. `SYSTEM_BACKFILL`은 `CurrentActor`와 분리된 media 내부 origin과 owner marker다.

- public 업로드, 완료, 상태 API로 `SYSTEM_BACKFILL` asset을 만들거나 조회할 수 없다.
- 일반 업로드는 인증 actor를 creator와 owner로 기록한다.
- backfill asset은 `creationOrigin=SYSTEM_BACKFILL`, `ownerType=SYSTEM_BACKFILL`로 기록하고
  actor subject는 null로 둔다.
- READY backfill 연결은 public claim 경로가 아니라 domain별 trusted `MediaBackfillPort`만
  수행한다. 이 Port는 source identity, purpose, `SYSTEM_BACKFILL`, READY, UNBOUND,
  `cleanupStatus=ACTIVE`를 다시 검증한다.
- backfill asset은 콘텐츠에 연결된 뒤에도 인증 사용자의 소유로 위장하지 않는다. 콘텐츠
  association과 BOUND 상태가 사용처를 표현하고, 생성 origin은 감사 정보로 보존한다.

신규 asset claim 시 다음을 모두 검증한다.

- actor와 발급자가 일치한다.
- domain이 요구한 purpose와 asset purpose가 일치한다.
- 연결 가능한 상태다.
- processingStatus가 EXPIRED 또는 FAILED가 아니고 bindingStatus가 RETIRED가 아니다.
- 같은 요청에 같은 asset이 중복되지 않는다.
- 이미 다른 콘텐츠에 연결된 single-use asset이 아니다.

## 6. 상태 계약

### 6.1 내부 asset 상태

```text
PENDING_UPLOAD
    -> PROCESSING
        -> READY
        -> FAILED
    -> EXPIRED
```

| 상태 | 의미 |
| --- | --- |
| `PENDING_UPLOAD` | presigned URL을 발급했지만 서버가 object 업로드 완료를 확인하지 않음 |
| `PROCESSING` | object 확인을 마쳤고 필수 rendition을 생성하는 중 |
| `READY` | activeSpecVersion의 필수 rendition이 모두 저장되고 DB에 반영됨 |
| `FAILED` | 동일 원본으로 재시도해도 성공할 수 없는 영구 이미지 검증 실패 |
| `EXPIRED` | 제한 시간 안에 업로드 완료 확인이 되지 않음 |

상태는 앞으로만 진행한다. 이미 READY인 asset은 늦게 도착한 FAILED 결과 때문에 이전
상태로 돌아가지 않는다.

일시적인 AWS, network, timeout과 worker 오류는 FAILED로 바꾸지 않는다. SQS 재시도 한도를
소진하면 request는 DLQ로 이동하고 asset은 PROCESSING을 유지한다. 운영자는 정체 지표와
DLQ를 확인해 같은 job을 redrive한다.

### 6.2 공개 상태

도메인 응답의 이미지 객체는 `PROCESSING`, `READY`, `FAILED`만 노출한다.

- `READY`: `defaultSource`와 `sourceSets`가 존재한다.
- `PROCESSING`: source를 반환하지 않으며 클라이언트는 비율이 고정된 placeholder를 사용한다.
- `FAILED`: source를 반환하지 않으며 클라이언트는 DefaultImage를 사용한다.
- 이미지 객체가 `null`: 해당 도메인에 연결된 이미지가 없다.

내부 `PENDING_UPLOAD`, `EXPIRED` asset은 공개 콘텐츠에 연결할 수 없다.

### 6.3 binding 상태

이미지 변환 상태와 콘텐츠 연결 상태를 분리한다.

```text
UNBOUND -> BOUND -> RETIRED
```

| 상태 | 의미 |
| --- | --- |
| `UNBOUND` | 아직 어떤 콘텐츠에도 연결되지 않음 |
| `BOUND` | 콘텐츠 Aggregate가 single-use asset을 claim함 |
| `RETIRED` | 이미지 제거 또는 교체로 연결이 끝났으며 다시 claim할 수 없음 |

- 이미지 제거와 교체는 도메인 변경과 같은 로컬 DB 트랜잭션에서 `MediaPort`로 retire한다.
- retire용 public web API는 두지 않는다. association을 소유한 Aggregate Service가 도메인
  권한을 먼저 검증하고 Aggregate를 write lock한 뒤, 요청값이 아니라 현재 저장된 association에서
  제거 대상 asset ID를 구한다.
- media는 제거 대상이 BOUND인지 잠금 후 재검증한다. `cleanupStatus=PURGING`이면 충돌로 거부해
  호출 transaction을 롤백하고, `ACTIVE`와 object 정리가 끝난 terminal FAILED tombstone의
  `PURGED`는 BOUND에서 RETIRED로 바꿀 수 있다. `PURGED`에서는 S3 작업 없이 binding만 바꾼다.
  retire는 creator 일치를 요구하지 않는다. 따라서 다른 관리자가 등록한 운영 이미지와
  `SYSTEM_BACKFILL` 이미지도 권한 있는 콘텐츠 Service가 현재 association을 제거할 때 retire할
  수 있다. 현재 association에 없는 임의 asset ID는 retire 대상으로 사용할 수 없다.
- 콘텐츠 soft delete와 복구 가능 기간에는 기존 연결을 유지한다.
- 일반 S3 물리 삭제 자동화는 v1에서 제외하지만 논리적인 RETIRED 전이는 v1에 포함한다.
- backfill asset은 필수 rendition이 READY될 때까지 UNBOUND로 둔다. domain별 backfill runner가
  source identity와 association을 다시 확인한 뒤 trusted `MediaBackfillPort`로
  `SYSTEM_BACKFILL`, purpose, READY, UNBOUND와 cleanup 상태를 검증하고 public asset ID 저장과
  BOUND claim을 같은 transaction에서 커밋한다. legacy key는 그대로 유지한다.
- 일반 콘텐츠와 연결된 BOUND, RETIRED 원본의 자동 삭제는 v1에서 제외한다. 반면 presigned
  URL만 발급된 PENDING_UPLOAD와 EXPIRED, 유예 기간이 지난 UNBOUND와 FAILED, DB 미참조
  object version은 무제한 누적을 막기 위해 v1 정리 범위에 포함한다.
- `media/originals/*`에는 무조건적인 noncurrent expiration을 적용하지 않는다. application
  cleanup이 DB의 고정 sourceVersionId와 S3 version 목록을 비교해 참조되지 않은 version만
  삭제한다. bucket lifecycle은 미완료 multipart upload 중단만 담당한다.
- media write 활성화 전 유한한 보존 기간을 반드시 설정한다. 안전한 초기값은
  PENDING_UPLOAD와 EXPIRED 24시간, 신규 업로드 READY UNBOUND 24시간, backfill UNBOUND
  7일, FAILED 7일, DB 미참조 version과 object 7일이다.
- PENDING_UPLOAD와 EXPIRED는 sourceVersionId가 없을 수 있으므로 presigned 만료와 safety
  window 뒤 DB 상태를 잠가 재확인하고 asset key에서 관측된 version을 삭제한다.
- READY UNBOUND는 canonical original과 rendition을 삭제한 뒤 asset row를 정리한다. FAILED는
  raw original과 partial rendition을 삭제한다. 일반 업로드 UNBOUND FAILED는 asset row도
  정리한다. backfill UNBOUND FAILED는 `backfillIdentityHash`, failure code와
  `objectsPurgedAt` tombstone을 유지해 같은 source를 자동으로 반복 처리하지 않는다. 리뷰에
  연결된 BOUND FAILED도 상태와 슬롯 tombstone을 유지한다. object 정리가 끝난 뒤에도
  association이 RETIRED되기 전까지 공개 조회에는 source 없는 FAILED로 반환한다.
- backfill source version ID 또는 ETag가 바뀌면 새 identity로 처리한다. 같은 identity의
  failure tombstone은 자동 scan이 재처리하지 않는다. 원인 해결 뒤 승인된 운영 runbook은
  UNBOUND FAILED와 object 정리 완료를 다시 검증하고 기존 tombstone row를 삭제한다. 다음 scan은
  같은 identity로 새 asset과 새 processing job을 생성하며 기존 FAILED asset을 PROCESSING으로
  되돌리지 않는다.
- current 여부와 무관하게 최초 processingStatus 또는 targetProcessingStatus가 PROCESSING인
  asset은 asset 전체 cleanup 대상으로 선택하지 않는다. READY와 복구 가능한 asset이 참조하는
  canonical sourceVersionId도 보존한다.
- original과 모든 rendition을 지우는 asset 전체 cleanup은 S3 delete 전에 짧은 transaction에서
  상태와 보존 기간을 재확인하고 `cleanupStatus: ACTIVE -> PURGING`과 고유 purgeToken을 기록한다.
  complete, content claim과 backfill claim은 `cleanupStatus=ACTIVE`만 허용해 PURGING과 PURGED를
  거부한다. S3 삭제는 transaction
  밖에서 purgeToken 기준으로 멱등 실행하고 별도 transaction에서 PURGED tombstone 또는 row
  삭제로 마무리한다. 중단된 PURGING은 같은 token으로 재개하며 DB row lock을 S3 호출 동안
  유지하지 않는다.
- terminal FAILED target의 partial rendition은 asset 전체 cleanup과 분리한 object-only
  reconciliation으로 정리한다. 이 작업은
  `cleanupStatus`를 바꾸지 않는다. v1은 target 전체 성공 transaction에서만
  `image_rendition` manifest와 active pointer를 함께 저장하므로 실패 target의 partial object는
  DB manifest가 없는 S3 orphan이다. 삭제 직전 asset을 잠가 `cleanupStatus=ACTIVE`, grace period
  경과, 정리 대상 spec이 active도 현재 PROCESSING target도 아니고
  `lastIssuedSpecVersion` 이하이며 해당 spec의 rendition manifest가 없음을 다시 확인한다. active와
  현재 target spec prefix의 manifest 없는 개별 object도 삭제하지 않는다.
- spec은 같은 asset에서 재사용하지 않으므로 위 재확인 뒤 정리 대상 spec이 다시 active나
  target이 될 수 없다. S3 delete는 멱등 처리한다. 여러 reconciler의 중복 삭제는
  같은 결과로 수렴하고, 늦은 stale worker가 폐기 spec 경로에 object를 다시 써도 API에는 노출하지
  않으며 다음 reconciliation에서 다시 정리한다.
- RETIRED 물리 삭제는 장애 조사, 복구와 콘텐츠 보존 정책이 필요해 후속 운영 결정으로
  남긴다.
- 개인정보 hard delete 자동화 전에는 승인된 운영 runbook으로 원본, 파생본과 필요한 CDN
  cache를 함께 정리한다.

### 6.4 specVersion 활성화

`image_asset`은 공개 중인 `activeSpecVersion`, `activeSpecDigest`와 생성 중인
`targetSpecVersion`, `targetSpecDigest`를 분리한다. 동시에 하나의 target job만 허용하며
`targetProcessingStatus`, `currentJobId`와 `lastIssuedSpecVersion`으로 추적한다.

`specVersion`은 crop, role, 후보 폭과 품질뿐 아니라 Sharp와 encoder, format, metadata 제거,
색상 처리처럼 출력 bytes에 영향을 주는 전체 pipeline의 전역 단조 증가 버전이다. 출력이 달라질
수 있는 구현이나 설정을 바꾸면 반드시 새 version을 만든다.

| 상태 | active | 현재 target | 공개 상태 |
| --- | --- | --- | --- |
| 업로드 대기 | `null` | 모두 `null` | 공개 불가 |
| 최초 변환 중 | `null` | 모두 존재하고 target은 PROCESSING | PROCESSING |
| 최초 영구 실패 | `null` | 현재 target은 모두 `null`, 마지막 실패 정보만 보존 | FAILED |
| 안정된 READY | 존재 | 모두 `null` | READY |
| 새 규격 생성 중 | 기존 version | 모두 존재하고 target은 PROCESSING | READY |

- 최초 처리에서는 `activeSpecVersion=null`이며 processing job 발급 시점에
  `media_pipeline_config`가 가리키는 canonical manifest version과 digest를 target으로 사용한다.
  v1 최초 배포의 current version은 1이다. target이 성공하면 필수 manifest 저장과 같은
  transaction에서 해당 version과 digest를 active로 바꾸고 `targetSpecVersion`,
  `targetSpecDigest`, `targetProcessingStatus`, `currentJobId`를 비운다. target을 발급할 때 target
  version을 `lastIssuedSpecVersion`에도 기록하고 성공이나 실패 뒤에도 낮추지 않는다.
- 최초 target이 영구 실패하면 공개 상태는 FAILED다. active rendition이 없으므로 source를
  반환하지 않는다. 마지막 실패 규격과 failure code를 기록하고 현재 target 필드는
  비운다. 최초 FAILED asset은 다시 PROCESSING으로 되돌리지 않으며 새 원본은 새 asset으로
  업로드한다.
- READY v1 asset을 v2로 재처리할 때는 active v1과 공개 READY 상태를 유지한 채 target v2만
  PROCESSING으로 둔다. v2의 일부 rendition은 공개하지 않는다.
- v2의 필수 manifest를 모두 검증한 성공 transaction에서만 active를 v2로 원자적으로 바꾸고
  target digest도 active digest로 옮긴다. `targetSpecVersion`, `targetSpecDigest`,
  `targetProcessingStatus`, `currentJobId`를 비운다.
  API와 기존 URL compatibility projection은 항상 active version만 읽는다.
- v2가 일시 실패하거나 DLQ로 이동하면 active v1을 계속 제공한다. 영구 실패도 target만
  실패 처리하고 active v1과 공개 READY 상태를 유지한다. 마지막 실패 규격과 failure
  code를 기록한 뒤 현재 target 필드는 비운다. 해당 실패 target의 partial rendition은 유예 기간
  뒤 cleanup할 수 있다.
- target 발급 transaction은 asset을 잠근 뒤 새 version이 `lastIssuedSpecVersion`보다 큰지와
  `media_pipeline_config`가 가리키는 canonical manifest의 version과 digest가 일치하는지 검증하고
  target version, target digest, currentJobId와 `lastIssuedSpecVersion`을 함께 기록한다. 성공,
  terminal 실패
  또는 supersede 여부와 무관하게 한 번 발급한 spec은 같은 asset에서 다시 사용하지 않는다.
  terminal 실패하거나 obsolete가 된 spec은 같은 asset에서 다시 target이나 active로 사용하지
  않는다. v2가 terminal 실패하면
  재처리는 v3 이상으로만 시작하고, 이전 정책으로 rollback해야 해도 그 정책을 복제한 더 높은
  version을 만든다. 일시 오류, EPR 재발행과 DLQ redrive만 동일 spec과 job ID를 유지한다.
- 결과 consumer는 source identity, currentJobId, targetSpecVersion, targetSpecDigest,
  `targetProcessingStatus=PROCESSING`과 cleanup ACTIVE를 모두 만족하는 target 결과만 반영한다.
  이전 target의 늦은 성공과 실패 결과는 무시한다.
- 한 번 active였던 rendition과 DB manifest는 v1에서 자동 삭제하지 않는다. 외부에 발급한
  immutable URL의 최대 수명과 운영 승인 절차를 정한 뒤 후속 범위에서 정리한다. object-only
  reconciliation은 active, 현재 PROCESSING target과 DB manifest가 있는 과거 active spec을
  보존하고 terminal FAILED target의 manifest 없는 partial object만 실패 보존 기간 뒤 정리한다.
  canonical spec manifest는 append-only로 유지한다. 실패 보존 기간은 운영 배포 전에 확정한다.

## 7. 업로드 API

모든 응답은 기존 `SuccessResponse`와 `ErrorResponse` 봉투를 사용한다. 아래 예시는 `data`
내용만 표시한다.

### 7.1 asset과 업로드 URL 생성

```http
POST /api/v1/media/assets
```

```json
{
  "purpose": "REVIEW",
  "files": [
    {
      "contentType": "image/jpeg",
      "fileSize": 1048576
    }
  ]
}
```

```json
{
  "uploads": [
    {
      "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
      "status": "PENDING_UPLOAD",
      "uploadUrl": "https://example-presigned-put-url",
      "requiredHeaders": {
        "Content-Type": "image/jpeg",
        "If-None-Match": "*"
      },
      "expectedContentLength": 1048576,
      "expiresInSeconds": 300,
      "uploadMethod": "PUT"
    }
  ]
}
```

- 파일 수와 크기 제한은 기존 계약과 같이 1개부터 10개, 파일당 최대 5MB다.
- 허용 선언 MIME은 `image/jpeg`, `image/png`, `image/webp`다.
- 서버는 선언한 `fileSize`를 presigned PUT의 서명된 `Content-Length`로 고정한다. 브라우저가 실제
  body 길이로 이 header를 자동 설정하므로 클라이언트는 직접 설정하지 않고, 전송 직전
  `file.size`가 `expectedContentLength`와 같은 동일 파일인지 확인한다. presigner가
  `content-length`, `content-type`과 `if-none-match`를 signed headers에 포함하지 못하면 URL을
  발급하지 않는다.
  `requiredHeaders`에는 브라우저가 직접 설정해야 하는 `Content-Type`과 `If-None-Match`만 제공한다.
- 신규 original PUT은 서명된 `If-None-Match: *` 조건을 사용하고 original bucket CORS도 이 header를
  허용한다. 같은 key의 첫 업로드만 200으로 성공하고 이후 또는 동시 재사용은 412나 409로
  거부한다. 412를 받은 클라이언트는 object가 이미 저장된 것으로 보고 complete를 호출한다.
  409는 complete로 object 존재를 확인한 뒤 고정할 source가 없을 때만 새 asset을 발급받는다.
  같은 presigned URL로 무조건 재업로드하지 않는다.
- 서버가 original object key를 생성하며 사용자 파일명을 포함하지 않는다.
- 응답에 original URL, object key, CloudFront URL을 포함하지 않는다.
- `assetId`는 외부에 노출하는 추측하기 어려운 식별자다.

### 7.2 업로드 완료 확인

```http
POST /api/v1/media/assets/complete
```

```json
{
  "assetIds": [
    "a3af06f1-4ef2-46f8-a489-2347fb840447"
  ]
}
```

서버는 발급자, purpose, 만료와 함께 S3 object의 존재, key, 선언된 content length와
content type을 확인한다. 확인이 끝나면 asset을 PROCESSING으로 바꾸고 변환 작업을
발행한다. 완료 시점의 S3 version ID와 ETag를 현재 job에 저장한다. 실제 MIME과 decode
검증은 worker가 수행한다.

```json
{
  "assets": [
    {
      "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
      "status": "PROCESSING"
    }
  ]
}
```

완료 확인은 멱등하다.

- `PENDING_UPLOAD`: 확인 후 PROCESSING으로 전환한다.
- `PROCESSING`, `READY`: 현재 상태를 그대로 반환한다.
- `FAILED`, `EXPIRED`: 다시 완료 처리하지 않고 해당 media error를 반환한다.
- 요청 asset ID는 1개부터 10개이며 중복을 허용하지 않는다.
- 존재하지 않거나 다른 actor가 발급한 asset이 하나라도 있으면 전체 404로 처리해 존재를
  숨긴다.
- PENDING_UPLOAD인 모든 object의 HEAD가 성공해야 상태 전이 transaction을 시작한다. 하나라도
  없거나 metadata가 다르면 이번 요청의 어떤 asset도 새 상태로 전이하지 않는다.
- transaction 안에서 내부 asset ID 오름차순으로 잠그고 상태를 다시 확인한다.
- 동시 완료 요청 중 하나만 PENDING_UPLOAD를 PROCESSING으로 바꾸고 job과 event publication을
  한 번 생성한다. 나머지는 기존 상태를 반환한다.
- 응답 순서는 요청 asset ID 순서와 같다. 내부 job ID는 공개 API에 노출하지 않는다.

### 7.3 상태 조회

```http
GET /api/v1/media/assets?assetIds={assetId1},{assetId2}
```

- 한 요청에 최대 10개를 조회한다.
- 발급 actor만 조회할 수 있다.
- 업로드 화면의 polling을 위한 API이며 공개 이미지 전달 API가 아니다.

```json
{
  "assets": [
    {
      "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
      "status": "READY"
    }
  ]
}
```

### 7.4 media error 의미

실제 숫자는 구현 이슈에서 기존 error code와 중복되지 않게 배정한다.

| error 이름 | HTTP | 의미 |
| --- | --- | --- |
| `MEDIA_ASSET_NOT_FOUND` | 404 | asset이 없거나 현재 actor 소유가 아님 |
| `MEDIA_PURPOSE_FORBIDDEN` | 403 | actor가 해당 purpose로 asset을 만들 권한이 없음 |
| `MEDIA_UPLOAD_NOT_FOUND` | 400 | 완료할 original object가 없음 |
| `MEDIA_UPLOAD_METADATA_MISMATCH` | 400 | 발급할 때 선언한 크기 또는 MIME과 HEAD metadata가 다름 |
| `MEDIA_ASSET_EXPIRED` | 409 | 업로드 완료 가능 시간이 지남 |
| `MEDIA_INVALID_STATE` | 409 | 현재 상태에서 요청한 전이를 수행할 수 없음 |
| `MEDIA_ALREADY_BOUND` | 409 | single-use asset이 이미 연결됐거나 RETIRED임 |
| `MEDIA_DUPLICATE_ASSET` | 400 | 한 요청에 같은 asset ID가 중복됨 |
| `MEDIA_PIPELINE_UNAVAILABLE` | 503 | config fail-closed 또는 운영 pause로 신규 asset 발급이나 PENDING_UPLOAD 완료 전이가 일시 중지됨 |

asset 생성은 `issuance_enabled=false` 또는 config 검증 실패 때
`MEDIA_PIPELINE_UNAVAILABLE`을 반환한다. 완료 요청은 PENDING_UPLOAD를 PROCESSING으로 바꿔 새 job을
발급해야 하는 asset이 하나라도 있으면 전체 요청을 같은 503으로 거부하고 어떤 상태도 바꾸지
않는다. 모든 asset이 이미 PROCESSING이나 READY여서 새 job이 필요 없는 멱등 재호출은 pause 중에도
현재 상태를 반환하며 FAILED와 EXPIRED는 기존 error 규칙을 유지한다. 중지 시간을 미리 알 수 없으므로
v1은 `Retry-After` 값을 임의로 약속하지 않고, 클라이언트는 짧은 반복 호출 대신 제한된 지수
backoff와 사용자 재시도를 사용한다.

### 7.5 요청 남용 방지

stateful media API와 변환 job은 저장, queue와 Lambda 비용을 발생시키므로 public write 활성화
전에 actor와 purpose 기준 제한을 적용한다.

- asset 생성 횟수와 선언한 총 byte 수
- 동시에 유지할 수 있는 PENDING_UPLOAD와 PROCESSING asset 수
- 완료 API와 상태 polling 호출 빈도
- 인증 실패, 만료와 반복 실패 요청 지표

동일 완료 요청의 멱등 재호출은 새 job이나 사용량을 중복 생성하지 않는다. 실제 window와
상한, 제한 응답 코드는 운영 트래픽과 기존 error code를 확인한 구현 이슈에서 확정한다.

## 8. 콘텐츠 연결 정책

| 사용 상황 | 연결 가능한 상태 | 노출 정책 |
| --- | --- | --- |
| 신규 리뷰 이미지 | PROCESSING, READY | 리뷰 본문은 등록하고 슬롯과 순서를 유지한다. PROCESSING은 placeholder다. |
| 신규 프로필 | READY | READY 이후 가입 또는 프로필 저장을 완료한다. |
| 신규 식당, 이미지가 제공된 메뉴, 매거진 | READY | 필수 이미지가 준비된 뒤 공개한다. 메뉴 이미지 미등록은 기존처럼 허용한다. |
| 기존 이미지 교체 | READY | 새 asset 연결이 커밋되기 전까지 기존 READY 이미지를 유지한다. |

업로더 본인은 파일 선택 직후 브라우저의 로컬 파일로 미리볼 수 있다. 서버에 업로드한
raw original을 공개 fallback으로 제공하지 않는다.

도메인 Service는 같은 트랜잭션에서 `MediaPort`로 연결 가능 여부와 single-use claim을
검증한다. domain 저장이 롤백되면 asset claim도 함께 롤백돼야 한다. S3 호출은 이
트랜잭션 안에서 수행하지 않는다.

collection 수정은 도메인 Aggregate를 write lock한 뒤 저장된 association과 요청을 비교한다.
유지된 association은 ID와 asset binding을 그대로 보존하고, 새 asset만 claim하며 제거되거나
교체된 asset만 retire한다. scalar에 같은 asset을 다시 보내는 요청은 no-op이다. claim과 retire
대상 합집합은 media 내부 숫자 ID 오름차순으로 잠그고 모든 전이는 도메인 변경과 같은 로컬
DB transaction에서 처리한다.

온보딩 프로필은 User 저장으로 user ID가 생성된 뒤 같은 로컬 DB 트랜잭션에서 claim한다.
media는 현재 ONBOARDING actor가 발급한 PROFILE purpose, READY, UNBOUND asset인지 확인하고
소유 actor를 생성된 USER로 인계한 뒤 BOUND로 바꾼다. User 저장이나 AuthAccount 연결이
실패하면 claim도 함께 롤백한다.

### 8.1 domain write 필드

기존 key 필드 옆에 asset ID 필드를 additive하게 추가한다.

| 요청 | legacy 필드 | 신규 필드 |
| --- | --- | --- |
| 프로필 온보딩 | `profileImageKey` | `profileImageAssetId` |
| 리뷰 생성 | `imageFileKeys` | `imageAssetIds` |
| 식당 생성 | `imageKeys` | `imageAssetIds` |
| 식당 수정 | `imageKeys` | `images` ordered wrapper |
| 메뉴 생성과 수정 | `imageKey` | `imageAssetId` |
| 매거진 생성과 수정 | `bannerKey`, `thumbnailKey` | `bannerImageAssetId`, `thumbnailImageAssetId` |

- 같은 단일 슬롯에 legacy key와 asset ID를 함께 보내면 validation error로 거부한다.
- 기존 collection field와 신규 ordered wrapper를 한 요청에 함께 보내면 validation error로
  거부한다. 신규 wrapper 안의 각 원소는 기존 association ID 또는 신규 asset ID 중 정확히
  하나만 가진다.
- 기존 key 요청의 null, 빈 목록, 전체 교체 의미는 유지한다.
- 신규 field가 null이면 기존 field 계약을 사용한다.
- 신규 media upload는 private original key를 domain request나 legacy key column에 기록하지
  않는다.
- 리뷰 수정은 #179 병합 이후 retained `reviewImageId`와 신규 `imageAssetId`를 구분하는
  additive 요청으로 별도 확정한다.

식당 수정의 신규 `images`는 다음 형태를 사용한다. 배열 순서가 표시 순서다.

```json
{
  "images": [
    { "restaurantImageId": 101 },
    { "imageAssetId": "a3af06f1-4ef2-46f8-a489-2347fb840447" }
  ]
}
```

- `restaurantImageId`는 현재 Aggregate에 남아 있는 association만 유지할 수 있다.
- 신규 `imageAssetId`는 READY, purpose, actor, UNBOUND와 중복 여부를 검증한 뒤 claim한다.
- 서버는 persisted association 기준으로 diff를 계산하고 유지, 추가, 제거를 구분한다.
- legacy key와 public asset ID가 함께 있는 backfill association에 기존 `imageKeys` 요청으로
  같은 key가 다시 오면 row를 삭제 후 재생성하지 않고 기존 association과 asset ID를 보존한다.
- 기존 `imageKeys`는 중복 key를 허용하므로 key별 multiset으로 비교한다. 저장 association을
  displayOrder 오름차순으로 두고 입력 key마다 같은 key의 아직 소비하지 않은 첫 row를 하나씩
  매칭한다. 남는 입력만 추가하고 소비되지 않은 기존 row만 제거한다.
- 신규 media 이미지가 하나라도 포함된 collection 수정은 `images` wrapper를 사용한다.
- 응답에도 stable `restaurantImageId`를 제공해 다음 수정에서 배열 index를 식별자로 사용하지
  않는다.
- `images`와 `imageKeys` 모두 배열 위치가 최종 순서다. restaurant displayOrder는 1부터
  연속으로 다시 부여한다.
- reorder는 기존 row를 최종 순서 범위 밖의 고유한 양수 임시 값으로 먼저 이동해 flush한 뒤
  1부터 최종 순서를 적용해 다시 flush한다. `(restaurant_id, display_order)` unique 제약을
  피하려고 retained row를 삭제 후 생성해서는 안 된다.

## 9. 이미지 응답

### 9.1 공통 이미지 객체

```json
{
  "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
  "role": "REVIEW_PREVIEW",
  "status": "READY",
  "defaultSource": {
    "url": "https://cdn.example.com/media/renditions/a3af.../v1/review-preview/270.webp",
    "width": 270,
    "height": 270,
    "mimeType": "image/webp"
  },
  "sourceSets": [
    {
      "mimeType": "image/webp",
      "candidates": [
        {
          "url": "https://cdn.example.com/media/renditions/a3af.../v1/review-preview/135.webp",
          "width": 135,
          "height": 135
        },
        {
          "url": "https://cdn.example.com/media/renditions/a3af.../v1/review-preview/270.webp",
          "width": 270,
          "height": 270
        }
      ]
    }
  ]
}
```

- `defaultSource`는 오류 이미지가 아니라 일반 `<img src>`에 사용할 기본 후보다.
- 후보의 width와 height는 실제 저장 파일의 픽셀 값이다.
- `sourceSets[].candidates`는 width 오름차순으로 정렬하고 같은 width를 중복하지 않는다.
- `defaultSource`는 해당 MIME 그룹에 실제 생성된 candidate 중 하나여야 한다. v1은 역할 표의
  기본 width를 선택하고, 원본보다 커서 생성되지 않았다면 생성된 candidate 중 가장 큰 값을
  선택한다.
- 클라이언트가 URL 문자열을 수정하거나 확장자를 바꿔 다른 후보를 추측하지 않는다.
- v1은 WebP만 제공한다.
- 향후 JPEG, PNG, AVIF가 필요하면 `sourceSets`에 다른 MIME 그룹을 추가한다. 구형 환경용
  fallback을 도입할 때는 `defaultSource`를 최적화 JPEG 또는 PNG로 두고 WebP 후보는
  `sourceSets`에 유지해 `<picture>`의 `<img>` fallback으로 사용할 수 있다.
- 이 절의 JSON 예시는 원본에서 crop 가능한 최대 width가 `defaultSource.width`와 같다고 가정해
  확대가 필요한 더 큰 표준 후보는 생략한다.

PROCESSING과 FAILED 응답은 다음 불변식을 지킨다.

```json
{
  "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
  "role": "REVIEW_PREVIEW",
  "status": "PROCESSING",
  "defaultSource": null,
  "sourceSets": []
}
```

콘텐츠 Aggregate가 소속과 순서를 관리하는 collection은 공통 이미지 객체를 association
wrapper 안에 둔다. 식당 이미지는 다음 형태를 사용한다.

```json
{
  "restaurantImageId": 101,
  "displayOrder": 1,
  "image": {
    "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
    "role": "RESTAURANT_HERO",
    "status": "READY",
    "defaultSource": {
      "url": "https://cdn.example.com/media/renditions/a3af.../v1/restaurant-hero/860.webp",
      "width": 860,
      "height": 512,
      "mimeType": "image/webp"
    },
    "sourceSets": [
      {
        "mimeType": "image/webp",
        "candidates": [
          {
            "url": "https://cdn.example.com/media/renditions/a3af.../v1/restaurant-hero/430.webp",
            "width": 430,
            "height": 256
          },
          {
            "url": "https://cdn.example.com/media/renditions/a3af.../v1/restaurant-hero/860.webp",
            "width": 860,
            "height": 512
          }
        ]
      }
    ]
  }
}
```

- `restaurantImageId`와 `displayOrder`는 restaurant Aggregate가 소유한다.
- restaurant displayOrder는 현재 계약대로 1부터 시작하며 작을수록 먼저 표시한다.
- 같은 association도 endpoint에 따라 `RESTAURANT_CARD` 또는 `RESTAURANT_HERO` image를
  포함한다.
- review collection은 10절의 `reviewImageId` wrapper를 사용한다.
- 메뉴, 프로필과 매거진의 단일 이미지 필드는 association wrapper 없이 공통 이미지 객체를
  직접 사용한다.

### 9.2 역할별 필드

기존 URL 필드 옆에 신규 이미지 필드를 추가한다.

| 도메인 응답 | 기존 필드 | 신규 필드 |
| --- | --- | --- |
| 식당 목록 | `thumbnailUrl`, `imageUrls` | `thumbnailImage`, `cardImages` |
| 식당 summary와 오늘의 식당 | `thumbnailUrl`, `imageUrls` | `thumbnailImage`, `heroImages` |
| 어드민 식당 생성과 수정 응답 | `thumbnailUrl`, `imageUrls`, 메뉴 `imageUrl` | `thumbnailImage`, `heroImages`, 메뉴 `listImage` |
| 메뉴 목록 | `imageUrl` | `listImage` |
| 메뉴 상세 | `imageUrl` | `detailImage` |
| 리뷰 목록 | `reviewerProfileImageUrl`, `previewImageUrls` | `reviewerProfileImage`, `previewImages` |
| 내 리뷰 상세 | `restaurantThumbnailUrl`, `imageUrls` | `restaurantThumbnailImage`, `images` |
| 내 리뷰 목록 | `restaurantThumbnailUrl` | `restaurantThumbnailImage` |
| 리뷰 작성 컨텍스트와 방문 예약 목록 | `restaurantThumbnailUrl` | `restaurantThumbnailImage` |
| 예약 목록과 상세 | `restaurantImageUrl` | `restaurantThumbnailImage` |
| 어드민 예약 | `restaurantImageUrl` | `restaurantThumbnailImage` |
| 매거진 배너 | `bannerImageUrl` | `bannerImage` |
| 매거진 목록 | `bannerImageUrl`, `thumbnailImageUrl` | `bannerImage`, `thumbnailImage` |
| 어드민 매거진 생성과 수정 응답 | `bannerImageUrl`, `thumbnailImageUrl` | `bannerImage`, `thumbnailImage` |
| 내 정보와 프로필 summary | `profileImageUrl` | `profileImage` |
| 어드민 사용자 | `profileImageUrl` | `profileImage` |

식당의 `thumbnailImage`, `cardImages`, `heroImages` 원소는 stable `restaurantImageId`를 가진
식당 association wrapper다. 리뷰의 `previewImages`와 상세 `images` 원소는 stable
`reviewImageId`를 가진 리뷰 association wrapper다.

교차 모듈 Port의 전환기 이미지 값은 `ImageReference(assetId, legacyUrl)` 형태로 전달한다.
기존 key를 소유한 모듈이 현재 방식으로 계산한 `legacyUrl`을 제공하며, object key 자체는 다른
모듈에 공개하지 않는다.

| 전환 상태 | `assetId` | `legacyUrl` |
| --- | --- | --- |
| 아직 backfill되지 않은 legacy 이미지 | `null` | 기존 CloudFront URL |
| READY backfill 이미지 | public asset ID | 전환 기간 기존 CloudFront URL |
| 신규 media 이미지 | public asset ID | `null` |
| 이미지 없음 | `null` | `null` |

최종 응답을 소유한 Service는 non-null asset ID와 role을 모아 `MediaPort`를 한 번만 bulk
조회한다. asset ID가 있으면 media 상태와 결과를 기준으로 응답하며 PROCESSING, FAILED 또는
조회 불일치에 `legacyUrl`로 우회하지 않는다. asset ID가 없고 `legacyUrl`만 있을 때에만
전환용 legacy URL을 사용한다. entity나 응답 item마다 `MediaPort`를 호출하지 않는다.

### 9.3 기존 URL 필드 compatibility projection

| 저장 상태 | 기존 URL 필드 | 신규 이미지 필드 |
| --- | --- | --- |
| legacy key만 있음 | 기존 CloudFront 원본 URL | 단일 이미지는 `null`, association collection은 stable ID와 순서를 가진 wrapper의 `image: null` |
| media asset READY | 해당 endpoint role의 `defaultSource.url` | READY 이미지 객체 |
| 신규 media asset PROCESSING 또는 FAILED | scalar는 `null`, 배열은 READY 항목만 오름차순으로 포함 | 슬롯과 상태를 유지한 이미지 객체 |
| 이미지가 없음 | `null` 또는 빈 배열 | `null` 또는 빈 배열 |

- 식당 목록은 `RESTAURANT_CARD`, 식당 summary는 `RESTAURANT_HERO`처럼 같은 asset도 endpoint
  role에 맞는 URL을 기존 필드에 projection한다.
- 리뷰 `imageCount`는 PROCESSING과 FAILED를 포함한 전체 첨부 슬롯 수를 유지한다. 따라서
  legacy `previewImageUrls.length`와 다를 수 있다.
- 기존 이미지 교체는 새 asset READY 전까지 관계 자체를 교체하지 않으므로 기존 필드와
  신규 필드 모두 이전 READY 이미지를 반환한다.
- 신규 media original key를 기존 URL 계산에 사용하지 않는다.
- 식당과 리뷰 association collection은 legacy-only 상태에서도 각각 `restaurantImageId` 또는
  `reviewImageId`, `displayOrder`, `image: null` wrapper를 반환해 점진 전환과 수정 식별자를
  보장한다.
- 신규 media write는 상태 응답을 이해하는 클라이언트 배포 후 활성화한다. 이전 클라이언트는
  PROCESSING 이미지를 일시적으로 보지 못할 수 있지만 raw original을 다운로드하지 않는다.

## 10. 리뷰 이미지 계약

- 리뷰 이미지는 현재와 같이 최대 10장이다.
- `displayOrder`는 0부터 9이며 작을수록 먼저 표시한다.
- preview와 detail은 동일한 `reviewImageId`, `assetId`, `displayOrder`를 사용한다.
- retained image는 리뷰 수정 이후에도 `reviewImageId`를 유지한다.
- PROCESSING과 FAILED 슬롯도 배열에서 제거하지 않는다.
- `imageCount`는 삭제되지 않은 전체 첨부 슬롯 수다.
- `previewImages.length`는 응답에 포함된 슬롯 수이며 향후 subset 정책이 생기면
  `imageCount`와 다를 수 있다.
- v1은 기존 동작을 보존해 preview에도 최대 10장 전체를 반환한다.

각 배열 원소는 다음 형태를 사용한다.

```json
{
  "reviewImageId": 501,
  "displayOrder": 0,
  "image": {
    "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
    "role": "REVIEW_PREVIEW",
    "status": "READY",
    "defaultSource": {
      "url": "https://cdn.example.com/media/renditions/a3af.../v1/review-preview/270.webp",
      "width": 270,
      "height": 270,
      "mimeType": "image/webp"
    },
    "sourceSets": [
      {
        "mimeType": "image/webp",
        "candidates": [
          {
            "url": "https://cdn.example.com/media/renditions/a3af.../v1/review-preview/135.webp",
            "width": 135,
            "height": 135
          },
          {
            "url": "https://cdn.example.com/media/renditions/a3af.../v1/review-preview/270.webp",
            "width": 270,
            "height": 270
          }
        ]
      }
    ]
  }
}
```

공개 사진 상세보기 계약은 리뷰 연동 이슈에서 다음 형태로 추가한다.

```http
GET /api/v1/restaurants/{restaurantId}/reviews/{reviewId}/images
```

서버는 활성 리뷰와 restaurant 관계를 검증하고 같은 `reviewImageId` 순서에
`REVIEW_DETAIL` rendition을 반환한다. 실제 구현은 리뷰 수정 이슈가 병합된 뒤 진행한다.

## 11. 역할별 파생 규격 v1

현재 UI의 표시 비율을 유지한다. 원본보다 큰 rendition은 만들지 않는다.

| role | 비율과 처리 | 후보 width | 기본 width |
| --- | --- | --- | --- |
| `PROFILE_AVATAR` | 1:1 중앙 cover | 48, 96, 192, 288 | 96 |
| `RESTAURANT_THUMBNAIL` | 1:1 중앙 cover | 96, 192, 288 | 192 |
| `RESTAURANT_CARD` | 1:1 중앙 cover | 135, 270, 405 | 270 |
| `RESTAURANT_HERO` | 393:234 중앙 cover | 430, 860, 1290 | 860 |
| `MENU_LIST` | 1:1 중앙 cover | 100, 200, 300 | 200 |
| `MENU_DETAIL` | 393:234 중앙 cover | 430, 860, 1290 | 860 |
| `REVIEW_PREVIEW` | 1:1 중앙 cover | 135, 270, 405 | 270 |
| `REVIEW_DETAIL` | 393:574 중앙 cover | 430, 860, 1290 | 860 |
| `MAGAZINE_BANNER` | 353:160 중앙 cover | 390, 780, 1170 | 780 |
| `MAGAZINE_THUMBNAIL` | 156:88 중앙 cover | 156, 312, 468 | 312 |

WebP quality와 worker 제한 시간은 대표 운영 이미지 benchmark 후 구현 이슈에서 확정한다.
crop, 후보 폭, quality처럼 출력 bytes를 바꾸는 변경은 target `specVersion`을 올리고 기존
active object를 덮어쓰지 않는다.

- role 규격은 임의 DB 설정이나 Java와 Node의 중복 코드가 아니라 저장소의
  `media-specs/v{specVersion}.json` immutable manifest를 단일 원본으로 관리한다.
- 원본에서 crop 가능한 width보다 작거나 같은 표준 후보만 생성한다.
- 생성 가능한 표준 후보가 하나도 없으면 원본에서 가능한 최대 width의 WebP 하나를
  생성하며 확대하지 않는다.
- asset READY는 해당 원본과 activeSpecVersion에서 생성 가능한 필수 role 후보와 각 role의
  `defaultSource`가 모두 DB에 반영된 상태다.
- API와 queue의 role enum은 대문자 snake case, S3 key의 role segment는 소문자 kebab case를
  canonical form으로 사용한다.

### 11.1 canonical spec manifest

manifest에는 다음 값을 모두 명시한다.

- manifest schema version과 전역 pipeline specVersion
- purpose별 필수 role 집합
- role별 aspect ratio, crop 방식과 기준점
- 후보의 exact width와 height 쌍, default width
- no-upscale fallback width와 height의 선택 및 정수 rounding 규칙
- format, quality, encoder 옵션, metadata와 color 처리
- 해당 출력을 만들 수 있는 processor revision

`specDigest`는 repository가 LF로 고정한 committed manifest 파일의 정확한 UTF-8 bytes를
SHA-256으로 계산한 lowercase hex 값이다. 기존 version 파일의 수정과 삭제, 같은 version의
재발급은 CI에서 거부하고 새 version 파일만 추가한다. Java와 Node build는 같은 파일과 JSON
Schema를 artifact에 포함하며, 양쪽 contract test가 version, digest, purpose, role과 산출 규격을
대조한다.

첫 media 구현 PR은 `.gitattributes`에 `media-specs/*.json text eol=lf`를 추가하고 CI가 artifact에
포함된 bytes의 digest를 다시 계산하도록 한다.

Spring은 processing job을 발급할 때 `media_pipeline_config`가 가리키는 canonical manifest의
version과 digest를 asset의 target snapshot과 queue request에 기록한다. worker는 packaged
manifest의 version과 digest가 request와 일치할 때만 변환하고 result에도 같은 digest를 반환한다.
Spring consumer도 target digest와 result digest를 대조한다. unknown version과 digest mismatch는
사용자 이미지의 terminal FAILED가 아니라 retry, DLQ와 운영 알람 대상으로 처리한다.

공개된 canonical manifest는 기존 active asset의 `defaultSource`와 후보 검증에도 사용하므로
append-only로 유지하며 v1에서는 제거하지 않는다. processor 실행 지원은 해당 version을 참조하는
target, currentJobId, 미완료 EPR, request와 result queue, DLQ가 모두 0인 것을 확인한 후속 배포에서만
제거할 수 있다.

새 spec은 다음 순서로 배포한다.

1. 기존 spec과 vN을 함께 지원하는 worker를 새 Lambda version 또는 alias로 먼저 배포한다.
2. 배포 artifact의 supported version과 digest를 compatibility check로 확인한다.
3. 같은 manifest를 포함한 Spring을 배포하되 current version은 기존 값으로 유지한다. 모든 serving
   API, EPR publisher와 result consumer instance가 vN version과 digest를 지원하는 release로
   교체되고 readiness와 compatibility check를 통과했으며 구 instance가 0임을 확인한다.
4. media 내부 단일 row `media_pipeline_config`의 current spec version과 digest를 atomic
   compare-and-set으로 vN에 올린다. 일반 upgrade는 `(oldVersion, oldDigest, true)`에서
   `(vN, vNDigest, true)`로 전환한다. instance별 environment 값으로 따로 활성화하지 않는다.
   spec version은 증가만 허용하며 한번 활성화한 current version은 낮추지 않고 rollback도 current
   vN을 유지한다.
5. 이전 target, currentJobId, EPR, request와 result queue, DLQ가 모두 소진되고 보존 기간이
   지났는지 확인한 뒤에만 구 processor 실행 지원을 제거한다.

worker rollback은 outstanding spec을 모두 지원하는 artifact로만 허용한다. vN active data가 생긴
뒤 vN manifest가 없는 과거 Spring binary로 단순 rollback하지 않고, append-only manifest를 포함한
forward fix 또는 hotfix를 사용한다.

Sharp native version 변경처럼 한 worker artifact에서 구 processor와 vN을 함께 지원할 수 없으면
`media_pipeline_config.issuance_enabled`를 false로 바꿔 새 processing job 발급을 먼저 중지하고
기존 target, EPR, request와 result queue, DLQ를 모두 drain한 뒤 worker와 Spring을 교체하고
새 fleet의 vN compatibility를 확인한다. 이후 config tuple을 `(oldVersion, oldDigest, false)`에서
`(vN, vNDigest, true)`로 한 번의 compare-and-set으로 전환한다. CAS가 실패하면 old pointer와
issuance false를 유지해 vN-only worker에 old spec job을 발급하지 않는다. 이 절차는 운영
runbook과 복구 검증을 통과한 경우에만 사용한다.

최초 rollout은 versioned migration에서 최초 생성한 `(v1, v1Digest, false)`를 그대로 두고 worker와 모든 Spring
instance의 v1 compatibility를 확인한 뒤 `(v1, v1Digest, true)`로 flag만 compare-and-set한다.
동일 spec의 운영 pause와 resume도 version과 digest를 바꾸지 않고 flag만 compare-and-set한다.
따라서 증가만 허용하는 대상은 spec version이고 `issuance_enabled`는 승인된 운영 절차에서만
false와 true 사이를 전환할 수 있다.

job 발급과 이를 허용하는 create, PENDING_UPLOAD complete, backfill과 upgrade transaction은
`media_pipeline_config` row를 shared lock으로 먼저 읽고 commit까지 유지한 뒤 asset을 잠근다.
config의 활성화, pause와 resume CAS는 같은 row의 exclusive lock과 조건 update로 직렬화한다.
따라서 pause CAS가 commit돼 반환될 때는 이전 true snapshot으로 시작한 transaction이 모두 끝났고,
그 이후 요청은 false를 보므로 drain 완료 뒤 old job이 새로 나타나지 않는다. S3와 SQS I/O는 이
잠금 transaction 안에서 실행하지 않으며 전체 lock 순서는 config row 다음 내부 asset ID
오름차순으로 통일한다.

## 12. S3 key와 공개 범위

### 12.1 원본

- 신규 original은 별도 private bucket에 저장한다.
- original bucket은 versioning을 활성화하고 완료 확인 시점의 version ID를 job에 고정한다.
- Block Public Access와 server-side encryption을 적용하고 public ACL을 허용하지 않는다.
- Spring은 presigned PUT 발급과 HEAD에 필요한 original 권한만, worker는 지정 version GET에
  필요한 권한만 갖는다.
- CloudFront가 original bucket을 origin으로 사용하지 않는다.
- API와 큐 결과가 original URL을 노출하지 않는다.
- key 예시: `media/originals/{assetId}/original`
- 서명된 `If-None-Match: *` 조건 때문에 같은 presigned URL의 두 번째 PUT은 412나 409로
  거부된다. 다른 원본으로 다시 시도하려면 새 asset을 생성한다.
- version reconciliation은 backfill copy의 crash 또는 race, v1 이전 object와 IAM 또는 설정
  오류로 생긴 DB 미참조 version을 방어적으로 정리한다. S3 noncurrent lifecycle을 사용하지 않고
  모든 version을 DB의 sourceVersionId와 비교해 canonical source는 current 여부와 무관하게
  보존한다.

### 12.2 파생본

- 기존 delivery bucket과 CloudFront를 사용한다.
- delivery bucket도 public access를 차단하고 CloudFront OAC를 사용한다.
- 전환 기간에는 기존 CloudFront와 OAC가 현재 legacy delivery prefix를 계속 조회할 수 있게
  유지하고 `media/renditions/*` 읽기 권한을 추가한다. 신규 media original prefix는 허용하지
  않는다.
- 전체 backfill, 신규 클라이언트 전환, legacy fallback 사용량 0과 별도 운영 승인을 모두
  확인한 뒤에만 후속 배포에서 legacy prefix 읽기 권한을 축소한다.
- key 예시:

```text
media/renditions/{assetId}/v{specVersion}/{role}/{width}.webp
```

- `Content-Type: image/webp`
- `Cache-Control: public, max-age=31536000, immutable`
- 규격 변경 시 새 version 경로를 생성하고 기존 object를 덮어쓰지 않는다.
- API는 worker가 보낸 전체 URL을 신뢰하지 않고 검증된 object key와 서버 CDN 설정으로
  URL을 구성한다.

## 13. 변환 큐 계약

### 13.1 요청

```json
{
  "contractVersion": 1,
  "jobId": "ebb9b9d8-c427-564b-a70e-0fd4e1925e5a",
  "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
  "purpose": "REVIEW",
  "specVersion": 1,
  "specDigest": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "originalKey": "media/originals/a3af.../original",
  "sourceVersionId": "version-1",
  "sourceETag": "etag-value",
  "declaredContentType": "image/jpeg",
  "declaredByteSize": 1048576
}
```

요청의 `specVersion`과 `specDigest`는 image_asset의 현재 target snapshot과 일치해야 한다.
worker가 만들 필수 role 집합은 request의 `purpose`와 canonical manifest만으로 결정한다. queue
request는 `roles`를 중복 전달하지 않는다. purpose가 manifest에 없거나 asset snapshot과 다르면
사용자 이미지 FAILED가 아니라 contract mismatch로 retry, DLQ와 운영 알람에 남긴다.
`sourceVersionId`는 빈 문자열을 허용하지 않고 UTF-8 기준 최대 1,024바이트다. `specVersion`은
DB `INT`와 UUIDv5 canonical encoding에 맞춰 1 이상 signed 32-bit 최댓값 이하로 제한한다.

### 13.2 성공 결과

```json
{
  "contractVersion": 1,
  "jobId": "ebb9b9d8-c427-564b-a70e-0fd4e1925e5a",
  "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
  "specVersion": 1,
  "specDigest": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "status": "SUCCEEDED",
  "sourceVersionId": "version-1",
  "sourceETag": "etag-value",
  "verifiedSource": {
    "mimeType": "image/jpeg",
    "byteSize": 1048576,
    "width": 3024,
    "height": 4032,
    "checksumSha256": "base64-sha256"
  },
  "renditions": [
    {
      "role": "REVIEW_PREVIEW",
      "format": "WEBP",
      "width": 135,
      "height": 135,
      "byteSize": 18342,
      "objectKey": "media/renditions/a3af.../v1/review-preview/135.webp"
    }
  ]
}
```

성공 예시는 형식 설명을 위해 rendition 한 개만 표시한다. 실제 성공 결과에는 현재 job의
필수 role과 생성 가능한 모든 width manifest가 포함돼야 한다.

`verifiedSource`는 worker가 고정된 source version을 실제 decode해 검증한 결과다.

- `mimeType`은 선언값이나 확장자가 아니라 magic bytes와 decoder로 확인한 실제 IANA MIME이다.
- `byteSize`와 `checksumSha256`은 worker가 읽은 원본 object bytes 기준이다.
- `width`와 `height`는 EXIF orientation을 적용한 뒤 사용자가 보게 되는 방향의 픽셀 크기다.
- Spring consumer는 job의 source identity를 다시 확인한 뒤 이 값을 `image_asset`에 저장한다.

### 13.3 실패 결과

```json
{
  "contractVersion": 1,
  "jobId": "ebb9b9d8-c427-564b-a70e-0fd4e1925e5a",
  "assetId": "a3af06f1-4ef2-46f8-a489-2347fb840447",
  "specVersion": 1,
  "specDigest": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "status": "FAILED",
  "sourceVersionId": "version-1",
  "sourceETag": "etag-value",
  "failureCode": "INVALID_IMAGE_DATA"
}
```

failure message에는 사용자 파일명, URL, stack trace와 원본 metadata를 넣지 않는다. worker는
실제 MIME 불일치, decode 실패, 픽셀 제한 초과처럼 재시도로 해결되지 않는 오류에만 FAILED
결과를 보낸다. 일시적인 storage, network, timeout 오류는 실패를 반환해 SQS가 재시도하게 한다.
unknown specVersion과 specDigest mismatch도 FAILED 결과로 확정하지 않고 invocation을 실패시켜
재시도와 DLQ로 보낸 뒤 운영 알람을 발생시킨다.

### 13.4 멱등성

- request queue와 result queue는 Standard queue로 두고 각각 DLQ를 연결한다. 중복과 순서
  역전을 전제로 한다.
- v1 job ID는 assetId, sourceVersionId와 specVersion으로 계산한 UUIDv5다. namespace는
  `5d167dc9-9bfd-5f4e-a7a0-46b10b4de90d`로 고정한다. UUID name bytes는 asset UUID의
  16-byte network order, sourceVersionId UTF-8 byte 길이의 4-byte big-endian signed integer,
  sourceVersionId UTF-8 bytes, specVersion의 4-byte big-endian signed integer 순서로 연결한다.
  예를 들어 assetId `a3af06f1-4ef2-46f8-a489-2347fb840447`, sourceVersionId `version-1`,
  specVersion `1`의 job ID는 `ebb9b9d8-c427-564b-a70e-0fd4e1925e5a`다. 동일 job을 재발행할
  때 DB에 저장된 같은 ID를 사용한다.
- 동일 asset, sourceVersionId와 specVersion의 job은 결정적 job ID와 object key를 사용한다.
  terminal 또는 obsolete spec은 같은 asset에서 재사용하지 않으며 재처리는 더 높은 spec으로만
  시작한다.
- DB는 `(asset_id, role, spec_version, format, width)`를 unique로 보호한다.
- worker가 같은 요청을 여러 번 처리해도 최종 object와 manifest가 같아야 한다. 고정 source와
  spec의 output bytes는 결정적이어야 하며 timestamp나 무작위 metadata를 포함하지 않는다.
  worker는 `If-None-Match: *` 또는 동등한 conditional create로 기존 key를 덮어쓰지 않는다.
  rendition PUT에는 SHA-256 S3 checksum 또는 동일 값을 담은 고정 object metadata를 기록한다.
  이미 존재한다는 응답을 받으면 동일 job의 MIME, byte size와 checksum을 HEAD로 검증해 같을
  때만 성공으로 수렴한다. 값이 다르면 immutable key 불변식 위반으로 실패와 운영 알람을 남긴다.
- Spring consumer가 같은 결과를 여러 번 받아도 DB 결과가 같아야 한다.
- 현재 target과 일치하지 않는 이전 job 결과는 무시한다. active rendition이 있는 upgrade의
  FAILED 결과는 target만 실패 처리하고 공개 READY 상태를 낮추지 않는다.
- ETag는 보안 checksum이 아니라 source version marker로만 사용한다.
- worker는 originalKey가 assetId와 허용 prefix에 맞는지 검증하고, 지정한 S3 version ID와
  `If-Match: sourceETag` 조건으로 원본을 읽는다.
- Spring consumer는 currentJobId, sourceVersionId, ETag, targetSpecVersion과 targetSpecDigest가
  모두 일치하고
  `targetProcessingStatus=PROCESSING`,
  `cleanupStatus=ACTIVE`인 결과만
  반영한다. 예상 purpose와 role, width, format, deterministic object key, `verifiedSource`의
  필수 값과 범위도 검증한다.
- consumer는 currentJobId, source identity와 target 일치를 manifest lookup보다 먼저 확인한다.
  stale 또는 superseded result는 spec을 해석하지 않고 ack와 no-op으로 끝낸다. 현재 target의
  unknown version 또는 digest mismatch만 retry, result DLQ와 운영 알람으로 보낸다.
- Spring은 DB commit 성공 후에만 result message를 ack한다.
- batch를 사용하면 실패한 item만 재시도한다.

## 14. worker 검증

worker는 선언값을 신뢰하지 않고 다음을 검증한다.

- 허용 bucket과 original prefix
- magic bytes와 실제 MIME
- 실제 decode 성공
- 파일 byte 크기
- 가로, 세로, frame별 픽셀 수와 전체 decode 픽셀 수
- 손상 파일
- frame과 page 수, v1은 APNG와 animated WebP를 포함해 format과 무관하게 animated 또는
  multi-page 입력을 거부
- EXIF orientation 보정
- GPS를 포함한 metadata 제거
- sRGB 변환
- 투명 배경 보존
- 원본보다 큰 확대 금지

최대 픽셀 수와 제한 시간은 운영 샘플 benchmark 후 확정한다.

## 15. legacy 호환과 rollout

### 15.1 읽기 우선순위

```text
media image가 READY
    -> 신규 rendition 사용

신규 media image가 PROCESSING 또는 FAILED
    -> 신규 상태 사용, raw original fallback 금지

public asset ID가 없고 legacy key가 존재
    -> 기존 CloudFront URL 사용

둘 다 없음
    -> null
```

신규 media write 트래픽은 상태 응답을 이해하는 클라이언트가 배포된 뒤 활성화한다.
따라서 신규 media asset을 위해 기존 original URL을 다시 공개하지 않는다.

### 15.2 additive 전환

1. 신규 media 테이블과 nullable public asset ID 컬럼을 추가한다.
2. asset-only row를 허용하도록 필요한 legacy key의 NOT NULL을 완화한다.
3. 기존 key 기반 upload와 domain write를 유지한다.
4. 신규 media upload와 asset ID write를 추가한다.
5. 기존 URL 필드 옆에 신규 이미지 필드를 추가한다.
6. 신규 클라이언트가 media 필드를 우선 사용한다.
7. 기존 데이터를 private original copy와 WebP 생성으로 별도 backfill한다.
8. legacy fallback 사용량과 실패를 확인한다.
9. 기존 key와 URL 제거는 별도 버전에서만 논의한다.

전체 legacy backfill 완료는 신규 계약 사용의 선행 조건이 아니다. 신규 업로드와 준비된
backfill 항목부터 점진적으로 전환하고, 미전환 항목은 `assetId=null`과 기존 URL로 계속
서비스한다. 따라서 한 번의 backfill 실패가 신규 업로드 활성화나 다른 항목의 전환을 막지
않는다.

필요한 schema 불변식은 다음과 같다.

- `media_pipeline_config`는 fixed PK `id=1`과 DB check로 최대 한 행만 허용한다. row는
  `current_spec_version`, `current_spec_digest`, `issuance_enabled`, optimistic `lock_version`과
  `updated_at`을 가진다. 첫 versioned migration에서 packaged v1 manifest와 같은 version과 digest,
  `issuance_enabled=false`로 필수 제어 행을 한 번 생성한다.
- 이 행은 업무·샘플 데이터가 아니라 환경 독립적이고 비민감한 필수 제어 데이터다.
  [DB 컨벤션](../conventions/database.md)의 최초 생성 예외와
  [ADR 0001](../adr/0001-media-module-and-image-pipeline.md)의 근거를 따른다. 서버 재시작·재배포는
  운영 중 변경된 값을 유지한다. 시작 시 초기화 코드나 repeatable migration으로 재초기화하지
  않으며, 활성화·중지·규격 변경은 검증과 승인을 거친 별도 운영 절차로만 수행한다.
- job 발급은 같은 DB transaction에서 config snapshot을 읽는다. 활성화는 이전 version과 digest를
  조건으로 한 compare-and-set이고 version 감소를 거부한다. 최초 활성화는 같은 v1 version과
  digest에서 issuance false를 true로 바꾸고, 일반 upgrade는 old version과 digest, issuance true를
  vN version과 digest, issuance true로 바꾼다. 운영 pause와 resume은 같은 version과 digest에서
  flag만 바꾼다. 활성화와 job 발급이 경합하면 job은 원자적인 old 또는 new snapshot 하나를
  사용하며 둘 다 지원 중이므로 유효하다. public web 변경 API는 두지 않는다.
- job 발급과 gated create, complete, backfill, upgrade는 config row를 shared lock으로 먼저 읽고
  commit까지 유지한다. config CAS는 exclusive lock으로 직렬화하며 lock 순서는 config 다음 내부
  asset ID 오름차순이다. pause CAS가 commit된 뒤에는 이전 true snapshot의 transaction이 남지 않아
  drain 중 old job이 뒤늦게 추가되지 않는다.
- 누락된 config row는 자동 재생성하지 않고, 원인 확인 후 승인된 운영 절차로 복구한다.
  config row가 없거나 중복됐거나 packaged manifest의 version과 digest와 일치하지 않으면 media
  job 발급을 fail-closed로 차단하고 별도 media issuance capability indicator 또는 metric을
  `DEGRADED`로 노출해 운영 알람을 보낸다. global liveness와 read-serving readiness는 유지해 기존
  result 처리, READY와 legacy 요청을 계속 제공하고 media write만 503으로 차단한다. 값은 worker,
  Spring과 클라이언트 compatibility 확인 뒤 승인된 운영 절차에서만 true로 전환한다.
- `issuance_enabled=false`는 신규 media asset create, PENDING_UPLOAD complete, backfill과 spec
  upgrade의 새 job 발급을 서비스 일시 불가로 차단한다. 새 job이 필요 없는 PROCESSING 또는 READY
  complete 멱등 재호출, 이미 발급된 job의 EPR publish, result consume, redrive와 drain, READY 이미지
  조회, legacy upload와 legacy read는 계속 동작한다.
- `restaurant_image.file_key`, `review_image.file_key`, `magazine.banner_key`,
  `magazine.thumbnail_key`는 media-backed write를 위해 nullable로 완화한다.
- restaurant image와 review image의 각 row는 legacy key 또는 public asset ID 중 최소 하나를
  가져야 한다.
- magazine banner와 thumbnail도 각 슬롯마다 legacy key 또는 public asset ID 중 최소 하나를
  가져야 한다.
- 선택 이미지인 user profile과 restaurant menu는 기존처럼 둘 다 null일 수 있다.
- domain public asset ID column에는 media table FK를 만들지 않는다.
- `image_asset.backfill_identity_hash`는 일반 업로드에서 null이고 backfill에서만 사용하는
  nullable unique 값이다.
- dummy key, private original key와 rendition key를 legacy key column에 저장하지 않는다.
- cleanup candidate와 장기 PROCESSING 복구는 `(기준시각, id)` keyset batch로 조회한다. 실제
  query predicate에 맞춰 cleanupStatus, processing 또는 binding 상태, 기준시각과 id를 포함하는
  composite index를 구현 PR에서 확정한다.

전환 중 허용되는 저장 형태는 다음과 같다.

| 형태 | legacy key | public asset ID |
| --- | --- | --- |
| 미전환 legacy | 있음 | 없음 |
| READY backfill 완료 | 있음 | 있음 |
| 신규 media | 없음 | 있음 |

기존 S3 key를 보고 role을 추측하지 않는다. backfill은 소유 테이블과 컬럼을 기준으로
purpose와 role을 결정한다. READY 전에는 domain public asset ID를 연결하지 않고 기존 legacy
URL을 사용한다. READY 뒤 domain별 runner가 association row를 잠그고 source identity와 key가
그대로인지 재검증한 transaction에서 public asset ID 연결과 BOUND claim을 함께 커밋한다.
legacy key는 전환 종료 전까지 유지한다.

backfill worker는 기존 delivery object를 private original bucket의 asset 전용 key로 복사한
뒤 목적지 version ID와 ETag를 고정한다. idempotency identity는 소유 association 종류와 내부
ID 또는 slot, source bucket과 key, source version ID 또는 확인한 ETag다. 이 값을 opaque
SHA-256으로 만든 nullable unique `backfillIdentityHash`를 image_asset에 저장해 crash와
재실행 뒤에도 같은 UNBOUND asset과 copy를 찾는다. 원시 콘텐츠 ID는 media에 저장하지 않는다.
이 asset은 인증 actor 없이 `SYSTEM_BACKFILL` origin과 owner로만 생성하며 public API에서 생성할
수 없다.

source version ID가 있으면 정확한 version을 복사하고, 없으면 HEAD에서 얻은 ETag를
`CopySourceIfMatch`로 조건부 복사한다. 412 응답은 source 변경으로 처리해 다시 HEAD하고 새
identity로 시작한다. copy에는 identity metadata를 기록하며, copy 후 DB 상태 반영 전에
중단돼도 matching destination version을 재사용한다. 중복 destination version은 DB에 고정한
canonical version을 제외하고 reconciliation으로 정리한다. 같은 legacy key가 여러
association에 연결돼 있으면 single-use binding을 위해 association별 asset을 생성한다.

source object가 누락됐거나 읽을 수 없으면 기존 domain row와 legacy key를 유지하고 public
asset ID를 연결하지 않는다. 실패 원인을 결과 보고와 지표에 남기되 다른 batch 항목은 계속
처리한다. 같은 idempotency identity로 재실행했을 때 기존 asset이나 copy가 있으면 재사용하고
중복 asset을 만들지 않는다.

신규 migration은 기존 `event_publication`도 Spring Modulith 1.4 MySQL schema에 맞게 보정한다.

- `serialized_event`: `VARCHAR(4000)`
- `listener_id`, `event_type`: `VARCHAR(512)`
- `completion_date` index 추가

내부 `MediaProcessingRequestedEvent`는 `assetId`와 `jobId`만 가진다. SQS publisher는 job
snapshot을 DB에서 조회해 queue payload를 만들며, listener ID는
`media-processing-sqs-publisher-v1`으로 고정한다. 시작 시 미완료 publication을 재전송하고
실행 중에도 오래된 미완료 건을 주기적으로 재전송한다. 완료 mode는 v1에서 `delete`다.
여러 instance의 동시 재전송은 결정적 job ID를 가진 중복 메시지로 흡수한다.

publisher가 event를 재처리할 때 asset의 currentJobId가 event jobId와 다르거나 현재 target이
없으면 해당 job은 이미 terminal 또는 superseded된 것으로 판단해 SQS를 보내지 않고 정상
반환한다. Event Publication Registry는 이 no-op publication을 완료 처리한다. currentJobId가
같고 target이 PROCESSING일 때만 immutable snapshot을 구성해 전송한다.

## 16. 검증 계약

- Security filter에서 `/api/v1/media/**`의 USER, ADMIN, ONBOARDING 인증을 모두 허용하되, media
  Service가 purpose별 허용과 거부, 소유권을 강제하고 미인증 요청은 401인지 통합 테스트한다.
- public API가 `SYSTEM_BACKFILL` asset의 생성, 조회와 claim을 허용하지 않고, trusted
  `MediaBackfillPort`만 source identity, purpose, system origin, READY, UNBOUND와 ACTIVE cleanup
  상태를 모두 만족한 asset을 연결하는지 테스트한다.
- 완료 API all-or-nothing, 중복 asset ID와 동시 완료 요청을 테스트한다.
- event publication 실제 직렬화 저장, listener 실패, 재시작과 주기 재전송, 중복 SQS 발행,
  완료 publication 삭제를 MySQL Testcontainers에서 테스트한다.
- SQS 전송 뒤 publication 완료 전 중단되고 그 사이 target이 terminal 또는 superseded된 경우,
  재시작한 publisher가 snapshot 부재를 오류로 반복하지 않고 no-op 완료하는지 테스트한다.
- source version과 ETag가 다른 stale job과 result를 거부하는지 테스트한다.
- 상태 전이, binding 전이, READY 이후 늦은 FAILED와 중복 result를 테스트한다.
- 최초 v1 처리와 READY v1에서 v2 target 처리의 상태를 구분하고, v2 부분 성공과 실패 중에도
  v1만 제공되는지, v2 전체 성공 시 active version만 원자 전환되는지 테스트한다.
- terminal v2 뒤 같은 asset의 v2 재사용과 하향 active 전환을 거부하고 v3 이상의 target만
  허용하는지 테스트한다. 동일 v2 job의 EPR 재발행과 DLQ redrive는 같은 job ID와 bytes로
  멱등 처리되는지 테스트한다.
- `media_pipeline_config`가 v5 manifest를 가리킬 때 신규 asset의 최초 target,
  lastIssuedSpecVersion, job ID와 object key가 모두 v5를 사용하고 obsolete v1을 요청하지 않는지
  테스트한다.
- admin A가 연결한 asset을 권한 있는 admin B가 교체하는 경우, USER와 ADMIN이 각 도메인
  권한으로 SYSTEM_BACKFILL association을 제거하는 경우, 현재 association에 없는 임의 asset
  retire 거부와 transaction rollback을 테스트한다.
- collection 동시 수정에서 retained, added, removed diff와 backfill association의 legacy key
  재전송이 stable association ID와 asset binding을 보존하는지 테스트한다.
- restaurant association 두 개의 순서 교환과 중복 legacy key의 multiset 매칭이 1-based
  displayOrder, unique 제약과 stable ID를 지키는지 테스트한다.
- Java publisher와 Node worker가 request, success result와 failure result의 같은 JSON Schema 또는
  golden fixture를 읽고 specDigest를 포함한 queue wire 계약을 동일하게 해석하는 테스트를 둔다.
- Java publisher와 Node worker가 모든 append-only spec manifest와 같은 manifest JSON Schema를
  읽고 version, digest, purpose별 role, exact 산출 규격을 동일하게 해석하는 계약 테스트를 둔다.
- no-upscale 경계값은 공통 golden fixture로 검증한다. `REVIEW_PREVIEW`의 270×270 원본에서는
  135와 270 width 후보를 생성하고 405는 제외한다. 원본과 같은 크기는 확대가 아니다.
- 기존 manifest 수정과 삭제는 CI가 거부하는지, current v5 request와 result의 digest가 target과
  일치하는지 테스트한다.
- unknown version과 digest mismatch가 asset을 FAILED로 바꾸지 않고 retry와 DLQ로 이동한 뒤
  compatible worker 배포 후 redrive되는지 테스트한다.
- config 최대 한 행 제약과 false 최초 생성, row 누락과 packaged digest mismatch의 fail-closed media issuance
  `DEGRADED` indicator, global liveness와 read-serving readiness 유지,
  최초 `(v1, digest, false)`에서 `(v1, digest, true)` 활성화, 일반
  `(oldVersion, oldDigest, true)`에서 `(vN, vNDigest, true)` upgrade, 동일 spec pause와 resume,
  spec version 감소 거부를 테스트한다.
- 최초 생성 후 운영 설정을 변경한 DB에서 migration을 다시 실행하거나 서버를 재시작·재배포해도
  현재 설정이 유지되는지, 누락된 제어 행을 시작 코드가 자동 재생성하지 않는지 검증한다.
- issuance disabled가 신규 media create와 PENDING_UPLOAD complete, backfill과 upgrade 발급만 막고
  PROCESSING 또는 READY complete 멱등 재호출, 기존 publish, result consume, redrive, READY와 legacy
  read를 막지 않는지 테스트한다.
- incompatible processor 교체는 old tuple과 issuance false에서 drain한 뒤 vN tuple과 true로 한
  번에 CAS되며, CAS 실패 시 old tuple과 false가 유지돼 old spec job을 vN-only worker에 발급하지
  않는지 테스트한다.
- MySQL latch 기반 동시성 테스트에서 true config shared lock을 가진 발급 transaction이 끝날 때까지
  pause CAS가 대기하고, pause commit 뒤 시작한 요청은 503이며 drain 확인 뒤 old job이 추가되지
  않는지 검증한다. config 다음 asset 순서를 어기는 lock 획득이 없는지도 검증한다.
- old active v1의 defaultSource와 후보가 v2와 v5 manifest 추가 뒤에도 같고, drain 전 구 processor
  지원 제거와 outstanding spec을 지원하지 않는 worker 또는 Spring rollback을 차단하는지 검증한다.
- MIME 위조, 손상 파일, APNG와 animated WebP를 포함한 multi-frame 입력, 과도한 frame별 픽셀과
  전체 decode 픽셀, EXIF 회전, metadata 제거, 투명 배경과 원본보다 작은 이미지 fixture를
  worker에서 검증한다.
- MySQL Testcontainers로 migration, representation check, unique 제약과 row lock 경쟁을
  검증한다.
- cleanup과 장기 PROCESSING scan은 데이터가 늘어도 OFFSET이나 full table scan을 사용하지 않고
  composite index와 keyset pagination을 타는지 MySQL EXPLAIN과 batch 경계 테스트로 검증한다.
- legacy URL projection, private original copy, source ETag 변경과 backfill 재실행 안전성을
  테스트한다.
- backfill identity 동시 생성, `CopySourceIfMatch` 412, copy 직전과 직후 process 중단,
  destination version 재사용과 READY 이후 원자적인 claim을 테스트한다.
- PENDING_UPLOAD, EXPIRED, 장기 UNBOUND와 DB 미참조 version cleanup이 BOUND 여부와 current
  여부가 다른 canonical version을 삭제하지 않는지 테스트한다.
- version A의 첫 PUT이 성공한 뒤 같은 URL의 B PUT은 412나 409로 거부되고, complete와 redrive가
  A를 canonical source로 계속 사용하는지 테스트한다.
- backfill copy crash 또는 race, v1 이전 data와 설정 오류 fixture로 주입한 DB 미참조 version만
  cleanup이 삭제하고, current 여부가 다른 canonical source는 보존하는지 테스트한다.
- FAILED BOUND는 object만 정리하고 tombstone과 리뷰 슬롯을 유지하며, 일반 업로드 FAILED
  UNBOUND는 object와 asset row를 정리하는지 테스트한다.
- FAILED BOUND object가 PURGED된 뒤 현재 association에서 제거하면 RETIRED로 바뀌고,
  PURGING 중에는 호출 transaction이 롤백되며 임의 PURGED asset ID는 거부하는지 테스트한다.
- backfill FAILED UNBOUND는 object만 정리하고 identity와 failure tombstone을 유지해 자동
  재생성이 반복되지 않는지 테스트한다. 명시적 runbook은 정리 완료를 검증해 tombstone row를
  제거하고 다음 scan이 같은 identity의 새 asset을 생성하며 기존 FAILED asset은 바꾸지 않는지
  테스트한다.
- DB에 없는 original, manifest에 없는 rendition과 현재 target PROCESSING job의 expected object를
  구분하는 reconciliation을 테스트한다. active v1, terminal failed v2와 PROCESSING v3가 함께
  있을 때 object-only reconciliation이 v1과 v3를 보존하고 v2만 정리하며, 늦은 v2 write도 다음
  실행에서 다시 정리하는지 테스트한다.
- cleanup-vs-content claim, cleanup-vs-complete와 cleanup 중 process 중단 경쟁에서 PURGING
  lease가 정상 asset 삭제를 막고 같은 token 재시도가 수렴하는지 테스트한다.
- result consumer가 PURGING asset과 현재 target이 아닌 늦은 결과를 거부하는지 테스트한다.
- presigned PUT부터 READY 응답까지 E2E와 request, result DLQ redrive를 검증한다.
- presigner가 선언 MIME과 `expectedContentLength`를 signed headers에 포함하는지 단위 테스트하고,
  같은 길이의 PUT은 성공하지만 다른 길이는 S3 signature 검증에서 거부되는지 통합 테스트한다.
  complete API도 고정된 source version의 HEAD 크기를 다시 검증한다.
- `If-None-Match: *`가 presigned signed headers와 응답 requiredHeaders에 포함되고 CORS preflight를
  통과하는지, 첫 PUT만 성공하며 동일 URL의 순차 또는 동시 재사용이 412나 409로 거부되고
  complete 또는 새 asset 발급으로 수렴하는지 실제 S3 통합 테스트로 검증한다.
- issuance pause와 config fail-closed 때 asset 생성과 새 job이 필요한 PENDING_UPLOAD 완료 요청은
  같은 retryable 503과 no-transition으로 끝나지만, 전부 PROCESSING 또는 READY인 완료 재호출은 현재
  상태를 반환하고 기존 result 처리와 READY 및 legacy read도 계속 동작하는지 검증한다.

## 17. 관측과 완료 기준

최소 관측 항목은 다음과 같다.

- PROCESSING 체류 시간과 일정 시간 이상 정체된 asset 수
- 변환 성공률과 안전한 실패 코드
- request queue와 result queue 지연
- DLQ message 수
- role별 원본 대비 byte 절감률
- role별 파생본 크기
- legacy fallback 사용량
- CloudFront cache hit ratio
- 클라이언트 이미지 로드 실패율
- 페이지별 이미지 전송량, LCP, CLS

구현 완료는 코드 배포만으로 판단하지 않는다. 대표 화면의 전송량과 LCP를 변경 전후로
비교하고, READY 실패와 DLQ 복구 절차까지 검증해야 한다.

## 18. 구현 전에 추가 확인할 값

§2.3의 Lambda runtime과 architecture, ZIP 배포, AWS SAM/CloudFormation과 GitHub Actions OIDC는
확정한 기술 선택이다. 아래는 이 선택을 실제 환경에 적용할 구체 설정과 운영 검증 항목이다.
초기 Lambda memory 1536MB, timeout 60초와 request batch size 1로 시작하며, 대표 이미지
benchmark에 따라 memory, timeout과 concurrency를 조정한다. 출력 bytes에 영향을 주는 설정을
바꾸면 §11의 `specVersion` 변경 규칙을 따른다.

- 실제 region, bucket, queue, Lambda 이름과 ARN
- 기존 bucket의 expected owner, policy, OAC의 S3/SigV4/always-signing, CORS, encryption,
  versioning과 `media/renditions/*`에 겹치지 않는 lifecycle
- 최초 original bucket versioning 활성화 뒤 첫 PUT 또는 DELETE 전 15분 대기 여부
- 결정적인 dev/prod stack 이름, environment parameter와 tag 일치 여부
- dev OIDC role의 trust policy와 private GitHub Free의 dev 배포 신뢰 경계
- dev/prod AWS 권한과 data 격리, deploy role과 CloudFormation execution role 분리 및 최소 권한
- deploy 주체에서 CloudFormation으로, CloudFormation에서 exact Lambda worker role로 이어지는 두 단계
  `iam:PassRole`, `cloudformation:RoleARN`, worker runtime role과 SQS resource 제한
- source commit과 worker build ZIP SHA-256을 prod GitHub artifact 및 운영자 change set과 대조하는 절차
- prod change set 별도 검토·실행, termination protection과 실제 alarm 수신 절차
- Lambda 초기 memory와 timeout의 적정성, concurrency 상한
- SQS visibility timeout, retention, retry, DLQ redrive 값
- WebP quality와 최대 픽셀 수
- 일반 삭제, 회원 탈퇴, 신고 이미지의 물리 삭제 보존 기간
- 운영 backfill 대상 수, 누락 object 수, 예상 비용
- actor와 purpose별 asset 생성, byte, 동시 처리와 polling 제한 값
- media event publisher 전용 executor의 pool, queue, shutdown 대기 값과 재발행 주기
- issuance 활성화 전 cleanup과 reconciliation의 보존 기간, 실행 주기, 실패 alarm과 복구 절차
