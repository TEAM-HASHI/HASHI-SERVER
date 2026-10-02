# Google Geocoding adapter (M3a, #222)

## 범위와 호출 경계

이 문서는 PR #226 (M3a, #222)의 adapter 범위를 기록한다.
`restaurant.internal.map.GeocodingProvider.geocode(address)`는 저장 전 후보를 반환하며,
원래 PR #226에서는 Controller, RestaurantPort, JPA, migration, SecurityConfig, Redis와 worker에 연결하지 않았다.
M2 #220의 모델과 독립적으로 사용할 수 있다.

2026-10-03 기준 통합 PR #233에는 관리자 등록·미삭제 식당의 주소 변경에 따른 job 생성,
worker/lease와 이 provider의 연결, 관리자 위치 상태 DTO가 구현돼 있다.
[위치 작업 문서](location-jobs.md)의 코드 연결은 운영 활성화나 실제 지역·주소 품질 검증,
Google 보관 정책 승인 완료를 의미하지 않는다.

Spring Boot BOM이 관리하는 `org.apache.httpcomponents.client5:httpclient5`를 추가한다.
JDK 21.0.7 client로는 응답 없이 연결을 끊는 합성 테스트에서 GET 요청이 두 번 전송됐다.
재시도/redirect를 명시적으로 차단할 수 있는 Spring의 지원 transport를 사용하기 위한 의존성이다.
기존 HTTP 호출인 KakaoOAuthClient는 `SimpleClientHttpRequestFactory`를 직접 지정하므로
classpath에 따른 자동 선택의 영향을 받지 않는다. 다른 RestClient/RestTemplate 사용처는 없다.

