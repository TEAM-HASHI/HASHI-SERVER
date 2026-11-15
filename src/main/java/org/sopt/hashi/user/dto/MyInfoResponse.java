package org.sopt.hashi.user.dto;

import java.time.LocalDate;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.user.domain.User;

/**
 * 내 정보 조회 응답(내 정보 수정 페이지용) — 온보딩에서 받은 프로필 전체를 내린다.
 * profileImageUrl은 READY 파생본 URL 또는 asset이 없는 기존 사진 URL이다.
 * 사진 미등록, asset 변환 중·실패 또는 조회 결과가 없으면 null이며, profileImage가 있으면 그 상태로 표시한다.
 */
public record MyInfoResponse(
        String nickname,
        String nameEng,
        LocalDate birthDate,
        String phone,
        String email,
        String profileImageUrl,
        MediaImage profileImage) {

    public static MyInfoResponse of(
            User user,
            String profileImageUrl,
            MediaImage profileImage
    ) {
        return new MyInfoResponse(
                user.getNickname(),
                user.getNameEng(),
                user.getBirthDate(),
                user.getPhone(),
                user.getEmail(),
                profileImageUrl,
                profileImage);
    }
}
