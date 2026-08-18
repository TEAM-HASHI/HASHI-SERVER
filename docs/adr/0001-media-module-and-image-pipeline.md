# ADR 0001: media 모듈과 이미지 변환 파이프라인

- 상태: 제안
- 결정일: 2026-08-18
- 관련 이슈: #180
- 외부 계약: [`image-delivery-contract-v1.md`](../media/image-delivery-contract-v1.md)

## 1. 배경

현재 서버는 presigned URL을 발급하고, 도메인이 S3 object key를 저장한 뒤 CloudFront
URL로 바꿔 반환한다. 업로드 완료, 실제 이미지 검증, 소유권, 처리 상태와 화면별
파생본은 관리하지 않는다.

식당 카드와 상세, 메뉴 목록과 상세, 리뷰 미리보기와 사진 상세보기가 같은 원본을
재사용하므로 작은 화면에서도 큰 원본을 전송한다. 이 문제는 API 캐시나 DB 조회 최적화가
아니라 이미지 생성과 전달 구조로 해결해야 한다.

## 2. 결정

### 2.1 모듈과 Aggregate

- 상태를 가진 지원 도메인 `media` 모듈을 추가한다.
- `ImageAsset`을 Aggregate Root로 둔다.
- `ImageRendition`은 `ImageAsset` Aggregate의 자식이다.
- media 내부의 `image_rendition.image_asset_id`에는 FK를 사용할 수 있다.
- media 내부 PK는 숫자를 사용하고 외부 공개 asset ID는 UUID를 사용한다.
- 식당, 리뷰, 메뉴, 사용자, 매거진은 공개 asset ID 값만 저장한다.
- media와 콘텐츠 도메인 사이에는 JPA 관계, DB FK, DB join을 만들지 않는다.
- `RestaurantImage`, `ReviewImage`는 소속과 표시 순서를 계속 소유한다.
- 일반 요청 경로의 콘텐츠 Service는 공개 `MediaPort`만 호출한다. association을 소유한
  도메인의 migration 전용 backfill runner만 공개 `MediaBackfillPort`를 호출할 수 있다.
  Controller와 일반 Service는 `MediaBackfillPort`를 사용하지 않는다.
- media는 식당, 리뷰, 메뉴, 사용자, 매거진 모듈을 참조하지 않는다.
- original storage, rendition storage와 transform queue는 media 내부 outbound port로 정의한다.
  media 전용 method를 기존 `shared/storage/FileStorage`에 추가하지 않는다.

`ImageAsset`이 원본과 파생본의 처리 상태를 함께 변경하는 일관성 경계이므로 별도 모듈이
적합하다. 반대로 이미지가 어느 리뷰나 식당에 속하고 몇 번째로 표시되는지는 기존
Aggregate의 업무 규칙이므로 media로 이동하지 않는다.

### 2.2 기존 upload 모듈

기존 `upload`는 전환 기간 동안 상태 없는 legacy presigned 발급 모듈로 유지한다. 여기에
asset 상태와 소유권을 추가하지 않는다.

신규 업로드는 media API가 담당한다. 두 계약을 동시에 유지하는 이유는 운영 중인 기존
key 기반 요청과 응답을 한 번에 깨뜨리지 않고 additive하게 전환하기 위해서다. 모든
화면과 기존 데이터가 전환된 뒤 upload 제거 여부를 별도 결정한다.

### 2.3 인증 actor

media는 USER, ADMIN, ONBOARDING을 구분해 업로드 목적별 권한과 소유권을 검사해야 한다.
기존 `CurrentUserProvider`는 사용자 ID 조회용이므로 이 요구를 표현할 수 없다.

auth가 공개하는 좁은 `CurrentActorProvider` 계약을 추가한다.

```java
public interface CurrentActorProvider {
    CurrentActor currentActor();
}
```

- media만 역할 구분이 필요한 업로드와 소유권 판단에 사용한다.
- 일반 사용자 도메인은 기존 `CurrentUserProvider`를 계속 사용한다.
- `CurrentActor`는 actor 유형과 auth 내부 subject를 함께 제공한다.
- ONBOARDING subject는 외부 제공자 식별자일 수 있으므로 API와 로그에 노출하지 않는다.
- USER와 ADMIN의 숫자 ID가 같아도 `ActorType`이 다르면 다른 actor다.
- legacy backfill은 인증 actor가 아니므로 임의의 ADMIN을 creator로 기록하지 않는다.
  `SYSTEM_BACKFILL`은 `CurrentActor`에 추가하는 로그인 역할이 아니라 media 내부
  `creationOrigin`과 owner marker다. actor subject는 null이고 public API에서 이 값으로 asset을
  생성하거나 조회할 수 없다.
