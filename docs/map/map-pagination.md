# 지도 목록과 조회 세션 (#227)

기준 계약은 #219 / PR #221의 Map Contract v1이다. #220 위치 모델과 #223 조회를 사용하며,
일반 `/restaurants`의 cursor·정렬과 컬렉션 전체 핀 계약은 바꾸지 않는다.

## 요청과 응답

`GET /api/v1/restaurants/map`은 익명 공개이며 다음 세 모드만 허용한다.

- 새 조회: 필수 south/north/west/east, 선택 mapRegionId/keyword/genre/placeType/sort.
- 같은 조회의 정렬 변경: querySessionId와 sort만.
- 다음 페이지: cursor만.

size, 중복 파라미터, 모드 혼합, 알 수 없는 파라미터는 COMMON-400이다. 지도 정렬은
recommend/rating/reviews이며 기본은 recommend다. BBOX·분류·지역·검색어는 #223의
MapQueryBounds/MapSearchCriteria 검증과 SQL을 공유한다.

공개 BBOX의 각 숫자는 문자열 128자, precision 128자리, 절대 scale 128까지 허용한다.
큰 지수 때문에 BigDecimal 산술이 자원을 과도하게 사용하지 않도록 산술 전에 거절하며 오류는
RESTAURANT-011이다. 이 범위 안의 SDK 소수는 그대로 비교하고 반올림/절삭하지 않는다.

content는 최대 10개다. 마지막/빈 페이지의 hasNext는 false이고 nextCursor는 생략한다.
query에는 적용한 BBOX·정규화 필터·정렬이 들어간다. rankingAsOf/expiresAt/location.validUntil은 UTC Z다.
기존 카드의 이미지와 오늘 영업시간 규칙을 공유하며 placeType/reviewCount/priceRange/location을 추가한다.
금액은 기존 store-information과 같이 Restaurant의 통화·최소·최대 금액을 정수 Long으로 반환한다.
rating/reviewCount만 rankingAsOf 기준이고 나머지 카드는 현재 DB 값이다. 개인 저장 정보는 포함하지 않는다.

## DB와 페이지 위치

RestaurantMapPageService는 NEVER로 상위 transaction이 열린 호출을 Redis 접근 전에 거절한다. 후보 확보는
RestaurantMapService의 짧은 읽기 transaction, 세션 저장은 Redis, 페이지 재검사/카드는
RestaurantMapPageReader의 REPEATABLE_READ transaction으로 나뉜다. transaction을 일시 중단해도 상위 호출의
DB 연결은 남을 수 있으므로 이 경계를 명시한다.

기본 OSIV도 요청의 EntityManager와 물리 연결을 transaction 종료 뒤까지 보유할 수 있다.
RestaurantMapWebConfig가 표준 OSIV interceptor를 등록하면서 새 `/api/v1/restaurants/map` 경로만 제외한다.
다른 경로의 기존 OSIV는 유지하며 명시적인 `spring.jpa.open-in-view=false` 설정도 존중한다.
따라서 지도 목록의 각 DB transaction이 자체 EntityManager를 닫고 Redis 접근 전에 연결을 반환한다.

최초 후보 ID·평점·리뷰 수를 한 SQL에서 읽고 추천 순열을 한 번 만든다. Redis 저장 성공 후에만
첫 페이지를 구성한다. 별점/리뷰 정렬은 최초 값의 안정 정렬이므로 동점은 처음 추천 순서다.
매 페이지에서 현재 공개·위치 수명·BBOX·지역·검색·분류를 다시 조회하고 탈락 후보를 건너뛴다.
한 페이지의 조건/좌표/카드에는 같은 DB snapshot을 사용한다. 반환 후 발생한 변경까지 보장하지 않는다.

cursor 위치는 10번째 표시 후보 바로 다음이다. hasNext 확인용 11번째 후보는 소비하지 않는다.
동일 cursor는 전역 상태를 바꾸지 않으며 복구/신규 후보가 이미 지나간 위치 앞에 삽입되지 않는다.
DB 공개 조건이 바뀌면 같은 cursor의 표시 항목이 달라질 수 있다.

