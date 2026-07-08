package org.sopt.hashi.point.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;
import org.sopt.hashi.point.PointSourceType;

/**
 * 포인트 거래 원장 — 잔액을 바꾼 모든 사건을 append-only로 기록한다(계정 애그리거트의 자식, 수정·삭제 없음).
 * 출처(sourceType·sourceId)로 "어떤 건 때문에 변동했는지"를 추적하며,
 * (type, source_type, source_id) 유니크 제약으로 같은 건의 중복 차감·중복 복원을 DB 수준에서 차단한다(멱등 백스톱).
 */
@Getter
@Entity
@Table(name = "point_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uk_point_tx_type_source",
                columnNames = {"type", "source_type", "source_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointTransaction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "point_account_id", nullable = false)
    private Long pointAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private PointTransactionType type;

    /** 변동량 — 항상 양수. 증감 방향은 type이 결정한다. */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "reason", length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 20, nullable = false)
    private PointSourceType sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    private PointTransaction(Long pointAccountId, PointTransactionType type, long amount,
                             String reason, PointSourceType sourceType, Long sourceId) {
        this.pointAccountId = pointAccountId;
        this.type = type;
        this.amount = amount;
        this.reason = reason;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    public static PointTransaction earn(Long pointAccountId, long amount, String reason,
                                        PointSourceType sourceType, Long sourceId) {
        return new PointTransaction(pointAccountId, PointTransactionType.EARN, amount, reason, sourceType, sourceId);
    }

    public static PointTransaction use(Long pointAccountId, long amount, String reason,
                                       PointSourceType sourceType, Long sourceId) {
        return new PointTransaction(pointAccountId, PointTransactionType.USE, amount, reason, sourceType, sourceId);
    }

    /** 복원 기록 — 금액은 원본 차감 기록의 금액을 그대로 쓴다. */
    public static PointTransaction restore(Long pointAccountId, long amount,
                                           PointSourceType sourceType, Long sourceId) {
        return new PointTransaction(pointAccountId, PointTransactionType.RESTORE, amount, null, sourceType, sourceId);
    }
}
