package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 모든 서버가 공유하는 운영 제어 행. 배포/재시작에서 운영값을 덮어쓰지 않는다. */
@Entity
@Table(name = "restaurant_geocoding_budget")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GeocodingBudget {
    @Id
    private Long id;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private int dailyLimit;
    @Column(nullable = false)
    private int maxConcurrent;
    private LocalDate budgetDay;
    @Column(nullable = false)
    private int reservedCalls;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime blockedUntil;

    public boolean reserve(LocalDateTime now, long running) {
        if (!enabled || running >= maxConcurrent || (blockedUntil != null && now.isBefore(blockedUntil))) {
            return false;
        }
        if (budgetDay == null || budgetDay.isBefore(now.toLocalDate())) {
            budgetDay = now.toLocalDate();
            reservedCalls = 0;
        }
        if (!budgetDay.equals(now.toLocalDate()) || reservedCalls >= dailyLimit) {
            return false;
        }
        reservedCalls++;
        return true;
    }

    public void blockUntil(LocalDateTime until) {
        if (blockedUntil == null || blockedUntil.isBefore(until)) {
            blockedUntil = until;
        }
    }
}
