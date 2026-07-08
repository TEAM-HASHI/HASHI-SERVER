package org.sopt.hashi.point.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;
import org.sopt.hashi.point.code.PointErrorCode;
import org.sopt.hashi.shared.error.BusinessException;

/**
 * 포인트 계정 애그리거트 루트 — 사용자당 1개, 현재 잔액을 보관한다. 회원은 타 모듈 소유라 userId(Long)로만 참조한다(§5).
 * 동시 차감의 잔액 정합은 낙관적 락(version)으로 보호한다(§8). 잔액의 근거(역사)는 {@link PointTransaction} 원장이 담당한다.
 */
@Getter
@Entity
@Table(name = "point_account",
        uniqueConstraints = @UniqueConstraint(name = "uk_point_account_user", columnNames = "user_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private PointAccount(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }

    /** 잔액 0으로 계정을 연다(최초 포인트 발생 시 lazy 생성). */
    public static PointAccount open(Long userId) {
        return new PointAccount(userId);
    }

    public void earn(long amount) {
        this.balance += amount;
    }

    /** 잔액 불변식 — 보유량을 초과해 차감할 수 없다(음수 잔액 금지). */
    public void use(long amount) {
        if (balance < amount) {
            throw new BusinessException(PointErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance -= amount;
    }

    public void restore(long amount) {
        this.balance += amount;
    }
}
