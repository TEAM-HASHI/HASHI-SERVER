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

조회 UUID별 String 키에 JSON 하나를 저장한다. dedicated 모드는 `hashi:restaurant:map:{sessions-v2}:query:<UUID>`, shared 모드는 `hashi:restaurant:map:{sessions-shared-v3}:query:<UUID>`를 사용한다. 모드 전환 시 이전 조회 세션은 410으로 끝나므로 새 조회를 시작한다.
최초 추천 순서와 별점·리뷰 수를 보관한다. 후보는 `[restaurantId, rating, reviewCount]` tuple 배열로
직렬화해 세 값의 관계와 추천 순서를 함께 유지하면서 후보마다 반복되던 필드명을 제거한다.
별도 정렬 자료구조나 payload 분할은 사용하지 않는다.
검색 조건, `rankingAsOf`, 최대 보관 시각도 포함한다. keyword 조회는 전체 일치 건수와 모든 결과를 포함하는
`searchResult.bounds`, 그 좌표 중 가장 이른 `validUntil`만 집계해 보관한다. 개별 식당 좌표·이미지·Google 원문·개인 상태는 없다.
JSON을 Lua에서 변환하지 않으므로 큰 Long ID도 그대로 보존된다.

저장 형식은 domain의 `schemaVersion`과 별도로 `formatVersion`을 검사한다. 이번 compact tuple 형식은
`formatVersion=2`, `schemaVersion=2`이며 누락·불일치·tuple 길이·검색 결과 집계 오류는 부분 복원하지 않고
RESTAURANT-013 / 410으로 끝난다. 이전 v1과 long-form JSON도 배포 뒤에는 410이므로 기존 cursor가 만료된다.
공개 기본 비활성 상태에서 전환하며,
향후 활성화 뒤 형식을 바꿀 때는 rolling 배포의 양방향 읽기 호환을 별도로 설계해야 한다.

세션은 **마지막 정상 조회부터 5분**, **첫 조회 admission부터 최대 30분** 유지한다.
keyword 조회는 집계 경계에 포함된 좌표 중 가장 이른 `validUntil`을 절대 만료 상한으로 추가 적용한다.
검색 결과가 0건이면 좌표 만료 상한은 없다.
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
| `redis-memory-ceiling` / `redis-headroom` | 128MiB / 32MiB | 지도 Redis 사용량 상한과 여유분 |

500개 고정 제한은 제거했다. 500·501·620개도 마지막 페이지까지 조회한다.
무한 목록을 메모리에 올리지는 않는다. 후보당 32 byte를 보수적 계획 단위로 사용해
`max snapshot bytes / 32`로 DB 조회 안전 상한을 정하고, compact tuple의 실제 직렬화 크기도 다시 검사한다.
32 byte는 저장 형식의 최소 크기 주장이 아니라 DB 결과를 무제한 적재하지 않기 위한 메모리 경계다.
결과를 잘라서 성공으로 반환하지 않고 계획 상한이나 실제 byte 예산을 넘으면 전체 요청을 503으로 끝낸다.
운영 데이터가 byte 예산을 초과하면 503으로 끝나므로 실제 후보 규모 측정과 예산 조정은 공개 전 필요하다.

`:admission` hash는 세션별 예약 바이트와 만료 시각을 보관한다. shared에는 복구 준비 시각도 포함한다. 생성 시 만료한 예약을 회수하고,
기존 예약 합계와 세션 수를 확인한다. 예약은 `JSON bytes × 2 + 1,024`로 잡아 key/allocator/ledger 여유를 둔다.
세션 생성·TTL 갱신·예약 변경은 같은 hash slot의 Lua로 처리한다. 예약 후 payload를 저장하므로 쓰기가
실패해도 예산에 잡히지 않은 payload를 만들지 않는다. 예약이 남으면 유휴 기한 뒤 회수한다.
전체 hash 스캔은 설정 최대 4,096개로 제한한다. 이 hash는 용량 관리용이며 정렬 데이터 구조가 아니다.

