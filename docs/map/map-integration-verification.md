# 식당 저장부터 지도 조회까지 서버 통합 검증 (#231)

## 검증 범위

이 브랜치는 관리자 식당 저장부터 위치 작업, Google adapter, MySQL 위치, 공개 BBOX 조회, Redis 조회 세션, 컬렉션 핀까지 서버 호출 경로를 결합한다. 실제 화면, 운영 배포, 운영 backfill은 범위에 포함하지 않는다.

| 결합 기능 | 확인 범위 |
|---|---|
| 위치 모델·Google adapter·worker·유지보수 | 관리자 저장/PATCH, durable job, 채택 정책, 갱신·만료 |
| 공개 지도 조회·Redis 세션 | 10개 페이지, cursor 재시도, 동점 순서, compact session payload, TTL·용량 guard |
| 컬렉션 지도 | 공개 핀, 저장 수, 본인 저장 여부, 위치 유효성 반영 |

테스트는 새 Testcontainers MySQL 8.4와 Redis를 사용한다. 합성 주소·좌표·사용자만 저장하며 개발·운영 DB/Redis에 쓰지 않는다. 일반 통합 테스트는 Google provider를 대체하고 worker를 직접 실행한다. `map-live` 태그 테스트만 명시적 승인 아래 실제 Google을 최대 두 번 호출한다.

## 자동 통합 흐름

`RestaurantMapFlowIntegrationTest`는 실제 Security filter, 관리자 Controller/Service, 위치 worker와 저장소, 공개 Controller/Service/Repository, Flyway/JPA, MySQL/Redis를 사용한다. 핵심 지도 서비스와 저장소는 mock으로 바꾸지 않는다.

1. 관리자 POST가 `201 ADMIN-204`, 위치 `PENDING`, 주소 revision 1, durable job 1개를 만든다. worker 전 공개 현재 위치는 409다.
2. worker가 transaction 밖에서 provider를 호출하고 후보 채택 정책을 통과하면 위치가 `READY`가 된다. 관리자 상태, 공개 현재 위치, 지역 수, BBOX 첫 페이지와 RestaurantPort가 같은 식당과 좌표를 반환한다.
3. 표시 주소의 층수만 바꾸고 같은 `geocodingAddress`를 함께 PATCH하면 위치는 `READY`, revision과 job 수는 그대로 유지된다. provider를 다시 호출하지 않고 기존 BBOX 카드와 컬렉션 핀도 유지한다.
4. 실제 geocoding 기준 주소가 바뀌면 revision이 증가하고 새 job이 등록된다. 기존 위치는 공개 조회와 핀에서 제외되고, 변경 전 Redis 조회 세션을 다시 읽어도 오래된 카드가 반환되지 않는다.
5. 이전 revision의 늦은 성공과 삭제 뒤 늦은 실패는 현재 주소나 삭제 상태를 덮어쓰지 못한다.
6. 유효기간 전 갱신 등록은 기존 좌표를 유지한 채 `PENDING`으로 전환한다. worker 성공 뒤 새 유효기간을 저장한다. 실제 만료 정리는 공개 현재 위치, Port, BBOX와 핀에서 좌표를 제거한다.
7. 합성 READY 식당 23·500·501·620개를 10개씩 끝까지 조회해 중복·누락 없음, 같은 cursor 재시도, 10 valid + 미소비 lookahead, 동점 추천 순서를 확인한다. 임의 500개 전체 제한은 두지 않는다.
8. 기존 컬렉션 저장 관계를 통해 전체 핀, 공개 저장 사용자 수, 본인 저장 여부를 확인한다. 위치가 무효해져도 저장 관계는 남고 핀만 제외된다.

## 로컬 검증 명령

Java 21과 Docker가 필요하다. 일반 검증과 부하는 동시에 실행하지 않는다.

