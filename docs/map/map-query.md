# 지도 범위와 관광 지역 조회

#223은 [지도 계약 PR #221](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/221)의
`d2df3100fcfe693956662aae9ab1845275e163d5`를 기준으로 위치 모델을 읽는 기능을 제공한다.

## 제공하는 기능

- `GET /api/v1/restaurants/map/regions`: 초기 범위, 지원 범위와 1도 제한, 활성 관광 지역의 대표 좌표·화면·식당 수.
- `GET /api/v1/restaurants/{restaurantId}/map-location`: 삭제되지 않은 식당의 현재 유효 위치.
- `RestaurantMapService.findCandidates(criteria, capacity)`: 후보 ID·평점·리뷰 수와 UTC `rankingAsOf`.
- `RestaurantMapService.findMatchingCandidates(criteria, ids)`: 같은 조건을 현재 DB에 다시 적용한 후보 값.
- `RestaurantPort.findActiveMapInfos(ids)`: 컬렉션에서 사용할 이름·분류·장르·선택적 위치.

`GET /restaurants/map` 페이지 API, Redis 세션·추천 순서·정렬·cursor·10개 카드·이미지/가격대 조합은
후속 [#227](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/227)에서 구현한다.
기존 일반 식당 목록·검색·cursor에는 변경이 없다.

## 조회 조건과 데이터

모든 후보 SQL은 삭제 아님, `READY`, 유효한 위경도 쌍, UTC `now < validUntil`을 함께 검사한다.
현재 모델은 주소 변경과 함께 좌표를 지우고 상태를 `PENDING`으로 바꾸므로 이전 주소 좌표가 조회되지 않는다.
`(0, 0)`은 정상 좌표다. 관광 지역 미분류 식당도 일반 범위 조회에는 포함한다.
native SQL의 기준 시각은 UTC 문자열을 `DATETIME(6)`으로 명시 변환하고, 응답 시각도 UTC DATETIME
필드에서 읽는다. JVM과 MySQL 연결 시간대가 달라도 JDBC Timestamp의 시간대 변환에 의존하지 않는다.

조회용 `MapQueryBounds`는 저장용 `MapBounds`와 별개다. SDK의 긴 소수점 입력을 반올림·절삭하지 않고
MySQL DECIMAL 좌표와 비교한다. 경계는 포함하고 역전·날짜변경선 횡단·유효 범위·1도 초과·지원 영역을 검증한다.
`MapSearchCriteria.of`는 wire 분류 값과 양의 지역 ID, 검색어를 검증한다. 검색어는 공백을 정규화한
1~100 코드포인트이며 개행·제어문자는 거절한다. 식당명·메뉴명 검색은 대소문자를 구분하지 않는다.
`!`를 SQL LIKE escape 문자로 지정하고 `%`, `_`, `!`를 모두 리터럴로 변환한다. 메뉴는 `EXISTS`로 검색한다.

후보 조회는 한 SQL에서 순위 값만 가져온다. `capacity + 1`개를 SQL 상한으로 사용하고 초과하면
`RESTAURANT-016`으로 전체 실패한다. 최종 후보 수·bytes·동시 세션 수용 한도는 후속 Redis 담당자가 측정해 정한다.
`rankingAsOf`는 조회 직전에 캡처한 기준 시각이며 DB commit 시각을 뜻하지 않는다.

지역 집계는 한 SQL에서 지역 ID와 그 지역의 cameraBounds를 함께 적용한다. 활성 지역 0건도 포함하고
displayOrder·ID 순으로 반환한다. 유효 위치가 cameraBounds 밖에 매핑된 지역은 count에서 그 식당을 제외하며,
`/map/regions`는 유효한 count와 다른 지역을 정상 반환하고 서버에 지역 ID·이상 건수만 기록한다.
이는 계약의 운영 검증을 공개 조회와 구분한 구현 해석이다. 주소 변경 후 지역 ID가 유지되는 경우에도
전체 첫 화면이 막히지 않으며 운영 매핑 확인은 별도로 필요하다. 조회 중 매핑을 자동 수정하지 않는다.

## 설정

운영 좌표와 지역 seed는 포함하지 않았다. 다음 설정 키의 `south`, `north`, `west`, `east`를 준비한다.

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
선택 식당 ID의 양수 제약·정수 형식 위반은 400 `COMMON-400`과 `restaurantId`의 필드별 `errors`를 반환한다.
식당 삭제/없음은 404 `RESTAURANT-004`, 위치만 무효하면 409 `RESTAURANT-018`, DB 장애는 503 `RESTAURANT-015`다.
SecurityFilterChain과 기존 공개 경로 정책은 유지한다.

- `RestaurantMapQueryIntegrationTest`: MySQL 8.4, Flyway 전체 migration 후 validate, 실제 후보·집계·Port 조회.
- `RestaurantMapControllerTest`: 실제 SecurityFilterChain, HTTP wrapper·오류·UTC 직렬화·no-store.
- `MapSearchCriteriaTest`, `MapQueryPropertiesTest`, `RestaurantMapServiceTest`: 입력·설정 격리·부분 실패.
- 기존 식당/관리자 회귀와 `ModularityTests`를 함께 검증한다.

시간대 반례 검증에서는 명령 단위로 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`를 지정한다.
지도 MySQL 테스트의 JDBC 연결은 `serverTimezone=Asia/Seoul`이며 SQL로 직접 저장한 UTC DATETIME도 검사한다.

MySQL 모듈 테스트는 기존에 무조건 등록되는 이미지 backfill attachment 빈 하나만 테스트 설정에서 제외한다.
일반 지도 조회에서 migration 전용 Port를 모킹하거나 사용하지 않는다.
EXPLAIN은 테스트가 실행한 후보 SQL에 대해 합성 데이터로 기록한다. 작은 fixture의 접근 경로와
쿼리 수는 운영 데이터 분포·처리 용량·응답 시간의 보장이 아니다.
