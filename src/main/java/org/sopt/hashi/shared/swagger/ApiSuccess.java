package org.sopt.hashi.shared.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.sopt.hashi.shared.error.SuccessCode;

/**
 * 엔드포인트가 내는 성공 코드를 enum과 코드 이름으로 지정한다({@link ApiException}의 성공 응답 짝).
 * {@code codes}를 비우면 해당 enum의 모든 코드를 문서화한다. 조건에 따라 코드가 갈리면 여러 개를 나열한다.
 *
 * <p>어노테이션이 없는 핸들러는 200 응답에 {@code CommonSuccessCode.OK}가 기본 문서화되므로,
 * 201 등 비기본 코드를 내는 엔드포인트에만 선언하면 된다.
 *
 * <pre>{@code
 * @ApiSuccess(value = UserSuccessCode.class, codes = {"ONBOARDING_COMPLETED"})
 * @PostMapping("/onboarding")
 * SuccessResponse<OnboardingResponse> completeOnboarding(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiSuccesses.class)
public @interface ApiSuccess {

    Class<? extends SuccessCode> value();

    String[] codes() default {};
}
