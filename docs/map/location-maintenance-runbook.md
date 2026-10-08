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
먼저 배포 절차로 V38 위치, V39 작업, V40 유지보수 migration을 순서대로 적용해야 한다. CLI는 schema를 `validate`만 한다.
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
이어가므로 run에 연결한 job N개의 예약 시도 합은 최대 8N이다. 기본 설정도 최초 요청을 포함해 8회다. 낮춰서 실행하더라도 재시작 후 8회가 될 수 있어
항상 8회를 기준으로 보수적으로 등록한다. 0~7회 예산은 0건 등록이다.
예: 80회/10건이면 최대 10건, 15회/10건이면 최대 1건이며 남은 7회는 쓰지 않는다.

전역 DB daily_limit·max_concurrent·blocked_until도 모든 claim에 별도로 적용된다.
예약 후 실제 전송이 없거나 HTTP 완료 전에 서버가 죽어도 예산을 환급하지 않는다.
`reservedAttempts`는 예약된 시도 수이며 실제 과금 호출 수라고 단정하지 않는다.
관리자가 따로 재처리하거나 정기 갱신으로 만든 새 job은 기존 run에 연결하지 않는다.
그 호출은 해당 run 상한 외이며
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
| currentlyUsableLocations | 현재 같은 revision/request이고 삭제되지 않았으며 아직 유효한 좌표 수 |

SUCCEEDED는 이력상 성공이다. 이후 주소가 바뀌거나 만료되면 currentlyUsableLocations에서는 제외한다.
연결한 job이 수동 DB 변경으로 없어졌으면 MISSING_JOB으로 드러나며 성공으로 세지 않는다.

STOP은 run 잠금을 먼저 잡는다. 앞서 시작한 한 건의 commit을 기다릴 수 있으며 STOP commit 이후
같은 run의 신규 등록은 없다. 이미 enqueue된 작업이나 claim된 HTTP는 취소하지 않는다.
전체 새 claim을 막으려면 별도로 승인된 DB 제어 절차에서 `restaurant_geocoding_budget.enabled=false`로
변경한다. 이미 진행 중인 HTTP에는 소급 적용되지 않는다. 부모 잠금이나 transaction을 HTTP 동안 유지하지 않는다.

## 6. 갱신과 실제 DB 정리

### 이전과 달라진 점

| 이전 | 현재 |
|---|---|
| 갱신 등록 즉시 좌표 제거 | 같은 주소의 좌표는 원래 validUntil까지 사용 |
| 갱신은 외부 CLI 주기 실행 필요 | 기존 retention 스케줄러가 갱신 등록과 만료 정리를 함께 수행 |
| 1일 전 갱신 대상으로 검사 | 기본 3일 전부터 검사 |
| READY만 정리 | 갱신·재시도·실패 중 남은 Google 좌표도 정리 |
| 정리하면서 requestId 변경 | 진행 중 작업의 requestId·attempt·다음 재시도 시각 보존 |
| 기본 1시간 전 좌표 제거 | 기본 만료 시각 이후 정리; 조회는 정확히 만료 시각부터 제외 |

정기 실행은 `hashi.map.maintenance.retention-enabled=true`로 켠다. 기본 false다.
전용 `location-retention` executor 하나가 작은 묶음으로 갱신 작업을 등록한 뒤 정리한다.
Google 호출은 이 스케줄러가 하지 않고 기존 worker가 같은 job을 최대 8회 처리한다.
갱신 등록 단계가 실패해도 정리는 별도로 시도한다. 이 변경에서 실제 서버 설정은 켜지 않았다.

기본값은 `refresh-ahead=3d`, `purge-ahead=0s`, `poll-delay=1m`, `batch-size=50`, `max-batches=1`이다.
갱신 대상은 미삭제 식당의 Google/READY 좌표 중 유효기한이 3일 안에 오는 항목이다.
짧은 잠금 안에서 현재 주소·요청·유효기한과 활성 작업 유무를 다시 확인한다.
PENDING/RETRY_WAIT/FAILED/REVIEW_REQUIRED는 새 작업을 자동 등록하지 않는다.
스케줄러가 반복 실행되거나 재시작해도 재시도 횟수를 초기화하지 않는다.
장애 후 이미 만료한 READY를 발견해도 최초 갱신 작업을 등록하고, 곧바로 이전 좌표를 정리한다.
새 결과가 오기 전에는 만료한 좌표를 다시 노출하지 않는다.

`refresh-ahead`는 저장된 좌표의 전체 수명보다 짧아야 한다. 같거나 더 크면 해당 좌표의 자동 갱신을
건너뛰어 성공 직후 반복 호출하는 것을 막는다. worker의 설정 수명도 3일 이하면
`refresh_window_invalid` 알림을 보낸다. 짧은 수명의 개발 테스트에서는 예를 들어
`retention=10m`, `refresh-ahead=3m`, `poll-delay=10s`처럼 함께 줄인다.
실제 운영은 계약상 허용 상한보다 짧은 retention과 충분한 정리·복구 여유를 설정해야 한다.
설정 검증은 `0 <= purge-ahead < refresh-ahead <= 30d`를 확인한다.

