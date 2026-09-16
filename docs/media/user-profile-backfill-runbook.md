# 프로필 이미지 backfill 실행기

관련 이슈: #199, #203. [공통 backfill 계약](legacy-backfill-runbook.md)을 사용하는 `user.migration`의
임시 실행기다. 코드 배포와 이 문서는 실제 AWS 적용, 운영 backfill, 개인정보 삭제를 승인하지 않는다.

## 1. 대상과 소유 경계

- `users.deleted=false`, legacy `profile_image_key` 존재, `profile_image_asset_id` 없음인 회원만 조사한다.
  탈퇴 회원은 일반 User 조회에서 제외되는 기존 정책을 따른다. 과거 예약 표시를 위해 삭제된 식당도
  처리하는 식당 runner와 의도적으로 다르다.
- 후보 조회는 ID와 legacy key만 읽는다. 별도 사용자 목록, 전화번호, 이메일 또는 이름을 적재하지 않는다.
- 공개 API, 로그인 actor, 반복 scheduler, 신규 dependency는 추가하지 않는다.
- `MediaBackfillTarget.USER_PROFILE`의 기존 identity marker를 그대로 사용한다. 콘텐츠 소유권은
  User가 유지하며 media의 Entity/Repository를 production 코드에서 직접 참조하지 않는다.
- 기본 비활성이다. 명시적으로 opt-in한 애플리케이션의 `ApplicationReadyEvent` 이후 전용
  `userProfileBackfillExecutor`에서 한 번만 실행하고 최대 batch 수에 도달하면 멈춘다.

## 2. 조사 → 준비 → 연결

| mode | 동작 | 쓰기 |
| --- | --- | --- |
| `DRY_RUN` | 후보 읽기, S3 HEAD와 기존 media 예약 조사 | DB·S3 쓰기 없음 |
| `PREPARE` | 동일 source identity의 private original copy·변환 job 준비 | media 예약·copy·job·checkpoint |
| `ATTACH` | 현재 source 재조사 후 READY asset 연결 | User 프로필 UUID·media claim·checkpoint |

PREPARE는 변환 완료를 기다리지 않으며 기존 프로필과 legacy URL을 유지한다. READY 이후 ATTACH로
연결하고 legacy key는 제거하지 않는다. 닉네임·이름·생일·전화번호·이메일·탈퇴 상태는 변경하지 않는다.

원본을 임시 공개하거나 PROCESSING/FAILED asset으로 기존 프로필을 교체하지 않는다.
ATTACH에서 PROCESSING은 건너뛰며, 준비 완료 후 새 ATTACH run으로 재조사한다.

## 3. 실행 전 확인

