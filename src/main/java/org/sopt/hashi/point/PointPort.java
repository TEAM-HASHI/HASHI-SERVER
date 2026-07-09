package org.sopt.hashi.point;

/**
 * point 모듈의 공개 포트 — 타 도메인(reservation·review 등)이 포인트를 조작·조회할 때 쓰는 계약.
 * 모든 변동 연산은 호출자의 트랜잭션에 참여한다(§8 — 예약 저장과 포인트 차감이 함께 커밋/롤백).
 * 변동은 반드시 출처(sourceType·sourceId)와 함께 기록되어 원장 추적·복원의 근거가 된다.
 */
public interface PointPort {

    /** 포인트를 적립한다(예: 리뷰 작성 보상). 계정이 없으면 생성한다. */
    void earn(Long userId, long amount, String reason, PointSourceType sourceType, Long sourceId);

    /** 포인트를 차감한다(예: 예약 수수료). 잔액이 부족하면 BusinessException(INSUFFICIENT_BALANCE). */
    void use(Long userId, long amount, String reason, PointSourceType sourceType, Long sourceId);

    /**
     * 해당 출처(sourceType·sourceId)로 차감했던 포인트를 되돌린다(예: 예약 취소).
     * 복원 금액은 원장의 차감 기록에서 찾으므로 호출자가 금액을 기억할 필요가 없다.
     * 차감 기록이 없으면 RESTORE_TARGET_NOT_FOUND, 이미 복원했으면 ALREADY_RESTORED.
     */
    void restore(Long userId, PointSourceType sourceType, Long sourceId);

    /**
     * 해당 출처의 차감이 이미 복원되었는지 확인한다 — 취소로 복원받은 예약을 어드민이 되살린 뒤
     * 다시 취소하는 흐름에서 중복 복원 시도(ALREADY_RESTORED)를 사전에 거르는 용도.
     */
    boolean isRestored(PointSourceType sourceType, Long sourceId);

    /** 현재 잔액을 조회한다. 계정이 없으면 0. */
    long getBalance(Long userId);
}
