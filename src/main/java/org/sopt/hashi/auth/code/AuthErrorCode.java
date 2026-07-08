package org.sopt.hashi.auth.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 인증/토큰 관련 에러 코드.
 */
@Getter
public enum AuthErrorCode implements ErrorCode {

    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-001", "유효하지 않은 토큰입니다"),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-002", "만료된 토큰입니다"),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "AUTH-003", "리프레시 토큰이 존재하지 않습니다"),
    TOKEN_REUSE_DETECTED(HttpStatus.UNAUTHORIZED, "AUTH-004", "이미 사용된 토큰입니다. 다시 로그인해주세요"),
    KAKAO_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "AUTH-005", "카카오 인증에 실패했습니다"),
    INVALID_ONBOARDING_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-006", "유효하지 않은 온보딩 토큰입니다"),
    KAKAO_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "AUTH-007", "카카오 서버와 통신할 수 없습니다"),
    ALREADY_LINKED_ACCOUNT(HttpStatus.CONFLICT, "AUTH-008", "이미 가입된 소셜 계정입니다"),
    // ID 존재 여부가 노출되지 않도록 아이디 오류·비밀번호 오류를 단일 메시지로 응답한다
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-009", "아이디 또는 비밀번호가 올바르지 않습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
