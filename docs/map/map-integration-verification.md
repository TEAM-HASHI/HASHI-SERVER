# 식당 저장부터 지도 조회까지 서버 통합 검증 (#231)

## 범위와 결합 기준

기본 통합 테스트는 관리자 HTTP → 저장/위치 작업 → Google 대체 응답 → MySQL 위치 → 공개 지도 조회와 Redis 세션을 확인한다. 아래의 별도 opt-in 테스트만 실제 Google을 호출한다. 테스트 코드를 추가한 것과 실호출에 성공한 것은 구분하며, 실제 화면·운영 DB·배포의 완료를 의미하지 않는다. 지도 전체 인수 조건은 [#219 계약](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)과 PR #221의 문서에 있다.

| 변경 | 결합한 Draft PR 기준 | 이 브랜치의 확인 범위 |
| --- | --- | --- |
| #220·#222·#224·#228 | #225·#226·#233·#235 | 위치 모델·Google adapter·관리자 저장/worker·유지보수, V32~V34 |
| #223·#227 | #229·#234 | 공개 지도 조회·Redis 세션·페이지 재검증 |
| #242 | #244 | 기존 #216 컬렉션 CRUD에 전체 핀·저장 수·본인 저장 여부 연결, V35 |

이 브랜치의 자체 변경은 `RestaurantMapFlowIntegrationTest`, 명시적으로 실행하는 `MapQueryLoadTest`와 k6 스크립트, 테스트 실행 설정과 이 문서다. develop에 병합된 #216 컬렉션 CRUD/스키마를 재구현하지 않는다. V28, V30, V31(매거진), V32(위치), V33(작업), V34(유지보수), V35(컬렉션 변경번호) 순서이며 기존 migration 파일은 수정하지 않는다. #242 결합 전 생긴 `RestaurantPortImpl.findActiveMapInfos` 중복 선언은 동일 메서드 하나를 제거했다.

## 로컬 실행 환경

- Java 21: `C:/Users/venus/.jdks/ms-21.0.7`; Docker 실행 가능. 테스트는 Testcontainers MySQL 8.4와 Redis를 사용한다.
- Gradle: PowerShell에서 전용 worktree를 현재 경로로 지정하고 다음 명령을 실행한다. 공유 Gradle 캐시와 Docker pipe 접근 권한이 필요하다.
- JVM 기본 UTC/JDBC MySQL 세션 Seoul을 사용한다. DB 위치 시각은 UTC `DATETIME`으로 확인한다.

```powershell
$env:JAVA_HOME='C:\Users\venus\.jdks\ms-21.0.7'
$env:PATH="$env:JAVA_HOME\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
.\gradlew test --tests '*RestaurantMapFlowIntegrationTest' --tests '*CollectionVersionMigrationTest' --no-daemon --console=plain --max-workers=2
.\gradlew clean build --no-daemon
```

테스트 주소 `東京都試験区架空町1丁目2番3号`, 식당·관광 지역·좌표는 합성 고정값이다. Google provider, 자동 `LocationJobScheduler`, 범위 밖의 `MediaPort`·`FileStorage`를 대체한다. worker는 테스트에서 직접 한 번 실행하고 관광 지역 관계는 fixture에서 직접 설정한다. 후보 정확도·주소 일치 판정은 실제 `LocationAdoptionPolicy`를 통과한다. 위치 작업 전역 호출 예산은 기본적으로 닫혀 있으므로 테스트 전용 MySQL row에서만 연다. Redis 서명 키도 테스트 문자열을 실행 중 메모리에만 설정한다. 운영 키나 주소 원문 응답을 로그/문서에 넣지 않는다.

이미 병합된 `V31__link_magazine_card_news_to_media_assets.sql`은 그대로 유지한다.
위치 업그레이드 테스트는 develop V31에서 V32로, 컬렉션 업그레이드 테스트는 V34에서 V35로 진행한다.
검증은 V32~V35가 있는 MySQL 8.4 새 DB의 Flyway와 Hibernate validation을 포함한다. 관련 테스트와 전체 build, 원격 CI는 서로 구분해 확인한다.

## 직접 연결한 흐름

`RestaurantMapFlowIntegrationTest`는 실제 `SecurityFilterChain`, 관리자 Controller/Service/RestaurantPort, 위치 worker/작업 저장소, 공개 Controller/Service/Repository, Flyway/JPA, MySQL/Redis를 사용한다. 핵심 서비스·Port·Repository는 mock으로 바꾸지 않는다. 관리자 인증은 합성 JWT를 만들고 무인증 요청의 401도 확인한다.

1. 관리자 POST의 기존 `201 ADMIN-204`와 `PENDING`/revision 1, 같은 DB transaction에서 생성된 durable job을 확인한다. worker 전에는 공개 `map-location`이 409다.
2. worker가 정확한 한 후보를 처리한다. provider 진입에서 활성 DB transaction이 없음을 관측한다. 이후 관리자 `READY`, 공개 현재 위치, 관광 지역 count, RestaurantPort bulk, 공개 첫 페이지에서 같은 식당 ID와 좌표를 확인한다.
3. 주소 B PATCH의 기존 `200 ADMIN-205`, revision 2와 새 `PENDING`을 확인한다. 기존 위치·지역 count·Port 좌표가 사라지고 기존 Redis 세션을 정렬 재조회해도 오래된 카드가 돌아오지 않는다.
4. 주소 A 작업을 claim한 채 B를 저장하고 A의 늦은 성공을 완료한다. 현재 revision을 덮지 못한다. B claim 뒤 삭제하고 늦은 실패를 완료해도 삭제 식당이 공개되지 않는다.
5. Google 결과를 READY로 만든 뒤 합성 DB의 남은 수명을 3시간으로 당기고, provider 예산을 닫은 상태에서 원본 1일 수명보다 짧은 6시간 갱신 창으로 run을 등록한다. 새 작업 등록, 추가 provider 호출 없음, 관리자 상태는 PENDING으로 바뀌지만 유효한 기존 좌표는 현재 위치, Port, 기존 페이지에서 유지되는지 확인한다.
6. 별도의 READY fixture를 실제로 만료시킨다. provider 예산을 끄고 정리를 실행하여 실제 DB의 좌표·유효기간이 제거되고 현재 위치, Port, 기존 페이지 재조회에서 보이지 않는지 확인한다.

7. 별도 합성 READY 식당 23·500·501·620개를 실제 DB에 만들어 10개씩 끝까지 조회한다. 모든 카드의 `restaurantId`가 중복·누락 없이 예상 식당 ID와 일치하는지 확인한다. 같은 커서를 다시 요청했을 때 같은 결과를 반환하고, 별점이 모두 같으면 정렬을 바꿔도 추천 순서가 유지되는지 확인한다.
8. 관리자 저장·worker 완료로 좌표가 확정된 식당을 USER가 기존 컬렉션 API로 저장한다. 익명 전체 핀, 공개 저장 사용자 수, 본인 저장 여부를 실제 HTTP와 MySQL로 확인한다. 주소 변경으로 좌표가 무효화되면 저장 관계는 남고 전체 핀만 빠지는지 확인한다.

Redis TTL·유실·장애, cursor 변조와 중복 재시도는 [#227 자체 테스트](../../src/test/java/org/sopt/hashi/restaurant/service/RestaurantMapPageIntegrationTest.java)의 실제 Redis 검증을 참조한다. 갱신 등록·CLI dry-run/resume/stop·동시 정리의 상세 반례는 [#228 runbook](location-maintenance-runbook.md)과 해당 MySQL 테스트를 참조한다.

## 별도 인수와 운영 입력

- 이 서버 테스트는 UI QA를 대체하지 않는다. 카메라 상태, chip 재선택, 카드·핀, 복귀·만료 처리는 실제 화면에서 따로 검증한다.
- #242의 `GET /collections/{id}/map-markers`, `GET /restaurants/save-counts`, `GET /users/me/restaurant-saves`를 develop의 실제 #216 저장 관계·권한과 연결했다. 23개 식당 중 위치 없는 2개가 있어도 목록과 별개로 유효 핀 21개를 한 응답에 반환한다는 별도 #242 MySQL 테스트가 있다. 저장 수는 사용자 수로 세고, 공개 범위/소속 변경 번호를 응답 직전에 재확인하며 부분 200을 허용하지 않는다. 현재 첫 지도 페이지의 카드는 개인 저장 상태를 포함하지 않는다.
- 조회 ID별 키·유휴 만료·전체 메모리 및 호출자 제한은 #234 구현을 사용한다. 아래 실험은 작은 로컬 부하의 회귀 검증이며, 운영 용량을 정하려면 실제 트래픽과 서버 자원에 맞춘 별도 측정이 필요하다.
- 관광 지역 대표 위치·범위·매핑, 운영 Google 계약/키 제한/보관 수명, Redis 용량과 장애 예산, DB 백업·복원 보존 절차는 실제 운영값이 필요하다. 합성 fixture는 이를 대신하지 않는다.
- 실제 Google 호출, 운영 backfill/물리 정리, 배포, 병합은 이 검증의 실행 대상이 아니다.


## 로컬 HTTP 부하·정리·복구 실험

이 테스트는 기존 `test` 작업에서 제외한 `map-load` 태그를 별도 `mapLoadTest` 작업으로 실행한다. k6를 설치한 실행 환경에서 직접 호출하며, 일반 CI 테스트에 k6 설치나 90초 부하를 추가하지 않는다.

```powershell
.\gradlew mapLoadTest -Pk6Executable='C:\tools\k6.exe' --no-daemon --console=plain --max-workers=2
```

- 새 MySQL·Redis 컨테이너와 `127.0.0.1`의 임의 HTTP 포트를 사용한다. 기존 개발 서버 주소를 입력받지 않으며 Google 호출과 k6 사용 통계 전송은 비활성이다.
- 합성 식당 620개를 넣고 90초간 최대 2명의 가상 사용자가 신규 조회, 다음 페이지, 같은 커서 재시도, 정렬 변경, 필터 변경을 반복한다. 모든 식당의 별점을 같게 해 동점 순서도 확인한다.
- JSON 파싱, 카드 배열과 ID, 다음 페이지·세션 필수값을 먼저 검사한다. 형식 오류나 예상하지 못한 iteration 예외도 실패 metric에 기록해 k6가 성공으로 끝나지 않게 한다.
- 동작별 응답 시간, HTTP 오류율, 페이지 중복과 재시도 결과를 확인한다. 1초마다 Redis 메모리, 세션 수, DB 연결 수와 연결 대기 수를 기록한다.
- 시간 기반 동작을 짧게 확인하려고 테스트에만 유휴 10초·최대 30초를 적용한다. 운영 기본값인 유휴 5분·최대 30분을 변경하지 않는다.
- 단일 loopback 호출자로 여러 동작을 반복하므로 fixture의 분당 호출 한도는 호출자 600회·신규 조회 500회·전체 3,000회로 설정한다. 운영 기본값은 변경하지 않는다.
- 요청을 멈추면 조회 키가 만료되는지 확인한다. 이어서 테스트의 세션 한도를 2개로 낮춰 3번째 조회가 제한되고, 만료 후 다시 조회되는지 확인한다. 다른 용도의 합성 Redis 키는 이 과정에서 남아 있어야 한다.
- `build/reports/map-load/`에 k6 요약, 실행 로그, 자원 관측값, 복구 결과를 저장한다. 부하 실행이 실패하면 테스트도 실패한다.

이 부하는 기능과 간단한 자원 회복을 확인하는 로컬 실험이다. 실제 사용자 수를 산정하거나 운영 응답 시간 목표를 검증하는 자료로 그대로 사용할 수 없다.


### 2026-10-07 로컬 실행 결과

`RestaurantMapFlowIntegrationTest` 8건과 `bootJar`가 통과했다. 23·500·501·620개 전체 조회에
중복·누락이 없었고, 같은 커서 재시도와 동점 정렬 순서도 유지됐다.

별도 `mapLoadTest`는 Windows 개발 PC에서 k6 2.3.0, 합성 식당 620개, 최대 2 VU,
90초 혼합 시나리오로 실행했다. 응답 형식 검증 보완 후 다시 실행한 HTTP 요청 310건의 실패가 없었으며 검사 682건이 모두 통과했다.
잘못된 JSON, null 카드, 누락된 커서 등 14개 스크립트 검증 사례에서도 실패가 metric과 check에 기록되는지 확인했다.

| 동작 | 관측한 p95 |
|---|---:|
| 신규 조회 | 535ms |
| 다음 페이지와 같은 커서 재시도 | 337ms |
| 정렬 변경 | 103ms |
| 필터 변경 | 175ms |

1초 간격 89개 표본에서 조회 키는 최대 20개, Redis `used_memory`는 최대 약 2.17MiB,
DB 활성 연결은 최대 2개, 연결 대기는 0개였다. 부하 종료 후 조회 키가 모두 만료됐고,
세션 한도 2개에서 세 번째 조회의 거부와 만료 후 복구, 다른 용도의 합성 Redis 키 보존도 통과했다.
표본 간의 순간 최대값이나 운영 처리량을 측정한 결과는 아니다. 운영 요청량에 맞춘 용량 산정은 별도다.

## 명시적으로 실행하는 실제 Google 검증

일반 `test`/CI는 `map-live`를 제외한다. `mapLiveTest`는 `-PmapLive=true`와 환경 변수
`HASHI_MAP_GOOGLEGEOCODING_APIKEY`가 없으면 실패한다. 키를 gradle 인자나 파일에 적지 않는다.

승인된 개발 EC2를 통한 TCP 터널 `127.0.0.1:443 → geocode.googleapis.com:443`을 준비한 뒤 실행한다.
테스트 전용 DNS만 loopback을 가리키며 HTTPS URI·SNI·인증서 검증은 그대로다. 제품 endpoint는 바꾸지 않는다.

```powershell
./gradlew mapLiveTest -PmapLive=true --no-daemon
```

주소는 도쿄도청의 공개 주소 한 건이다. Security 필터를 거친 관리자 저장 → durable job → 실제
Google adapter → 기존 주소 채택 정책 → MySQL READY → BBOX → 컬렉션 핀을 확인한다.
외부 provider 응답은 모킹하지 않는다. scheduler는 대체하고 worker는 한 번만 수동 실행한다.
테스트 provider의 호출 상한, DB 일일 예산, max-attempts를 각각 1로 제한한다.
실제 응답이 기존 정책을 통과하지 않으면 실패로 보고하며, 통과하려고 후보나 기대값을 바꾸지 않는다.
DB/Redis는 일회성 Testcontainers이며 실제 dev/prod 데이터는 사용하지 않는다.
MockMvc 출력은 끄고 API 키·Google 응답·좌표 원문은 증거에 남기지 않는다.

## 격리 부하 프로필

```powershell
# 기존 2 VU / 90초 / 620개 / 유휴 10초 smoke는 그대로 유지
./gradlew mapLoadTest -Pk6Executable=<k6.exe 절대경로> --no-daemon
# 5 → 10 → 20 VU, 부하 구간 300초, 합성 식당 최대 5000개
./gradlew mapLoadTest -PmapLoadProfile=staged -PmapLoadRestaurants=3000 -Pk6Executable=<경로> --no-daemon
# 같은 부하 + 실제 정책의 유휴 5분 / 최대 30분, 만료 정리까지 추가 관찰
./gradlew mapLoadTest -PmapLoadProfile=ttl -PmapLoadRestaurants=620 -Pk6Executable=<경로> --no-daemon
```

프로필은 smoke/staged/ttl만 받으며 식당 수는 620~5000으로 제한한다. 서버는 loopback의 임의 포트에만
열고 k6도 그 주소만 받는다. 호출 한도는 기존 smoke용 override(호출자600·신규500·전체3000/분)를 유지하며
production 기본값(120·12·600/분)보다 높다. 동시 실행·snapshot·메모리 예산은 바꾸지 않는다. 한 loopback 호출자에
여러 VU가 모이므로 호출 제한이 먼저 나타날 수 있다. 운영 동시 사용자 수와 동일하게 해석하지 않는다.

신규/다음 페이지/정렬/필터 지연을 따로 기록하고 429·503 건수와 작업별 상태 코드를 남긴다.
기존 p95<3초·실패율<1% 기준을 유지한다. 거절을 정상 응답으로 바꿔 통과시키지 않는다.
k6 실패 시에도 TTL 정리와 메모리/DB pool 관찰을 진행한 뒤 최종 실패한다.
확장 프로필은 호출 제한 창의 자연 만료도 기다린다. 별도 용량 거절·복구 검사는 유휴 10초로 줄여 실행하고
그 사실을 구분한다. auth sentinel을 유지하며 Redis 전체 flush는 하지 않는다.

결과는 `build/reports/map-load/{k6-summary.json,k6.log,resource-samples.json,recovery.json}`이다.
TTL 프로필은 부하 5분 외에 마지막 요청 후 정리 약 5분과 복구 검증 시간이 추가된다.
실제 Google 검증과 부하 검증은 동시에 실행하지 않는다. 이 결과는 운영 용량 보장이 아니다.

프런트 전달 내용은 [프런트 연동 인계](frontend-integration-handoff.md)를 따른다.

2026-10-08 개발 Redis 읽기 전용 점검에서는 maxmemory 384MiB, 정책 `volatile-lru`, 사용량 약6.55MiB였다.
현재 지도 저장소의 `noeviction` 보호 조건과 맞지 않으므로 그대로 활성화하면 지도 조회가 503으로 거절된다.
인증 데이터와 공유하므로 부하 테스트 통과를 이유로 운영 `CONFIG SET`을 실행하지 않는다.
개발 환경 정책 변경 또는 지도용 Redis 분리는 운영 담당자가 영향 범위를 확인한 뒤 진행해야 한다.
아래 격리 테스트의 128MiB/noeviction 설정은 실제 개발 Redis 설정을 변경하지 않는다.

추가 계측은 Redis admission ledger의 기록된 예약 bytes/항목 수, 동시 요청 한도, 세션 한도,
ledger 예산과 k6의 `map_error_RESTAURANT_*` 카운터를 남긴다. ledger 표본은 아직 정리되지 않은 만료 항목도
포함할 수 있으므로 실제 활성 예약의 상한으로 해석한다. 오류 코드016만으로 메모리 예산과 in-flight guard를
단정해서는 안 된다. 2026-10-08 staged620 측정은 이 추가 계측 직전 버전으로 실행했으며 원인 코드 수는 서버
테스트 로그와 대조했다. 실제 유휴5분 TTL 및 3000/5000개 프로필은 아직 실행하지 않았다.

## 실제 Google 1회 결과 — 2026-10-08

검증 코드 `d59d282`에서 공개 주소 한 건을 실제 Google로 조회했다. TLS와 API 응답 수신은 성공했으나,
기존 주소 채택 정책에서 `ADDRESS_MISMATCH`로 거절되어 작업이 `REVIEW_REQUIRED`가 됐다.
단일 후보·JP·東京都·지원 범위·ROOFTOP 검사는 통과했지만 주소 구성요소의 어느 조건이 다른지는
그 실행에서 보관하지 않았으므로 확정할 수 없다. 이후 READY/BBOX/컬렉션 핀 검증은 실행되지 않았다.
전체 실호출 흐름은 실패이며, 성공으로 바꾸기 위한 운영 정책이나 테스트 기대값 변경은 하지 않았다.
후속 진단은 테스트 전용으로 구성요소 타입(고정 allowlist 외 UNKNOWN)과 빈 값/숫자/한자 숫자 여부만
출력한다. 주소 원문·좌표·키는 출력하지 않으며 원래 응답을 그대로 기존 worker에 전달한다.


2026-10-08 staged/620개/5→10→20 VU/300초에서는 3,838건 중 429(RESTAURANT-022) 595건,
503(RESTAURANT-016) 268건으로 실패율22.49%여서 기존 기준을 통과하지 못했다. 별도 용량 거절 probe1건은
위 부하 건수에서 제외했다. 만료 후 정상 요청 복구와 auth sentinel 보존은 확인했다.
1초 표본의 최대치는 query key137개, Redis 약6.04MiB, DB active4/waiting0이었다. 503은 동시요청4개 제한의
영향이 유력하지만 기존 측정은 저장 예산과 원인을 직접 구분하지 않았으므로 확정하지 않는다.
거절 응답도 지연 통계에 포함되며 한 호출자·smoke용 호출 한도로 측정했으므로 실사용 용량 추정값이 아니다.
