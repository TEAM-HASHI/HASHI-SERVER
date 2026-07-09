package org.sopt.hashi.point.dto;

/**
 * 내 포인트 잔액 응답. 계정이 아직 없는 사용자(포인트 발생 이력 없음)는 0으로 내린다.
 * 필드 확장(내역 등)은 클라 합의 후 별도.
 */
public record PointBalanceResponse(long balance) {
}