`:requests` hash 하나에 최근 분의 호출 수를 보관한다. 다음 분 첫 요청에서 초기화한다.
dedicated에는 120초 TTL을 두고, shared에서는 TTL 없이 bounded hash를 유지한다.
원문 IP는 저장하지 않고 서명키로 HMAC한 호출자 식별자를 사용한다. 임의의 caller마다 Redis 키를 늘리지 않는다.
카운터 검사는 DB 후보 조회와 JSON 생성보다 먼저 실행한다.
인스턴스별 Semaphore는 지도 작업을 기본 4개로 제한하고 대기열 없이 초과 요청을 503으로 돌려준다.
Redis뿐 아니라 Java heap과 DB 연결도 보호하며, 실패해도 finally에서 실행 자리를 반환한다.
`concurrent-requests`는 기동 때 적용되며 변경하려면 인스턴스를 다시 시작한다. 분 경계에서는 두 분의 한도가 연속 사용될 수 있다.

내부 counter `hashi.restaurant.map.capacity.rejected`는 사용자 입력 없이 고정된 `reason`만 기록한다.
`concurrent_requests`, `candidate_count`, `snapshot_bytes`, `total_bytes`, `session_count`,
`caller_rate`, `caller_cardinality`, `global_requests`, `redis_memory_guard`, `redis_recovery`,
`redis_accounting`을 구분하며 공개 상태·오류 코드는 바꾸지 않는다.
Prometheus에서는 `hashi_restaurant_map_capacity_rejected_total`로 조회한다.

Redis `INFO memory`로 실제 메모리와 eviction 정책을 확인한다. dedicated 모드는 `noeviction`,
shared 모드는 유한한 `maxmemory`가 있는 `volatile-lru`만 허용한다. 서버의 Redis 설정은 변경하지 않는다.
설정 ceiling과 Redis maxmemory 중 작은 값에서 headroom을 뺀 값이 입장 차단 기준이다.
세션 생성은 앞으로 저장할 예약 크기까지 더해서 검사한다. 기본 설정은 ceiling 128MiB, headroom 32MiB이므로
전체 Redis 사용량 96MiB 전에 지도 요청을 제한한다. 인증용 데이터가 증가해도 같은 기준을 적용한다.
이 수치는 초기 보호 설정이며 Redis 전체 용량이나 정상 사용자 수를 보장하지 않는다.

shared에서 세션 String은 TTL로 만료되거나 Redis에 의해 삭제될 수 있다. 조회가 사라지면 410으로
새 조회를 안내한다. 기존 `requests`, `admission` Hash 두 개는 TTL 없이 유지하여 `volatile-lru`로
요청 제한·용량 예약이 먼저 사라지지 않게 한다. 호출자 Hash는 현재 1분 창만 보관하고 최대 4096명,
예약 Hash는 최대 4096개이며 생성 시 만료 예약을 정리한다. 사라진 세션의 예약은 원래 만료까지
보수적으로 남긴다. 필드마다 별도 키를 만들거나 인증 키를 SCAN하지 않는다.

shared의 두 회계 키 중 하나가 없으면 두 키를 복구하고 **30분간 지도 입장·생성·연장을 차단**한다.
사라진 회계에 연결된 세션의 최장 수명 30분이 지나기 전에 예산을 0으로 오인하지 않기 위한 절차다.
최초 shared 활성화도 동일하게 30분 준비 시간이 필요하다. 아래 운영자 준비 명령으로 공개 기능을
꺼 둔 상태에서 대기를 시작한다. 재시작해도 회계 키가 보존됐다면 이 대기는 생기지 않는다.
잘못된 TTL·값·키 타입은 덮어써서 복구하지 않고 503으로 막는다. 운영자가 원인을 확인한 다음 지도
기능을 비활성화하고 정확한 지도 키만 정리해야 한다. 인증 키·서버 정책을 바꾸거나 전체 FLUSH를 하지 않는다.
영구 회계 키는 기능을 폐기할 때 별도로 정리해야 하며, 이를 지우면 복구 대기가 다시 시작된다.

