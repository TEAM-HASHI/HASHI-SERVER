# ADR 0002: 식당 지도 조회와 위치 처리의 경계

- 상태: #219에서 채택한 후속 구현 기준. 이 문서의 병합은 기능 구현·운영 활성화를 뜻하지 않는다.
- 결정일: 2026-09-26
- 관련 이슈: [#219](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/219), [#216](https://github.com/TEAM-HASHI/HASHI-SERVER/issues/216)
- 외부 계약: [Map Contract v1](../map/map-contract-v1.md)
- 구현·인수 계획: [Implementation Plan](../map/implementation-plan.md)

## 1. 배경

지도는 관광 지역과 첫 추천 목록을 안내하고, 최초 또는 명시적으로 조회한 범위의 식당을
10개씩 제공한다. 추천 무작위 순서와 별점·리뷰 동점 순서를 페이지 사이에 유지해야 한다.
선택 컬렉션은 목록 스크롤과 관계없이 모든 유효 핀을 표시해야 한다.

현재 `restaurant`는 주소·식당 조회, `user`는 사용자 관계를 소유한다. 일반 식당 목록의
`basic/popular/rating` 정렬과 cursor는 지도 요구와 다르다. 기존 동작을 바꾸거나
`restaurant → user` 의존을 추가해 이 요구를 해결하지 않는다.

## 2. 결정

### 2.1 소유권과 공개 지점

| 소유자 | 책임 | 허용 호출 |
|---|---|---|
| restaurant | 좌표·출처·수명·주소 revision, 관광 지역, BBOX와 지도 순서 | 자신의 Repository, media의 기존 bulk 계약 |
| user | 저장 관계·집계·컬렉션 버전·열람/편집 권한 | `RestaurantPort`를 통한 공개 식당 bulk 조회 |
| admin | 관리자 요청 검증과 위임 | 기존 `RestaurantPort` |
| FE | 공개 지도와 사용자 저장 요약을 ID로 조합, 조회 세대 관리 | 별도 HTTP API |

일반 런타임 공개 facade는 기존 `RestaurantPort` 하나를 확장한다. 모듈 루트의
`RestaurantMapInfo`는 값만 전달한다. `findActiveMapInfos(ids)`는 공개 가능한 식당만
반환하되 좌표 미준비 식당도 위치 없이 반환해 user가 목록과 핀의 차이를 설명하게 한다.
입력 순서·중복 제거와 내부 batch 크기는 구현 테스트로 고정한다. 관리자 위치 조회·재처리도
이 Port에 위임한다. 다른 모듈의 엔티티·Repository·`domain` enum을 공개 계약에 넣지 않는다.

`restaurant → user`, 모듈 간 FK·DB join, 새 최상위 `map` 모듈은 만들지 않는다.
기존 `findSummaries`는 삭제 식당도 반환하므로 지도 노출용으로 재사용하지 않는다.
아키텍처 문서의 `user/bookmark` 서술 정정은 #216의 범위로 남긴다.

### 2.2 패키지와 작업 처리

기존 [아키텍처 컨벤션](../conventions/architecture.md)의 레이아웃을 유지한다.

| 위치 | 후속 구현 내용 |
|---|---|
| `restaurant/domain` | 위치·관광 지역·변환 작업 상태와 Repository |
| `restaurant/service` | 지도 조회, 좌표 결과 판정, claim·완료·재시도 transaction |
| `restaurant/dto`, `restaurant/web`, `restaurant/code` | HTTP DTO·Controller·소유 도메인 코드 |
| `restaurant/internal/map` | Redis 세션 adapter·직렬화, Google HTTP adapter·설정, worker 진입점 |
| `restaurant/migration` | 승인된 한시적 backfill runner·checkpoint; 종료 후 제거 |

주소 저장과 `PENDING` 작업 기록을 같은 MySQL transaction에서 원자적으로 남긴다.
worker는 별도의 짧은 transaction에서 lease를 claim하고, **transaction이 없는 구간에서**
Google을 호출한 뒤, 짧은 완료 transaction에서 revision·job ID·lease token을 재검사한다.
class-level `@Transactional` Service나 그 호출자의 transaction이 HTTP까지 감싸지 않도록
오케스트레이션과 DB 단계의 Bean 경계를 나눈다. scheduler가 DB의 미완료 작업을 다시 찾으므로
commit 직후 프로세스가 죽어도 작업은 유실되지 않는다.

M1에서는 새 공개 이벤트를 도입하지 않는다. 같은 모듈 안의 DB 작업 polling만으로 복구한다.
추후 즉시 깨우는 내부 이벤트가 필요해도 durable 작업을 대체하지 않으며,
`restaurant/internal/event`에서만 연결한다. 모듈 간 이벤트·broker로 확장하면 별도 ADR에서
발행 위치·EPR·멱등성과 transaction 밖 HTTP를 정의한다. media의 publisher 예외를 복사하지 않는다.

### 2.3 MySQL과 Redis

- MySQL: 식당·위치·관광 지역·변환 작업·저장 관계의 원본, BBOX·필터 후보 검색.
- Redis: 조회 조건, 후보 ID, 추천 순서, 별점·리뷰 수 기준값의 일시적인 조회 세션.
  생성 후 절대 TTL 15분을 초기 설정으로 채택한다. 읽기·정렬 변경으로 연장하지 않는다.
- 세션에 식당 사진·Google 좌표/원본 응답·개인 저장 여부를 복제하지 않는다.
- 현재 DB의 삭제·위치 수명·필터를 매 페이지 다시 확인한다. 신규 후보·최신 순위는 새 조회부터 반영한다.
- 유실·만료는 전용 410, Redis 접근 장애는 503으로 구분한다. 새 순서를 기존 cursor에 연결하지 않는다.
- 세션을 전부 저장할 수 없으면 명시적으로 실패한다. 일부 후보만 성공처럼 저장하지 않는다.

현재 Redis 설정이 있다는 사실은 지도 용량 확보의 증거가 아니다. 인증 토큰과 같은 인스턴스를
쓴다면 memory·eviction 영향을 확인하고 세션별/전체 admission 한도를 측정한다.
키 prefix는 메모리 격리 수단이 아니다. 이 결정은 성능 향상이 검증됐다는 뜻이 아니다.

## 3. 대안과 영향

| 대안 | 선택하지 않은 이유 |
|---|---|
| 매 페이지 `RAND()` | 앞 페이지와 순서·중복이 달라진다. |
| seed만 cursor에 저장 | 동점 순서는 재생성해도 별점·리뷰 수 변경으로 페이지 경계가 바뀐다. |
| BBOX를 Redis GEO에 복제 | MySQL의 분류·검색·삭제·수명과 이중으로 맞출 근거가 아직 없다. |
| 관리자 저장 transaction 안에서 Google 호출 | 외부 지연 동안 DB 연결·잠금을 점유하고 저장 실패와 변환 실패를 섞는다. |
| commit 후 메모리 비동기 작업만 등록 | commit과 enqueue 사이 장애에서 재처리 대상을 잃는다. |
| 컬렉션 목록 10개를 지도 핀으로 사용 | 아직 스크롤하지 않은 저장 식당의 핀을 누락한다. |

조회 세션의 생성 비용·메모리와 TTL 복구 UX, durable 작업의 재시도·수명 정리가 추가된다.
후속 PR은 DB migration·동시성·외부 provider 등 실제 변경 위험에 맞춰 다시 리뷰한다.
Google 보관 계약, 관광 지역 실제 값, 운영 quota와 backfill 승인은 이 ADR로 확보되지 않는다.
