# 컬렉션 전체 지도와 저장 정보 (#242)

기존 컬렉션의 저장 관계를 사용해 전체 핀과 저장 정보를 조회한다.
일반 지도 목록의 페이지 조회와 별개이며, 확대·축소나 BBOX로 핀을 줄이지 않는다.

## API

`GET /api/v1/collections/{collectionId}/map-markers`는 선택한 컬렉션을 한 번에 표시하기 위한 API다.
기존 `/restaurants` 목록의 스크롤/커서와 독립적으로 전체 핀을 요청한다. BBOX·검색·음식점 분류를
적용하지 않으며 식당 ID 오름차순이다. `visibleRestaurantCount`는 현재 존재하는 저장 식당 수,
`locationUnavailableCount`는 그중 좌표가 없는 식당 수다. `generatedAt`은 마지막 좌표 유효성 검사 시각이며 UTC다.
예를 들어 삭제 식당 없이 23개 중 2개 좌표가 없으면 전체 수 23, `content` 21개, 위치 불가 수 2다.

`GET /api/v1/restaurants/save-counts?restaurantIds=3,1,2`는 공개 집계다.
응답 `restaurants` 배열에 `restaurantId`와 `saveCount`를 요청 순서로 반환한다.
삭제/미존재 식당은 배열에서 제외하며, 실제 식당의 저장 관계가 없으면 `saveCount=0`이다.

`GET /api/v1/users/me/restaurant-saves?restaurantIds=3,1,2`는 USER 본인의 조회다.
응답 `restaurants` 배열에 `restaurantId`와 `saved`를 반환한다. ADMIN/ONBOARDING은 사용할 수 없다.
두 요약 API는 빈 목록·누락·null·0·음수·중복·101개 이상 요청을 COMMON-400으로 거부한다.
새 API 성공 응답은 기존 SuccessResponse 봉투와 `Cache-Control: no-store`를 사용한다.
지도 과량/조회 장애는 신규 `USER-017`(503)이다. M1 초기 초안의 `USER-008`은 현재 #232에서
이미 지원하지 않는 색상(400)으로 사용 중이므로 재사용하지 않는다. 기존 오류의 의미는 유지한다.

## 구현 선택

- 저장한 사용자 수는 `count(distinct collection.userId)`다. 공개/비공개 모두 집계한다.
  같은 사용자의 두 컬렉션은 1명이며 마지막 저장 관계 제거/컬렉션 삭제로만 감소한다.
- 내 저장 여부는 인증된 USER 본인의 소유 컬렉션에서 조회한다. 식당 ID 1~100개를
  중복 없이 양수로 요구하고 현재 존재하는 식당만 요청 순서대로 응답한다.
  RestaurantPort 실패를 빈 결과나 0/false로 변환하지 않는다. Redis 개인 캐시는 추가하지 않는다.
- 집계는 user의 컬렉션/저장 관계만 조인한다. restaurant 테이블은 조인하지 않는다.
  V30의 `saved_restaurant(restaurant_id)` 인덱스로 요청 ID를 한정하며 집계와 내 저장 조회 각각 1 SQL이다.
- 컬렉션 전체 지도는 기존 상한 1,000개를 재사용한다. 초기 조회는 상한+1까지만 읽어
  과량을 503으로 거부하며, restaurant의 500개 batch Port가 부분 실패를 전파한다.
- 메타데이터/저장관계 변경을 하나의 명시적 `collectionVersion`으로 식별한다.
  모든 기존 쓰기는 부모 행을 먼저 잠그며, 이동은 작은 ID부터 부모 두 행을 잠근다.
  inverse 자식 변경만으로 부모 @Version 갱신을 기대하지 않는다.
- 초기 변경번호와 membership은 같은 RR snapshot으로 읽는다. Port 호출 중에는 부모 잠금을
  유지하지 않는다. 최종 권한/변경번호 검사에는 새 RC 트랜잭션의 current locking read를 사용한다.
  비공개 전환/삭제/권한 없음은 동일 404, 허용된 컬렉션의 변경번호 불일치는 409다.
  검사 종료 이후 미래 변경을 막는 약속은 하지 않는다.
- 응답 직전에 좌표 `validUntil`을 다시 확인한다. 좌표 없는 식당은 기존 목록의 관계를
  유지하고 핀만 빠진다. 지도 API에는 BBOX/검색/분류/페이지 규칙을 적용하지 않는다.
- 사용자 직접 결정(2026-10-01): 생성·수정 모두 이름과 설명의 앞뒤 공백을 보존한다.
  공백만인 이름은 거부한다. 설명 공백 문자열은 보존하며 PATCH null은 유지, 빈 문자열은 삭제다.
  이름 중복 판정은 기존 DB collation을 변경하지 않는다. 이는 수정 시 trim을 적은
  SAVED_COLLECTION_MANAGE 명세보다 우선하는 직접 결정이다.

## DB 적용 순서

`V41__add_collection_version.sql`은 컬렉션에 `collection_version BIGINT NOT NULL DEFAULT 0`을 추가한다.
이미 병합된 V28/V30 및 `V31__link_magazine_card_news_to_media_assets.sql`은 수정하지 않는다.
지도 스택의 V38(위치)·V39(작업)·V39.1(기본 주소)·V39.2(Places 선택)·V40(유지보수)를 먼저 적용한 뒤 V41을 적용한다.
선행 지도 migration이 빠진 환경에 V41을 먼저 배포하거나 `outOfOrder`로 순서를 우회하지 않는다.
최종 업그레이드 검증은 V40까지 적용한 DB에서 V41을 실행해 기존 컬렉션과 저장 관계의 보존을 확인한다.

## 좌표 갱신 정책 반영

같은 주소의 정기 갱신이나 재시도·실패 중에도 기존 검증 좌표가 유효하면 핀을 유지한다.
주소가 바뀌었거나 validUntil에 도달하면 핀만 제외한다. 식당과 컬렉션의 저장 관계는 유지한다.
새 결과 없이 기존 좌표의 만료 시각을 연장하지 않는다.
Places 제3자 출처가 있으면 `location.attributions`의 이름·HTTPS 링크를 좌표와 함께 표시한다.
일반 지도 카드·단건 위치도 같은 Port 값을 사용하며, 만료된 좌표의 출처는 함께 제외한다.
출처가 손상되면 출처 없는 핀으로 바꾸지 않고 조회 실패로 처리한다.

관련 검증은 실제 CollectionMapQueryService → RestaurantPort → MySQL 경로로 수행한다.
23개 중 좌표가 없는 2개 제외, 1,000개 전체 핀, 두 번째 batch 실패 시 부분 응답 차단과 함께
갱신·재시도·실패 중 좌표 유지, 주소 변경·정확한 만료 시각의 핀 제외를 확인한다.
공개/비공개 접근과 응답 직전 컬렉션 변경 검사는 기존 HTTP 통합 테스트로 확인한다.
