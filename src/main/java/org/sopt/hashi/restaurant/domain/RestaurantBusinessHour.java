package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "restaurant_business_hour")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantBusinessHour {

    private static final long MINUTES_PER_DAY = 24 * 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 10, nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(name = "break_start")
    private LocalTime breakStart;

    @Column(name = "break_end")
    private LocalTime breakEnd;

    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    private RestaurantBusinessHour(DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime,
                                   LocalTime breakStart, LocalTime breakEnd, boolean closed) {
        validate(dayOfWeek, openTime, closeTime, breakStart, breakEnd, closed);
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.breakStart = breakStart;
        this.breakEnd = breakEnd;
        this.closed = closed;
    }

    public static RestaurantBusinessHour create(DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime,
                                                LocalTime breakStart, LocalTime breakEnd, boolean closed) {
        return new RestaurantBusinessHour(dayOfWeek, openTime, closeTime, breakStart, breakEnd, closed);
    }

    private static void validate(DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime,
                                 LocalTime breakStart, LocalTime breakEnd, boolean closed) {
        Objects.requireNonNull(dayOfWeek, "dayOfWeek is required");

        if (closed) {
            if (openTime != null || closeTime != null || breakStart != null || breakEnd != null) {
                throw new IllegalArgumentException("Closed day cannot have business hours.");
            }
            return;
        }

        if (openTime == null || closeTime == null) {
            throw new IllegalArgumentException("Open and close time are required for business day.");
        }

        if ((breakStart == null) != (breakEnd == null)) {
            throw new IllegalArgumentException("Break start and end time must be provided together.");
        }

        if (breakStart != null) {
            long businessMinutes = businessMinutes(openTime, closeTime);
            long breakStartOffset = minutesFromOpen(openTime, breakStart);
            long breakEndOffset = minutesFromOpen(openTime, breakEnd);
            if (breakStartOffset >= breakEndOffset || breakEndOffset > businessMinutes) {
                throw new IllegalArgumentException("Break time must be within business hours.");
            }
        }
    }

    // closeTime이 openTime보다 이르면 익일 마감(자정 넘김), 같으면 24시간 영업으로 해석한다.
    private static long businessMinutes(LocalTime openTime, LocalTime closeTime) {
        long minutes = Duration.between(openTime, closeTime).toMinutes();
        return minutes <= 0 ? minutes + MINUTES_PER_DAY : minutes;
    }

    private static long minutesFromOpen(LocalTime openTime, LocalTime time) {
        long minutes = Duration.between(openTime, time).toMinutes();
        return minutes < 0 ? minutes + MINUTES_PER_DAY : minutes;
    }

    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }
}
