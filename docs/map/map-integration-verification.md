# 식당 저장부터 지도 조회까지 서버 통합 검증 (#231)

## 범위와 결합 기준

이 문서는 한 서버 조립의 관리자 HTTP → 저장/위치 작업 → Google 대체 응답 → MySQL 위치 → 공개 지도 조회와 Redis 세션을 확인하는 방법이다. 실제 화면, 운영 Google 계정, 운영 DB, 유료 호출, 배포의 완료 증거가 아니다. 지도 전체 인수 조건은 [#219 계약](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)과 PR #221의 문서에 있다.

| 변경 | 결합한 Draft PR 기준 | 이 브랜치의 확인 범위 |
| --- | --- | --- |
| #220·#222·#224·#228 | #225·#226·#233·#235 | 위치 모델·Google adapter·관리자 저장/worker·유지보수, V31~V33 |
| #223·#227 | #229·#234 | 공개 지도 조회·Redis 세션·페이지 재검증 |
| #242 | #244 `c94c2fb` | 기존 #216 컬렉션 CRUD에 전체 핀·저장 수·본인 저장 여부 연결, V34 |

이 브랜치의 자체 변경은 `RestaurantMapFlowIntegrationTest`, 명시적으로 실행하는 `MapQueryLoadTest`와 k6 스크립트, 테스트 실행 설정과 이 문서다. develop에 병합된 #216 컬렉션 CRUD/스키마를 재구현하지 않는다. V28, V30, V31, V32, V33, V34 순서이며 기존 migration 파일은 수정하지 않는다. #242 결합 전 생긴 `RestaurantPortImpl.findActiveMapInfos` 중복 선언은 동일 메서드 하나를 제거했다.

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

2026-10-01 결합 후 `RestaurantMapFlowIntegrationTest` 5개와 `CollectionVersionMigrationTest` 1개, 총 6개가 failures·errors·skipped 0건으로 통과했다. V31~V34가 있는 MySQL8.4 새 DB에서 Flyway와 Hibernate validation도 통과했다. 전체 build와 원격 CI 결과는 별도로 확인한다.

## 직접 연결한 흐름

`RestaurantMapFlowIntegrationTest`는 실제 `SecurityFilterChain`, 관리자 Controller/Service/RestaurantPort, 위치 worker/작업 저장소, 공개 Controller/Service/Repository, Flyway/JPA, MySQL/Redis를 사용한다. 핵심 서비스·Port·Repository는 mock으로 바꾸지 않는다. 관리자 인증은 합성 JWT를 만들고 무인증 요청의 401도 확인한다.

1. 관리자 POST의 기존 `201 ADMIN-204`와 `PENDING`/revision 1, 같은 DB transaction에서 생성된 durable job을 확인한다. worker 전에는 공개 `map-location`이 409다.
2. worker가 정확한 한 후보를 처리한다. provider 진입에서 활성 DB transaction이 없음을 관측한다. 이후 관리자 `READY`, 공개 현재 위치, 관광 지역 count, RestaurantPort bulk, 공개 첫 페이지에서 같은 식당 ID와 좌표를 확인한다.
3. 주소 B PATCH의 기존 `200 ADMIN-205`, revision 2와 새 `PENDING`을 확인한다. 기존 위치·지역 count·Port 좌표가 사라지고 기존 Redis 세션을 정렬 재조회해도 오래된 카드가 돌아오지 않는다.
4. 주소 A 작업을 claim한 채 B를 저장하고 A의 늦은 성공을 완료한다. 현재 revision을 덮지 못한다. B claim 뒤 삭제하고 늦은 실패를 완료해도 삭제 식당이 공개되지 않는다.
5. Google 결과를 READY로 만든 뒤 합성 DB의 남은 수명을 3시간으로 당기고, provider 예산을 닫은 상태에서 원본 1일 수명보다 짧은 6시간 갱신 창으로 run을 등록한다. 새 작업 등록, 추가 provider 호출 없음, 관리자 상태는 PENDING으로 바뀌지만 유효한 기존 좌표는 현재 위치, Port, 기존 페이지에서 유지되는지 확인한다.
6. 별도의 READY fixture를 보존 경계 안으로 당긴다. provider 예산을 끄고 정리를 실행하여 실제 DB의 좌표·유효기간이 제거되고 현재 위치, Port, 기존 페이지 재조회에서 보이지 않는지 확인한다.

7. 별도 합성 READY 식당 23·500·501·620개를 실제 DB에 만들어 10개씩 끝까지 조회한다. 모든 카드의 `restaurantId`가 중복·누락 없이 예상 식당 ID와 일치하는지 확인한다. 같은 커서를 다시 요청했을 때 같은 결과를 반환하고, 별점이 모두 같으면 정렬을 바꿔도 추천 순서가 유지되는지 확인한다.
8. 관리자 저장·worker 완료로 좌표가 확정된 식당을 USER가 기존 컬렉션 API로 저장한다. 익명 전체 핀, 공개 저장 사용자 수, 본인 저장 여부를 실제 HTTP와 MySQL로 확인한다. 주소 변경으로 좌표가 무효화되면 저장 관계는 남고 전체 핀만 빠지는지 확인한다.

Redis TTL·유실·장애, cursor 변조와 중복 재시도는 [#227 자체 테스트](../../src/test/java/org/sopt/hashi/restaurant/service/RestaurantMapPageIntegrationTest.java)의 실제 Redis 검증을 참조한다. 갱신 등록·CLI dry-run/resume/stop·동시 정리의 상세 반례는 [#228 runbook](location-maintenance-runbook.md)과 해당 MySQL 테스트를 참조한다.

## 별도 인수와 운영 입력

- 현재 CLIENT `/map`은 준비 중 화면이며 관리자도 새 위치 상태를 아직 표시하지 않는다. 이 테스트 성공은 UI QA가 아니다. 카메라 상태, chip 재선택, 카드·핀, 복귀·만료 처리는 실제 화면에서 따로 검증한다.
- #242의 `GET /collections/{id}/map-markers`, `GET /restaurants/save-counts`, `GET /users/me/restaurant-saves`를 develop의 실제 #216 저장 관계·권한과 연결했다. 23개 식당 중 위치 없는 2개가 있어도 목록과 별개로 유효 핀 21개를 한 응답에 반환한다는 별도 #242 MySQL 테스트가 있다. 저장 수는 사용자 수로 세고, 공개 범위/소속 변경 번호를 응답 직전에 재확인하며 부분 200을 허용하지 않는다. 현재 첫 지도 페이지의 카드는 개인 저장 상태를 포함하지 않는다.
- 조회 ID별 키·유휴 만료·전체 메모리 및 호출자 제한은 #234 구현을 사용한다. 아래 실험은 작은 로컬 부하의 회귀 검증이며, 운영 용량을 정하려면 실제 트래픽과 서버 자원에 맞춘 별도 측정이 필요하다.
- 관광 지역 대표 위치·범위·매핑, 운영 Google 계약/키 제한/보관 수명, Redis 용량과 장애 예산, DB 백업·복원 보존 절차는 실제 운영값이 필요하다. 합성 fixture는 이를 대신하지 않는다.
- 실제 Google 호출, 운영 backfill/물리 정리, 배포, 병합은 이 검증의 실행 대상이 아니다.


## 로컬 HTTP 부하·정리·복구 실험

이 테스트는 기존 `test` 작업에서 제외한 `map-load` 태그를 별도 `mapLoadTest` 작업으로 실행한다. k6를 설치한 실행 환경에서 직접 호출하며, 일반 CI 테스트에 k6 설치나 90초 부하를 추가하지 않는다.

```powershell
.\gradlew mapLoadTest -Pk6Executable='C:\tools\k6.exe' --no-daemon --console=plain --max-workers=2
```

- 새 MySQL·Redis 컨테이너와 `127.0.0.1`의 임의 HTTP 포트를 사용한다. 기존 개발 서버 주소를 입력받지 않으며 Google 호출은 비활성이다.
- 합성 식당 620개를 넣고 90초간 최대 2명의 가상 사용자가 신규 조회, 다음 페이지, 같은 커서 재시도, 정렬 변경, 필터 변경을 반복한다. 모든 식당의 별점을 같게 해 동점 순서도 확인한다.
- 동작별 응답 시간, HTTP 오류율, 페이지 중복과 재시도 결과를 확인한다. 1초마다 Redis 메모리, 세션 수, DB 연결 수와 연결 대기 수를 기록한다.
- 시간 기반 동작을 짧게 확인하려고 테스트에만 유휴 10초·최대 30초를 적용한다. 운영 기본값인 유휴 5분·최대 30분을 변경하지 않는다.
- 요청을 멈추면 조회 키가 만료되는지 확인한다. 이어서 테스트의 세션 한도를 2개로 낮춰 3번째 조회가 제한되고, 만료 후 다시 조회되는지 확인한다. 다른 용도의 합성 Redis 키는 이 과정에서 남아 있어야 한다.
- `build/reports/map-load/`에 k6 요약, 실행 로그, 자원 관측값, 복구 결과를 저장한다. 부하 실행이 실패하면 테스트도 실패한다.

이 부하는 기능과 간단한 자원 회복을 확인하는 로컬 실험이다. 실제 사용자 수를 산정하거나 운영 응답 시간 목표를 검증하는 자료로 그대로 사용할 수 없다.
