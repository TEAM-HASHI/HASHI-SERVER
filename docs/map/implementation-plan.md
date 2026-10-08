# 지도 후속 구현과 인수 기준

상태: [#219](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)의 문서 계획을 2026-10-01 PLAN `6f2c99c`와 develop `053fdb3`에 대조했다.
2026-10-06에는 develop `f6908ba`의 문서를 #225에 반영하고, 아래 좌표 보존·갱신·조회 정책을 보완했다.
이번 코드 변경은 #225의 좌표 모델·DB 제약에 한정한다. Redis·재시도·알림·부하 테스트는 후속 구현 기준이다.
M2~M7은 아래의 작업 구분자다. 대응 이슈/PR은 별도로 추적하며, Draft PR이나 테스트 성공을 병합·배포로 취급하지 않는다.
계약은 [Map Contract v1](./map-contract-v1.md), 구조 결정은 [ADR 0002](../adr/0002-restaurant-map-query-and-location.md)를 따른다.

## 1. 기존 이슈와 PR의 작업 범위

| 단위 / 이슈 제목안 | 선행·범위 | 인수 증거·제외 범위 |
|---|---|---|
| M2 `[Feat] 식당 위치와 관광 지역 모델 추가` | M1. restaurant 위치/지역 모델, nullable legacy 전환, revision·좌표 쌍·수명 제약, Port 값 계약 | 실제 MySQL migration·제약·기존 데이터 호환, ModularityTests. Google 호출·기존 주소 일괄 변환 제외 |
| M3 `[Feat] 식당 주소 좌표 변환 작업 추가` | M2. 관리자 원자 저장, durable 작업·lease·CAS, Google adapter, 상태·재처리 API | fake provider와 transaction/경합 검증, 실패 후 재개. 새 dependency·DB·보안 영향 사전 설명. 유료 호출 전 실제 청구 지역·계약과 FE 지도 제공자/사용 필드의 허용 조합 확인. 운영 호출 기본 비활성 |
| M4a `[Feat] 관광 지역과 지도 후보 조회 추가` | M2. BBOX·필터·지역 count, 지도 DTO·map-location, 합성 fixture | 경계·삭제·만료·지역 미분류·bulk query 검증. 완성된 공개 페이지 API로 출시하지 않음 |
| M4b `[Feat] 지도 정렬 세션과 페이지 연결 추가` | M4a. Redis 세션·유휴/최대 수명·cursor·정렬 변경·오류 | 실제 Redis TTL/eviction/장애와 MySQL 재검사, 중복·누락 반례. M4a와 함께 Map Contract의 공개 조회 완성 |
| 후속 `#251 관리자 관광 지역 설정과 식당 지역 지정` | M2·M4a. 지역 목록/설정 API와 식당 소속 지정·해제 | ADMIN 권한, 중복·동시 수정, 관리자 등록부터 공개 집계까지 합성 데이터 검증. 실제 대표 좌표·식당 소속은 기획 승인 후 입력 |
| M5 `[Feat] 기존 식당 좌표 보완과 수명 정리 추가` | M3. 조회 전용 dry-run·checkpoint·제한 실행·만료 전 갱신/제거 | resume·동시 실행·stop·expiry·백업 복구 방어. 유료 호출/운영 backfill 실행은 별도 승인과 결과 보고 |
| M6 `#242 컬렉션 전체 핀과 저장 요약 연동` | M2 Port 및 develop에 병합된 #216의 저장/권한 기반. user 공개 집계·내 상태·전체 핀 API | 소유권·비공개 접근·버전 변경·전체 반환·실패 원자성, security matcher와 모듈 경계. #216 쓰기 API 중복 구현 금지 |
| M7 `[Docs] 지도 통합 검증과 운영 절차 정리` | M3~M6와 FE/어드민 통합 | 화면 상태/카메라·부분 실패·귀속 표기·성능·quota·운영 gate 증거. 실제 청구 지역·적용 계약과 FE 지도 제공자/표시 필드의 허용 조합을 인수 기록으로 확인. 문서 통과를 배포 증거로 대체하지 않음 |

학습 순서는 M2의 좌표 불변식 → M4a의 BBOX → M3의 짧은 transaction과 외부 HTTP →
M4b의 정렬 세션 → M5/M6 통합이다. M3와 M4a는 M2 이후 독립 개발할 수 있으나 공유 Port·
관리자 DTO·SecurityConfig·다음 Flyway 번호는 담당을 정한다.
2026-10-08 develop `e780c4c`의 V37까지 적용한 다음
V38(위치) → V39(작업) → V39.1(선택 위치 확인 주소) → V40(유지보수) →
V41(컬렉션 변경번호)를 적용한다.
병합된 V32 사용자 익명 닉네임, 공지 V36·V36.1, 약관 V37은 수정하지 않는다.
지도·공지·약관의 기존 파일이 개발·운영 DB에 적용되지 않았음을 확인한 뒤 파일명과
테스트·문서만 조정한다. SQL 본문은 바꾸지 않는다.

이미 병합된 파일과 Flyway 이력은 수정하지 않고 `outOfOrder`나 checksum repair로
순서를 우회하지 않는다. 다른 담당자의 PR #249·#250은 수정하지 않는다.
최종 조합에서 버전 중복, 빈 DB 적용, develop V31에서의 기존 데이터 보존을 검증하고,
실제 병합 전 최신 develop에 새 migration이 추가됐는지 다시 확인한다.

## 2. 테스트 인수 시나리오

다음은 후속 테스트의 **완료 조건**이며 현재 통과 결과가 아니다. 새 문서의 문장을 그대로 검사하는
production 테스트를 추가하지 않는다.

| ID / 대상 | 반례와 기대 결과 |
|---|---|
| MAP-01 / M4a·M7 | 초기 클러스터와 첫 10개 목록 공존, 목록과 같은 핀 데이터 확보. 개별 탐색 전환 후 이동·줌·상세 복귀에 추가 클러스터/자동 HTTP 없음 |
| MAP-02 / M4a·M7 | 긴자 선택→다른 지역 이동→같은 cafe chip 재선택. mapRegionId 해제, 새 BBOX·cafe 유지. 전체 chip은 placeType도 해제 |
| MAP-03 / M4a | 경계 위 점 포함; NaN·Infinity·범위 역전·동경/서경 횡단·1도 초과·지원영역 밖은 400. 없는 지역·빈 keyword·잘못된 enum 검증 |
| MAP-04 / M4a | 같은 지역 cameraBounds의 count와 후보 조건 일치. area 문자열만 같거나 미분류인 식당을 잘못 포함하지 않음 |
| MAP-05 / M4b | 23개 후보에서 10/10/3, ID 중복 없음, 마지막 nextCursor 생략. 각 페이지 핀과 content ID 동일 |
| MAP-06 / M4b | 모두 동점인 fixture에서 recommend↔rating↔reviews 전환 시 추천 순서 유지. 같은 querySessionId·queryBounds, 새 정렬 첫 페이지로 교체 |
| MAP-07 / M4b | 2페이지 전 평점/리뷰 변경·신규 식당·삭제·좌표 만료/주소 변경. 순위 수치 고정, 현재 무효 ID 제외, 이후 후보로 채움, cursor 소비 위치 확인 |
| MAP-08 / M4b | cursor 변조/다른 API 토큰/모드 혼합은 400. 같은 cursor 재시도·응답 역전·동시 정렬에 중복 append나 이전 세대 화면 교체 없음 |
| MAP-09 / M4b | 유휴 5분 뒤 410, 정상 다음 페이지/재정렬은 현재부터 5분 유지하되 최초 생성 30분 상한. 동시 갱신·만료 직후 요청으로 부활/기한 역행 없음. 잘못된 요청/실패는 연장 없음. eviction 410, Redis timeout 503, 응답 expiresAt과 실제 수명 일치 |
| MAP-10 / M4b | 후보 500·501·620개를 끝까지 조회해 중복·누락 없음. 작은 묶음의 11번째 후보가 다음 페이지에 남음. 실제 용량 초과·부분 쓰기 실패는 전체 실패, 조용한 ID 절단 없음. 호출자별 제한·인증 Redis 영향·query plan 측정 |
| LOC-01 / M2·M3 | 주소 저장 중 작업 insert 실패면 식당 저장도 rollback. commit 직후 worker 미실행/프로세스 종료여도 PENDING 작업 복구 |
| LOC-02 / M3 | revision 1 결과보다 revision 2가 먼저 완료, 같은 revision 재처리, lease 재claim 후 옛 worker 완료. 오래된 좌표/실패 상태 덮어쓰기 없음 |
| LOC-03 / M3 | HTTP adapter 진입 때 실제 transaction 비활성. 느린 HTTP 동안 식당 lock 미점유. 결과 저장 실패·중복 전달은 안전한 재처리 |
| LOC-04 / M3 | 0건·복수/저정확도·timeout·quota·권한 오류의 상태 분류, 제한 backoff·attempt 소진·관리자 재처리·동시 재처리 1개 작업 |
| LOC-05 / M2·M5·M7 | 같은 주소 갱신·재시도·실패 중 유효 좌표 유지. 주소 변경 시 즉시 제거, 늦은 결과 거절. validUntil 경계에서 서버·FE 노출 중단, 작업 상태와 무관하게 DB/복제 캐시 제거, 새 결과/진행 작업 보호. 실패로 수명 연장 없음, 만료 데이터 백업 복원도 공개 차단 |
| COL-01 / M6 | 23개 공개 식당 중 2개 위치 없음. 목록 첫 10개와 독립적으로 핀 21개, visible=23/unavailable=2. 위치 없는 2개 목록 유지 |
| COL-02 / M6 | 같은 사용자의 두 컬렉션에 저장해도 saveCount=1. 하나 제거하면 유지, 마지막 제거면 감소. 타 공개 컬렉션 열람은 내 저장 아님 |
| COL-03 / M6 | 익명 공개 열람 성공, 비공개 타인/익명 404, 소유 USER 성공. ADMIN/ONBOARDING의 내 저장·편집 접근 차단. 쓰기 matcher 비공개 유지 |
| COL-04 / M6 | 내부 두 번째 bulk 실패·응답 전 권한 철회/삭제·소속 version 변경. 부분 200 없음, 404면 FE 기존 핀 제거, 409면 전체 재조회 |
| COL-05 / M6·M7 | 공개 집계/개인 상태 중 하나만 실패해도 false/0으로 대입하지 않음. 0개 핀 성공과 전체 조회 오류 구분. 전체 capacity 초과는 명시적 실패 |
| COL-06 / M6 | #216 쓰기의 공백 제거를 #242에서 수정. 생성·수정 이름/설명 앞뒤 공백 보존, 공백만인 이름 거부, 기존 이름 중복 판정 유지 |
| API-01 / M4·M6 | SuccessResponse/ErrorResponse·HTTP/code·errors·nextCursor 생략·UTC data 시각·no-store 직렬화와 실제 SecurityFilterChain 검증 |
| API-02 / M2~M6 | RestaurantPort 밖 도메인 내부 참조/순환 없음. 지도 페이지·collection bulk의 ID 수 증가에 item별 DB/media 호출이 늘지 않음 |

## 3. 검증 명령과 증거 기록

구현 PR은 존재하는 테스트 이름을 확인한 뒤 관련 범위를 실행한다. 예시 이름을 실제 테스트 통과로
기록하지 않는다. MySQL 제약·동시성은 Testcontainers MySQL, Redis 수명은 실제 Redis 컨테이너,
Google은 fake adapter로 검증한다. M1은 문서 링크·JSON 예시·diff·요구사항 대조와 독립 리뷰가 대상이다.

- 문서: `git diff --check`, 변경 경로가 docs인지 확인, 상대 링크·JSON 예시 파싱.
- 구조 변경 시: `./gradlew test --tests 'org.sopt.hashi.ModularityTests' --no-daemon`.
- 최종 CI: 기존 `.github/workflows/ci.yml`의 PR 컨벤션, Flyway 버전 중복 검사,
  Docker 확인, `./gradlew clean build --no-daemon`, test report의 failures/errors/skips=0 검증.
  문서 PR도 CI를 임의로 우회하지 않는다. 별도 Markdown lint task는 현재 build에 없다.
- 리뷰: candidate HEAD 고정 → diff/관련 검증 → 독립 읽기 전용 리뷰 → evidence별
  accept/decline/defer → 수용사항 일괄 수정 → 필요한 검증. 최종 HEAD/CI와 미실행 항목을 보고한다.

M1은 문서 낮은 위험 리뷰 1명이다. 후속 DB·transaction·동시성·인증·외부 provider 변경은
사용자/프로젝트의 해당 위험 단계 리뷰를 적용한다. 이 문서 PR의 최종 GO/NO-GO는 별도
지도 조정 작업의 결정자가 수행하며, draft PR 생성/CI 성공만으로 merge를 허가하지 않는다.

## 4. 출시 전 남은 입력

| 입력/검증 | 없을 때 가능한 일 | 운영 완료로 주장할 수 없는 것 |
|---|---|---|
| 관광 지역·지원 bounds·대표 화면·식당 매핑 및 등록 경로 | [#251 관리자 API](admin-regions.md)와 합성 데이터 검증. 승인된 실제 값은 별도 입력 | 실제 지역 count·대표 위치의 정확성, 클러스터 화면 완료 |
| #242 지도 요약·전체 핀 경로 확정, FE 카메라/지역 해제·초기 안내 상태 확인 | #216 병합 API를 기준으로 M6 Port·mock 개발 | 팀 통합 합의·화면 인수 완료 |
| Google 프로젝트·청구 지역/계약·FE 지도 제공자와 표시 필드의 허용 조합·키 제한·허용 보존 수명 | fake 호출과 장애 테스트, [계약 §8](./map-contract-v1.md#8-google-출처보존과-운영-전환)의 조합 검토 | 실계정 허가·과금·quota·지도 표시 및 Google 결과 보관 적합성 |
| Redis 메모리·eviction·세션/전체 admission 예산 | 소규모 fixture·부하 도구 설계 | 처리 용량·응답 시간 보장 |
| 대상 DB 수·주소 품질·backfill 호출/일일 한도·stop/resume·백업 수명 | 조회 전용 dry-run 설계 | 유료 호출·운영 backfill·물리 제거 실행 승인 |

서버 키·운영 DB 정보·사용자 정보는 PR 증거에 넣지 않는다. 배포, 유료 호출, backfill 실행,
운영 검증은 각각 실행 여부와 근거를 별도로 기록한다.

## 5. 2026-10-06 합의한 수정 방향

관련 문서와 코드는 기존 PR에 함께 반영한다. 문서만을 위한 새 PR을 만들지 않는다.
#225는 아래 정책 전체의 구현 완료가 아니라 좌표 보존 모델의 첫 변경이다.

### 좌표 취득·갱신·실패 처리

- 기존 Spring Boot·MySQL 작업 큐를 유지한다. 새 SQS·Kafka나 별도 알림 서비스를 추가하지 않는다.
  DB transaction 밖 Google 호출, 짧은 claim/완료 transaction, revision·request·lease 검사를 유지한다.
- 같은 주소의 갱신은 기존 좌표·source·obtainedAt·validUntil을 유지한다. 새 결과가 검증을 통과하면
  한 번에 교체한다. 주소 변경은 즉시 이전 좌표를 지우고 revision을 올린다.
- `validUntil`은 적용 계약상 보관·사용 기한이고, `nextAttemptAt`은 다음 작업 시각이다.
  첫 갱신 대상 시각은 `validUntil - 3일`로 계산한다. 이를 위한 별도 컬럼은 우선 추가하지 않는다.
  정리 지연까지 감안해 기한 안에 제거되도록 작업 간격·처리량을 검증한다.
- 갱신 최초 시도 후 재시도 간격은 **1분 → 5분 → 30분 → 2시간 → 6시간 → 12시간 → 24시간**이다.
  작은 무작위 지연(jitter)을 더하고 최초 요청 포함 최대 8회로 제한한다. timeout·일시 장애 등
  재시도 가능한 실패에만 적용하며 quota는 전체 호출 제어와 함께 처리한다. 인증·권한·설정 오류는
  즉시 중단·알림, 결과 없음·모호한 후보는 운영 확인으로 보낸다.
- 재시도 횟수는 DB 작업에 남겨 서버 재시작 후에도 이어간다. 스케줄러가 같은 실패 작업을 계속
  새 작업으로 바꾸어 시도 횟수를 초기화하면 안 된다. 신규 등록의 재시도 정책은 #233에서 별도 검증하며
  이 갱신 정책을 문서 수정만으로 적용했다고 보지 않는다.
- 작업 상태가 PENDING/RETRY_WAIT/REVIEW_REQUIRED/FAILED여도 기존 좌표가 유효하면 사용할 수 있다.
  만료되면 사용을 중단하고 제거한다. 재시도는 이어갈 수 있지만 실패했다고 옛 validUntil을 미루지 않는다.
  제거는 진행 중 요청을 취소하거나 직전에 저장된 새 결과를 지워서는 안 된다.
- 자체 확보 좌표와 Google 결과의 출처·보관 조건을 구분한다. Google 결과를 관리자가 확인했다고
  자체 좌표로 바꾸지 않는다. 현재 #225 모델은 ADMIN에도 validUntil을 요구한다. 자체 좌표의 별도 수명
  정책 적용에는 모델·CHECK·조회·DTO 변경이 필요하며 이번 구현 범위에 포함하지 않는다.

### 운영자가 알아야 할 실패

기존 Prometheus·Loki·Grafana·Discord 경로를 사용한다. 환경·실패 원인별로 묶고 동일 장애의 식당마다
알림을 보내지 않는다. 지표에는 낮은 종류 수의 상태/원인만 쓰고 식당·작업 ID는 안전한 로그에 남긴다.
Google 원문·주소·좌표·키·요청 URL은 로그에 남기지 않는다.

| 조건 | 초기 알림 기준 |
|---|---|
| 인증·권한·설정 오류 | 첫 발생부터 알림 |
| 3회 이상 실패했고 15분 이상 미해결 | 경고 |
| 최대 시도 횟수 소진 또는 만료 24시간 이내 미갱신 | 우선 대응 알림 |
| 실행할 작업이 있는데 진행이 장시간 멈춤 | 작업 큐·스케줄러 점검 알림 |

중단 감지 시간·그룹별 반복 간격은 실제 poll/lease/처리 시간에 맞춰 #235에서 설정한다.
알림 경로가 있다는 사실과 새 규칙의 배포·수신 검증을 구분해서 보고한다.

### Redis와 페이지 조회

- 초기 구현은 조회 ID별 키 하나와 TTL을 사용한다. 키에 식당 ID·추천 순서·정렬 기준값만 담고
  Google 좌표·이미지·개인 저장 상태는 복제하지 않는다. 후보를 여러 키로 나누거나 정렬별 자료구조를
  따로 두는 방식은 실제 크기·정렬·전송 비용에서 병목이 확인될 때 검토한다.
- 초기 유휴 시간은 **5분**, 최대 수명은 **30분**이다. 정상 다음 페이지 조회·정렬 변경 때
  `min(현재 시각 + 유휴 시간, 최초 생성 + 최대 수명)`으로 갱신한다. 기존 만료 시각에 시간을 더하지
  않는다. 수치는 설정으로 두고 사용 간격·만료 비율·메모리 측정에 따라 우선 5~10분 안에서 조정한다.
- 실패·잘못된 cursor는 연장하지 않고 자동 keepalive도 넣지 않는다. 갱신 도중 이미 만료된 키는
  되살리지 않는다. 동시 요청에서 만료 시각이 뒤로 줄거나 최대 수명을 넘지 않도록 원자적으로 처리한다.
  Redis TTL, 저장된 수명 검사, 응답 expiresAt을 맞춘다.
- 410이면 기존 목록과 아직 유효한 핀을 유지하고 새로 불러오기를 제공한다. 마지막 조회 범위·필터·
  현재 정렬로 새 조회하며 성공 후 첫 페이지로 교체한다. 503은 같은 요청 재시도, 호출자별 제한 429는
  대기 후 재시도로 구분한다. Google 좌표 만료는 조회 세션 만료와 별개다.
- #234에서 고정 슬롯을 제거하고 조회 ID별 키·슬라이딩 TTL·호출 제한·메모리 예산을 적용했다.
  [#252](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/252)의 운영 예산 확정은 격리된 부하 검증과 함께 확인한다. 정상 조건의 전체 조회를 보장하면서 실제 과부하는 오류로
  알리고 결과를 조용히 잘라내지 않는다. 제한값은 측정 없이 숫자만 키우지 않는다.
- 인증과 Redis를 공유하면 인증 키의 eviction·쓰기 실패가 발생하지 않도록 여유와 정책을 검증한다.
  prefix나 논리 DB 구분은 메모리 격리가 아니다. 보호를 입증하기 어렵다면 인스턴스 분리를 검토한다.
- 각 페이지에서 남은 후보 전체를 DB 조회하지 않는다. 작은 묶음으로 유효한 10개+다음 1개를 찾으면
  멈춘다. 탈락한 후보는 건너뛰고 11번째 유효 식당은 소비하지 않는다. DB 조회 개선과 별개로 Redis
  전체 읽기·역직렬화·정렬 비용도 측정한다.

### 구현 순서와 검증

1. [#225](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/225): 이 문서·계약 갱신과 좌표 상태/DB 제약 수정.
   [모델 문서](location-model.md)의 후속 SQL·정리 호환 조건까지 리뷰한다.
2. [#226](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/226): 공식 Google 프로젝트·계약·제한된 개발 키를
   준비하고 소량 실제 호출을 확인한다. 이번 #225 작업은 실제 호출을 하지 않는다.
3. [#233](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/233)·[#235](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/235):
   worker·갱신·정리·알림을 연결하고 재시도/주소 변경/만료/복구를 검증한다.
4. [#229](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/229)·[#234](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/234)·#252:
   좌표 조회 조건, 작은 묶음 조회와 Redis 수명·용량 처리를 함께 반영한다.
5. [#244](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/244)·[#236](https://github.com/TEAM-HASHI/HASHI-SERVER/pull/236):
   컬렉션 전체 유효 핀과 일반 지도 페이지를 연결하고 전체 흐름을 검증한다.

통합 테스트로 500·501·620개 전체 조회, 동점 순서, 삭제·만료·같은 cursor 재시도를 확인한다.
k6는 별도의 합성 데이터 환경에서 새 조회/다음 페이지/정렬/필터 변경을 구분해 응답 시간·오류율·처리량을
측정한다. Google을 부하 테스트에 호출하지 않는다. TTL보다 긴 지속 부하와 부하 감소 후 복구에서
세션 정리, Redis 메모리와 DB 연결이 안정되는지도 확인한다. 합격 기준을 먼저 정하고 실측 결과를 기록한다.
이 계획에는 아직 k6 측정 결과나 공개 활성화 승인이 없다. 최종 코드의 독립 리뷰·팀 리뷰 후 병합을 판단한다.
