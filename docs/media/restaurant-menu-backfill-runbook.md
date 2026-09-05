# 식당·메뉴 이미지 backfill 실행기

관련 이슈: #196, #203. [공통 backfill 계약](legacy-backfill-runbook.md)을 사용하는 식당 모듈의
임시 migration 실행기다. 이 문서는 실행 방법과 중단·복구 기준이며, 실제 AWS 적용이나
운영 backfill 실행을 승인하지 않는다.

## 1. 실행 범위

- 대상은 `restaurant_image.file_key`와 `restaurant_menu.image_key` 중 legacy key가 있고
  `image_asset_id`가 없는 association이다. soft-delete 식당도 과거 예약·리뷰 표시를 위해 포함한다.
- `restaurant.migration`만 `MediaBackfillPort`를 호출한다. public HTTP endpoint, 로그인 actor,
  반복 scheduler와 신규 dependency는 추가하지 않는다.
- opt-in된 애플리케이션의 `ApplicationReadyEvent` 이후 전용 단일 background thread에서
  한 번 실행한다. 일반 API readiness를 기다리게 하지 않는다.
- 기본값은 비활성화다. 제한된 batch를 마치면 종료하며 자동으로 다음 실행을 시작하지 않는다.

## 2. 세 단계를 별도로 실행한다

| mode | 수행 작업 | 쓰기 |
| --- | --- | --- |
| `DRY_RUN` | 후보 조회, S3 HEAD, 기존 media 예약 조회 | DB·S3 쓰기 없음 |
| `PREPARE` | 같은 source identity로 private original 복사와 변환 job 준비 | media 예약·copy·job·checkpoint |
| `ATTACH` | 현재 source 재조사 후 READY asset을 기존 association에 연결 | media claim·association·checkpoint |

`PREPARE`는 변환 완료를 기다리지 않는다. 준비 중에는 기존 association의 legacy 이미지가
계속 표시된다. `ATTACH`는 원본을 공개하지 않고 READY 파생본만 연결한다.

연결 후에도 legacy key, association ID, 이미지 순서, 메뉴명·설명·가격·main과 식당 삭제 상태를
보존한다. key나 기존 asset 연결이 동시 수정되었으면 덮어쓰지 않고 건너뛴다.

## 3. 실행 전 gate

