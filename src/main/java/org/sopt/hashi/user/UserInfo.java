package org.sopt.hashi.user;

import java.time.LocalDate;

/**
 * 모듈 간 전달용 회원 요약 DTO. 예약자 조회 등 의존 모듈이 회원의 연락·식별 정보를 확인하는 데 필요한
 * 최소 필드만 담는다(§2-2 {@code <Context>Info}).
 */
public record UserInfo(
        Long id,
        String nickname,
        String nameEng,
        LocalDate birthDate,
        String phone,
        String email) {
}
