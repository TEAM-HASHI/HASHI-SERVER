package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MapCoordinatesTest {

    @ParameterizedTest
    @CsvSource({"-90,-180", "90,180", "0,0", "35.1234560,139.6543210"})
    void 세계_경계와_영점은_유효하고_불필요한_후행_영은_제거한다(String latitude, String longitude) {
        MapCoordinates point = MapCoordinates.of(new BigDecimal(latitude), new BigDecimal(longitude));
        assertThat(point.getLatitude().scale()).isEqualTo(6);
        assertThat(point.getLongitude().scale()).isEqualTo(6);
        assertThat(point.getLatitude()).isEqualByComparingTo(latitude);
        assertThat(point.getLongitude()).isEqualByComparingTo(longitude);
    }

    @ParameterizedTest
    @CsvSource({"90.000001,0", "-90.000001,0", "0,180.000001", "0,-180.000001",
            "35.1234567,139", "35,139.1234567", "139,35"})
    void 범위_초과와_정밀도_손실과_뒤집힌_도쿄_좌표를_거절한다(String latitude, String longitude) {
        assertThatThrownBy(() -> MapCoordinates.of(new BigDecimal(latitude), new BigDecimal(longitude)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 한쪽만_있거나_모두_없는_좌표_객체를_만들_수_없다() {
        assertThatThrownBy(() -> MapCoordinates.of(null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MapCoordinates.of(BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MapCoordinates.of(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity"})
    void 비유한_문자열은_BigDecimal_좌표로_변환되지_않는다(String value) {
        assertThatThrownBy(() -> MapCoordinates.of(new BigDecimal(value), BigDecimal.ZERO))
                .isInstanceOf(NumberFormatException.class);
    }
}