- 온보딩 Service는 User 저장으로 ID가 생성된 뒤 같은 로컬 DB 트랜잭션에서
  `MediaPort.claimOnboardingProfile(assetId, userId)`를 호출한다.
- media는 현재 ONBOARDING actor가 원 발급자이고 PROFILE purpose, READY, UNBOUND인지 검증한
  뒤 소유 actor를 생성된 USER로 인계하고 BOUND로 바꾼다.
- User 저장이나 AuthAccount 연결이 실패하면 media claim도 함께 롤백한다.

### 2.4 영속성 모델

`image_asset`은 다음 범주의 값을 소유한다.

- 내부 숫자 PK와 외부 UUID public ID
- purpose, specVersion
- creationOrigin, creator와 current owner의 actor type, 내부 subject. 일반 업로드는 인증
  actor를 기록하고 backfill은 `SYSTEM_BACKFILL` origin과 owner, null subject를 기록한다.
- original object key, S3 version ID, ETag, 실제 MIME, bytes, width, height, checksum
- processingStatus, bindingStatus, current job ID, 안전한 failure code
- legacy backfill 재실행용 nullable unique `backfillIdentityHash`
- `cleanupStatus`, nullable `purgeToken`, `purgeStartedAt`, `objectsPurgedAt`
- 낙관적 version과 생성, 수정 시각

`image_rendition`은 media 내부 asset FK, role, specVersion, format, 실제 width와 height,
bytes와 object key를 소유한다. `(asset_id, role, spec_version, format, width)`를 unique로
보호한다.

원본의 실제 MIME, bytes, width, height와 checksum은 presigned 요청의 선언값이 아니라 worker
성공 결과의 `verifiedSource`에서만 반영한다. width와 height는 EXIF orientation 보정 후 표시
방향, bytes와 checksum은 고정된 원본 object bytes 기준이다.

media는 연결된 콘텐츠 type이나 콘텐츠 ID를 저장하지 않는다. 콘텐츠 소속은 각 도메인이
public asset ID로 소유하고, media는 single-use binding 상태만 관리한다.

## 3. 처리 흐름

```text
Client
  -> Spring media: asset 생성과 presigned PUT 발급
  -> private original S3: 원본 PUT
  -> Spring media: 업로드 완료 확인
  -> S3 HEAD 검증
  -> DB: PROCESSING 전이와 변환 요청 이벤트 기록
  -> request SQS
  -> Lambda worker: 검증, WebP 생성, delivery S3 저장
  -> result SQS
  -> Spring media consumer: rendition upsert와 READY 또는 FAILED 전이
  -> API: role별 후보 반환
  -> CloudFront: 파생본 캐시와 전달
```

### 3.1 완료 확인 API를 두는 이유

S3 ObjectCreated 이벤트만 사용하면 클라이언트가 업로드 직후 콘텐츠 등록을 시도할 때
서버가 asset 상태를 확정하기 어렵다. 또한 event notification 구성에만 업무 상태 전이를
의존하게 된다.

클라이언트가 PUT 성공 후 멱등 완료 API를 호출하고, 서버가 S3 HEAD로 발급한 key와
metadata를 확인한 다음 PROCESSING으로 전이한다. 이 API가 사용자 요청 기준의 완료
경계다. worker는 실제 MIME, decode와 픽셀을 별도로 검증한다.

v1 변환 요청의 유일한 업무 trigger는 업로드 완료 확인 API다. S3 ObjectCreated notification은
사용하지 않는다.

### 3.2 트랜잭션과 메시지 전달

- S3 HEAD, PUT, DELETE와 SQS 네트워크 호출을 DB 트랜잭션 안에서 실행하지 않는다.
- 업로드 완료 확인의 S3 HEAD가 끝난 뒤 짧은 트랜잭션에서 상태를 PROCESSING으로 바꾸고
  변환 요청 이벤트를 기록한다.
