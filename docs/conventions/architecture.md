# 아키텍처 컨벤션

> 본 문서는 **Spring Modulith 기반 모듈러 모놀리스** 프로젝트(HASHI)의 구조 규칙을 정의한다.
> Claude skill / CodeRabbit 등 자동 리뷰 도구는 이 문서를 기준으로 컨벤션 위반을 지적한다.
> 규칙은 `MUST`(필수) / `MUST NOT`(금지) / `SHOULD`(권장)로 표기한다.

---

## 0. 빠른 점검 (리뷰 시 우선 확인)

- [ ] `common` / `core` 패키지·모듈을 만들지 않았는가
- [ ] 새 공통 코드가 `shared`에 들어갈 때 **도메인 지식이 없는가**
- [ ] 모듈 간 호출이 상대 모듈의 `internal`/엔티티/Repository가 아니라 **`XxxPort`** 를 통하는가
- [ ] 모듈 간 **순환 의존**이 없는가
- [ ] 모듈 간 **DB 조인 / FK** 로 묶지 않았는가 (ID 참조만)
- [ ] 도메인 에러/성공 코드가 **각 모듈 `code/`** 에 있는가 (shared에 두지 않음)

---

## 1. 모듈 경계

- **MUST**: 모듈 경계는 **애그리거트(비즈니스 능력 + 트랜잭션 일관성 경계)** 기준으로 나눈다.
- **MUST NOT**: 기술 계층(`controller` / `service` / `repository`)을 최상위 모듈로 두지 않는다.
- **MUST NOT**: 엔티티(테이블) 1개당 모듈 1개로 매핑하지 않는다. "함께 바뀌고 함께 커밋되는 단위"로 묶는다.
- **MUST**: 자식 엔티티(메뉴·사진·카드 등)는 별도 모듈이 아니라 애그리거트 루트가 소유하는 자식으로 둔다.

도메인 컨텍스트(7): `restaurant` · `review` · `reservation` · `point` · `magazine` · `user` · `support`
횡단: `auth` / 진입점: `admin` / 공유 커널: `shared`

---

## 2. 패키지 구조

### 2-1. 루트 (모듈 아님)
- **MUST**: `@SpringBootApplication`·`@Modulithic`을 붙인 애플리케이션 클래스, `config/`, `BaseEntity`는 **base 패키지(`org.sopt.hashi`) 직속**에 둔다.
- **MUST NOT**: 이들을 `root/` 같은 하위 패키지로 감싸지 않는다 (하위 패키지는 모듈로 인식됨).

### 2-2. 도메인 모듈 내부 (고정 레이아웃)
각 도메인 모듈은 아래 패키지 구성을 **MUST** 따른다.

```
<context>/
├─ <Context>Port          # 모듈 간 공개 포트 (통합 1개)
├─ <Context>Info          # 모듈 간 전달 DTO (포트로 노출, 필요 시)
├─ code/                  # 도메인 에러/성공 코드 (ErrorCode/SuccessCode 구현)
├─ domain/                # 엔티티 · Repository (애그리거트)
├─ service/               # 비즈니스 로직
├─ dto/                   # Request / Response DTO
├─ web/                   # Controller
└─ event/                 # 이벤트 리스너 (필요 시)
```

- **MUST**: 외부에 공개하는 것은 `<Context>Port` / `<Context>Info` / **발행 이벤트**뿐이다. 그 외(`domain`·`service`·`dto`·`web`)는 모듈 내부 구현으로 취급한다.
- **MUST**: **발행 이벤트**(예: `UserWithdrawnEvent`)는 모듈 루트에 공개(Port와 같은 위치)하고, `event/` 에는 **구독 리스너**(예: `UserWithdrawnListener`)만 둔다.
- **SHOULD**: 복합 컨텍스트는 하위 도메인 패키지를 둘 수 있다(예: `user/bookmark/`, `support/inquiry/`·`support/notice/`). 이 경우에도 모듈 공개 지점은 `<Context>Port`로 단일화한다.

---

## 3. 금지 — common / core

- **MUST NOT**: `common`, `core`, `util`, `global` 등 **모든 모듈이 의존하는 잡동사니 패키지**를 만들지 않는다.
- **MUST NOT**: 공용 DTO·엔티티·도메인 모델을 공유 패키지에 두지 않는다.
- **SHOULD**: 모듈 간 공유가 필요해 보이면, 공유 대신 **약간의 중복을 허용**한다 ("적게 공유하고 많이 소유한다").
- 진짜 전역 공통은 §4의 `shared`로만 둔다.

---

## 4. shared (공유 커널)

- **MUST**: `shared`는 `@ApplicationModule(type = OPEN)`으로 선언한다.
- **MUST**: `shared`에는 **도메인 지식이 없는 틀·계약·VO만** 둔다.
    - 허용: 응답 래퍼(`BaseResponse`/`SuccessResponse`/`ErrorResponse`), 코드 계약 인터페이스(`BaseCode`/`ErrorCode`/`SuccessCode`), 공통 예외(`BusinessException`), 전역 핸들러(`GlobalExceptionHandler`), 도메인 무관 VO(`Money`/`Address`), 스토리지 포트(`FileStorage`).
- **MUST NOT**: 특정 도메인을 아는 타입(예: `RestaurantDto`, `User`, `ReservationStatus`)을 `shared`에 두지 않는다.
- **MUST**: 의존 방향은 **도메인 → shared 단방향**. `shared`는 어떤 도메인 모듈도 import하지 않는다.
- 하위 패키지: `response` · `error` · `exception` · `storage` · `vo`

원칙: **"틀은 공유, 내용은 도메인."**

