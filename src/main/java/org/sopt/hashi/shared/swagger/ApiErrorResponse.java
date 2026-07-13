package org.sopt.hashi.shared.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.http.HttpStatus;

/**
 * 다른 모듈의 내부 ErrorCode enum을 참조하지 않고 API 오류 응답을 문서화한다.
 * 모듈 진입점이 하위 모듈의 오류를 그대로 전달하지만 해당 enum은 공개 API가 아닐 때 사용한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiErrorResponses.class)
public @interface ApiErrorResponse {

    HttpStatus status();

    String code();

    String message();
}
