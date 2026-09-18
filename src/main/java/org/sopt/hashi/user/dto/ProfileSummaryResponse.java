package org.sopt.hashi.user.dto;

import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.user.domain.User;

/**
 * 프로필 요약 응답(헤더·마이페이지 노출용) — 닉네임과 프로필 사진만.
 * profileImageUrl은 READY 파생본 URL 또는 asset이 없는 기존 사진 URL이다.
 * 사진 미등록, asset 변환 중·실패 또는 조회 결과가 없으면 null이며, profileImage가 있으면 그 상태로 표시한다.
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
