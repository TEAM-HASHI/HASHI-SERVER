package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MapRegionTest {

    @Test
    void 지역은_합성_대표_위치와_범위를_갖고_명시적으로_활성화한다() {
        MapRegion region = region("FIXTURE_AREA", "합성 지역", 0);
        assertThat(region.isActive()).isFalse();
        region.activate();
        assertThat(region.isActive()).isTrue();
        region.deactivate();
        assertThat(region.isActive()).isFalse();
        assertThat(region.getCameraBounds().contains(region.getClusterPosition())).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"lower_case", "1AREA", "AREA-1", "AREA ", "한글"})
    void 불안정한_지역_코드를_거절한다(String code) {
        assertThatThrownBy(() -> region(code, "합성 지역", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n", "\r\n", "\t \n", "\u001C", "\u0085",
            "\u00A0", "\u2007", "\u202F", "\u3000", "\u001C\u00A0"})
    void 공백_문자로만_된_지역명을_거절한다(String name) {
        assertThatThrownBy(() -> region("AREA", name, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 범위_밖_대표_위치와_빈_이름과_초과_길이와_음수_순서를_거절한다() {
        assertThatThrownBy(() -> region("A".repeat(41), "지역", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> region("AREA", " ", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> region("AREA", "가".repeat(101), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> region("AREA", "지역", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MapRegion.create("AREA", "지역", point("12", "20"), bounds(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MapRegion region(String code, String name, int order) {
        return MapRegion.create(code, name, point("10", "20"), bounds(), order);
    }

    private MapCoordinates point(String latitude, String longitude) {
        return MapCoordinates.of(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    private MapBounds bounds() {
        return MapBounds.of(new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("20"), new BigDecimal("21"));
    }
}