### 공유 Redis를 공개 전에 준비하기

`HASHI_RESTAURANT_MAP_SESSION_ENABLED=false`를 유지하고 저장소 루트에서 다음을 실행한다.
명령은 Redis에 접근 가능한 관리 환경의 Bash/redis-cli 예시다. 접속값은 비밀 환경 설정에서 주입한다.
비밀번호가 필요하면 `REDISCLI_AUTH` 환경변수를 사용하고 명령 인수나 터미널 기록에 직접 적지 않는다.
운영처럼 TLS가 필수인 환경은 `--tls`와 SNI를 유지한다. 로컬 비 TLS 검증에서만 두 옵션을 생략한다.

```bash
redis-cli --tls --sni "$HASHI_RESTAURANT_MAP_REDIS_HOST" \
  -h "$HASHI_RESTAURANT_MAP_REDIS_HOST" -p "$HASHI_RESTAURANT_MAP_REDIS_PORT" \
  EVAL "$(cat src/main/resources/map/shared.lua) $(cat src/main/resources/map/prepare-shared.lua)" 2 \
  'hashi:restaurant:map:{sessions-shared-v3}:requests' \
  'hashi:restaurant:map:{sessions-shared-v3}:admission' 134217728 33554432
```

ACL 사용자가 있으면 외부 설정의 사용자명으로 `--user`도 지정한다. 마지막 두 숫자는 앱과 동일한
ceiling/headroom 초기값이므로 설정을 조정했다면 함께 맞춘다. 결과는 `[상태, 남은 대기 밀리초]`다.
`-6`은 준비 중, `0`은 준비 완료, `-5`는 정책/메모리 확인 필요, `-7`은 회계 손상/잘못된 키다.
30분 뒤 같은 명령으로 `0`을 확인한 후 mode=shared·연결·알림 설정을 확인하고 공개를 켠다.
이 명령은 대기 시각을 앞당기지 않으며 반복 실행해도 정상 회계나 기존 요청 수를 초기화하지 않는다.
준비를 생략하고 공개부터 켜면 첫 요청이 준비를 시작하고 최대 30분간 503을 반환한다.

공유 Redis는 물리적으로 격리된 환경이 아니다. headroom 검사 이후 다른 요청이 메모리를 사용할 수 있고,
인증 키도 TTL이 있으면 eviction 대상이 될 수 있다. 지도 예산·차단 기준과 사전 알림을 함께 운영한다.

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
검증한다. 다음 페이지는 직전 정상 응답의 `nextCursor`를 그대로 사용하며 임의로 만들거나 수정하지 않는다.
전체 cursor·검색어·서명키를 오류나 로그에 넣지 않는다. Redis serializer/parser 예외의
payload 포함 가능성 때문에 cause도 외부 로그에 전달하지 않는다.

- RESTAURANT-013 / 410: 만료·유실·UUID 불일치·구버전/손상 세션.
- RESTAURANT-014 / 503: 지도 세션 비활성화·서명 설정 누락/오류·Redis 읽기/쓰기/연결 장애·메모리 보호 조건 미충족.
- RESTAURANT-015 / 503: DB 조회/transaction 장애.
- RESTAURANT-016 / 503: 인스턴스 동시 실행·후보 수·snapshot bytes·전체 예약·세션 수·호출자 cardinality·전역 호출 한도 초과.
- RESTAURANT-022 / 429: 호출자별 신규/전체 요청 한도 초과. 분 단위로 회복하므로 최대 60초 기다린 뒤 재시도한다.
- 기존 011/012/017/018은 #223 의미를 유지한다.

### 지도 Redis 연결과 공유 운영

인증과 기존 캐시는 Spring Boot의 RedisConnectionFactory·RedisTemplate·CacheManager를 그대로 사용한다.
지도 조회는 `hashi.restaurant.map.redis` 설정의 별도 연결을 사용한다. 이번 운영 방향은 기존 ElastiCache를
명시적으로 shared 모드로 재사용하는 것이다. 연결은 별개지만 같은 인스턴스의 메모리·장애는 공유한다.
후에 분리가 필요해지면 지도 endpoint와 mode를 바꾸며 기존 인증 배선은 유지한다.

