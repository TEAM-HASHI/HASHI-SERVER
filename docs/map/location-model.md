# 식당 위치·관광 지역 모델 (#220)

이 변경은 지도 데이터의 저장·검증 기반이다. 지도 API, 관리자 위치 API, Google 호출,
worker/lease, Redis 세션, 실제 지역 데이터와 backfill은 후속 작업이다.
이번 #225 수정은 같은 주소의 갱신 중에도 기존 유효 좌표를 사용할 수 있게 한다.
전체 구현 방향과 후속 검증 기준은 [구현 계획](implementation-plan.md)에 함께 정리한다.

## 변경 범위와 소유권

- `Restaurant`가 선택적 `RestaurantLocation`을 단방향 LAZY 1:1로 소유한다.
  `restaurant.location_id`는 unique FK이며 같은 Aggregate 안의 관계다.
  기존 식당과 지금의 관리자 신규 등록은 location이 null이다. 이를 UNRESOLVED로 해석한다.
- 부모가 FK를 가지므로 일반 조회에서 위치 행 존재 여부를 확인하는 추가 조회가 필요 없다.
  [Hibernate의 optional inverse 1:1 설명](https://docs.hibernate.org/orm/6.6/introduction/html_single/#one-to-one)
  및 실제 MySQL의 query-count 회귀 테스트로 확인한다.
- `MapRegion`은 restaurant 모듈의 별도 Aggregate다. 식당은 nullable `map_region_id`만 보관한다.
  지역을 향한 FK/JPA 연관은 없다. 후속 운영 Service가 지역의 존재·활성을 검사하며
  없는/비활성 지역의 ID는 지역 집계에서 제외한다. 일반 BBOX 탐색은 지역 유무와 무관하다.
- 기존 `area`는 표시 문자열, `placeType`은 음식점·카페·주점 분류다. 관광 지역 코드와 자동 변환하지
  않는다. Google address components도 지역을 자동 확정하지 않는다.
- 기존 Controller·Service·Port·요청/응답 DTO와 평점 통계 갱신 경로는 유지한다.
  주소가 실제로 달라질 때만 이미 있는 위치를 무효화한다. 일반 정보 수정은 위치에 영향을 주지 않는다.

## 값과 DB 제약

좌표는 WGS84 위도 [-90,90], 경도 [-180,180]의 `BigDecimal` 한 쌍이다.
위도 `DECIMAL(9,6)`, 경도 `DECIMAL(10,6)`로 저장한다. Java는 소수점 6자리로 정확히 표현할 수
없는 값을 거절하고, 뒤에 붙은 0은 허용한다. 외부 응답을 이 정밀도로 변환하는 정책은 후속 좌표
채택·저장 단계에서 명시해야 한다. HTTP adapter는 원래 정밀도의 후보를 반환한다.
MySQL DECIMAL은 직접 SQL의 초과 소수 자릿수를 반올림할 수 있으므로
DB CHECK가 원래 입력의 정밀도까지 검증한다고 주장하지 않는다.
BigDecimal에는 NaN/Infinity가 없으며 후속 HTTP 경계에서도 숫자 변환 실패를 거절한다.
`(0,0)`은 유효한 세계 좌표다. 변환 실패의 대체값으로 사용하지 않는다.

`MapBounds`는 경계 포함, `south < north`, `west < east`와 같은 정밀도를 검증한다.
도쿄 1차에서는 날짜변경선 횡단을 지원하지 않는다. 1도 span 제한·supportedBounds 검사는
후속 조회 Service의 정책이다. 기하 모델 자체를 도쿄의 가상 좌표로 제한하지 않는다.
관광 지역의 대표 좌표는 cameraBounds 안에 있어야 한다. 코드는 `[A-Z][A-Z0-9_]{0,39}`,
이름은 1~100자이며 Unicode 공백과 Java 공백 구분 문자만 있는 값은 Java·DB에서 모두 거절한다.
displayOrder는 0 이상이다. 지역은 명시적으로 활성화하기 전까지 비활성이다.
실제 코드·대표 위치·bounds를 migration에 넣지 않는다.

V31은 새 두 테이블과 nullable 참조 두 개를 추가한다. 기존 데이터는 미분류/위치 없음으로 보존한다.
마이그레이션에는 HTTP, job 생성, 업무 seed가 없다. 적용된 이전 migration은 수정하지 않는다.
큰 restaurant 테이블의 DDL 소요·잠금 시간은 운영 대상 규모로 배포 전에 측정해야 한다.

2026-10-06 기준 V31은 `develop`에 없는 미병합·미출시 migration이다.
[DB 규칙](../conventions/database.md)의 merge 후 수정 금지 기준에 따라 V31에서 갱신 중 좌표
보존을 허용하도록 CHECK를 수정한다. 새 컬럼은 추가하지 않는다. 좌표 쌍·출처·취득/만료 시각은
전부 있거나 전부 없어야 하며, READY에는 반드시 있어야 한다. 일부 값이나 잘못된 수명만 저장할
수는 없다. 로컬 DB 적용 이력은 확인하지 않았다.
기존 V31을 적용한 로컬 DB는 수정본을 바로 적용하거나 checksum만 repair하지 않는다.
데이터 폐기가 허용된 환경은 재생성하고, 보존이 필요하면 적용 이력·기존 `OPERATOR` 값·CHECK를
확인해 전환 절차를 별도 결정해야 한다. PR #249의 다른 V31과 번호가 충돌하므로 병합 전에
migration 소유자와 최종 순서를 합의해야 한다. 여기서는 번호 재선점이나 다른 PR 통합을 하지 않는다.

## 상태와 수명

| 상태 | 의미 | 저장 규칙 |
|---|---|---|
| 행 없음 (UNRESOLVED) | 아직 위치 요청을 기록하지 않음 | 일반 식당 API에 그대로 노출 가능 |
| PENDING | 현재 주소의 위치 요청을 기록함 | 같은 주소의 이전 검증 좌표 보존 가능, 재시도 시각 없음 |
| READY | 검증된 위치 결과를 저장함 | 좌표 쌍·출처·obtainedAt·validUntil 필수, 취득 < 만료 |
| RETRY_WAIT | 일시 오류 뒤 재시도 대기 | nextAttemptAt 필수, 같은 주소의 이전 검증 좌표 보존 가능 |
| REVIEW_REQUIRED | 주소·후보·정확도를 운영자가 확인해야 함 | 같은 주소의 이전 검증 좌표 보존 가능, 자동 재시도 없음 |
| FAILED | 설정 문제 또는 재시도 소진으로 자동 처리 중단 | 같은 주소의 이전 검증 좌표 보존 가능, 자동 재시도 없음 |

status는 현재 작업 상태다. 저장된 좌표의 사용 가능 여부는 별도로 판정한다.
처음 주소를 변환할 때는 기존 좌표가 없지만, 같은 주소를 갱신할 때는 마지막 검증 결과가 남는다.
갱신 시작·재시도·실패만으로 출처·취득/만료 시각을 바꾸지 않는다. 새 결과의 검증이 모두 끝나야
좌표와 출처·수명을 함께 교체한다. 실패한 갱신 때문에 기존 validUntil을 연장하지 않는다.

첫 위치 요청의 addressRevision은 1이다. 위치가 있는 식당의 주소 변경은 같은 transaction에서
revision을 증가시키고 새 requestId/PENDING으로 바꾸며 이전 좌표와 수명 정보를 제거한다.
관광 지역 ID는 그대로 둔다. 같은 주소의 PENDING 재요청은 기존 requestId를 유지한다.
실패·확인 필요·재시도 대기에서 명시적 재요청하거나 READY를 갱신하면 새 requestId를 발급한다.
자동 재시도는 nextAttemptAt 이상일 때만 가능하다. READY는 일반 retry 대신 refresh를 사용한다.

결과 반영은 PENDING 및 revision/requestId 일치 시에만 가능하다. 늦은 주소 결과,
같은 주소의 이전 요청, 중복 완료, 삭제 식당의 완료는 false로 종료한다.
Location의 낙관적 버전도 오래된 영속 객체의 덮어쓰기를 막는다.
이것은 lease 검증이나 전체 worker 동시성 구현 완료를 의미하지 않는다.

현재 모델의 출처는 GOOGLE_GEOCODING / ADMIN이며 둘 다 명시적 수명을 가진다. `@Enumerated(STRING)`으로
DB에도 같은 이름을 저장한다. 운영자가 Google 결과를 확인했다는 이유로 ADMIN으로 바꾸지 않는다.
출처를 포함한 새 결과는 새로운 요청으로만 저장한다.
독립적으로 확보한 자체 좌표에 Google 보관 조건을 그대로 적용하지 않는 것이 후속 정책이다.
ADMIN의 수명을 선택 사항으로 바꾸는 코드·DB·응답 변경은 이번 PR에 포함하지 않는다.
시각은 UTC `DATETIME(6)`이며 Clock의 zone과 관계없이 instant를 UTC로 해석한다.
DB 정밀도에 맞춰 취득/만료/재시도 시각의 나노초를 마이크로초로 내린 뒤 검증한다.
취득 시각은 미래일 수 없고 저장 시점에 이미 만료된 결과도 거절한다.

`obtainedAt`, `validUntil`, `nextAttemptAt`은 `@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)`으로
JDBC에서 `LocalDateTime`을 직접 전달하고 읽는다. UTC로 계산한 연월일·시분초를 그대로 보존하며,
중간 `Timestamp` 변환에서 JVM과 연결 시간대 차이만큼 값이 이동하는 것을 방지한다.
[Hibernate의 직접 매핑](https://docs.hibernate.org/orm/6.6/javadocs/org/hibernate/type/SqlTypes.html#LOCAL_DATE_TIME)을
이 세 필드에만 적용한다. `BaseTimeEntity`, 일반 운영 시각과 전역 시간대 설정은 기존 동작을 유지한다.
실제 MySQL에서 JVM/JDBC 각각 UTC·Asia/Seoul의 네 조합을 검증하며, DB 원문 값과 ORM 왕복,
insert/update, 직접 SQL로 넣은 값의 로딩, 마이크로초 만료·재시도 경계를 확인한다.
후속 native SQL 조회의 시간 인자 바인딩은 별도로 UTC 계약을 지켜야 한다.

도메인의 지도 사용 판정은 삭제 아님, 완전한 검증 좌표 묶음 존재, `now < validUntil`이다.
READY 여부는 판정 조건이 아니다. 주소가 바뀌면 좌표를 즉시 비우므로 보존된 좌표도 현재 주소의 결과다.
관광 지역이 없어도 이 조건을 만족하면 일반 BBOX 대상이다.
soft delete는 위치 행을 물리 삭제하지 않는다. 예약·리뷰의 과거 조회와 일반 공개 제외 규칙을 유지한다.
만료는 Clock 판정으로 즉시 제외하며, 만료 데이터의 실제 제거는 후속 수명 관리 작업이 담당한다.
조회 제외만으로 보관 정책 이행이 끝나지는 않는다.

## 후속 worker와 조회 구현의 경계

1. 관리자 등록/주소 변경 Service에서 식당 저장·위치 요청·durable job을 같은 transaction에 묶는다.
   지금의 관리자 신규 등록에는 요청을 자동 생성하지 않는다. 상태 DTO도 후속 PR에서 추가한다.
2. 기존 `RestaurantRepository.findByIdForUpdate`로 부모를 먼저 잠근 뒤 위치를 변경한다.
   완료/재시도/삭제/주소 변경이 이 순서를 공유해야 한다. detached Restaurant를 받아 저장하지 않는다.
3. job ID·lease token/만료·attempt·안전한 failureCode·쿼터 gate를 후속 worker에서 구현한다.
   requestId만으로 만료된 lease나 동일 요청의 중복 worker를 구분할 수 없다.
4. HTTP는 DB transaction 밖에서 수행하고 완료 transaction에서 부모 삭제 여부,
   revision/requestId와 job/lease를 모두 재검사한다. 재시도 횟수·backoff는 이 모델에 만들지 않았다.
5. 지도 조회는 위치를 명시적으로 join/projection하고 삭제·좌표 묶음·만료를 SQL에서도 검사한다.
   지역 미분류를 inner join으로 누락하지 않는다. 일반 조회 DTO에 위치를 무조건 붙이지 않는다.
6. Google 계약에 따른 허용 수명·제거·백업 정책, 운영 지역 값, 지원 영역과 조회 크기는
   실제 연동 전에 확인한다. 이 PR에는 유료 호출·운영 DB 조회/갱신·backfill·배포가 없다.
7. 관광 지역 등록·수정·활성화와 식당의 지역 소속을 설정하는 운영 API/Service는 아직 없다.
   실제 지역 데이터도 없으므로 지역 탐색을 활성화할 수 없다. 승인된 지역 데이터와 함께
   지역 존재·활성 검증을 포함한 관리자 쓰기 경로를 별도 후속 범위로 구현해야 한다.

새 dependency와 전역 Clock 설정 변경은 없다. 기존 japanClock도 instant를 통해 UTC 수명 판정에
사용할 수 있으며 테스트는 고정 Clock을 사용한다.

### 후속 PR을 연결하기 전 확인할 점

이 PR의 도메인 수정만으로 지도 API와 만료 정리가 완성되지는 않는다. 2026-10-06 확인한
통합 브랜치에는 다음 READY 전제가 남아 있으므로 공개 활성화 전에 함께 수정·검증해야 한다.

- [#229](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/229)의 `RestaurantMapQueryRepository`:
  READY만 고르는 SQL을 바꾸고 일반 목록·선택 위치·지역 집계·컬렉션 Port의 판정을 맞춘다.
- [#233](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/233)의 worker와 관리자 상태 DTO:
  현재 작업 상태와 기존 좌표 사용 가능 여부를 구분하고, 실패·재시도에서도 기존 수명을 보존한다.
- [#235](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/235)의 `LocationRetentionReader`,
  `RestaurantLocation.purgeGoogle`와 유지보수 reader/inspection/transactions:
  갱신 중·실패 상태의 Google 좌표도 만료 전에 제거한다. 제거하면서 진행 중 requestId·job·재시도를
  취소하지 않아야 한다. 기존 `purgeGoogle`은 `beginPending`에 좌표 제거를 의존하므로 명시적으로
  바꾸어야 한다. 제거와 새 결과 저장이 겹칠 때 새 좌표를 지우지 않는 검사도 유지한다.

현재 PR의 테스트는 도메인 전이, 실제 MySQL CHECK·왕복·주소 PATCH·낙관적 잠금과 시간대 경계를
확인한다. 후속 PR은 갱신 중 공개 조회·만료 정리·worker 복구를 연결한 통합 테스트가 추가로 필요하다.
