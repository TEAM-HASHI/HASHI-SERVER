package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

/**
 * Restaurant가 소유하는 선택적 위치. 변경은 Restaurant를 통해 수행한다.
 * requestId는 같은 주소의 이전 요청을 구별하며 worker lease를 대신하지 않는다.
 */
@Getter
@Entity
@Table(name = "restaurant_location")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantLocation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Embedded
    private MapCoordinates coordinates;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RestaurantLocationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    private RestaurantLocationSource source;

    @Column(name = "address_revision", nullable = false)
    private long addressRevision;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", length = 36, nullable = false)
    private UUID requestId;

    @Column(name = "obtained_at")
    private LocalDateTime obtainedAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    static RestaurantLocation pending() {
        RestaurantLocation location = new RestaurantLocation();
        location.addressRevision = 1;
        location.beginPending();
        return location;
    }

    /** 시각은 UTC DATETIME(6). 정확히 validUntil이면 이미 만료다. */
    public boolean isUsable(Clock clock) {
        return status == RestaurantLocationStatus.READY && coordinates != null
                && utcNow(clock).isBefore(validUntil);
    }

    void addressChanged() {
        addressRevision = Math.incrementExact(addressRevision);
        beginPending();
    }

    void requestRetry() {
        if (status == RestaurantLocationStatus.READY) {
            throw new IllegalStateException("준비된 위치는 갱신 절차를 사용해야 합니다");
        }
        if (status != RestaurantLocationStatus.PENDING) {
            beginPending();
        }
    }

    void beginRefresh() {
        if (status != RestaurantLocationStatus.READY) {
            throw new IllegalStateException("준비된 위치만 갱신할 수 있습니다");
        }
        beginPending();
    }

    boolean beginScheduledRetry(Clock clock) {
        boolean due = status == RestaurantLocationStatus.RETRY_WAIT
                && !utcNow(clock).isBefore(nextAttemptAt);
        if (due) {
            beginPending();
        }
        return due;
    }

    boolean complete(long expectedRevision, UUID expectedRequestId, MapCoordinates coordinates,
                     RestaurantLocationSource source, LocalDateTime obtainedAt,
                     LocalDateTime validUntil, Clock clock) {
        if (!matchesPending(expectedRevision, expectedRequestId)) {
            return false;
        }
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(source, "source");
        LocalDateTime obtained = toMicros(obtainedAt);
        LocalDateTime until = toMicros(validUntil);
        LocalDateTime now = utcNow(clock);
        boolean validLifetime = !obtained.isAfter(now) && until.isAfter(now) && until.isAfter(obtained);
        if (!validLifetime) {
            throw new IllegalArgumentException("취득 시각과 만료 시각이 올바르지 않습니다");
        }
        this.coordinates = coordinates;
        this.source = source;
        this.obtainedAt = obtained;
        this.validUntil = until;
        this.status = RestaurantLocationStatus.READY;
        return true;
    }

    boolean defer(long expectedRevision, UUID expectedRequestId, LocalDateTime nextAttemptAt, Clock clock) {
        if (!matchesPending(expectedRevision, expectedRequestId)) {
            return false;
        }
        LocalDateTime next = toMicros(nextAttemptAt);
        if (!next.isAfter(utcNow(clock))) {
            throw new IllegalArgumentException("재시도 시각은 현재보다 늦어야 합니다");
        }
        this.nextAttemptAt = next;
        this.status = RestaurantLocationStatus.RETRY_WAIT;
        return true;
    }

    boolean reject(long expectedRevision, UUID expectedRequestId, RestaurantLocationStatus outcome) {
        if (!matchesPending(expectedRevision, expectedRequestId)) {
            return false;
        }
        boolean terminal = outcome == RestaurantLocationStatus.REVIEW_REQUIRED
                || outcome == RestaurantLocationStatus.FAILED;
        if (!terminal) {
            throw new IllegalArgumentException("확인 필요 또는 자동 처리 중단 상태만 지정할 수 있습니다");
        }
        this.status = outcome;
        return true;
    }

    private boolean matchesPending(long expectedRevision, UUID expectedRequestId) {
        return status == RestaurantLocationStatus.PENDING && addressRevision == expectedRevision
                && requestId.equals(expectedRequestId);
    }

    private void beginPending() {
        this.status = RestaurantLocationStatus.PENDING;
        this.requestId = UUID.randomUUID();
        this.coordinates = null;
        this.source = null;
        this.obtainedAt = null;
        this.validUntil = null;
        this.nextAttemptAt = null;
    }

    private static LocalDateTime utcNow(Clock clock) {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static LocalDateTime toMicros(LocalDateTime time) {
        return Objects.requireNonNull(time, "time").truncatedTo(ChronoUnit.MICROS);
    }
}
