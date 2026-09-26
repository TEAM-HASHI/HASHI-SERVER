# ADR 0003: 식당 위치 확인 작업과 호출 예산

- 범위: #224. 선행 계약은 [M1 #219](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/221).
- 운영 Google 활성화, 기존 식당 backfill, 만료 전 갱신과 보관 데이터 정리는 별도 gate다.

## 결정

식당 등록/주소 변경과 처리할 작업을 같은 MySQL transaction에 저장한다. 메모리 이벤트만으로
commit 직후의 종료를 복구할 수 없으므로 `restaurant_location_job`을 Restaurant의 작업 이력으로 둔다.
주소, 좌표, Google 원문은 작업 테이블에 복사하지 않는다. 소유 식당의 ID만 FK로 연결한다.

별도 `RestaurantLocationWorker` Bean이 polling → claim → HTTP/판정 → 완료를 수행한다.
worker는 transaction이 있으면 실행을 거절한다. 별도 `LocationJobTransactions` Bean의 claim/완료는
`REQUIRES_NEW`, `READ_COMMITTED`다. HTTP는 claim commit 뒤에 시작한다.
모든 쓰기 경로는 식당 → 해당 작업(복수이면 ID 순) → 전역 예산 순서로 잠근다.
관리자 저장은 예산 행을 잠그지 않으며 외부 호출도 하지 않는다.

관리자 등록/수정/삭제/재처리의 Port와 Service 진입점도 READ_COMMITTED로 맞춘다.
기본 REPEATABLE_READ에서는 비어 있는 작업 범위의 FOR UPDATE 뒤 INSERT가 서로 다른 식당의
동시 등록에서도 gap lock deadlock을 일으켰다. 부모 식당 잠금으로 같은 식당의 변경을 직렬화하면서
범위 gap lock을 피한다. 후속 작업이 enqueue/cancel을 호출할 때도 이 transaction 조건을 지킨다.

HTTP를 기다리는 polling cycle은 전용 단일 스레드 scheduler에서 실행한다. cycle 중복 적재가 없고,
기존 미디어 작업의 기본 scheduler를 점유하지 않는다. 전용 Bean은 defaultCandidate=false로 두어
Spring Boot의 기본 scheduler 구성을 유지한다. 종료 중인 호출은 중단 후 lease 만료로 복구한다.

`addressRevision`, `requestId`, 작업 ID, lease token, lease 만료와 soft delete를 완료 시 다시 검사한다.
마지막 예산 잠금을 얻은 뒤 DB 시각을 다시 읽는다. 유효하지 않은 성공/실패는 모두 no-op이다.
주소 변경/삭제로 대체된 작업도 이미 예약한 동시 슬롯은 원래 기한까지 유지한다.
HTTP 자체는 중복 실행될 수 있다. DB 완료 실패나 crash 후에는 lease 만료를 기다렸다가 재처리한다.

자동 재시도는 같은 작업 행에서 새로운 M2 requestId를 이어받고 attempt는 보존한다.
관리자 재처리와 주소 변경은 새 작업을 만들며 누적 전역 호출 예산을 우회하지 않는다.
PENDING 재처리는 기존 작업을 돌려준다. 작업 행은 최소 처리 이력만 남긴다.

## 환경 독립적인 제어 행

V29는 `restaurant_geocoding_budget(id=1)`을 `enabled=false`, `daily_limit=0`,
`max_concurrent=0`, `reserved_calls=0`으로 최초 생성한다. 이는 업무 데이터/운영 seed가 아닌
안전한 필수 제어 상태이므로 database convention의 제어 데이터 예외를 사용한다.
시작 코드나 repeatable migration으로 값을 초기화하지 않는다. 행 누락도 호출을 차단한다.

서버별 application 설정으로 전역 한도를 각각 계산하면 서버 수에 따라 비용이 늘어난다.
따라서 같은 DB 행 잠금 아래 UTC 일자·예약량·동시 실행 예약 수를 검사한다. 호출 직전 claim의
commit에 예산을 보수적으로 예약한다. 실제 전송 여부가 불확실해도 일일 사용량을 환급하지 않는다.
timeout/cancel의 실행 슬롯은 lease 기한까지 유지하고 같은 작업을 그 전에 다시 실행하지 않는다.
공유 quota 대기 중에는 다른 식당의 작업과 관리자 재처리도 호출할 수 없다.
마지막 자동 시도에서 quota 오류를 받아도 공유 대기는 기록하며 해당 작업만 FAILED로 끝낸다.

동시 예약 count는 예산 잠금을 얻은 후 최신 commit을 읽어야 하므로 READ_COMMITTED를 사용한다.
식당/작업은 부모 잠금으로 직렬화한다. 전역 예산만 변경하는 운영 transaction은 예산 행만 잠그고
식당/작업을 추가로 잠그지 않아야 한다. 중단은 이미 예약된 호출을 소급 취소하지 않는다.

## UTC 기준

lease·재시도·일일 예산 기준은 DB `UTC_TIMESTAMP(6)`이다. 이 시각은 DATE_FORMAT 문자열로 읽어
JDBC Timestamp의 connectionTimeZone 변환을 피한다. 새 job/budget DATETIME 필드는
`SqlTypes.LOCAL_DATE_TIME`으로 직접 읽고 쓴다. M2 위치 시각도 같은 선행 보완을 사용한다.
응답은 UTC Instant의 ISO-8601이다. 애플리케이션 Clock은 enqueue 이력 시각에 instant 기준으로만 쓴다.

Google 좌표 수명은 예약 직전 DB 시각을 보수적인 취득 기준으로 사용한다. `validUntil`은 이 값에
명시된 retention을 더해 계산한다. 응답 도착/DB 저장 지연은 수명을 연장하지 않는다.
재시도는 새 호출이므로 그 호출의 새 취득 기준을 사용하며 이전 결과/만료일을 재활용하지 않는다.

## 영향

두 테이블과 한 제어 행이 추가된다. 기존 migration과 주소/관광 지역 ID는 유지한다.
새 dependency, 인증 규칙 변경, Redis 의존은 없다. 관리자 저장 응답은 기존 201/200 성공 코드를
유지하고 상태/revision만 추가한다. 기존 위치 없는 식당은 UNRESOLVED/revision 0이며 일반 조회를 유지한다.
일반 정보만 수정할 때 새 작업이나 revision을 만들지 않는다.
삭제된 식당의 기존 관리자 편집 허용 정책은 유지하되 새 위치 작업은 등록하지 않는다.

테스트용 fake provider와 실제 MySQL에서 저장 원자성, lease 경합, 늦은 성공/실패, retry 예산,
JDBC Asia/Seoul 세션의 UTC 원시 값과 왕복을 검증한다. 실행 결과는 PR의 최종 HEAD evidence로 확인한다.
