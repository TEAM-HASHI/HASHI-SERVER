package org.sopt.hashi.user.dto;

import java.time.LocalDate;
import org.sopt.hashi.user.domain.User;

/**
 * 내 정보 조회 응답(내 정보 수정 페이지용) — 온보딩에서 받은 프로필 전체를 내린다.
 * profileImageUrl은 저장된 S3 key를 조회 URL로 변환한 값이며, 사진 미등록이면 null(클라가 기본 이미지 처리).
 */
public record MyInfoResponse(
        String nickname,
        String nameEng,
        LocalDate birthDate,
        String phone,
        String email,
        String profileImageUrl) {

    public static MyInfoResponse of(User user, String profileImageUrl) {
        return new MyInfoResponse(
                user.getNickname(),
                user.getNameEng(),
                user.getBirthDate(),
                user.getPhone(),
                user.getEmail(),
                profileImageUrl);
    }
}
