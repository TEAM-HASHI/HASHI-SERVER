# 코드 스타일 컨벤션

> 기준: Oracle Java Code Conventions, Google Java Style Guide, Spring Framework Code Style (Java 21)
> Controller·Service·Repository·DTO·VO 등 실제 코드 작성/리뷰 시 참조.
> 모듈/패키지 배치·네이밍(포트·이벤트 등) 구조 규칙은 `architecture.md`와 함께 본다.

---

## 1. 네이밍 (Naming)

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 패키지 | 모두 소문자, 단어 구분 없이 연결 | `org.sopt.hashi.reservation` |
| 클래스 / 인터페이스 | UpperCamelCase, 명사 | `ReservationService`, `Payable` |
| 메서드 | lowerCamelCase, 동사로 시작 | `findById()`, `createReservation()` |
| 변수 / 필드 | lowerCamelCase | `reservationCount`, `userName` |
| 상수 (`static final`) | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| 제네릭 타입 | 단일 대문자 | `T`, `E`, `K`, `V` |

- 약어도 일반 단어처럼 처리: `HttpUrl` (O), `HTTPURL` (X)
- 불리언 메서드/필드는 `is`/`has`/`can` 접두사: `isActive`, `hasPermission`

### 1-1. 프로젝트 고유 네이밍 (구조 규칙과 연동)

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 모듈 간 포트 | `<Context>Port` | `RestaurantPort` |
| 모듈 간 전달 DTO | `<Context>Info` | `UserInfo` |
| 응답 DTO | `<Context>Response` | `ReviewResponse` |
| 요청 DTO | `<동작><Context>Request` | `CreateReviewRequest` |
| 도메인 에러/성공 코드 | `<Context>ErrorCode` / `<Context>SuccessCode` | `PointErrorCode` |
| 발행 이벤트 | `<과거형 사건>Event` | `UserWithdrawnEvent` |
| 이벤트 리스너 | `<Event>Listener` | `UserWithdrawnListener` |

- **MUST NOT**: 응답 DTO를 `~View`로 명명하지 않는다(`~Response` 사용).

---

## 2. 포맷팅 (Formatting)

- 들여쓰기 **스페이스 4칸** (탭 금지)
- 한 줄 최대 **100~120자**
- 중괄호는 **K&R 스타일**(여는 중괄호 같은 줄)

```java
if (condition) {
    doSomething();
} else {
    doOther();
}
```

- 단일 문장이라도 중괄호 생략 금지
- 메서드/클래스 사이 빈 줄 1개
- import 와일드카드(`import java.util.*`) 금지, 명시적 선언

---

## 3. 클래스 구성 순서 (Member Ordering)

1. static 상수
2. static 필드
3. 인스턴스 필드
4. 생성자
5. public 메서드
6. protected / package-private 메서드
7. private 메서드

> 논리적으로 연관된 메서드는 호출 순서대로 가까이 배치한다.

---

## 4. Spring 컨벤션

### 4-1. 의존성 주입 (DI)

- **MUST**: 생성자 주입을 기본으로 한다. 필드 주입(`@Autowired` 필드) 지양.
- **MUST**: 주입 필드는 `private final`.
- 단일 생성자면 `@Autowired` 생략. (Lombok 사용 시 `@RequiredArgsConstructor` 허용)

```java
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final RestaurantPort restaurantPort;

    public ReservationService(ReservationRepository reservationRepository,
                              RestaurantPort restaurantPort) {
        this.reservationRepository = reservationRepository;
        this.restaurantPort = restaurantPort;
    }
}
```

### 4-2. API 설계

- **MUST**: 모든 endpoint는 `/api/v1`로 시작한다(버전 프리픽스).
- URL: 소문자·복수형 명사·케밥 케이스 (`/api/v1/reservations`, `/api/v1/reservation-items`)
- HTTP 메서드로 행위 표현(URL에 동사 금지): `GET /api/v1/reservations`, `POST /api/v1/reservations`
- **MUST**: 응답은 DTO(`<Context>Response`)로 반환하고 **엔티티를 직접 노출하지 않는다.**
- **MUST**: 응답은 `SuccessResponse`로 감싼다(상세 `error-handling.md`).
- **MUST**: 목록 페이지네이션은 **사용자향 피드=커서(cursor)**, **어드민 목록=offset(`page`/`size` + 총건수)**로 한다.
- **MUST**: 이미지 URL은 DB에 **S3 object key만 저장**하고, 응답 생성 시 **presigned GET URL로 변환**해 내린다(만료 있음, `shared/storage`의 `FileStorage`). 저장 값에 presigned 전체 URL을 넣지 않는다.
- **MUST**: 시각은 DB에 `DATETIME`으로 저장하고 응답은 **ISO-8601 문자열**로 내린다. 표시 포맷(`6월 22일` 등)은 클라이언트가 담당한다.

#### 4-2-1. Action 서브리소스 예외 (동사 명사화가 어색한 경우)

