package org.sopt.hashi.user;

import java.util.Optional;

/**
 * user 모듈의 공개 포트 — 타 도메인(reservation·review 등)이 회원을 참조할 때 쓰는 최소 계약.
 * 의존 모듈은 user 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩한다.
 */
public interface UserPort {

    /** 회원 요약을 조회한다 — 없으면 empty(탈퇴 등). 호출 측이 fallback을 결정한다(§5-2). */
    Optional<UserInfo> findById(Long userId);
}
