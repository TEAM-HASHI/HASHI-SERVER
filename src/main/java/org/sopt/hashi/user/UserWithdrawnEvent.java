package org.sopt.hashi.user;

/**
 * 회원 탈퇴 이벤트 — 탈퇴 트랜잭션이 커밋되면 구독 모듈(point 등)이 각자 트랜잭션에서 후속 정리를 한다(architecture.md §6·§8).
 * 발행 트랜잭션에 Event Publication Registry 행이 함께 기록되므로 리스너가 실패해도 유실되지 않고 재제출된다.
 * 구독 모듈은 이 타입만 의존한다.
 */
public record UserWithdrawnEvent(Long userId) {
}
