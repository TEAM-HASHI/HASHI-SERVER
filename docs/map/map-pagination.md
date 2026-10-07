# 지도 목록과 조회 세션 (#227)

기준 계약은 #219 / PR #221의 Map Contract v1이다. #220 위치 모델과 #223 조회를 사용하며,
일반 `/restaurants`의 cursor·정렬과 컬렉션 전체 핀 계약은 바꾸지 않는다.

## 요청과 응답

`GET /api/v1/restaurants/map`은 활성화 시 익명 공개이며 기본 비활성화 상태다. 다음 세 모드만 허용한다.

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

cursor 위치는 실제 검사한 후보 위치다. 후보를 32개씩 재검사하고 유효한 10개와
hasNext 확인용 1개가 확보되면 멈춘다. 11번째 후보는 소비하지 않아 다음 페이지에 포함된다.
동일 cursor는 전역 위치를 바꾸지 않으며 복구/신규 후보가 지나간 위치 앞에 삽입되지 않는다.
DB 공개 조건이 바뀌면 같은 cursor의 표시 항목은 달라질 수 있다.

## 저장소와 한도

조회 UUID별 `hashi:restaurant:map:{sessions-v2}:query:<UUID>` 키에 JSON 하나를 저장한다.
최초 추천 순서와 별점·리뷰 수만 보관한다. 별도 정렬 자료구조나 payload 분할은 사용하지 않는다.
검색 조건, `rankingAsOf`, 최대 보관 시각도 포함하며 좌표·이미지·Google 원문·개인 상태는 없다.
JSON을 Lua에서 변환하지 않으므로 큰 Long ID도 그대로 보존된다.

세션은 **마지막 정상 조회부터 5분**, **첫 조회 admission부터 최대 30분** 유지한다.
생성 시각과 TTL 판단은 Redis `TIME`으로 통일한다. 기존 admission 호출에서 받은 시각을 재사용하므로 추가 왕복은 없다.
다음 페이지와 정렬 변경이 성공하면 그 시점부터 5분으로 TTL을 갱신한다. 기존 TTL에 5분을 더하지 않는다.
조회만 시작했거나 잘못된 cursor, DB 실패인 경우 연장하지 않는다. Redis Lua에서 현재 TTL을 확인해
만료한 키를 다시 만들지 않으며 동시 요청도 이미 연장한 시각을 앞당기거나 최대 수명을 넘지 않는다.
앱 시각과 Redis 시각을 비교해 세션을 만료시키지 않는다. `rankingAsOf`는 DB 조회 조건에 사용한 앱 시각 메타데이터이며
세션 수명 계산에 사용하지 않는다. DB 조회·좌표 유효성에는 앱 시계를 사용하므로 서버 시각 동기화는 계속 필요하다.
응답 `expiresAt`은 해당 성공 요청에서 확정한 실제 만료 시각이다. `rankingAsOf`는 변하지 않는다.

설정 접두사는 `hashi.restaurant.map.session.limits`다. 다음은 측정 후 조정할 초기값이며 운영 최적값이 아니다.

| 설정 | 초기값 | 목적 |
| --- | --- | --- |
| `concurrent-requests` | 인스턴스별 4 | DB·역직렬화·정렬의 동시 작업 수 |
| `idle-timeout` / `max-lifetime` | 5m / 30m | 유휴 만료와 최대 보관 시간 |
| `snapshot-bytes` | 1MiB | 조회 하나의 JSON 크기 |
| `total-bytes` | 16MiB | 지도 세션 예약 예산 |
| `sessions` | 1,024 | 작은 세션과 ledger 처리량 제한 |
| `new-queries-per-caller` | 분당 12 | 신규 DB 조회 남용 제한 |
| `requests-per-caller` / `requests-per-minute` | 분당 120 / 600 | 페이지·정렬 포함 호출 제한 |
| `callers-per-minute` | 2,048 | 익명 호출자 관리 정보 크기 제한 |
| `redis-memory-ceiling` / `redis-headroom` | 128MiB / 32MiB | 공유 Redis 사용량 상한과 여유분 |

