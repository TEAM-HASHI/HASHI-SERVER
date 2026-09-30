# 컬렉션 전체 지도와 저장 정보 (#242)

상태: 구현과 독립 리뷰, 개발 후보 결합 및 upgrade 완료. 선행 최종 기준 재확인/전체 CI/GO는 진행 중이다.

기준 develop: `053fdb3a050d48956241357934a43e191ef99ece`.
V31~V33 개발 결합 기준: #231 `8d53a36c99378747afa33ab7b8f4e89343775efd` (최종 선행 GO 전 후보).
이 후보의 RestaurantPortImpl에 같은 findActiveMapInfos 선언이 두 번 있어 compileJava가 실패했다.
#242 결합 브랜치에서 동일 선언 한 개만 제거한 `3b03760`으로 관련 검증을 실행했다.
기획: HASHI-PLAN `6f2c99c3d089ddb65f015121312e19f66572ea03`.
이 문서는 #232 컬렉션을 확장하며, 새 컬렉션 CRUD를 만들지 않는다.

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

V34는 컬렉션에 `collection_version BIGINT NOT NULL DEFAULT 0`을 추가한다.
이미 적용 가능한 V28/V30 파일을 수정하지 않는다. 지도 스택의 V31~V33을 먼저 병합/적용한 뒤
V34를 적용해야 한다. V31만 있는 환경에 V34를 먼저 배포하지 않는다.
`outOfOrder`로 순서를 우회하지 않는다. 최종 업그레이드 검증은 V31~V33 포함 기준 SHA에서 수행한다.

## 남은 검증

- 개발 기준 #229 `53ed783`을 merge했으며 전체 핀 Port를 연결했다. 독립 리뷰/최종 승인은 별도다.
- V31~V33 개발 후보 결합과 V33→V34 업그레이드, 기존 관련 45건 검증 통과.
- 신규 관리자 위치 의존성의 테스트 wiring 보완 후 실제 지도 Port 3건 재검증 통과.
- 최종 PR/GO 전에 선행 최신 후보와 변경 영향 재확인.
- 최종 clean build/CI와 Draft PR, 중앙 담당자의 GO 판단.

## 독립 리뷰와 검증 근거

`0bc1dd46a9eba55144316ecefabc20daa88ae308`을 고정하고
`53ed78382572d6873e5bd52a6c8690fbe3efffed..HEAD`의 #242 변경을 독립 readonly 서브 에이전트 3명이 검토했다.
전체 흐름·scope, DB·migration·동시성, API·권한·반례 모두 supported blocker 없이 READY였다.
이는 선행 스택 및 운영 병합 GO를 대신하지 않는다. 수용 finding은 없어 production 보완 round는 없었다.

관련 검증 명령은 JDK21에서 다음과 같다.

```text
gradlew.bat test --tests '*RestaurantCollectionIntegrationTest' --tests '*CollectionMySqlIntegrationTest' --tests '*RestaurantSaveSummaryHttpTest' --tests '*CollectionMapHttpMySqlTest' --tests '*ModularityTests' --console=plain --max-workers=2
```

5 suites / 45 tests / failures 0 / errors 0 / skipped 0.
MySQL8.4에서 기존 managed Entity가 stale인 것을 먼저 증명한 뒤 현재 비공개를 scalar locking read로 차단했다.
실제 HTTP OSIV=true request를 Port에서 멈춘 동안 비공개 전환/삭제/이동이 커밋되어 404/404/409로 응답했다.
동시 마지막 자리 저장은 성공 1개와 USER-011 1개로 상한 1000을 유지했으며, 반대 방향 이동도 모두 완료했다.
전체 ID snapshot은 저장 1000개에도 2 SQL, 최종 검사 1 SQL이었다.

실제 RestaurantPortImpl과 RestaurantMapService를 연결한 MySQL8.4 검증:

```text
gradlew.bat test --tests '*CollectionMapPortMySqlIntegrationTest' --console=plain --max-workers=2
```

1 suite / 3 tests / failures 0 / errors 0 / skipped 0.
실제 저장 후 지도·집계·내 저장 여부를 조회했고, 23개 중 좌표 없는 2개를 제외한 21개 핀을 확인했다.
삭제 식당은 저장 관계를 유지하면서 응답에서 제외했다. 1000개 지도는 Port 2 batch와 전체 5 SQL,
컬렉션 Entity 1개 로딩으로 반환했다. 501개 조회의 두 번째 batch 장애는 USER-017(503)으로 전파했다.

## V33→V34 개발 후보 업그레이드

`8d53a36` 결합 및 중복 선언 제거 후 위 45건과 `CollectionVersionMigrationTest` 1건이 모두 통과했다.
MySQL8.4/Flyway의 실제 current version 33에서 기존 컬렉션과 저장 관계를 넣고 V34를 적용했다.
기존 이름·설명 공백과 저장 관계 2개가 보존되고, collection_version은 null 없이 0으로 초기화됐다.
Flyway validate도 통과했다. 이 근거는 최종 선행 후보 승인 및 전체 CI를 대신하지 않는다.
결합 후 실제 Port 테스트는 미사용 관리자 위치 Service bean 누락으로 3건이 초기화 실패했다.
해당 의존성만 mock으로 격리한 뒤 같은 3건을 재실행해 failures/errors/skipped 모두 0을 확인했다.
따라서 개발 후보 결합의 관련 검증은 45 + upgrade 1 + 실제 Port 3 = 49건이다.