### 4-1. VO 규칙
- **SHOULD**: 검증·규칙·연산이 값에 붙으면 VO로 만든다. 단순 값이면 엔티티 컬럼으로 둬도 된다(VO는 필수 아님).
- **MUST**: 여러 모듈이 공유하는 도메인 무관 VO만 `shared/vo`에 둔다. 한 모듈만 쓰는 VO는 그 모듈 안에 둔다.
- **MUST**: VO는 JPA `@Embeddable`로 저장한다(별도 테이블 없이 컬럼).
- 채택 VO: `Money`(금액·포인트 — 음수 불가/통화 일치/연산).

---

## 5. 모듈 간 통신

- **MUST**: 모듈 간 동기 호출은 상대 모듈의 **`<Context>Port`(포트 인터페이스)** 로만 한다.
- **MUST NOT**: 다른 모듈의 `domain`·`service`·`Repository`·엔티티를 직접 import/호출하지 않는다.
- **MUST NOT**: 모듈 간 **DB 조인 / 외래키(FK)** 로 묶지 않는다. 타 도메인 식별자는 **ID 값으로만** 보관한다.
- **MUST NOT**: 모듈 간 **순환 의존**을 만들지 않는다(직접·간접 모두). 역방향 정보가 필요하면 §6 이벤트로 해결한다.

### 5-1. 포트 vs 이벤트 선택 기준
- 판단 기준: **"그 작업의 성공이 상대 모듈 응답에 즉시 달려 있는가?"**
    - 예 → **포트(동기 직접 호출)**. 즉시 일관성·1:1 의존.
    - 아니오(알리고 각자 처리) → **이벤트(비동기)**. 결과적 일관성·1:N 팬아웃.
- **SHOULD**: 이벤트는 보수적으로 최소화한다(현재 `UserWithdrawnEvent` 1건).

---

## 6. 이벤트

- **MUST**: 이벤트 구독은 `@ApplicationModuleListener`로 처리한다.
- **MUST**: 이벤트 핸들러는 **멱등(idempotent)** 하게 작성한다(재처리/중복 수신 대비).
- **MUST**: 이벤트는 발행 모듈이 `@NamedInterface`로 노출하고, 구독 모듈은 그 이벤트 타입만 의존한다.
- **MUST NOT**: 이벤트 발행자가 구독자를 직접 알거나 호출하지 않는다.
- 이벤트 신뢰성은 Event Publication Registry(JPA, MySQL)로 보장한다(트랜잭셔널 아웃박스).

---

## 7. 에러 / 응답 코드

- **MUST**: 코드 계약 인터페이스(`BaseCode`/`ErrorCode`/`SuccessCode`)와 공통 에러(`CommonErrorCode`), `BusinessException`, `GlobalExceptionHandler`는 `shared`에 둔다.
- **MUST**: 도메인별 에러/성공 코드(`<Context>ErrorCode`/`<Context>SuccessCode`)는 **각 모듈 `code/`** 에서 shared 인터페이스를 **구현**한다.
- **MUST NOT**: 도메인 코드 enum을 `shared`에 두지 않는다.
- **MUST**: 응답 봉투(`SuccessResponse`/`ErrorResponse`)는 `shared`, 도메인 응답 DTO(`<Context>Response`)는 각 모듈 `dto/`에 둔다. 컨트롤러에서 후자를 전자로 감싼다.
- **SHOULD**: 에러 코드 문자열에 도메인 prefix를 둔다(예: `RESERVATION-001`).

---

## 8. 트랜잭션

- **MUST**: 원칙은 **1 트랜잭션 = 1 애그리거트**.
- **MUST**: 즉시 일관성이 필요한 교차 작업(포인트 적립/차감/복원 등)은 트리거 작업과 **같은 트랜잭션**(동기 포트 호출)으로 처리한다.
- **MUST**: 팬아웃 후속처리(탈퇴 정리 등)는 **이벤트**로, 발행 트랜잭션 커밋 후 별도 트랜잭션에서 처리한다.
- **SHOULD**: 동시성 위험이 있는 잔액성 데이터(포인트)는 낙관적 락 또는 잔액 검증으로 보호한다.

---

## 9. auth / admin

- **MUST**: `auth`는 `@Modulithic(sharedModules = "org.sopt.hashi.auth")`로 등록한다(횡단 관심사).
- **MUST**: 인증 **강제**는 Spring Security 필터 체인이 담당한다. 도메인 모듈은 `auth`를 import하지 않고 `SecurityContext`/`CurrentUserProvider`로 현재 사용자를 읽는다.
- **MUST NOT**: 도메인 모듈이 인증 로직을 직접 구현하지 않는다.
- **MUST**: `admin`은 진입점 모듈로, **도메인 로직을 두지 않는다.** 각 컨텍스트의 `Port`로 위임만 한다.
- **MUST NOT**: `admin`이 타 모듈의 `internal`/Repository/엔티티에 직접 접근하지 않는다.

---

## 10. 구조 검증

- **MUST**: 모듈 경계·순환 의존은 `ApplicationModules.of(HashiApplication.class).verify()` 테스트로 **강제**한다.
- **SHOULD**: `shared`가 도메인 모듈을 의존하면 실패하는 ArchUnit 규칙을 둔다.
- 검증 테스트의 작성 방법·예시 코드는 `testing.md`를 참조한다.

---

## 부록 — 의존 방향 (비순환)

```
auth → user
review → restaurant, reservation, point
reservation → restaurant, user, point
admin → restaurant, magazine, reservation, support, user
모든 도메인 → shared (OPEN)
user ⇢ review, reservation, point   (UserWithdrawnEvent, 이벤트)
```