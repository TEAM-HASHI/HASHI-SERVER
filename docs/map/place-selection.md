# 관리자 Places 후보 선택 (#224)

주소 Geocoding만으로 식당 시설을 확정하기 어려운 `REVIEW_REQUIRED`와 잘못 채택된 `READY` 위치를
관리자가 Google Places 후보로 교정하는 계약이다. 후보 검색과 선택은 기존 ADMIN 권한을 사용하며,
클라이언트가 검색어, Place ID 또는 저장 좌표를 직접 지정하지 않는다.

## API와 상태 전이

- `POST /api/v1/admin/restaurants/{restaurantId}/location/place-candidates`
  - body: `{"expectedAddressRevision":2}`
  - 서버가 저장된 현지 식당명과 `geocodingAddress`(없으면 `address`)로 검색한다.
  - `READY`, `REVIEW_REQUIRED`, `FAILED`, `RETRY_WAIT`에서만 허용한다. `PENDING`, 주소 revision 변경,
    현재 request 변경은 409다.
  - 결과 없음과 도쿄 범위를 통과한 후보 없음은 200과 빈 `candidates`다. provider 장애는 빈 결과로 바꾸지 않는다.
  - 후보는 표시명·주소·좌표·국가·행정구역·유형·영업 상태·attribution·Google Maps URI와
    `selectionToken`, `selectionExpiresAt`을 반환한다. 원본 Place ID는 응답 필드로 내리지 않는다.
- `POST /api/v1/admin/restaurants/{restaurantId}/location/place-selection`
  - body: `{"expectedAddressRevision":2,"selectionToken":"..."}`
  - 성공은 200과 `PENDING`이다. 저장 성공일 뿐 Places Details 완료를 뜻하지 않는다.
  - token 변조·만료는 400, token의 restaurant/revision/request와 현재 값 불일치 또는 `PENDING`은 409다.
  - 상태 조회의 `verificationMode=PLACE_DETAILS`와 이후 `READY`, `validUntil`을 확인한다.

검색 후보는 `countryCode=JP`, 행정구역 `Tokyo` 또는 `東京都`, 설정된 지도 BBOX 안의 좌표를 모두
통과해야 token을 받는다. Details 결과도 같은 조건과 token의 Place ID 일치를 다시 검사한다.
Places Details에는 Geocoding의 `ROOFTOP` 개념을 적용하지 않는다.
Details 성공 시 제3자 attribution의 표시명과 HTTPS URI를 좌표와 같은 수명으로 저장한다. 후보 표시명·주소와
달리 공개 지도·컬렉션에서 좌표와 함께 전달해야 하는 최소 metadata다.

## 후보 결합과 저장 범위

`selectionToken`은 HMAC-SHA256으로 restaurantId, addressRevision, 현재 requestId, Place ID, 발급·만료 시각을
묶으며 유효 시간은 10분이다. 키는 후보 DB 테이블을 만들지 않고도 검색 결과를 현재 위치 상태에 결합하기
위해 필요하다. 검색 응답의 표시명·주소·유형·attribution은 즉시 응답에만 사용하고 DB에 저장하지 않는다.

선택 시 새 requestId와 `PLACE_DETAILS` job을 만들고 job에만 선택 Place ID를 보관한다. 잘못된 핀을
교정하는 동작이므로 수동으로 다른 후보를 선택하면 이전 승인 좌표·source·location Place ID를 즉시 지운다.
Details가 성공하면 좌표, `source=GOOGLE_PLACES`, Place ID, 취득 시각과 30일 유효 기한을 한 번에 저장한다.

같은 Place ID의 정기 갱신은 수동 교체와 다르다. 아직 유효한 좌표를 유지한 채 Details job을 만들고,
새 결과가 검증된 뒤 교체한다. 만료 정리가 accepted location의 Place ID를 비워도 현재 request의 job에
`operation=PLACE_DETAILS`와 Place ID가 남으므로 재시도는 Geocoding으로 바뀌지 않는다. job이 없거나
operation과 Place ID가 모순되면 자동 fallback하지 않고 충돌로 끝낸다.
주소 변경·수동 후보 교체·만료 제거는 좌표와 attribution metadata를 함께 지운다.

## 예산과 실행

`restaurant_places_budget`은 `SEARCH`, `DETAILS` 두 행을 별도로 잠근다. 각 행은 `enabled`, UTC 일일 한도,
UTC 분당 한도, 사용량과 `blocked_until`을 가진다. 초기값은 두 행 모두 disabled, 일 100, 분 10이다.
행 누락, disabled, 0 이하 한도, 일·분 한도 소진, 공유 차단 시간은 fail closed다.

restaurant와 job의 현재 tuple을 먼저 확인하고 operation budget을 잠근 뒤 실제 provider 호출 전에 사용량을
증가시킨다. timeout, 알 수 없는 전송 결과, 빈 결과, provider 오류, 호출 중 주소 변경도 환불하지 않는다.
SEARCH 소진은 DETAILS를, DETAILS 소진은 Geocoding을 막지 않는다. 기존 Geocoding 동시 실행 카운트는
`operation=GEOCODING` job만 센다. worker는 두 operation 후보를 교차 처리한다.

## 설정과 활성화

- `hashi.map.google-places.enabled=false`가 기본이며 API key, timeout, 응답 크기 제한을 별도로 설정한다.
- `hashi.map.places-selection.enabled=false`가 기본이다.
- `hashi.map.places-selection.signing-key`는 Base64 디코딩 후 32바이트 이상인 전용 비밀키여야 한다.
- `hashi.map.places-selection.token-ttl`은 10분이어야 한다.
- provider, 서명 또는 지도 bounds 설정이 없으면 SEARCH 예산을 쓰기 전에 503으로 fail closed한다.
- 애플리케이션 설정을 켜도 DB의 SEARCH/DETAILS 행은 자동 활성화하지 않는다.

Docker 환경 변수는 `HASHI_MAP_GOOGLE_PLACES_ENABLED`, `HASHI_MAP_GOOGLE_PLACES_API_KEY`,
`HASHI_MAP_PLACES_SELECTION_ENABLED`, `HASHI_MAP_PLACES_SELECTION_SIGNING_KEY`,
`HASHI_MAP_PLACES_SELECTION_TOKEN_TTL`로 전달한다. 기존 Google Geocoding key에 Places API (New) 권한도
설정했다면 같은 비밀값을 Places API key 환경 변수에 별도로 주입할 수 있다. selection 서명키에는
provider API key를 재사용하지 않는다.

키, selectionToken, Place ID, 검색어, provider 원문과 전체 요청 URI는 로그에 남기지 않는다.

## 변경 전후

| 상황 | 변경 전 | 변경 후 |
|---|---|---|
| 주소 Geocoding이 시설을 특정하지 못함 | REVIEW_REQUIRED에서 주소 재시도만 가능 | 저장 정보 기반 Places 후보 검색 후 관리자 선택 가능 |
| 잘못된 READY 핀 교정 | READY 일반 retry 불가 | 후보 선택 즉시 기존 좌표 제거 후 Details 검증 |
| Places 재시도 | 없음 | current job의 operation과 Place ID를 그대로 복제 |
| provider 예산 | Geocoding 단일 일일·동시 한도 | SEARCH·DETAILS 일일/분당 예산을 서로 분리 |
| provider 콘텐츠 저장 | Geocoding 좌표와 안전 코드만 저장 | 성공 좌표·Place ID·필수 attribution만 저장하고 후보명·주소는 저장하지 않음 |