1. 공통 계약과 worker·result pipeline의 dev E2E가 통과해야 한다. 실패 원인별 관측 보완(#203)도
   반영되어 있어야 하며, 그전에는 운영 backfill을 실행하지 않는다.
2. SAM `BackfillAccessEnabled=true`로 승인된 source 읽기·private original copy 권한이 필요하다.
3. Spring `AWS_MEDIA_BACKFILL_ENABLED=true`가 필요하다.
4. `PREPARE` 전에는 `AWS_MEDIA_QUEUE_ENABLED=true`, `AWS_MEDIA_RECOVERY_ENABLED=true`, worker event
   source와 Spring result consumer가 활성 상태인지 확인한다. request/result queue와 DLQ 지연·오류
   alarm도 정상이어야 한다.
5. `PREPARE`는 DB `media_pipeline_config.issuance_enabled=true`와 일치하는 배포 규격이 필요하다.
   조회와 READY `ATTACH`는 issuance pause 상태에서도 가능하다.
6. 아래 식당 runner 설정을 별도로 opt-in한다. 이 코드 추가나 migration 적용만으로는 실행되지 않는다.

backfill 동안 legacy S3 객체를 같은 key로 직접 덮어쓰지 않는다. 콘텐츠 수정은 새 key를 사용한다.
S3 HEAD와 DB 잠금은 서로 다른 시스템이므로 임의의 콘솔 덮어쓰기까지 하나의 transaction으로
묶을 수는 없다. runner는 현재 HEAD identity와 잠금 아래의 association key를 각각 재검증한다.

## 4. 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `RESTAURANT_MEDIA_BACKFILL_ENABLED` | `false` | one-shot runner 활성화 |
| `RESTAURANT_MEDIA_BACKFILL_RUN_ID` | 비어 있음 | PREPARE/ATTACH의 소문자 canonical UUID |
| `RESTAURANT_MEDIA_BACKFILL_TARGET` | `RESTAURANT_IMAGE` | `RESTAURANT_IMAGE` 또는 `RESTAURANT_MENU` |
| `RESTAURANT_MEDIA_BACKFILL_MODE` | `DRY_RUN` | `DRY_RUN`, `PREPARE`, `ATTACH` |
| `RESTAURANT_MEDIA_BACKFILL_BATCH_SIZE` | `50` | 1~500개 후보씩 조회 |
| `RESTAURANT_MEDIA_BACKFILL_MAX_BATCHES` | `10` | 한 번 기동에서 최대 1~1,000 batch |
| `RESTAURANT_MEDIA_BACKFILL_LEASE_DURATION` | `5m` | 30초~30분, 항목 커밋마다 연장 |
| `RESTAURANT_MEDIA_BACKFILL_MAX_ATTEMPTS` | `3` | storage 일시 장애의 총 시도 횟수, 1~5 |
| `RESTAURANT_MEDIA_BACKFILL_RETRY_INITIAL_DELAY` | `200ms` | 0~10초, 지수 backoff 시작값 |

- target과 mode마다 별도 run ID를 사용한다. 같은 run ID의 target/mode를 바꾸면 거부한다.
- 여러 API replica에서 실행할 때는 동일한 run ID를 배포한다. 유효한 lease를 가진 하나만 처리한다.
- 같은 target/mode에 서로 다른 run ID를 동시에 실행하지 않는다. 이 경우에도 media identity,
  Aggregate lock과 unique 제약은 중복 연결을 막지만 불필요한 copy·조회와 경합이 생긴다.
- `DRY_RUN`은 checkpoint를 만들지 않으므로 조사용 단일 실행 환경에서 수행한다.
- 설정한 지수 backoff의 총 대기 예산은 lease보다 짧아야 한다. 실제 storage 지연으로 lease가
  만료되면 갱신하지 않고 다음 실행이 같은 identity로 재개한다.
- 실제 환경값, S3 key, 원시 콘텐츠 ID와 source hash를 이슈·로그에 붙이지 않는다.

## 5. 체크포인트와 중단 복구

`restaurant_media_backfill_checkpoint`는 식당 모듈 소유다. 다른 모듈 FK나 media table join은 없다.
기술적인 일회성 실행 상태만 JDBC로 접근하며, 기존 식당·메뉴 Entity와 Repository는 유지한다.

- 최초 실행에서 association ID 상한을 고정하고 `id > cursor AND id <= upper_bound`로 순회한다.
  이후 새 association은 다음 run의 대상이다.
- `PREPARE`는 항목 처리 후 cursor를 커밋한다. copy 후 중단되어 cursor가 남지 않아도 같은
  identity로 재실행하여 기존 예약·copy·job을 재사용한다.
- `ATTACH`는 식당 Aggregate 잠금 → association key/asset 재검증 → media claim → cursor 갱신을
  같은 쓰기 transaction에서 수행한다. 하나라도 실패하면 연결·claim·cursor가 모두 rollback된다.
- cursor 갱신은 run ID와 lease token을 대조한다. DB 잠금을 얻은 뒤 새 statement의 DB 시각으로
  만료를 확인하므로 잠금 대기 전 시각으로 lease를 되살리지 않는다.
- 최대 batch 이후 정상 중단은 `PAUSED`다. 중단 요청이 거절되면 `LEASE_LOST`로 보고한다.
  실행 오류로 중단하면 결과는 `FAILED`이고 checkpoint는 재개를 위해 `PAUSED`로 남긴다.
  이때도 lease를 잃어 중단 요청이 거절되면 결과는 `LEASE_LOST`다. 재개는 같은 run ID를 사용한다.
- 강제 종료로 `RUNNING`이 남으면 lease 만료 후 같은 run ID가 인계받는다. 이전 token의 worker는
  cursor를 갱신할 수 없고, ATTACH 변경도 함께 rollback된다.
- `COMPLETED` run ID는 다시 실행하지 않는다. 변환 대기·실패·동시 수정으로 건너뛴 항목이나
  상한 이후 항목을 재조사하려면 문제를 확인한 뒤 새 run ID를 사용한다.

**scan 완료는 전체 이미지 전환 완료가 아니다.** `PROCESSING`은 이번 ATTACH에서 건너뛰고,
READY 이후 새 ATTACH run에서 연결한다. terminal identity는 자동으로 새 asset을 만들지 않는다.

종료 시 background task는 interrupt 대상이다. 현재 transaction은 성공 또는 rollback되고,
미완료 PREPARE는 멱등 재실행한다. 종료 과정에서 lease 해제가 불가능하면 만료 후 인계받는다.

## 6. 결과 확인

로그에는 고정 target·mode·상태·집계·실패 코드와 오류 클래스명만 기록한다.
원시 association ID·key·asset UUID·hash·예외 payload를 로그나 metric label에 넣지 않는다.
PREPARE/ATTACH의 DB 집계는 run 전체 누적값이며 다음 조회로 확인한다.

```sql
SELECT target, mode, status,
       scanned_count, prepared_count, attached_count, skipped_count, failed_count
FROM restaurant_media_backfill_checkpoint
WHERE run_id = ?;
```

- `prepared_count`: 미완료 copy를 준비했거나 이미 PROCESSING/READY인 항목.
- `attached_count`: 이번 run에서 READY asset을 연결한 항목.
- `skipped_count`: 아직 준비되지 않았거나 잠금 아래에서 association이 달라진 항목.
- `failed_count`: source 실패 또는 terminal media 상태인 항목.
- `DRY_RUN`은 메모리 내 `scanned/inspected/failed` 집계만 보고하며 영속 상태를 만들지 않는다.

- 결과와 종료 로그의 `sourceFailuresThisExecution`은 현재 실행에서 최종 실패한 source 항목의
  `SOURCE_MISSING`, `SOURCE_UNREADABLE`, `SOURCE_CHANGED`, `INVALID_SOURCE`, `COPY_CONFLICT`,
  `STORAGE_UNAVAILABLE`별 건수다. 빈 key는 `INVALID_SOURCE`다.
- `hashi.restaurant.media.backfill.source.failures` counter는 고정 enum인 `target`, `mode`,
  `reason`만 label로 사용한다. 재시도 도중 복구된 실패는 제외하고, 최종 실패한 항목만 한 번 센다.
- 원인 집계는 DB의 누적 `failed_count`와 다르다. 이전 실행의 원인 내역과 terminal media 상태 실패는
  포함하지 않는다. PREPARE/ATTACH는 FAILED cursor 저장 성공 후 집계하며, metric 장애는
  후보 처리나 커밋 결과를 바꾸지 않는다. 지표는 운영 관측값이지 영속적인 감사 원장이 아니다.
- DRY_RUN이 DB 오류나 종료 interrupt로 중단되면 `FAILED` 부분 결과와 이미 관측한 원인을
  종료 로그에 남긴다. 이 결과는 전체 조사 완료가 아니며 interrupt flag도 유지한다.

`STORAGE_UNAVAILABLE`만 제한된 지수 backoff로 재시도하며, 마지막 시도도 실패하면 현재 항목의
cursor를 전진시키지 않고 실행을 중단한다. 다른 source 실패는 해당 항목을 기록하고 진행한다.
DRY_RUN에서 `SOURCE_UNREADABLE`이 반복되면 source별 실패로 단정하지 말고 IAM과 암호화 권한부터
확인한다. DB·설정·media 불변식 오류도 현재 항목 cursor를 전진하지 않고 실행을 중단한다.
장애를 해결하지 않은 채 run ID만 바꿔 반복 실행하지 않는다.

## 7. 검증과 운영 전환

```text
./gradlew test --tests 'org.sopt.hashi.restaurant.migration.*' --tests '*RestaurantImageMediaTest' --tests '*MediaBackfillBoundaryTest' --tests 'org.sopt.hashi.ModularityTests'
./gradlew clean build
```

MySQL Testcontainers에서 V22 제약, checkpoint 재개·lease fencing, 잠금 대기 후 만료,
Aggregate/media/checkpoint rollback, 관리자 동시 수정, soft-delete 포함 keyset과 query plan을 검증한다.
hosted CI는 Docker 사용 가능 여부와 해당 MySQL suite의 skip 0을 별도로 확인한다.

이 검증은 실제 AWS source·IAM·SQS·CloudFront E2E나 운영 backfill을 대체하지 않는다.
dev dry-run → 제한 PREPARE → 변환 READY 확인 → 제한 ATTACH → 응답·성능 확인 순서로 검증한 뒤,
운영 실행 범위와 일정을 별도 승인받는다. legacy 필드 제거와 원본 삭제는 이 작업의 범위가 아니다.

## 8. 실행 중단과 임시 권한 회수

1. 새 실행을 막기 위해 `RESTAURANT_MEDIA_BACKFILL_ENABLED=false`로 배포한다. 실행 중인 background
   task는 정상 종료로 interrupt하고, checkpoint가 `PAUSED`, `COMPLETED` 또는 lease 만료 상태인지 확인한다.
2. 이미 발급된 변환은 `AWS_MEDIA_QUEUE_ENABLED=true`, `AWS_MEDIA_RECOVERY_ENABLED=true`와 result
   consumer를 유지한 채 처리한다. target PROCESSING, 미완료 EPR, request/result queue와 두 DLQ가
   비었는지 확인하고, 실패 항목은 원인을 분류한 뒤 복구한다.
3. 더 이상 조사·복사·연결이 없으면 `AWS_MEDIA_BACKFILL_ENABLED=false`로 배포한다.
4. SAM `BackfillAccessEnabled=false` change set을 검토·적용해 임시 source 읽기·copy 권한을 회수한다.
5. V22 checkpoint는 실행 이력과 재개 판단을 위해 유지한다. rollback 과정에서 테이블을 삭제하거나
   과거 migration을 수정하지 않는다. 이미지 pipeline 상태를 모르는 과거 바이너리로 되돌려야 한다면
   공통 인프라 runbook의 drain 조건을 먼저 만족하고, 조건이 맞지 않으면 현재 계열 수정 release를 쓴다.

설계 근거: [MySQL 현재 시각 함수](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html),
[동일 DataSource의 JPA·JDBC transaction 참여](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html).
