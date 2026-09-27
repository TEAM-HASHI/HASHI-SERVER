package org.sopt.hashi.user;

import org.sopt.hashi.media.ImageReference;

/** 다른 모듈에 공개하는 회원 프로필 요약. 이미지가 없으면 reference는 null이다. */
public record UserProfileInfo(
        Long id,
        String nickname,
        ImageReference profileImageReference
) {
}
