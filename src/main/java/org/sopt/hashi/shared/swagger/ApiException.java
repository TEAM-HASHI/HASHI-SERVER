package org.sopt.hashi.shared.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.sopt.hashi.shared.error.ErrorCode;

/**
 * 엔드포인트가 낼 수 있는 에러 코드를 enum과 코드 이름으로 지정한다.
 * {@code codes}를 비우면 해당 enum의 모든 코드를 문서화한다(전체). 여러 enum은 반복해서 붙인다.
 *
 * <pre>{@code
 * @ApiException(value = UserErrorCode.class, codes = {"NOT_FOUND"})
 * @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
 * @GetMapping("/users/{id}")
 * SuccessResponse<UserResponse> get(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiExceptions.class)
public @interface ApiException {

    Class<? extends ErrorCode> value();

    String[] codes() default {};
}
