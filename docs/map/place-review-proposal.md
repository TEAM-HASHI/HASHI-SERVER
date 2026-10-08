# 시설 내부 식당의 장소 확인 제안

상태: 후속 설계안. 아래 후보 검색·확정 API와 Places 자동 갱신은 아직 구현하지 않았다.

## 필요한 이유

Geocoding은 주소의 위치를 찾는다. 역이나 쇼핑몰에서는 같은 주소에 여러 매장이 있어,
주소 조회가 성공해도 시설 대표 위치와 실제 매장 위치가 다를 수 있다.
2026-10-09 개발 프로젝트 실호출에서 도쿄역 이카루가는 약 208m 차이가 났다.
키와미야 도쿄역점은 약 11m, 마구로마트는 약 4m로 차이가 작았다.
따라서 모든 정상 식당을 Places로 다시 조회하기보다 확인이 필요한 식당에 집중한다.

## 권장 사용자 흐름

1. 관리자가 위치 검토 목록이나 식당 수정 화면에서 대상 식당을 연다.
2. 서버가 식당명·현지명·지점명과 위치 확인용 주소로 후보를 찾는다.
3. 관리자가 매장명·주소·지도 위치를 비교해 같은 지점인지 확인한다.
4. 확정 요청에는 서버가 발급하거나 검증한 장소 ID와 현재 주소 변경 번호만 보낸다. 클라이언트 좌표는 받지 않는다.
5. 서버가 Place Details를 다시 조회하고 검증한 뒤 좌표를 저장한다.

자동 Geocoding 결과가 `READY`여도 시설 내부 매장은 검토할 수 있어야 한다.
서로 다른 주소가 함께 적혔거나 같은 이름의 지점이 여러 개면 첫 후보를 자동 확정하지 않는다.
관리자가 이전 핀이 잘못됐다고 확인해 교체를 요청하는 경우에는 기존 핀을 숨기는 쪽을 권한다.
이후 같은 장소의 정기 갱신에서는 아직 유효한 좌표를 유지한다.

## API와 저장 범위

| 제안 API | 역할 |
|---|---|
| `POST /api/v1/admin/restaurants/{id}/location/place-candidates` | 관리자 전용 소량 후보 검색. 호출 비용이 발생하므로 횟수·동시 실행을 제한 |
| `POST /api/v1/admin/restaurants/{id}/location/place-selection` | `expectedAddressRevision`, `placeId`로 선택 요청. 새 작업 ID로 검증하며 이전 주소의 결과를 차단 |

필요한 DB 변경은 장소 ID 보관과 `GOOGLE_PLACES` 출처 구분이다.
후보 이름·주소·응답 원문을 식당 데이터로 복제해 쌓지 않는다. 기존 식당의 표시 주소는 유지한다.
Places 좌표를 관리자가 확인했다고 `ADMIN` 자체 좌표로 바꿔 저장하지 않는다.

기존 작업 처리·재시도 구조를 재사용하되 다음 변경을 함께 해야 한다.

- Places 좌표는 저장한 place ID로 Details를 재조회한다. 주소 Geocoding으로 덮어쓰지 않는다.
- 실제 위치 확인 주소가 바뀌면 이전 장소 ID와 좌표를 해제한다.
- 만료 정리 대상에 Places 출처를 포함하며 실패했다고 유효기간만 늘리지 않는다.
- 검색과 상세 조회의 호출 예산을 구분한다. 현재 Geocoding 예산에 조용히 섞지 않는다.
- 후보 선택 후 같은 주소에서 두 관리자가 다르게 확정하는 경우에도 새 요청 ID와 잠금으로 이전 결과를 차단한다.

## 구현 전에 기획·운영과 맞출 항목

- 이번 관리자 화면에 후보 검색·선택까지 포함할지, 기존 위치 상태·주소 보완 화면을 먼저 연결할지
- 후보 화면의 매장명·주소·지도·Google 표시와 선택 확인 동작
- 장소 ID와 좌표의 보관 범위, 삭제·백업 관리, 검색·상세 호출 예산
- 주소 수준의 위치와 매장 위치 중 서비스 공개 시 요구하는 정확도

권장안은 시설 내부 매장을 공개 전에 따로 점검하고, 확인된 Places 장소를 사용하는 것이다.
현재 개발 프로젝트의 Places SearchText/GetPlace 호출은 각각 일 100회·분 10회로 준비했고 실호출도 확인했다.
이 설정 확인을 제품 저장·갱신 흐름 검증 완료로 취급하지 않는다.

공식 참고:
- [Text Search](https://developers.google.com/maps/documentation/places/web-service/text-search)
- [Place Details](https://developers.google.com/maps/documentation/places/web-service/place-details)
- [Places 표시·보관 정책](https://developers.google.com/maps/documentation/places/web-service/policies)
- [서비스별 약관](https://cloud.google.com/maps-platform/terms/maps-service-terms)

Places 정책은 place ID의 장기 보관 예외를 명시한다. 좌표와 다른 응답 데이터까지 같은 예외라고 간주하지 않는다.