## 저장소와 한도

schemaVersion=1 구체 record를 JSON으로 저장한다. Java default typing과 Lua cjson 변환은 쓰지 않는다.
세션 ID(UUID), 정규화 조건, 최초 추천 순 후보·순위 값, rankingAsOf, 절대 expiresAt만 들어간다.
식당 좌표·이미지·Google 원문·개인 상태·인증 토큰은 저장하지 않는다.

| 한도 | 값 |
| --- | --- |
| 후보 | 500개; DB는 501개까지 읽어 초과 전체 실패 |
| 직렬화 UTF-8 값 | 65,536 bytes |
| 동시에 저장 가능한 세션 | 128개 |
| 세션 수명 | 생성 후 15분; 읽기/정렬 비연장 |

키는 `hashi:restaurant:map:{sessions-v1}:slot:0`부터 `:127`까지 고정이다. 슬롯 값 자체가
수용 상태여서 별도 카운터/index 관리 키가 없다. 모든 키를 Lua KEYS에 명시적으로 전달하고 같은
Redis Cluster hash tag를 쓴다. 최대 128개의 EXISTS 검사 후 단 한 번의 SET NX PXAT으로
전체 값을 저장한다. 실패 전 쓰기를 rollback한다고 가정하지 않는다. payload를 Lua에서 파싱하지
않으므로 2^53보다 큰 Long 식당 ID도 그대로 보존된다.

한 슬롯 유실은 그 세션 유실과 동일하며 정확히 한 자리를 회수한다. 모든 슬롯 유실은 모든 세션을
410으로 만들고 용량은 비워진다. 슬롯이 재사용돼도 UUID가 다르면 기존 요청은 410이다.
쓰기 응답을 잃었을 때 남는 완전한 세션은 TTL까지 한 자리를 차지할 수 있다. 임의 복구/새 세션
연결이나 부분 성공은 하지 않는다. namespace는 공유 Redis의 인증 메모리를 물리적으로 격리하지 않는다.

