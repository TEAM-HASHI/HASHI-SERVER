# 에러 / 응답 처리 컨벤션

> 응답 봉투·코드 계약·예외 처리 규칙. 새 에러/성공 코드 추가, 예외 처리 작업 시 참조.
> 구조 규칙(어디에 두나)은 `architecture.md` §4·§7과 함께 본다.

---

## 1. 큰 그림 — "틀은 shared, 내용은 도메인"

| 요소 | 위치 | 비고 |
| --- | --- | --- |
| 응답 봉투 `BaseResponse`/`SuccessResponse`/`ErrorResponse` | `shared/response` | 모든 API 공통 틀 |
| 코드 계약 `BaseCode`/`ErrorCode`/`SuccessCode` | `shared/error` | 인터페이스(계약)만 |
| 공통 에러 `CommonErrorCode` | `shared/error` | 도메인 무관(400/401/403/404/500 등) |
| `BusinessException` | `shared/error` | 비즈니스 예외 |
| `GlobalExceptionHandler` | `shared/exception` | 전역 변환 |
| **도메인 코드** `<Context>ErrorCode`/`<Context>SuccessCode` | **각 모듈 `code/`** | shared 인터페이스 구현 |
| **도메인 응답 DTO** `<Context>Response` | **각 모듈 `dto/`** | |

- **MUST NOT**: 도메인 코드 enum·도메인 응답 DTO를 `shared`에 두지 않는다.

---

## 2. 코드 계약 (shared/error)

```java
// 모든 코드의 공통 계약
public interface BaseCode {
    String code();        // 예: "RESERVATION-001"
    String message();
    HttpStatus status();
}
public interface ErrorCode extends BaseCode {}
public interface SuccessCode extends BaseCode {}
```

```java
// 도메인 무관 공통 에러
public enum CommonErrorCode implements ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST,           "COMMON-400", "잘못된 요청입니다"),
    UNAUTHORIZED (HttpStatus.UNAUTHORIZED,          "COMMON-401", "인증이 필요합니다"),
    FORBIDDEN    (HttpStatus.FORBIDDEN,             "COMMON-403", "권한이 없습니다"),
    NOT_FOUND    (HttpStatus.NOT_FOUND,             "COMMON-404", "리소스를 찾을 수 없습니다"),
    INTERNAL     (HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "서버 오류입니다");
    // status·code·message 필드 + 생성자
}
```

---

## 3. 도메인 코드 (각 모듈 code/)

- **MUST**: 도메인 코드는 각 모듈 `code/`에서 shared의 `ErrorCode`/`SuccessCode`를 **구현**한다.
- **MUST**: 코드 문자열에 **도메인 prefix**를 둔다(`<CONTEXT>-NNN`).
- **SHOULD**: 메시지는 사용자에게 보여줄 수 있는 한국어로 간결하게.

```java
// reservation/code/ReservationErrorCode.java
public enum ReservationErrorCode implements ErrorCode {
    NOT_FOUND        (HttpStatus.NOT_FOUND, "RESERVATION-001", "예약을 찾을 수 없습니다"),
    ALREADY_CANCELED (HttpStatus.CONFLICT,  "RESERVATION-002", "이미 취소된 예약입니다"),
    POINT_EXCEEDED   (HttpStatus.BAD_REQUEST,"RESERVATION-003", "보유 포인트를 초과했습니다");
    // ...
}
```

| prefix | 모듈 |
| --- | --- |
| `RESTAURANT-` | restaurant |
| `REVIEW-` | review |
| `RESERVATION-` | reservation |
| `POINT-` | point |
| `MAGAZINE-` | magazine |
| `USER-` | user |
| `SUPPORT-` | support |
| `AUTH-` | auth |
| `COMMON-` | shared(공통) |

---

## 4. 예외 처리

- **MUST**: 비즈니스 규칙 위반은 `BusinessException`(또는 그 하위)에 `ErrorCode`를 담아 던진다.
- **MUST NOT**: 컨트롤러/서비스에서 `ResponseEntity`로 에러를 직접 만들지 않는다. 변환은 `GlobalExceptionHandler`가 전담한다.
- **MUST NOT**: 예외를 삼키고(`catch` 후 무시) 정상 응답으로 처리하지 않는다.

```java
// shared/error/BusinessException.java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    public BusinessException(ErrorCode c) { super(c.message()); this.errorCode = c; }
    public ErrorCode errorCode() { return errorCode; }
}

// 던지는 쪽 (도메인 service)
if (reservation.isCanceled())
    throw new BusinessException(ReservationErrorCode.ALREADY_CANCELED);
```

```java
// shared/exception/GlobalExceptionHandler.java
@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handle(BusinessException e) {
        ErrorCode c = e.errorCode();
        return ResponseEntity.status(c.status()).body(ErrorResponse.of(c));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handle(Exception e) {
        return ResponseEntity.status(500).body(ErrorResponse.of(CommonErrorCode.INTERNAL));
    }
}
```

---

## 5. 응답 봉투

- **MUST**: 모든 API 응답은 `SuccessResponse`/`ErrorResponse`로 감싼다.
- **MUST**: 성공 응답의 알맹이(도메인 DTO)는 각 모듈 `dto/`의 `<Context>Response`다.

```java
// 컨트롤러
@GetMapping("/restaurants/{id}")
SuccessResponse<RestaurantResponse> get(@PathVariable Long id) {
    return SuccessResponse.of(restaurantService.getRestaurant(id));
}
```

```java
// shared/response (sealed로 봉인)
public sealed interface BaseResponse permits SuccessResponse, ErrorResponse {}
public record SuccessResponse<T>(boolean success, String code, String message, T data)
        implements BaseResponse {
    public static <T> SuccessResponse<T> of(T data) { return new SuccessResponse<>(true, "COMMON-200", "성공", data); }
    public static <T> SuccessResponse<T> of(SuccessCode c, T data) { return new SuccessResponse<>(true, c.code(), c.message(), data); }
}
public record ErrorResponse(boolean success, String code, String message) implements BaseResponse {
    public static ErrorResponse of(ErrorCode c) { return new ErrorResponse(false, c.code(), c.message()); }
}
```

---

## 빠른 점검

- [ ] 도메인 코드가 각 모듈 `code/`에서 shared 인터페이스를 구현하는가 (shared에 두지 않음)
- [ ] 코드 문자열에 도메인 prefix(`<CONTEXT>-NNN`)가 있는가
- [ ] 비즈니스 위반을 `BusinessException(ErrorCode)`로 던지는가
- [ ] 컨트롤러/서비스에서 에러 응답을 직접 만들지 않고 `GlobalExceptionHandler`에 위임하는가
- [ ] 응답을 `SuccessResponse`/`ErrorResponse`로 감쌌는가