패키지는 [M1 ADR 0002의 고정본](https://github.com/TEAM-HASHI/HASHI-SERVER/blob/d2df3100fcfe693956662aae9ab1845275e163d5/docs/adr/0002-restaurant-map-query-and-location.md)의
`restaurant/internal/map` 경계를 따른다. Google 구현과 설정은 그 아래 `google`에 있다.
다른 모듈은 이 provider나 후보 DTO를 import하지 않는다.

- `Candidates`: 모든 후보를 불변 목록으로 반환한다. 첫 후보를 선택하지 않는다.
- `NoResults`: 정상 JSON의 빈 `results` 또는 repeated 필드가 생략된 `{}`다.
- `Failure`: 분류 enum과 알려진 HTTP 상태만 전달한다. 원문 메시지나 예외 cause는 없다.

후보의 위도/경도는 `BigDecimal`이며 각각 [-90, 90], [-180, 180]을 검증한다.
명시적 `(0,0)`은 유효하다. 누락/문자열/비유한 좌표를 영점으로 대체하지 않는다.
국가, 행정구역, 주소 구성요소, types, granularity를 반환하며 PR #233의 채택 정책이 국가·주소·정확도를 검증한다.
누락되거나 새로 추가된 granularity는 `UNKNOWN`이고, 누락된 문자 메타데이터는 빈 문자열이다.
알 수 없는 필드는 무시하지만 알려진 필드의 잘못된 자료형, JSON 중복 키, trailing token은 거부한다.
후보 중 하나라도 형식이 잘못되면 전체 응답이 `INVALID_RESPONSE`다.

## HTTP 계약

2026-09-26 확인한 [v4 GA 개요](https://developers.google.com/maps/documentation/geocoding/geocoding-v4-overview),
[주소 요청](https://developers.google.com/maps/documentation/geocoding/geocoding),
[v3 이전 안내](https://developers.google.com/maps/documentation/geocoding/geocoding-v4-migrate),
[GeocodeResult](https://developers.google.com/maps/documentation/geocoding/reference/rest/v4/GeocodeResult)를 따른다.

- 고정 `https://geocode.googleapis.com/v4/geocode/address`에 GET을 보낸다. endpoint 설정은 없다.
- 주소는 `address.addressLines` URI 변수로 엄격히 인코딩한다. 일본어와 `+ & # / %`를 데이터로 보존한다.
- `languageCode=ja`, `regionCode=JP`는 언어/지역 편향이다. 일본 결과를 보장하지 않는다.
- 서버 키는 `X-Goog-Api-Key` 헤더에만 넣는다.
- `X-Goog-FieldMask`는 location, granularity, postalAddress의 regionCode/administrativeArea,
  addressComponents의 longText/shortText/types와 결과 types로 제한한다.
- v3의 `status`, `error_message`, `partial_match`를 성공 판정에 사용하지 않는다.
- 성공 200 JSON은 설정된 크기 안에서 읽는다. 403은 권한 거부와 quota 초과가 겹치므로
  JSON·비압축 응답의 처음 8KiB 안에서 `error.status`만 제한적으로 판독한다.
  malformed·초과·비 JSON 오류는 ACCESS_DENIED로 분류한다. `error.message`, details,
  Retry-After의 원문은 결과나 로그에 전달하지 않는다.
- redirect, proxy, cookie, authenticator와 자동 SDK 재시도는 사용하지 않는다.

| HTTP/상황 | 실패 분류 |
|---|---|
| 비활성 | DISABLED |
| 빈 주소 또는 기존 주소 한도 255자 초과, HTTP 400/422 | INVALID_REQUEST |
| 401, 403 PERMISSION_DENIED·알 수 없거나 읽을 수 없는 오류 | ACCESS_DENIED |
| 403 RESOURCE_EXHAUSTED | QUOTA_EXCEEDED |
| 404 | CONFIGURATION_ERROR |
| 429 | QUOTA_EXCEEDED |
| 5xx | TRANSIENT_ERROR |
| HTTP 408, DNS/연결/응답 deadline | TIMEOUT |
| 호출자 thread 중단 | CANCELLED |
| 진행 중인 호출 4개로 실행 한도 도달 | CAPACITY_EXCEEDED |
| 연결 실패·응답 도중 연결 유실 | CONNECTION_ERROR |
| 3xx | REDIRECT_REJECTED |
| 응답 크기 초과 | RESPONSE_TOO_LARGE |
| JSON/자료형/좌표/Content-Type 오류, 그 밖의 상태 | INVALID_RESPONSE |

2026-10-03 [v4 개요](https://developers.google.com/maps/documentation/geocoding/geocoding-v4-overview)와
[AIP-193의 HTTP JSON 오류 형식](https://google.aip.dev/193#http11json-representation)을 다시 확인했다.
`error.status`는 canonical code이며 v3/legacy의 `OVER_QUERY_LIMIT`, `rateLimitExceeded`,
`userRateLimitExceeded`, `OVER_DAILY_LIMIT`, `dailyLimitExceeded`를 v4 상태로 해석하지 않는다.
이 값이나 알 수 없는 값이 담긴 403은 `ACCESS_DENIED`로 분류한다.
403 분류 검증은 합성 mock 응답 기준이며 실제 Google 403 응답을 확인한 결과가 아니다.
현재 adapter는 quota의 일일/분당 한도를 구별하지 않으며 `ErrorInfo`를 추측해 추가 분류하지 않는다.
이 분류만으로 재시도를 허가하지 않는다. PR #233의 worker는 내부 호출 예산·누적 attempt·공유 quota 대기를
확인해 제한 재시도/backoff를 적용한다. 특히 quota를 무한 재시도하지 않는다.

## 제한과 설정

Spring 설정 prefix는 `hashi.map.google-geocoding`이다. 별도 설정이 없으면 비활성이며 키 없이 부팅한다.
활성 시 키 형식과 제한값을 Bean 생성 중 검증한다. 검증 자체는 외부 요청을 하지 않는다.

| 설정 | 기본값 | 허용 범위 |
|---|---|---|
| enabled | false | true/false |
| api-key | 없음 | 비어 있지 않은 서버 키; 공백/헤더 제어문자 거부 |
| connect-timeout | 2s | 1ms~10s |
| response-timeout | 5s | 1ms~30s, connect-timeout 이상 |
| max-response-bytes | 65536 | 1024~1048576 |

환경 변수는 Spring relaxed binding 규칙을 따른다. 예를 들어 enabled는
`HASHI_MAP_GOOGLEGEOCODING_ENABLED`, api-key는 `HASHI_MAP_GOOGLEGEOCODING_APIKEY`다.
키는 비밀 설정으로 공급하며 문서·명령줄 인자·로그에 넣지 않는다. 이 PR에서는 활성화하지 않는다.

RestClient에는 전용 `HttpComponentsClientHttpRequestFactory`를 지정한다.
각 호출은 virtual thread에서 실행하고 호출자는 response-timeout까지만 기다린다.
DNS가 중단을 무시해도 호출자는 `TIMEOUT`으로 반환한다. 이때 Apache request를 취소하고
전용 client를 즉시 닫아 DNS가 뒤늦게 끝나도 HTTP를 보내지 않는다. 호출자 중단은
interrupt 상태를 유지하며 `CANCELLED`로 반환한다. 작은 조각을 계속 보내는 본문도 같은 deadline을 적용한다.
실제 작업이 종료될 때까지 동시 실행 슬롯을 유지한다. provider 인스턴스당 최대 4개이며,
장애 중 추가 호출은 큐에 쌓지 않고 `CAPACITY_EXCEEDED`로 반환한다. DNS 작업이 종료되면 슬롯을 회수한다.
Content-Length와 별개로 최대 한도+1 byte만 읽고, 초과 시 스트림을 닫는다. 압축 본문은 거부한다.
Jackson의 중첩 깊이·숫자 길이·문자열 길이도 제한한다.

한 호출마다 전용 client를 사용하며 자동 재시도, redirect, 인증 재요청, cookie와 압축을 끈다.
본문 처리 후 client를 즉시 닫고 Spring response를 닫으므로 cleanup이 남은 큰 본문을 읽지 않는다.
연결 재사용 비용보다 요청 1회와 즉시 중단 보장을 우선한 구성이다.
HTTP 라이브러리 변경 시 응답 없이 연결을 끊는 wire 테스트를 유지한다.

## 정보 노출과 검증

원문 주소, 요청 전체 URL, 키, Google 원문 응답과 좌표를 로그에 남기지 않는다.
properties, 후보 및 주소 구성요소의 `toString()`을 마스킹하고, 파서/HTTP 예외 cause를 전달하지 않는다.
새 transport의 logger는 logback에서 OFF로 두고, wire/header/request debug override가 있으면
활성 Bean 생성을 거부한다. 운영에서도 HTTP/TLS wire dump나
요청 헤더/body 수집을 켜지 않는다. 결과 관측에 허용하는 값은 실패 종류와 HTTP 상태다.
예상하지 못한 `RuntimeException`과 작업의 `ExecutionException`은 `INVALID_RESPONSE`로 유지하며,
진단 WARN에는 고정 phase(`execute`/`await`)와 예외 클래스명만 기록한다.
`await`에서는 바로 아래 cause의 클래스명만 확인한다. 원문 메시지, Throwable, stacktrace와
cause chain은 로그에 전달하지 않는다.

테스트는 합성 데이터와 mock/loopback HTTP만 사용한다. 실제 Google 계정·키·유료 API는 사용하지 않는다.
`GoogleGeocodingWireTest`는 인코딩/헤더, redirect, 실제 연결 유실 후 재전송 여부,
429/503, header/body deadline, Content-Length/chunked 크기 제한을 검증한다.
`GoogleGeocodingDeadlineTest`는 중단을 무시하는 합성 DNS로 호출자 반환, 늦은 HTTP 차단,
실행 슬롯 상한, 추가 호출 거부와 DNS 종료 후 회복을 검증한다.
설정/파서 테스트는 활성·비활성, 빈/복수 후보, 누락/알 수 없는 값, 숫자 경계,
잘못된 JSON과 예외/로그/toString 비노출을 검증한다. `GeocodingBoundaryTest`와
`ModularityTests`가 모듈 외부 사용과 순환 의존을 검사한다.

## PR #233 연결 구현과 운영 전 확인

2026-10-03 기준 다음 1~4는 PR #233에 구현돼 있으며, 5는 운영 전 확인 사항이다.

1. DB transaction 밖에서 호출하도록 worker와 claim/완료 transaction Bean을 나눴다.
2. 후보 수·국가·지원 영역·주소 일치·정확도를 판정하고 모호하면 검토 상태로 둔다. 관광 지역 ID는 추론하지 않는다.
3. 좌표 6자리 저장 정밀도 변환, source/obtainedAt/validUntil과 주소 revision을 적용한다.
4. job/lease/revision을 재검사한 완료, 제한 재시도/backoff, quota·일일 예산과 안전한 상태 DTO를 연결했다.
5. 실제 계약·키 제한·quota·주소 품질·지역과 보관 정책을 확인한 후 별도 승인으로 운영을 활성화한다.

M3a adapter와 PR #233의 worker 연결 구현은 실제 Google 호출 검증·운영 활성화나 지도 전체 완료를 의미하지 않는다.
