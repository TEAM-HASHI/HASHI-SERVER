package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 주소/Google 응답을 복제하지 않는 durable 작업. 자동 재시도는 같은 행의 attempt를 이어간다. */
@Entity
@Table(name = "restaurant_location_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantLocationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long restaurantId;
    @Column(nullable = false)
    private long addressRevision;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false, unique = true)
    private UUID requestId;
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private State state;
    @Column(nullable = false)
    private int attempt;
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime nextAttemptAt;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID leaseToken;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime leaseUntil;
    /** 대체된 작업도 기존 호출의 실행 슬롯을 기한까지 점유한다. */
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime reservedUntil;
    @Column(length = 40)
    private String failureCode;
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime updatedAt;

    public static RestaurantLocationJob pending(Restaurant restaurant, LocalDateTime now) {
        RestaurantLocationJob job = new RestaurantLocationJob();
        job.restaurantId = restaurant.getId();
        job.addressRevision = restaurant.getLocation().getAddressRevision();
        job.requestId = restaurant.getLocation().getRequestId();
        job.state = State.PENDING;
        job.nextAttemptAt = now;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public boolean isCurrent(Restaurant restaurant) {
        RestaurantLocation location = restaurant.getLocation();
        return !restaurant.isDeleted() && location != null
                && restaurantId.equals(restaurant.getId())
                && addressRevision == location.getAddressRevision()
                && requestId.equals(location.getRequestId());
    }

    public boolean isDue(LocalDateTime now) {
        return switch (state) {
            case PENDING -> reservedUntil == null || !reservedUntil.isAfter(now);
            case RETRY_WAIT -> !nextAttemptAt.isAfter(now)
                    && (reservedUntil == null || !reservedUntil.isAfter(now));
            case LEASED -> !leaseUntil.isAfter(now);
            default -> false;
        };
    }

    public boolean ownsLease(UUID token, LocalDateTime now) {
        return state == State.LEASED && leaseToken.equals(token) && now.isBefore(leaseUntil);
    }

    public void claim(UUID currentRequestId, LocalDateTime now, LocalDateTime until) {
        requestId = currentRequestId;
        state = State.LEASED;
        leaseToken = UUID.randomUUID();
        leaseUntil = until;
        reservedUntil = until;
        attempt = Math.incrementExact(attempt);
        updatedAt = now;
    }

    public void defer(String code, LocalDateTime next, LocalDateTime now) {
        LocalDateTime outstandingReservation = reservedUntil;
        finish(State.RETRY_WAIT, code, now);
        nextAttemptAt = next;
        if ("CANCELLED".equals(code) || "TIMEOUT".equals(code)) {
            reservedUntil = outstandingReservation;
        }
    }

    public void followRequest(UUID currentRequestId) {
        requestId = currentRequestId;
    }

    public void finish(State outcome, String code, LocalDateTime now) {
        LocalDateTime outstandingReservation = reservedUntil;
        state = outcome;
        failureCode = code;
        leaseToken = null;
        leaseUntil = null;
        reservedUntil = null;
        if ("ATTEMPTS_EXHAUSTED".equals(code)) {
            reservedUntil = outstandingReservation;
        }
        updatedAt = now;
    }

    public void supersede(LocalDateTime now) {
        LocalDateTime outstandingReservation = reservedUntil;
        finish(State.SUPERSEDED, "SUPERSEDED", now);
        reservedUntil = outstandingReservation;
    }

    public enum State {
        PENDING, LEASED, RETRY_WAIT, SUCCEEDED, REVIEW_REQUIRED, FAILED, SUPERSEDED
    }
}
