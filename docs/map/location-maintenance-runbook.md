# 기존 식당 위치 보완과 보존 기한 관리 (#228)

## 1. 먼저 알아둘 범위

이 도구는 기존 식당을 기존 [위치 worker](location-jobs.md)에 연결한다. 주소와 관광 지역을
추측하지 않는다. 식당 이름·주소·관광 지역·메뉴·이미지·태그를 변경하지 않는다.
Google 호출과 자동 재시도는 #224 worker, HTTP는 #222 adapter 한 곳에서 처리한다.

운영 Google 계정·계약·청구·키·허용 보존 기간·호출 예산·관광 지역 매핑·백업 정책은
확정되지 않았다. 아래 값과 테스트는 합성 예시다. 실제 일괄 실행·삭제·배포·merge는 수행하지 않았다.
실행 전 대상 확인 결과, 실제 계약, 비용 한도, 보존 절차와 별도 운영 승인을 확인한다.

## 2. 실행 파일과 연결

기존 `./gradlew bootJar`의 `build/libs/app.jar`에 CLI도 들어 있다. 일반 서버와 CLI의 시작점은 다르다.
CLI는 web, Flyway, worker, scheduler, Google adapter, Redis, SQS를 부트스트랩하지 않는다.
먼저 배포 절차로 V30까지 migration을 적용해야 한다. CLI는 schema를 `validate`만 한다.
`ddl-auto=create` 등의 입력은 validate로 덮어쓰며 SQL 초기화도 하지 않는다.

