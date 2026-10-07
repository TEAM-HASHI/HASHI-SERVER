# 관리자 식당 위치 확인 (#224)

## 관리자 계약

기존 등록은 201 `ADMIN-204`, 수정은 200 `ADMIN-205`다. 식당 정보와 위치 작업을 저장하면 성공한다.
응답에 `locationStatus`, `addressRevision`을 추가한다. Google의 위치 확인 성공을 뜻하지 않는다.

- `GET /api/v1/admin/restaurants/{id}/location`: 200 `COMMON-200`.
- `POST /api/v1/admin/restaurants/{id}/location/retry`: body `{"expectedAddressRevision":1}`,
  200 `COMMON-200`. 누락/음수 revision은 400, 현재 revision 불일치/READY는 409 `RESTAURANT-019`.
- 두 경로 모두 기존 SecurityFilterChain의 ADMIN 권한을 사용한다. 삭제/없는 식당은 `RESTAURANT-004`.
- 상태 응답: `restaurantId`, `locationStatus`, `addressRevision`, `validUntil`, `attempt`,
  `nextAttemptAt`, `failureCode`, `canRetry`. 시각은 UTC ISO-8601, Cache-Control은 no-store.
- UNRESOLVED의 revision은 0, attempt는 0이다. PENDING 재처리는 기존 작업을 반환한다.
  READY는 갱신 API 범위이며 일반 재처리로 받지 않는다. 좌표/주소/provider 원문/lease는 반환하지 않는다.

## 자동 채택 범위

v1은 **일본어로 행정구역과 동·번지가 완전하게 입력된 도쿄 주소**를 자동 채택한다.
합성 주소 fixture는 `東京都試験区架空町1丁目2番3号`와 같은 모양이며 실제 식당 주소가 아니다.

1. 후보가 정확히 하나, countryCode와 country component가 JP, administrativeArea와
   administrative_area_level_1이 東京都여야 한다. 별도 설정된 지원 BBOX에도 원본 좌표가 포함돼야 한다.
2. ROOFTOP과 street_address/premise 유형을 모두 요구한다.
3. administrative_area_level_1 → locality → sublocality_level_1~4 → route → street_number 순서의
   구성 요소를 합친다. 필수 행정구역 누락, 같은 유형 중복, 알 수 없는 구성 요소는 확인 필요다.
4. NFKC 전각 숫자, 공백, 명시된 하이픈, 숫자 뒤 丁目/番/番地/号만 정규화한다. 결과는 행정구역 뒤
   세 숫자(정/번/호)인 완전 주소여야 하며 원래 주소와 **전체가 정확히 일치**해야 한다.
   선택적인 日本 접두사와 응답 postal_code와 정확히 같은 〒 접두사만 허용한다.
5. 일본어↔로마자/한국어 번역, 편집 거리/유사도, 부분 포함 비교, 첫 후보 선택은 하지 않는다.
   건물명/층수나 다른 표기가 남아 확신할 수 없으면 REVIEW_REQUIRED다. 관광 지역 ID는 추론하지 않는다.
6. 원본 위도/경도 범위를 먼저 검사한 후 HALF_UP으로 소수점 6자리까지 반올림한다.

