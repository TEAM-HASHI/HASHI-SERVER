package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MapBoundsTest {

    @ParameterizedTest
    @CsvSource({"10,20,true", "11,21,true", "10.5,20.5,true", "9.999999,20,false",
            "11.000001,20,false", "10,19.999999,false", "10,21.000001,false"})
    void 네_경계를_포함하고_바깥_좌표는_제외한다(String latitude, String longitude, boolean expected) {
        MapBounds bounds = bounds("10", "11", "20", "21");
        assertThat(bounds.contains(MapCoordinates.of(new BigDecimal(latitude), new BigDecimal(longitude))))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"11,10,20,21", "10,10,20,21", "10,11,21,20", "10,11,20,20",
            "10,11,179,-179", "-91,0,0,1", "0,91,0,1", "0,1,-181,0", "0,1,0,181",
            "0.0000001,1,0,1", ",1,0,1"})
    void 역전과_빈_면적과_날짜변경선과_잘못된_좌표를_거절한다(
            String south, String north, String west, String east) {
        assertThatThrownBy(() -> bounds(south, north, west, east)).isInstanceOf(IllegalArgumentException.class);
    }

    private MapBounds bounds(String south, String north, String west, String east) {
        return MapBounds.of(south == null ? null : new BigDecimal(south), new BigDecimal(north),
                new BigDecimal(west), new BigDecimal(east));
    }
}