접속값은 승인된 환경의 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`로 주입한다. 키나 DB 접속값을 명령 예시·출력·PR에 적지 않는다.
일반 서버의 `application.yml` 대신 `location-maintenance.yml`을 읽는다.
추가 설정 파일을 쓰면 접근 권한을 제한하고 저장소에 커밋하지 않는다.
확인용 DB 계정은 SELECT 권한만 부여한다. 변경용 계정은 별도로 승인한다.

PowerShell에서 아래 함수를 만든 뒤 실행할 수 있다. Java 21을 사용한다.

```powershell
function Invoke-LocationMaintenance {
    & java '-Dloader.main=org.sopt.hashi.restaurant.migration.LocationMaintenanceCli' `
        -cp build/libs/app.jar org.springframework.boot.loader.launch.PropertiesLauncher @args
    if ($LASTEXITCODE -ne 0) { throw '위치 관리 명령 실패: 승인된 설정과 DB 상태 확인 필요' }
}
```

CLI가 실패하면 안전한 고정 오류 문구와 exit code 1을 반환한다. SQL/주소/provider 원문을 출력하지 않는다.
중단된 실행을 재시도할 때 run ID를 새로 발급하지 않고 DB 상태부터 확인한다.

## 3. 대상 확인 → 검토

```powershell
Invoke-LocationMaintenance --hashi.map.maintenance.command=DRY_RUN `
    --hashi.map.maintenance.after-id=0 --hashi.map.maintenance.batch-size=50 `
    --hashi.map.maintenance.max-batches=2
```

이 명령은 DB 쓰기·enqueue·Google 호출을 하지 않는다. 출력에는 다음이 있다.

- `asOfUtc`: 실제 MySQL UTC 기준 시각. JVM 시각이나 JDBC 세션의 현지 시각을 사용하지 않는다.
- `afterId`, `upperId`: `afterId < restaurant.id <= upperId`. 생략한 upperId는 시작 시 MAX(id)로 고정한다.
- `lastInspectedId`, `inspected`, `inspectionLimit`, `partial`: 읽은 범위와 제한.
  partial=true이면 집계는 읽은 행만 의미한다. 이어 볼 때 after-id를 lastInspectedId로 지정한다.
- 분류: UNRESOLVED(위치 행 없음), VALID, REFRESH_DUE, EXPIRED, PENDING, RETRY_WAIT,
  REVIEW_REQUIRED, FAILED, DELETED. 분류의 합은 inspected다. 좌표가 없는 활성 식당에는
  UNRESOLVED뿐 아니라 PENDING/RETRY_WAIT/REVIEW_REQUIRED/FAILED도 포함되지만 자동 보완 대상은 UNRESOLVED다.
- `googlePurgeDueInInspectedRows`, `deletedGoogleInInspectedRows`: 앞 분류와 겹치는 보존 확인 수다.
  합계에 다시 더하지 않는다. deleted 식당의 Google 결과도 보존 기한 대상이다.
- `mode`, `eligibleByLocationStateInInspectedRows`: 선택한 BACKFILL/REFRESH의 상태·수명 기준에 맞는 수다.
  `registrationEstimateInInspectedRows`는 여기에 등록/호출 한도를 적용한 예상치이고,
  `reservedCallCeilingForEstimate`는 예상 등록 수 × 8이다. 읽지 않은 행은 추정하지 않으며
  실제 잠금 아래 처리 중 작업·상태를 다시 확인하면 등록 수가 더 줄어들 수 있다.

주소·좌표는 조회 projection에 포함하지 않는다. ID keyset + 작은 scalar projection을 사용하며
offset 증가나 JPA 연관관계 N+1을 만들지 않는다. bounded read-only snapshot이므로 확인 결과는
그 시점의 정보다. 등록 직전에는 잠금 아래 삭제·주소·상태를 다시 확인한다.

## 4. 제한 실행

검토한 upper ID를 반드시 명시한다. 예시의 200은 합성 값이다.

```powershell
$locationRun = [guid]::NewGuid().ToString()
Invoke-LocationMaintenance --hashi.map.maintenance.command=START `
    --hashi.map.maintenance.execute=true --hashi.map.maintenance.mode=BACKFILL `
    "--hashi.map.maintenance.run-id=$locationRun" --hashi.map.maintenance.upper-id=200 `
    --hashi.map.maintenance.max-registrations=10 --hashi.map.maintenance.max-calls=80 `
    --hashi.map.maintenance.batch-size=50 --hashi.map.maintenance.max-batches=2
```

START/RESUME/STOP/PURGE는 `execute=true`가 없으면 변경 전에 거부한다. START는 run ID, 범위,
기준 시각, 갱신 기준 시각, 등록/호출 한도를 DB에 저장한다. 동일 run ID의 START는 이어서 진행하되
기존 범위·한도 변경은 거부하고 STOP을 해제하지 않는다. 신규 ID가 upper ID 밖에 생겨도 포함하지 않는다.

한 CLI 실행은 최대 batch-size × max-batches 건을 검사한다(각각 최대 100).
식당 한 건마다 짧게 commit하므로 실제 쓰기 transaction에는 식당 한 건만 들어간다.
checkpoint·enqueue·run-job 연결은 같은 transaction이다. 실패하면 모두 rollback한다.
마지막 commit 직후 프로세스가 죽어도 다음 실행은 그 다음 ID부터 시작한다.
run 자체에 긴 lease가 없어 프로세스 종료 후 lease 만료를 기다릴 필요가 없다.
Google worker의 기존 2분 lease·복구·최대 attempt는 그대로 사용한다.

### 호출 상한 계산

`등록 가능 수 = min(max-registrations, floor(max-calls / 8))`이다.
#224는 앱 설정의 max-attempts를 하드 최대 8로 제한한다. 자동 재시도는 같은 job 행의 attempt를
이어가므로 run에 연결한 job N개의 예약 시도 합은 최대 8N이다. 기본 설정 4회를 쓰더라도
재시작 때 8회로 바뀔 수 있어 8회를 기준으로 보수적으로 등록한다. 0~7회 예산은 0건 등록이다.
예: 80회/10건이면 최대 10건, 15회/10건이면 최대 1건이며 남은 7회는 쓰지 않는다.

전역 DB daily_limit·max_concurrent·blocked_until도 모든 claim에 별도로 적용된다.
예약 후 실제 전송이 없거나 HTTP 완료 전에 서버가 죽어도 예산을 환급하지 않는다.
`reservedAttempts`는 예약된 시도 수이며 실제 과금 호출 수라고 단정하지 않는다.
관리자가 따로 재처리한 새 job은 기존 run에 연결하지 않는다. 그 호출은 해당 run 상한 외이며
전역 예산에 포함된다. 운영자는 동시에 실행하는 다른 run·관리자 변경의 총량도 확인해야 한다.

BACKFILL은 위치 행이 없는 활성 식당만 등록한다. REFRESH는 갱신 범위의 GOOGLE_GEOCODING/READY만
등록한다. PENDING/RETRY_WAIT은 기존 worker가 맡고 FAILED/REVIEW_REQUIRED는 자동 재등록하지 않는다.
재개하거나 새 run을 만들어 실패한 job의 시도 상한을 초기화하지 않는다.

## 5. 상태 확인 → 중단/재개

```powershell
Invoke-LocationMaintenance --hashi.map.maintenance.command=STATUS "--hashi.map.maintenance.run-id=$locationRun"
Invoke-LocationMaintenance --hashi.map.maintenance.command=STOP --hashi.map.maintenance.execute=true `
    "--hashi.map.maintenance.run-id=$locationRun"
Invoke-LocationMaintenance --hashi.map.maintenance.command=RESUME --hashi.map.maintenance.execute=true `
    "--hashi.map.maintenance.run-id=$locationRun"
```

| 출력 | 의미 |
|---|---|
| ACTIVE | 등록 진행 가능. CLI 단위 한도에 도달하면 이 상태로 끝날 수 있다. 다음 RESUME이 같은 범위를 계속한다 |
| STOPPED | 운영자가 신규 등록을 중단함 |
| SCANNED | 고정 ID 범위의 검사가 끝남. Google 완료를 의미하지 않음 |
| LIMIT_REACHED | 등록/호출 한도 때문에 범위를 다 검사하지 못함. 재개로 한도를 늘리지 않음 |
| enqueued | 이 run이 commit한 job 수 |
| jobStates | 연결한 job별 PENDING/LEASED/RETRY_WAIT/SUCCEEDED/FAILED/REVIEW_REQUIRED/SUPERSEDED 수 |
| currentlyUsableLocations | 현재 같은 revision/request이고 삭제되지 않았으며 아직 유효한 READY 수 |

SUCCEEDED는 이력상 성공이다. 이후 주소가 바뀌거나 만료되면 currentlyUsableLocations에서는 제외한다.
연결한 job이 수동 DB 변경으로 없어졌으면 MISSING_JOB으로 드러나며 성공으로 세지 않는다.

STOP은 run 잠금을 먼저 잡는다. 앞서 시작한 한 건의 commit을 기다릴 수 있으며 STOP commit 이후
같은 run의 신규 등록은 없다. 이미 enqueue된 작업이나 claim된 HTTP는 취소하지 않는다.
전체 새 claim을 막으려면 별도로 승인된 DB 제어 절차에서 `restaurant_geocoding_budget.enabled=false`로
변경한다. 이미 진행 중인 HTTP에는 소급 적용되지 않는다. 부모 잠금이나 transaction을 HTTP 동안 유지하지 않는다.

## 6. 갱신과 실제 DB 정리

만료 전 갱신은 START의 mode를 REFRESH로 지정한다. 기본 refresh-ahead=1d는 합성 기본값이며
운영에서 허용한 retention보다 짧고 purge-ahead보다 길게 정한다. run 기준 시각 + refresh-ahead까지
만료하는 READY를 선택한다. 저장된 실제 수명보다 refresh-ahead가 짧지 않으면 갱신 등록을 생략한다.
run 시작 후 얻은 새 결과도 제외하여 겹치는 run이 방금 성공한 결과를 다시 등록하지 않는다.
`Restaurant.refreshLocation()`이 먼저 PENDING으로 전환하면서
이전 좌표·source·obtainedAt·validUntil을 비우고, 기존 enqueue 흐름으로 연결한다.
갱신 실패로 이전 validUntil을 늘리거나 이전 좌표를 되살리지 않는다.
정기 갱신 실행 시각·검토·등록/호출 한도는 운영 배치 담당자가 명시적으로 관리한다.

```powershell
Invoke-LocationMaintenance --hashi.map.maintenance.command=START --hashi.map.maintenance.execute=true `
    --hashi.map.maintenance.mode=REFRESH "--hashi.map.maintenance.run-id=$([guid]::NewGuid())" `
    --hashi.map.maintenance.upper-id=200 --hashi.map.maintenance.refresh-ahead=1d `
    --hashi.map.maintenance.max-registrations=10 --hashi.map.maintenance.max-calls=80

Invoke-LocationMaintenance --hashi.map.maintenance.command=PURGE --hashi.map.maintenance.execute=true `
    --hashi.map.maintenance.purge-ahead=1h --hashi.map.maintenance.batch-size=50 `
    --hashi.map.maintenance.max-batches=2
```

PURGE는 ID 범위 대신 Google/READY의 만료 시각 오름차순으로 최대 batch-size × max-batches 건을 다룬다.
Google 호출·worker·전역 호출 예산을 켤 필요가 없다. 실제 DB의 좌표·source·obtainedAt·validUntil을
모두 NULL로 만들고 REVIEW_REQUIRED로 둔다. 식당 원본과 자식 데이터는 보존한다.
삭제된 식당도 포함한다. OPERATOR 좌표는 Google 보존 정책으로 지우지 않는다.
조회 때 수집한 revision/request/obtainedAt/validUntil을 부모 잠금 아래 다시 비교하므로
오래된 정리 작업이 동시 주소 변경이나 새로운 위치 결과를 지우지 않는다.

일반 서버의 정기 정리는 `hashi.map.maintenance.retention-enabled=true`로 별도 승인 후 활성화한다.
기본 false이며 Google 설정과 독립적이다. 전용 `location-retention` executor에서 실행하므로
media 스케줄러나 Google HTTP 대기에 막히지 않는다. 다른 전역 scheduler 설정은 변경하지 않는다.
기본 poll-delay=1m, purge-ahead=1h, refresh-ahead=1d, batch-size=50, max-batches=1이다.
설정은 `2 × poll-delay <= purge-ahead < refresh-ahead <= 30d`를 검사한다.
이 값은 새 Google 계약을 뜻하지 않는다. 실제 계약보다 짧게 정한 validUntil을 연장하지 않는다.

기한이 지난 뒤 언젠가 삭제하는 정책이 아니다. 갱신을 먼저 준비하고, 갱신이 실행되지 않거나 실패해도
기한 전 purge 여유시간에서 원본 DB 값을 제거한다. 정리 결과의 dueRemaining/overdueRemaining이 남으면
경고를 남긴다. 서버/DB/스케줄러가 여유시간보다 오래 멈추거나 backlog가 처리량을 넘으면
이 앱만으로 보존 기한을 보장할 수 없다. **그 상태는 보존 gate 실패**이며 기한 연장이 아니다.
운영에서 스케줄러 heartbeat·실패·backlog를 감시하고 여유시간 안에 별도 PURGE를 실행할 담당자와
복구 시간을 검증해야 한다. 각 쓰기 transaction은 10초 제한이 있지만 전체 장애 시간을 제한하지는 않는다.

## 7. 잠금·시각·운영 gate

- 등록: `run → restaurant → job`. worker: `restaurant → job → budget`. worker/관리자는 run을 잠그지 않는다.
  stop/resume은 run만 잠근다. 정리는 restaurant만 잠근다. run과 budget을 함께 잡거나 역순으로 잡지 않는다.
- 쓰기 단계는 READ_COMMITTED다. 부모 행을 먼저 잠그며 다른 식당 job 범위의 gap-lock 경합을 피한다.
- 새 run 시각/쿼리 기준은 DB `UTC_TIMESTAMP(6)` 문자열을 파싱한다. JDBC에는 UTC calendar 문자열을
  bind하고 DATETIME은 `getObject(LocalDateTime.class)`로 읽는다. 선행 entity의 direct LocalDateTime JDBC를 유지한다.
- 만료한 백업 값을 복구해도 `hasUsableMapLocation`과 후속 지도 조회의 validUntil 조건으로 노출을 차단한다.
  노출 차단은 DB/복제본/캐시/클라이언트/백업에서 제거했다는 증거가 아니다.
- 복구 환경은 공개 전에 만료 값 정리와 검증을 수행한다. 복제 지연, PITR/binlog, 스냅샷/백업 수명,
  클라이언트 좌표 제거와 실제 Google 계약 증거는 운영 gate에서 별도로 확인한다.
  DB DELETE/NULL 처리만으로 이 경로들까지 제거되었다고 보고하지 않는다.

## 8. 검증 범위

`LocationMaintenanceMySqlTest`는 실제 MySQL 8.4와 fake provider로 migration/validate, 재시작,
동시 run/worker, checkpoint rollback, stop 경합, 주소/삭제 경합, lease 복구, UTC/JDBC 교차 조건,
물리 정리와 백업 복구 방어, app.jar launcher의 SELECT 전용 dry-run을 검증한다.
합성 fixture의 EXPLAIN·쿼리 수는 쿼리 형태를 확인하는 자료이며 운영 데이터에서의 지연/처리량 보장이 아니다.
최종 실행 명령·XML 결과·독립 리뷰·CI와 미실행 운영 항목은 PR 검증 증거에 기록한다.
