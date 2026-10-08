package org.sopt.hashi.user;

/**
 * 회원 탈퇴를 막는 조건의 공개 계약 — user가 정의하고, 조건을 소유한 모듈이 구현한다(의존 역전: 구현 모듈 → user).
 * 탈퇴 서비스가 회원 행을 잠근 뒤 구현체를 순서대로 호출하며, 막아야 하면 구현체가 자기 모듈의 에러 코드로
 * {@code BusinessException}을 던진다. user는 구현 모듈을 알지 못하므로 순환 의존 없이 조건을 늘릴 수 있다.
 * 이미 user를 의존하는 모듈(reservation 등)만 구현한다 — user가 의존하는 모듈(restaurant·media·auth)의 조건은
 * user가 그 모듈의 포트로 직접 확인한다(구현하면 순환).
 */
public interface WithdrawalBlocker {

    /** 회원이 탈퇴할 수 있으면 그대로 반환하고, 아니면 BusinessException을 던진다. */
    void validateWithdrawable(Long userId);
}
