package org.sopt.hashi.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 모든 응답 코드의 공통 계약. {@link ErrorCode}/{@link SuccessCode}가 확장하고, 구현 enum이 그 둘 중 하나를 구현한다.
 * 구현 enum은 Lombok {@code @Getter}로 getter를 노출한다.
 */
public interface BaseCode {

    String getCode();

    String getMessage();

    HttpStatus getStatus();
}