1. 공통 media·worker·result pipeline의 승인된 dev E2E를 먼저 완료한다. 실패 원인별 관측 보완(#203)도
   반영되어 있어야 하며, 그전에는 운영 backfill을 실행하지 않는다.
2. SAM `BackfillAccessEnabled`와 Spring `AWS_MEDIA_BACKFILL_ENABLED`의 별도 승인을 확인한다.
3. `PREPARE` 전에는 `AWS_MEDIA_QUEUE_ENABLED=true`, `AWS_MEDIA_RECOVERY_ENABLED=true`, worker event
   source와 Spring result consumer가 활성 상태인지 확인한다. request/result queue와 DLQ 지연·오류
   alarm도 정상이어야 한다.
4. PREPARE는 DB `media_pipeline_config.issuance_enabled=true`와 배포 규격 일치가 필요하다.
   조회와 READY ATTACH는 issuance pause 상태에서도 가능하다.
5. 프로필 runner 설정을 별도로 opt-in한다. V23 migration 자체는 작업을 실행하지 않는다.
6. legacy S3 객체를 같은 key로 직접 덮어쓰지 않는다. 이후 사진 변경은 새 key를 사용한다.

S3 HEAD/copy와 User DB 잠금은 하나의 원자적 작업이 아니다. 조사 이후 source가 바뀌면
prepare의 identity 검증이나 ATTACH의 잠금 아래 key 재검증으로 이전 사진 연결을 거부한다.
단, 같은 key를 콘솔에서 직접 덮어쓰는 작업은 위 운영 통제가 필요하다.

조사·준비 도중 사용자가 탈퇴하면 미연결 private copy가 남을 수 있다. ATTACH는 탈퇴한 User를
연결하지 않으며, 미연결 파일은 승인된 cleanup/reconciliation 정책의 대상이다. 이 실행기는
탈퇴 데이터 삭제나 보존 기간을 새로 정하거나 자동 삭제하지 않는다.

## 4. 실행 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `USER_PROFILE_BACKFILL_ENABLED` | `false` | one-shot runner 활성화 |
| `USER_PROFILE_BACKFILL_RUN_ID` | 비어 있음 | PREPARE/ATTACH의 소문자 canonical UUID |
| `USER_PROFILE_BACKFILL_MODE` | `DRY_RUN` | 조사·준비·연결 중 하나 |
| `USER_PROFILE_BACKFILL_BATCH_SIZE` | `50` | 1~500개 후보 |
| `USER_PROFILE_BACKFILL_MAX_BATCHES` | `10` | 한 기동당 1~1,000 batch |
| `USER_PROFILE_BACKFILL_LEASE_DURATION` | `5m` | 30초~30분 |
| `USER_PROFILE_BACKFILL_MAX_ATTEMPTS` | `3` | storage 일시 장애의 총 시도 횟수, 1~5 |
| `USER_PROFILE_BACKFILL_RETRY_INITIAL_DELAY` | `200ms` | 0~10초, 지수 backoff 시작값 |

- 프로필 슬롯만 처리하므로 target 선택 설정은 없다.
- PREPARE와 ATTACH는 서로 다른 run ID를 사용한다. 같은 run ID의 mode는 바꾸지 못한다.
- 여러 replica에는 같은 run ID를 사용한다. 유효한 lease를 얻은 한 실행기만 처리한다.
- 같은 mode를 서로 다른 run ID로 동시에 실행하지 않는다. User 잠금과 media claim이 이중 연결을
  막더라도 불필요한 source 읽기·copy와 경합 비용이 발생한다.
- DRY_RUN은 checkpoint 없이 단일 조사 환경에서 수행한다.
- 지수 backoff의 총 대기 예산은 lease보다 짧아야 한다. source 지연까지 포함해 만료되면
  현재 연결 transaction을 롤백하고 다음 기동에서 같은 run ID로 재개한다.

## 5. 중단·복구와 정합성

V23의 `user_profile_backfill_checkpoint`는 user 소유 실행 기록이다. 다른 모듈 FK, media DB join,
PII, legacy key, asset UUID 또는 source hash를 저장하지 않는다. run ID·진행 ID 범위·lease·집계만 둔다.

- 최초 후보 User ID 상한을 고정하고 `id > cursor AND id <= upper_bound`로 순회한다.
  이후 생성된 회원은 다음 run에서 조사한다. 이미 있던 기본 프로필의 변경도 재조사가 필요할 수 있다.
- PREPARE는 항목별로 진행 위치를 커밋한다. copy 이후 중단되어도 같은 identity의 예약·copy·job을 재사용한다.
- ATTACH는 User 잠금 → 활성 상태·legacy key·기존 UUID 재검증 → media READY claim → cursor 갱신을
  같은 transaction에서 처리한다. 연결·claim·cursor 중 하나라도 실패하면 모두 롤백한다.
- 탈퇴나 source 변경이 먼저 커밋되면 해당 항목을 SKIPPED로 기록한다. 새로운 사진으로 덮어쓰지 않는다.
- checkpoint 잠금 뒤 별도 SQL의 DB 시각으로 lease 만료를 판단한다. 대기 전에 읽은 시각으로 연장하지 않는다.
- 최대 batch 이후 정상 중단은 PAUSED다. 중단 요청이 거절되면 LEASE_LOST로 보고한다.
  같은 run ID로 다음 기동에서 이어간다. 강제 종료로 RUNNING이 남으면
  만료 후 새로운 token으로 인계받고, 이전 실행기는 진행 위치를 갱신하거나 새 lease를 해제할 수 없다.
- COMPLETED는 **정해진 ID 범위의 순회 완료**다. 전체 프로필 전환 완료를 뜻하지 않는다.
  변환 대기·실패·동시 수정으로 남은 항목은 원인을 확인하고 새로운 run에서 처리한다.
- 종료 시 executor의 graceful-stop 대기 이후 진행 중 작업은 interrupt 대상이다. 커밋되지 않은
  작업은 롤백 또는 멱등 재시도로 복구하며 lease 해제가 불가능하면 만료를 기다린다.

현재 별도 프로필 수정 API는 없다. 향후 프로필 수정·탈퇴 경로를 추가할 때에도 User 행의
동시 변경과 media claim/retire 경계를 함께 검토해야 한다. 이 작업에서 해당 API를 추가하지 않는다.

## 6. 관측과 실패 대응

로그에는 mode·상태·집계·고정 실패 코드와 오류 클래스명만 남긴다. 원시 User ID, key, asset UUID,
hash, 개인정보 또는 예외 payload를 로그나 metric label에 넣지 않는다.
PREPARE/ATTACH의 DB 집계는 run 전체 누적값이며 다음 조회로 확인한다.

```sql
SELECT mode, status, scanned_count, prepared_count, attached_count, skipped_count, failed_count
FROM user_profile_backfill_checkpoint
WHERE run_id = ?;
```

PREPARED는 변환 완료가 아니라 준비 단계 처리 수다. SKIPPED는 미준비 또는 변경된 프로필,
FAILED는 source 오류 또는 terminal media 상태다. `STORAGE_UNAVAILABLE`만 정해진 횟수 내에서
재시도하며 마지막 시도도 실패하면 현재 cursor를 전진시키지 않고 실행을 중단한다. DRY_RUN에서
`SOURCE_UNREADABLE`이 반복되면 source별 실패로 단정하지 말고 IAM과 암호화 권한부터 확인한다.
DB·설정·불변식 오류도 현재 cursor를 전진시키지 않고 실행을 중단한다. run ID만 바꿔 장애를
무한 반복하지 말고 원인을 확인한다.

- 결과와 종료 로그의 `sourceFailuresThisExecution`은 현재 실행에서 최종 실패한 source 항목의
  `SOURCE_MISSING`, `SOURCE_UNREADABLE`, `SOURCE_CHANGED`, `INVALID_SOURCE`, `COPY_CONFLICT`,
  `STORAGE_UNAVAILABLE`별 건수다. 빈 key는 `INVALID_SOURCE`다.
- `hashi.user.profile.backfill.source.failures` counter는 고정된 `target=USER_PROFILE`과 enum인
  `mode`, `reason`만 label로 사용한다. 재시도 도중 복구된 실패는 제외하고, 최종 실패 항목만 한 번 센다.
- 원인 집계는 DB의 누적 `failed_count`와 다르다. 이전 실행의 원인 내역과 terminal media 상태 실패는
  포함하지 않는다. PREPARE/ATTACH는 FAILED cursor 저장 성공 후 집계하며, metric 장애는
  후보 처리나 커밋 결과를 바꾸지 않는다. 지표는 운영 관측값이지 영속적인 감사 원장이 아니다.
- DRY_RUN이 DB 오류나 종료 interrupt로 중단되면 `FAILED` 부분 결과와 이미 관측한 원인을
  종료 로그에 남긴다. 이 결과는 전체 조사 완료가 아니며 interrupt flag도 유지한다.

## 7. 검증과 전환

```text
./gradlew test --tests 'org.sopt.hashi.user.migration.*' --tests '*UserProfileImageTest' --tests '*MediaBackfillBoundaryTest' --tests 'org.sopt.hashi.ModularityTests'
./gradlew clean build
```

실제 MySQL에서 V23 제약·상한·재개·lease takeover와 대기 중 만료, User/media/checkpoint 롤백,
탈퇴·source 수정·동일 슬롯 이중 연결 경쟁, keyset 실행 계획을 검증한다. 시작 이벤트와 executor
종료 테스트는 모킹한 storage를 사용한다. 운영의 종료 대기 시간이나 AWS 지연을 측정한 것은 아니다.

기존 backfill CI를 확장해 식당과 프로필 MySQL suite 모두 실행 수 > 0, 실패·오류·skip 0을 요구한다.
Docker가 없어서 건너뛴 결과는 검증 완료가 아니다.

실제 AWS dev dry-run → 제한 PREPARE → READY 확인 → 제한 ATTACH → 프로필 응답·전송량 확인은
별도 승인 후 실행한다. 운영 범위·일정 승인, legacy 제거, 원본 삭제는 이 PR에서 수행하지 않는다.

## 8. 실행 중단과 임시 권한 회수

1. 새 실행을 막기 위해 `USER_PROFILE_BACKFILL_ENABLED=false`로 배포한다. 실행 중인 background
   task는 정상 종료로 interrupt하고, checkpoint가 `PAUSED`, `COMPLETED` 또는 lease 만료 상태인지 확인한다.
2. 이미 발급된 변환은 `AWS_MEDIA_QUEUE_ENABLED=true`, `AWS_MEDIA_RECOVERY_ENABLED=true`, worker
   event source와 result consumer를 유지한 채 처리한다. target PROCESSING, 미완료 EPR,
   request/result queue와 두 DLQ가 비었는지 확인하고, 실패 항목은 원인을 분류한 뒤 복구한다.
3. 더 이상 조사·복사·연결이 없으면 `AWS_MEDIA_BACKFILL_ENABLED=false`로 배포한다.
4. SAM `BackfillAccessEnabled=false` change set을 검토·적용해 임시 source 읽기·copy 권한을 회수한다.
5. V23 checkpoint는 실행 이력과 재개 판단을 위해 유지한다. rollback 과정에서 테이블을 삭제하거나
   과거 migration을 수정하지 않는다. 이미지 pipeline 상태를 모르는 과거 바이너리로
   되돌려야 한다면 공통 인프라 runbook의 drain 조건을 먼저 만족하고, 조건이 맞지 않으면 현재 계열
   수정 release를 사용한다.
