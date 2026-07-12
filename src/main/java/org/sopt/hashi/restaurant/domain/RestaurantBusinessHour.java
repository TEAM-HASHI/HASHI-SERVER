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

        if (!openTime.isBefore(closeTime)) {
            throw new IllegalArgumentException("Open time must be before close time.");
        }

        if ((breakStart == null) != (breakEnd == null)) {
            throw new IllegalArgumentException("Break start and end time must be provided together.");
        }

        if (breakStart != null && (!breakStart.isBefore(breakEnd)
                || breakStart.isBefore(openTime) || breakEnd.isAfter(closeTime))) {
            throw new IllegalArgumentException("Break time must be within business hours.");
        }
    }

    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }
}
