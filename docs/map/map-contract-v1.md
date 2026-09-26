# Map Contract v1

- 작성: 2026-09-26, [#219](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)
- 상태: 후속 구현을 위한 계약. 아래 신규 API·상태·오류는 아직 서버에 구현되지 않았다.
- 구조 결정: [ADR 0002](../adr/0002-restaurant-map-query-and-location.md)
- 구현 순서·테스트 인수 기준: [Implementation Plan](./implementation-plan.md)

## 1. 근거와 현재 구현

기획 기준은 PLAN `cb29913817a113ac7b2bc4d8d92a4232a3998d2c`다.

| 근거 | 확정 요구 |
|---|---|
| [MAP_MAIN](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/cb29913817a113ac7b2bc4d8d92a4232a3998d2c/02_PRODUCT_SPEC/MAP/MAP_MAIN/MAP_MAIN.md) | 이동만으로 재조회하지 않음, 일반 목록·핀을 10개씩 함께 추가, 추천·별점·리뷰 정렬 |
| [MAP_FILTERED](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/cb29913817a113ac7b2bc4d8d92a4232a3998d2c/02_PRODUCT_SPEC/MAP/MAP_FILTERED/MAP_FILTERED.md) | 선택 chip 재선택도 현재 viewport로 새 조회, 정렬 유지 |
| [MAP_RESTAURANT_DETAIL](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/cb29913817a113ac7b2bc4d8d92a4232a3998d2c/02_PRODUCT_SPEC/MAP/MAP_RESTAURANT_DETAIL/MAP_RESTAURANT_DETAIL.md) | 상세 복귀 시 지도 상태 유지, 삭제·비노출 식당 선택 차단 |
| [DEC-001](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/cb29913817a113ac7b2bc4d8d92a4232a3998d2c/07_DECISIONS/DEC-001_MAP_INITIAL_CLUSTERING.md) | 행정구가 아닌 관광 지역 초기 클러스터, 이후 동적 클러스터 없음 |
| [SAVED_COLLECTION](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/cb29913817a113ac7b2bc4d8d92a4232a3998d2c/02_PRODUCT_SPEC/SAVED/SAVED_COLLECTION/SAVED_COLLECTION.md) | 유효 좌표만 핀, 좌표 없는 공개 식당은 목록 유지, 같은 사용자의 마지막 저장 관계 해제 때만 전체 저장 수 감소 |

선택 컬렉션의 **모든 유효 핀을 목록과 독립 로드**하는 것은 이번 작업의 명시 요구다.
지역 매핑·검색 진입 카메라·지역 필터 해제·세션 수명·전송 방식 등 아래의 세부 기술 규칙은
M1에서 채택한 구현안이다. 기획 원문에 이미 확정된 UI 정책으로 소급해서 해석하지 않는다.

SERVER 기준은 `5082e01d5c821be92726751f8f528092bde8bc7f`다.

| 현재 코드 근거 | 유지할 점 / 후속 차이 |
|---|---|
| [RestaurantController](../../src/main/java/org/sopt/hashi/restaurant/web/RestaurantController.java), [RestaurantService](../../src/main/java/org/sopt/hashi/restaurant/service/RestaurantService.java) | `/api/v1/restaurants`의 기존 cursor·`basic/popular/rating` 유지. 지도는 별도 API·정렬 타입 |
| [Restaurant](../../src/main/java/org/sopt/hashi/restaurant/domain/Restaurant.java), [Specifications](../../src/main/java/org/sopt/hashi/restaurant/domain/RestaurantSpecifications.java), [Repository](../../src/main/java/org/sopt/hashi/restaurant/domain/RestaurantRepository.java) | 현재 공개 조건은 `deleted=false`; 별도 공개 상태·좌표·관광 지역 필드는 없음. 검색은 식당명·메뉴명 |
| [RestaurantPlaceType](../../src/main/java/org/sopt/hashi/restaurant/domain/RestaurantPlaceType.java) | wire 값 `restaurant/cafe/bar`. `genre`·자유문구 `foodCategory`와 다른 축 |
| [목록 DTO](../../src/main/java/org/sopt/hashi/restaurant/dto/RestaurantListResponse.java), [직렬화 테스트](../../src/test/java/org/sopt/hashi/restaurant/dto/RestaurantListResponseTest.java) | `content/hasNext`, 마지막 `nextCursor` 생략. 지도 전용 DTO로 확장 |
| [RestaurantPort](../../src/main/java/org/sopt/hashi/restaurant/RestaurantPort.java), [user](../../src/main/java/org/sopt/hashi/user/UserPort.java) | `user → restaurant` 유지. 컬렉션 API는 아직 없고 [#216](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/216)과 연동 필요 |
| [SecurityConfig](../../src/main/java/org/sopt/hashi/auth/internal/security/SecurityConfig.java), [RedisConfig](../../src/main/java/org/sopt/hashi/config/RedisConfig.java) | 식당 공개 경로·USER/ADMIN 분리 유지. 새 공개 컬렉션 GET matcher는 후속 변경. 지도 세션 TTL은 명시적으로 설정 |
| [관리자 Controller](../../src/main/java/org/sopt/hashi/admin/web/AdminRestaurantController.java), [모듈 검증](../../src/test/java/org/sopt/hashi/ModularityTests.java) | admin은 Port에 위임. DB 작업·HTTP를 감싸는 transaction 경계를 새로 검증 |

## 2. 지도 상태와 조회 조건

`viewport`는 현재 화면, `queryBounds`는 마지막 성공한 조회의 BBOX다. 지도를 움직여도
추가 페이지와 정렬 변경은 `queryBounds`를 사용한다. FE는 요청 세대 번호를 올리고 마지막
세대의 성공 응답만 채택한다. 새 조회가 성공하기 전까지 이전 결과를 유지하되 로딩·오류를 구분한다.

| 동작 | 카메라·필터·세션 규칙 |
|---|---|
| 지도 탭의 새 진입 | `/map/regions` 성공 후 initialBounds가 보이게 이동하고 실제 viewport로 `/map` 최초 조회(recommend, 필터 없음). 첫 10개 목록과 핀 데이터를 함께 확보. 관광 안내 상태에서는 지역 클러스터를 렌더링하고 개별 핀은 중복 표시하지 않음 |
| 관광 안내 종료 | 지역·chip·검색·목록 카드 선택, 정렬 변경 또는 첫 추가 조회 때 개별 핀 상태로 전환. 이후 content에 있는 식당만 핀으로 표시. 초기 클러스터와 첫 목록의 공존을 위한 M1 렌더링 채택안이며 FE 통합에서 확인 |
| 지역 클러스터 선택 | 해당 cameraBounds를 포함하도록 이동이 끝난 뒤 실제 viewport BBOX와 mapRegionId로 새 조회. keyword·genre·placeType·sort는 유지 |
| 이동·확대·축소 | viewport만 변경. 핀·목록·queryBounds·선택 필터·세션 유지. 자동 조회·재클러스터링 없음 |
| chip 선택·재선택 | 현재 viewport를 새 BBOX로 사용. 선택 placeType 반영, keyword·genre·sort 유지, **mapRegionId 해제**. 이동 후 같은 chip으로 다른 지역을 조회할 수 있음 |
| `전체` chip | 위 규칙과 같고 placeType도 생략. `MAP_MAIN`으로 돌아가도 관광 안내 클러스터는 재생성하지 않음 |
| 검색 결과에서 지도 진입 | 전달받은 keyword·genre·placeType만 적용하고 mapRegionId 해제, sort는 recommend. 진입 시 `/map/regions`의 initialBounds로 한 번 이동 후 조회. 검색 결과 첫 식당/미확인 대표 좌표로 임의 이동하지 않음 |
| 정렬 변경 | 기존 querySessionId에 새 sort만 전달, 같은 후보·추천 순서를 재정렬한 첫 10개로 **교체**. queryBounds·필터 유지, viewport로 새 검색하지 않음 |
| 목록 추가 | 같은 cursor의 다음 10개와 그 핀만 추가. 중복 응답은 같은 페이지로 취급하고 ID 중복 추가 금지 |
| 상세 열기·닫기/로그인·예약 후 복귀 | 카메라·검색·필터·정렬·목록 상태 복원. 세션이 만료됐으면 아래 만료 규칙 적용 |
| 세션 만료·유실 후 새로 조회 | 현재 viewport·keyword·genre·placeType·sort로 새 세션, mapRegionId 해제. 성공한 첫 페이지로 교체. 기존 cursor에 연결 금지 |

최초 지역 설정 조회 실패는 초기 지도 재시도로, 설정 조회 성공 후 첫 목록 실패는 클러스터를 유지한
목록 재시도로 처리한다. 목록 성공 전 가짜 식당을 표시하지 않는다. 최초 클러스터 count는 목록 10개의
개수가 아니라 해당 지역 전체 유효 식당 수다. 관광 안내 종료 후 상세를 닫아도 클러스터로 돌아가지 않는다.

## 3. 위치와 관광 지역의 유효성

- WGS84 `latitude/longitude`를 한 쌍으로 취급한다. 지도 표시 가능은 현재 공개 조건,
  삭제 아님, `READY`, 현재 주소 revision과 일치, 유한한 좌표 쌍, `now < validUntil`을 모두 만족함이다.
  향후 공개 상태가 추가되면 이 공통 판정에 포함한다. `READY`만 검사하면 안 된다.
- 식당당 대표 `mapRegionId`는 0~1개. 기존 `area` 표시 문자열·행정구와 동일시하지 않는다.
  모호한 기존 area는 미분류로 남기고 운영자가 확인한다. 미분류도 BBOX 일반 조회에는 포함할 수 있다.
- 지역의 `name`, `clusterPosition`, `cameraBounds`, `displayOrder`, 활성 여부는 restaurant 소유 설정/데이터다.
  실제 대표 위치·범위·식당 매핑은 미확인이다. 테스트는 합성 fixture를 사용한다.
- 지역 수는 해당 지역 ID **및 그 cameraBounds** 안의 표시 가능한 식당 수다. `/map`에 같은 조건을
  주면 같은 판정이 적용된다. 활성 지역의 bounds 밖 매핑은 운영 검증 실패로 잡아 집계 차이를 숨기지 않는다.
- API가 반환한 `location.validUntil`에 도달하면 FE도 해당 좌표·핀을 폐기한다. 세션 오류 중에도
  만료 좌표를 유지하지 않는다. 목록 자체는 남길 수 있고, 새 좌표는 명시적 새 조회로 받는다.

## 4. 공개 지도 API

모든 경로는 `/api/v1` 기준이다. 성공은 기존 `SuccessResponse`의
`success/code/message/data`, 조회는 HTTP 200·`COMMON-200`·`요청에 성공했습니다`를 사용한다.
아래 신규 endpoint·필드·오류 번호는 M1 채택 계약이며 후속 구현 시 최신 코드와 충돌을 확인한다.
기존 일반 목록 endpoint나 enum의 의미는 변경하지 않는다.

### 4.1 관광 지역

`GET /restaurants/map/regions` — 공개. 요청 필터 없음.

`data`는 `initialBounds`, `queryLimits`, `regions`를 가진다.
`queryLimits={supportedBounds,maxLatitudeSpan:1,maxLongitudeSpan:1}`은 최초 구현의 제한값이다.
모든 Bounds 객체는 `{south,north,west,east}`다. 실제 지원 영역·initialBounds는 운영 입력이며 아직
확정된 좌표가 없다. 다양한 화면 비율에서 실제 viewport도 조회 제한을 만족하게 검증한다. 설정 미완료/잘못된 bounds는
503 `RESTAURANT-017`; 임의의 도쿄 좌표나 빈 성공으로 대체하지 않는다.

`regions[]={mapRegionId,name,restaurantCount,clusterPosition:{latitude,longitude},cameraBounds,displayOrder}`.
ID는 양의 정수다. 활성 지역을 displayOrder·ID 오름차순으로 반환하며 0건 지역도 포함한다.
수는 최초 안내용 전체 분류 기준이고 페이지마다 동기화된 실시간 수를 보장하지 않는다.

### 4.2 목록과 핀

`GET /restaurants/map` — 공개. 다음 세 요청 모드는 서로 배타적이다.

| 모드 | 허용 파라미터 |
|---|---|
| 새 조회 | 필수 `south,north,west,east`; 선택 `mapRegionId,keyword,genre,placeType,sort` |
| 같은 세션 정렬 변경 | 필수 `querySessionId,sort`만; cursor·BBOX·필터 금지 |
| 다음 페이지 | `cursor`만; querySessionId·sort·BBOX·필터 금지 |

- 페이지 크기는 10으로 고정하고 `size`를 받지 않는다. 잘못된 모드 혼합·중복 파라미터·알 수 없는
  파라미터는 400 `COMMON-400`이다. 기존 목록 cursor는 호환하지 않는다.
- BBOX는 유한한 수, 위도 [-90,90], 경도 [-180,180], south < north, west < east를 검증한다.
  경계 포함이며 날짜변경선 횡단은 지원하지 않는다. 두 span은 각각 1도 이하이고 supportedBounds
  안에 있어야 한다. 기하/크기/지원 영역 오류는 `RESTAURANT-011`이다.
- `placeType=restaurant|cafe|bar`; 전체는 생략한다. `all`, 빈 값, 대문자는 허용하지 않는다.
- `genre=sushi|noodle|rice-bowl|nabe|fried|grill|etc`; 전체는 생략한다.
  `foodCategory`·hashtag 검색을 추가하지 않는다.
- keyword는 생략 가능하다. 전달 시 앞뒤 공백 제거·연속 공백 하나로 정규화한 1~100자이며
  개행·제어문자를 거절한다. 식당명·메뉴명의 대소문자 구분 없는 부분 검색이다.
  `%`, `_`는 검색 와일드카드가 아닌 문자로 escape한다. 새 지도 API의 검증 규칙이다.
- `sort=recommend|rating|reviews`, 기본 recommend. rating은 평균 별점 내림차순,
  reviews는 공개 리뷰 수 내림차순이며 둘 다 동점은 해당 세션 추천 순서다.
- 지역은 양의 ID·현재 활성 상태를 검사한다. 잘못된 형식은 COMMON-400,
  없는/비활성 지역은 RESTAURANT-012. 유효한 조건의 0건 결과는 오류가 아니다.
- cursor는 최대 512자 불투명 토큰이다. version·session ID·sort·다음 후보 위치를 무결성 검증하며
  querySessionId는 추측하기 어려운 공개 세션 식별자다. URL·로그에 사용자 토큰을 넣지 않는다.

`data={content,nextCursor?,hasNext,querySessionId,expiresAt,rankingAsOf,query}`.
query에는 서버가 적용한 bounds·정규화 필터·sort를 반환한다. 시각은 UTC ISO-8601(`Z`)이다.
마지막 페이지·빈 결과는 `hasNext=false`이고 **nextCursor 필드 생략**, content는 배열이다.

content의 각 항목은 지도 전용 DTO이며 기존 RestaurantSummaryResponse의 필드·이미지 규칙을 유지하고
`placeType`, `reviewCount`, `priceRange:{currency,minPrice,maxPrice}`,
`location:{latitude,longitude,validUntil}`을 추가한다. 엔티티는 노출하지 않는다.
rating·reviewCount는 rankingAsOf 기준, 다른 표시 값과 좌표는 현재 유효한 값이다.
개인 isSaved·savedCount를 restaurant 응답에 합치지 않는다(§6).

빈 결과의 완전한 예시(좌표는 운영 설정을 뜻하지 않는 합성 데이터):

```json
{
  "success": true,
  "code": "COMMON-200",
  "message": "요청에 성공했습니다",
  "data": {
    "content": [],
    "hasNext": false,
    "querySessionId": "opaque-session-id",
    "expiresAt": "2026-09-26T01:15:00Z",
    "rankingAsOf": "2026-09-26T01:00:00Z",
    "query": {"south": 35.0, "north": 35.1, "west": 139.0, "east": 139.1, "sort": "recommend"}
  }
}
```

### 4.3 세션·경합·부분 실패

1. 새 조회에서 MySQL의 모든 해당 후보 ID·별점·리뷰 수를 일관된 읽기로 확보하고 추천 순열을 만든다.
   Redis에 조건·순열·순위 기준값·rankingAsOf·절대 expiresAt을 전부 저장한 뒤 첫 페이지를 반환한다.
   일부만 저장하거나 저장 실패를 성공으로 처리하지 않는다. 15분은 초기 설정값이며 용량 검증 결과가 아니다.
2. 다음 페이지/재정렬마다 현재 MySQL에서 공개 여부·좌표 수명·BBOX·지역·검색·분류를 bulk 재검사한다.
   탈락 후보는 건너뛰고 이후 후보로 10개를 채운다. cursor는 마지막 소비한 후보 위치를 가리킨다.
   다음 유효 후보의 존재를 확인해 hasNext를 정하되, 이후 삭제로 다음 페이지가 빈 결과가 될 수 있다.
3. 새 식당이나 이전에 탈락한 후보를 이전 cursor 앞에 끼워 넣지 않는다. 순위 수치는 같은 세션에서
   고정한다. 상세의 최신 별점·리뷰 수와 목록이 다를 수 있음을 rankingAsOf로 구분한다.
4. 같은 cursor 재요청으로 서버의 전역 포인터가 전진하지 않는다. FE는 동일 페이지 응답을 두 번
   append하지 않는다. DB 공개 상태가 변하면 같은 cursor의 표시 항목은 줄거나 바뀔 수 있다.
5. 410은 만료·eviction 등 세션 유실이다. 기존 목록·아직 유효한 핀을 유지하고 새로 조회를 제공한다.
   503은 저장소 장애/수용량 부족이다. 같은 요청 재시도를 허용하고, 재시도 때 410이면 새 조회로 전환한다.
   첫 페이지 생성 실패도 기존 결과를 교체하지 않는다. 서버가 임의로 새 세션을 이어 주지 않는다.
6. 세션별 후보 수·bytes·전체 동시 세션 admission 한도는 M4b에서 측정 후 명시한다.
   초과 시 RESTAURANT-016으로 전체 요청 실패; 성공처럼 결과를 잘라내지 않는다.

### 4.4 선택 식당

`GET /restaurants/{restaurantId}/map-location`을 추가해 현재 표시 가능한
`{restaurantId,location}`을 조회한다. 삭제·비노출은 기존 RESTAURANT-004,
좌표 미준비·만료는 RESTAURANT-018이다. 선택 시 이 검증과 기존
`/{restaurantId}/summary`, `/store-information`, 메뉴·사진·리뷰 API를 사용한다.
삭제·비노출이면 핀·지도 목록에서 제거한다. 위치만 무효이면 핀과 지도 선택을 해제하되
컬렉션 목록의 식당은 유지한다. 일반 통신 장애에는 바텀시트 오류·재시도를 표시한다.

## 5. 주소 저장과 좌표 처리

기존 `POST /admin/restaurants`(201 ADMIN-204), `PATCH /admin/restaurants/{id}`
(200 ADMIN-205)의 저장 성공 의미를 유지한다. 후속 구현에서 응답에 `locationStatus`,
`addressRevision`을 추가한다. “식당 정보 저장 완료 / 지도 위치 확인 중”을 분리해 표시한다.
기존 주소는 보존하며 Google 응답의 formatted address로 덮어쓰지 않는다.

| 내부/관리자 wire 상태 | 의미·지도 노출 |
|---|---|
| UNRESOLVED | 기존 데이터 등 아직 작업이 없음. 지도 제외 |
| PENDING | 주소와 durable 작업 저장 완료, 처리 대기/lease 수행 중. 제외 |
| READY | 유효 좌표 준비. §3 조건까지 만족하면 노출 |
| RETRY_WAIT | 일시 장애로 제한 재시도 대기. 제외 |
| REVIEW_REQUIRED | 결과 없음·모호함·허용 정확도 미달로 주소 확인 필요. 제외 |
| FAILED | 설정·권한 문제 또는 재시도 소진으로 자동 처리 중단. 제외 |

- 신규 저장/주소 변경은 `addressRevision` 증가, 이전 좌표 무효화·제거, PENDING 작업 생성까지
  한 transaction이다. 동일 주소의 다른 정보 수정은 revision·작업을 불필요하게 갱신하지 않는다.
- 작업에는 restaurant ID·주소 revision·job ID·lease token/만료·attempt·nextAttemptAt·안전한
  failureCode를 남긴다. 원본 주소는 해당 revision에 맞게 읽는다. 완료 시 모든 식별자와 lease가
  여전히 유효하고 식당이 삭제되지 않았을 때만 CAS로 저장한다. 같은 주소 재처리도 새 job/token으로
  이전 결과를 막는다. lease 만료 후 늦은 worker, 중복 완료는 no-op이다.
- worker가 죽으면 lease 만료 후 재claim한다. DB 완료 실패는 재처리 가능하며 외부 호출은
  정확히 한 번을 보장하지 않는다. 중복 과금 가능성을 quota·attempt 제한에 포함한다.
- Google timeout·일시 5xx·단기 quota 오류는 지수 backoff와 jitter로 제한 재시도한다.
  결제/권한/설정 오류는 FAILED로 중단하고, 애매한 여러 결과의 첫 항목을 임의 채택하지 않는다.
  timeout·lease 길이·최대 attempt·일일 예산 수치는 M3 설정/테스트에서 정하고 활성화 전에 검증한다.
- `GET /admin/restaurants/{id}/location`(ADMIN)은 상태·revision·validUntil·attempt·nextAttemptAt·
  failureCode·canRetry를 반환한다. Google 원문·키·요청 URL·lease token을 노출하지 않는다.
- `POST /admin/restaurants/{id}/location/retry`, body `{"expectedAddressRevision":3}`(ADMIN)는
  UNRESOLVED/REVIEW_REQUIRED/FAILED/RETRY_WAIT에서 현재 revision의 새 작업을 등록한다.
  동일 revision이 이미 PENDING이면 기존 작업을 반환한다. READY 또는 revision 불일치는 409
  RESTAURANT-019. 성공은 200 COMMON-200과 최신 상태 DTO다. quota gate를 우회하지 않는다.

## 6. 저장 요약과 선택 컬렉션 전체 핀

다음 경로는 #216에 제안하는 **M1 채택 경로**다. 현재 구현·담당자 합의 완료로 표시하지 않는다.
#216의 실제 경로/오류 번호가 먼저 확정되면 FE·서버 mock과 이 문서를 함께 갱신한다.
저장·이동·삭제 명령 API 자체는 #216 소유이며 여기서 두 번째 API를 만들지 않는다.

| API (담당 user) | 접근·응답 data |
|---|---|
| `GET /restaurants/save-counts?restaurantIds=1,2` | 공개. `content:[{restaurantId,savedCount}]` |
| `GET /users/me/restaurant-saves?restaurantIds=1,2` | USER만. `content:[{restaurantId,isSaved}]`; 현재 사용자는 인증 문맥으로 결정 |
| `GET /collections/{collectionId}/map-markers` | 공개 컬렉션은 비로그인 열람 가능, 비공개는 소유자만. 아래 전체 응답 |

bulk IDs는 1~100개의 서로 다른 양의 정수다. 빈 값·중복·형식 오류·초과는 COMMON-400.
user가 RestaurantPort로 현재 공개 식당만 검사해 요청 ID 순서로 반환하고, 없는/비공개 식당 ID는 생략한다.
빠진 항목이나 조회 실패를 savedCount=0/isSaved=false로 바꾸지 않는다.
공개 집계에 사용자 ID·비공개 컬렉션 이름을 포함하지 않는다. 개인 응답은 공유 세션에 저장하지 않는다.

savedCount는 해당 식당을 하나 이상 저장한 사용자 수다. 같은 사용자의 여러 컬렉션은 1명이다.
마지막 관계 제거 때만 1 감소하는 것은 최신 SAVED_COLLECTION의 확정 규칙이다.
isSaved는 현재 사용자의 소유 컬렉션에 하나 이상 저장됐는지다. 공개 컬렉션 열람만으로 true가 되지 않는다.
미래 공동 편집자의 저장 귀속은 이번 범위 밖이며 역할·집계 정책을 별도로 정한다.
저장 성공 시 #216 결과로 상태·집계를 갱신하고 실패 시 이전 값 유지, 처리 중 중복 클릭을 막는다.

### 전체 마커 응답과 실패

- `data={collectionId,collectionVersion,generatedAt,visibleRestaurantCount,locationUnavailableCount,content}`.
  content는 `{restaurantId,name,placeType,genre,location}`의 **모든 유효 핀**이다. ID 오름차순,
  cursor·size·BBOX·분류·검색 필터 없음. 목록의 10개 페이지/정렬과 연결하지 않는다.
- visibleRestaurantCount는 공개 가능한 소속 식당 수이고, locationUnavailableCount는 그중
  위치가 미준비·만료된 수다. `content.length + locationUnavailableCount = visibleRestaurantCount`.
  삭제·비노출 식당은 세 수와 목록에서 제외하고 식별 정보를 공개하지 않는다.
- user는 소속 ID·collectionVersion을 확보하고 RestaurantPort를 **내부 bulk 단위**로 호출한다.
  모든 결과를 모은 뒤 현재 열람 권한·collectionVersion을 다시 확인하고 하나의 응답만 보낸다.
  membership·공개 범위 변경/삭제 시 version을 갱신해야 한다. 범위 밖 모듈과 DB join은 하지 않는다.
- 중간 Port/DB 오류나 전송 실패는 전체 실패다. FE는 완전한 성공 응답을 받은 때만 핀 세트를
  원자적으로 교체한다. 목록 스크롤을 기다리거나 내부 batch를 성공 응답 여러 개로 노출하지 않는다.
- 최초 실패는 핀 오류 상태, 재시도 실패는 기존의 아직 유효한 핀과 오류 안내를 유지한다.
  권한 상실/삭제(404)는 기존 컬렉션 핀·상세를 즉시 비운다. 버전 변경(409)은 새 전체 조회를 제공하고
  이전·새 버전 핀을 섞지 않는다. 원래부터 핀 0개인 성공과 실패를 구분한다.
- 전부 반환할 자원이 없으면 USER-008로 전체 요청을 거절한다. 숨은 핀 개수 상한은 두지 않는다.
  실측 상한·응답 bytes·timeout이 전체 로드를 감당하지 못하면 출시 전에 계약을 개정해 자동 완주 전송을
  설계한다. 이 v1에는 외부 페이지 전송이 없다.
- 공개 열람과 편집 권한은 분리한다. 공개 GET만 permitAll로 추가하고 쓰기·내 저장 조회를 함께
  공개하지 않는다. ADMIN/ONBOARDING을 소유 USER로 오인하지 않는다. 권한은 매 요청 서버에서 검사한다.

## 7. 오류와 응답 수명

[ErrorResponse](../../src/main/java/org/sopt/hashi/shared/response/ErrorResponse.java)를 유지한다.
실패는 `success:false,code,message,data:null,timestamp,path`; 검증 오류에만 `errors` 배열을 넣는다.
timestamp는 기존 LocalDateTime 형식이며, 새 data의 UTC 시각과 혼동해 전역 직렬화를 변경하지 않는다.

| HTTP / code | enum 의미 (신규는 채택안) | FE 처리 |
|---|---|---|
| 400 COMMON-400 | 기존 INVALID_INPUT; 모드·필드·cursor 변조/형식·정렬 cursor 혼용 | 요청 수정, 기존 결과 유지 |
| 400 RESTAURANT-001 / -002 / -010 | 기존 UNSUPPORTED_GENRE / UNSUPPORTED_SORT / UNSUPPORTED_PLACE_TYPE | 허용 wire 값 사용 |
| 400 RESTAURANT-011 | MAP_BOUNDS_INVALID | 지원 범위/크기로 새 조회 |
| 400 RESTAURANT-012 | MAP_REGION_INVALID | 지역 해제 후 명시적 새 조회 |
| 410 RESTAURANT-013 | MAP_SESSION_EXPIRED; 만료·유실·읽을 수 없는 구버전 세션 | 기존 결과 유지, 새로 조회 |
| 503 RESTAURANT-014 | MAP_SESSION_UNAVAILABLE; Redis 연결/읽기/쓰기 장애 | 같은 요청 재시도 |
| 503 RESTAURANT-015 | MAP_QUERY_UNAVAILABLE; DB 조회 실패 | 같은 요청 재시도 |
| 503 RESTAURANT-016 | MAP_CAPACITY_EXCEEDED; 온전한 세션 수용 불가 | 범위 축소/나중에 재시도 |
| 503 RESTAURANT-017 | MAP_CONFIGURATION_UNAVAILABLE | 지역·초기 화면 설정 오류 안내 |
| 404 RESTAURANT-004 | 기존 NOT_FOUND; 삭제·비노출 식당 | 해당 핀·지도 목록 제거 |
| 409 RESTAURANT-018 | MAP_LOCATION_UNAVAILABLE | 좌표·선택 해제; 컬렉션 목록 유지 |
| 409 RESTAURANT-019 | LOCATION_RETRY_CONFLICT | 관리자 상태 재조회 |
| 404 USER-006 | COLLECTION_NOT_ACCESSIBLE; 없음·삭제·열람 불가 통합 | 컬렉션 표시 제거 |
| 409 USER-007 | COLLECTION_VERSION_CHANGED | 전체 핀 새 조회 |
| 503 USER-008 | COLLECTION_MAP_UNAVAILABLE; 전체 생성 실패/용량 초과 | 전체 재시도, 부분 성공 금지 |
| 503 USER-009 | SAVE_SUMMARY_UNAVAILABLE | 집계/개인 상태만 재시도; false/0 대입 금지 |
| 401 COMMON-401 / 403 COMMON-403 | 기존 인증 필요 / 역할 불가 | 로그인·권한 안내 |

user endpoint는 내부 RestaurantPort 통신/조회 실패를 해당 USER-008/009로 매핑하고 상세 원인은
안전한 서버 로그로 한정한다. 인증 필터의 잘못된/만료 토큰 오류는 기존 auth 계약을 유지한다.
예상하지 못한 서버 오류는 기존 COMMON-500이다. 신규 code는 각 소유 모듈 code 패키지에 둔다.

410 예시(문구는 해당 enum 등록 시 그대로 사용):

```json
{
  "success": false,
  "code": "RESTAURANT-013",
  "message": "지도 조회가 만료되었습니다. 새로 조회해주세요",
  "data": null,
  "timestamp": "2026-09-26T10:16:00",
  "path": "/api/v1/restaurants/map"
}
```

좌표를 반환하는 API와 개인/컬렉션 응답은 `Cache-Control: no-store`를 사용한다.
FE는 좌표를 영구 저장소에 남기지 않고 각 validUntil에 폐기한다. 세션 15분 TTL이 좌표의
보관 허가/수명을 연장하지 않는다. 정상/실패와 무관하게 로그에 좌표·검색어·Google 원문을 남기지 않는다.

## 8. Google 출처·보존과 운영 전환

완전한 주소의 비동기 변환은 Geocoding API를 사용한다.
[공식 권장 용도](https://developers.google.com/maps/documentation/geocoding/best-practices)를
확인했지만 실제 Google 프로젝트·계약·청구 지역·키·quota는 확인하지 않았다.

2026-09-26 조회한 [서비스 조항 §6.3](https://cloud.google.com/maps-platform/terms/maps-service-terms)은
위경도 임시 보관을 기본 최대 30일로 제한하고, 장기 보관 예외에는 특정 최종 사용자별 격리 조건을 둔다.
Hashi 공용 식당 DB가 그 예외를 충족한다고 가정하지 않는다. 실제 계약 확인 전에는 유료 호출·운영
backfill을 활성화하지 않고 fake adapter로 개발한다. MySQL에 넣어도 Google 결과의 보존 제한은 적용된다.

| 데이터 | 수명·정리 기준 |
|---|---|
| 운영자 입력 주소·식당 데이터 | Hashi 원본. Google 결과와 출처를 분리 |
| Google 위치 | source·obtainedAt·validUntil·addressRevision 기록. 계약상 허용 기간 이하로 만료, 원본 응답 저장 금지 |
| 만료 위치 | 조회에서 즉시 제외하고 보존 기한 전에 DB·캐시·클라이언트에서 제거. 갱신 실패로 옛 validUntil 연장 금지 |
| 변환 작업 | 완료 후 원본 응답/주소 복제 없이 ID·revision·안전한 결과 코드·attempt 등 최소 이력만 유지 |
| Redis 조회 세션 | 최초 생성 후 15분, 접근 시 연장 없음. Google 좌표/원문·개인 상태 저장 없음 |
| 로그·백업·복구본 | Google 결과가 남는 모든 경로를 보존 정책에 포함. 만료 데이터 복원 후 공개 금지; 제거·백업 분리/보존 증거가 없으면 운영 gate 미충족 |

만료 전에 갱신할 작업을 예약한다. 갱신을 시작하면 PENDING으로 전환해 기존 좌표를 비노출·제거하며,
실패 중에도 식당 목록의 원본 데이터를 삭제하지 않는다. 만료 스케줄러가 늦어도 조회의 시간 검사로
노출은 막지만 그것만으로 저장 데이터 제거를 완료했다고 보지 않는다.

[Geocoding 정책](https://developers.google.com/maps/documentation/geocoding/policies)에 따라
지도 표시·귀속 표기·공개 이용약관/개인정보처리방침을 통합 QA한다. Place ID의 별도 보관 허용을
좌표 무기한 저장 허용으로 해석하지 않는다. 서버 키·브라우저 키·Map ID와 운영 값을 문서/로그에 적지 않는다.

실제 연동 전에는 대표 위치·지역 매핑, Google 계약/청구/허용 보존 기간, 키 제한, timeout·quota·
재시도 한도, Redis 인증 데이터와의 용량 경쟁을 확인한다. backfill은 대상 수·주소 품질·예상 호출량을
**조회 전용 dry-run**으로 확인한 뒤 checkpoint·중단·복구 기준과 별도 운영 승인을 갖춘다.
이 PR은 Java·dependency·migration·유료 호출·운영 데이터 변경·배포를 포함하지 않는다.
