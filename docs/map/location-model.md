# 식당 위치·관광 지역 모델 (#220)

이 변경은 지도 데이터의 저장·검증 기반이다. 지도 API, 관리자 위치 API, Google 호출,
worker/lease, Redis 세션, 실제 지역 데이터와 backfill은 후속 작업이다.
[선행 계약 #219](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)의 상태·노출 의미를 따른다.

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

V28은 새 두 테이블과 nullable 참조 두 개를 추가한다. 기존 데이터는 미분류/위치 없음으로 보존한다.
마이그레이션에는 HTTP, job 생성, 업무 seed가 없다. 적용된 이전 migration은 수정하지 않는다.
큰 restaurant 테이블의 DDL 소요·잠금 시간은 운영 대상 규모로 배포 전에 측정해야 한다.

## 상태와 수명

| 상태 | 의미 | 저장 규칙 |
|---|---|---|
| 행 없음 (UNRESOLVED) | 아직 위치 요청을 기록하지 않음 | 일반 식당 API에 그대로 노출 가능 |
| PENDING | 현재 주소의 위치 요청을 기록함 | 좌표·출처·취득/만료·재시도 시각 없음 |
| READY | 검증된 위치 결과를 저장함 | 좌표 쌍·출처·obtainedAt·validUntil 필수, 취득 < 만료 |
| RETRY_WAIT | 일시 오류 뒤 재시도 대기 | nextAttemptAt만 필수, 좌표 없음 |
| REVIEW_REQUIRED | 주소·후보·정확도를 운영자가 확인해야 함 | 좌표 없음, 자동 재시도 없음 |
| FAILED | 설정 문제 또는 재시도 소진으로 자동 처리 중단 | 좌표 없음, 자동 재시도 없음 |

첫 위치 요청의 addressRevision은 1이다. 위치가 있는 식당의 주소 변경은 같은 transaction에서
revision을 증가시키고 새 requestId/PENDING으로 바꾸며 이전 좌표와 수명 정보를 제거한다.
관광 지역 ID는 그대로 둔다. 같은 주소의 PENDING 재요청은 기존 requestId를 유지한다.
실패·확인 필요·재시도 대기에서 명시적 재요청하거나 READY를 갱신하면 새 requestId를 발급한다.
자동 재시도는 nextAttemptAt 이상일 때만 가능하다. READY는 일반 retry 대신 refresh를 사용한다.

결과 반영은 PENDING 및 revision/requestId 일치 시에만 가능하다. 늦은 주소 결과,
같은 주소의 이전 요청, 중복 완료, 삭제 식당의 완료는 false로 종료한다.
Location의 낙관적 버전도 오래된 영속 객체의 덮어쓰기를 막는다.
이것은 lease 검증이나 전체 worker 동시성 구현 완료를 의미하지 않는다.

출처는 GOOGLE_GEOCODING / OPERATOR이며 둘 다 명시적 수명을 가진다. 운영자가 Google 결과를
확인했다는 이유로 OPERATOR로 바꾸지 않는다. 출처를 포함한 새 결과는 새로운 요청으로만 저장한다.
시각은 UTC `DATETIME(6)`이며 Clock의 zone과 관계없이 instant를 UTC로 해석한다.
DB 정밀도에 맞춰 취득/만료/재시도 시각의 나노초를 마이크로초로 내린 뒤 검증한다.
취득 시각은 미래일 수 없고 저장 시점에 이미 만료된 결과도 거절한다.

공개 지도 판정은 `!deleted && location != null && READY && now < validUntil`이다.
주소 변경이 기존 location을 즉시 초기화하므로 READY는 현재 주소 revision의 결과만 가질 수 있다.
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
5. 지도 조회는 위치를 명시적으로 join/projection하고 삭제·상태·만료를 SQL에서도 검사한다.
   지역 미분류를 inner join으로 누락하지 않는다. 일반 조회 DTO에 위치를 무조건 붙이지 않는다.
6. Google 계약에 따른 허용 수명·제거·백업 정책, 운영 지역 값, 지원 영역과 조회 크기는
   실제 연동 전에 확인한다. 이 PR에는 유료 호출·운영 DB 조회/갱신·backfill·배포가 없다.

새 dependency와 전역 Clock 설정 변경은 없다. 기존 japanClock도 instant를 통해 UTC 수명 판정에
사용할 수 있으며 테스트는 고정 Clock을 사용한다.