500개 고정 제한은 제거했다. 500·501·620개도 마지막 페이지까지 조회한다.
무한 목록을 메모리에 올리지는 않는다. JSON 후보 하나에 반드시 필요한 최소 바이트보다 작은 32로
`max snapshot bytes / 32`를 계산해 DB 조회 안전 상한을 정하고, 실제 직렬화 크기도 검사한다.
이 안전 상한을 넘는 후보는 어차피 JSON 예산에 들어갈 수 없다. 결과를 잘라서 성공으로 반환하지 않는다.
운영 데이터가 byte 예산을 초과하면 503으로 끝나므로 실제 후보 규모 측정과 예산 조정은 공개 전 필요하다.

`:admission` hash는 세션별 예약 바이트와 만료 시각만 보관한다. 생성 시 만료한 예약을 회수하고,
기존 예약 합계와 세션 수를 확인한다. 예약은 `JSON bytes × 2 + 1,024`로 잡아 key/allocator/ledger 여유를 둔다.
세션 생성·TTL 갱신·예약 변경은 같은 hash slot의 Lua로 처리한다. 예약 후 payload를 저장하므로 쓰기가
실패해도 예산에 잡히지 않은 payload를 만들지 않는다. 예약이 남으면 유휴 기한 뒤 회수한다.
전체 hash 스캔은 설정 최대 4,096개로 제한한다. 이 hash는 용량 관리용이며 정렬 데이터 구조가 아니다.

`:requests` hash 하나에 현재 분의 호출 수를 보관한다. 다음 분 첫 요청에서 초기화하고 120초 TTL을 둔다.
원문 IP는 저장하지 않고 서명키로 HMAC한 호출자 식별자를 사용한다. 임의의 caller마다 Redis 키를 늘리지 않는다.
카운터 검사는 DB 후보 조회와 JSON 생성보다 먼저 실행한다.
인스턴스별 Semaphore는 지도 작업을 기본 4개로 제한하고 대기열 없이 초과 요청을 503으로 돌려준다.
Redis뿐 아니라 Java heap과 DB 연결도 보호하며, 실패해도 finally에서 실행 자리를 반환한다.
`concurrent-requests`는 기동 때 적용되며 변경하려면 인스턴스를 다시 시작한다. 분 경계에서는 두 분의 한도가 연속 사용될 수 있다.

Redis `INFO memory`로 실제 메모리와 `noeviction`을 확인한다. 설정 상한과 Redis maxmemory 중 작은 값에서
headroom을 뺀 공간이 부족하거나 정책이 다르면 지도 요청만 503으로 거절한다. 서버의 Redis 설정은 수정하지 않는다. `INFO memory`와 Lua 관련 명령의 ACL도 공개 전에 확인한다.
키 접두사나 Redis DB 번호는 인증 데이터와의 메모리 격리가 아니다. 운영자에 의한 ledger 단독 삭제도 지원하지 않는다.
수동 정리는 지도 namespace 전체를 함께 비우고 사용 중 세션을 만료 처리해야 한다.

공개 전에는 실제 후보 수·초당 신규 조회·p95·TTL 회수·인증 키 보존을 격리된 k6 환경에서 검증한다.
`:admission`의 `HLEN`, `MEMORY USAGE`, 전체 Redis used_memory를 함께 측정한다. local 합성 테스트가
운영 트래픽을 보장하지 않는다. 운영 QPS/메모리 예산과 ingress 제한은 별도 확인한다.

## 설정·오류와 운영 경계

`hashi.restaurant.map.session.enabled`는 기본 `false`이며 환경 변수 이름은
`HASHI_RESTAURANT_MAP_SESSION_ENABLED`다. 키 준비와 공개 활성화는 별개다.
비활성화 상태에서는 유효한 키가 있어도 신규 조회·정렬 변경·다음 페이지 모두 DB/Redis 접근 전에
RESTAURANT-014 / 503을 반환한다. 기존 입력 검증은 유지한다.