```powershell
.\gradlew.bat test `
  --tests '*RestaurantMapFlowIntegrationTest' `
  --tests '*MapQuerySessionTest' `
  --tests '*MapSessionLimitsTest' `
  --tests '*RedisMapSessionStoreIntegrationTest' `
  --tests '*RestaurantMapSessionGateTest' `
  --no-daemon --console=plain --max-workers=2
```

전체 build와 원격 CI는 위 focused 검증과 별도 결과로 기록한다.

## 실제 Google opt-in 검증

`MapGoogleLiveFlowTest`는 일반 `test`와 CI에서 제외된다. `-PmapLive=true`와 환경 변수의 API key가 모두 있어야 실행되며, key를 Gradle 인자·파일·로그에 적지 않는다.

```powershell
.\gradlew.bat mapLiveTest -PmapLive=true --no-daemon --console=plain --max-workers=1
```

승인된 loopback TCP 터널을 사용할 때도 제품 HTTPS endpoint, SNI와 인증서 검증은 유지한다. 자동 scheduler는 대체하고 worker만 수동 실행한다. DB 일일 예산과 테스트 provider counter의 상한은 두 번이다.

한 실행의 확인 순서는 다음과 같다.

1. 관리자 저장 뒤 실제 Google 1회 호출로 `READY`, BBOX 카드와 컬렉션 핀까지 확인한다.
2. 표시 주소 층수만 바꾸고 같은 `geocodingAddress`를 PATCH한다. revision·job·핀은 유지되고 provider counter도 1에 머문다.
3. 위치 유효기간을 갱신 창 안으로 옮겨 refresh job을 등록한다. 기존 핀을 유지한 채 실제 Google 두 번째 호출로 새 유효기간을 저장한다.

`languageCode=en`은 provider 응답 언어를 고정하기 위한 값이며 입력 주소나 UI 표시 주소의 언어를 강제하지 않는다. JP 지역 정보와 후보의 국가·행정구역·정밀도·주소 구성요소를 채택 정책에서 확인한다. Google 응답 언어와 관리자가 저장한 표시 주소 언어는 서로 다른 계약이다. 통과를 위해 실제 응답이나 기대 좌표를 임의로 바꾸지 않는다. Google 원문, 좌표 원문, 운영 주소와 key는 저장소에 남기지 않는다.

2026-10-08 코드 `39266dd`에서 위 흐름을 실제로 실행해 통과했다. Google 호출은 최초 등록과 갱신 각 한 번이며, 층수만 바꾸는 PATCH는 추가 호출 없이 기존 revision·job·핀을 유지했다. 별도 Testcontainers DB/Redis를 사용했고 운영 데이터나 자동 호출 설정은 변경하지 않았다.

## Redis session과 용량 결과

세션은 query ID별 단일 Redis key와 단일 JSON payload를 유지한다. 후보는 `[restaurantId,rating,reviewCount]` tuple로 직렬화하고 format version을 검사한다. 이전 장문 JSON은 혼합 배포에서 fail-closed 410으로 처리한다. 유휴 TTL 5분과 최대 수명 30분, 추천 순서, 10 valid + 미소비 lookahead 계약은 바꾸지 않았다.

620개 serializer 비교는 다음과 같다.

| 항목 | 기존 object JSON | compact tuple JSON |
|---|---:|---:|
| payload | 31,188 B | 7,646 B |
| admission 예약 | 63,400 B | 16,316 B |

예약값은 74.3% 감소했다. 16 MiB 예산을 올리거나 Redis 자료구조를 여러 key로 나누지 않았다.

실제 TTL 300초, distinct caller, 620개, 16 MiB의 로컬 비교 결과는 다음과 같다.

| 설정 | 요청/iteration | 503 | 최대 ledger | DB pool | 성공 응답 전체 p95 | 판정 |
|---|---:|---:|---:|---|---:|---|
| 운영 기본 동시 요청 4 | 1,230 / 236 | 6 | 7,977,700 B (47.55%) | active 4, wait 0 | 229.76 ms | k6 FAIL |
| test-only 동시 요청 8 | 1,148 / 223 | 3 | 7,488,384 B (44.64%) | max 10, active 7, wait 0 | 1,087.88 ms | k6 FAIL |