| 설정 | 기본값 / 의미 |
| --- | --- |
| `mode` | dedicated(기본값): noeviction 전용 / shared: 기존 volatile-lru ElastiCache 공유 |
| `host`, `port` | 호스트 기본값 없음, 포트 6379. 호스트가 없으면 인증 Redis로 대체하지 않고 지도만 503 |
| `ssl` | false. TLS가 필요한 서버는 true, 인증서 검증은 유지 |
| `username`, `password` | 기본값 없음. 해당 Redis에서 ACL/비밀번호를 사용하는 경우 입력 |
| `connect-timeout`, `timeout` | 연결 2초, 명령 3초. 각각 1ms~30s 범위 |

환경 변수는 `HASHI_RESTAURANT_MAP_REDIS_MODE`, `..._HOST`, `..._PORT`, `..._SSL`, `..._USERNAME`,
`..._PASSWORD`, `..._CONNECTTIMEOUT`, `..._TIMEOUT`이다. 예시는 `.env.dev.example`에 있다.
enabled=true일 때 누락/잘못된 연결 설정은 값이나 예외 원문 없는 WARN을 남기며 앱 시작은 유지한다.
첫 유효 지도 요청 또는 활성 상태의 주기 관측에서 전용 연결과 제한된 Lettuce 자원을 생성하고,
앱 종료 때 함께 정리한다.
지도 factory/template은 Spring 빈으로 노출하지 않아 Boot의 기존 Redis 자동 구성을 끄지 않는다.
비활성 상태에서는 지도 연결과 추가 Lettuce 자원을 만들지 않는다.

다음 구성은 dedicated 모드의 로컬 회귀 검증용이다. 운영 EC2에 컨테이너를 추가하는 계획은 보류한다.

```shell
docker compose -p hashi-map-local -f docker/docker-compose.map-redis.local.yml up -d
# 로컬 앱: hashi.restaurant.map.redis.host=127.0.0.1, hashi.restaurant.map.redis.port=16379
# 검증 후 이 프로젝트의 임시 컨테이너만 정리
docker compose -p hashi-map-local -f docker/docker-compose.map-redis.local.yml down
```

이 파일은 루프백만 열며 컨테이너 한도 128MiB, Redis maxmemory 64MiB, noeviction을 사용한다.
세션 예약 16MiB와 headroom 32MiB 기본값을 유지하고, Lua는 설정 ceiling과 Redis maxmemory 중
작은 값으로 검사한다. AOF/RDB는 사용하지 않으므로 재시작하면 기존 조회는 410으로 끝나고 새로
조회해야 한다. 식당 원본과 인증 토큰은 다른 저장소에 남는다. 이 크기는 로컬 검증 시작값이며 운영 용량 보장이 아니다.
이 파일은 로컬 실험용이며 dev/prod compose에 Redis 컨테이너를 자동 추가하지 않는다.

지도 연결 무응답/쓰기 거절은 해당 지도 요청을 503으로 처리한다. 다만 shared Redis 자체가 멈추면
기존 인증도 같은 인스턴스를 사용하므로 영향을 받는다. 별도 연결이 장애 격리까지 보장하는 것은 아니다.
배포·공개 활성화·알림 규칙 등록은 이 변경만으로 실행되지 않는다.

### 사전 알림과 재검토 기준

활성 상태에서 30초마다 집계 정보만 조회해 다음 Prometheus gauge를 제공한다. 원문 키·주소·사용자는
태그로 넣지 않는다. 실패하면 probe=0과 NaN으로 바꿔 이전 수치가 정상 관측처럼 보이지 않게 한다.

