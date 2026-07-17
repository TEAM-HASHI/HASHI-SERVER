package org.sopt.hashi.admin.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.sopt.hashi.user.AdminUserInfo;

/** 어드민 회원 응답 — 연락·식별 정보와 가입 시각. 프로필 이미지가 없으면 {@code profileImageUrl}은 null. */
public record AdminUserResponse(
        Long userId,
        String nickname,
        String nameEng,
        LocalDate birthDate,
        String phone,
        String email,
        String profileImageUrl,
        LocalDateTime createdAt) {

    public static AdminUserResponse from(AdminUserInfo info) {
        return new AdminUserResponse(
                info.id(),
                info.nickname(),
                info.nameEng(),
                info.birthDate(),
                info.phone(),
                info.email(),
                info.profileImageUrl(),
                info.createdAt());
    }
}
