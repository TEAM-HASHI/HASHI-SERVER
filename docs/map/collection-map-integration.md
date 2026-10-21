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

`GET /api/v1/restaurants/{restaurantId}/summary`의 `saveCount`는 식당 상세 하단에 표시할 식당 저장 수다(#263).
값은 `/restaurants/save-counts`와 같은 기준(회원 한 명당 최대 1)이지만 매번 세지 않고 `restaurant.save_count` 카운터를 읽는다.
summary는 restaurant 모듈이고 저장 관계는 user 모듈이라 restaurant가 user를 조회하면 순환이 되므로,
리뷰 통계(`rating_sum`·`review_count`)처럼 user 모듈이 `RestaurantPort.increaseSaveCount/decreaseSaveCounts`로 증감한다.

`GET /api/v1/users/me/saved-restaurants/count`는 마이 화면 `내가 찜한 식당`에 표시할 USER 본인의 저장 식당 수다(#259).
응답은 `savedRestaurantCount` 하나이며, 내 컬렉션 전체(공개·비공개)에서 중복을 제외한 식당 수다.
같은 식당을 여러 컬렉션에 저장해도 1로 세고, 삭제된 식당은 컬렉션별 저장 식당 수와 같은 기준으로 제외한다.
그래서 컬렉션 목록의 컬렉션별 저장 식당 수 합계보다 작을 수 있다. ADMIN/ONBOARDING은 사용할 수 없고 응답은 no-store다.

## 구현 선택

- 저장한 사용자 수는 `count(distinct collection.userId)`다. 공개/비공개 모두 집계한다.
  같은 사용자의 두 컬렉션은 1명이며 마지막 저장 관계 제거/컬렉션 삭제로만 감소한다.
- 내 저장 여부는 인증된 USER 본인의 소유 컬렉션에서 조회한다. 식당 ID 1~100개를
  중복 없이 양수로 요구하고 현재 존재하는 식당만 요청 순서대로 응답한다.
  RestaurantPort 실패를 빈 결과나 0/false로 변환하지 않는다. Redis 개인 캐시는 추가하지 않는다.
- 식당 저장 수 카운터(#263): 회원의 첫 저장에 +1, 회원의 마지막 저장 관계 제거(식당 제거·컬렉션 삭제·탈퇴)에 -1이며
  같은 회원의 컬렉션 간 이동은 변동이 없다. 증감은 원자 UPDATE(감소는 0 아래로 내려가지 않음)이고,
  첫 저장·마지막 제거 판정은 저장·제거·삭제 트랜잭션의 첫 문장에서 회원 행을 잠가(`findByIdForUpdate`) 회원 단위로 직렬화한다.
  잠금 순서는 회원 → 컬렉션 → 식당으로 고정하고, 탈퇴의 일괄 감소는 식당 ID 오름차순이다. 포트 호출은 같은 트랜잭션이라 함께 롤백된다.
  V43이 컬럼을 추가하고 기존 저장 관계를 회원 distinct로 backfill한다. 대조 쿼리: `select count(distinct c.user_id) from saved_restaurant s join restaurant_collection c on c.id = s.collection_id where s.restaurant_id = ?`.
- 저장 식당 수는 저장 관계에서 사용자 기준 `distinct restaurantId`를 읽은 뒤 `RestaurantPort.countActiveByIds`로
  삭제 식당을 뺀다. 카드 정보는 읽지 않고 restaurant 테이블도 조인하지 않는다.
  distinct ID 수는 서비스에 등록된 식당 수를 넘지 않으므로 컬렉션·저장 상한과 무관하다.
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
- 생성·수정 모두 이름과 설명의 앞뒤 공백을 제거하고 중간 공백은 유지한다.
  NBSP·전각 공백도 같은 규칙을 적용한다. 공백만 있는 이름은 거부한다.
  이름 20자·설명 30자(공백 포함) 제한은 공백을 제거하기 전 입력값에 적용하고, 설명에 줄바꿈 문자가 있으면 거절한다(#269).
  DB 컬럼은 VARCHAR(100)을 유지하며 기존 30자 초과 설명은 조회와 설명을 보내지 않는 PATCH에서 그대로 둔다.
  설명은 생성 시 비어 있으면 null로 저장하고, PATCH에서는 null이면 유지, 빈 문자열·공백만 보내면 삭제한다.
  예: 이름 `  도쿄  맛집  `은 `도쿄  맛집`으로 저장하고, 같은 사용자의 이 이름이 이미 있으면 409다.
  이름 중복 판정에는 공백 제거 후 값을 사용하며 기존 DB collation·유니크 제약은 유지한다.
  기존 행을 일괄 수정하지 않는다. 이전 값에 공백이 있더라도 이름을 보내지 않은 PATCH는 유지하며,
  이름을 다시 보냈을 때 정규화 결과가 다른 컬렉션과 충돌하면 저장하지 않고 409로 응답한다.
  공백 처리는 2026-10-12 확인한 수정 화면을 기준으로 생성·수정에 동일하게 적용한다.
  HASHI-PLAN f11be8f(2026-10-20)가 SAVE·MANAGE 명세를 같은 규칙(앞뒤 공백 제거, 제거한 이름으로 중복 비교,
  설명 공백 포함 30자·직접 줄바꿈 불가)으로 갱신해 서버 동작과 일치한다.
- 저장 식당 목록 응답의 `totalCount`(#269)는 분류 필터를 적용한 뒤 커서·페이지 크기를 적용하기 전의 건수다.
  상세 상단 "총 N곳"에 선택한 분류의 개수를 표시하기 위한 값으로, 삭제된 식당은 세지 않고 분류 필터가 없으면
  상세 `savedCount`와 같다. 목록 조회가 이미 저장 식당 전체를 메모리에서 거르므로 추가 쿼리는 없다.
  분류별 개수 표시는 HASHI-PLAN에 명세가 없어("총 N곳"은 시안 이미지에만 있음) 기획에 집계 기준 추가를 요청한 상태다.

## DB 적용 순서

`V41__add_collection_version.sql`은 컬렉션에 `collection_version BIGINT NOT NULL DEFAULT 0`을 추가한다.
이미 병합된 V28/V30 및 `V31__link_magazine_card_news_to_media_assets.sql`은 수정하지 않는다.
지도 스택의 V38(위치)·V39(작업)·V39.1(기본 주소)·V39.2(Places 선택)·V39.3(위치 확인 기록)·V40(유지보수)를 먼저 적용한 뒤 V41을 적용한다.
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
