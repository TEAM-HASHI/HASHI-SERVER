# 관리자 관광 지역 API

[#251](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/251)은 운영자가 관광 지역의 대표 화면과
식당 소속을 입력할 수 있게 한다. 기존 `map_region`·`restaurant.map_region_id`를 사용하며
테이블, dependency, 인증 규칙을 추가하지 않는다. 실제 지역 좌표나 식당 소속 seed는 포함하지 않는다.

## API와 입력 순서

세 API는 기존 `/api/v1/admin/**` 규칙에 따라 ADMIN만 접근한다. 응답은 `COMMON-200`과
`SuccessResponse`, `Cache-Control: no-store`를 사용한다.

| 요청 | 역할 |
| --- | --- |
| `GET /api/v1/admin/map-regions?page=0&size=20` | 비활성 지역까지 `displayOrder`, ID 순으로 조회 |
| `PUT /api/v1/admin/map-regions/{code}` | 같은 code의 지역을 생성하거나 전체 설정 교체 |
| `PUT /api/v1/admin/restaurants/{restaurantId}/map-region` | 식당의 지역 소속 지정·변경·해제 |

목록은 `content`, `page`, `size`, `totalElements`, `totalPages`를 반환한다.
page는 0부터 시작하고 size는 1~100이다. 목록이 비어 있으면 빈 content로 성공한다.

1. 기획에서 승인한 대표 위치·카메라 범위를 받아 비활성 지역을 등록한다.
2. 반환된 `mapRegionId`로 식당의 소속을 지정한다. 공개 준비 전인 비활성 지역도 지정할 수 있다.
3. 서버의 최초 화면·지원 영역 설정과 소속 식당을 확인한 뒤 `active:true`로 전체 설정을 저장한다.
4. 공개 지역 목록과 해당 범위의 식당 조회를 확인한다. API 구현·테스트 성공은 실제 데이터 검수나 배포 완료를 뜻하지 않는다.

아래는 형식 설명용 합성 값이다. 운영 대표 좌표로 사용하지 않는다.

```http
PUT /api/v1/admin/map-regions/TEST_AREA
```

```json
{
  "name": "테스트 관광 지역",
  "clusterPosition": {"latitude": 0.5, "longitude": 0.5},
  "cameraBounds": {"south": 0, "north": 1, "west": 0, "east": 1},
  "displayOrder": 0,
  "active": false
}
```

응답 data는 위 필드에 `mapRegionId`, `code`를 더한 형태다. code는 대문자 영문으로 시작하는
최대 40자의 영문 대문자·숫자·밑줄 조합이며 변경하지 않는 식별자다. 이름은 1~100자이고
공백만인 값은 거부한다. 대표 좌표와 모든 경계는 소수점 6자리까지 저장한다. 위도는 -90~90,
경도는 -180~180이며 남쪽 < 북쪽, 서쪽 < 동쪽이어야 한다. 대표 좌표는 cameraBounds 안에 있어야 한다.
displayOrder는 0 이상이다. 모든 필드가 필수이고 빠진 필드를 기본값으로 덮어쓰지 않는다.

활성화할 때는 cameraBounds가 설정된 지원 영역 안에 있고 가로·세로 각각 1도 이하여야 한다.
공개 지도와 같은 설정 검증을 사용하므로 최초 화면 설정도 준비되어야 한다. 비활성 초안에는
지원 영역·1도 제한을 적용하지 않아 먼저 입력하고 나중에 활성화할 수 있다.

소속 지정은 `{"mapRegionId":123}`, 해제는 `{"mapRegionId":null}`을 보낸다.
필드가 없는 `{}`는 잘못된 요청이다. 응답 data의 `restaurantId`, `mapRegionId`는 해제 시에도
명시적 null을 포함한다. 식당 주소·좌표·주소 revision·변환 작업은 바꾸지 않는다.

## 재전송과 동시 수정

`admin → RestaurantPort → restaurant` 경계를 유지한다. 지역 검증과 저장, 식당 잠금은
restaurant 모듈에서 처리하고 Entity를 응답에 노출하지 않는다.

- 지역 code의 기존 unique 제약과 MySQL upsert를 사용한다. 동시에 처음 생성해도 한 ID만 생긴다.
  재전송·수정은 ID와 기존 소속을 유지하며 요청의 전체 설정을 함께 저장한다.
- 동시 수정은 DB에서 나중에 적용된 요청의 전체 설정이 최종값이다. 필드별로 섞이지 않는다.
  변경 이력·수정 충돌 안내가 필요해지면 version 기반 편집을 별도 검토한다.
- 식당 소속은 기존 `findByIdForUpdate`로 부모 식당을 잠근 뒤 변경한다. 삭제와 경합하더라도
  삭제가 먼저 확정된 식당은 변경하지 않는다. 같은 식당의 동시 지정도 차례대로 적용된다.
- 지역 삭제 API는 없다. 비활성화해도 지역과 식당의 연결은 보존한다.

## 공개 지도에 미치는 영향

cameraBounds는 지역을 눌렀을 때 보여줄 화면이고 자동 소속 판정 경계가 아니다. 밖에 있는
식당도 관리자가 지정할 수 있다. 다만 현재 공개 집계는 **그 지역 소속이면서 cameraBounds 안에
있는 유효 식당**만 센다. 범위 밖 소속의 지역 ID·건수는 기존 조회 로직이 운영 확인용으로 기록한다.
일반 BBOX 조회에는 미분류 식당도 포함된다.

지역 조회에는 활성 지역만 나온다. 모두 비활성이면 현재 공개 계약대로 `503 RESTAURANT-017`이다.
기존 지역 조회 세션에서 소속이 해제된 식당은 다음 페이지 재검사 때 빠진다. 새로 소속된 식당은
이미 만든 후보 목록에 추가되지 않으며 새 조회에서 반영된다. 지역을 비활성화하면 그 지역으로 만든
기존 세션의 다음 페이지도 `400 RESTAURANT-012`다. 프런트는 지역 선택을 해제하고 새 조회할 수 있다.
이미 응답한 화면을 서버가 밀어내거나 Redis 세션을 일괄 삭제하지 않는다.

| 오류 | 의미 |
| --- | --- |
| `400 COMMON-400` | 필수 필드 누락, 잘못된 code·좌표·경계·순서·ID·페이지 |
| `400 RESTAURANT-011` | 활성 지역의 카메라 범위가 지원 영역 밖이거나 1도 초과 |
| `401` / `403` | 인증 필요 / ADMIN 권한 없음 |
| `404 RESTAURANT-004` | 없거나 삭제된 식당 |
| `404 RESTAURANT-023` | 지정할 지역이 없음 |
| `503 RESTAURANT-017` | 활성화에 필요한 지도 설정이 준비되지 않음 |

## 기획 기준과 이번 변경의 경계

2026-10-08 확인한 [PLAN MAP_MAIN](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/1c981e524f262cfdbd18f53c568242a7620cacf9/02_PRODUCT_SPEC/MAP/MAP_MAIN/MAP_MAIN.md)
본문은 검색·필터가 없는 상태에서 축소하면 관광 지역 클러스터를 다시 표시하고, 지역 선택 뒤
필터는 지역 범위를 유지하며, 지도 이동 후 지역 맥락을 해제하도록 정한다. 다른 탭·백그라운드에서
5분이 지나 돌아오면 화면을 초기화한다. 마지막 QA 구간에는 미정리 충돌 표시가 있어 본문을 대조했다.

같은 버전의 [DEC-001](https://github.com/TEAM-HASHI/HASHI-PLAN/blob/1c981e524f262cfdbd18f53c568242a7620cacf9/07_DECISIONS/DEC-001_MAP_INITIAL_CLUSTERING.md)과
[기존 서버 계약](map-contract-v1.md)은 최초 클러스터 중심의 이전 기준을 담고 있다.
이번 API는 지역 데이터 입력을 마련하며 줌 전환·지도 복귀·필터 상태 같은 화면 동작을 바꾸지 않는다.
이 차이는 프런트 연동 시 최신 본문 기준으로 별도 대조해야 한다. 화면 이탈 후 5분 초기화와
서버 조회 세션의 요청 기준 유휴 5분 만료는 시작 시점이 다른 정책이다.

## 검증

`MapRegionAdminIntegrationTest`는 실제 SecurityFilterChain·ADMIN JWT·Controller·Port·Repository와
Testcontainers MySQL 8.4·Redis를 사용한다. 지역·식당은 합성 데이터다. 반복/동시 upsert, null 해제,
익명·USER·ONBOARDING 차단, 잘못된 입력의 기존 데이터 보존, 삭제 잠금 경합, 공개 집계·기존 세션을 확인한다.
운영 Google, 운영 DB·Redis, 실제 지역 등록, 프런트 지도와 배포는 이 검증에 포함하지 않는다.
