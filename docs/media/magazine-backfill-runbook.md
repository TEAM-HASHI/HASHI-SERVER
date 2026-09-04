# 매거진 이미지 backfill 실행기

관련 이슈: #201. [공통 backfill 계약](legacy-backfill-runbook.md)을 사용하는 `magazine.migration`의
임시 실행기다. 코드 배포와 이 문서는 AWS 적용, 실제 backfill, 원본 삭제나 운영 전환을 승인하지 않는다.

## 1. 대상과 소유 경계

- `magazine.deleted=false`이며 대상 슬롯의 legacy key가 있고 asset UUID가 없는 항목만 조사한다.
  삭제된 매거진은 기존 일반 조회와 같은 기준으로 제외한다.
- 배너는 `MAGAZINE_BANNER`, 썸네일은 `MAGAZINE_THUMBNAIL` target과 purpose를 사용한다.
  `MediaBackfillTarget`의 기존 association/slot marker는 변경하지 않는다.
- Magazine Aggregate는 두 이미지 슬롯을, magazine 모듈은 전환 checkpoint를 소유한다.
  production 코드는 media Entity/Repository를 참조하지 않고 `MediaBackfillPort`만 사용한다.
- 후보는 매거진 ID와 대상 key만 읽는다. 제목·리다이렉트 URL 같은 표시 정보를 추가 적재하지 않는다.
- 기본 비활성이다. opt-in한 애플리케이션의 `ApplicationReadyEvent` 이후 전용
  `magazineMediaBackfillExecutor`에서 한 번 실행하며 batch 상한에 도달하면 중단한다.
- 기존 API·표시 순서·crop·크기 규격·인증 계약은 변경하지 않는다.

## 2. 조사 → 준비 → 연결

| mode | 동작 | 쓰기 |
| --- | --- | --- |
| `DRY_RUN` | keyset 후보 조회, S3 HEAD와 기존 media 예약 조사 | DB·S3 쓰기 없음 |
| `PREPARE` | 같은 source identity의 private original copy와 변환 요청 준비 | media 예약·copy·job·checkpoint |
| `ATTACH` | 현재 source 재조사 후 READY 자산만 대상 슬롯에 연결 | 슬롯 UUID·media claim·checkpoint |

PREPARE는 변환 완료를 기다리지 않으며 화면에는 기존 legacy 이미지를 유지한다.
ATTACH 후에도 두 legacy key는 제거하지 않는다. PROCESSING은 건너뛰고 준비 완료 후 새 ATTACH run에서
재조사한다. FAILED나 다른 terminal 상태의 원본을 공개하거나 새 자산으로 자동 재생성하지 않는다.

배너와 썸네일은 서로 다른 실행이다. 하나를 연결해도 다른 슬롯, 매거진 ID·제목·리다이렉트 URL·
생성 시각·삭제 상태는 보존한다. 배너 전환 완료가 썸네일 완료를 뜻하지 않는다.

## 3. 실행 전 확인

1. 공통 media·worker·result pipeline의 승인된 dev E2E를 먼저 완료한다.
2. SAM `BackfillAccessEnabled`와 Spring `AWS_MEDIA_BACKFILL_ENABLED`의 별도 승인을 확인한다.
3. PREPARE에는 DB `media_pipeline_config.issuance_enabled=true`와 배포 규격 일치가 필요하다.
   조회와 READY ATTACH는 issuance가 일시 중지된 상태에서도 가능하다.
4. 매거진 runner를 별도로 opt-in한다. V24 migration 적용만으로 작업이 시작되지 않는다.
5. legacy 객체를 같은 S3 key로 덮어쓰지 않는다. 사진 변경에는 새 key를 사용한다.

S3 HEAD/copy와 Magazine DB 잠금은 원자적이지 않다. 준비 시 source identity를, 연결 시 잠금 아래
현재 key를 재검증한다. 같은 key를 콘솔에서 직접 덮어쓰는 작업까지 막아 주지는 않는다.
준비 중 삭제·교체가 발생하면 미연결 private copy가 남을 수 있으므로 승인된 cleanup/reconciliation이
필요하다. DB rollback이 S3 copy를 삭제한다는 뜻은 아니다. 이 실행기는 파일을 자동 삭제하지 않는다.

## 4. 실행 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| `MAGAZINE_MEDIA_BACKFILL_ENABLED` | `false` | one-shot 활성화 |
| `MAGAZINE_MEDIA_BACKFILL_RUN_ID` | 비어 있음 | PREPARE/ATTACH의 소문자 canonical UUID |
| `MAGAZINE_MEDIA_BACKFILL_TARGET` | `MAGAZINE_BANNER` | 배너 또는 `MAGAZINE_THUMBNAIL` |
| `MAGAZINE_MEDIA_BACKFILL_MODE` | `DRY_RUN` | 조사·준비·연결 |
| `MAGAZINE_MEDIA_BACKFILL_BATCH_SIZE` | `50` | 1~500개 후보 |
| `MAGAZINE_MEDIA_BACKFILL_MAX_BATCHES` | `10` | 한 기동당 1~1,000 batch |
| `MAGAZINE_MEDIA_BACKFILL_LEASE_DURATION` | `5m` | 30초~30분 |
| `MAGAZINE_MEDIA_BACKFILL_MAX_ATTEMPTS` | `3` | storage 일시 장애의 총 시도 횟수, 1~5 |
| `MAGAZINE_MEDIA_BACKFILL_RETRY_INITIAL_DELAY` | `200ms` | 0~10초, 지수 backoff 시작값 |

