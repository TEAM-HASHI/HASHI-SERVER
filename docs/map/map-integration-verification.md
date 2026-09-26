# 식당 저장부터 지도 조회까지 서버 통합 검증 (#231)

## 범위와 결합 기준

이 문서는 한 서버 조립의 관리자 HTTP → 저장/위치 작업 → Google 대체 응답 → MySQL 위치 → 공개 지도 조회와 Redis 세션을 확인하는 방법이다. 실제 화면, 운영 Google 계정, 운영 DB, 유료 호출, 배포의 완료 증거가 아니다. 지도 전체 인수 조건은 [#219 계약](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)과 PR #221의 문서에 있다.

| 코드 | 원래 고정 기준 | 이 브랜치에 가져온 변경 |
| --- | --- | --- |
| #220·#222·#224 | `0d77d91`, 이후 #224 `aaacb5d` | 시작 tree와 `1656dfe`. 위치 모델·adapter·관리자 저장/작업 경로와 개발용 fixture 잠금 수정 |
| #223 | `98299f2`, `303fb5c` | `a592d1d`, `9f7e3e4`. 지도 오류 011~018과 위치 재처리 019, 두 Port 의존성을 함께 유지 |
| #227 | `31a7998`, `c956780` | `291b46a`, `ee873b7`. 지도 페이지/Redis 세션과 OSIV 연결 반환 수정 |
| #228 | `4ad25ea` | `ff7fad3`. V30과 갱신/정리. 선행 리뷰 수정이 있으면 추가 결합 필요 |

이 브랜치의 자체 변경은 `RestaurantMapFlowIntegrationTest`와 이 문서다. 선행 소유 브랜치의 HEAD를 바꾸지 않았다. #216의 컬렉션 CRUD/스키마는 아직 결합 가능한 구현이 없어 만들지 않았다. 컬렉션 인수는 별도 #216 연결 기준을 따른다.

#227 첫 구현과 #228 구현의 원본/결합 커밋은 각각 stable patch-id가 일치한다. #223 첫 커밋은 `RestaurantErrorCode`와 `RestaurantPortImpl`의 양쪽 요구를 보존하는 충돌 해결이 들어가므로 원본과 patch-id가 달라질 수 있다. 결합 기준은 최종 파일의 API/상태·실제 테스트로 확인한다.

## 로컬 실행 환경

- Java 21: `C:/Users/venus/.jdks/ms-21.0.7`; Docker 실행 가능. 테스트는 Testcontainers MySQL 8.4와 Redis를 사용한다.
- Gradle: PowerShell에서 전용 worktree를 현재 경로로 지정하고 다음 명령을 실행한다. 공유 Gradle 캐시와 Docker pipe 접근 권한이 필요하다.
- JVM 기본 UTC/JDBC MySQL 세션 Seoul을 사용한다. DB 위치 시각은 UTC `DATETIME`으로 확인한다.

```powershell
$env:JAVA_HOME='C:\Users\venus\.jdks\ms-21.0.7'
$env:PATH="$env:JAVA_HOME\bin;C:\Program Files\Docker\Docker\resources\bin;$env:PATH"
.\gradlew test --tests org.sopt.hashi.restaurant.service.RestaurantMapFlowIntegrationTest --no-daemon
.\gradlew clean build --no-daemon
```

테스트 주소 `東京都試験区架空町1丁目2番3号`, 식당·관광 지역·좌표는 합성 고정값이다. Google 경계만 대체하고 후보 정확도·주소 일치 판정은 실제 `LocationAdoptionPolicy`를 통과한다. 위치 작업 전역 호출 예산은 기본적으로 닫혀 있으므로 테스트 전용 MySQL row에서만 연다. Redis 서명 키도 테스트 문자열을 실행 중 메모리에만 설정한다. 운영 키나 주소 원문 응답을 로그/문서에 넣지 않는다.

2026-09-27 관련 검증은 5개 suite / 42개 테스트, failures·errors·skipped 모두 0건이었다(`RestaurantMapFlowIntegrationTest` 4, `RestaurantMapPageIntegrationTest` 17, `LocationMaintenanceMySqlTest` 18, `DevLocationJobMySqlTest` 2, `ModularityTests` 1). 이 수치는 전체 build나 원격 CI 결과가 아니다.

## 직접 연결한 흐름

`RestaurantMapFlowIntegrationTest`는 실제 `SecurityFilterChain`, 관리자 Controller/Service/RestaurantPort, 위치 worker/작업 저장소, 공개 Controller/Service/Repository, Flyway/JPA, MySQL/Redis를 사용한다. 핵심 서비스·Port·Repository는 mock으로 바꾸지 않는다. 관리자 인증은 합성 JWT를 만들고 무인증 요청의 401도 확인한다.

1. 관리자 POST의 기존 `201 ADMIN-204`와 `PENDING`/revision 1, 같은 DB transaction에서 생성된 durable job을 확인한다. worker 전에는 공개 `map-location`이 409다.
2. worker가 정확한 한 후보를 처리한다. provider 진입에서 활성 DB transaction이 없음을 관측한다. 이후 관리자 `READY`, 공개 현재 위치, 관광 지역 count, RestaurantPort bulk, 공개 첫 페이지에서 같은 식당 ID와 좌표를 확인한다.
3. 주소 B PATCH의 기존 `200 ADMIN-205`, revision 2와 새 `PENDING`을 확인한다. 기존 위치·지역 count·Port 좌표가 사라지고 기존 Redis 세션을 정렬 재조회해도 오래된 카드가 돌아오지 않는다.
4. 주소 A 작업을 claim한 채 B를 저장하고 A의 늦은 성공을 완료한다. 현재 revision을 덮지 못한다. B claim 뒤 삭제하고 늦은 실패를 완료해도 삭제 식당이 공개되지 않는다.
5. Google 결과를 READY로 만든 뒤 합성 DB의 남은 수명을 3시간으로 당기고, 원본 1일 수명보다 짧은 6시간 갱신 창으로 run을 등록한다. 기존 좌표를 즉시 제외하는지 관리자 상태, 현재 위치, Port, 기존 페이지에서 확인한다.
6. 별도의 READY fixture를 보존 경계 안으로 당긴다. provider 예산을 끄고 정리를 실행하여 실제 DB의 좌표·유효기간이 제거되고 현재 위치, Port, 기존 페이지 재조회에서 보이지 않는지 확인한다.

23개 후보의 10/10/3, Redis TTL·유실·장애, cursor 변조와 중복 재시도는 [#227 자체 테스트](../../src/test/java/org/sopt/hashi/restaurant/service/RestaurantMapPageIntegrationTest.java)의 실제 Redis 검증을 참조한다. 이 통합 테스트는 그 전체를 복제하지 않고 관리자 저장과 페이지 사이의 경계를 확인한다. 갱신 등록·CLI dry-run/resume/stop·동시 정리의 상세 반례는 [#228 runbook](location-maintenance-runbook.md)과 해당 MySQL 테스트를 참조한다.

## 별도 인수와 운영 입력

- 현재 CLIENT `/map`은 준비 중 화면이며 관리자도 새 위치 상태를 아직 표시하지 않는다. 이 테스트 성공은 UI QA가 아니다. 카메라 상태, chip 재선택, 카드·핀, 복귀·만료 처리는 실제 화면에서 따로 검증한다.
- #216 컬렉션이 준비되면 `GET /collections/{id}/map-markers`, `GET /restaurants/save-counts`, `GET /users/me/restaurant-saves`를 실제 저장 관계·권한과 연결한다. 23개 식당 중 위치 없는 2개가 있어도 목록과 별개로 유효 핀 21개를 한 응답에 반환해야 한다. 저장 수는 사용자 수로 세고, 공개 범위/소속 변경 번호를 응답 직전에 재확인하며 부분 200을 허용하지 않는다. 현재 첫 지도 페이지의 카드는 개인 저장 상태를 포함하지 않는다.
- 관광 지역 대표 위치·범위·매핑, 운영 Google 계약/키 제한/보관 수명, Redis 용량과 장애 예산, DB 백업·복원 보존 절차는 실제 운영값이 필요하다. 합성 fixture는 이를 대신하지 않는다.
- 실제 Google 호출, 운영 backfill/물리 정리, 배포, 병합은 이 검증의 실행 대상이 아니다.
