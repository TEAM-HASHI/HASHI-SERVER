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

    @Column(name = "last_order_time")
    private LocalTime lastOrderTime;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    private RestaurantBusinessHour(DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime,
                                   LocalTime lastOrderTime, boolean closed) {
        this.dayOfWeek = dayOfWeek;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.lastOrderTime = lastOrderTime;
        this.closed = closed;
    }

    public static RestaurantBusinessHour create(DayOfWeek dayOfWeek, LocalTime openTime, LocalTime closeTime,
                                                LocalTime lastOrderTime, boolean closed) {
        return new RestaurantBusinessHour(dayOfWeek, openTime, closeTime, lastOrderTime, closed);
    }

    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }
}
