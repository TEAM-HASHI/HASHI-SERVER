# 지도 범위와 관광 지역 조회

#223은 [지도 계약 PR #221](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/221)의
`d2df3100fcfe693956662aae9ab1845275e163d5`를 기준으로 위치 모델을 읽는 기능을 제공한다.

## 제공하는 기능

- `GET /api/v1/restaurants/map/regions`: 초기 범위, 지원 범위와 1도 제한, 활성 관광 지역의 대표 좌표·화면·식당 수.
- `GET /api/v1/restaurants/{restaurantId}/map-location`: 삭제되지 않은 식당의 현재 유효 위치.
- `RestaurantMapService.findCandidates(criteria, capacity)`: 후보 ID·평점·리뷰 수와 UTC `rankingAsOf`.
  검색어가 있으면 현재 화면 안의 전체 결과 수·좌표 경계·가장 이른 좌표 만료 시각도 함께 제공한다.
- `RestaurantMapService.findMatchingCandidates(criteria, ids)`: 같은 조건을 현재 DB에 다시 적용한 후보 값.
- `RestaurantPort.findActiveMapInfos(ids)`: 컬렉션에서 사용할 이름·분류·장르·선택적 위치.

`GET /restaurants/map` 페이지 API, Redis 세션·추천 순서·정렬·cursor·10개 카드·이미지/가격대 조합은
후속 [#227](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/227)에서 구현한다.
기존 일반 식당 목록의 정렬·cursor와 검색 구현은 유지한다. 지도 검색은 최신 PLAN의 검색 입력 계약을
별도로 적용하며, 두 API의 남은 차이는 아래에 명시한다.

## 조회 조건과 데이터

모든 지도 SQL은 삭제 아님, 유효한 위경도 쌍, 출처·취득 시각·유효기한,
UTC `now < validUntil`을 함께 검사한다. 좌표 갱신 작업 상태는 조회 조건으로 사용하지 않는다.
기존에는 `READY`만 조회해 같은 주소의 갱신이 시작되면 핀이 사라졌다. 이제 갱신 대기·재시도·실패·확인 필요
상태에서도 마지막으로 검증한 좌표가 아직 유효하면 후보, 지역 집계, 단건 위치와 컬렉션 Port에 포함한다.
주소 변경 시에는 좌표를 지우므로 이전 주소의 핀이 나오지 않는다. 만료 시각부터는 모든 지도 조회에서 제외하며,
컬렉션 Port는 식당을 삭제하지 않고 `location=null`로 반환한다. 일반 식당 목록은 좌표 유무와 관계없이 유지한다.
`(0, 0)`은 정상 좌표다. 관광 지역 미분류 식당도 일반 범위 조회에는 포함한다.
native SQL의 기준 시각은 UTC 문자열을 `DATETIME(6)`으로 명시 변환하고, 응답 시각도 UTC DATETIME
필드에서 읽는다. JVM과 MySQL 연결 시간대가 달라도 JDBC Timestamp의 시간대 변환에 의존하지 않는다.

조회용 `MapQueryBounds`는 저장용 `MapBounds`와 별개다. SDK의 긴 소수점 입력을 반올림·절삭하지 않고
MySQL DECIMAL 좌표와 비교한다. 경계는 포함하고 역전·날짜변경선 횡단·유효 범위·1도 초과·지원 영역을 검증한다.
`MapSearchCriteria.of`는 wire 분류 값과 양의 지역 ID, 검색어를 검증한다. 지도 검색어가 있으면
Unicode 공백을 포함한 앞뒤 공백을 제거한 뒤 최대 30 코드포인트이며 개행·제어문자는 거절한다.
일반 검색어는 내부 Unicode 공백으로 단어를 나누고, 각 단어 중 하나라도 식당명·메뉴명·해시태그에
대소문자 구분 없이 부분 일치하면 포함한다. 메뉴와 해시태그는 `EXISTS`로 검사해 한 식당을 한 번만 반환한다.
검색어가 `#`으로 시작하면 뒤의 공백 없는 단일 단어를 해시태그에서만 부분 검색한다. `#`만 있거나
`#` 뒤에 공백 또는 여러 단어가 있는 입력은 400으로 거절한다.
미지정(null)은 두 API 모두 필터를 생략한다. 명시적 빈 문자열·공백만 있는 입력은 일반 목록에서
필터를 생략하지만, 지도에서는 기존 `MAP-03` 계약대로 400으로 거절한다.
`!`를 SQL LIKE escape 문자로 지정해 `%`, `_`, `!`를 모두 리터럴로 검색한다.
현재 일반 `/restaurants` 목록은 앞뒤 공백을 제거한 전체 문구를 식당명·메뉴명에서만 검색한다.
여러 단어 OR, 해시태그, `#` 전용 검색과 30자 제한은 아직 일반 목록에 적용되지 않았다. 따라서 이 변경은
지도 검색 계약만 충족하며, 최신 공통 검색 계약을 일반 목록까지 맞추는 작업은 정렬·cursor와 함께 별도 범위다.

후보 조회는 한 SQL에서 순위 값만 가져온다. `capacity + 1`개를 SQL 상한으로 사용하고 초과하면
`RESTAURANT-016`으로 전체 실패한다. 최종 후보 수·bytes·동시 세션 수용 한도는 후속 Redis 담당자가 측정해 정한다.
이 조회 계층에는 후보 500개 고정 상한이 없다. 아래 ID 배치의 500은 SQL 한 번에 넘기는 묶음 크기이며,
전체 결과를 자르는 제한이 아니다. #234는 기존 호출자의 500개 제한을 byte 예산 기반으로 바꾸고 조회 ID별 Redis 키,
유휴 5분·최대 30분 만료, 메모리 예산과 호출 제한을 적용한다. 상세 계약은 [페이지 조회](map-pagination.md)를 따른다.
`rankingAsOf`는 조회 직전에 캡처한 기준 시각이며 DB commit 시각을 뜻하지 않는다.

검색어가 있는 새 조회는 후보 상한을 통과한 뒤 같은 read-only transaction과 `rankingAsOf`로 집계 SQL을 한 번 더
실행한다. 후보 SQL과 집계 SQL은 삭제·좌표 수명·현재 화면 BBOX·지역·장르·음식점 분류·검색 predicate와
parameter binding을 공유한다. 집계는 페이지 크기와 무관한 `totalCount`, 모든 결과 좌표의 min/max 경계,
`earliestValidUntil`을 반환한다. 0건은 `totalCount=0`, `bounds=null`, `earliestValidUntil=null`이고,
1건은 남북·동서가 같은 경계를 허용한다. 검색어가 없는 일반 탐색은 추가 집계 SQL을 실행하지 않는다.
후속 Redis 세션은 집계 경계를 좌표 수명보다 오래 보관하지 않도록 hard expiry를 `earliestValidUntil`으로 제한해야 한다.

지역 집계는 한 SQL에서 지역 ID와 그 지역의 cameraBounds를 함께 적용한다. 활성 지역 0건도 포함하고
displayOrder·ID 순으로 반환한다. 유효 위치가 cameraBounds 밖에 매핑된 지역은 count에서 그 식당을 제외하며,
`/map/regions`는 유효한 count와 다른 지역을 정상 반환하고 서버에 지역 ID·이상 건수만 기록한다.
이는 계약의 운영 검증을 공개 조회와 구분한 구현 해석이다. 주소 변경 후 지역 ID가 유지되는 경우에도
전체 첫 화면이 막히지 않으며 운영 매핑 확인은 별도로 필요하다. 조회 중 매핑을 자동 수정하지 않는다.

## 설정

운영 좌표와 지역 seed는 포함하지 않았다. 다음 설정 키의 `south`, `north`, `west`, `east`를 준비한다.

[#251 관리자 지역 API](admin-regions.md)는 지역 설정·활성화와 식당 소속 지정·해제를 제공한다.
비활성 지역에 미리 소속을 입력할 수 있고 공개 조회는 활성 지역만 사용한다.
승인된 실제 지역 데이터와 최초·지원 영역을 입력하고 운영 검증하기 전에는 지역 탐색 준비가 끝난 것이 아니다.
최신 PLAN과 이전 클러스터 결정 문서의 차이도 해당 문서에서 구분한다.

| 설정 | 의미 |
| --- | --- |
| `hashi.restaurant.map.initial-bounds.*` | 최초 조회 화면. 각 span 1도 이하이며 지원 영역 안 |
| `hashi.restaurant.map.supported-bounds.*` | 지원 영역 전체. 각 조회 화면의 1도 제한과 구분 |

문자열로 바인딩한 뒤 요청 시점에 파싱하므로 누락·형식 오류가 서버 부팅을 막지 않는다.
지역 안내는 설정 누락·비정상 값·활성 지역 없음·잘못된 지역 화면에서 503 `RESTAURANT-017`을 반환한다.
지역 필터는 없거나 비활성인 ID에 400 `RESTAURANT-012`를 반환한다.
선택 식당과 Port 조회는 초기 화면 설정 없이도 동작한다.

## Port와 재검사

첫 입력 ID 순서를 유지하고 중복을 제거한다. null/빈 컬렉션은 쿼리 없이 빈 목록을 반환한다.
컬렉션 안의 null·0·음수 ID는 잘못된 내부 호출로 거절한다. 없는/삭제된 식당은 생략한다.
Port는 위치가 미준비·만료된 공개 식당을 `location=null`로 남기고, 유효하지 않은 좌표는 SQL JOIN에서 차단한다.

ID 조회는 내부 500개 단위이며 모두 모은 뒤 반환한다. 중간 조회 실패는 부분 결과 없이 전파한다.
식당 수에 따라 엔티티·메뉴·사진·media 단건 조회가 늘어나지 않는다. 모듈 루트의 공개 record에는
문자열 분류와 좌표/UTC 시각 값만 있고 domain enum·Entity·Repository는 없다.

재검사에서 반환한 현재 평점·리뷰 수를 Redis의 고정 순위 값에 덮어쓰면 안 된다. 후속 호출자는
재검사한 ID를 기존 세션 순서에 맞추고 탈락한 후보만 건너뛴다. 컬렉션 소유 모듈은 응답 직전
자신의 열람 권한·멤버십 버전을 재검사하고, 클라이언트는 `validUntil`부터 핀을 폐기한다.

## 오류와 검증

기존 SuccessResponse/ErrorResponse를 유지한다. 좌표 응답은 `Cache-Control: no-store`, data의 만료 시각은 UTC `Z`다.
선택 식당 ID의 양수 제약·정수 형식 위반은 공통 `GlobalExceptionHandler`에 위임해
400 `COMMON-400`, `data: null`을 반환하고 현재 다른 API처럼 `errors`를 생략한다.
이 오류는 Controller 본문 실행 전 발생하므로 기존 Spring Security cache header writer가
`Cache-Control: no-cache, no-store, max-age=0, must-revalidate`를 설정한다.
정상 응답과 본문 실행 이후 오류의 `no-store`는 Controller 또는 DB/transaction advice가 설정한다.
식당 삭제/없음은 404 `RESTAURANT-004`, 위치만 무효하면 409 `RESTAURANT-018`, DB 장애는 503 `RESTAURANT-015`다.
SecurityFilterChain과 기존 공개 경로 정책은 유지한다.

DB 조회 실패와 Service 본문 밖 transaction 실패는 고정 operation `restaurant-map-query`와
예외 클래스명만 WARN으로 남긴다. SQL·검색어·좌표·예외 메시지·cause·stack trace는 기록하지 않는다.

- `RestaurantMapQueryIntegrationTest`: MySQL 8.4, Flyway 전체 migration 후 validate, 실제 후보·검색 결과 경계·Port 조회.
- `RestaurantMapControllerTest`: 실제 SecurityFilterChain, HTTP wrapper·오류·UTC 직렬화·no-store.
- `MapSearchCriteriaTest`, `MapQueryPropertiesTest`, `RestaurantMapServiceTest`: 입력·설정 격리·부분 실패.
- 기존 식당/관리자 회귀와 `ModularityTests`를 함께 검증한다.

시간대 반례 검증에서는 명령 단위로 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`를 지정한다.
지도 MySQL 테스트의 JDBC 연결은 `serverTimezone=Asia/Seoul`이며 SQL로 직접 저장한 UTC DATETIME도 검사한다.

MySQL 모듈 테스트는 기존에 무조건 등록되는 이미지 backfill attachment 빈 하나만 테스트 설정에서 제외한다.
일반 지도 조회에서 migration 전용 Port를 모킹하거나 사용하지 않는다.
EXPLAIN은 테스트가 실행한 후보 SQL에 대해 합성 데이터로 기록한다. 작은 fixture의 접근 경로와
쿼리 수는 운영 데이터 분포·처리 용량·응답 시간의 보장이 아니다.