Redis 공식 문서의 [SET NX/PXAT](https://redis.io/docs/latest/commands/set/)와
[Lua 키 전달/실행 제약](https://redis.io/docs/latest/develop/programmability/eval-intro/)을 따른다.
PXAT은 Redis 6.2 이상을 요구한다. 운영 버전·ACL·eviction·메모리 경쟁은 활성화 전 확인해야 한다.
서버 TIME보다 미래 15분을 5초 넘겨 벗어나거나 이미 지난 deadline은 저장하지 않으므로 서버 시계도
동기화해야 한다. 이 5초는 시계 차이 허용 범위이며 payload의 expiresAt이나 PXAT을 늘리지는 않는다.

2026-09-27 KST 임시 Redis 7.4.11, 공식 digest
`sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499`에서 합성 값을 측정했다.
최대 Long ID/리뷰 수, 별점 5.0, 500개 후보, 100개 보조 평면 Unicode 검색어, 긴 소수 BBOX 및
지역/분류 필터, 각 128자 BBOX의 최종 serializer 값은 실행별 43,226~43,232 bytes,
MEMORY USAGE는 49,240 bytes였다. Instant의 소수 초 문자열 길이에 따라 payload 길이가 달라진다.
이는 현재 값 형식의 합성 최대 조건 측정이며 더 큰 내부 입력도 별도 bytes 한도가 거절한다.
실제 동시 160회 생성에서는 128회만 성공했다. 128개 전부가 64KiB라면
payload 상한은 8MiB이고 Redis allocator/key overhead는 별도다. 운영 QPS/p95 보장은 아니다.

## 설정·오류와 운영 경계

`hashi.restaurant.map.session.signing-key`에 모든 서버가 공유하는 Base64 형식 32~64 byte 비밀값을
설정한다. 환경 변수 이름은 `HASHI_RESTAURANT_MAP_SESSION_SIGNINGKEY`다. 코드 기본값·랜덤 생성·로그
출력이 없고 요청 시 검증하므로 누락/형식 오류는 지도 세션만 503이며 부팅·관광 안내·기존 API는 유지된다.
키 교체 때 기존 cursor는 검증 실패한다. 회전 기간 복수 키 지원은 이 변경에 포함하지 않는다.

커서는 최대 512자 계약 안에서 74자 Base64url 토큰이며 version/slot/UUID/sort/후보 위치에 HMAC-SHA256을
검증한다. 전체 cursor·검색어·서명키를 오류나 로그에 넣지 않는다. Redis serializer/parser 예외의
payload 포함 가능성 때문에 cause도 외부 로그에 전달하지 않는다.

- RESTAURANT-013 / 410: 만료·유실·UUID 불일치·구버전/손상 세션.
- RESTAURANT-014 / 503: 서명 설정 누락/오류·Redis 읽기/쓰기/연결 장애.
- RESTAURANT-015 / 503: DB 조회/transaction 장애.
- RESTAURANT-016 / 503: 후보·bytes·슬롯 수용 한도 초과.
- 기존 011/012/017/018은 #223 의미를 유지한다.

기존 RedisTemplate·인증 키·CacheManager·연결 설정은 변경하지 않았다. 기존 명령 timeout 3초와
연결 timeout 2초를 사용하며, 임시 Redis 일시중지 반례로 명령 실패가 10초 미만에 끝남을 검사한다.
운영 Redis·Google·운영 DB에는 연결하지 않았다. 배포/운영 활성화/최종 GO 판정은 이 구현의 범위 밖이다.

## 검증 구성

HTTP 통합 테스트는 restaurant 모듈에 실제 공통 응답/로그와 SecurityFilterChain을 포함한다.
MySQL 8.4에서 전체 Flyway 적용 후 validate, JVM UTC/JDBC Asia-Seoul, 실제 Redis 7.4.11을 사용한다.
OSIV를 테스트에서 끄지 않으며, 지도 경로의 request-bound EntityManager 부재와 Redis 저장 시
실제 Hikari active connection 0개를 관측한다. 일반 목록에서는 기존 OSIV가 활성인 것도 확인한다.
모듈 테스트에서 제외되는 root Redis 설정은 같은 production factory 메서드로 연결한다.
미사용 이미지 backfill attachment 빈 하나만 제외하고 Restaurant Repository/Service/Port/세션 adapter는 실제다.
다른 모듈의 MediaPort, 외부 FileStorage, 이 공개 조회에서 사용하지 않는 OnboardingTokenStore는
모듈 격리를 위해 대체한다. 지도 Repository/Service/Port/Redis 저장소를 mock으로 바꾸지는 않는다.
지역 필터 없는 BBOX 조회에서 실제 카드 SQL은 식당 1곳과 10곳 모두 7회, MediaPort bulk 호출 1회였다.
메뉴를 식당별로 조회하지 않는다.

최초 후보 검증 명령은 JDK 21, JVM UTC에서 다음과 같다.

```text
./gradlew.bat build test --tests org.sopt.hashi.restaurant.* --tests *AdminRestaurant* --tests *ModularityTests --no-daemon --max-workers=2
```

44개 suite, 439개 test가 실행됐고 실패/오류/건너뜀은 모두 0이었다. 이 결과에는 새 HTTP 통합 16개,
실제 Redis 저장소 통합 7개와 Modulith 검증이 포함된다.

이후 기본 OSIV에서 Redis 저장 시 물리 DB 연결 1개가 남는 반례를 재현하고 지도 목록 경로를 제외했다.
수정 후 다음 관련 build는 5개 suite / 37개 test / 실패·오류·건너뜀 0으로 통과했다. 이 실행에는
DB 연결 반환과 기존 OSIV 유지 검증을 포함한 HTTP 17개, 기존 식당/지도/관리자 Controller 및 Modulith가 포함된다.

```text
./gradlew.bat build test --tests *RestaurantMapPageIntegrationTest --tests *RestaurantMapControllerTest --tests *RestaurantControllerTest --tests *AdminRestaurantControllerTest --tests *ModularityTests --no-daemon --max-workers=2
```

전체 프로젝트 빌드와 CI 결과는 PR에 따로 기록한다.
