# Legacy 이미지 backfill 기반

관련 이슈: #194. 기준 계약은 [ADR 0001](../adr/0001-media-module-and-image-pipeline.md)과
[Image Delivery Contract v1](image-delivery-contract-v1.md)이다.

이 문서는 공통 기반의 사용 경계와 후속 runner의 요구사항이다. 실제 AWS 적용이나 운영
backfill을 승인하지 않는다. 식당·메뉴의 dry-run, checkpoint와 bounded runner는
[식당·메뉴 실행기](restaurant-menu-backfill-runbook.md)를, 활성 회원의 프로필 전환은
[프로필 실행기](user-profile-backfill-runbook.md)를 따른다. 매거진 runner와 운영 전환 검증은
후속 작업이며, 리뷰 도메인 연동은 #179 병합 후 진행한다.

## 1. 소유 경계

- 일반 요청 경로는 기존 `MediaPort`를 사용한다. `MediaBackfillPort`는 association을 소유한
  도메인의 `migration` 패키지에서만 호출한다. Controller와 일반 Service에서 사용할 수 없다.
- media는 원본·파생본·상태만 소유한다. 콘텐츠 association, legacy key, 표시 순서와 checkpoint는
  소유 도메인의 책임이며 media가 다른 도메인의 Entity나 Repository를 참조하지 않는다.
- `MediaBackfillReference(target, associationId, legacyKey)`는 메모리 안의 일시적 입력이다.
  원시 콘텐츠 ID를 media DB에 기록하지 않고 association/source를 조합한 SHA-256만 보관한다.
- public web endpoint나 `SYSTEM_BACKFILL` 로그인 actor는 추가하지 않는다.

| target | 소유 source 슬롯 | purpose |
| --- | --- | --- |
| `RESTAURANT_IMAGE` | `restaurant_image.file_key` | `RESTAURANT` |
| `RESTAURANT_MENU` | `restaurant_menu.image_key` | `RESTAURANT_MENU` |
| `USER_PROFILE` | `users.profile_image_key` | `PROFILE` |
| `MAGAZINE_BANNER` | `magazine.banner_key` | `MAGAZINE_BANNER` |
| `MAGAZINE_THUMBNAIL` | `magazine.thumbnail_key` | `MAGAZINE_THUMBNAIL` |
| `REVIEW_IMAGE` | `review_image.file_key` | `REVIEW` |

target의 association/slot marker는 identity의 일부다. 이름 변경으로 기존 hash를 무효화하지
않는다. 같은 source key라도 다른 association이나 슬롯에는 별도 single-use asset을 만든다.

## 2. 조사·준비·연결을 분리한다

1. 소유 도메인이 legacy key가 있고 public asset ID가 없는 후보를 읽는다.
2. **transaction 밖**에서 `inspect(reference)`를 호출한다. S3 HEAD와 기존 예약 조회만 수행하고
   asset, copy, job이나 EPR을 생성하지 않는다. 운영 dry-run은 이 단계까지만 사용한다.
3. 승인된 실행 단계에서 **transaction 밖**의 `prepare(reference, inspected.identityHash())`를
   호출한다. source를 다시 HEAD하고 hash가 같을 때만 예약·복사·변환 요청을 준비한다.
4. `PROCESSING`이면 기존 association과 legacy key를 그대로 유지한다. 변환은 기존 worker와
   result pipeline이 담당하며, 준비 호출이 변환 완료를 기다리지는 않는다.
5. READY 연결 전에 소유 row와 source를 다시 조사한다. 이전 준비 hash와 현재 hash가 같은지
   확인한 뒤 소유 도메인의 쓰기 transaction에서 association을 잠근다.
6. 잠금 아래에서 현재 legacy key가 조사한 key와 같고 asset ID가 아직 없는지 확인한다.
   같은 transaction 안에서 `claimReady(claims)`와 association의 public asset ID 저장을 수행한다.
   어느 단계든 실패하면 claim과 association 변경을 모두 rollback한다.
7. 연결 후에도 기존 key와 표시 순서는 유지한다. 이미 같은 asset에 연결된 항목의 재실행
   건너뛰기는 소유 runner가 판단한다. Port의 중복 claim은 실패한다.

