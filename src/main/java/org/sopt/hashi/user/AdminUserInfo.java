package org.sopt.hashi.user;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.sopt.hashi.media.ImageReference;

/**
 * 어드민 회원 목록 전달용 요약 DTO(§2-2 {@code <Context>Info}) — 연락·식별 정보와 가입 시각.
 * 프로필 이미지는 전환기 reference로 담고, 없으면 null이다.
 */
public record AdminUserInfo(
        Long id,
        String nickname,
        String nameEng,
        LocalDate birthDate,
        String phone,
        String email,
        ImageReference profileImageReference,
        LocalDateTime createdAt) {
}
