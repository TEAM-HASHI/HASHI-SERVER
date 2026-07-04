package org.sopt.hashi.shared.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ApiException}의 컨테이너. 한 핸들러에 여러 @ApiException을 붙이면 자동으로 여기에 묶인다.
 * 지정한 코드들이 상태코드별로 묶여 Swagger 문서에 ErrorResponse 예시로 노출된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiExceptions {

    ApiException[] value();
}