S3 HEAD·copy를 DB transaction 안에 넣지 않는다. `inspect`와 `prepare`는 외부 transaction을
거부하고, `claimReady`는 기존 쓰기 transaction이 없거나 read-only이면 거부한다.
여러 asset의 claim은 media 내부 PK 오름차순으로 잠그고 전체 검증 후 변경한다.

## 3. 실행 gate

| gate | 기본값 | 책임 |
| --- | --- | --- |
| SAM `BackfillAccessEnabled` | `false` | 임시 source 읽기·copy version 조회 권한 |
| Spring `hashi.media.backfill.enabled` | `false` | migration Port의 사용 허용 |
| DB `media_pipeline_config.issuance_enabled` | 최초 `false` | 새 asset와 변환 job 발급 허용 |

Spring 환경변수는 `AWS_MEDIA_BACKFILL_ENABLED`이고, dev IAM 배포 변수는
`MEDIA_DEV_BACKFILL_ACCESS_ENABLED`다. 이름이 비슷하지만 서로 다른 gate다.
Spring opt-in이 꺼져 있으면 backfill S3 adapter도 생성하지 않는다.

DB issuance가 pause되어도 opt-in된 환경의 조회와 READY claim, 이미 발급된 job의 EPR 재전송은
계속 가능하다. 새 예약·job은 차단한다. copy 직전에 issuance를 확인하고, copy 중 pause
경합은 DB 완료 transaction에서 다시 차단한다. 이때 남은 copy와 PENDING 예약은 resume 후
같은 identity로 이어갈 수 있다.

원본과 delivery는 별도 bucket이며 이 구현에서는 같은 region을 사용한다. 임시 S3 policy의
정확한 범위와 실제 적용 전 점검은 [인프라 문서](../../infra/media/README.md)를 따른다.
추가 policy를 켜더라도 batch가 자동 실행되는 scheduler나 public API는 없다.

## 4. 재실행과 source 변경

- identity는 association 종류·ID·슬롯과 source bucket·key, 실제 version ID 또는 ETag로 만든다.
  S3의 문자열 `null` version은 불변 version으로 취급하지 않고 ETag를 사용한다.
- 실제 version이 있으면 해당 version을 복사하고, 없으면 관찰한 ETag로 조건부 복사한다.
  ETag를 파일 내용의 SHA-256이나 항상 MD5인 값으로 간주하지 않는다.
- 조사와 준비 사이 hash가 달라지면 DB 예약 전에 `SOURCE_CHANGED`를 반환한다. copy의 412도
  같은 원인이다. runner는 원본과 association을 다시 조사해 새 hash를 확인해야 한다.
- copy에는 opaque identity metadata를 기록한다. 응답 유실·중단 뒤 재시도는 같은 asset key의
  matching destination version을 재발견한다.
- source의 user metadata·tag·S3 annotation은 승계하지 않는다. annotation은 metadata와
  별개이므로 `x-amz-object-annotation-directive: EXCLUDE`를 명시하며 복사용 annotation 권한을 추가하지 않는다.
  빈 tag로 교체하는 CopyObject 요청에는 `s3:PutObjectTagging`이 필요하므로, backfill 기간에만
  private original의 `media/originals/*` prefix로 제한해 허용한다.
- 동시 copy가 둘 이상 생겨도 DB에 처음 고정한 exact version과 결정적 job을 유지한다.
  늦은 copy가 canonical source나 job을 덮어쓰지 않는다.
- FAILED, EXPIRED, RETIRED, PURGED identity는 자동으로 새 asset을 만들지 않는다. 새 source가
  관찰되거나 별도의 재처리 정책이 승인되기 전까지 기존 tombstone을 보존한다.
- 불필요한 copy version 삭제는 DB canonical version과 대조하는 후속 reconciliation의 책임이다.
  임의의 noncurrent-version lifecycle 만료나 전체 prefix 삭제로 대체하지 않는다.

## 5. 반환 상태와 실패

`MediaBackfillInspectionInfo.asset`이 비어 있으면 아직 해당 identity의 예약이 없다.
`MediaBackfillAssetInfo`는 UUID·purpose·opaque hash·migration 상태만 전달한다.