- DB commit과 SQS 발행 사이의 유실을 막기 위해 현재 dependency와 schema에 이미 포함된
  Spring Modulith Event Publication Registry를 사용한다.
- 완료 확인 transaction에서 `assetId`와 `jobId`만 가진 작은
  `MediaProcessingRequestedEvent`를 발행한다. SQS publisher는 저장된 immutable job
  snapshot을 조회해 실제 queue payload를 만든다.
- SQS publisher는 고정 ID `media-processing-sqs-publisher-v1`을 가진 `@Async`
  `@TransactionalEventListener`를 사용하되 `@Transactional`을 붙이지 않는다. 원 transaction
  commit 후 DB transaction 없이 SQS를 호출하고, 정상 반환한 뒤 Event Publication Registry가
  publication을 완료 처리한다. listener 클래스나 메서드 이름 변경이 listener ID를 바꾸지
  않게 한다.
- `@ApplicationModuleListener`는 listener 자체에 새 transaction을 부여하므로 SQS publisher에
  사용하지 않는다.
- SQS 발행 실패는 incomplete publication으로 남긴다. 애플리케이션 시작 시 미완료 건을
  재전송하고, 실행 중에도 기준 시간보다 오래된 미완료 건을 주기적으로 재전송한다.
- 여러 Spring instance가 동시에 재전송하면 중복 SQS 메시지가 생길 수 있음을 허용하고
  결정적 job ID로 무해하게 처리한다. 재전송 횟수와 최종 성공 여부를 관측한다.
- v1은 `spring.modulith.events.completion-mode=delete`를 사용해 완료 publication을 즉시
  정리한다. 감사 이력이 필요해지면 UPDATE와 주기 purge를 함께 도입하는 별도 결정으로
  변경한다.
- 결과 consumer는 DB commit 성공 후에만 메시지를 ack한다.
- 콘텐츠 연결 Service가 MediaPort로 claim하면 콘텐츠 변경과 claim은 같은 로컬 DB
  트랜잭션에 참여한다. S3 작업은 참여하지 않는다.
- 여러 asset을 claim할 때 asset 내부 ID 오름차순으로 잠근다.

