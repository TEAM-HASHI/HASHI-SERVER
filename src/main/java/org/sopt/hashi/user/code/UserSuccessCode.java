package org.sopt.hashi.user.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.SuccessCode;
import org.springframework.http.HttpStatus;

@Getter
public enum UserSuccessCode implements SuccessCode {

    ONBOARDING_COMPLETED(HttpStatus.CREATED, "USER-201", "회원가입이 완료되었습니다"),
    COLLECTION_CREATED(HttpStatus.CREATED, "USER-202", "컬렉션을 만들었습니다"),
    RESTAURANT_SAVED(HttpStatus.CREATED, "USER-203", "컬렉션에 식당을 저장했습니다"),
    // USER-204는 회원 탈퇴(#243)가 선점했다
    PROFILE_UPDATED(HttpStatus.OK, "USER-205", "내 정보를 수정했습니다"),
    PROFILE_IMAGE_DELETED(HttpStatus.OK, "USER-206", "프로필 사진을 삭제했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    UserSuccessCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
