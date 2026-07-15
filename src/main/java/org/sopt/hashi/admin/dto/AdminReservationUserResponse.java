package org.sopt.hashi.admin.dto;

import java.time.LocalDate;
import org.sopt.hashi.user.UserInfo;

/** 어드민 예약자 정보 응답 — 예약 대행에 필요한 연락·식별 정보(클라 합의 계약, 프로필 이미지 제외). */
public record AdminReservationUserResponse(
        Long userId,
        String nickname,
        String nameEng,
        LocalDate birthDate,
        String phone,
        String email) {

    public static AdminReservationUserResponse from(UserInfo info) {
        return new AdminReservationUserResponse(
                info.id(),
                info.nickname(),
                info.nameEng(),
                info.birthDate(),
                info.phone(),
                info.email());
    }
}