| 지표 | 의미 |
| --- | --- |
| `hashi_restaurant_map_redis_enabled` | 지도 세션 활성 상태 1, 비활성 0. 비활성 환경의 probe 알림 제외 |
| `hashi_restaurant_map_redis_used_bytes` | 해당 Redis 인스턴스 전체 사용 메모리 |
| `hashi_restaurant_map_redis_admission_limit_bytes` | ceiling/maxmemory 중 작은 값에서 headroom을 뺀 차단선 |
| `hashi_restaurant_map_redis_warning_limit_bytes` | 차단선의 75%, 기본 설정에서 72MiB |
| `hashi_restaurant_map_redis_evicted_keys` | 인스턴스 시작 이후 강제 삭제 수, 지도 전용 수치가 아님 |
| `hashi_restaurant_map_redis_probe_success` | 마지막 조회 성공 1, 비활성/실패 0 |

기존 Grafana에서 환경별로 다음 규칙을 연결한다. 숫자는 보수적인 초기 운영값이며 실제 추이에 따라 조정한다.

- 메모리가 warning_limit을 5분 넘으면 경고한다. 차단선보다 먼저 대응하기 위한 기준이다.
- 메모리가 admission_limit의 90%를 1분 넘거나 Evictions가 증가하면 우선 확인한다.
- 지도 활성 환경에서 probe_success=0이 2분 지속되면 연결·ACL을 확인한다.
- `hashi_restaurant_map_capacity_rejected_total`의 `redis_memory_guard`, `redis_recovery`,
  `redis_accounting`을 구분한다. 복구 대기는 분당 최대 한 번 고정 WARN으로도 남긴다.

재사용할 PromQL 규칙은 [map-redis-alerts.example.yml](../../docker/prometheus/rules/map-redis-alerts.example.yml)에 있다.
기존 Prometheus에는 자동 로드하지 않으므로 Grafana에서 식을 복사하고 환경별 notification policy를 선택한다.
기존 Discord contact point에 연결할 수 있다. 이 PR은 지표와 기준을 제공하며 실제 Grafana 규칙 생성이나
AWS CloudWatch 알림 등록은 별도 운영 설정이다. ElastiCache 자체의 메모리·Evictions·CPU·FreeableMemory도
함께 확인한다. 정상 이용이 지도 제한에 반복해서 막히거나 인증 지연이 측정되면 전용 자원/증설을 재검토한다.

2026-10-11 운영 ElastiCache 사전 점검에서는 620개 후보의 합성 조회 데이터 256개를 저장·조회·TTL
갱신했고, 메모리는 약 6.57→9.16MiB였다. 강제 삭제는 발생하지 않았고 임시 키는 모두 정리했다.
이 결과는 추가 Redis 자원보다 기존 ElastiCache 재사용을 먼저 검증하기로 한 근거다. 소규모 저장 실험이며
현재 공유 모드 제품 코드의 HTTP 처리량·최대 동시 사용자·장기 운영을 검증한 결과는 아니다.

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
`MapRedisIsolationIntegrationTest`는 실제 Boot 인증 배선과 메모리 제한이 있는 Redis 두 개로
지도 연결 분리·무응답·재시작·쓰기 거절·TTL 회수를 검사한다. 지도 장애 중에도 실제
RefreshTokenStore의 저장·토큰 회전이 성공하는지 확인한다. 이는 로그인 HTTP 전체 흐름이나
EC2 운영 부하 검증과는 구분한다.
620개 합성 후보의 serializer 비교에서 기존 객체 배열은 UTF-8 31,188 bytes, compact tuple은
7,646 bytes였다. ledger 예약식까지 적용하면 63,400 bytes에서 16,316 bytes로 74.3% 줄었다.
큰 Long ID·리뷰 수와 긴 조건을 넣은 실제 Redis 500개 snapshot은 UTF-8 24,250 bytes,
MEMORY USAGE 24,712 bytes였다. 이는 한 payload 측정이며 전체 운영 용량을 보장하지 않는다.
부하·지속 부하·회복은 #236의 격리된 HTTP/MySQL/Redis+k6 시나리오에서 별도로 확인한다.