CRUD로 표현이 안 되는 상태 전이 액션(취소, 승인 등)은 `cancellation`처럼 억지로 명사화하지 않고,
Stripe·PayPal 등에서 쓰는 **action 서브리소스**(`/동사`)를 예외로 허용한다.

- **MUST**: 리소스 경로 뒤에 슬래시(`/`)와 동사를 붙인다(`/{reservationId}/cancel` 형태).
- **MUST**: HTTP 메서드는 `POST`로 고정한다(멱등하지 않은 상태 전이 액션이므로).
- **MUST**: 일반 CRUD로 표현 가능한 경우(단순 필드 갱신 등)에는 action 서브리소스를 쓰지 않고 `PATCH`를 사용한다.

```
POST /api/v1/reservations/{reservationId}/cancel
POST /api/v1/admin/reservations/{reservationId}/approve
```

```java
@PostMapping("/{reservationId}/cancel")
public SuccessResponse<ReservationResponse> cancel(@PathVariable Long reservationId) {
    return SuccessResponse.of(reservationService.cancel(reservationId));
}
```

```java
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/{id}")
    public SuccessResponse<ReservationResponse> get(@PathVariable Long id) {
        return SuccessResponse.of(reservationService.findById(id));
    }
}
```

### 4-3. 예외 처리

- `@RestControllerAdvice` + `@ExceptionHandler`로 전역 처리(상세 `error-handling.md`).
- 비즈니스 예외는 `BusinessException(ErrorCode)`로 던진다.

### 4-4. 계층 책임

- **MUST**: Controller는 입력 검증·DTO 변환·service 호출만. 비즈니스 로직 금지.
- **MUST**: Service에 비즈니스 로직. 다른 모듈이 필요하면 그 모듈의 `<Context>Port`만 호출.
- **MUST**: Repository는 자기 모듈 엔티티만 다룬다(타 모듈 테이블 조인 금지).

---

## 5. DTO / VO / 엔티티

- **MUST**: 요청/응답 DTO는 `record`로 작성한다(불변).
- **MUST**: 엔티티를 컨트롤러 응답으로 노출하지 않는다(항상 `<Context>Response`로 변환).
- **MUST**: 검증·규칙·연산이 붙는 값은 VO로(예: `Money`). VO는 JPA `@Embeddable`(상세 `architecture.md` §4-1).
- **MUST**: 입력 검증은 Bean Validation(`@NotNull`, `@Size` 등)을 요청 DTO에 선언하고, 컨트롤러에서 `@Valid`로 트리거한다.

```java
public record CreateReviewRequest(
        @NotNull Long reservationId,
        @NotBlank @Size(max = 1000) String content) {}
```

---

## 6. 주석 & 문서화

- 공개 API·복잡한 로직에 **Javadoc**. "무엇을"이 아니라 **"왜"** 를 설명.
- 주석 처리된 코드(commented-out)는 커밋하지 않는다.

---

## 7. 조건문 리팩토링

> 기준: Martin Fowler, *Refactoring* — Extract Variable / Extract Method

복합 조건(2개 이상)은 설명 변수로 추출하고, 같은 조건이 클래스 전반에서 반복되면 메서드로 추출한다.

```java
// Extract Variable — 메서드 내 1회
boolean isDiscountTarget = order.totalPrice() > 50_000
        && member.grade() == Grade.VIP
        && !order.isCanceled();
if (isDiscountTarget) applyDiscount();
```

| 상황 | 적용 |
| --- | --- |
| 조건 2개 이상, 해당 메서드 내에서만 사용 | Extract Variable |
| 동일 조건이 클래스 전반에서 반복 | Extract Method |

---

## 8. 기타 모범 사례

- 컬렉션/Optional 반환 시 `null` 대신 빈 컬렉션 또는 `Optional`.
- 매직 넘버는 상수로 추출.
- 메서드는 하나의 책임만, 짧게.
- 가변 상태 공유를 줄이고 불변 객체 선호.
- 정적 분석(Spotless 등)을 CI에 통합.

---

## 빠른 점검

- [ ] 생성자 주입 + `private final`인가 (필드 주입 금지)
- [ ] 컨트롤러가 엔티티가 아니라 `<Context>Response`를 반환하는가
- [ ] 요청/응답 DTO가 `record`이고 검증을 `@Valid`로 트리거하는가
- [ ] Controller에 비즈니스 로직이 없는가
- [ ] URL이 `/api/v1`로 시작하고, 동사 없이 명사·복수·케밥인가 (단, 상태 전이 액션은 `/동사` action 서브리소스 예외 사용, POST 고정)
- [ ] 목록이 사용자=커서 / 어드민=offset 페이지네이션 규칙을 따르는가
- [ ] 이미지 URL을 응답 시 presigned로 변환하고(저장은 key), 시각을 ISO-8601로 내리는가
- [ ] 복합 조건을 설명 변수/메서드로 추출했는가

---

### 참고

- Oracle, *Code Conventions for the Java Programming Language*
- Google Java Style Guide — https://google.github.io/styleguide/javaguide.html
- Spring Framework Wiki — Code Style