두 실행의 workload에서 `total_bytes`, `snapshot_bytes`, `session_count`, `redis_memory_guard` 거절은 0이었다. 모든 503은 내부 `concurrent_requests` counter로 확인했다. 동시 요청 8은 거절을 6개에서 3개로 줄였지만 threshold를 통과하지 못했고, 처리량이 줄면서 성공 응답 전체 p95가 4.73배 증가했다. 운영 기본값 4를 유지한다.

두 실행 모두 실제 300초 TTL 뒤 session key가 정리됐다. 별도 2-session probe는 세 번째 요청을 503으로 거절하고 만료 뒤 회복했으며 auth sentinel을 보존했다. 이 결과는 작은 로컬 합성 부하이며 운영 동시 사용자 수나 SLO를 증명하지 않는다.

`MapQueryLoadTest`는 opt-in `map-load` 태그다. k6 설치 환경에서만 실행한다.

```powershell
.\gradlew.bat mapLoadTest `
  -Pk6Executable=<k6.exe 절대경로> `
  -PmapLoadProfile=ttl -PmapLoadTraffic=distinct `
  -PmapLoadRestaurants=620 -PmapLoadBudgetMiB=16 `
  --no-daemon --console=plain --max-workers=1
```

테스트 전용 비교에는 `-PmapLoadConcurrentRequests=8`을 추가할 수 있지만 운영 기본값은 바뀌지 않는다. 결과는 `build/reports/map-load/`의 k6 summary, log, resource samples, recovery JSON에 기록한다. k6 threshold가 실패해도 TTL 정리·회복·auth sentinel을 확인한 뒤 최종 실패한다.

## 현재 데이터 확인과 남은 gate

운영 식당 주소 71개에 대한 실제 provider 응답을 별도 환경에서 제품 parser·정책으로 재생한 결과는 자동 채택 62건, 확인 대상 9건이다. 자동 채택 62건은 모두 선택 후보와 Maps 기준 거리를 계산했다. 위치 참조 정정 후 잘못 다른 지점을 가리키던 표본은 기준점과 거리 0.0m로 다시 확인됐다. 자동 채택 표본 중 기준점에서 50m 이상 떨어진 항목은 한 건이며 역 시설 약 208m 사례다. 이는 운영 DB에 좌표를 쓰거나 `READY` 상태를 만든 결과가 아니다. 저장소 문서에는 구체 식당 ID, 주소와 좌표를 기록하지 않는다.
이 62/9 수치는 component의 동일 숫자를 서로 다른 역할로 오해하던 문제를 수정하기 전 선행 정책 HEAD에서 측정했으며, 현재 정책 71건의 최종 재검증 결과로 해석하지 않는다.

공개 활성화는 아직 NO-GO다.

- 개발 Redis의 읽기 전용 점검에서는 eviction 정책이 지도 저장소의 fail-closed `noeviction` 요구와 맞지 않았다. 인증 데이터와 공유하므로 부하 결과를 이유로 runtime `CONFIG SET`을 실행하지 않는다.
- 전용 map Redis 또는 충분한 headroom이 있는 공유 `noeviction` Redis를 선택하고 장애 격리·auth 영향을 검증해야 한다.
- 실제 데이터 aggregate와 새 fixed HEAD의 focused/full test, 실제 Google opt-in 결과, CI를 각각 확인해야 한다.
- merge, 배포, live backfill과 운영 설정 변경은 별도 승인 대상이다.

프런트 계약과 화면 인계는 [프런트 연동 인계](frontend-integration-handoff.md), 위치 유지보수 반례와 명령은 [위치 유지보수 runbook](location-maintenance-runbook.md)을 따른다.
