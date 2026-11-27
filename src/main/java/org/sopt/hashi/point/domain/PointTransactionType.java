package org.sopt.hashi.point.domain;

import lombok.Getter;

/**
 * 포인트 거래 유형(ERD point_transaction.type — 적립/차감/복원/소멸).
 * 금액(amount)은 항상 양수로 기록하고, 잔액에 더할지 뺄지는 이 유형이 결정한다.
 */
@Getter
public enum PointTransactionType {

    EARN("적립 — 잔액 증가"),
    USE("차감 — 잔액 감소"),
    RESTORE("복원 — 차감 취소로 잔액 증가"),
    RECLAIM("리뷰 보상 즉시 회수"),
    DEFER_RECLAIM("리뷰 보상 미회수액 발생"),
    OFFSET_RECLAIM("신규 적립에서 미회수액 상계"),
    FORFEIT("소멸 — 회원 탈퇴로 잔액 전액 감소");

    private final String description;

    PointTransactionType(String description) {
        this.description = description;
    }
}