`hashi.restaurant.map.session.signing-key`에 모든 서버가 공유하는 Base64 형식 32~64 byte 비밀값을
설정한다. 환경 변수 이름은 `HASHI_RESTAURANT_MAP_SESSION_SIGNINGKEY`다. 키 기본값·랜덤 생성·로그
출력은 없다. 활성화 상태에서 키 누락/형식 오류는 지도 세션만 503이며 부팅·관광 안내·기존 API는 유지된다.
이 경우에만 시작 시 고정 WARN을 한 번 남기고, 키 값·예외 원문·Throwable/cause는 기록하지 않는다.
비활성화 상태의 키 누락/오류와 활성화 상태의 유효한 키에는 WARN을 남기지 않는다.

dev/prod compose의 기존 `env_file`은 이미 해당 환경변수를 전달할 수 있다. 명시적인 `environment`
매핑은 enabled 기본 `false`와 빈 키 기본값을 보여준다. 기존 배포처럼 `--env-file`로 동일 런타임
파일을 Compose 변수 치환에도 사용해야 하며, [EC2 런타임 설정](../infra/dev-deploy.md#5-ec2-런타임-환경변수)을 따른다.
유효한 키와 `enabled=true`만으로 ingress·용량 검증이 완료되는 것은 아니다.
호출자 식별은 Servlet `remoteAddr`를 사용하며 원문 X-Forwarded-For를 직접 읽지 않는다.
현재 `forward-headers-strategy=native`이므로 ingress가 외부 Forwarded/XFF를 제거·재설정하고
Tomcat이 신뢰하는 프록시 범위를 확인해야 한다. 이 경계가 없으면 IP 제한을 우회할 수 있다.
같은 NAT 사용자는 IP 한도를 공유하므로 실제 트래픽을 보고 조정한다. 애플리케이션 검사만으로 DDoS를 막지는 못한다.
키 교체 때 기존 cursor는 검증 실패한다. 회전 기간 복수 키 지원은 이 변경에 포함하지 않는다.

커서는 최대 512자 계약 안에서 72자 Base64url 토큰이며 version/UUID/sort/후보 위치에 HMAC-SHA256을
검증한다. 전체 cursor·검색어·서명키를 오류나 로그에 넣지 않는다. Redis serializer/parser 예외의
payload 포함 가능성 때문에 cause도 외부 로그에 전달하지 않는다.

- RESTAURANT-013 / 410: 만료·유실·UUID 불일치·구버전/손상 세션.
- RESTAURANT-014 / 503: 지도 세션 비활성화·서명 설정 누락/오류·Redis 읽기/쓰기/연결 장애·메모리 보호 조건 미충족.
- RESTAURANT-015 / 503: DB 조회/transaction 장애.
- RESTAURANT-016 / 503: snapshot bytes·전체 예약·세션 수·전역 호출 한도 초과.
- RESTAURANT-022 / 429: 호출자별 신규/전체 요청 한도 초과. 분 단위로 회복하므로 최대 60초 기다린 뒤 재시도한다.
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

현재 검증 명령:

```text
./gradlew.bat test --tests '*Map*' --tests '*ModularityTests' bootJar --no-daemon --max-workers=2
```

고정 슬롯 제거, 500개 초과 전량 페이지 조회, lookahead, 같은 cursor 재시도, 실패 시 미연장,
절대 수명, 동시 TTL 갱신, 호출자/전체 용량 제한, 인증 키 보존을 검증한다.
실제 Redis의 후보 500개 합성 snapshot은 UTF-8 43,232 bytes, MEMORY USAGE 49,288 bytes였다.
이는 한 payload 측정이며 전체 운영 용량을 보장하지 않는다.
부하·지속 부하·회복은 #236의 격리된 HTTP/MySQL/Redis+k6 시나리오에서 별도로 확인한다.
