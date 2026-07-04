package org.sopt.hashi.shared.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.sopt.hashi.shared.error.ErrorCode;

/**
 * 해당 API가 낼 수 있는 에러 코드 enum을 나열한다.
 * 지정한 enum의 코드들이 상태코드별로 묶여 Swagger 문서에 예시(ErrorResponse)로 자동 노출된다.
 *
 * <pre>{@code
 * @ApiExceptions({UserErrorCode.class, CommonErrorCode.class})
 * @GetMapping("/users/{id}")
 * SuccessResponse<UserResponse> get(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiExceptions {

    Class<? extends ErrorCode>[] value();
}