- 배너 PREPARE, 배너 ATTACH, 썸네일 PREPARE, 썸네일 ATTACH는 각각 다른 run ID를 사용한다.
  같은 run ID로 target이나 mode를 변경하면 기존 기록을 보존하고 거부한다.
- 같은 실행의 여러 replica는 같은 run ID를 사용한다. lease를 가진 실행기만 진행 위치를 갱신한다.
- 같은 슬롯을 서로 다른 run ID로 동시에 실행하지 않는다. DB 잠금이 이중 연결을 막더라도 불필요한
  S3 요청과 경합 비용이 발생한다. 배너와 썸네일도 운영에서는 순차 실행을 우선한다.
- DRY_RUN은 checkpoint 없이 단일 조사 환경에서 수행한다.
- 지수 backoff 대기 예산은 lease보다 짧아야 한다. 외부 지연으로 lease를 상실하면 같은 run으로
  재개하되 이미 준비된 private copy와 job은 공통 media 멱등 계약으로 재사용한다.

## 5. 중단·복구와 정합성

V24 `magazine_media_backfill_checkpoint`에는 run ID·target·mode·ID 범위·lease·집계만 둔다.
다른 모듈 FK, legacy key, asset UUID, source hash, 제목·리다이렉트 URL은 저장하지 않는다.

- 최초 후보 ID 상한을 고정하고 `id > cursor AND id <= upper_bound`로 순회한다.
  상한 이후 매거진과 지나간 ID의 사진 변경은 새 run에서 재조사한다.
- ATTACH는 기존 `MagazineRepository.findByIdForUpdate`로 Magazine 행을 잠근다.
  현재 삭제 상태·key·기존 UUID 확인 후 슬롯 변경·실제 media claim·cursor를 함께 커밋한다.
  어느 단계든 실패하면 셋 모두 롤백한다.
- 관리자 수정·삭제가 먼저 커밋되면 이전 사진은 SKIPPED로 남긴다. 제목만 변경됐다면 새 제목을
  보존하고 사진 연결을 진행한다. 배너와 썸네일이 경합해도 서로의 UUID를 덮어쓰지 않는다.
- checkpoint 잠금 뒤 별도 SQL의 DB 시각으로 만료를 검사한다. 이전 token으로는 새 lease의
  진행·완료·중단 상태를 변경하지 못한다.
- 최대 batch 이후 정상 중단은 PAUSED, 다른 실행기로 lease가 인계돼 중단 요청도 실패하면 LEASE_LOST다.
- PAUSED는 같은 run ID의 다음 기동에서 이어간다. 강제 종료로 RUNNING이 남으면 만료 후 인계한다.
- COMPLETED는 고정 ID 범위의 순회 완료다. 두 슬롯의 모든 이미지가 전환됐다는 의미가 아니다.
  변환 대기·실패·동시 수정으로 남은 항목은 원인을 확인하고 새 run에서 처리한다.
- context 종료 시 executor의 graceful-stop 대기 이후 진행 중 작업은 interrupt 대상이다.
  커밋되지 않은 DB 작업은 rollback하고, lease 해제가 안 되면 만료를 기다린다.

## 6. 관측과 실패 대응

로그는 target·mode·상태·집계·오류 클래스명만 기록한다. 원시 콘텐츠 ID, key, asset UUID,
source hash와 예외 payload를 로그·이슈·메트릭 label에 넣지 않는다.

```sql
SELECT target, mode, status, scanned_count, prepared_count, attached_count, skipped_count, failed_count
FROM magazine_media_backfill_checkpoint
WHERE run_id = ?;
```

PREPARED는 변환 완료 수가 아니다. SKIPPED는 미준비 또는 변경된 슬롯, FAILED는 source 오류 또는
terminal media 상태다. `STORAGE_UNAVAILABLE`만 제한 재시도한다. DB·설정·불변식 오류는 cursor를
전진시키지 않고 중단한다. run ID를 바꿔 같은 장애를 무한 반복하지 않는다.

## 7. 검증과 전환

```text
./gradlew test --tests 'org.sopt.hashi.magazine.migration.*' --tests '*MagazineBackfillImageTest' --tests '*MediaBackfillBoundaryTest' --tests 'org.sopt.hashi.ModularityTests'
./gradlew clean build
```

실제 MySQL에서 V24 제약·재개·lease 인계·잠금 대기 중 만료, 슬롯/media/checkpoint rollback,
실제 MagazineService의 수정·삭제 경쟁, 두 슬롯 동시 연결과 각 keyset 실행 계획을 확인한다.
시작·종료 테스트는 모킹한 storage를 사용하므로 운영 종료 시간이나 실제 AWS 지연의 증거는 아니다.
CI에서는 식당·프로필·매거진 MySQL suite 모두 실행 수 > 0, 실패·오류·skip 0을 요구한다.

dev 제한 DRY_RUN → PREPARE → READY 확인 → ATTACH → 실제 응답·전송량 확인은 별도 승인 후 실행한다.
운영 배포·범위·일정 승인, legacy 제거, 원본 삭제와 머지는 이 PR 범위 밖이다.