SQS 전송 뒤 publication 완료 처리 전에 process가 중단되면 같은 메시지가 다시 전송될 수
있으므로 downstream 멱등성이 필요하다. 실패, 재시작, 주기 재전송, 중복 발행과 완료 삭제를
통합 테스트한다. 동작 근거는 [Spring Modulith Event Publication Registry](https://docs.spring.io/spring-modulith/reference/1.4/events.html#events.event-publication-registry)를
따른다.

### 3.3 상태와 멱등성

- request와 result SQS는 Standard queue와 DLQ를 사용하며 중복과 순서 역전을 전제로 한다.
- request와 result에 contractVersion, jobId, assetId, sourceVersionId, sourceETag,
  specVersion을 포함한다.
- jobId와 파생 object key는 같은 입력에 대해 결정적이다.
- DB는 `(asset_id, role, spec_version, format, width)` unique 제약을 둔다.
- READY가 된 asset은 늦은 이전 job의 FAILED 결과로 내려가지 않는다.
- 일시 오류는 SQS와 DLQ로 재시도하고 asset을 PROCESSING으로 유지한다. 영구 파일 검증
  오류만 FAILED로 확정한다.
- 일정 시간 이상 PROCESSING인 asset을 찾아 publication, queue와 DLQ를 대조하는 복구
  절차를 둔다.

## 4. 변환 worker

v1은 Node.js와 Sharp를 Lambda ZIP으로 배포한다.

- Spring Boot 애플리케이션과 worker는 실행 환경과 책임이 다르다.
- Node.js를 사용하는 것은 서버 전체 기술 스택을 바꾸는 것이 아니다.
- Sharp는 이미지 decode, resize, WebP encode와 metadata 제거에 특화돼 있다.
- worker 요청이 없을 때 Lambda compute 실행 비용은 발생하지 않으며, SQS, log와 storage
  비용은 별도다. API EC2의 CPU와 메모리는 사용하지 않는다.
- 현재 최대 5MB 정지 이미지와 고정 role 규격에는 Lambda 실행 모델이 적합하다.
- ZIP은 worker 코드와 Lambda Linux 호환 dependency를 묶는 배포 파일이다.
- v1에는 worker용 EC2, ECS, ECR과 운영 Docker image를 추가하지 않는다.

GitHub Actions Linux runner에서 Lambda 환경과 호환되는 Sharp package를 포함한 ZIP을 만든다.
worker source는 HASHI-SERVER 저장소 안의 별도 디렉터리와 독립 Node package로 관리한다.
Gradle과 Spring runtime dependency에는 포함하지 않고 worker 변경에만 별도 CI와 배포를
실행한다. 팀과 배포 주기가 실제로 분리될 때 별도 저장소 이전을 검토한다.

다음 조건이 생기면 Lambda container 또는 ECS worker로 전환할 수 있다.

- ZIP 크기 제한을 넘는다.
- HEIC, ImageMagick, 폰트처럼 OS dependency가 늘어난다.
- Lambda 시간과 메모리 제한에 맞지 않는 큰 작업이 생긴다.
- 지속적으로 높은 처리량 때문에 상시 worker가 더 경제적이다.

전환하더라도 queue와 media API 계약을 유지해 Spring과 클라이언트 변경을 최소화한다.

## 5. 저장과 전달

- 신규 원본은 별도 private original bucket에 저장한다.
- original bucket은 versioning을 활성화한다. 완료 확인 시점의 version ID와 ETag를 job에
  고정하고 worker는 해당 version만 읽는다.
- `media/originals/*`에는 무조건적인 `NoncurrentVersionExpiration`을 설정하지 않는다. 완료
  뒤 같은 presigned PUT으로 새 version이 생기면 job이 고정한 canonical source가
  noncurrent가 될 수 있기 때문이다. bucket lifecycle은 미완료 multipart upload 중단만
  담당한다.
- application cleanup이 object version 목록과 DB의 고정 `sourceVersionId`를 대조한다. current
  여부와 무관하게 PROCESSING, READY와 복구 가능한 asset이 참조하는 canonical source를
  보존하고, 어떤 asset도 참조하지 않는 version만 유예 기간 뒤 삭제한다.
- media write 활성화 전 유한한 보존 기간을 설정한다. 안전한 초기값은 PENDING_UPLOAD와
  EXPIRED 24시간, 신규 업로드 READY UNBOUND 24시간, backfill UNBOUND 7일, FAILED 7일,
  DB 미참조 version과 object 7일이다.
- PENDING_UPLOAD와 EXPIRED는 presigned 만료와 safety window 뒤 DB 상태를 잠가 재확인하고
  asset key의 관측된 version을 삭제한다. 아직 complete되지 않아 고정 sourceVersionId가
  없을 수 있음을 전제로 한다.
- READY UNBOUND는 canonical source와 rendition을 삭제한 뒤 asset row를 정리한다. FAILED는
  7일 뒤 raw original과 partial rendition을 삭제한다. 일반 업로드 UNBOUND FAILED는 asset
  row도 정리한다. backfill UNBOUND FAILED는 `backfillIdentityHash`, failure code와
  `objectsPurgedAt` tombstone을 유지해 같은 source를 자동으로 반복 처리하지 않는다. 리뷰에
  연결된 BOUND FAILED도 상태와 슬롯 tombstone을 유지한다.
- backfill source version ID 또는 ETag가 바뀌면 새 identity로 다시 처리할 수 있다. 같은
  identity의 재처리는 원인 해결을 확인한 운영자가 기존 failure tombstone을 명시적으로
  해제하는 runbook으로만 허용한다.
- original과 rendition prefix를 주기적으로 reconciliation한다. DB asset, rendition manifest
  또는 현재 PROCESSING job에 대응하지 않는 object는 grace period 뒤 삭제한다.
- cleanup은 S3 delete 전에 짧은 transaction에서 현재 상태와 보존 기간을 다시 확인하고
  `cleanupStatus`를 ACTIVE에서 PURGING으로 바꾸며 고유 purgeToken을 기록한다. complete,
  content claim과 backfill claim은 PURGING asset을 거부한다.
- S3 삭제는 transaction 밖에서 purgeToken 기준으로 멱등 실행하고, 별도 transaction에서
  PURGED tombstone 또는 row 삭제로 마무리한다. 중간에 process가 중단되면 오래된 PURGING을
  같은 token으로 재개한다. DB lock을 S3 호출 동안 유지하지 않는다.
- original bucket은 CloudFront origin으로 연결하지 않는다.
- 기존 S3 bucket은 WebP 파생본 delivery에 사용한다.
- 기존 CloudFront distribution을 유지한다.
- 전환 기간에는 기존 CloudFront OAC의 legacy delivery prefix 읽기 권한을 유지하고
  `media/renditions/*`를 추가 허용한다. backfill, 클라이언트 전환과 legacy fallback 사용량 0을
  확인하고 별도 운영 승인을 받은 뒤에만 legacy prefix 권한을 축소한다.
- 원본과 파생본 prefix를 분리한다. 변환 요청은 완료 확인 API에서 기록한 durable event만
  사용하며 v1에는 S3 ObjectCreated notification을 구성하지 않는다.
- 파생 object key에 assetId, specVersion, role, width와 format을 포함한다.
- 파생본은 versioned immutable object로 저장한다.

bucket 분리로 늘어나는 주된 비용은 원본과 noncurrent version 저장 bytes, 요청, Lambda,
SQS와 log다. bucket policy, CORS, lifecycle, IAM과 환경 설정도 추가된다. 원본 비공개,
version 보존과 권한 분리를 구조적으로 보장하는 이점이 운영 설정 증가보다 크다고 판단한다.

실제 bucket 이름, ARN, account, region, policy와 CloudFront ID는 저장소 문서에 기록하지
않으며 기존 운영 환경을 확인한 뒤 구성한다.

## 6. 콘텐츠 연결 정책

- 업로더 화면은 브라우저 local object URL로 즉시 미리보기한다.
- 신규 리뷰는 원본 업로드 확인 후 PROCESSING asset을 연결할 수 있다. 공개 화면은 READY
  전까지 비율이 고정된 placeholder를 표시한다.
- 이미지가 필수인 신규 식당과 매거진, 필요한 메뉴는 READY 이후 공개한다.
- 기존 이미지 교체는 새 asset이 READY된 뒤 연결하고 그전에는 기존 READY 이미지를 유지한다.
- 신규 raw original은 공개 fallback으로 제공하지 않는다.
- 기존 `assetId`가 없는 legacy 데이터만 전환 기간에 기존 URL을 사용할 수 있다.
- 교차 모듈 Port는 전환 기간에 `ImageReference(assetId, legacyUrl)`을 전달한다. legacy URL은
  key 소유 모듈이 계산하고 object key는 공개하지 않는다. 최종 응답 Service는 asset ID를 role과
  함께 bulk 조회하며, asset ID가 없고 legacy URL만 있는 경우에만 legacy fallback을 사용한다.
  asset ID가 있으면 PROCESSING, FAILED 또는 조회 불일치에도 legacy URL로 우회하지 않는다.
- 처리 상태와 별도로 `UNBOUND -> BOUND -> RETIRED` binding lifecycle을 둔다.
- 이미지 제거와 교체는 콘텐츠 변경과 같은 transaction에서 `MediaPort.retireAssets()`를
  호출한다. RETIRED single-use asset은 다시 claim할 수 없다.
- collection 전체 교체는 도메인 Aggregate를 write lock한 뒤 저장된 association과 요청을
  비교한다. 유지 항목은 그대로 두고 추가 항목만 claim하며 제거 또는 교체 항목만 retire한다.
  scalar에 같은 asset을 다시 보내는 요청은 no-op이다.
- claim과 retire 대상 asset의 합집합을 media 내부 숫자 ID 오름차순으로 잠근다. 도메인 변경과
  binding 전이는 같은 로컬 DB transaction에서 커밋해 참조되지 않는 BOUND asset을 남기지
  않는다.
- backfill로 legacy key와 public asset ID를 모두 가진 association에 같은 legacy key가 다시
  전달되면 기존 association과 asset ID를 보존한다.
- restaurant image는 현재와 같이 1부터 시작하는 displayOrder를 사용한다. stable association
  ID를 보존한 reorder는 기존 모든 row를 최종 범위 밖의 고유한 양수 임시 순서로 옮겨 flush한
  뒤 요청 배열 순서대로 1부터 재배정하고 다시 flush한다. unique 제약과 양수 불변식을 중간
  상태에서도 지킨다.
- legacy `imageKeys` 전체 교체는 중복 key를 허용하는 현재 계약을 보존한다. 기존 association을
  displayOrder 오름차순으로 두고 입력 key마다 같은 key의 아직 소비하지 않은 첫 row를 하나씩
  매칭한다. 남는 입력은 추가하고 소비되지 않은 기존 row만 제거해 backfill asset ID를
  결정적으로 보존한다.
- soft delete와 복구 가능 기간에는 BOUND를 유지한다. 물리 삭제 시점은 후속 운영 정책으로
  결정한다.

raw original을 PROCESSING fallback으로 사용하면 큰 원본과 WebP를 연속 다운로드하고,
검증 전 파일과 metadata를 공개하며 private bucket의 접근 경계를 약화한다. 처리 시간이
실제 UX 문제가 되면 검증된 작은 WebP를 먼저 만드는 first safe rendition을 후속으로
추가한다.

## 7. DB와 migration

- 신규 Flyway migration은 media 테이블과 도메인의 nullable public asset ID 컬럼을 추가한다.
- `image_asset.id`는 media 내부 숫자 PK, `image_asset.public_id`는 API와 콘텐츠 도메인이
  사용하는 UUID unique key다.
- 콘텐츠 도메인은 UUID 값을 저장하고 media 내부 숫자 PK를 저장하지 않는다.
- `image_asset.backfill_identity_hash`는 nullable unique로 두고 일반 업로드에는 사용하지 않는다.
- asset-only write에 필요한 `restaurant_image.file_key`, `review_image.file_key`,
  `magazine.banner_key`, `magazine.thumbnail_key`는 nullable로 완화한다.
- 각 필수 이미지 참조에는 legacy key 또는 public asset ID 중 최소 하나가 있어야 한다.
- 기존 key 컬럼은 전환 기간 유지하고 private original key나 dummy key를 저장하지 않는다.
- S3 object 확인과 WebP 생성 backfill은 Flyway에 넣지 않는다.
- backfill은 dry-run, checkpoint, batch, retry를 지원하는 별도 멱등 작업으로 실행한다.
- legacy role은 key 경로가 아니라 소유 테이블과 컬럼으로 결정한다.
- 신규 media upload는 public asset ID만 domain에 기록한다. backfill은 association 종류와
  내부 ID 또는 slot, source bucket, key, version ID 또는 ETag로 opaque SHA-256
  `backfillIdentityHash`를 계산한다. media는 원시 콘텐츠 ID를 저장하지 않고 이 nullable
  unique hash로 같은 작업의 UNBOUND asset을 재사용한다.
- 기존 delivery object backfill은 원본을 직접 변환하지 않는다. 소유 row 또는 슬롯, 기존
  bucket과 key, 확인한 ETag를 idempotency identity로 삼아 private original bucket의
  `media/originals/{assetId}/original`로 복사하고 목적지 version ID와 ETag를 job에 고정한다.
  source version ID가 있으면 정확한 version을 복사하고, 없으면 HEAD에서 얻은 ETag를
  `CopySourceIfMatch` 조건으로 사용한다. 412 응답은 source 변경으로 처리해 다시 HEAD하고 새
  identity로 시작한다.
  같은 legacy key가 여러 association에 재사용된 경우에도 single-use binding을 지키기 위해
  association별 asset을 만든다. source ETag가 바뀌면 기존 job을 덮어쓰지 않고 새 backfill
  대상으로 처리한다.
- copy에는 backfill identity metadata를 기록한다. copy 뒤 process가 중단돼도 같은 hash로
  asset과 matching destination version을 찾아 재사용한다. 중복 copy version은 canonical
  destination version을 DB에 고정한 뒤 reconciliation으로 정리한다.
- asset READY 뒤 domain별 backfill runner가 source identity를 다시 확인하고 association row를
  잠근다. legacy key가 그대로일 때 trusted `MediaBackfillPort`가 purpose,
  `SYSTEM_BACKFILL`, READY, UNBOUND와 `cleanupStatus=ACTIVE`를 검증하고 public asset ID 저장과
  BOUND claim을 한 transaction에서 커밋한다. 이 Port는 public web API가 사용하지 않는다.
  그전에는 asset을 UNBOUND로 두고 legacy URL만 제공한다. FAILED UNBOUND asset은 유한 보존
  기간 뒤 정리한다.
- source object가 없거나 읽을 수 없으면 domain row와 legacy key를 그대로 두고 asset ID를
  연결하지 않는다. 누락 원인은 backfill 결과와 운영 지표에 기록하며 전체 batch의 다른
  항목은 계속 처리한다.
- 모든 화면 전환과 fallback 사용량 확인 전에는 기존 URL과 key를 제거하지 않는다.
- 전체 backfill은 신규 media 계약 활성화의 선행 조건이 아니다. 신규 업로드와 READY backfill
  association부터 점진 전환하고, 미전환 legacy association은 asset ID 없이 기존 URL을 유지한다.

현재 `event_publication`의 255자 컬럼은 media event 저장에 안전하지 않다. 신규 migration은
Spring Modulith 1.4 MySQL schema에 맞춰 `serialized_event`를 4000자, `listener_id`와
`event_type`을 512자로 확장하고 `completion_date` 인덱스를 추가한다. 실제 Jackson 직렬화,
저장과 replay는 MySQL Testcontainers로 검증한다.

실제 MySQL migration과 unique 제약은 H2 create-drop 테스트로 충분히 검증할 수 없다.
구현 이슈에서 MySQL Testcontainers 추가 이유와 CI 영향을 먼저 설명하고 migration 전용
검증을 추가한다.

## 8. 리뷰 연동

리뷰 media 연동은 리뷰 수정 이슈 #179 병합 후 별도 작업으로 진행한다.

- retained review image는 수정 후에도 reviewImageId를 유지한다.
- preview와 detail은 동일 reviewImageId, assetId, displayOrder를 사용한다.
- displayOrder는 0부터 9이고 오름차순으로 반환한다.
- PROCESSING과 FAILED 슬롯도 순서에서 제거하지 않는다.
- 수정 시 모든 ReviewImage를 삭제하고 다시 만드는 방식은 안정 식별자 계약과 맞지 않으므로
  media 연동에서 선택적 유지 방식으로 변경한다.

## 9. 검토한 대안

| 대안 | 장점 | 제외 이유 |
| --- | --- | --- |
| Spring 요청 안에서 동기 변환 | 한 언어와 배포 단위 | 응답 지연, API EC2 자원 경합, 업로드 폭주가 서비스 장애로 확산 |
| 같은 EC2의 background task | 별도 서비스가 없음 | API와 자원, 배포, 장애 복구가 결합 |
| 별도 EC2 또는 ECS worker | 긴 작업과 고정 고처리량에 적합 | 현재 규모에는 상시 비용과 운영 부담이 큼 |
| Lambda container | Lambda 장점과 큰 package 지원 | v1 Sharp worker에는 ECR과 image 관리가 불필요한 부담 |
| 요청 시 동적 변환 | 임의 크기 대응과 초기 저장 절감 | 첫 요청 지연, cache key 증가, 파라미터 악용 방어가 필요 |
| Cloudinary, ImageKit | 구현과 운영 시작이 빠름 | 기존 S3와 CloudFront 중복, 지속 비용과 vendor lock-in |
| 전역 small, medium, large | 단순함 | 비율과 사용 목적이 다른 화면을 표현하지 못해 다시 원본 재사용 문제 발생 |

HASHI는 화면 role과 후보 폭이 제한돼 있으므로 업로드 후 비동기 사전 생성이 비용,
캐시 예측성, 보안과 첫 조회 지연의 균형이 가장 좋다.

## 10. 결과와 후속 결정

장점:

- 콘텐츠 도메인과 이미지 처리 lifecycle을 분리한다.
- worker 실행 방식을 바꿔도 API와 Aggregate 계약을 유지할 수 있다.
- 규격과 포맷 확장을 additive하게 처리할 수 있다.
- 신규 원본을 공개하지 않고 기존 운영 계약을 점진적으로 전환한다.

비용:

- 신규 테이블, actor 계약, queue, worker와 운영 관측이 필요하다.
- original bucket 정책과 lifecycle을 별도로 운영해야 한다.
- backfill과 legacy 제거까지 일시적으로 두 계약을 유지한다.

구현 전 별도 확인:

- 기존 AWS 리소스와 배포 인증 방식
- role별 WebP quality와 보안 제한 benchmark
- SQS와 Lambda의 실제 운영 수치
- 일반 삭제, 회원 탈퇴와 신고 이미지의 물리 삭제 보존 정책
- 운영 backfill 범위와 일정
