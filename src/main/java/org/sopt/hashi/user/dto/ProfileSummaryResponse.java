package org.sopt.hashi.user.dto;

import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.user.domain.User;

/**
 * 프로필 요약 응답(헤더·마이페이지 노출용) — 닉네임과 프로필 사진만.
 * profileImageUrl은 저장된 S3 key를 조회 URL로 변환한 값이며, 사진 미등록이면 null(클라가 기본 이미지 처리).
 */
public record ProfileSummaryResponse(
        String nickname,
        String profileImageUrl,
        MediaImage profileImage) {

    public static ProfileSummaryResponse of(
            User user,
            String profileImageUrl,
            MediaImage profileImage
    ) {
        return new ProfileSummaryResponse(user.getNickname(), profileImageUrl, profileImage);
    }
}