성공하면 새 좌표와 취득·만료 시각을 함께 교체한다. 실패는 이전 validUntil을 연장하지 않는다.
주소가 바뀌면 선행 #233의 주소 revision 처리로 이전 좌표를 즉시 지운다.
유효기한이 되면 공개 조회는 즉시 제외하고, DB 값은 다음 정리 주기에 제거한다.
기본 정상 상태에서도 **DB 물리 제거는 최대 한 polling 주기와 처리 시간만큼 늦을 수 있다.**
조회 제외와 DB 삭제 완료는 다르다. 서버/DB 장애나 backlog가 있으면 더 늦어질 수 있다.
따라서 provider의 최종 허용 보관 상한을 validUntil과 똑같이 잡고 이 지연을 무시하면 안 된다.
계약상 상한 전에 복구·정리할 안전 여유를 retention에 반영하고, 알림과 정리 처리량을 검증한 뒤 켠다.

PURGE는 작업 상태와 관계없이 Google 좌표·source·obtainedAt·validUntil을 NULL로 만든다.
READY였으면 REVIEW_REQUIRED로 전환하고, 진행 중이거나 실패한 작업은 상태와 requestId를 유지한다.
예를 들어 3번째 재시도 대기 중 만료되더라도 다음 호출은 같은 작업의 4번째 시도다.
삭제된 식당도 정리하지만 식당 원본과 자식 데이터는 보존한다. ADMIN 좌표에는 적용하지 않는다.
revision/request/obtainedAt/validUntil을 다시 비교하므로 오래된 정리 결과가 새 좌표를 지우지 않는다.

수동 한정 실행은 기존 CLI를 그대로 사용한다. REFRESH `START`도 좌표를 유지하고,
`PURGE`는 아래처럼 실행한다. 실제 실행 전에 DRY_RUN의 범위와 호출 예산을 확인한다.

```powershell
Invoke-LocationMaintenance --hashi.map.maintenance.command=PURGE --hashi.map.maintenance.execute=true `
    --hashi.map.maintenance.purge-ahead=0s --hashi.map.maintenance.batch-size=50 `
    --hashi.map.maintenance.max-batches=2
```

### Grafana 알림

기존 Micrometer/Prometheus를 사용한다. 추가 저장소나 dependency는 없다.
스케줄러가 DB에서 집계한 `hashi_map_maintenance_issues{reason="..."}`와 마지막 집계 시각을 노출한다.
식당 ID·job ID·주소·키는 metric label과 알림에 넣지 않는다.
작업 집계는 현재 주소 revision/requestId만 보므로 재처리 후에도 과거 실패가 계속 알림으로 남지 않는다.

| reason | 조건 / 알림 |
|---|---|
| access_denied, configuration_error | 해당 실패가 처음 집계되는 평가부터 우선 대응 |
| refresh_window_invalid | worker 수명보다 갱신 여유가 같거나 긴 설정이면 우선 대응 |
| retrying | 3회 이상 실패한 미해결 작업이 있는 상태가 15분 지속되면 경고 |
| attempts_exhausted | 현재 작업의 자동 시도 소진이면 우선 대응 |
| expiry_soon | Google 좌표가 24시간 이내 만료하거나 이미 만료했으면 우선 대응 |
| stalled | 호출 가능한 상태인데 이미 실행 시각이 지난 작업이 15분 이상 진행되지 않으면 경고 |
| observation_stale | 집계 시각이 3분 넘게 갱신되지 않으면 스케줄러·DB 점검 |

retrying의 15분은 Grafana `for: 15m`으로 판정한다. 전체 장애 상태를 묶는 알림이며 개별 식당의
실패 시작 시각을 별도 저장하지 않는다. 정상적인 미래 재시도 시각·전역 quota cooldown·꺼진 worker/
예산은 stalled에서 제외한다. 쿼리 실패 시 이전 값을 0으로 덮지 않으므로 관측 중단을 정상으로 숨기지 않는다.

[Grafana rule 예제](monitoring/grafana-alerts.example.json)는 자동 마운트하지 않으며 **전부 일시정지 상태**다.
운영자가 환경 label과 Prometheus UID를 확인하고 기존 Grafana에 불러온 뒤 테스트하고 활성화한다.
기존 Discord contact point와 notification policy를 덮어쓰는 파일은 제공하지 않는다.
현재 정책 아래 `service=hashi-map` 하위 경로를 추가하고 기존 Discord 수신처를 선택한다.
`environment, reason`으로 묶고 초기 `group_wait=30s`, `group_interval=5m`, `repeat_interval=4h`를 사용한다.
100개 식당이 같은 원인으로 실패해도 원인별 집계 메시지를 보낸다. 실제 전송 확인은 별도 개발 환경 적용 단계다.
파일 형식은 [Grafana 공식 provisioning 문서](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/)를 따른다.

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
물리 정리와 백업 복구 방어, app.jar launcher의 SELECT 전용 dry-run과 START/RESUME을 검증한다.
실제 CLI가 START로 만든 위치 행의 created_at/updated_at이 채워지고 RESUME에서 기존 created_at이
유지되는지도 DB에서 확인한다. CLI는 서버와 같은 `JpaAuditingConfig`를 명시적으로 불러온다.
합성 fixture의 EXPLAIN·쿼리 수는 쿼리 형태를 확인하는 자료이며 운영 데이터에서의 지연/처리량 보장이 아니다.
최종 실행 명령·XML 결과·독립 리뷰·CI와 미실행 운영 항목은 PR 검증 증거에 기록한다.