이는 [Google v4 결과 필드](https://developers.google.com/maps/documentation/geocoding/reference/rest/v4/GeocodeResult)를
이용한 Hashi의 채택 정책이다. [v4 이전 문서](https://developers.google.com/maps/documentation/geocoding/geocoding-v4-migrate)의
partial_match 제거와 국가/지역 입력의 편향 의미를 반영한다. ROOFTOP만으로 주소 일치를 보장하지 않는다.
지원하지 않는 표기를 실제 운영에서 자동화하려면 대표 주소로 별도 정책/회귀 테스트를 확장한다.

## 호출과 재시도 제한

| 항목 | 값/의미 |
|---|---|
| worker enabled | 기본 false (`hashi.map.location-job.enabled`) |
| Google enabled | 기본 false, 선행 adapter 설정을 별도로 사용 |
| lease | 2분; adapter의 최대 30초 deadline보다 길다 |
| polling | 기본 5초 (`hashi.map.location-job.poll-delay`, ms), cycle 후보 최대 50 |
| 실행 스레드 | 전용 단일 scheduler, cycle 중첩 없음; 기존 예약 작업의 scheduler 유지 |
| max-attempts | 기본 8(최초 요청 포함), 허용 1~8, 같은 작업 행에 누적 |
| 재시도 간격 | 1분 → 5분 → 30분 → 2시간 → 6시간 → 12시간 → 24시간, 각 간격에 0~10% 무작위 지연 |
| Google quota | 같은 재시도 간격을 DB blockedUntil에 기록해 전체 서버 대기; 마지막 실패도 24시간 + jitter 적용 |
| 일일 예산 | DB daily_limit, 초기 0; UTC 날짜가 앞으로 바뀔 때만 갱신 |
| 전역 동시 처리 | DB max_concurrent, 초기 0, 허용 0~4 |
| 중단 | application enabled 또는 DB enabled=false; 새 claim 중단 |
| retention | 활성 시 명시 필수, 5분~30일. 실제 계약보다 길게 설정하면 안 됨 |
| south/north/west/east | 활성 시 명시 필수. 운영 값 기본 제공 없음 |

| 결과 | 관리자 상태/안전한 코드 |
|---|---|
| 일치하는 완전 주소 | READY, failureCode null |
| 결과 없음/복수/다른 국가/영역/정확도/주소 불일치 | REVIEW_REQUIRED, NO_RESULTS / AMBIGUOUS_RESULTS / COUNTRY_MISMATCH / OUTSIDE_SUPPORTED_AREA / INSUFFICIENT_PRECISION / ADDRESS_MISMATCH |
| timeout/연결/5xx | RETRY_WAIT; TIMEOUT / CONNECTION_ERROR / TRANSIENT_ERROR |
| Google 429 | RETRY_WAIT / QUOTA_EXCEEDED, 공유 대기; 마지막 시도도 공유 대기를 기록하고 FAILED / ATTEMPTS_EXHAUSTED |
| 로컬 실행 슬롯 부족 | RETRY_WAIT / CAPACITY_EXCEEDED, Google quota와 구별 |
| 취소 | 한도 내 RETRY_WAIT / CANCELLED, 마지막 시도는 FAILED / ATTEMPTS_EXHAUSTED; 중단된 thread는 lease 만료 후 복구 |
| 비활성 provider/키권한/설정/잘못된 요청·응답 | FAILED / adapter의 안전한 FailureKind |
| 최대 시도 소진 | FAILED / ATTEMPTS_EXHAUSTED |
| 오래된 revision/request/job/lease 또는 삭제 | 성공/실패 모두 no-op |

취소/timeout의 아직 남아 있을 수 있는 실행 슬롯은 lease 기한까지 보존한다.
일일 예약량은 전송 여부를 임의로 추측해 환급하지 않는다. worker만 재시도를 소유하고 adapter는 재시도하지 않는다.
관리자 재처리의 attempt는 새 이력에서 0으로 시작하나 전역 예산은 그대로 적용한다.

## 운영 활성화 전에 확인할 사항

실제 Google 호출·운영 DB·운영 계정·backfill은 이 구현 테스트에 사용하지 않는다.
worker를 켰으나 지원 범위/보관 기간이 없거나 유효하지 않으면 식당 저장은 유지하고
작업을 CONFIGURATION_ERROR/FAILED로 기록한다. provider 호출/예산 예약은 하지 않는다.
실제 프로젝트/키 제한, 청구 지역/계약, quota·비용 예산, 대표 주소와 지원 BBOX를 확인해야 한다.
[서비스 조항 §6.3](https://cloud.google.com/maps-platform/terms/maps-service-terms)의 기본 보관 상한을 넘기지 않으며,
공용 식당 DB에 최종 사용자별 격리 예외가 적용된다고 가정하지 않는다.
좌표 수명만 저장했다고 실제 제거/백업 정책까지 완료된 것은 아니다.
만료 전 갱신·DB/캐시/클라이언트·백업의 제거, attribution과 정책 QA가 확인돼야 운영 gate를 통과한다.

승인된 운영 변경은 singleton 예산 행만 수정한다. 시작 시 자동 초기화/자동 enable은 없다.
제어 행 누락 시 호출을 차단하므로 누락 원인을 확인한 뒤 중단·한도 0 상태로 복원해야 한다.
문서/로그에 키·원문 주소·좌표·Google 응답·전체 URI를 남기지 않는다.
처리 이력에는 ID/revision/requestId/lease/시도/안전한 코드와 시각만 남는다.

## 이번 변경 전후

- 기존에는 갱신 시작 시 좌표를 비웠다. 이제 같은 주소의 갱신 중에는 이전 좌표를 원래 만료 시각까지 사용한다.
  새 결과가 검증되면 교체하고, 주소가 바뀌면 즉시 제거한다. 재시도나 실패만으로 만료 시각을 늘리지 않는다.
- 자동 시도 기본값을 4회에서 최초 요청 포함 8회로 바꿨다. 일정은 위 표를 따르며 오류가 영구적이면 즉시 중단한다.
- 마지막 취소 응답도 재시도 대기에 남기지 않고 실패로 끝낸다. 전송 중인 요청의 실행 예약은 lease까지 남겨 중복 호출을 막는다.
- 정기 갱신 스케줄과 Grafana 알림은 #235에서 연결한다. 위치 V32 다음에 작업 V33을 적용한다. 이미 병합된 매거진 V31과 다른 담당자의 PR은 수정하지 않는다.
- 전역 호출 중단·일일 한도 소진·공유 대기·실행 슬롯 포화는 후보 조회 전에 읽기 전용으로 확인한다. 실제 예약 시에는 잠금 안에서 다시 검사한다.
  다만 이미 시도를 소진한 만료 작업과 설정 오류는 Google 호출 없이 실패로 정리한다. 호출 대기 작업이 많아도 소진 작업이 밀리지 않도록 별도로 조회한다.
