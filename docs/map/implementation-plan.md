# 지도 후속 구현과 인수 기준

상태: [#219](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219)의 문서 계획.
M2~M7은 아래의 작업 구분자이며 실제 등록된 GitHub 이슈 번호가 아니다.
계약은 [Map Contract v1](./map-contract-v1.md), 구조 결정은 [ADR 0002](../adr/0002-restaurant-map-query-and-location.md)를 따른다.

## 1. 쪼갠 이슈 계획

| 단위 / 이슈 제목안 | 선행·범위 | 인수 증거·제외 범위 |
|---|---|---|
| M2 `[Feat] 식당 위치와 관광 지역 모델 추가` | M1. restaurant 위치/지역 모델, nullable legacy 전환, revision·좌표 쌍·수명 제약, Port 값 계약 | 실제 MySQL migration·제약·기존 데이터 호환, ModularityTests. Google 호출·기존 주소 일괄 변환 제외 |
| M3 `[Feat] 식당 주소 좌표 변환 작업 추가` | M2. 관리자 원자 저장, durable 작업·lease·CAS, Google adapter, 상태·재처리 API | fake provider와 transaction/경합 검증, 실패 후 재개. 새 dependency·DB·보안 영향 사전 설명. 운영 호출 기본 비활성 |
| M4a `[Feat] 관광 지역과 지도 후보 조회 추가` | M2. BBOX·필터·지역 count, 지도 DTO·map-location, 합성 fixture | 경계·삭제·만료·지역 미분류·bulk query 검증. 완성된 공개 페이지 API로 출시하지 않음 |
| M4b `[Feat] 지도 정렬 세션과 페이지 연결 추가` | M4a. Redis 세션·절대 TTL·cursor·정렬 변경·오류 | 실제 Redis TTL/eviction/장애와 MySQL 재검사, 중복·누락 반례. M4a와 함께 Map Contract의 공개 조회 완성 |
| M5 `[Feat] 기존 식당 좌표 보완과 수명 정리 추가` | M3. 조회 전용 dry-run·checkpoint·제한 실행·만료 전 갱신/제거 | resume·동시 실행·stop·expiry·백업 복구 방어. 유료 호출/운영 backfill 실행은 별도 승인과 결과 보고 |
| M6 `[Feat] 컬렉션 전체 핀과 저장 요약 연동` | M2 Port 및 #216의 저장/권한 기반. user 공개 집계·내 상태·전체 핀 API | 소유권·비공개 접근·버전 변경·전체 반환·실패 원자성, security matcher와 모듈 경계. #216 쓰기 API 중복 구현 금지 |
| M7 `[Docs] 지도 통합 검증과 운영 절차 정리` | M3~M6와 FE/어드민 통합 | 화면 상태/카메라·부분 실패·귀속 표기·성능·quota·운영 gate 증거. 문서 통과를 배포 증거로 대체하지 않음 |

학습 순서는 M2의 좌표 불변식 → M4a의 BBOX → M3의 짧은 transaction과 외부 HTTP →
M4b의 정렬 세션 → M5/M6 통합이다. M3와 M4a는 M2 이후 독립 개발할 수 있으나 공유 Port·
관리자 DTO·SecurityConfig·다음 Flyway 번호는 담당을 정한다. 실제 이슈 등록·작업방 생성·담당자
연락은 이 문서 PR에서 수행하지 않는다. 각 구현은 최신 develop의 전용 worktree에서 시작한다.

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
| MAP-09 / M4b | 생성 15분 뒤 410, 읽기/재정렬은 TTL 연장 없음. eviction도 410, Redis timeout은 503, 새 순서 몰래 연결 금지 |
| MAP-10 / M4b | 세션 bytes/후보 수/동시 세션 admission 초과와 일부 쓰기 실패에서 전체 실패. 조용한 ID 절단 없음. 인증 Redis 영향과 query plan 측정 |
| LOC-01 / M2·M3 | 주소 저장 중 작업 insert 실패면 식당 저장도 rollback. commit 직후 worker 미실행/프로세스 종료여도 PENDING 작업 복구 |
| LOC-02 / M3 | revision 1 결과보다 revision 2가 먼저 완료, 같은 revision 재처리, lease 재claim 후 옛 worker 완료. 오래된 좌표/실패 상태 덮어쓰기 없음 |
| LOC-03 / M3 | HTTP adapter 진입 때 실제 transaction 비활성. 느린 HTTP 동안 식당 lock 미점유. 결과 저장 실패·중복 전달은 안전한 재처리 |
| LOC-04 / M3 | 0건·복수/저정확도·timeout·quota·권한 오류의 상태 분류, 제한 backoff·attempt 소진·관리자 재처리·동시 재처리 1개 작업 |
| LOC-05 / M5·M7 | validUntil 경계에서 서버·FE 노출 중단, DB/복제 캐시 제거, refresh 실패로 수명 연장 없음, 만료 데이터 백업 복원도 공개 차단 |
| COL-01 / M6 | 23개 공개 식당 중 2개 위치 없음. 목록 첫 10개와 독립적으로 핀 21개, visible=23/unavailable=2. 위치 없는 2개 목록 유지 |
| COL-02 / M6 | 같은 사용자의 두 컬렉션에 저장해도 savedCount=1. 하나 제거하면 유지, 마지막 제거면 감소. 타 공개 컬렉션 열람은 내 저장 아님 |
| COL-03 / M6 | 익명 공개 열람 성공, 비공개 타인/익명 404, 소유 USER 성공. ADMIN/ONBOARDING의 내 저장·편집 접근 차단. 쓰기 matcher 비공개 유지 |
| COL-04 / M6 | 내부 두 번째 bulk 실패·응답 전 권한 철회/삭제·소속 version 변경. 부분 200 없음, 404면 FE 기존 핀 제거, 409면 전체 재조회 |
| COL-05 / M6·M7 | 공개 집계/개인 상태 중 하나만 실패해도 false/0으로 대입하지 않음. 0개 핀 성공과 전체 조회 오류 구분. 전체 capacity 초과는 명시적 실패 |
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
| 관광 지역·지원 bounds·대표 화면·식당 매핑 | 합성 fixture와 validation | 실제 지역 count·대표 위치의 정확성 |
| #216 실제 경로·오류 배정, FE 카메라/지역 해제·초기 안내 상태 확인 | 이 문서 기준 mock과 Port 개발 | 팀 통합 합의·화면 인수 완료 |
| Google 프로젝트·청구 지역/계약·키 제한·허용 보존 수명 | fake 호출과 장애 테스트 | 실계정 허가·과금·quota·Google 결과 보관 적합성 |
| Redis 메모리·eviction·세션/전체 admission 예산 | 소규모 fixture·부하 도구 설계 | 처리 용량·응답 시간 보장 |
| 대상 DB 수·주소 품질·backfill 호출/일일 한도·stop/resume·백업 수명 | 조회 전용 dry-run 설계 | 유료 호출·운영 backfill·물리 제거 실행 승인 |

서버 키·운영 DB 정보·사용자 정보는 PR 증거에 넣지 않는다. 배포, 유료 호출, backfill 실행,
운영 검증은 각각 실행 여부와 근거를 별도로 기록한다.
