package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class RestaurantBusinessHourTest {

    @Test
    void 영업일은_영업_시작과_종료_시간이_필수다() {
        assertThatThrownBy(() -> RestaurantBusinessHour.create(
                DayOfWeek.MONDAY,
                null,
                LocalTime.of(22, 0),
                LocalTime.of(21, 0),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 휴무일은_영업_시간을_가질_수_없다() {
        assertThatThrownBy(() -> RestaurantBusinessHour.create(
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(22, 0),
                null,
                true
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 마지막_주문_시간은_영업_시간_범위_안에_있어야_한다() {
        assertThatThrownBy(() -> RestaurantBusinessHour.create(
                DayOfWeek.MONDAY,
                LocalTime.of(10, 0),
                LocalTime.of(22, 0),
                LocalTime.of(23, 0),
                false
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
