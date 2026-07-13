package org.sopt.hashi.user;

/** 다른 모듈에 공개하는 회원 프로필 요약. 프로필 이미지가 없으면 {@code profileImageUrl}은 null이다. */
public record UserProfileInfo(
        Long id,
        String nickname,
        String profileImageUrl
) {
}