| 상태 | runner의 판단 |
| --- | --- |
| `PENDING_COPY` | 미완료 예약. 준비 단계에서 같은 asset으로 재시도 가능 |
| `PROCESSING` | 기존 legacy 유지. 제한된 간격으로 재확인 |
| `READY` | 아직 미연결이며 cleanup ACTIVE. source/association 재검증 후 claim |
| `BOUND` | 이미 사용 중. 소유 association과 동일한 연결인지 확인 |
| `FAILED`, `EXPIRED`, `RETIRED` | 자동 재생성 금지. 결과 기록 후 별도 복구 판단 |
| `PURGING`, `PURGED` | 연결 금지. tombstone 유지 |

반환값은 snapshot일 뿐 연결 권한을 보장하는 토큰이 아니다. claim은 현재 DB 상태를 재검증한다.
만료 시각을 지난 PENDING은 조회에서 `EXPIRED`로 표현할 수 있지만 조회 자체가 DB를 갱신하지 않는다.

storage 실패는 `MediaBackfillSourceException.Reason`으로만 전달한다. 내부 AWS 예외·경로를
공개 예외의 cause에 넣지 않는다.

- `SOURCE_MISSING`, `SOURCE_UNREADABLE`: 기존 association 유지. 접근 권한이나 source 복구 후 재조사.
  source bucket 목록 권한은 부여하지 않으므로 존재하지 않는 객체도 HEAD 403으로 응답할 수 있다.
- `SOURCE_CHANGED`: 새 HEAD와 association 재확인이 필요하다. 이전 hash로 반복 실행하지 않는다.
- `INVALID_SOURCE`: 지원하지 않는 MIME·크기·위치. 유효한 source로 교체하기 전 재시도하지 않는다.
- `COPY_CONFLICT`: 목적지 version이나 metadata 불일치. 덮어쓰지 말고 운영 점검한다.
- `STORAGE_UNAVAILABLE`: 일시 장애를 분류하고 제한된 재시도·backoff를 적용한다.

개별 source 실패는 다른 batch 항목을 막지 않되, DB·설정 오류나 지속적인 권한 장애는 무한
재시도하지 않고 실행을 중단·보고한다. 원시 입력, AWS 응답, 콘텐츠 ID, object key를 로그·이슈에
남기지 않는다. 지표에는 고정 target·상태·실패 원인만 사용하며 asset ID나 hash를 label로 넣지 않는다.

## 6. Migration과 검증

최초 media schema인 V15는 SYSTEM_BACKFILL identity의 NULL을 처음부터 금지하므로 별도 후속
제약 migration을 추가하지 않는다. 다음 조회는 V15 적용 전 사전 쿼리가 아니라 적용 후 불변식
확인용이다. 결과가 0이 아니면 원인을 조사하고 별도 복구 방향을 결정한다. 약한 V15가 공유 환경에
적용된 이력이 생긴 경우에는 V15를 수정하지 않고 새로운 versioned migration을 추가한다. 현재
stacked PR의 V15는 공유 운영 환경에 적용하지 않았으며, 이 문서 작성 과정에서도 운영 DB에 조회를
실행하지 않았다.

```sql
SELECT COUNT(*)
FROM image_asset
WHERE creation_origin = 'SYSTEM_BACKFILL'
  AND backfill_identity_hash IS NULL;
```

서버 검증:

```text
./gradlew test --tests '*MediaBackfill*' --tests 'org.sopt.hashi.media.internal.backfill.*' --tests 'org.sopt.hashi.ModularityTests'
./gradlew test build
```

실제 MySQL Testcontainers에서 unique/row-lock 경합, pause, EPR 재전송, copy 이후 중단,
동시 준비, claim rollback을 검증한다. S3와 queue는 모킹하며, 이것만으로 실제 AWS IAM·copy·
CloudFront 전달 E2E가 완료됐다고 보고하지 않는다. 배포 전에는 dev의 제한된 테스트 source로
IAM과 전체 변환·연결 흐름을 별도 검증해야 한다.

식당·메뉴와 프로필 runner의 keyset batch·checkpoint·dry-run과 동시 수정 검증은 별도 실행기 문서를 따른다.
후속 작업은 매거진 runner, 안전한 cleanup/reconciliation, dev E2E와 운영 승인이다.
legacy 필드 제거와 원본 삭제는
별도 종료 조건과 승인을 충족하기 전에는 실행하지 않는다.
