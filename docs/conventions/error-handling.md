# 에러 / 응답 처리 컨벤션

> 응답 봉투·코드 계약·예외 처리 규칙. 새 에러/성공 코드 추가, 예외 처리 작업 시 참조.
> 구조 규칙(어디에 두나)은 `architecture.md` §4·§7과 함께 본다.

---

## 1. 큰 그림 — "틀은 shared, 내용은 도메인"

| 요소 | 위치 | 비고 |
| --- | --- | --- |
| 응답 봉투 `BaseResponse`/`SuccessResponse`/`ErrorResponse`/`FieldError` | `shared/response` | 모든 API 공통 틀 |
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
// 모든 코드의 공통 계약 (구현 enum은 Lombok @Getter로 getter 노출)
public interface BaseCode {
    String getCode();         // 예: "RESERVATION-001"
    String getMessage();
    HttpStatus getStatus();
}
public interface ErrorCode extends BaseCode {}
public interface SuccessCode extends BaseCode {}
```

```java
// 도메인 무관 공통 에러
@Getter
public enum CommonErrorCode implements ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST,           "COMMON-400", "잘못된 요청입니다"),
    UNAUTHORIZED (HttpStatus.UNAUTHORIZED,          "COMMON-401", "인증이 필요합니다"),
    FORBIDDEN    (HttpStatus.FORBIDDEN,             "COMMON-403", "권한이 없습니다"),
    NOT_FOUND    (HttpStatus.NOT_FOUND,             "COMMON-404", "리소스를 찾을 수 없습니다"),
    INTERNAL     (HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "서버 오류입니다");

    private final HttpStatus status;   // @Getter → getStatus()
    private final String code;         // @Getter → getCode()
    private final String message;      // @Getter → getMessage()
    // 생성자
}
```

---

## 3. 도메인 코드 (각 모듈 code/)

- **MUST**: 도메인 코드는 각 모듈 `code/`에서 shared의 `ErrorCode`/`SuccessCode`를 **구현**한다.
- **MUST**: 코드 문자열에 **도메인 prefix**를 둔다(`<CONTEXT>-NNN`).
- **SHOULD**: 메시지는 사용자에게 보여줄 수 있는 한국어로 간결하게.

```java
// reservation/code/ReservationErrorCode.java
@Getter
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
    public BusinessException(ErrorCode c) { super(c.getMessage()); this.errorCode = c; }
    public ErrorCode getErrorCode() { return errorCode; }
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
    ResponseEntity<ErrorResponse> handle(BusinessException e, HttpServletRequest req) {
        ErrorCode c = e.getErrorCode();
        return ResponseEntity.status(c.getStatus()).body(ErrorResponse.of(c, req.getRequestURI()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handle(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT, req.getRequestURI(), errors));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handle(Exception e, HttpServletRequest req) {
        return ResponseEntity.status(CommonErrorCode.INTERNAL.getStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL, req.getRequestURI()));
    }
}
```

---

## 5. 응답 봉투

- **MUST**: 모든 API 응답은 `SuccessResponse`/`ErrorResponse`로 감싼다. 둘 다 `sealed interface BaseResponse`(=`success`/`code`/`message`)를 구현한다.
- **MUST**: 응답 바디에 `HttpStatus`를 넣지 않는다. 상태 코드는 `ResponseEntity`가 전달하므로 바디에 중복하지 않는다.
- **MUST**: 성공 응답의 알맹이(도메인 DTO)는 각 모듈 `dto/`의 `<Context>Response`다.
- **MUST**: 성공·실패 응답 모두 `data` 필드를 **항상 노출**한다(없으면 `null`). 실패 응답의 `data`는 **항상 `null`**이다(성공/실패 형태 일관성).
- **MUST**: 실패 응답에는 `timestamp`(ISO-8601)·`path`(요청 경로)를 포함하고, **검증 실패 시** `errors`(필드 단위 `FieldError` 목록)를 채운다. 검증 외 일반 에러는 `errors`를 내려보내지 않는다.
- **MUST**: `data`를 제외한 `null` 필드는 직렬화에서 제외한다 — `ErrorResponse`에 **클래스 단위 `@JsonInclude(NON_NULL)`**, `data` 필드에만 **`@JsonInclude(ALWAYS)`**를 붙인다(`data`는 `null`이어도 항상 노출, `errors`는 `null`이면 자동 생략).
- **MUST NOT**: `SuccessResponse`에는 클래스 단위 `NON_NULL`을 적용하지 않는다(성공 응답 `data`도 `null`이어도 항상 노출).

```java
// 컨트롤러
@GetMapping("/restaurants/{id}")
SuccessResponse<RestaurantResponse> get(@PathVariable Long id) {
    return SuccessResponse.of(restaurantService.getRestaurant(id));
}
```

```java
// shared/response — 응답 객체 최상위 인터페이스(레코드 아님). SuccessResponse·ErrorResponse만 구현(sealed).
// HttpStatus는 ResponseEntity가 전달하므로 응답 바디에 두지 않는다(중복 방지).
public sealed interface BaseResponse permits SuccessResponse, ErrorResponse {
    boolean success();
    String code();
    String message();
}

// 성공: data는 null이어도 항상 노출 (클래스 단위 NON_NULL 미적용)
public record SuccessResponse<T>(boolean success, String code, String message, T data)
        implements BaseResponse {
    public static <T> SuccessResponse<T> of(T data) { return new SuccessResponse<>(true, "COMMON-200", "요청에 성공했습니다.", data); }
    public static <T> SuccessResponse<T> of(SuccessCode c, T data) { return new SuccessResponse<>(true, c.getCode(), c.getMessage(), data); }
}

// 실패: data 제외 null 필드는 생략(클래스 NON_NULL), data만 ALWAYS로 항상 노출
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        Object data,                                   // 항상 null이지만 ALWAYS로 노출
        LocalDateTime timestamp,
        String path,
        List<FieldError> errors                        // 검증 실패 시에만 채움, null이면 클래스 규칙으로 생략
) implements BaseResponse {

    // 필드 단위 검증 에러 (도메인 무관 — shared에 둠)
    public record FieldError(String field, Object rejectedValue, String reason) {}

    public static ErrorResponse of(ErrorCode c, String path) {
        return new ErrorResponse(false, c.getCode(), c.getMessage(), null, LocalDateTime.now(), path, null);
    }
    public static ErrorResponse of(ErrorCode c, String path, List<FieldError> errors) {
        return new ErrorResponse(false, c.getCode(), c.getMessage(), null, LocalDateTime.now(), path, errors);
    }
}
```

---

## 빠른 점검

- [ ] 도메인 코드가 각 모듈 `code/`에서 shared 인터페이스를 구현하는가 (shared에 두지 않음)
- [ ] 코드 문자열에 도메인 prefix(`<CONTEXT>-NNN`)가 있는가
- [ ] 비즈니스 위반을 `BusinessException(ErrorCode)`로 던지는가
- [ ] 컨트롤러/서비스에서 에러 응답을 직접 만들지 않고 `GlobalExceptionHandler`에 위임하는가
- [ ] 응답을 `SuccessResponse`/`ErrorResponse`로 감쌌는가
- [ ] 실패 응답에 `data`(항상 null)·`timestamp`·`path`가 있고, 검증 실패 시 `errors`(FieldError)를 채우는가
- [ ] `ErrorResponse`에 클래스 단위 `@JsonInclude(NON_NULL)`을 걸고 `data` 필드에만 `@JsonInclude(ALWAYS)`를 붙여, `data`는 항상 노출되고 그 외 `null`(예: `errors`)은 생략되는가