package org.sopt.hashi.review.dev;

/** 더미 리뷰를 달 대상 — 방문 완료 예약과 그 예약자. */
public record DummyReviewTarget(Long reservationId, Long userId) {
}
