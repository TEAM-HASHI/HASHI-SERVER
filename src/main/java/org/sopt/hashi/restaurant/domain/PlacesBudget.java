package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** SEARCH와 DETAILS가 서로 예산을 잠식하지 않도록 operation별로 공유하는 DB 제어 행. */
@Entity
@Table(name = "restaurant_places_budget")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlacesBudget {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Operation operation;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private int dailyLimit;
    @Column(nullable = false)
    private int minuteLimit;
    private LocalDate budgetDay;
    @Column(nullable = false)
    private int dailyUsed;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime minuteWindowStart;
    @Column(nullable = false)
    private int minuteUsed;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime blockedUntil;

    public boolean canReserve(LocalDateTime now) {
        if (!enabled || dailyLimit <= 0 || minuteLimit <= 0
                || (blockedUntil != null && now.isBefore(blockedUntil))) {
            return false;
        }
        int effectiveDaily = budgetDay == null || budgetDay.isBefore(now.toLocalDate()) ? 0 : dailyUsed;
        LocalDateTime minute = now.truncatedTo(ChronoUnit.MINUTES);
        int effectiveMinute = minuteWindowStart == null || minuteWindowStart.isBefore(minute) ? 0 : minuteUsed;
        return effectiveDaily < dailyLimit && effectiveMinute < minuteLimit;
    }

    /** 호출 직전에 예약하며 provider 결과나 동시 주소 변경과 관계없이 환불하지 않는다. */
    public boolean reserve(LocalDateTime now) {
        if (!canReserve(now)) {
            return false;
        }
        if (budgetDay == null || budgetDay.isBefore(now.toLocalDate())) {
            budgetDay = now.toLocalDate();
            dailyUsed = 0;
        }
        LocalDateTime minute = now.truncatedTo(ChronoUnit.MINUTES);
        if (minuteWindowStart == null || minuteWindowStart.isBefore(minute)) {
            minuteWindowStart = minute;
            minuteUsed = 0;
        }
        dailyUsed++;
        minuteUsed++;
        return true;
    }

    public void blockUntil(LocalDateTime until) {
        if (blockedUntil == null || blockedUntil.isBefore(until)) {
            blockedUntil = until;
        }
    }

    public enum Operation {
        SEARCH, DETAILS
    }
}
