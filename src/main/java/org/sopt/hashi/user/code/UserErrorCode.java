package org.sopt.hashi.user.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 회원(가입·프로필)·식당 컬렉션 관련 에러 코드.
 */
@Getter
public enum UserErrorCode implements ErrorCode {

    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "USER-001", "중복된 닉네임입니다"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "USER-002", "이미 사용 중인 이메일입니다"),
    DUPLICATE_PHONE(HttpStatus.CONFLICT, "USER-003", "이미 사용 중인 연락처입니다"),
    // 사전 검사와 저장 사이의 동시 가입 경합에서, 어느 필드인지 특정할 수 없을 때의 폴백(409)
    DUPLICATE_USER_INFO(HttpStatus.CONFLICT, "USER-004", "이미 사용 중인 가입 정보입니다"),
    // 토큰은 유효하나 회원 레코드가 없는 경우(탈퇴 직후 잔여 토큰 등)
    NOT_FOUND(HttpStatus.NOT_FOUND, "USER-005", "회원을 찾을 수 없습니다"),

    // 식당 컬렉션(#216)
    COLLECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-006", "컬렉션을 찾을 수 없습니다"),
    DUPLICATE_COLLECTION_NAME(HttpStatus.CONFLICT, "USER-007", "이미 같은 이름의 컬렉션이 있습니다"),
    UNSUPPORTED_COLLECTION_COLOR(HttpStatus.BAD_REQUEST, "USER-008", "지원하지 않는 컬렉션 색상입니다"),
    UNSUPPORTED_COLLECTION_VISIBILITY(HttpStatus.BAD_REQUEST, "USER-009", "지원하지 않는 공개 범위입니다"),
    COLLECTION_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "USER-010", "만들 수 있는 컬렉션 수를 초과했습니다"),
    SAVED_RESTAURANT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "USER-011", "컬렉션에 저장할 수 있는 식당 수를 초과했습니다"),
    RESTAURANT_ALREADY_SAVED(HttpStatus.CONFLICT, "USER-012", "이미 컬렉션에 저장된 식당입니다"),
    RESTAURANT_NOT_SAVED(HttpStatus.NOT_FOUND, "USER-013", "컬렉션에 저장되지 않은 식당입니다"),
    SAME_COLLECTION_MOVE(HttpStatus.BAD_REQUEST, "USER-014", "같은 컬렉션으로는 이동할 수 없습니다"),
    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-015", "저장할 식당을 찾을 수 없습니다"),
    UNSUPPORTED_SAVED_RESTAURANT_SORT(HttpStatus.BAD_REQUEST, "USER-016", "지원하지 않는 정렬 기준입니다"),
    // 공유 링크로 들어왔는데 소유자가 비공개로 바꾼 경우 — 없음(006)과 구분해 클라이언트가 안내를 달리한다
    COLLECTION_PRIVATE(HttpStatus.FORBIDDEN, "USER-017", "비공개 컬렉션입니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UserErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
